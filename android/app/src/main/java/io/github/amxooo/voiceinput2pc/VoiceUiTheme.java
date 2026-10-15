package io.github.amxooo.voiceinput2pc;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Shared visual language for the app-owned management and transfer screens. */
final class VoiceUiTheme {
    static final int PAPER = Color.rgb(246, 239, 230);
    static final int SURFACE = Color.rgb(255, 252, 247);
    static final int INK = Color.rgb(39, 34, 29);
    static final int MUTED = Color.rgb(105, 101, 94);
    static final int BROWN = Color.rgb(117, 79, 42);
    static final int GREEN = Color.rgb(22, 112, 91);
    static final int BORDER = Color.rgb(225, 210, 192);
    static final int SOFT_GREEN = Color.rgb(229, 238, 233);
    static final int SOFT_BROWN = Color.rgb(241, 231, 219);

    private VoiceUiTheme() {}

    static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static GradientDrawable surface(Context context, int color, int border, int radius) {
        GradientDrawable result = new GradientDrawable();
        result.setColor(color);
        result.setCornerRadius(dp(context, radius));
        if (border != 0) result.setStroke(dp(context, 1), border);
        return result;
    }

    static TextView text(Context context, String value, int size, int color) {
        TextView result = new TextView(context);
        result.setText(value);
        result.setTextSize(size);
        result.setTextColor(color);
        result.setGravity(Gravity.START);
        return result;
    }

    static TextView title(Context context, String value, int size) {
        TextView result = text(context, value, size, INK);
        result.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return result;
    }

    static LinearLayout card(Context context) {
        LinearLayout result = new LinearLayout(context);
        result.setOrientation(LinearLayout.VERTICAL);
        result.setPadding(dp(context, 16), dp(context, 14), dp(context, 16), dp(context, 14));
        result.setBackground(surface(context, SURFACE, BORDER, 18));
        return result;
    }

    static Button button(Context context, String value, boolean primary) {
        Button result = new Button(context);
        result.setText(value);
        result.setAllCaps(false);
        result.setTextSize(14);
        result.setMinWidth(0);
        result.setMinimumWidth(0);
        result.setMinHeight(0);
        result.setMinimumHeight(0);
        result.setPadding(dp(context, 12), 0, dp(context, 12), 0);
        result.setTextColor(primary ? Color.WHITE : BROWN);
        result.setBackgroundTintList(null);
        result.setBackground(surface(context, primary ? BROWN : SURFACE,
                primary ? 0 : BORDER, 22));
        return result;
    }

    static TextView statusChip(Context context, String value, boolean ready) {
        TextView result = text(context, value, 12, ready ? GREEN : BROWN);
        result.setGravity(Gravity.CENTER);
        result.setPadding(dp(context, 10), dp(context, 5), dp(context, 10), dp(context, 5));
        result.setBackground(surface(context, ready ? SOFT_GREEN : SOFT_BROWN, 0, 14));
        return result;
    }
}
