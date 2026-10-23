package io.github.amxooo.voiceinput2pc;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.TextView;

/**
 * Keycap that shows the main letter through the normal TextView path and an optional
 * secondary hint (for example a digit) in the top-right corner.
 * It adds no touch handling, no input logic and no layout changes.
 */
public class ImeKeycapView extends TextView {

    private static final float HINT_SP = 8f;
    private static final float HINT_MIN_SP = 6.5f;
    private static final float HINT_PAD_DP = 3f;
    private static final float GLYPH_GAP_DP = 2f;
    private static final float HINT_ALPHA_FACTOR = 0.48f;

    private final Paint hintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect tmpRect = new Rect();
    private final RectF glyphRect = new RectF();
    private final RectF hintRect = new RectF();
    private String keyHint;

    public ImeKeycapView(Context context, String letter, String hint) {
        super(context);
        keyHint = normalize(hint);
        hintPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.NORMAL));
        hintPaint.setTextAlign(Paint.Align.LEFT);
        setText(letter == null ? "" : letter);
    }

    /** Named setKeyHint because TextView.setHint is final and means something else. */
    public void setKeyHint(String hint) {
        String n = normalize(hint);
        if (!TextUtils.equals(n, keyHint)) {
            keyHint = n;
            invalidate();
        }
    }

    public String getKeyHint() {
        return keyHint;
    }

    private static String normalize(String s) {
        return s == null ? "" : s.trim();
    }

    private float dp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private float sp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, getResources().getDisplayMetrics());
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (TextUtils.isEmpty(keyHint)) {
            return;
        }
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }

        int base = getCurrentTextColor();
        int a = Math.round(Color.alpha(base) * HINT_ALPHA_FACTOR);
        hintPaint.setColor(Color.argb(a, Color.red(base), Color.green(base), Color.blue(base)));

        boolean hasGlyph = computeGlyphRect(glyphRect);
        float gap = dp(GLYPH_GAP_DP);
        if (hasGlyph) {
            glyphRect.inset(-gap, -gap);
        }
        float pad = dp(HINT_PAD_DP);
        int len = keyHint.length();

        canvas.save();
        // Draw in view-local coordinates regardless of any scroll offset.
        canvas.translate(getScrollX(), getScrollY());
        try {
            for (float size = HINT_SP; size >= HINT_MIN_SP - 0.01f; size -= 1f) {
                hintPaint.setTextSize(sp(size));
                hintPaint.getTextBounds(keyHint, 0, len, tmpRect);
                float inkW = tmpRect.width();
                float inkH = tmpRect.height();
                float right = w - pad;
                float top = pad;
                float left = right - inkW;
                float bottom = top + inkH;
                if (left < 0f || bottom > h) {
                    continue;
                }
                hintRect.set(left, top, right, bottom);
                if (hasGlyph && RectF.intersects(hintRect, glyphRect)) {
                    continue;
                }
                float x = left - tmpRect.left;
                float baseline = top - tmpRect.top;
                canvas.drawText(keyHint, 0, len, x, baseline, hintPaint);
                return;
            }
            // No non-overlapping placement found: skip the hint rather than cover the letter.
        } finally {
            canvas.restore();
        }
    }

    /** Computes where the main text is drawn, in view-local coordinates (scroll excluded). */
    private boolean computeGlyphRect(RectF out) {
        Layout layout = getLayout();
        CharSequence text = getText();
        if (layout == null || text == null || text.length() == 0 || layout.getLineCount() == 0) {
            return false;
        }
        float x0 = getCompoundPaddingLeft();
        float y0 = getExtendedPaddingTop() + verticalOffset(layout);
        int lines = layout.getLineCount();
        if (lines == 1) {
            String s = text.toString();
            getPaint().getTextBounds(s, 0, s.length(), tmpRect);
            float lineLeft = layout.getLineLeft(0);
            float baseline = layout.getLineBaseline(0);
            float inkLeft = lineLeft + tmpRect.left;
            float inkRight = Math.max(lineLeft + tmpRect.right, layout.getLineRight(0));
            out.set(x0 + inkLeft, y0 + baseline + tmpRect.top,
                    x0 + inkRight, y0 + baseline + tmpRect.bottom);
        } else {
            float l = Float.MAX_VALUE;
            float r = -Float.MAX_VALUE;
            for (int i = 0; i < lines; i++) {
                l = Math.min(l, layout.getLineLeft(i));
                r = Math.max(r, layout.getLineRight(i));
            }
            out.set(x0 + l, y0 + layout.getLineTop(0), x0 + r, y0 + layout.getLineBottom(lines - 1));
        }
        return true;
    }

    /** Mirrors TextView vertical gravity placement of the text layout. */
    private int verticalOffset(Layout layout) {
        int gravity = getGravity() & Gravity.VERTICAL_GRAVITY_MASK;
        if (gravity == Gravity.TOP) {
            return 0;
        }
        int boxHeight = getHeight() - getExtendedPaddingTop() - getExtendedPaddingBottom();
        int textHeight = layout.getHeight();
        if (textHeight >= boxHeight) {
            return 0;
        }
        if (gravity == Gravity.BOTTOM) {
            return boxHeight - textHeight;
        }
        return (boxHeight - textHeight) >> 1;
    }
}
