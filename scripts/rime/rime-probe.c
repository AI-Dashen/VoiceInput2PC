/* Real decoder smoke test; never substitutes dictionary fixtures. */
#include <stdio.h>
#include <string.h>
#include <dlfcn.h>
#include "rime_api.h"
int main(int argc,char **argv) {
 if(argc<3)return 2;
 void *lib=dlopen("librime.so",RTLD_NOW|RTLD_LOCAL);
 if(!lib){fprintf(stderr,"%s\n",dlerror());return 3;}
 RimeApi *(*get_api)(void)=dlsym(lib,"rime_get_api");if(!get_api)return 4;
 RimeApi *r=get_api();RIME_STRUCT(RimeTraits,t);
 t.shared_data_dir=argv[1];t.user_data_dir=argv[2];t.app_name="rime.probe";t.min_log_level=2;t.log_dir="";
 r->setup(&t);r->initialize(&t);if(argc<7 || strcmp(argv[6],"prebuilt")) { if(r->start_maintenance(0))r->join_maintenance_thread(); }
 RimeSessionId id=r->create_session();if(!id)return 5;
 const char *schemas[]={"rime_ice","t9"},*inputs[]={argc>3?argv[3]:"nihao",argc>4?argv[4]:"64426"};const char *expected=argc>5?argv[5]:"你好";int failed=0;
 for(int n=0;n<2;n++) {
   r->clear_composition(id);if(!r->select_schema(id,schemas[n])){failed++;continue;}r->set_option(id,"ascii_mode",0);
   for(const char *p=inputs[n];*p;p++)r->process_key(id,*p,0);
   RIME_STRUCT(RimeContext,c);if(!r->get_context(id,&c)){failed++;continue;}
   int found=0;printf("%s input=%s candidates=%d:",schemas[n],inputs[n],c.menu.num_candidates);
   for(int i=0;i<c.menu.num_candidates;i++){printf(" %s",c.menu.candidates[i].text);if(!strcmp(c.menu.candidates[i].text,expected))found=1;}
   printf("\n");if(!found)failed++;r->free_context(&c);
 }
 r->destroy_session(id);r->finalize();return failed?6:0;
}
