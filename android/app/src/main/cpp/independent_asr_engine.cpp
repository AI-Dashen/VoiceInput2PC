#include "independent_asr_engine.h"
#include "independent_decoder_runtime.h"
#include "independent_frontend_params.h"
#include "asr_transcript_postprocessor.h"
#include <android/log.h>
#include <MNN/Interpreter.hpp>
#include <MNN/Tensor.hpp>
#include <MNN/expr/Executor.hpp>
#include <MNN/expr/ExprCreator.hpp>
#include <MNN/expr/Module.hpp>
#include "core/KVMeta.hpp"
#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <cstring>
#include <cstdlib>
#include <cstdio>
#include <fstream>
#include <limits>
#include <memory>
#include <sstream>
#include <stdexcept>
#include <unordered_map>
#include <vector>

extern "C" {
int frontend_preprocess_reference(float*,int32_t,const float*,int32_t,int32_t,int32_t,int32_t,float*,float);
int fft512_radix4_reference(const float*,float*);
int frontend_power_reference(const float*,int32_t,int32_t,float*,int32_t,int32_t);
int frontend_mel_bank_reference(float*,int32_t);
int frontend_mel_apply_reference(const float*,const float*,float*);
int frontend_floor_reference(float*,int32_t,float,float);
int frontend_log_reference(float*,int32_t);
}

