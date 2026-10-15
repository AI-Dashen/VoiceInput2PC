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
        if (chinese()) {
            if (key == null || key.length() != 1) return "";
            char value=key.charAt(0);
            boolean composingKey=mode == Mode.T9
                    ? (value >= '2' && value <= '9') || value == '\''
                    : (value >= 'a' && value <= 'z') || value == '\'';
            if (!composingKey) return key;
            if (!engine.snapshot().available) return null;
            engine.input(value);
            return null;
        }
        return mode == Mode.ENGLISH && uppercase ? key.toUpperCase(java.util.Locale.ROOT) : key;
    }
    public ChineseInputEngine.Snapshot snapshot() { return engine.snapshot(); }
    /**
     * Text safe to expose to the editor while composition is in progress.
     * T9 digits are an internal lookup code and must never appear in the host
     * document. Its first Chinese candidate is the visible composing text.
     */
    public String displayComposition(ChineseInputEngine.Snapshot state) {
        if (state == null) return "";
        if (mode == Mode.T9)
            return state.candidates.isEmpty() ? "" : state.candidates.get(0);
        return state.rawInput;
    }
    public String select(int index) {
        if (!chinese()) return null;
        ChineseInputEngine.Snapshot state = engine.snapshot();
        if (index < 0 || index >= state.candidates.size()) return null;
        return engine.select(index);
    }
    public void reset() { engine.reset(); }
    public void backspace() { engine.backspace(); }
    public void nextPage() { engine.nextPage(); }
    public void previousPage() { engine.previousPage(); }
    public void close() { engine.close(); }
}
