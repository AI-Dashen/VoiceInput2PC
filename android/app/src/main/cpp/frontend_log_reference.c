/* Independent scalar reference to the audited finite-positive SIMD log formula.
 * Evidence: AUDIO_FRONTEND_IMPLEMENTATION_AUDIT.md, ELF36fcdc.
 * No original library/model dependency. This is not a complete audio frontend.
 */
#include <math.h>
#include <stdint.h>
#include <string.h>
#pragma STDC FP_CONTRACT OFF
static float frombits(uint32_t u) {float x;memcpy(&x,&u,4);return x;}
static float lane_log(float x) {
    uint32_t u;memcpy(&u,&x,4);
    int32_t e=(int32_t)(u>>23)-127;
    float m=frombits(u-((uint32_t)e<<23));
    float z=m*m;
    float a=fmaf(frombits(0x3f317218),(float)e,frombits(0xc012eb5b));
    a=fmaf(frombits(0x40a5a113),m,a);
    float b=fmaf(frombits(0x4092b3e3),m,frombits(0xc0b5fb4c));
    float c=fmaf(frombits(0x3f5810d8),m,frombits(0xc01e2024));
    float d=fmaf(frombits(0x3c677861),m,frombits(0xbe29383a));
    c=fmaf(z,d,c);b=fmaf(z,c,b);return fmaf(z,b,a);
}
int frontend_log_reference(float *values,int32_t count) {
    if(!values || count<0 || count>4096)return 1;
    for(int32_t i=0;i<count;i++)if(!isnormal(values[i]) || values[i]<=0)return 2;
    int32_t end=count/16*16;
    for(int32_t i=0;i<end;i++)values[i]=lane_log(values[i]);
    for(int32_t i=end;i<count;i++)values[i]=logf(values[i]);
    return 0;
}

int frontend_floor_reference(float *values,int32_t count,float floor,float offset) {
    if(!values || count<0 || count>4096 || !isfinite(floor) || floor<=0 || !isfinite(offset))return 1;
    for(int32_t i=0;i<count;i++)if(!isfinite(values[i]))return 2;
    int32_t end=count/16*16;
    for(int32_t i=0;i<end;i++) {float sum=values[i]+offset;values[i]=fmaxf(sum,floor);}
    for(int32_t i=end;i<count;i++)values[i]=fmaxf(values[i],floor);
    return 0;
}

int frontend_power_reference(const float *source,int32_t frames,int32_t width,
                             float *dest,int32_t stride,int32_t usepower) {
    if(!source || !dest || frames<1 || frames>8 || width<16 || width>512 || width%16
            || stride<width/2 || stride>512 || frames*stride>4096 || frames*width>4096
            || (usepower!=0 && usepower!=1))return 1;
    for(int32_t t=0;t<frames;t++)for(int32_t k=0;k<width/2;k++) {
        float re=source[t*width+2*k],im=source[t*width+2*k+1];
        if(!isfinite(re) || !isfinite(im))return 2;
        float rr=re*re;float value=fmaf(im,im,rr);
        dest[t*stride+k]=usepower?value:sqrtf(value);
    }
    return 0;
}

