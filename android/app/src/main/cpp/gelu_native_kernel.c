/* Independent C99 implementation of the recovered finite FP32 GELU body.
 * It uses explicit fmaf for the operations that the Python oracle models as
 * one-rounding FMA and disables implicit contraction at build time. */
#include <math.h>
#include <stddef.h>

static float tanh_lut32(float z,const float*table){
    if(z>=8.0f)return 1.0f;
    if(z<=-8.0f)return -1.0f;
    float a=fabsf(z);
    int i=(int)(a*25.0f);
    float t=table[i];
    float r=fmaf(-0.04f,(float)i,a);
    float p=fmaf(-t,t,1.0f);
    float v=fmaf(-t,r,1.0f);
    float pr=p*r;
    float h=fmaf(pr,v,t);
    return copysignf(h,z);
}

int gelu_vector_fp32(const float*input,float*output,size_t n,
                     const float*table,size_t table_n){
    if(!input||!output||!table||table_n!=201||n>1000000||n%16)return 1;
    const float cubic=0.035677406936883926f;
    const float linear=0.7978845834732056f;
    for(size_t i=0;i<n;i++){
        float x=input[i];
        if(!isfinite(x)||fabsf(x)>1e10f)return 2;
        float square=x*x;
        float u=square*cubic;
        float z=x*(u+linear);
        float half=x*0.5f;
        output[i]=half+half*tanh_lut32(z,table);
    }
    return 0;
}
