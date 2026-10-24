package io.github.amxooo.voiceinput2pc;

/** Pure keyboard state. The host controls InputConnection and asynchronous speech. */
public final class KeyboardController {
    public enum Mode { PINYIN, T9, ENGLISH, NUMBERS, SYMBOLS }
    private final ChineseInputEngine engine;
    private Mode mode = Mode.PINYIN;
    private boolean uppercase;
    public KeyboardController(ChineseInputEngine engine) { this.engine = engine; }
    public Mode mode() { return mode; }
    public boolean uppercase() { return uppercase; }
    public boolean chinese() { return mode == Mode.PINYIN || mode == Mode.T9; }
    public void setMode(Mode next) {
        engine.reset(); mode = next;
        if (chinese()) engine.setLayout(next == Mode.T9
                ? ChineseInputEngine.Layout.T9 : ChineseInputEngine.Layout.FULL_PINYIN);
    }
    public void toggleCase() { uppercase = !uppercase; }
    /** Null means composition only. Non-Chinese keys are committed by the host. */
    public String type(String key) {
        return type(key,null);
    }
    /** Reuses a state already read by the host for this same key event. */
    public String type(String key,ChineseInputEngine.Snapshot current) {
        if (chinese()) {
            if (key == null || key.length() != 1) return "";
            char value=key.charAt(0);
            boolean composingKey=mode == Mode.T9
                    ? (value >= '2' && value <= '9') || value == '\''
                    : (value >= 'a' && value <= 'z') || value == '\'';
            if (!composingKey) return key;
            if (!(current == null ? engine.snapshot() : current).available) return null;
            engine.input(value);
            return null;
        }
        return mode == Mode.ENGLISH && uppercase ? key.toUpperCase(java.util.Locale.ROOT) : key;
    }
    /**
     * Any T9 lookup with no real candidate must be recovered in the same key
     * event. It must never survive as an invisible composition that blocks
     * typing, switching modes, Space, or Enter.
     */
    public boolean t9NoMatchRecoveryNeeded() {
        return t9NoMatchRecoveryNeeded(null);
    }
    public boolean t9NoMatchRecoveryNeeded(ChineseInputEngine.Snapshot current) {
        if (mode != Mode.T9) return false;
        ChineseInputEngine.Snapshot state=current == null ? engine.snapshot() : current;
        return !state.numericInput.isEmpty()
                && firstCommittableCandidateIndex(state) < 0;
    }
    public boolean isT9ComposingKey(String key) {
        if (mode != Mode.T9 || key == null || key.length() != 1) return false;
        char value=key.charAt(0);
        return (value >= '2' && value <= '9') || value == '\'';
    }
    public ChineseInputEngine.Snapshot snapshot() { return engine.snapshot(); }
    /** T9 digits are private lookup codes, never user-visible candidates. */
    public boolean displayableCandidate(String candidate) {
        if (candidate == null || candidate.isEmpty()) return false;
        // Only the decoder's private lookup address is hidden. Real candidates
        // such as "2号楼" and "3D打印" are valid user text and must remain visible.
        return mode != Mode.T9 || !candidate.matches("[0-9'\\s]+");
    }
    public int firstCommittableCandidateIndex(ChineseInputEngine.Snapshot state) {
        if (state == null) return -1;
        for (int i=0;i<state.candidates.size();i++)
            if (displayableCandidate(state.candidates.get(i))) return i;
        return -1;
    }
    /**
     * Text safe to expose to the editor while composition is in progress.
     * T9 digits are an internal lookup code and must never appear in the host
     * document. Its first Chinese candidate is the visible composing text.
     */
    public String displayComposition(ChineseInputEngine.Snapshot state) {
        if (state == null) return "";
        if (mode == Mode.T9) {
            int index=firstCommittableCandidateIndex(state);
            return index < 0 ? "" : state.candidates.get(index);
        }
        return state.rawInput;
    }
    /** Keyboard-panel explanation only; never pass this string to the editor. */
    public String displayPreedit(ChineseInputEngine.Snapshot state) {
        if (state == null) return "";
        if (mode == Mode.T9 && !state.candidateComments.isEmpty()) {
            String spelling=state.candidateComments.get(0);
            if (spelling != null && !spelling.trim().isEmpty()) {
                int selectedEnd=Math.max(0,Math.min(state.selectionStart,state.preedit.length()));
                String prefix=state.preedit.substring(0,selectedEnd);
                return hideT9LookupDigits(prefix+spelling);
            }
        }
        String value=state.preedit.isEmpty() ? state.rawInput : state.preedit;
        // A T9 lookup can temporarily have no dictionary match. Keep its digits
        // inside the decoder instead of flashing them as if they were typed text,
        // but still make the live state visible so it cannot look frozen.
        if (mode == Mode.T9) {
            String safe=hideT9LookupDigits(value);
            return safe.isEmpty() && !state.numericInput.isEmpty() ? "未匹配 · 继续输入将自动分段" : safe;
        }
        return value;
    }
    private static String hideT9LookupDigits(String value) {
        if (value == null || value.isEmpty()) return "";
        if (value.matches("[0-9'\\s]+")) return "";
        return value.replaceAll("[0-9]+", "…").replaceAll("'{2,}", "'");
    }
    public String select(int index) {
        if (!chinese()) return null;
        ChineseInputEngine.Snapshot state = engine.snapshot();
        if (index < 0 || index >= state.candidates.size()) return null;
        return engine.select(index);
    }
    public void setLearningEnabled(boolean enabled) { engine.setLearningEnabled(enabled); }
    public boolean activateSyllable(int index) {
        return mode == Mode.T9 && engine.activateSyllable(index);
    }
    public boolean chooseSyllable(String syllable) {
        return mode == Mode.T9 && engine.chooseSyllable(syllable);
    }
    public void reset() { engine.reset(); }
    public void backspace() { engine.backspace(); }
    public void nextPage() { engine.nextPage(); }
    public void previousPage() { engine.previousPage(); }
    public void close() { engine.close(); }
}
