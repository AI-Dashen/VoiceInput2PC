package io.github.amxooo.voiceinput2pc;

/**
 * Pure layout/state policy for the compact IME UI. No Android dependencies.
 */
public final class CompactImeUiPolicy {

    public static final int MIC_TOUCH_DP = 48;
    public static final int MIC_DIAMETER_DP = 30;
    public static final int DOCK_BOTTOM_DP = 80;

    private static final int PORTRAIT_BASE_DP = 220;
    private static final int LANDSCAPE_BASE_DP = 188;
    private static final int CANDIDATE_STRIP_DP = 48;
    private static final int REMOTE_DRAFT_EXTRA_DP = 48;

    private static final int COLOR_LISTENING = 0xffc83a41;
    private static final int COLOR_RECOGNIZING = 0xffb9801e;
    private static final int COLOR_IDLE = 0xff264a5c;

    private CompactImeUiPolicy() {
    }

    /**
     * Stateless: result depends only on arguments, so repeated calls never accumulate.
     */
    public static int keyboardHeightDp(boolean landscape, boolean composing, boolean remote) {
        int height = landscape ? LANDSCAPE_BASE_DP : PORTRAIT_BASE_DP;
        // Reserve preedit20 + candidates28 even when empty: showing candidates must
        // not resize the host Activity/terminal or move its cursor and viewport.
        height += CANDIDATE_STRIP_DP;
        if (remote) {
            height += REMOTE_DRAFT_EXTRA_DP;
        }
        return height;
    }

    public static int microphoneColor(InputSession.State state) {
        if (state == InputSession.State.LISTENING) {
            return COLOR_LISTENING;
        }
        if (state == InputSession.State.RECOGNIZING) {
            return COLOR_RECOGNIZING;
        }
        return COLOR_IDLE;
    }

    public static boolean canRunEditorAction(InputSession.State state, boolean remote, boolean remoteCanSend) {
        if (isVoiceBusy(state)) {
            return false;
        }
        return !remote || remoteCanSend;
    }

    private static boolean isVoiceBusy(InputSession.State state) {
        return state == InputSession.State.LISTENING || state == InputSession.State.RECOGNIZING;
    }
}
