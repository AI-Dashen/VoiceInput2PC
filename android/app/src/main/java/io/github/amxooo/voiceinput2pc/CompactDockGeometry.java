package io.github.amxooo.voiceinput2pc;

/** Pure geometry for a three-slot vertical dock, microphone in the middle slot. */
public final class CompactDockGeometry {
    public static final int SLOT_DP = 48;
    public static final int EXPANDED_SLOTS = 3;
    public static final int EXPANDED_HEIGHT_DP = SLOT_DP * EXPANDED_SLOTS;
    public static final float DEFAULT_X_FRACTION = .9f;
    public static final float DEFAULT_Y_FRACTION = .75f;

    private CompactDockGeometry() {}

    public static final class Placement {
        public final int left, top, width, height;
        /** Offset of the microphone touch area within the returned dock rectangle. */
        public final int micTop, micHeight;
        /** Microphone center in viewport coordinates, including half pixels for odd slots. */
        public final float micCenterX, micCenterY;
        /** Actual normalized microphone center after boundary clamping. */
        public final float xFraction, yFraction;
        public final boolean expanded, canExpand;

        private Placement(int left, int top, int width, int height, int micTop,
                int micHeight, int viewportWidth, int viewportHeight,
                boolean expanded, boolean canExpand) {
            this.left = left; this.top = top; this.width = width; this.height = height;
            this.micTop = micTop; this.micHeight = micHeight;
            this.micCenterX = left + width / 2f;
            this.micCenterY = top + micTop + micHeight / 2f;
            this.xFraction = fractionX(micCenterX, viewportWidth);
            this.yFraction = fractionY(micCenterY, viewportHeight);
            this.expanded = expanded; this.canExpand = canExpand;
        }
    }

    /**
     * Fractions describe the microphone center relative to the viewport, not dock top-left.
     * Pass all sizes in the same unit (Android callers normally convert 48dp to pixels).
     * When three slots fit, BOTH states reserve one slot above/below the microphone.
     * Consequently toggling expansion at an edge never moves the microphone.
     *
     * A viewport smaller than three slots falls back to a single touch area; callers
     * must honor Placement.expanded. A viewport narrower/shorter than one slot clips
     * that single area to the available size. Zero/negative viewport extents are empty.
     * NaN restores the corresponding default, infinities clamp to their nearest edge.
     */
    public static Placement layout(int windowWidth, int windowHeight, int slotPx,
            float xFraction, float yFraction, boolean expanded) {
        if (slotPx <= 0) throw new IllegalArgumentException("slotPx must be positive");
        int w = Math.max(0, windowWidth), h = Math.max(0, windowHeight);
        if (w == 0 || h == 0) return new Placement(0, 0, 0, 0, 0, 0, w, h, false, false);
        int width = Math.min(slotPx, w), micHeight = Math.min(slotPx, h);
        boolean canExpand = w >= slotPx && (long) h >= (long) slotPx * EXPANDED_SLOTS;
        boolean showExpanded = expanded && canExpand;
        int left = centeredStart(w, width, fraction(xFraction, DEFAULT_X_FRACTION));
        int micAbsoluteTop = centeredStart(h, micHeight, fraction(yFraction, DEFAULT_Y_FRACTION));
        if (canExpand) micAbsoluteTop = Math.max(slotPx, Math.min(h - 2 * slotPx, micAbsoluteTop));
        int micTop = showExpanded ? slotPx : 0;
        int height = showExpanded ? slotPx * EXPANDED_SLOTS : micHeight;
        return new Placement(left, micAbsoluteTop - micTop, width, height, micTop,
                micHeight, w, h, showExpanded, canExpand);
    }

    /** Convert a dragged microphone center to a safe, persistable viewport fraction. */
    public static float fractionX(float center, int windowWidth) {
        return fractionFromCenter(center, windowWidth, DEFAULT_X_FRACTION);
    }

    public static float fractionY(float center, int windowHeight) {
        return fractionFromCenter(center, windowHeight, DEFAULT_Y_FRACTION);
    }

    private static float fractionFromCenter(float center, int extent, float fallback) {
        return extent <= 0 ? fallback : fraction(center / extent, fallback);
    }

    private static float fraction(float value, float fallback) {
        return Float.isNaN(value) ? fallback : Math.max(0f, Math.min(1f, value));
    }

    private static int centeredStart(int extent, int size, float fraction) {
        // Double math prevents float rounding or intermediate overflow at large int sizes.
        long wanted = Math.round((double) extent * fraction - size / 2.0);
        return (int) Math.max(0L, Math.min((long) extent - size, wanted));
    }
}
