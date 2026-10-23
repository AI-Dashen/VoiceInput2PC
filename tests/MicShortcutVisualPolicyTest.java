import io.github.amxooo.voiceinput2pc.InputSession;
import io.github.amxooo.voiceinput2pc.MicShortcutMenuGeometry;
import io.github.amxooo.voiceinput2pc.MicStatusVisualPolicy;

public final class MicShortcutVisualPolicyTest {
    public static void main(String[] args) {
        check(MicStatusVisualPolicy.ring(InputSession.State.IDLE) == MicStatusVisualPolicy.Ring.READY, "idle is ready ring");
        check(MicStatusVisualPolicy.ring(InputSession.State.LISTENING) == MicStatusVisualPolicy.Ring.RECORDING, "listening is recording ring");
        check(MicStatusVisualPolicy.ring(InputSession.State.RECOGNIZING) == MicStatusVisualPolicy.Ring.RECOGNIZING, "recognizing has progress ring");
        check(MicStatusVisualPolicy.ring(InputSession.State.CANCELLED) == MicStatusVisualPolicy.Ring.MUTED, "cancelled is muted");
        check(MicStatusVisualPolicy.ring(InputSession.State.COMPOSING) == MicStatusVisualPolicy.Ring.READY, "composing remains ready");
        check(MicStatusVisualPolicy.ring(InputSession.State.PREVIEW) == MicStatusVisualPolicy.Ring.READY, "preview remains ready");
        check(MicStatusVisualPolicy.color(MicStatusVisualPolicy.Ring.READY) == MicStatusVisualPolicy.GREEN, "ready green");
        check(MicStatusVisualPolicy.color(MicStatusVisualPolicy.Ring.RECORDING) == MicStatusVisualPolicy.RED, "recording red");
        check(MicStatusVisualPolicy.color(MicStatusVisualPolicy.Ring.RECOGNIZING) == MicStatusVisualPolicy.AMBER, "recognizing amber");
        check(MicStatusVisualPolicy.GREEN == 0xff51c832, "fresh leaf green matches approved reference");
        check(MicStatusVisualPolicy.RED == 0xffff4c40, "recording red remains distinct");
        check(MicStatusVisualPolicy.AMBER == 0xffffb918, "recognizing amber remains distinct");
        float[][] center = MicShortcutMenuGeometry.layout(412, 280, 206, 182, 64, 48, 8);
        check(center.length == 4, "four unique shortcuts");
        for (float[] point : center) {
            check(point[0] >= 0 && point[0] <= 364, "x is inside IME bounds");
            check(point[1] >= 0 && point[1] <= 232, "y is inside IME bounds");
        }
        for (int i = 0; i < center.length; i++) for (int j = i + 1; j < center.length; j++) {
            float dx=Math.abs((center[i][0]+24)-(center[j][0]+24));
            float dy=Math.abs((center[i][1]+24)-(center[j][1]+24));
            check(dx >= 48 || dy >= 48, "48dp shortcut touch rectangles do not overlap");
        }
        // With a right-edge mic, the arc turns left but remains geometrically centered on it.
        float micX=390, micY=140;
        float[][] rightEdge = MicShortcutMenuGeometry.layout(412, 280, micX, micY, 64, 48, 8);
        float leftEndpointCenterX=(rightEdge[0][0]+rightEdge[3][0]+48)/2f;
        float endpointCenterY=(rightEdge[0][1]+rightEdge[3][1]+48)/2f;
        check(Math.abs(leftEndpointCenterX-micX)<3, "right-edge arc keeps its center on the mic instead of shifting away");
        check(Math.abs(endpointCenterY-micY)<3, "side arc wraps above and below the mic");
        float[][] topEdge = MicShortcutMenuGeometry.layout(360, 280, 20, 28, 64, 48, 8);
        float[][] bottomEdge = MicShortcutMenuGeometry.layout(360, 280, 340, 266, 64, 48, 8);
        for (float[] point : topEdge) check(point[0] >= 0 && point[0] <= 312 && point[1] >= 0 && point[1] <= 232, "top-edge menu is bounded");
        for (float[] point : bottomEdge) check(point[0] >= 0 && point[0] <= 312 && point[1] >= 0 && point[1] <= 232, "bottom-edge menu is bounded");
        System.out.println("mic shortcut visual checks passed");
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
