/* Independent C99 q1 subset-LUT batch math. No model/library loading or I/O.
 * Caller supplies validated canonical q1 body (MSB first), raw FP32 alpha pairs,
 * bias and logical token rows. Build without FMA contraction or fast-math.
 */
#include <stdint.h>
#include <stddef.h>
#include <stdlib.h>
#include <math.h>
#include <string.h>

enum { OK=0, E_DIMS=1, E_LENGTH=2, E_WORK=3, E_FINITE=4,
       E_ZERO_SPAN=5, E_ALLOC=6, E_NUMERIC=7, E_CONTRACT=8 };

static float fp(double x) { return (float)x; }
static int away(float x) { return x>=0 ? (int)floor((double)x+0.5) : (int)ceil((double)x-0.5); }
static int even(float x) {
    double a=floor((double)x),f=(double)x-a;
    if(f>0.5 || (f==0.5 && fmod(a,2.0)!=0.0))a+=1.0;
    return (int)a;
}
/* Expansion summation plus final halfway correction, matching math.fsum for
 * finite binary32 inputs. Partial count is bounded by the input width. */
static double sum_f32(const float *values,size_t n,double *partials) {
    size_t used=0;
    for(size_t k=0;k<n;k++) {
        double x=values[k];size_t i=0;
        for(size_t j=0;j<used;j++) {
            double y=partials[j];
            if(fabs(x)<fabs(y)){double t=x;x=y;y=t;}
            double hi=x+y,lo=y-(hi-x);
            if(lo!=0.0)partials[i++]=lo;
            x=hi;
        }
        used=i;
        if(x!=0.0)partials[used++]=x;
    }
    if(!used)return 0.0;
    double hi=partials[--used],lo=0.0;
    while(used) {
        double x=hi,y=partials[--used];hi=x+y;lo=y-(hi-x);
        if(lo!=0.0)break;
    }
    if(used && ((lo<0.0 && partials[used-1]<0.0)||(lo>0.0 && partials[used-1]>0.0))) {
        double y=lo*2.0,x=hi+y;
        if(y==x-hi)hi=x;
    }
    return hi;
}

