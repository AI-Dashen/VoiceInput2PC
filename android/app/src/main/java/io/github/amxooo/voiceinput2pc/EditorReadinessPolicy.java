package io.github.amxooo.voiceinput2pc;

/** Editor lifecycle readiness only; no host text, selection offsets, or Android dependencies. */
public final class EditorReadinessPolicy {
    public enum State { NO_EDITOR, STALE_EDITOR, DISCONNECTED, READY }

    private EditorReadinessPolicy() {}

    /** Unknown selection (for example -1 in a terminal) is not a connectivity signal. */
    public static State evaluate(boolean bound, boolean hasEditor,
            boolean identityMatches, boolean hasConnection) {
        if (!bound || !hasEditor) return State.NO_EDITOR;
        if (!identityMatches) return State.STALE_EDITOR;
        if (!hasConnection) return State.DISCONNECTED;
        return State.READY;
    }

    public static boolean canRunLocalAction(State state, boolean busy) {
        return state == State.READY && !busy;
    }
}
