package io.github.amxooo.voiceinput2pc;
final class LocalAsrNative {
    static { System.loadLibrary("offlineasr"); }
    interface Callback { void onNativeEvent(long generation, String kind, byte[] json); }
    static native String lastError();
    static native int prepare(String nativeDir, String model);
    static native long begin(long generation, String model, Callback callback);
    static native int feed(long handle, byte[] pcm);
    static native int finish(long handle);
    static native void cancel(long handle);
    static native void close(long handle);
    static native void release();
}
