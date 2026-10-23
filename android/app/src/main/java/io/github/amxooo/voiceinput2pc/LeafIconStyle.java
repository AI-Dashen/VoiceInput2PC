package io.github.amxooo.voiceinput2pc;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;

/** One transparent vector treatment shared by microphone and floating actions. */
public final class LeafIconStyle {
    public static final int LEAF = 0xff51c832;
    public static final int HIGHLIGHT = 0xff9be85c;
    public static final int INK = 0xff264434;
    public static final float RING_RADIUS_DP = 17f;
    private LeafIconStyle() {}

    /** Draws strokes only: the host application's content stays visible in the middle. */
    public static void drawRing(Canvas canvas, Paint paint, int color, boolean broken, float phase, boolean pressed) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        RectF bounds = new RectF(-17f,-17f,17f,17f);
        int[] colors = {color, color, 0xc0264434, color, 0xffedffdf};
        int[] alphas = {18, 34, 190, 255, 225};
        float[] widths = {8f, 5.5f, 3.4f, 2.4f, .7f};
        for (int layer=0;layer<colors.length;layer++) {
            paint.setColor(colors[layer]);
            paint.setAlpha(pressed && layer<2 ? Math.min(255,alphas[layer]*2) : alphas[layer]);
            paint.setStrokeWidth(widths[layer]);
            if (broken) {
                for (int arc=0;arc<3;arc++) canvas.drawArc(bounds,-90+phase*360+arc*120,91,false,paint);
            } else canvas.drawCircle(0,0,RING_RADIUS_DP,paint);
        }
        // Fresh leaf edge catches light without an opaque disc or software blur.
        if (!broken) {
            paint.setColor(color==LEAF?HIGHLIGHT:Color.WHITE);paint.setAlpha(210);paint.setStrokeWidth(.9f);
            canvas.drawArc(-16.25f,-16.25f,16.25f,16.25f,205,105,false,paint);
        }
        paint.setAlpha(255);
    }

    public static RippleDrawable keyBackground(Context context, boolean action) {
        float density=context.getResources().getDisplayMetrics().density;
        GradientDrawable face=new GradientDrawable();face.setColor(action?0xffeff8e9:0xfffffefd);
        face.setCornerRadius(7*density);face.setStroke(Math.max(1,Math.round(.7f*density)),action?0xffbad6ae:0xffe2e8e0);
        GradientDrawable mask=new GradientDrawable();mask.setColor(Color.WHITE);mask.setCornerRadius(7*density);
        return new RippleDrawable(ColorStateList.valueOf(0x3051c832),face,mask);
    }
}
