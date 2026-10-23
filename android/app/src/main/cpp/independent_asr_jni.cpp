#include "independent_asr_engine.h"
#include <jni.h>
#include <memory>
#include <mutex>
#include <string>

namespace {
std::mutex g_mutex;
std::unique_ptr<mobile_asr::Engine> g_engine;
std::string g_error;
std::string from_jstring(JNIEnv* env, jstring value) {
    if (!value) return {};
    const char* raw = env->GetStringUTFChars(value, nullptr);
    if (!raw) return {};
    std::string out(raw);
    env->ReleaseStringUTFChars(value, raw);
    return out;
}
}

extern "C" JNIEXPORT jint JNICALL
Java_io_github_amxooo_voiceinput2pc_IndependentAsrNative_prepare(
        JNIEnv* env, jclass, jstring directory) {
    std::lock_guard<std::mutex> lock(g_mutex);
    try {
        auto next = std::make_unique<mobile_asr::Engine>(from_jstring(env, directory));
        if (!next->ready()) { g_error = next->error(); return -2; }
        g_engine = std::move(next); g_error.clear(); return 0;
    } catch (const std::exception& error) {
        g_error = error.what(); g_engine.reset(); return -3;
    } catch (...) {
        g_error = "UNKNOWN_NATIVE_PREPARE_FAILURE"; g_engine.reset(); return -4;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_amxooo_voiceinput2pc_IndependentAsrNative_recognize(
        JNIEnv* env, jclass, jbyteArray pcm) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_engine || !pcm) { g_error = "ENGINE_NOT_READY"; return nullptr; }
    const jsize bytes = env->GetArrayLength(pcm);
    if (bytes <= 0 || (bytes & 1) || bytes > 16000 * 2 * 180) {
        g_error = "PCM_LENGTH_INVALID"; return nullptr;
    }
    jbyte* raw = env->GetByteArrayElements(pcm, nullptr);
    if (!raw) { g_error = "PCM_ACCESS_FAILED"; return nullptr; }
    std::string result;
    try { result = g_engine->recognize(reinterpret_cast<int16_t*>(raw), bytes / 2); }
    catch (const std::exception& error) { g_error = error.what(); }
    catch (...) { g_error = "UNKNOWN_NATIVE_RECOGNIZE_FAILURE"; }
    env->ReleaseByteArrayElements(pcm, raw, JNI_ABORT);
    if (result.empty()) return nullptr;
    g_error.clear();
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_amxooo_voiceinput2pc_IndependentAsrNative_lastError(JNIEnv* env, jclass) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return env->NewStringUTF(g_error.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_amxooo_voiceinput2pc_IndependentAsrNative_release(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_engine.reset(); g_error.clear();
}

extern "C" JNIEXPORT jint JNICALL
Java_io_github_amxooo_voiceinput2pc_IndependentAsrNative_beginStream(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_engine) { g_error = "ENGINE_NOT_READY"; return -1; }
    try { g_engine->beginStream(); g_error.clear(); return 0; }
    catch (const std::exception& error) { g_error = error.what(); return -2; }
    catch (...) { g_error = "UNKNOWN_NATIVE_STREAM_BEGIN_FAILURE"; return -3; }
}

extern "C" JNIEXPORT jint JNICALL
Java_io_github_amxooo_voiceinput2pc_IndependentAsrNative_acceptStream(
        JNIEnv* env, jclass, jbyteArray pcm) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_engine || !pcm) { g_error = "ENGINE_NOT_READY"; return -1; }
    const jsize bytes = env->GetArrayLength(pcm);
    if (bytes <= 0 || (bytes & 1) || bytes > 16384) { g_error = "PCM_CHUNK_INVALID"; return -2; }
    jbyte* raw = env->GetByteArrayElements(pcm, nullptr);
    if (!raw) { g_error = "PCM_ACCESS_FAILED"; return -3; }
    int rc = 0;
    try { g_engine->acceptStream(reinterpret_cast<int16_t*>(raw), bytes / 2); }
    catch (const std::exception& error) { g_error = error.what(); rc = -4; }
    catch (...) { g_error = "UNKNOWN_NATIVE_STREAM_ACCEPT_FAILURE"; rc = -5; }
    env->ReleaseByteArrayElements(pcm, raw, JNI_ABORT);
    if (rc == 0) g_error.clear();
    return rc;
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_amxooo_voiceinput2pc_IndependentAsrNative_finishStream(JNIEnv* env, jclass) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_engine) { g_error = "ENGINE_NOT_READY"; return nullptr; }
    std::string result;
    try { result = g_engine->finishStream(); }
    catch (const std::exception& error) { g_error = error.what(); }
    catch (...) { g_error = "UNKNOWN_NATIVE_STREAM_FINISH_FAILURE"; }
    if (result.empty()) return nullptr;
    g_error.clear();
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_amxooo_voiceinput2pc_IndependentAsrNative_cancelStream(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_engine) g_engine->cancelStream();
}
