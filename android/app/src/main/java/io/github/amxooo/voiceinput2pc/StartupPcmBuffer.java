package io.github.amxooo.voiceinput2pc;

import java.io.ByteArrayOutputStream;

/** Bounded PCM held only while the isolated model process is cold-starting. */
final class StartupPcmBuffer {
    private final int maximumBytes;
    private final ByteArrayOutputStream bytes;

    StartupPcmBuffer(int maximumBytes) {
        if (maximumBytes <= 0) throw new IllegalArgumentException("maximumBytes");
        this.maximumBytes=maximumBytes;
        this.bytes=new ByteArrayOutputStream(Math.min(maximumBytes,16000*2*5));
    }

    boolean append(byte[] chunk) {
        if (chunk==null || chunk.length==0) return true;
        if (bytes.size()>maximumBytes-chunk.length) return false;
        bytes.write(chunk,0,chunk.length);
        return true;
    }

    byte[] drain() {
        byte[] result=bytes.toByteArray();
        bytes.reset();
        return result;
    }

    void clear() { bytes.reset(); }
    int size() { return bytes.size(); }
}