static float dc_mean(float *x,int32_t n) {
    float acc[4]={0,0,0,0};int32_t end=n/16*16;
    for(int32_t b=0;b<end;b+=16)for(int l=0;l<4;l++) {
        float a=acc[l]+x[b+4+l];a=a+x[b+8+l];a=a+x[b+12+l];acc[l]=x[b+l]+a;
    }
    float a=acc[2]+acc[0],b=acc[3]+acc[1],sum=a+b;
    for(int32_t i=end;i<n;i++)sum=x[i]+sum;
    return sum/(float)n;
}
static float frame_energy(const float *x,int32_t n) {
    float acc[4]={0,0,0,0};int32_t end=n/16*16;
    for(int32_t b=0;b<end;b+=16)for(int l=0;l<4;l++)for(int o=0;o<16;o+=4) {
        float v=x[b+o+l];acc[l]=fmaf(v,v,acc[l]);
    }
    float a=acc[0]+acc[1],b=acc[2]+acc[3],energy=a+b;
    for(int32_t i=end;i<n;i++)energy=fmaf(x[i],x[i],energy);
    return logf(energy);
}
int frontend_preprocess_reference(float *rows,int32_t count,const float *window,
        int32_t n,int32_t stride,int32_t dc,int32_t raw,float *energy,float coef) {
    if(!rows || !window || !energy || count<1 || count>4 || n!=400 || stride!=512
            || (dc!=0 && dc!=1) || (raw!=0 && raw!=1) || !isfinite(coef) || coef<0 || coef>1)return 1;
    for(int32_t t=0;t<count;t++) {
        float *x=rows+t*stride;
        if(dc) {float mean=dc_mean(x,n);for(int32_t i=0;i<n;i++)x[i]=x[i]-mean;}
        if(raw)energy[t]=frame_energy(x,n);
        if(coef!=0) {
            for(int32_t i=n-1;i>0;i--)x[i]=fmaf(-coef,x[i-1],x[i]);
            x[0]=fmaf(-coef,x[0],x[0]);
        }
        for(int32_t i=0;i<n;i++)x[i]=window[i]*x[i];
        if(!raw)energy[t]=frame_energy(x,n);
    }
    return 0;
}

int frontend_mel_bank_reference(float *bank,int32_t size) {
    if(!bank || size!=256*80)return 1;
    const float inv700=frombits(0x3abb3ee7),scale=frombits(0x448ce000);
    float low_log=logf(fmaf(20.0f,inv700,1.0f));
    float high_log=logf(fmaf(8000.0f,inv700,1.0f));
    float low_mel=low_log*scale;
    float span=fmaf(high_log,scale,-low_mel);
    float delta=span/81.0f;
    float step=(float)((double)16000/(double)512),factor=step*inv700;
    for(int32_t i=0;i<size;i++)bank[i]=0.0f;
    for(int32_t j=0;j<80;j++) {
        float left=fmaf(delta,(float)j,low_mel);
        float center=fmaf(delta,(float)(j+1),low_mel);
        float right=fmaf(delta,(float)(j+2),low_mel);
        float il=1.0f/(center-left),ir=1.0f/(right-center);
        for(int32_t k=0;k<256;k++) {
            float mk=logf(fmaf(factor,(float)k,1.0f))*scale;
            if(mk<=left || mk>=right)continue;
            float l=(mk-left)*il,r=(right-mk)*ir;
            bank[k*80+j]=mk>center?r:l;
        }
    }
    return 0;
}

/* Apply the fixed 256x80 mel bank in the audited accumulation order. */
int frontend_mel_apply_reference(const float *power,const float *bank,float *mel) {
    if(!power || !bank || !mel)return 1;
    for(int32_t j=0;j<80;j++) {
        float acc=0.0f;
        for(int32_t k=0;k<256;k++) {
            if(!isfinite(power[k]) || !isfinite(bank[k*80+j]))return 2;
            acc=fmaf(power[k],bank[k*80+j],acc);
        }
        if(!isfinite(acc))return 2;
        mel[j]=acc;
    }
    return 0;
}

/* Slow double-precision DFT baseline, not an optimized/bit-exact FFT replacement. */
int frontend_rfft_reference(const float *input,float *output) {
    if(!input || !output)return 1;
    const double tau=6.283185307179586476925286766559;
    for(int k=0;k<=256;k++) {
        double re=0,im=0;
        for(int n=0;n<512;n++) {
            double angle=tau*(double)k*(double)n/512.0;
            re+=(double)input[n]*cos(angle);im-=(double)input[n]*sin(angle);
        }
        output[2*k]=(float)re;output[2*k+1]=(float)im;
    }
    return 0;
}
