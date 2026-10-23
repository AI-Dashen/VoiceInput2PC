package io.github.amxooo.voiceinput2pc;

/** Local speech lifecycle port shared by live capture and deterministic lifecycle tests. */
interface SpeechInput {
    void start(long generation,OfflineSpeechRecognizer.Listener listener);
    void finish();
    void cancel();
    void close();
}
