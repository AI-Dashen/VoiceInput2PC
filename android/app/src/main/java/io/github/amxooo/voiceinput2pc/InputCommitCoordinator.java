package io.github.amxooo.voiceinput2pc;

/** Separates a destination accepting text from saving its private history. */
public final class InputCommitCoordinator {
    private InputCommitCoordinator() {}

    public interface Destination {
        /** True means the destination accepted the call, not that visible text was verified. */
        boolean write(String text) throws Exception;
    }

    public interface History {
        /** The caller captures a unique event ID and editor identity for each real commit. */
        void record(String text) throws Exception;
    }

    public enum Error {
        NONE, EMPTY_TEXT, TARGET_UNAVAILABLE, TARGET_REJECTED, TARGET_FAILED,
        HISTORY_UNAVAILABLE, HISTORY_FAILED
    }

    public static final class Result {
        public final boolean accepted;
        public final boolean historySaved;
        public final Error error;

        private Result(boolean accepted, boolean historySaved, Error error) {
            this.accepted = accepted;
            this.historySaved = historySaved;
            this.error = error;
        }
    }

    /**
     * Calls the destination at most once. History failures never replay a destination write.
     * Sensitive text reaches the destination but never reaches the history callback.
     * No text-based deduplication is performed: two identical inputs are two commits.
     */
    public static Result commit(String text, boolean sensitive, Destination target, History sink) {
        if (text == null || text.isEmpty()) return new Result(false, false, Error.EMPTY_TEXT);
        if (target == null) return new Result(false, false, Error.TARGET_UNAVAILABLE);
        try {
            if (!target.write(text)) return new Result(false, false, Error.TARGET_REJECTED);
        } catch (Exception failure) {
            return new Result(false, false, Error.TARGET_FAILED);
        }
        if (sensitive) return new Result(true, false, Error.NONE);
        if (sink == null) return new Result(true, false, Error.HISTORY_UNAVAILABLE);
        try {
            sink.record(text);
            return new Result(true, true, Error.NONE);
        } catch (Exception failure) {
            return new Result(true, false, Error.HISTORY_FAILED);
        }
    }
}
