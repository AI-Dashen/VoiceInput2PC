package io.github.amxooo.voiceinput2pc;

/** Single source of truth for the visible IME proportions. Values are density-independent pixels. */
public final class ImeLayoutSpec {
    public static final int PORTRAIT_PANEL_HEIGHT_DP = 260;
    public static final int LANDSCAPE_PANEL_HEIGHT_DP = 252;
    public static final int RAIL_WIDTH_DP = 48;
    public static final int ACTION_RAIL_WIDTH_DP = 48;
    public static final int STATUS_HEIGHT_DP = 28;
    public static final int COMPOSITION_HEIGHT_DP = 30;
    public static final int CANDIDATE_HEIGHT_DP = 38;
    public static final int ACTION_HEIGHT_DP = 44;
    public static final int UTILITY_HEIGHT_DP = 44;
    public static final int DRAFT_WARNING_CODE_POINTS = 400;
    public static final int DRAFT_MAX_CODE_POINTS = 500;
    public static final int COMPOSITION_TEXT_MIN_SP = 12;
    public static final int COMPOSITION_TEXT_MAX_SP = 16;
    public static final int KEY_TEXT_MIN_SP = 12;
    public static final int KEY_TEXT_MAX_SP = 17;

    private ImeLayoutSpec() { }

    public static int panelHeightDp(boolean landscape) {
        return landscape ? LANDSCAPE_PANEL_HEIGHT_DP : PORTRAIT_PANEL_HEIGHT_DP;
    }

    public static int centerWidthDp(int windowWidthDp) {
        return Math.max(0, windowWidthDp - 2 * RAIL_WIDTH_DP - 10);
    }

    /** Keep short dictation prominent while allowing long drafts to stay readable. */
    public static int draftTextSizeSp(int codePoints) {
        if (codePoints <= 30) return 18;
        if (codePoints <= 120) return 16;
        return 14;
    }
}
