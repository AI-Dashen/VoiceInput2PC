/* Independent bounded FP32 causal attention for incremental decoder state. */
#include <math.h>
#include <stddef.h>

int incremental_attention_fp32(const float*q,const float*all_key,
        const float*all_value,int past_tokens,int query_tokens,int heads,
        int width,float scale,float epsilon,float*out){
    if(!q||!all_key||!all_value||!out||past_tokens<0||query_tokens<1||
       query_tokens>3||heads<1||heads>64||width<1||width>256||
       past_tokens+query_tokens>256)return 1;
    int total=past_tokens+query_tokens;
    float scores[256],prob[256];
    for(int token=0;token<query_tokens;token++){
        int visible=past_tokens+token+1;
        for(int head=0;head<heads;head++){
            int qoff=(token*heads+head)*width;
            float peak=-INFINITY;
            for(int source=0;source<visible;source++){
                int soff=(source*heads+head)*width;
                float dot=q[qoff]*all_key[soff];
                for(int d=1;d<width;d++)dot=fmaf(all_key[soff+d],q[qoff+d],dot);
                scores[source]=dot*scale;
                peak=fmaxf(peak,scores[source]);
            }
            float divisor=0.0f;
            for(int source=0;source<visible;source++){
                float weight=expf(scores[source]-peak);
                prob[source]=weight;
                divisor=divisor+weight;
            }
            float inverse=1.0f/(divisor+epsilon);
            for(int source=0;source<visible;source++)prob[source]=prob[source]*inverse;
            for(int d=0;d<width;d++){
                float value=prob[0]*all_value[head*width+d];
                for(int source=1;source<visible;source++)
                    value=fmaf(prob[source],all_value[(source*heads+head)*width+d],value);
                if(!isfinite(value))return 2;
                out[qoff+d]=value;
            }
        }
    }
    (void)total;
    return 0;
}