int projection_lut_batch(
    const uint8_t *packed,size_t packed_n,
    const float *alpha,size_t alpha_n,const float *bias,size_t bias_n,
    const float *input,size_t input_n,size_t width,size_t rows,size_t tokens,size_t groups,
    const uint8_t *round_modes,size_t modes_n,float *output,size_t output_n) {
    if(!packed||!alpha||!bias||!input||!round_modes||!output)return E_DIMS;
    if(!width||width>4096||width%8||!rows||rows>4096||!tokens||tokens>3||!groups||groups>8||width%groups)return E_DIMS;
    size_t features=width/groups;
    if(features%4||!features)return E_DIMS;
    uint64_t work=(uint64_t)width*(uint64_t)rows*(uint64_t)tokens;
    if(work>128000000ULL)return E_WORK;
    if(packed_n!=rows*(width/8)||alpha_n!=rows*2||bias_n!=rows||input_n!=tokens*width||modes_n!=tokens||output_n!=tokens*rows)return E_LENGTH;
    for(size_t i=0;i<tokens;i++)if(round_modes[i]>1)return E_DIMS;
    for(size_t i=0;i<alpha_n;i++)if(!isfinite(alpha[i]))return E_FINITE;
    for(size_t i=0;i<bias_n;i++)if(!isfinite(bias[i]))return E_FINITE;
    for(size_t i=0;i<input_n;i++)if(!isfinite(input[i]))return E_FINITE;
    size_t quads=width/4,group_quads=features/4;
    float *lut=malloc(quads*16*sizeof(float));
    int16_t *quant=malloc(quads*16*sizeof(int16_t));
    double *partials=malloc(features*sizeof(double));
    if(!lut||!quant||!partials){free(lut);free(quant);free(partials);return E_ALLOC;}
    int error=OK;
    for(size_t token=0;token<tokens;token++) {
        const float *x=input+token*width;
        for(size_t q=0;q<quads;q++) {
            for(unsigned mask=0;mask<16;mask++) {
                float a=(mask&8)?x[q*4]:0.0f,b=(mask&4)?x[q*4+1]:0.0f;
                float c=(mask&2)?x[q*4+2]:0.0f,d=(mask&1)?x[q*4+3]:0.0f;
                float first=fp((double)a+b),second=fp((double)c+d);
                float value=fp((double)first+second);
                if(!isfinite(value)){error=E_NUMERIC;goto done;}
                lut[q*16+mask]=value;
            }
        }
        float scales[8];int zeros[8];double sums[8];
        for(size_t g=0;g<groups;g++) {
            size_t begin=g*group_quads*16,end=begin+group_quads*16;
            float lo=lut[begin],hi=lo;
            for(size_t i=begin+1;i<end;i++){if(lut[i]<lo)lo=lut[i];if(lut[i]>hi)hi=lut[i];}
            float span=fp((double)hi-lo);
            if(span==0.0f){error=E_ZERO_SPAN;goto done;}
            float inverse=fp(255.0/(double)span),scale=fp((double)span/255.0);
            float zproduct=fp((double)lo*(-255.0)),zfloat=fp((double)zproduct/span);
            if(!(span>0.0f)||!isfinite(span)||!isfinite(inverse)||!isfinite(scale)||!isfinite(zfloat)||scale<=0.0f||zfloat< -1.0f||zfloat>256.0f){error=E_NUMERIC;goto done;}
            int zero=away(zfloat)-128;scales[g]=scale;zeros[g]=zero;
            sums[g]=sum_f32(x+g*features,features,partials);
            for(size_t i=begin;i<end;i++) {
                float product=fp((double)lut[i]*inverse),value=fp((double)product+zero);
                if(!isfinite(value)||fabs((double)value)>1000000.0){error=E_NUMERIC;goto done;}
                int q=round_modes[token]?away(value):even(value);
                if(q< -128)q=-128;if(q>127)q=127;quant[i]=(int16_t)q;
            }
        }
        for(size_t row=0;row<rows;row++) {
            double offset=alpha[row*2],wscale=alpha[row*2+1],pred=0.0;
            const uint8_t *bits=packed+row*(width/8);
            for(size_t g=0;g<groups;g++) {
                int64_t selected=0;
                size_t begin=g*group_quads,end=begin+group_quads;
                for(size_t quad=begin;quad<end;quad++) {
                    unsigned mask=(bits[quad/2]>>((quad&1)?0:4))&15;
                    selected+=(int)quant[quad*16+mask]-zeros[g];
                }
                pred+=(double)selected*(double)scales[g]*wscale+sums[g]*(offset-wscale);
            }
            float result=fp(pred+(double)bias[row]);
            if(!isfinite(result)){error=E_NUMERIC;goto done;}
            output[token*rows+row]=result;
        }
    }
done:
    free(lut);free(quant);free(partials);return error;
}

/* Exact static branch order supplied by native mapping review: right-associated
 * LUT16 and sumX, FP32 weighted groups, explicit fmaf, and distinct paired/tail
 * bias order. Clamp is supplied explicitly: no guessed native bounds. */

