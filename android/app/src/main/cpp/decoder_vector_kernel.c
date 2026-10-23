#include <stddef.h>
#include <math.h>
enum { DV_OK=0, DV_NULL=1, DV_LENGTH=2, DV_NONFINITE=3 };
int decoder_residual_add_fp32(const float *a,const float *b,float *out,size_t n) {
 if(!a||!b||!out)return DV_NULL; if(!n||n>12288)return DV_LENGTH;
 for(size_t i=0;i<n;i++){if(!isfinite(a[i])||!isfinite(b[i]))return DV_NONFINITE;float v=a[i]+b[i];if(!isfinite(v))return DV_NONFINITE;out[i]=v;}
 return DV_OK;
}
