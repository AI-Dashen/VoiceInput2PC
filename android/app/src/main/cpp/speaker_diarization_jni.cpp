// Local-only diarization. Pinned sherpa-onnx C ABI; no ASR runtime replacement.
#include <sherpa-onnx/c-api/c-api.h>
#include <jni.h>
#include <cmath>
#include <cstdint>
#include <fstream>
#include <memory>
#include <mutex>
#include <sstream>
#include <stdexcept>
#include <vector>
namespace {
std::mutex mutex;
std::string run(const std::string& dir,const std::string& pcm,int speakers,float threshold) {
 std::lock_guard<std::mutex> lock(mutex);
 if (speakers<0||speakers>32||!std::isfinite(threshold)||threshold<=0||threshold>=2) throw std::runtime_error("DIARIZATION_PARAMETERS_INVALID");
 std::ifstream in(pcm,std::ios::binary|std::ios::ate);
 auto bytes=in.tellg();
 if(!in||bytes<=0||bytes%2||bytes>16000LL*2*3600) throw std::runtime_error("PCM_16K_MONO_LENGTH_INVALID_MAX_60_MIN");
 in.seekg(0); std::vector<float> samples(static_cast<size_t>(bytes)/2);
 // Read in bounded blocks, avoiding a second full-size PCM allocation.
 int16_t block[8192]; size_t offset=0;
 while(in.read(reinterpret_cast<char*>(block),sizeof(block))||in.gcount()) {
  const auto n=in.gcount()/2;if((in.gcount()&1)||offset+static_cast<size_t>(n)>samples.size()) throw std::runtime_error("PCM_CHANGED_DURING_READ");for(int i=0;i<n;i++) samples[offset++]=block[i]/32768.0f;
 }
 if(offset!=samples.size()) throw std::runtime_error("PCM_READ_FAILED");
 const auto seg=dir+"/model.int8.onnx",emb=dir+"/embedding.onnx";
 SherpaOnnxOfflineSpeakerDiarizationConfig c{};
 c.segmentation.pyannote.model=seg.c_str(); c.segmentation.pyannote.window_shift_ratio=0.1f;
 c.segmentation.num_threads=2;c.segmentation.provider="cpu";
 c.embedding.model=emb.c_str();c.embedding.num_threads=2;c.embedding.provider="cpu";
 c.clustering.num_clusters=speakers>0?speakers:-1;c.clustering.threshold=threshold;c.clustering.compute_confidence=1;
 c.min_duration_on=0.3f;c.min_duration_off=0.5f;
 auto sd=SherpaOnnxCreateOfflineSpeakerDiarization(&c);
 if(!sd) throw std::runtime_error("DIARIZATION_MODEL_INIT_FAILED");
 std::unique_ptr<const SherpaOnnxOfflineSpeakerDiarization,decltype(&SherpaOnnxDestroyOfflineSpeakerDiarization)> engine(sd,SherpaOnnxDestroyOfflineSpeakerDiarization);
 if(SherpaOnnxOfflineSpeakerDiarizationGetSampleRate(sd)!=16000) throw std::runtime_error("DIARIZATION_SAMPLE_RATE_UNEXPECTED");
 auto r=SherpaOnnxOfflineSpeakerDiarizationProcess(sd,samples.data(),samples.size());
 if(!r) throw std::runtime_error("DIARIZATION_PROCESS_FAILED");
 std::unique_ptr<const SherpaOnnxOfflineSpeakerDiarizationResult,decltype(&SherpaOnnxOfflineSpeakerDiarizationDestroyResult)> result(r,SherpaOnnxOfflineSpeakerDiarizationDestroyResult);
 const int count=SherpaOnnxOfflineSpeakerDiarizationResultGetNumSegments(r);
 auto s=SherpaOnnxOfflineSpeakerDiarizationResultSortByStartTime(r);
 std::unique_ptr<const SherpaOnnxOfflineSpeakerDiarizationSegment,decltype(&SherpaOnnxOfflineSpeakerDiarizationDestroySegment)> segments(s,SherpaOnnxOfflineSpeakerDiarizationDestroySegment);
 if(count>0&&!s) throw std::runtime_error("DIARIZATION_SEGMENTS_MISSING");
 std::ostringstream out;out<<"[";
 for(int i=0;i<count;i++) {
  bool overlap=false;for(int j=i-1;j>=0;j--) {if(s[j].end>s[i].start&&s[j].speaker!=s[i].speaker){overlap=true;break;}}
  for(int j=i+1;j<count&&s[j].start<s[i].end;j++) if(s[j].speaker!=s[i].speaker) overlap=true;
  const bool uncertain=overlap||s[i].confidence<0.25f||!std::isfinite(s[i].confidence);
  if(i)out<<',';out<<"{\"start_ms\":"<<std::llround(s[i].start*1000)<<",\"end_ms\":"<<std::llround(s[i].end*1000)<<",\"speaker\":"<<s[i].speaker<<",\"uncertain\":"<<(uncertain?"true":"false")<<",\"confidence\":"<<(std::isfinite(s[i].confidence)?s[i].confidence:-2)<<'}';
 }
 out<<"]";return out.str();
}
std::string str(JNIEnv*e,jstring s){if(!s)throw std::runtime_error("NULL_ARGUMENT");const char*p=e->GetStringUTFChars(s,nullptr);if(!p)throw std::runtime_error("STRING_ACCESS_FAILED");std::string v(p);e->ReleaseStringUTFChars(s,p);return v;}
}
extern "C" JNIEXPORT jstring JNICALL Java_io_github_amxooo_voiceinput2pc_SpeakerDiarizationNative_diarize(JNIEnv* e,jclass,jstring dir,jstring pcm,jint speakers,jfloat threshold){
 try{return e->NewStringUTF(run(str(e,dir),str(e,pcm),speakers,threshold).c_str());}
 catch(const std::exception&x){e->ThrowNew(e->FindClass("java/lang/IllegalStateException"),x.what());return nullptr;}
 catch(...){e->ThrowNew(e->FindClass("java/lang/IllegalStateException"),"DIARIZATION_NATIVE_FAILED");return nullptr;}
}
#ifdef SPEAKER_DIARIZATION_CLI
#include <iostream>
int main(int argc,char**argv){try{if(argc<3)return 2;std::cout<<run(argv[1],argv[2],argc>3?std::stoi(argv[3]):0,argc>4?std::stof(argv[4]):0.9f)<<std::endl;return 0;}catch(const std::exception&e){std::cerr<<e.what()<<std::endl;return 1;}}
#endif
