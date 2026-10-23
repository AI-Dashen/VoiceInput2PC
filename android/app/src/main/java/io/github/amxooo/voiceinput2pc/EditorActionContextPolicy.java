package io.github.amxooo.voiceinput2pc;

/** Pure context policy: inputs contain offsets and lengths only, never host text. */
public final class EditorActionContextPolicy {
    public enum DeleteContext { AVAILABLE, AT_START, UNKNOWN }

    private EditorActionContextPolicy() {}

    /**
     * Negative offsets/lengths mean unknown or unsupported. Represent null text
     * queries as -1, not zero. beforeCursorLength comes from a bounded
     * getTextBeforeCursor(1, ...) query. Use observations from the current editor.
     * An empty query alone does not establish the start of a document.
     */
    public static DeleteContext evaluate(int selectionStart, int selectionEnd,
            int selectedTextLength, int beforeCursorLength) {
        boolean knownSelection = selectionStart >= 0 && selectionEnd >= 0;
        if (selectedTextLength > 0
                || (knownSelection && selectionStart != selectionEnd)
                || beforeCursorLength > 0) return DeleteContext.AVAILABLE;
        if (selectionStart == 0 && selectionEnd == 0) return DeleteContext.AT_START;
        return DeleteContext.UNKNOWN;
    }

    /** Unknown context preserves manual deletion; readiness still gates it. */
    public static boolean canDelete(DeleteContext context, boolean editorReady, boolean busy) {
        return editorReady && !busy
                && (context == DeleteContext.AVAILABLE || context == DeleteContext.UNKNOWN);
    }

    /** Host action remains usable when deletion is known to be impossible. */
    public static boolean canEnter(boolean editorReady, boolean busy) {
        return editorReady && !busy;
    }
}
