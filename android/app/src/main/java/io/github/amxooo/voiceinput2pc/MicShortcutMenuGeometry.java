package io.github.amxooo.voiceinput2pc;

/** Bounded placement for four 48dp shortcuts on a semicircular arc centered on the mic. */
public final class MicShortcutMenuGeometry {
    public static final int ITEM_COUNT = 4;
    private enum Arc { UP, DOWN, LEFT, RIGHT }
    private static final float[] UP_ANGLES = {180f, 240f, 300f, 360f};
    private static final float[] DOWN_ANGLES = {0f, 60f, 120f, 180f};
    private static final float[] LEFT_ANGLES = {90f, 150f, 210f, 270f};
    private static final float[] RIGHT_ANGLES = {-90f, -30f, 30f, 90f};
    private MicShortcutMenuGeometry() {}

    /** Returns item top-left coordinates in px, ordered device/history/settings/switch. */
    public static float[][] layout(float width, float height, float micX, float micY,
                                   float radius, float itemSize, float margin) {
        float half = itemSize / 2f;
        float horizontalLeft = micX - half - margin;
        float horizontalRight = width - micX - half - margin;
        float verticalUp = micY - half - margin;
        float verticalDown = height - micY - half - margin;
        boolean sideFitsVertically = verticalUp >= radius && verticalDown >= radius;

        // Keep the arc's center on the mic. Near a horizontal edge, turn the arc
        // toward the available side instead of translating the entire menu away.
        Arc arc;
        if (sideFitsVertically && horizontalRight < radius && horizontalLeft >= radius) {
            arc = Arc.LEFT;
        } else if (sideFitsVertically && horizontalLeft < radius && horizontalRight >= radius) {
            arc = Arc.RIGHT;
        } else if (verticalUp >= radius) {
            arc = Arc.UP;
        } else if (verticalDown >= radius) {
            arc = Arc.DOWN;
        } else if (sideFitsVertically && horizontalLeft >= horizontalRight) {
            arc = Arc.LEFT;
        } else if (sideFitsVertically) {
            arc = Arc.RIGHT;
        } else {
            arc = verticalUp >= verticalDown ? Arc.UP : Arc.DOWN;
        }
        float[] angles = angles(arc);
        float[][] points = new float[ITEM_COUNT][2];
        for (int i = 0; i < ITEM_COUNT; i++) {
            double angle = Math.toRadians(angles[i]);
            float x = micX + radius * (float) Math.cos(angle);
            float y = micY + radius * (float) Math.sin(angle);
            points[i][0] = clamp(x - half, 0, Math.max(0, width - itemSize));
            points[i][1] = clamp(y - half, 0, Math.max(0, height - itemSize));
        }
        return points;
    }
    private static float[] angles(Arc arc) {
        switch (arc) {
            case DOWN: return DOWN_ANGLES;
            case LEFT: return LEFT_ANGLES;
            case RIGHT: return RIGHT_ANGLES;
            default: return UP_ANGLES;
        }
    }
    private static float clamp(float value, float min, float max) {
        if (max < min) return (min + max) / 2f;
        return Math.max(min, Math.min(max, value));
    }
}
