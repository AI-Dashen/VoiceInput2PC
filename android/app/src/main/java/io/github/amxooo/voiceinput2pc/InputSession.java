package io.github.amxooo.voiceinput2pc;

/** Pure state machine shared by the IME and the app workspace. */
public final class InputSession {
    public enum State { IDLE, COMPOSING, LISTENING, RECOGNIZING, PREVIEW, COMMITTED, CANCELLED }

    public static final class Identity {
        public final long generation;
        public final String targetId;
        public final String packageName;
        public final int fieldId;
        public final int inputType;

        Identity(long generation, String targetId, String packageName, int fieldId, int inputType) {
            this.generation = generation;
            this.targetId = safe(targetId);
            this.packageName = safe(packageName);
            this.fieldId = fieldId;
            this.inputType = inputType;
        }
    }

    private long nextGeneration;
    private Identity identity = new Identity(0, "none", "", 0, 0);
    private State state = State.IDLE;
    private String preview = "";

    public synchronized Identity begin(String targetId, String packageName, int fieldId, int inputType) {
        identity = new Identity(++nextGeneration, targetId, packageName, fieldId, inputType);
        state = State.IDLE;
        preview = "";
        return identity;
    }

    public synchronized long generation() { return identity.generation; }
    public synchronized Identity identity() { return identity; }
    public synchronized State state() { return state; }
    public synchronized String preview() { return preview; }
    public synchronized boolean isCurrent(long generation) { return generation == identity.generation; }

    public synchronized boolean compose(long generation, String value) {
        if (!isCurrent(generation) || state == State.LISTENING || state == State.RECOGNIZING) return false;
        preview = safe(value);
        state = preview.isEmpty() ? State.IDLE : State.COMPOSING;
        return true;
    }

    public synchronized boolean startListening(long generation) {
        if (!isCurrent(generation) || state == State.LISTENING || state == State.RECOGNIZING
                || state == State.PREVIEW) return false;
        preview = "";
        state = State.LISTENING;
        return true;
    }

    /**
     * Start another speech segment without committing the existing editable draft.
     * Advancing the generation makes every late callback from the previous segment stale.
     */
    public synchronized Identity resumeListening(long generation) {
        if (!isCurrent(generation) || state != State.PREVIEW || preview.isEmpty()) return null;
        Identity previous = identity;
        identity = new Identity(++nextGeneration, previous.targetId, previous.packageName,
                previous.fieldId, previous.inputType);
        state = State.LISTENING;
        return identity;
    }

    public synchronized boolean startRecognizing(long generation) {
        if (!isCurrent(generation) || state != State.LISTENING) return false;
        state = State.RECOGNIZING;
        return true;
    }

    public synchronized boolean showPreview(long generation, String value) {
        if (!isCurrent(generation) || state != State.RECOGNIZING) return false;
        preview = safe(value);
        state = State.PREVIEW;
        return true;
    }

    /** User edits only the current final preview, never a running recognizer or another field. */
    public synchronized boolean updatePreview(long generation, String value) {
        if (!isCurrent(generation) || state != State.PREVIEW) return false;
        preview = safe(value);
        return true;
    }

    /** Restore a retained draft after a resumed segment produced no usable result. */
    public synchronized boolean restorePreview(long generation, String value) {
        if (!isCurrent(generation)
                || (state != State.LISTENING && state != State.RECOGNIZING)) return false;
        preview = safe(value);
        state = preview.isEmpty() ? State.CANCELLED : State.PREVIEW;
        return true;
    }

    public synchronized String commit(long generation) {
        if (!isCurrent(generation) || (state != State.COMPOSING && state != State.PREVIEW)
                || preview.isEmpty()) return null;
        String result = preview;
        preview = "";
        state = State.COMMITTED;
        return result;
    }

    public synchronized boolean cancel(long generation) {
        if (!isCurrent(generation)) return false;
        preview = "";
        state = State.CANCELLED;
        return true;
    }

    /** Invalidate every callback belonging to the previous editor or audio capture. */
    public synchronized long invalidate() {
        identity = new Identity(++nextGeneration, "none", "", 0, 0);
        preview = "";
        state = State.IDLE;
        return identity.generation;
    }

    private static String safe(String value) { return value == null ? "" : value; }
}
