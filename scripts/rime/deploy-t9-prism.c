/* Build-time only: reuse the existing real dictionary table and rebuild T9 prisms. */
#include <dlfcn.h>
#include <stdio.h>
#include "rime_api.h"
int main(int argc,char **argv) {
 if(argc<5)return 2;
 void *lib=dlopen("librime.so",RTLD_NOW|RTLD_LOCAL);
 if(!lib){fprintf(stderr,"%s\n",dlerror());return 3;}
 RimeApi *(*get)(void)=dlsym(lib,"rime_get_api");if(!get)return 4;
 RimeApi *r=get();RIME_STRUCT(RimeTraits,t);
 t.shared_data_dir=argv[1];t.user_data_dir=argv[2];t.app_name="rime.t9build";t.min_log_level=2;t.log_dir="";
 r->setup(&t);r->initialize(&t);r->deployer_initialize(&t);
 int ok=1;for(int i=3;i<argc;i++)if(!r->deploy_schema(argv[i]))ok=0;
 r->finalize();return ok?0:5;
}
