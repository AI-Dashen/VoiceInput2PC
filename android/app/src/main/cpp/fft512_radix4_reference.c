/* Fixed FFT512 real forward reference reconstructed from the documented
 * arithmetic contract. No original library, model, ELF or external tables.
 * Mathematical tables are independently generated at build time.
 * FFT output equivalence to native remains PENDING until real comparison.
 */
#include <math.h>
#include <stddef.h>
#include <stdint.h>
#include "fft512_generated_twiddles.h"

typedef struct { float re, im; } complex32;

/* Prevent contraction across explicitly separate native instructions. fmaf is
 * intentional only where the original selected SIMD path uses FMLA. */
static inline float add32(float a, float b) { volatile float v=a+b; return v; }
static inline float sub32(float a, float b) { volatile float v=a-b; return v; }
static inline float mul32(float a, float b) { volatile float v=a*b; return v; }

static inline complex32 cmul(complex32 a, const float *w) {
    const float p_re=mul32(a.im,w[1]);
    const float p_im=mul32(a.im,w[0]);
    complex32 r={fmaf(w[0],a.re,-p_re),fmaf(w[1],a.re,p_im)};
    return r;
}

static inline void butterfly(complex32 a,complex32 b,complex32 c,complex32 d,
                             complex32 y[4]) {
    const float acR=add32(c.re,a.re), acI=add32(c.im,a.im);
    const float bdR=add32(d.re,b.re), bdI=add32(d.im,b.im);
    const float abR=sub32(a.re,c.re), abI=sub32(a.im,c.im);
    const float beR=sub32(b.re,d.re), beI=sub32(b.im,d.im);
    y[0]=(complex32){add32(bdR,acR),add32(bdI,acI)};
    y[1]=(complex32){add32(beI,abR),sub32(abI,beR)};
    y[2]=(complex32){sub32(acR,bdR),sub32(acI,bdI)};
    y[3]=(complex32){sub32(abR,beI),add32(beR,abI)};
}

/* Return0 success,-1 bad pointer/count,-2 nonfinite input. Counts are elements,
 * not bytes. Requires512 readable input and514 writable output floats.
 * Input is copied before any output write. No heap allocation or global state.
 */
int fft512_radix4_reference_checked(const float *input,size_t input_count,
                           float *output,size_t output_count) {
    if (!input || !output || input_count!=512 || output_count!=514) return -1;
    complex32 in[256], a[256], b[256];
    for (size_t i=0;i<256;i++) {
        const float re=input[2*i],im=input[2*i+1];
        if (!isfinite(re)||!isfinite(im)) return -2;
        in[i]=(complex32){re,im};
    }
    for (size_t j=0;j<64;j++) {
        complex32 y[4];
        butterfly(in[j],in[j+64],in[j+128],in[j+192],y);
        for(size_t leg=0;leg<4;leg++) a[4*j+leg]=y[leg];
    }
    size_t table_offset=0;
    const size_t widths[3]={4,16,64};
    for(size_t stage=0;stage<3;stage++) {
        const size_t m=widths[stage],groups=64/m;
        complex32 *src=stage==1?b:a;
        complex32 *dst=stage==0?b:a;
        for(size_t group=0;group<groups;group++) {
            for(size_t j=0;j<m;j++) {
                complex32 v[4],y[4];
                v[0]=src[group*m+j];
                for(size_t leg=1;leg<4;leg++) {
                    const float *w=fft512_complex_twiddles+
                        2*(table_offset+(leg-1)*m+j);
                    v[leg]=cmul(src[group*m+j+64*leg],w);
                }
                butterfly(v[0],v[1],v[2],v[3],y);
                for(size_t leg=0;leg<4;leg++)
                    dst[group*(4*m)+j+leg*m]=y[leg];
            }
        }
        table_offset+=3*m;
    }
    output[0]=add32(a[0].im,a[0].re);
    output[1]=0.0f;
    output[512]=sub32(a[0].re,a[0].im);
    output[513]=0.0f;
    for(size_t k=1;k<=128;k++) {
        const complex32 lo=a[k],hi=a[256-k];
        const float *w=fft512_real_twiddles+2*(k-1);
        const float sR=add32(hi.re,lo.re),dR=sub32(lo.re,hi.re);
        const float sI=add32(hi.im,lo.im),dI=sub32(lo.im,hi.im);
        const float tR=fmaf(w[0],dR,-mul32(sI,w[1]));
        const float tI=fmaf(w[0],sI,mul32(dR,w[1]));
        output[2*k]=mul32(add32(tR,sR),0.5f);
        output[2*k+1]=mul32(add32(tI,dI),0.5f);
        /* Preserve the selected native SIMD final mirror write at k128. */
        output[2*(256-k)]=mul32(sub32(sR,tR),0.5f);
        output[2*(256-k)+1]=mul32(sub32(tI,dI),0.5f);
    }
    return 0;
}

/* Fixed ABI used by the parent-owned public-array native comparator. */
int fft512_radix4_reference(const float *input,float *output) {
    return fft512_radix4_reference_checked(input,512,output,514);
}
