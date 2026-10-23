package io.github.amxooo.voiceinput2pc;

/** Raw terminal connections accept committed text and key events, not preedit. */
public final class EditorCompatibility {
    private EditorCompatibility() {}
    public static boolean rawEditor(int inputType, String packageName) {
        return (inputType & 15) == 0 || "com.termux".equals(packageName);
    }
    public static boolean inlineComposition(int inputType, String packageName, boolean t9) {
        return !t9 && !rawEditor(inputType, packageName);
    }
}
