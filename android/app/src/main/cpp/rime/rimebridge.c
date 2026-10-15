/* App-owned JNI adapter to the BSD-licensed librime C API. No Trime code copied. */
#include <jni.h>
#include <dlfcn.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include "rime_api.h"
static RimeApi *api;
static char last_error[512];
static char *shared_path, *user_path, *prebuilt_path;
#define FN(name) Java_io_github_amxooo_voiceinput2pc_RimeChineseInputEngine_##name
/* Standard UTF-8 includes supplementary Han characters; NewStringUTF is modified UTF-8. */
static jstring java_utf8(JNIEnv *env,const char *text) {
    if (!text) return NULL;
    size_t length=strlen(text); if (length>0x7fffffff) return NULL;
    jbyteArray bytes=(*env)->NewByteArray(env,(jsize)length);
    if (!bytes) return NULL;
    (*env)->SetByteArrayRegion(env,bytes,0,(jsize)length,(const jbyte*)text);
    jclass cls=(*env)->FindClass(env,"java/lang/String");
    jmethodID init=(*env)->GetMethodID(env,cls,"<init>","([BLjava/lang/String;)V");
    jstring charset=(*env)->NewStringUTF(env,"UTF-8");
    jstring value=(jstring)(*env)->NewObject(env,cls,init,bytes,charset);
    (*env)->DeleteLocalRef(env,bytes);(*env)->DeleteLocalRef(env,cls);(*env)->DeleteLocalRef(env,charset);
    return value;
}
JNIEXPORT jlong JNICALL FN(nativeCreate)(JNIEnv *env,jclass clazz,jstring shared,jstring user) {
    (void)clazz;
    if (!api) {
        void *library=dlopen("librime.so",RTLD_NOW|RTLD_LOCAL);
        if (!library) { snprintf(last_error,sizeof(last_error),"%s",dlerror()); return 0; }
        RimeApi *(*get_api)(void)=dlsym(library,"rime_get_api");
        if (!get_api) { snprintf(last_error,sizeof(last_error),"Missing librime C API"); return 0; }
        api=get_api();
        const char *a=(*env)->GetStringUTFChars(env,shared,NULL), *b=(*env)->GetStringUTFChars(env,user,NULL);
        shared_path=strdup(a); user_path=strdup(b);
        (*env)->ReleaseStringUTFChars(env,shared,a); (*env)->ReleaseStringUTFChars(env,user,b);
        RIME_STRUCT(RimeTraits,traits);
        traits.shared_data_dir=shared_path; traits.user_data_dir=user_path;
        prebuilt_path=malloc(strlen(shared_path)+7);sprintf(prebuilt_path,"%s/build",shared_path);
        traits.prebuilt_data_dir=prebuilt_path;
        traits.staging_dir=prebuilt_path;
        traits.distribution_name="Universal Input"; traits.distribution_code_name="universal";
        traits.distribution_version="0.8"; traits.app_name="rime.universal";
        traits.min_log_level=2; traits.log_dir="";
        api->setup(&traits); api->initialize(&traits);
        /* All dictionary/prism/schema deployment is performed at build time.
           Never run the high-memory dictionary compiler inside an IME process. */
    }
    return (jlong)api->create_session();
}
JNIEXPORT jobjectArray JNICALL FN(nativeSnapshot)(JNIEnv *env,jclass clazz,jlong id) {
    (void)clazz;
    if (!api || !api->find_session((RimeSessionId)id)) return NULL;
    RIME_STRUCT(RimeContext,ctx);
    if (!api->get_context((RimeSessionId)id,&ctx)) return NULL;
    int n=ctx.menu.num_candidates;
    jobjectArray result=(*env)->NewObjectArray(env,n+3,(*env)->FindClass(env,"java/lang/String"),NULL);
    char page[24]; snprintf(page,sizeof(page),"%d",ctx.menu.page_no);
    const char *base[3]={RIME_PROVIDED(api,get_input)?api->get_input((RimeSessionId)id):(ctx.composition.preedit?ctx.composition.preedit:""),page,ctx.menu.is_last_page?"0":"1"};
    for (int i=0;i<n+3;i++) {
        const char *text=i<3?base[i]:ctx.menu.candidates[i-3].text;
        jstring value=java_utf8(env,text?text:"");
        (*env)->SetObjectArrayElement(env,result,i,value); (*env)->DeleteLocalRef(env,value);
    }
    api->free_context(&ctx); return result;
}
JNIEXPORT jboolean JNICALL FN(nativeKey)(JNIEnv *env,jclass clazz,jlong id,jint key) {
    (void)env;(void)clazz; return api && api->process_key((RimeSessionId)id,key,0);
}
JNIEXPORT jstring JNICALL FN(nativeSelect)(JNIEnv *env,jclass clazz,jlong id,jint index) {
    (void)clazz;
    if (!api || index<0) return NULL;
    RIME_STRUCT(RimeContext,ctx);
    if (!api->get_context((RimeSessionId)id,&ctx)) return NULL;
    int global=ctx.menu.page_no*ctx.menu.page_size+index;
    int valid=index<ctx.menu.num_candidates;
    api->free_context(&ctx);
    if (!valid || !RIME_PROVIDED(api,select_candidate) || !api->select_candidate((RimeSessionId)id,global)) return NULL;
    RIME_STRUCT(RimeCommit,commit);
    if (!api->get_commit((RimeSessionId)id,&commit)) return NULL;
    jstring result=java_utf8(env,commit.text);
    api->free_commit(&commit); return result;
}
JNIEXPORT jboolean JNICALL FN(nativeSchema)(JNIEnv *env,jclass clazz,jlong id,jstring schema) {
    (void)clazz; if (!api) return JNI_FALSE;
    const char *name=(*env)->GetStringUTFChars(env,schema,NULL);
    int ok=api->select_schema((RimeSessionId)id,name);
    (*env)->ReleaseStringUTFChars(env,schema,name);
    if (ok) api->set_option((RimeSessionId)id,"ascii_mode",False);
    return ok;
}
JNIEXPORT void JNICALL FN(nativeClear)(JNIEnv *env,jclass clazz,jlong id) {(void)env;(void)clazz;if(api)api->clear_composition((RimeSessionId)id);}
JNIEXPORT void JNICALL FN(nativeDestroy)(JNIEnv *env,jclass clazz,jlong id) {(void)env;(void)clazz;if(api)api->destroy_session((RimeSessionId)id);}

JNIEXPORT jstring JNICALL FN(nativeError)(JNIEnv *env,jclass clazz) { (void)clazz; return java_utf8(env,last_error); }
