package io.github.amxooo.voiceinput2pc;

/** State-to-ring mapping for the compact microphone control. */
public final class MicStatusVisualPolicy {
    public enum Ring { READY, RECORDING, RECOGNIZING, MUTED }
    public static final int GREEN = 0xff51c832;
    public static final int RED = 0xffff4c40;
    public static final int AMBER = 0xffffb918;
    public static final int MUTED = 0xff78858d;
    private MicStatusVisualPolicy() {}

    public static Ring ring(InputSession.State state) {
        if (state == null) return Ring.MUTED;
        switch (state) {
            case LISTENING: return Ring.RECORDING;
            case RECOGNIZING: return Ring.RECOGNIZING;
            case CANCELLED: return Ring.MUTED;
            case IDLE:
            case COMPOSING:
            case PREVIEW:
            case COMMITTED: return Ring.READY;
            default: return Ring.MUTED;
        }
    }
    public static int color(Ring ring) {
        if (ring == null) return MUTED;
        switch (ring) {
            case RECORDING: return RED;
            case RECOGNIZING: return AMBER;
            case READY: return GREEN;
            case MUTED:
            default: return MUTED;
        }
    }
    public static String label(Ring ring) {
        if (ring == null) return "状态未知";
        switch (ring) {
            case RECORDING: return "正在录音";
            case RECOGNIZING: return "正在识别";
            case READY: return "麦克风可用";
            case MUTED:
            default: return "语音暂不可用";
        }
    }
}
