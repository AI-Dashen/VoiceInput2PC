package io.github.amxooo.voiceinput2pc;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.inputmethodservice.InputMethodService;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/** Probe A system IME shell. Chinese decoding and ASR are added only after their own probes. */
public final class UniversalInputMethodService extends InputMethodService
        implements SafeAudioRecorder.Listener {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final InputSession session = new InputSession();
    private final StringBuilder composing = new StringBuilder();
    private SafeAudioRecorder recorder;
    private TextView status;
    private TextView composition;
    private Button voice;
    private boolean sensitiveField;

    @Override public void onCreate() {
        super.onCreate();
        recorder = new SafeAudioRecorder(this, this);
    }

    @Override public View onCreateInputView() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(8), dp(8), dp(8), dp(8));
        root.setBackgroundColor(Color.rgb(246, 246, 242));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView local = pill("🔒 本地模式", Color.rgb(226, 247, 234), Color.rgb(24, 108, 72));
        top.addView(local, new LinearLayout.LayoutParams(0, dp(38), 1));
        TextView target = pill("输入到：本机", Color.rgb(237, 235, 225), Color.rgb(45, 43, 36));
        LinearLayout.LayoutParams targetParams = new LinearLayout.LayoutParams(0, dp(38), 1);
        targetParams.leftMargin = dp(8); top.addView(target, targetParams);
        Button settings = key("⚙", 16);
        settings.setContentDescription("本地输入法设置");
        settings.setOnClickListener(v -> openSettings());
        LinearLayout.LayoutParams settingsParams = new LinearLayout.LayoutParams(dp(46), dp(38));
        settingsParams.leftMargin = dp(8); top.addView(settings, settingsParams);
        root.addView(top);

        status = new TextView(this);
        status.setText("文字键盘已就绪 · 中文候选将在 Probe C 接入");
        status.setTextSize(12); status.setTextColor(Color.rgb(76, 82, 79));
        status.setPadding(dp(6), dp(8), dp(6), dp(5));
        root.addView(status);

        composition = new TextView(this);
        composition.setTextSize(20); composition.setTextColor(Color.rgb(25, 31, 29));
        composition.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        composition.setPadding(dp(8), dp(5), dp(8), dp(8));
        composition.setMinHeight(dp(42));
        root.addView(composition);

        addRow(root, "qwertyuiop");
        addRow(root, "asdfghjkl");
        addRow(root, "zxcvbnm");

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER);
        Button symbols = key("?123", 13);
        symbols.setOnClickListener(v -> commitLiteral("?"));
        actions.addView(symbols, weighted());
        Button space = key("空格", 14);
        space.setOnClickListener(v -> commitLiteral(" "));
        LinearLayout.LayoutParams spaceParams = weighted(); spaceParams.weight = 2;
        actions.addView(space, spaceParams);
        voice = key("🎙 语音", 14);
        voice.setContentDescription("本地语音");
        voice.setOnClickListener(v -> toggleVoice());
        actions.addView(voice, weighted());
        Button delete = key("⌫", 18);
        delete.setContentDescription("删除"); delete.setOnClickListener(v -> backspace());
        actions.addView(delete, weighted());
        Button enter = key("↵", 18);
        enter.setContentDescription("换行或执行编辑器动作"); enter.setOnClickListener(v -> enter());
        actions.addView(enter, weighted());
        root.addView(actions, new LinearLayout.LayoutParams(-1, dp(52)));
        render();
        return root;
    }

    @Override public void onStartInput(EditorInfo info, boolean restarting) {
        super.onStartInput(info, restarting);
        stopVoice(false);
        composing.setLength(0);
        sensitiveField = isSensitive(info == null ? 0 : info.inputType);
        session.begin("android-current-field", info == null ? "" : info.packageName,
                info == null ? 0 : info.fieldId, info == null ? 0 : info.inputType);
        render();
    }

    @Override public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        render();
    }

    @Override public void onFinishInputView(boolean finishingInput) {
        stopVoice(true);
        InputConnection connection = getCurrentInputConnection();
        if (connection != null) connection.finishComposingText();
        composing.setLength(0);
        session.invalidate();
        render();
        super.onFinishInputView(finishingInput);
    }

    @Override public void onDestroy() {
        stopVoice(true);
        super.onDestroy();
    }

    private void addRow(LinearLayout parent, String keys) {
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER);
        for (int i = 0; i < keys.length(); i++) {
            String value = String.valueOf(keys.charAt(i));
            Button button = key(value, 16);
            button.setOnClickListener(v -> type(value));
            row.addView(button, weighted());
        }
        parent.addView(row, new LinearLayout.LayoutParams(-1, dp(50)));
    }

    private void type(String value) {
        if (recorder.isRunning()) return;
        composing.append(value);
        long generation = session.generation();
        session.compose(generation, composing.toString());
        InputConnection connection = getCurrentInputConnection();
        if (connection != null) connection.setComposingText(composing, 1);
        render();
    }

    private void commitLiteral(String value) {
        stopVoice(false);
        InputConnection connection = getCurrentInputConnection();
        if (connection == null) return;
        if (composing.length() > 0) {
            connection.commitText(composing.toString(), 1);
            composing.setLength(0);
        }
        connection.commitText(value, 1);
        session.begin("android-current-field", currentPackage(), currentField(), currentInputType());
        render();
    }

    private void backspace() {
        if (recorder.isRunning()) return;
        InputConnection connection = getCurrentInputConnection();
        if (composing.length() > 0) {
            composing.deleteCharAt(composing.length() - 1);
            if (connection != null) {
                if (composing.length() == 0) connection.finishComposingText();
                else connection.setComposingText(composing, 1);
            }
            session.compose(session.generation(), composing.toString());
        } else if (connection != null) {
            connection.deleteSurroundingTextInCodePoints(1, 0);
        }
        render();
    }

    private void enter() {
        stopVoice(false);
        InputConnection connection = getCurrentInputConnection();
        EditorInfo info = getCurrentInputEditorInfo();
        if (connection == null) return;
        if (composing.length() > 0) {
            connection.commitText(composing.toString(), 1);
            composing.setLength(0);
        }
        int action = info == null ? EditorInfo.IME_ACTION_NONE : info.imeOptions & EditorInfo.IME_MASK_ACTION;
        // Only this explicit user tap may invoke an editor action.
        if (action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            connection.performEditorAction(action);
        } else {
            connection.commitText("\n", 1);
        }
        session.begin("android-current-field", currentPackage(), currentField(), currentInputType());
        render();
    }

    private void toggleVoice() {
        if (sensitiveField) {
            Toast.makeText(this, "敏感输入框已禁用语音", Toast.LENGTH_SHORT).show();
            return;
        }
        if (recorder.isRunning()) {
            stopVoice(false);
            return;
        }
        if (composing.length() > 0) {
            Toast.makeText(this, "请先确认或删除当前拼音", Toast.LENGTH_SHORT).show();
            return;
        }
        InputSession.Identity current = session.identity();
        long generation = session.begin(current.targetId, current.packageName,
                current.fieldId, current.inputType).generation;
        if (!session.startListening(generation)) return;
        if (!recorder.start(generation)) session.cancel(generation);
        render();
    }

    private void stopVoice(boolean invalidate) {
        if (recorder != null) recorder.stop();
        long generation = session.generation();
        session.cancel(generation);
        if (invalidate) session.invalidate();
        render();
    }

    @Override public void onStarted(long generation) {
        main.post(() -> { if (session.isCurrent(generation)) render(); });
    }
    @Override public void onProgress(long generation, long samples) {
        main.post(() -> {
            if (!session.isCurrent(generation) || status == null) return;
            status.setText(String.format(java.util.Locale.ROOT,
                    "本地录音中 %.1f 秒 · 音频不保存", samples / (double) SafeAudioRecorder.sampleRate()));
        });
    }
    @Override public void onStopped(long generation, long samples) {
        main.post(() -> {
            if (!session.isCurrent(generation)) return;
            session.cancel(generation);
            if (status != null) status.setText("录音已释放 · Probe A 不执行识别");
            renderVoiceButton();
        });
    }
    @Override public void onError(long generation, String message) {
        main.post(() -> {
            if (!session.isCurrent(generation)) return;
            session.cancel(generation);
            if (status != null) status.setText(message);
            renderVoiceButton();
        });
    }

    private void render() {
        if (composition != null) composition.setText(composing);
        if (status != null && sensitiveField) status.setText("敏感输入框 · 语音、学习、历史和电脑链路已关闭");
        renderVoiceButton();
    }
    private void renderVoiceButton() {
        if (voice == null || recorder == null) return;
        voice.setText(recorder.isRunning() ? "■ 完成" : "🎙 语音");
        voice.setEnabled(!sensitiveField);
    }

    private void openSettings() {
        Intent intent = new Intent(this, ImeSettingsActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    private String currentPackage() {
        EditorInfo info = getCurrentInputEditorInfo(); return info == null ? "" : info.packageName;
    }
    private int currentField() {
        EditorInfo info = getCurrentInputEditorInfo(); return info == null ? 0 : info.fieldId;
    }
    private int currentInputType() {
        EditorInfo info = getCurrentInputEditorInfo(); return info == null ? 0 : info.inputType;
    }

    private static boolean isSensitive(int inputType) {
        int klass = inputType & InputType.TYPE_MASK_CLASS;
        int variation = inputType & InputType.TYPE_MASK_VARIATION;
        if (klass == InputType.TYPE_CLASS_NUMBER) {
            return variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD;
        }
        if (klass != InputType.TYPE_CLASS_TEXT) return false;
        return variation == InputType.TYPE_TEXT_VARIATION_PASSWORD
                || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD;
    }

    private TextView pill(String value, int background, int foreground) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(13);
        view.setTextColor(foreground); view.setGravity(Gravity.CENTER);
        view.setBackground(surface(background, 18)); return view;
    }
    private Button key(String value, int size) {
        Button button = new Button(this); button.setText(value); button.setTextSize(size);
        button.setAllCaps(false); button.setMinWidth(0); button.setMinimumWidth(0);
        button.setMinHeight(0); button.setMinimumHeight(0); button.setPadding(0,0,0,0);
        button.setTextColor(Color.rgb(30,34,33)); button.setBackground(surface(Color.WHITE, 9));
        return button;
    }
    private LinearLayout.LayoutParams weighted() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -1, 1);
        params.setMargins(dp(2), dp(2), dp(2), dp(2)); return params;
    }
    private GradientDrawable surface(int color, int radius) {
        GradientDrawable shape = new GradientDrawable(); shape.setColor(color);
        shape.setCornerRadius(dp(radius)); shape.setStroke(dp(1), Color.rgb(222, 222, 216));
        return shape;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
