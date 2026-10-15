package io.github.amxooo.voiceinput2pc;

public final class ImeLayoutSpecTest {
    public static void main(String[] args) {
        check(ImeLayoutSpec.panelHeightDp(false) == 260, "portrait height");
        check(ImeLayoutSpec.panelHeightDp(true) == 252, "landscape height");
        check(ImeLayoutSpec.RAIL_WIDTH_DP == 48, "rail touch width");
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
        System.out.println("IME layout checks passed");
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
