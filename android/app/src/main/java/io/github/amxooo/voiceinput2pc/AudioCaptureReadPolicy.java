package io.github.amxooo.voiceinput2pc;

/** Pure decision boundary for AudioRecord reads, including concurrent user stop. */
final class AudioCaptureReadPolicy {
    enum Decision { DATA, RETRY, CLEAN_STOP, FAILURE }

    private AudioCaptureReadPolicy() {}

    static Decision classify(int count, boolean sessionCurrent) {
        // Preserve a positive block already returned by AudioRecord just before
        // another thread requested stop. The generation-aware consumer decides
        // whether that final block still belongs to the session.
        if (count > 0) return Decision.DATA;
        if (!sessionCurrent) return Decision.CLEAN_STOP;
        if (count == 0) return Decision.RETRY;
        return Decision.FAILURE;
    }
}
