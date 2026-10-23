#include <stdio.h>
#include <dlfcn.h>
#include <string.h>
#include "rime_api.h"
static void dump(RimeApi *r,RimeSessionId id,const char *tag){
 RIME_STRUCT(RimeContext,c);r->get_context(id,&c);
 printf("%s raw=%s preedit=%s len=%d cursor=%d sel=%d:%d caret=%zu preview=%s page=%d highlighted=%d count=%d\n",tag,r->get_input(id),c.composition.preedit?c.composition.preedit:"",c.composition.length,c.composition.cursor_pos,c.composition.sel_start,c.composition.sel_end,r->get_caret_pos(id),c.commit_text_preview?c.commit_text_preview:"",c.menu.page_no,c.menu.highlighted_candidate_index,c.menu.num_candidates);
 for(int i=0;i<c.menu.num_candidates;i++)printf(" %d text=%s comment=%s\n",i,c.menu.candidates[i].text,c.menu.candidates[i].comment?c.menu.candidates[i].comment:"");
 r->free_context(&c);
}
int main(int argc,char**argv){
 void *lib=dlopen("librime.so",RTLD_NOW|RTLD_LOCAL);if(!lib){puts(dlerror());return 3;}RimeApi*(*get)(void)=dlsym(lib,"rime_get_api");RimeApi*r=get();
 printf("version=%s set_input=%d highlight=%d\n",r->get_version(),RIME_API_AVAILABLE(r,set_input),RIME_API_AVAILABLE(r,highlight_candidate));
 RIME_STRUCT(RimeTraits,t);t.shared_data_dir=argv[1];t.user_data_dir=argv[2];t.app_name="rime.feasibility";t.min_log_level=2;t.log_dir="";r->setup(&t);r->initialize(&t);r->deployer_initialize(&t);
 if(argc>3){printf("deploy=%d\n",r->deploy_schema(argv[3]));}
 RimeSessionId id=r->create_session();printf("schema=%d\n",r->select_schema(id,"t9mixed"));r->set_option(id,"ascii_mode",0);
 const char *tests[]={"64426","ni426","ni'426","mi426","ni'hao","ni'hao'746","ni'hao'746542"};
 for(int i=0;i<7;i++){r->clear_composition(id);int ok=r->set_input(id,tests[i]);printf("set_input=%d ",ok);dump(r,id,tests[i]);}
 r->clear_composition(id);r->set_input(id,"64426");r->set_caret_pos(id,2);dump(r,id,"caret2");r->set_caret_pos(id,5);r->process_key(id,0xff08,0);dump(r,id,"backspace");
 r->clear_composition(id);r->set_input(id,"ni'hao'746542");int chosen=r->select_candidate_on_current_page(id,0);printf("select=%d\n",chosen);dump(r,id,"after-select0");RIME_STRUCT(RimeCommit,c);int committed=r->get_commit(id,&c);printf("commit=%d text=%s\n",committed,committed&&c.text?c.text:"");if(committed)r->free_commit(&c);
 
 int final=r->commit_composition(id);RIME_STRUCT(RimeCommit,c2);int got=r->get_commit(id,&c2);printf("explicit_commit=%d got=%d text=%s\n",final,got,got&&c2.text?c2.text:"");if(got)r->free_commit(&c2);
 r->clear_composition(id);r->set_input(id,"ni\'hao");r->select_candidate_on_current_page(id,2);dump(r,id,"partial-ni");r->set_input(id,"ni'hao");dump(r,id,"reset-same-after-partial");r->set_input(id,"ni'426");dump(r,id,"change-tail-after-partial");
 r->clear_composition(id);r->set_input(id,"64426");int key=r->process_key(id,32,0);RIME_STRUCT(RimeCommit,c3);int got3=r->get_commit(id,&c3);printf("space_key=%d got=%d text=%s\n",key,got3,got3&&c3.text?c3.text:"");if(got3)r->free_commit(&c3);dump(r,id,"after-space");
 r->destroy_session(id);r->finalize();return 0;
}