namespace mobile_asr {
namespace {
constexpr int kWidth=768, kVocab=64000, kMaxContext=256;
std::string join(const std::string& a,const char* b){return a.empty()?std::string(b):a+"/"+b;}
bool regular(const std::string& p){std::ifstream f(p,std::ios::binary);return f.good();}
std::vector<std::string> read_lines(const std::string& path){
 std::ifstream f(path);if(!f)throw std::runtime_error("VOCAB_OPEN_FAILED");std::vector<std::string> out;std::string s;
 while(std::getline(f,s)){if(!s.empty()&&s.back()=='\r')s.pop_back();out.push_back(s);}return out;
}
std::vector<uint32_t> codepoints(const std::string& text){
 std::vector<uint32_t> out;for(size_t i=0;i<text.size();){uint8_t c=text[i++];uint32_t cp;int n;
  if(c<0x80){cp=c;n=0;}else if((c&0xe0)==0xc0){cp=c&0x1f;n=1;}else if((c&0xf0)==0xe0){cp=c&0x0f;n=2;}else if((c&0xf8)==0xf0){cp=c&7;n=3;}else throw std::runtime_error("VOCAB_UTF8_INVALID");
  if(i+n>text.size())throw std::runtime_error("VOCAB_UTF8_TRUNCATED");for(int j=0;j<n;j++){uint8_t d=text[i++];if((d&0xc0)!=0x80)throw std::runtime_error("VOCAB_UTF8_CONTINUATION");cp=(cp<<6)|(d&0x3f);}out.push_back(cp);
 }return out;
}
std::unordered_map<uint32_t,uint8_t> byte_decoder(){
 std::vector<int> bytes;for(int i=33;i<=126;i++)bytes.push_back(i);for(int i=161;i<=172;i++)bytes.push_back(i);for(int i=174;i<=255;i++)bytes.push_back(i);
 std::vector<uint32_t> cps(bytes.begin(),bytes.end());int extra=0;for(int i=0;i<256;i++)if(std::find(bytes.begin(),bytes.end(),i)==bytes.end()){bytes.push_back(i);cps.push_back(256+extra++);}std::unordered_map<uint32_t,uint8_t> result;for(size_t i=0;i<bytes.size();i++)result[cps[i]]=static_cast<uint8_t>(bytes[i]);return result;
}
MNN::ScheduleConfig cpu_config(MNN::BackendConfig& backend){backend.precision=MNN::BackendConfig::Precision_High;backend.memory=MNN::BackendConfig::Memory_Normal;backend.power=MNN::BackendConfig::Power_High;MNN::ScheduleConfig c;c.type=MNN_FORWARD_CPU;c.numThread=2;c.backendConfig=&backend;return c;}
std::unique_ptr<MNN::Interpreter> load_model(const std::string& model,const std::string& weight){
 std::unique_ptr<MNN::Interpreter> net(MNN::Interpreter::createFromFile(model.c_str()));if(!net)throw std::runtime_error("MNN_MODEL_OPEN_FAILED:"+model);net->setExternalFile(weight.c_str());return net;
}
}

struct Engine::Impl {
 std::string dir,last_error;std::vector<std::string> vocab;std::unordered_map<uint32_t,uint8_t> byte_map;bool ok=false;
 std::unique_ptr<IndependentDecoderRuntime> decoder;
 std::ifstream logit_weight;
 std::unique_ptr<MNN::Interpreter> logit;
 MNN::BackendConfig logit_backend;
 MNN::ScheduleConfig logit_config;
 MNN::Session* logit_session=nullptr;
 MNN::Tensor* logit_input=nullptr;
 MNN::Tensor* logit_output=nullptr;
 MNN::BackendConfig encoder_backend;
 MNN::ScheduleConfig encoder_config;
 std::shared_ptr<MNN::Express::Executor::RuntimeManager> encoder_runtime;
 std::shared_ptr<MNN::Express::Module> encoder;
 MNN::KVMeta encoder_meta;
 std::array<float,400> frontend_window{};
 std::array<float,256*80> frontend_bank{};
 bool stream_active=false;
 std::vector<int16_t> stream_pcm;
 std::vector<float> stream_s0,stream_s1,stream_hidden;
 std::size_t stream_frames=0;
 int stream_chunk=0;
 int stream_encoder_remove=0;
 explicit Impl(std::string d):dir(std::move(d)),byte_map(byte_decoder()){
  try{
   for(const char* p:{"encoder_stream.mnn","encoder.mnn.weight","decoder-runtime.bin","logit.mnn","logit.mnn.weight","vocab.txt"})if(!regular(join(dir,p)))throw std::runtime_error(std::string("ASSET_MISSING:")+p);
   auto lines=read_lines(join(dir,"vocab.txt"));if(lines.size()!=128000)throw std::runtime_error("VOCAB_LINE_COUNT");vocab.resize(kVocab);
   for(size_t i=0;i<lines.size();i+=2){int id=std::stoi(lines[i+1]);if(id<0||id>=kVocab||!vocab[id].empty())throw std::runtime_error("VOCAB_ID_INVALID");vocab[id]=lines[i];}
   encoder_backend.precision=MNN::BackendConfig::Precision_High;encoder_backend.memory=MNN::BackendConfig::Memory_Normal;encoder_backend.power=MNN::BackendConfig::Power_High;encoder_config.type=MNN_FORWARD_CPU;encoder_config.numThread=2;encoder_config.backendConfig=&encoder_backend;
   encoder_runtime.reset(MNN::Express::Executor::RuntimeManager::createRuntimeManager(encoder_config),MNN::Express::Executor::RuntimeManager::destroy);if(!encoder_runtime)throw std::runtime_error("ENCODER_RUNTIME");encoder_runtime->setHintPtr(MNN::Interpreter::KVCACHE_INFO,&encoder_meta);encoder_runtime->setHint(MNN::Interpreter::ATTENTION_OPTION,0);encoder_runtime->setExternalFile(join(dir,"encoder.mnn.weight"));
   encoder.reset(MNN::Express::Module::load({"_offset_0","_s_1","_s_0","fbank","mask"},{"encoder_out","Unsqueeze2___tr4audio_encoder.acoustic_front_end_module.conv.0.out_slice","BinaryOp11___tr4audio_encoder.acoustic_front_end_module.conv.2.out_slice"},join(dir,"encoder_stream.mnn").c_str(),encoder_runtime),MNN::Express::Module::destroy);if(!encoder)throw std::runtime_error("ENCODER_MODULE");auto encoder_info=encoder->getInfo();if(!encoder_info||encoder_info->inputs.size()!=5)throw std::runtime_error("ENCODER_INFO");
   decoder=std::make_unique<IndependentDecoderRuntime>(join(dir,"decoder-runtime.bin"));if(!decoder->ready())throw std::runtime_error(decoder->error());
   logit_weight.open(join(dir,"logit.mnn.weight"),std::ios::binary);if(!logit_weight)throw std::runtime_error("EMBED_WEIGHT_OPEN");
   logit=load_model(join(dir,"logit.mnn"),join(dir,"logit.mnn.weight"));logit_config=cpu_config(logit_backend);logit_session=logit->createSession(logit_config);if(!logit_session)throw std::runtime_error("LOGIT_SESSION");
   logit_input=logit->getSessionInput(logit_session,"hidden_state");if(!logit_input)throw std::runtime_error("LOGIT_INPUT");logit->resizeTensor(logit_input,{1,1,kWidth});logit->resizeSession(logit_session);logit_output=logit->getSessionOutput(logit_session,"lm_logits");if(!logit_output)throw std::runtime_error("LOGIT_OUTPUT");
   for(int i=0;i<400;i++)frontend_window[i]=static_cast<float>(std::pow(0.5-0.5*std::cos((i*(2.0*3.14159265358979323846))/399.0),0.85));
   if(frontend_mel_bank_reference(frontend_bank.data(),frontend_bank.size()))throw std::runtime_error("MEL_BANK_FAILED");
   stream_s0.resize(80);stream_s1.resize(10240);stream_pcm.reserve(16000*15);
   ok=true;
  }catch(const std::exception& e){last_error=e.what();}
 }
 ~Impl(){if(logit&&logit_session)logit->releaseSession(logit_session);}
 std::vector<float> frontend_chunk(const int16_t* pcm){
  std::vector<float> features(32*80);std::array<float,512> frame{};std::array<float,514> fft{};std::array<float,256> power{};std::array<float,80> mel{};
  for(int t=0;t<32;t++){
   std::fill(frame.begin(),frame.end(),0.0f);for(int i=0;i<400;i++)frame[i]=static_cast<float>(pcm[t*160+i]);float energy=0;
   if(frontend_preprocess_reference(frame.data(),1,frontend_window.data(),400,512,1,1,&energy,0.9700000286102295f)||fft512_radix4_reference(frame.data(),fft.data())||frontend_power_reference(fft.data(),1,512,power.data(),256,1)||frontend_mel_apply_reference(power.data(),frontend_bank.data(),mel.data())||frontend_floor_reference(mel.data(),80,std::ldexp(1.0f,-23),0.0f)||frontend_log_reference(mel.data(),80))throw std::runtime_error("FRONTEND_COMPUTE_FAILED");
   for(int c=0;c<80;c++){float v=(mel[c]-kFrontendMean[c])*kFrontendScale[c];if(!std::isfinite(v))throw std::runtime_error("FRONTEND_NONFINITE");features[t*80+c]=v;}
  }
  return features;
 }
 std::vector<float> embedding(int token){
  if(token<0||token>=kVocab)throw std::runtime_error("TOKEN_RANGE");constexpr std::streamoff body=22,packed_row=384,scale_base=24576022;
  std::array<uint8_t,packed_row> packed{};logit_weight.clear();logit_weight.seekg(body+static_cast<std::streamoff>(token)*packed_row);logit_weight.read(reinterpret_cast<char*>(packed.data()),packed.size());if(!logit_weight)throw std::runtime_error("EMBED_PACKED_READ");float scale;logit_weight.clear();logit_weight.seekg(scale_base+static_cast<std::streamoff>(token)*4);logit_weight.read(reinterpret_cast<char*>(&scale),4);if(!logit_weight||!std::isfinite(scale))throw std::runtime_error("EMBED_SCALE_READ");
  std::vector<float> row(kWidth);for(int i=0;i<packed_row;i++){row[2*i]=static_cast<float>(packed[i]>>4)*scale-8.0f*scale;row[2*i+1]=static_cast<float>(packed[i]&15)*scale-8.0f*scale;}return row;
 }
 std::string decode(const std::vector<int>& ids){
  std::string bytes;for(int id:ids){if(id==2)break;if(id<0||id>=kVocab)throw std::runtime_error("DECODE_ID_RANGE");const auto& token=vocab[id];if(id<=6||(id>=30&&id<=511&&token.size()>1&&token.front()=='['&&token.back()==']'))continue;if(id>=7&&id<=29){bytes+=token;continue;}for(uint32_t cp:codepoints(token)){auto it=byte_map.find(cp);if(it==byte_map.end())throw std::runtime_error("DECODE_SYMBOL_UNKNOWN");bytes.push_back(static_cast<char>(it->second));}}
  return bytes;
 }
 std::vector<float> embed_ids(std::initializer_list<int> ids){std::vector<float> result;result.reserve(ids.size()*kWidth);for(int id:ids){auto row=embedding(id);result.insert(result.end(),row.begin(),row.end());}return result;}
 void begin_stream(){
  if(stream_active)throw std::runtime_error("STREAM_ALREADY_ACTIVE");
  stream_active=true;stream_pcm.clear();stream_frames=0;stream_chunk=0;stream_encoder_remove=encoder_meta.previous;std::fill(stream_s0.begin(),stream_s0.end(),0.0f);std::fill(stream_s1.begin(),stream_s1.end(),0.0f);decoder->reset();auto prefix=embed_ids({529,23641,531});stream_hidden=decoder->append(prefix.data(),3);
 }
 void encode_ready_chunks(){
  using namespace MNN;using namespace MNN::Express;
  auto info=encoder->getInfo();if(!info||info->inputs.size()!=5)throw std::runtime_error("ENCODER_INFO");
  while(stream_pcm.size()>=400+(stream_frames+31)*160){
   auto features=frontend_chunk(stream_pcm.data()+stream_frames*160);
   auto offset=_Input(info->inputs[0].dim,info->inputs[0].order,halide_type_of<int>());offset->writeMap<int>()[0]=0;offset->writeMap<int>()[1]=stream_chunk*8;offset->writeMap<int>()[2]=0;
   auto i1=_Input(info->inputs[1].dim,info->inputs[1].order,halide_type_of<float>());std::copy(stream_s1.begin(),stream_s1.end(),i1->writeMap<float>());auto i0=_Input(info->inputs[2].dim,info->inputs[2].order,halide_type_of<float>());std::copy(stream_s0.begin(),stream_s0.end(),i0->writeMap<float>());
   auto fb=_Input(info->inputs[3].dim,info->inputs[3].order,halide_type_of<float>());std::copy(features.begin(),features.end(),fb->writeMap<float>());auto mask=_Input(info->inputs[4].dim,info->inputs[4].order,halide_type_of<int>());std::fill(mask->writeMap<int>(),mask->writeMap<int>()+32,1);
   encoder_meta.add=8;encoder_meta.remove=stream_chunk==0?stream_encoder_remove:0;auto out=encoder->onForward({offset,i1,i0,fb,mask});if(out.size()!=3)throw std::runtime_error("ENCODER_FORWARD");auto encVar=_Convert(out[0],NCHW);auto next0=_Convert(out[1],NCHW);auto next1=_Convert(out[2],NCHW);const float* enc=encVar->readMap<float>();const float* p0=next0->readMap<float>();const float* p1=next1->readMap<float>();if(!enc||!p0||!p1)throw std::runtime_error("ENCODER_MAP");std::copy(p0,p0+80,stream_s0.begin());std::copy(p1,p1+10240,stream_s1.begin());encoder_meta.sync();stream_hidden=decoder->append(enc,2);stream_frames+=32;stream_chunk++;
  }
 }
 void accept_stream(const int16_t* pcm,size_t count){
  if(!stream_active)throw std::runtime_error("STREAM_NOT_ACTIVE");if(!pcm||!count||stream_pcm.size()+count>16000u*180)throw std::runtime_error("PCM_SAMPLE_RANGE");stream_pcm.insert(stream_pcm.end(),pcm,pcm+count);encode_ready_chunks();
 }
 std::string finish_stream(){
  if(!stream_active)throw std::runtime_error("STREAM_NOT_ACTIVE");if(stream_pcm.size()<400)throw std::runtime_error("PCM_SAMPLE_RANGE");
  size_t raw=(stream_pcm.size()-400)/160+1;size_t frames=((raw+31)/32)*32;if(frames<32)frames=32;if(frames>4096)throw std::runtime_error("FRONTEND_FRAME_RANGE");size_t needed=400+(frames-1)*160;stream_pcm.resize(needed,0);encode_ready_chunks();if(stream_frames!=frames)throw std::runtime_error("STREAM_FRAME_MISMATCH");
  auto suffix=embed_ids({1912,23641,531});stream_hidden=decoder->append(suffix.data(),3);std::vector<int> generated;
  for(int step=0;step<64;step++){
   MNN::Tensor hli(logit_input,logit_input->getDimensionType());std::copy(stream_hidden.begin(),stream_hidden.end(),hli.host<float>());logit_input->copyFromHostTensor(&hli);if(logit->runSession(logit_session)!=MNN::NO_ERROR)throw std::runtime_error("LOGIT_RUN");MNN::Tensor hlo(logit_output,MNN::Tensor::TENSORFLOW);logit_output->copyToHostTensor(&hlo);const float* logits=hlo.host<float>();int token=static_cast<int>(std::max_element(logits,logits+kVocab)-logits);generated.push_back(token);if(token==2)break;auto row=embedding(token);stream_hidden=decoder->append(row.data(),1);
  }
  if(generated.empty()||generated.back()!=2)throw std::runtime_error("EOS_NOT_REACHED");
  auto normalized=normalize_transcript_tokens(generated);
  auto decoded=normalize_transcript_text(decode(normalized.ids));
  if(normalized.annotation_unwrapped||decoded.annotation_unwrapped)__android_log_print(ANDROID_LOG_INFO,"IndependentASR","normalized inline A annotation token=%d text=%d (%zu tokens)",normalized.annotation_unwrapped?1:0,decoded.annotation_unwrapped?1:0,generated.size());
  auto text=decoded.text;if(text.empty())throw std::runtime_error("EMPTY_TRANSCRIPT");stream_active=false;stream_pcm.clear();stream_hidden.clear();return text;
 }
 void cancel_stream(){stream_active=false;stream_pcm.clear();stream_hidden.clear();stream_frames=0;stream_chunk=0;decoder->reset();}
 std::string run(const int16_t* pcm,size_t samples){try{begin_stream();accept_stream(pcm,samples);return finish_stream();}catch(...){cancel_stream();throw;}}

};

Engine::Engine(std::string model_dir):impl_(std::make_unique<Impl>(std::move(model_dir))){}
Engine::~Engine()=default;
bool Engine::ready()const{return impl_&&impl_->ok;}
const std::string& Engine::error()const{return impl_->last_error;}
std::string Engine::recognize(const int16_t* pcm,size_t samples){if(!ready())throw std::runtime_error(error());return impl_->run(pcm,samples);}
void Engine::beginStream(){if(!ready())throw std::runtime_error(error());impl_->begin_stream();}
void Engine::acceptStream(const int16_t* pcm,size_t samples){if(!ready())throw std::runtime_error(error());impl_->accept_stream(pcm,samples);}
std::string Engine::finishStream(){if(!ready())throw std::runtime_error(error());return impl_->finish_stream();}
void Engine::cancelStream(){if(impl_)impl_->cancel_stream();}
}
