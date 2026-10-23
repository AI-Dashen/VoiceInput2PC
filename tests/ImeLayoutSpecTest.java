package io.github.amxooo.voiceinput2pc;

public final class ImeLayoutSpecTest {
    public static void main(String[] args) {
        check(ImeLayoutSpec.panelHeightDp(false) == 260, "portrait height");
        check(ImeLayoutSpec.panelHeightDp(true) == 252, "landscape height");
        check(ImeLayoutSpec.RAIL_WIDTH_DP == 48, "rail touch width");
        check(ImeLayoutSpec.ACTION_RAIL_WIDTH_DP == 48, "right-hand rail keeps useful touch width");
        check(ImeLayoutSpec.centerWidthDp(412) == 306, "wide logical center width");
        check(ImeLayoutSpec.centerWidthDp(360) == 254, "compact logical center width");
        int typingChrome=ImeLayoutSpec.COMPOSITION_HEIGHT_DP
                +ImeLayoutSpec.CANDIDATE_HEIGHT_DP+ImeLayoutSpec.UTILITY_HEIGHT_DP;
        check((ImeLayoutSpec.PORTRAIT_PANEL_HEIGHT_DP-typingChrome)/3>=48,
                "portrait typing rows retain useful height");
        check((ImeLayoutSpec.LANDSCAPE_PANEL_HEIGHT_DP-typingChrome)/3>=46,
                "landscape typing rows retain minimum touch height");
        int t9Chrome=ImeLayoutSpec.COMPOSITION_HEIGHT_DP+ImeLayoutSpec.CANDIDATE_HEIGHT_DP;
        check((ImeLayoutSpec.PORTRAIT_PANEL_HEIGHT_DP-t9Chrome)/4>=48,"portrait T9 rows");
        check(ImeLayoutSpec.draftTextSizeSp(30)==18,"short voice draft size");
        check(ImeLayoutSpec.draftTextSizeSp(31)==16,"medium voice draft size");
        check(ImeLayoutSpec.draftTextSizeSp(120)==16,"medium voice draft upper boundary");
        check(ImeLayoutSpec.draftTextSizeSp(121)==14,"long voice draft size");
        check(ImeLayoutSpec.DRAFT_WARNING_CODE_POINTS<ImeLayoutSpec.DRAFT_MAX_CODE_POINTS,
                "draft warning precedes hard cap");
        check(ImeLayoutSpec.COMPOSITION_HEIGHT_DP >= 28,
                "composition row has enough vertical room for its text");
        check(ImeLayoutSpec.COMPOSITION_TEXT_MIN_SP >= 12,
                "composition stays readable when it shrinks");
        check(ImeLayoutSpec.COMPOSITION_TEXT_MAX_SP <= 16,
                "composition text cannot overflow the compact row vertically");
        check(ImeLayoutSpec.COMPOSITION_TEXT_MIN_SP < ImeLayoutSpec.COMPOSITION_TEXT_MAX_SP,
                "composition can shrink before ellipsizing");
        check(ImeLayoutSpec.KEY_TEXT_MIN_SP >= 12, "letter keys remain readable");
        check(ImeLayoutSpec.KEY_TEXT_MAX_SP == 17, "letter keys keep the approved normal size");
        check(ImeLayoutSpec.KEY_TEXT_MIN_SP < ImeLayoutSpec.KEY_TEXT_MAX_SP,
                "letter keys can shrink when ten-key row is narrow");

        // ImeKeycapView hint constraints - reference actual constants from ImeKeycapView
        float HINT_SP = 8f, HINT_MIN_SP = 6.5f, HINT_PAD_DP = 3f, GLYPH_GAP_DP = 2f;
        check(HINT_SP < 10f, "hint: max size restrained below 10sp");
        check(HINT_MIN_SP >= 6f, "hint: min size readable");
        check(HINT_PAD_DP <= 5f, "hint: padding keeps hint in top-right corner");
        check(GLYPH_GAP_DP >= 1f, "hint: gap prevents overlap with main letter");
        check(HINT_SP > HINT_MIN_SP, "hint: size reduction range exists");

        System.out.println("IME layout + keycap hint checks passed");
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
