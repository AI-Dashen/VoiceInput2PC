#include "independent_decoder_runtime.h"
#include <algorithm>
#include <array>
#include <cmath>
#include <cstring>
#include <fstream>
#include <limits>
#include <stdexcept>

extern "C" {
int projection_lut_batch_native_fp32(const uint8_t*,size_t,const float*,size_t,const float*,size_t,const float*,size_t,size_t,size_t,size_t,size_t,const uint8_t*,size_t,float*,size_t,float,float);
int decoder_norm_batch_fp32(const float*,size_t,const float*,size_t,const float*,size_t,size_t,size_t,float,float*,size_t);
int decoder_rope_batch_fp32(const float*,size_t,const float*,size_t,const float*,size_t,const float*,size_t,size_t,size_t,size_t,float*,size_t,float*,size_t);
int incremental_attention_fp32(const float*,const float*,const float*,int,int,int,int,float,float,float*);
int decoder_residual_add_fp32(const float*,const float*,float*,size_t);
int gelu_vector_fp32(const float*,float*,size_t,const float*,size_t);
}

namespace mobile_asr {
namespace {
constexpr std::size_t W=768,H=12,D=64,L=12,MAXTOK=256,ROPEPOS=8192;
constexpr float FMAX=std::numeric_limits<float>::max();
struct Reader {
 const std::uint8_t* p; std::size_t n,o=0;
 template<class T>T scalar(){if(o+sizeof(T)>n)throw std::runtime_error("DECODER_ASSET_TRUNCATED");T v;std::memcpy(&v,p+o,sizeof(v));o+=sizeof(v);return v;}
 const std::uint8_t* raw(std::size_t count){if(count>n-o)throw std::runtime_error("DECODER_ASSET_TRUNCATED");auto q=p+o;o+=count;return q;}
 const float* floats(std::size_t count){auto q=raw(count*sizeof(float));if(reinterpret_cast<std::uintptr_t>(q)%alignof(float))throw std::runtime_error("DECODER_ASSET_ALIGNMENT");return reinterpret_cast<const float*>(q);}
};
void finite(const float* p,std::size_t n,const char* e){for(std::size_t i=0;i<n;i++)if(!std::isfinite(p[i]))throw std::runtime_error(e);}
}

IndependentDecoderRuntime::IndependentDecoderRuntime(const std::string& path){
 try{
  std::ifstream f(path,std::ios::binary);if(!f)throw std::runtime_error("DECODER_ASSET_OPEN");f.seekg(0,std::ios::end);auto end=f.tellg();if(end<=0||end>64*1024*1024)throw std::runtime_error("DECODER_ASSET_SIZE");f.seekg(0);bytes_.resize(static_cast<std::size_t>(end));if(!f.read(reinterpret_cast<char*>(bytes_.data()),end))throw std::runtime_error("DECODER_ASSET_READ");
  Reader r{bytes_.data(),bytes_.size()};auto magic=r.raw(8);if(std::memcmp(magic,"IASRDEC1",8))throw std::runtime_error("DECODER_ASSET_MAGIC");
  auto version=r.scalar<std::uint32_t>(),width=r.scalar<std::uint32_t>(),layers=r.scalar<std::uint32_t>(),positions=r.scalar<std::uint32_t>(),pc=r.scalar<std::uint32_t>(),nc=r.scalar<std::uint32_t>(),gc=r.scalar<std::uint32_t>();
  if(version!=1||width!=W||layers!=L||positions!=ROPEPOS||pc!=72||nc!=25||gc!=201)throw std::runtime_error("DECODER_ASSET_HEADER");
  projections_.reserve(pc);
  for(std::uint32_t i=0;i<pc;i++){
   Projection p{};p.rows=r.scalar<std::uint32_t>();p.width=r.scalar<std::uint32_t>();p.groups=r.scalar<std::uint32_t>();p.packed_n=r.scalar<std::uint32_t>();p.alpha_n=r.scalar<std::uint32_t>();p.bias_n=r.scalar<std::uint32_t>();
   if(!((p.rows==W&&p.width==W&&p.groups==2)||(p.rows==3072&&p.width==W&&p.groups==2)||(p.rows==W&&p.width==3072&&p.groups==6))||p.packed_n!=static_cast<std::size_t>(p.rows)*p.width/8||p.alpha_n!=p.rows*2||p.bias_n!=p.rows)throw std::runtime_error("DECODER_PROJECTION_HEADER");
   p.packed=r.raw(p.packed_n);p.alpha=r.floats(p.alpha_n);p.bias=r.floats(p.bias_n);finite(p.alpha,p.alpha_n,"DECODER_ALPHA_NONFINITE");finite(p.bias,p.bias_n,"DECODER_BIAS_NONFINITE");projections_.push_back(p);
  }
  norms_.reserve(nc);for(std::uint32_t i=0;i<nc;i++){Norm x{};x.epsilon=r.scalar<float>();auto count=r.scalar<std::uint32_t>();if(count!=W||!std::isfinite(x.epsilon)||x.epsilon<0)throw std::runtime_error("DECODER_NORM_HEADER");x.gamma=r.floats(W);x.beta=r.floats(W);finite(x.gamma,W,"DECODER_NORM_NONFINITE");finite(x.beta,W,"DECODER_NORM_NONFINITE");norms_.push_back(x);}
  if(r.scalar<std::uint32_t>()!=ROPEPOS)throw std::runtime_error("DECODER_ROPE_HEADER");rope_cos_=r.floats(ROPEPOS*32);rope_sin_=r.floats(ROPEPOS*32);gelu_table_=r.floats(201);if(r.o!=r.n)throw std::runtime_error("DECODER_ASSET_TRAILING");finite(rope_cos_,ROPEPOS*32,"DECODER_ROPE_NONFINITE");finite(rope_sin_,ROPEPOS*32,"DECODER_ROPE_NONFINITE");finite(gelu_table_,201,"DECODER_GELU_NONFINITE");reset();ready_=true;
 }catch(const std::exception& e){error_=e.what();ready_=false;}
}
bool IndependentDecoderRuntime::ready()const{return ready_;}
const std::string& IndependentDecoderRuntime::error()const{return error_;}
void IndependentDecoderRuntime::reset(){states_.assign(L,LayerState{});for(auto& s:states_){s.key.reserve(MAXTOK*W);s.value.reserve(MAXTOK*W);}tokens_=0;}

std::vector<float> IndependentDecoderRuntime::append(const float* input,std::size_t n){
 if(!ready_)throw std::runtime_error(error_);if(!input||n<1||n>3||tokens_+n>MAXTOK||tokens_+n>ROPEPOS)throw std::runtime_error("DECODER_APPEND_RANGE");finite(input,n*W,"DECODER_INPUT_NONFINITE");
 std::vector<float> value(input,input+n*W),normalized(n*W),q(n*W),k(n*W),v(n*W),rq(n*W),rk(n*W),attention(n*W),projected(n*W),residual(n*W),ff(n*3072),activated(n*3072),ffout(n*W),final(n*W);std::vector<float> cos(n*32),sin(n*32);
 for(std::size_t t=0;t<n;t++){std::copy_n(rope_cos_+(tokens_+t)*32,32,cos.data()+t*32);std::copy_n(rope_sin_+(tokens_+t)*32,32,sin.data()+t*32);}
 auto norm=[&](const std::vector<float>& src,std::vector<float>& dst,const Norm& x){int rc=decoder_norm_batch_fp32(src.data(),src.size(),x.gamma,W,x.beta,W,n,W,x.epsilon,dst.data(),dst.size());if(rc)throw std::runtime_error("DECODER_NORM_"+std::to_string(rc));};
 auto project=[&](const std::vector<float>& src,std::vector<float>& dst,const Projection& p){std::array<std::uint8_t,3> modes{{0,0,1}};if(n==1)modes[0]=1;int rc=projection_lut_batch_native_fp32(p.packed,p.packed_n,p.alpha,p.alpha_n,p.bias,p.bias_n,src.data(),src.size(),p.width,p.rows,n,p.groups,modes.data(),n,dst.data(),dst.size(),-FMAX,FMAX);if(rc)throw std::runtime_error("DECODER_PROJECT_"+std::to_string(rc));};
 for(std::size_t layer=0;layer<L;layer++){
  const auto base=layer*6;norm(value,normalized,norms_[layer*2]);project(normalized,q,projections_[base]);project(normalized,k,projections_[base+1]);project(normalized,v,projections_[base+2]);
  int rc=decoder_rope_batch_fp32(q.data(),q.size(),k.data(),k.size(),cos.data(),cos.size(),sin.data(),sin.size(),n,H,D,rq.data(),rq.size(),rk.data(),rk.size());if(rc)throw std::runtime_error("DECODER_ROPE_"+std::to_string(rc));
  auto& state=states_[layer];auto old=state.key.size();state.key.insert(state.key.end(),rk.begin(),rk.end());state.value.insert(state.value.end(),v.begin(),v.end());rc=incremental_attention_fp32(rq.data(),state.key.data(),state.value.data(),static_cast<int>(tokens_),static_cast<int>(n),H,D,0.125f,1e-20f,attention.data());if(rc){state.key.resize(old);state.value.resize(old);throw std::runtime_error("DECODER_ATTENTION_"+std::to_string(rc));}
  project(attention,projected,projections_[base+3]);rc=decoder_residual_add_fp32(value.data(),projected.data(),residual.data(),residual.size());if(rc)throw std::runtime_error("DECODER_RESIDUAL1_"+std::to_string(rc));norm(residual,normalized,norms_[layer*2+1]);project(normalized,ff,projections_[base+4]);rc=gelu_vector_fp32(ff.data(),activated.data(),activated.size(),gelu_table_,201);if(rc)throw std::runtime_error("DECODER_GELU_"+std::to_string(rc));project(activated,ffout,projections_[base+5]);rc=decoder_residual_add_fp32(residual.data(),ffout.data(),value.data(),value.size());if(rc)throw std::runtime_error("DECODER_RESIDUAL2_"+std::to_string(rc));
 }
 norm(value,final,norms_.back());tokens_+=n;return std::vector<float>(final.end()-W,final.end());
}
}
