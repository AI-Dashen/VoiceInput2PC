/* Exact Decoder LayerNorm for width 768 and 1..3 token rows.
 * Four-lane FP32 reduction order matches reference_layers.special_norm_fp32.
 * No allocation, no FMA contraction and no model/library I/O.
 */
#include <stddef.h>
#include <math.h>

enum { DN_OK=0, DN_NULL=1, DN_DIMS=2, DN_LENGTH=3, DN_NONFINITE=4, DN_ZERO_DENOM=5 };

int decoder_norm_batch_fp32(
    const float *input,size_t input_n,
    const float *gamma,size_t gamma_n,
    const float *beta,size_t beta_n,
    size_t tokens,size_t width,float eps,
    float *output,size_t output_n) {
    if(!input||!gamma||!beta||!output)return DN_NULL;
    if(tokens<1||tokens>3||width!=768)return DN_DIMS;
    if(input_n!=tokens*width||gamma_n!=width||beta_n!=width||output_n!=input_n)return DN_LENGTH;
    if(!isfinite(eps)||eps<0.0f)return DN_NONFINITE;
    for(size_t i=0;i<gamma_n;i++)if(!isfinite(gamma[i])||!isfinite(beta[i]))return DN_NONFINITE;
    for(size_t token=0;token<tokens;token++) {
        const float *x=input+token*width;float *y=output+token*width;
        float lanes[4]={0.0f,0.0f,0.0f,0.0f};
        for(size_t start=0;start<width;start+=4)for(size_t lane=0;lane<4;lane++) {
            float value=x[start+lane];if(!isfinite(value))return DN_NONFINITE;
            lanes[lane]=lanes[lane]+value;
        }
        float total=((lanes[0]+lanes[1])+lanes[2])+lanes[3];
        float mean=total/(float)width;
        float variance_lanes[4]={0.0f,0.0f,0.0f,0.0f};
        for(size_t start=0;start<width;start+=4)for(size_t lane=0;lane<4;lane++) {
            float difference=x[start+lane]-mean;
            variance_lanes[lane]=variance_lanes[lane]+difference*difference;
        }
        float variance_sum=(variance_lanes[0]+variance_lanes[1])+(variance_lanes[2]+variance_lanes[3]);
        float variance=variance_sum/(float)width;
        float root=sqrtf(variance+eps);
        if(!isfinite(root))return DN_NONFINITE;
        if(root==0.0f)return DN_ZERO_DENOM;
        float inverse=1.0f/root;
        for(size_t i=0;i<width;i++) {
            float value=x[i]-mean;value=value*inverse;value=value*gamma[i];value=value+beta[i];
            if(!isfinite(value))return DN_NONFINITE;y[i]=value;
        }
    }
    return DN_OK;
}
