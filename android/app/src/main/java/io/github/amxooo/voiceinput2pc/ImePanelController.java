package io.github.amxooo.voiceinput2pc;

/**
 * Pure navigation state for the IME's primary input surface and secondary
 * feature surface. It never commits text or talks to InputConnection.
 */
public final class ImePanelController {
    public enum Level { PRIMARY, SECONDARY }
    public enum Feature { HUB, TRANSFER, STATISTICS, SETTINGS }

    /** Immutable evidence captured when leaving the primary input surface. */
    public static final class ReturnSnapshot {
        public final long editorGeneration;
        public final KeyboardController.Mode mode;
        public final String rawInput;
        public final int selectionStart;
        public final int selectionEnd;

        ReturnSnapshot(long editorGeneration, KeyboardController.Mode mode, String rawInput,
                       int selectionStart, int selectionEnd) {
            this.editorGeneration = editorGeneration;
            this.mode = mode;
            this.rawInput = rawInput == null ? "" : rawInput;
            this.selectionStart = selectionStart;
            this.selectionEnd = selectionEnd;
        }
    }

    public static final class ReturnResult {
        public final boolean restored;
        public final ReturnSnapshot snapshot;

        ReturnResult(boolean restored, ReturnSnapshot snapshot) {
            this.restored = restored;
            this.snapshot = snapshot;
        }
    }

    private Level level = Level.PRIMARY;
    private Feature feature = Feature.HUB;
    private ReturnSnapshot snapshot;

    public synchronized Level level() { return level; }
    public synchronized Feature feature() { return feature; }
    public synchronized boolean isPrimary() { return level == Level.PRIMARY; }
    public synchronized ReturnSnapshot snapshot() { return snapshot; }

    public synchronized boolean enterSecondary(long editorGeneration,
                                                KeyboardController.Mode mode,
                                                String rawInput,
                                                int selectionStart,
                                                int selectionEnd) {
        if (level != Level.PRIMARY || editorGeneration <= 0 || mode == null) return false;
        snapshot = new ReturnSnapshot(editorGeneration, mode, rawInput,
                selectionStart, selectionEnd);
        feature = Feature.HUB;
        level = Level.SECONDARY;
        return true;
    }

    public synchronized boolean openFeature(Feature next) {
        if (level != Level.SECONDARY || next == null) return false;
        feature = next;
        return true;
    }

    /**
     * Returning is allowed only inside the editor generation that opened the
     * feature surface. A stale feature surface is discarded without restoring
     * or mutating editor state.
     */
    public synchronized ReturnResult returnPrimary(long currentGeneration) {
        ReturnSnapshot captured = snapshot;
        boolean valid = level == Level.SECONDARY && captured != null
                && captured.editorGeneration == currentGeneration;
        reset();
        return new ReturnResult(valid, valid ? captured : null);
    }

    public synchronized void reset() {
        level = Level.PRIMARY;
        feature = Feature.HUB;
        snapshot = null;
    }
}
