package io.github.amxooo.voiceinput2pc;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class ImeSettingsActivity extends Activity {
    private static final int AUDIO_PERMISSION = 808;
    private TextView audioStatus;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle("本地输入法设置");
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(20));
        root.setBackgroundColor(Color.rgb(248, 247, 243));

        TextView title = text("万能输入法 · 本地模式", 23, Color.rgb(32, 37, 35));
        root.addView(title);
        TextView note = text("文字键盘始终可用。麦克风只在键盘可见且你主动点击时开启；Probe A 只验证录音生命周期，不会保存音频。", 15, Color.DKGRAY);
        note.setPadding(0, dp(12), 0, dp(16));
        root.addView(note);

        audioStatus = text("", 14, Color.rgb(22,112,91));
        root.addView(audioStatus);
        Button permission = button("授权本地语音麦克风");
        permission.setOnClickListener(v -> requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, AUDIO_PERMISSION));
        root.addView(permission);

        Button enable = button("打开系统输入法设置");
        enable.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)));
        root.addView(enable);

        Button choose = button("选择当前输入法");
        choose.setOnClickListener(v -> ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).showInputMethodPicker());
        root.addView(choose);
        setContentView(root);
        refresh();
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == AUDIO_PERMISSION) refresh();
    }

    private void refresh() {
        boolean granted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        audioStatus.setText(granted ? "麦克风：已授权" : "麦克风：未授权（文字键盘不受影响）");
    }

    private Button button(String label) {
        Button b = new Button(this); b.setText(label); b.setAllCaps(false);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(48));
        p.topMargin = dp(10); b.setLayoutParams(p); return b;
    }
    private TextView text(String value, int size, int color) {
        TextView t = new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(color);
        t.setGravity(Gravity.START); return t;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
