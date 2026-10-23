/* Decoder split-half RoPE for 1..3 token rows, 12 heads and width 64.
 * FP32 operation boundaries match rope_reference; no allocation or FMA.
 */
#include <stddef.h>
#include <math.h>

enum { DR_OK=0, DR_NULL=1, DR_DIMS=2, DR_LENGTH=3, DR_NONFINITE=4 };

int decoder_rope_batch_fp32(const float *q,size_t q_n,const float *k,size_t k_n,
        const float *cosine,size_t cosine_n,const float *sine,size_t sine_n,
        size_t tokens,size_t heads,size_t width,float *q_out,size_t q_out_n,
        float *k_out,size_t k_out_n) {
    if(!q||!k||!cosine||!sine||!q_out||!k_out)return DR_NULL;
    if(tokens<1||tokens>3||heads!=12||width!=64)return DR_DIMS;
    size_t count=tokens*heads*width,half=width/2,coeff_n=tokens*half;
    if(q_n!=count||k_n!=count||cosine_n!=coeff_n||sine_n!=coeff_n||q_out_n!=count||k_out_n!=count)return DR_LENGTH;
    for(size_t i=0;i<coeff_n;i++)if(!isfinite(cosine[i])||!isfinite(sine[i]))return DR_NONFINITE;
    for(size_t i=0;i<count;i++)if(!isfinite(q[i])||!isfinite(k[i]))return DR_NONFINITE;
    for(size_t token=0;token<tokens;token++)for(size_t head=0;head<heads;head++) {
        size_t base=(token*heads+head)*width,cb=token*half;
        for(size_t j=0;j<half;j++) {
            size_t left_i=base+j,right_i=base+half+j,ci=cb+j;
            float c=cosine[ci],s=sine[ci];
            float ql=q[left_i],qr=q[right_i],kl=k[left_i],kr=k[right_i];
            float qlc=ql*c,qrs=qr*s,qrc=qr*c,qls=ql*s;
            float klc=kl*c,krs=kr*s,krc=kr*c,kls=kl*s;
            q_out[left_i]=qlc-qrs;q_out[right_i]=qrc+qls;
            k_out[left_i]=klc-krs;k_out[right_i]=krc+kls;
            if(!isfinite(q_out[left_i])||!isfinite(q_out[right_i])||!isfinite(k_out[left_i])||!isfinite(k_out[right_i]))return DR_NONFINITE;
        }
    }
    return DR_OK;
}
