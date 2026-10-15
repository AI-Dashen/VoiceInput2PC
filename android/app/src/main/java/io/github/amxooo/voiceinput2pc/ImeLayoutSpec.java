package io.github.amxooo.voiceinput2pc;

/** Single source of truth for the visible IME proportions. Values are density-independent pixels. */
public final class ImeLayoutSpec {
    public static final int PORTRAIT_PANEL_HEIGHT_DP = 260;
    public static final int LANDSCAPE_PANEL_HEIGHT_DP = 252;
    public static final int RAIL_WIDTH_DP = 48;
    public static final int STATUS_HEIGHT_DP = 28;
    public static final int COMPOSITION_HEIGHT_DP = 30;
    public static final int CANDIDATE_HEIGHT_DP = 38;
    public static final int ACTION_HEIGHT_DP = 44;
    public static final int UTILITY_HEIGHT_DP = 44;

    private ImeLayoutSpec() { }

    public static int panelHeightDp(boolean landscape) {
        return landscape ? LANDSCAPE_PANEL_HEIGHT_DP : PORTRAIT_PANEL_HEIGHT_DP;
    }

    public static int centerWidthDp(int windowWidthDp) {
        return Math.max(0, windowWidthDp - 2 * RAIL_WIDTH_DP - 10);
    }
}
