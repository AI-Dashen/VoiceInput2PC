package io.github.amxooo.voiceinput2pc;

/** APK-owned independent ASR runtime. It never loads the former vendor runtime. */
final class IndependentAsrNative {
    static { System.loadLibrary("independentasr"); }
    static native int prepare(String modelDirectory);
    static native String recognize(byte[] pcm16le);
    static native int beginStream();
    static native int acceptStream(byte[] pcm16le);
    static native String finishStream();
    static native void cancelStream();
    static native String lastError();
    static native void release();
    private IndependentAsrNative() {}
}
