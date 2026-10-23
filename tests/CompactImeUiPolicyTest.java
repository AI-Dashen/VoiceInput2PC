package io.github.amxooo.voiceinput2pc;

public final class CompactImeUiPolicyTest {

    private CompactImeUiPolicyTest() {
    }

    public static void main(String[] args) {
        testKeyboardHeights();
        testNoAccumulation();
        testComposingToggleKeepsHeight();
        testMicrophoneColors();
        testBusyStatesBlockActions();
        testPreviewAllowsRetryActions();
        testIdleActions();
        testTouchTarget();
        testMicrophoneStyleConstraints();
        System.out.println("CompactImeUiPolicyTest: all passed");
    }

    private static void testKeyboardHeights() {
        eq(268, CompactImeUiPolicy.keyboardHeightDp(false, false, false), "portrait base");
        eq(268, CompactImeUiPolicy.keyboardHeightDp(false, true, false), "portrait composing");
        eq(316, CompactImeUiPolicy.keyboardHeightDp(false, false, true), "portrait remote");
        eq(316, CompactImeUiPolicy.keyboardHeightDp(false, true, true), "portrait composing+remote");
        eq(236, CompactImeUiPolicy.keyboardHeightDp(true, false, false), "landscape base");
        eq(236, CompactImeUiPolicy.keyboardHeightDp(true, true, false), "landscape composing");
        eq(284, CompactImeUiPolicy.keyboardHeightDp(true, false, true), "landscape remote");
        eq(284, CompactImeUiPolicy.keyboardHeightDp(true, true, true), "landscape composing+remote");
    }

    private static void testNoAccumulation() {
        for (int i = 0; i < 5; i++) {
            eq(316, CompactImeUiPolicy.keyboardHeightDp(false, true, true), "repeat portrait full");
            eq(268, CompactImeUiPolicy.keyboardHeightDp(false, false, false), "repeat portrait base");
            eq(284, CompactImeUiPolicy.keyboardHeightDp(true, true, true), "repeat landscape full");
            eq(236, CompactImeUiPolicy.keyboardHeightDp(true, false, false), "repeat landscape base");
        }
    }

    private static void testComposingToggleKeepsHeight() {
        for (boolean landscape : new boolean[]{false, true}) {
            for (boolean remote : new boolean[]{false, true}) {
                int expected = (landscape ? 236 : 268) + (remote ? 48 : 0);
                for (int iteration = 0; iteration < 100; iteration++) {
                    eq(expected, CompactImeUiPolicy.keyboardHeightDp(landscape, false, remote),
                            "before composition " + iteration);
                    eq(expected, CompactImeUiPolicy.keyboardHeightDp(landscape, true, remote),
                            "candidate visible " + iteration);
                    eq(expected, CompactImeUiPolicy.keyboardHeightDp(landscape, false, remote),
                            "after candidate commit " + iteration);
                }
            }
        }
    }

    private static void testMicrophoneColors() {
        eqHex(0xffc83a41, CompactImeUiPolicy.microphoneColor(InputSession.State.LISTENING), "listening color");
        eqHex(0xffb9801e, CompactImeUiPolicy.microphoneColor(InputSession.State.RECOGNIZING), "recognizing color");
        eqHex(0xff264a5c, CompactImeUiPolicy.microphoneColor(InputSession.State.IDLE), "idle color");
        eqHex(0xff264a5c, CompactImeUiPolicy.microphoneColor(InputSession.State.PREVIEW), "preview color");
    }

    private static void testBusyStatesBlockActions() {
        InputSession.State[] busy = {InputSession.State.LISTENING, InputSession.State.RECOGNIZING};
        boolean[] flags = {false, true};
        for (InputSession.State s : busy) {
            for (boolean remote : flags) {
                for (boolean canSend : flags) {
                    check(!CompactImeUiPolicy.canRunEditorAction(s, remote, canSend),
                            "busy " + s + " remote=" + remote + " canSend=" + canSend + " must be blocked");
                }
            }
        }
    }

    private static void testPreviewAllowsRetryActions() {
        InputSession.State p = InputSession.State.PREVIEW;
        check(CompactImeUiPolicy.canRunEditorAction(p, false, false), "preview local allowed");
        check(CompactImeUiPolicy.canRunEditorAction(p, false, true), "preview local allowed (flag ignored)");
        check(CompactImeUiPolicy.canRunEditorAction(p, true, true), "preview remote sendable allowed");
        check(!CompactImeUiPolicy.canRunEditorAction(p, true, false), "preview remote unsendable blocked");
    }