int projection_lut_batch_native_fp32(
    const uint8_t *packed,size_t packed_n,
    const float *alpha,size_t alpha_n,const float *bias,size_t bias_n,
    const float *input,size_t input_n,size_t width,size_t rows,size_t tokens,size_t groups,
    const uint8_t *round_modes,size_t modes_n,float *output,size_t output_n,float clamp_min,float clamp_max) {
    if(!packed||!alpha||!bias||!input||!round_modes||!output)return E_DIMS;
    if(!isfinite(clamp_min)||!isfinite(clamp_max)||clamp_min>clamp_max)return E_CONTRACT;
    if(!width||width>4096||width%8||!rows||rows>4096||!tokens||tokens>3||!groups||groups>8||width%groups)return E_DIMS;
    size_t features=width/groups;
    if(features%4||!features)return E_DIMS;
    uint64_t work=(uint64_t)width*(uint64_t)rows*(uint64_t)tokens;
    if(work>128000000ULL)return E_WORK;
    if(packed_n!=rows*(width/8)||alpha_n!=rows*2||bias_n!=rows||input_n!=tokens*width||modes_n!=tokens||output_n!=tokens*rows)return E_LENGTH;
    for(size_t i=0;i<tokens;i++){unsigned expected=(tokens==1 || (tokens==3 && i==2))?1:0;if(round_modes[i]!=expected)return E_CONTRACT;}
    for(size_t i=0;i<alpha_n;i++)if(!isfinite(alpha[i]))return E_FINITE;
    for(size_t i=0;i<bias_n;i++)if(!isfinite(bias[i]))return E_FINITE;
    for(size_t i=0;i<input_n;i++)if(!isfinite(input[i]))return E_FINITE;
    size_t quads=width/4,group_quads=features/4;
    float *lut=malloc(quads*16*sizeof(float));
    int16_t *quant=malloc(quads*16*sizeof(int16_t));
    double *partials=NULL;
    if(!lut||!quant){free(lut);free(quant);free(partials);return E_ALLOC;}
    int error=OK;
    for(size_t token=0;token<tokens;token++) {
        const float *x=input+token*width;
        for(size_t q=0;q<quads;q++) {
            float a=x[q*4],b=x[q*4+1],c=x[q*4+2],d=x[q*4+3];
            float cd=fp((double)c+d),bd=fp((double)b+d),bc=fp((double)b+c);
            float bcd=fp((double)b+cd);
            float values[16]={0.0f,d,c,cd,b,bd,bc,bcd,a,
                fp((double)a+d),fp((double)a+c),fp((double)a+cd),
                fp((double)a+b),fp((double)a+bd),fp((double)a+bc),fp((double)a+bcd)};
            for(unsigned mask=0;mask<16;mask++) {
                if(!isfinite(values[mask])){error=E_NUMERIC;goto done;}
                lut[q*16+mask]=values[mask];
            }
        }
        float scales[8];int zeros[8];float sums[8];
        for(size_t g=0;g<groups;g++) {
            size_t begin=g*group_quads*16,end=begin+group_quads*16;
            float lo=lut[begin],hi=lo;
            for(size_t i=begin+1;i<end;i++){if(lut[i]<lo)lo=lut[i];if(lut[i]>hi)hi=lut[i];}
            float span=fp((double)hi-lo);
            if(span==0.0f){error=E_ZERO_SPAN;goto done;}
            float inverse=fp(255.0/(double)span),scale=fp((double)span/255.0);
            float zproduct=fp((double)lo*(-255.0)),zfloat=fp((double)zproduct/span);
            if(!(span>0.0f)||!isfinite(span)||!isfinite(inverse)||!isfinite(scale)||!isfinite(zfloat)||scale<=0.0f||zfloat< -1.0f||zfloat>256.0f){error=E_NUMERIC;goto done;}
            int zero=away(zfloat)-128;scales[g]=scale;zeros[g]=zero;
            sums[g]=0.0f;
            for(size_t q=g*group_quads;q<(g+1)*group_quads;q++)
                sums[g]=fp((double)sums[g]+lut[q*16+15]);
            if(!isfinite(sums[g])){error=E_NUMERIC;goto done;}
            for(size_t i=begin;i<end;i++) {
                float product=fp((double)lut[i]*inverse),value=fp((double)product+zero);
                if(!isfinite(value)||fabs((double)value)>1000000.0){error=E_NUMERIC;goto done;}
                int q=round_modes[token]?away(value):even(value);
                if(q< -128)q=-128;if(q>127)q=127;quant[i]=(int16_t)q;
            }
        }
        int single=(tokens==1 || (tokens==3 && token==2));
        for(size_t row=0;row<rows;row++) {
            float offset=fp((double)alpha[row*2]-alpha[row*2+1]);
            float wscale=alpha[row*2+1];
            float acc=single?bias[row]:0.0f;
            const uint8_t *bits=packed+row*(width/8);
            for(size_t g=0;g<groups;g++) {
                int64_t int_dot=0;size_t begin=g*group_quads,end=begin+group_quads;
                for(size_t quad=begin;quad<end;quad++) {
                    unsigned mask=(bits[quad/2]>>((quad&1)?0:4))&15;
                    int_dot+=(int)quant[quad*16+mask];
                }
                float correction=fp((double)fp((double)zeros[g]*1.0)*(double)group_quads);
                float sub=fp((double)fp((double)int_dot)-correction),t;
                if(single && row%32<16){t=fp((double)sub*wscale);t=fp((double)t*scales[g]);}
                else{t=fp((double)sub*scales[g]);t=fp((double)t*wscale);}
                float y=fmaf(offset,sums[g],t);
                if(single)acc=fp((double)acc+y);else acc=g?fp((double)y+acc):y;
            }
            if(!single)acc=fp((double)acc+bias[row]);
            if(!isfinite(acc)){error=E_NUMERIC;goto done;}
            output[token*rows+row]=fmaxf(clamp_min,fminf(clamp_max,acc));
        }
    }

done:
    free(lut);free(quant);free(partials);return error;
}