    private static void testIdleActions() {
        InputSession.State i = InputSession.State.IDLE;
        check(CompactImeUiPolicy.canRunEditorAction(i, false, false), "idle local allowed");
        check(CompactImeUiPolicy.canRunEditorAction(i, true, true), "idle remote sendable allowed");
        check(!CompactImeUiPolicy.canRunEditorAction(i, true, false), "idle remote unsendable blocked");
    }

    private static void testTouchTarget() {
        eq(48, CompactImeUiPolicy.MIC_TOUCH_DP, "touch dp");
        eq(30, CompactImeUiPolicy.MIC_DIAMETER_DP, "diameter dp");
        eq(80, CompactImeUiPolicy.DOCK_BOTTOM_DP, "dock bottom dp");
        check(CompactImeUiPolicy.MIC_TOUCH_DP >= 48, "touch target at least 48dp");
        check(CompactImeUiPolicy.MIC_TOUCH_DP >= CompactImeUiPolicy.MIC_DIAMETER_DP, "touch target >= icon");
    }

    private static void testMicrophoneStyleConstraints() {
        // Reference production constants from MicStyleSpec
        int baseBg = UniversalInputMethodService.MicStyleSpec.BG_IDLE;
        int pressedBg = UniversalInputMethodService.MicStyleSpec.BG_PRESSED;
        int outerStroke = UniversalInputMethodService.MicStyleSpec.STROKE_OUTER;
        int innerStroke = UniversalInputMethodService.MicStyleSpec.STROKE_INNER;
        float iconRadius = UniversalInputMethodService.MicStyleSpec.ICON_RADIUS;
        float outerStrokeWidth = UniversalInputMethodService.MicStyleSpec.STROKE_OUTER_WIDTH;
        float innerStrokeWidth = UniversalInputMethodService.MicStyleSpec.STROKE_INNER_WIDTH;
        float stateStrokeWidth = UniversalInputMethodService.MicStyleSpec.STATE_STROKE_WIDTH;

        // Verify opacity levels are restrained (not fully opaque)
        int baseAlpha = (baseBg >> 24) & 0xff;
        int pressedAlpha = (pressedBg >> 24) & 0xff;
        check(baseAlpha <= 0x66, "base background: <= 40% opacity");
        check(pressedAlpha <= 0x8c, "pressed background: <= 55% opacity");
        check(baseAlpha >= 0x33, "base background: >= 20% opacity for visibility");

        // Verify stroke weights are thin (flat design)
        check(outerStrokeWidth <= 1.0f, "outer stroke: <= 1dp for flatness");
        check(innerStrokeWidth <= 1.0f, "inner stroke: <= 1dp for flatness");
        check(stateStrokeWidth <= 2.0f, "state indicator: <= 2dp for clarity without bulk");

        // Verify icon size leaves breathing room in 48dp touch target
        float touchRadius = CompactImeUiPolicy.MIC_TOUCH_DP / 2f;
        check(iconRadius < touchRadius, "icon visual size < touch target");
        check(iconRadius >= 12f, "icon visual size >= 12dp for recognizability");

        // Verify contrast helper strokes for dark/light backgrounds
        int outerAlpha = (outerStroke >> 24) & 0xff;
        int innerAlpha = (innerStroke >> 24) & 0xff;
        check(outerAlpha >= 0x30 && outerAlpha <= 0x60, "outer stroke: 30-60% black for definition");
        check(innerAlpha >= 0x20 && innerAlpha <= 0x50, "inner stroke: 20-50% white for highlight");

        // Verify state colors are distinct (from CompactImeUiPolicy)
        int listening = CompactImeUiPolicy.microphoneColor(InputSession.State.LISTENING);
        int recognizing = CompactImeUiPolicy.microphoneColor(InputSession.State.RECOGNIZING);
        int idle = CompactImeUiPolicy.microphoneColor(InputSession.State.IDLE);
        check(listening != recognizing && recognizing != idle && listening != idle, "state colors distinct");
    }

    private static void eq(int expected, int actual, String msg) {
        if (expected != actual) {
            throw new AssertionError(msg + ": expected " + expected + " but was " + actual);
        }
    }

    private static void eqHex(int expected, int actual, String msg) {
        if (expected != actual) {
            throw new AssertionError(msg + ": expected 0x" + Integer.toHexString(expected)
                    + " but was 0x" + Integer.toHexString(actual));
        }
    }

    private static void check(boolean cond, String msg) {
        if (!cond) {
            throw new AssertionError(msg);
        }
    }
}
