package io.github.amxooo.voiceinput2pc;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** App-owned settings surface; system settings are only opened after an explicit tap. */
public final class ImeSettingsActivity extends Activity {
    private static final int AUDIO_PERMISSION = 808;
    private TextView audioStatus, imeStatus;
    private Button permission, enable, choose;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle("输入法设置");
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(VoiceUiTheme.PAPER);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(14), dp(18), dp(22));
        root.setBackgroundColor(VoiceUiTheme.PAPER);
        if (Build.VERSION.SDK_INT >= 30) root.setOnApplyWindowInsetsListener((view,insets)-> {
            android.graphics.Insets bars=insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            view.setPadding(bars.left+dp(18),bars.top+dp(14),bars.right+dp(18),bars.bottom+dp(22));
            return insets;
        });

        root.addView(VoiceUiTheme.title(this,"输入法设置",28));
        TextView intro=VoiceUiTheme.text(this,"管理本地语音权限和系统输入法状态。所有日常输入仍在键盘中完成。",14,VoiceUiTheme.MUTED);
        intro.setPadding(0,dp(4),0,dp(16));root.addView(intro);

        LinearLayout voiceCard=VoiceUiTheme.card(this);
        LinearLayout voiceHeading=row();
        voiceHeading.addView(VoiceUiTheme.title(this,"本地语音",18),new LinearLayout.LayoutParams(0,-2,1));
        audioStatus=VoiceUiTheme.statusChip(this,"检查中",false);
        voiceHeading.addView(audioStatus,new LinearLayout.LayoutParams(-2,-2));
        voiceCard.addView(voiceHeading);
        TextView voiceNote=VoiceUiTheme.text(this,
                "只有主动点击键盘麦克风时才会录音。本地识别不会保存录音。",
                13,VoiceUiTheme.MUTED);
        voiceNote.setPadding(0,dp(8),0,0);voiceCard.addView(voiceNote);
        permission=VoiceUiTheme.button(this,"授权麦克风",true);
        permission.setId(3201);
        permission.setOnClickListener(v->requestPermissions(
                new String[]{Manifest.permission.RECORD_AUDIO},AUDIO_PERMISSION));
        LinearLayout.LayoutParams permissionParams=buttonParams();voiceCard.addView(permission,permissionParams);
        root.addView(voiceCard,sectionParams());

        LinearLayout systemCard=VoiceUiTheme.card(this);
        LinearLayout systemHeading=row();
        systemHeading.addView(VoiceUiTheme.title(this,"系统输入法",18),new LinearLayout.LayoutParams(0,-2,1));
        imeStatus=VoiceUiTheme.statusChip(this,"检查中",false);
        systemHeading.addView(imeStatus,new LinearLayout.LayoutParams(-2,-2));
        systemCard.addView(systemHeading);
        TextView systemNote=VoiceUiTheme.text(this,
                "启用后再选择为当前输入法。每次从系统页面返回，这里都会自动刷新。",
                13,VoiceUiTheme.MUTED);
        systemNote.setPadding(0,dp(8),0,0);systemCard.addView(systemNote);
        enable=VoiceUiTheme.button(this,"打开系统输入法设置",false);enable.setId(3202);
        enable.setOnClickListener(v->startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)));
        systemCard.addView(enable,buttonParams());
        choose=VoiceUiTheme.button(this,"选择当前输入法",false);choose.setId(3203);
        choose.setOnClickListener(v->((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).showInputMethodPicker());
        systemCard.addView(choose,buttonParams());
        root.addView(systemCard,sectionParams());

        LinearLayout privacyCard=VoiceUiTheme.card(this);
        privacyCard.addView(VoiceUiTheme.title(this,"隐私说明",18));
        TextView privacy=VoiceUiTheme.text(this,
                "密码输入框会关闭语音、历史和跨设备输出。文字只有在你明确选择电脑并发送后才会离开手机。",
                13,VoiceUiTheme.MUTED);
        privacy.setPadding(0,dp(8),0,0);privacyCard.addView(privacy);
        root.addView(privacyCard,sectionParams());

        scroll.addView(root);setContentView(scroll);
        refresh();
    }

    @Override protected void onResume() { super.onResume(); refresh(); }

    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results) {
        super.onRequestPermissionsResult(request,permissions,results);
        if (request == AUDIO_PERMISSION) refresh();
    }

    private void refresh() {
        if (audioStatus == null) return;
        boolean audio=checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED;
        audioStatus.setText(audio ? "已授权" : "未授权");
        audioStatus.setTextColor(audio ? VoiceUiTheme.GREEN : VoiceUiTheme.BROWN);
        audioStatus.setBackground(VoiceUiTheme.surface(this,
                audio ? VoiceUiTheme.SOFT_GREEN : VoiceUiTheme.SOFT_BROWN,0,14));
        permission.setText(audio ? "麦克风已授权" : "授权麦克风");
        permission.setEnabled(!audio);

        InputMethodManager manager=(InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
        String service=new ComponentName(this,UniversalInputMethodService.class).flattenToString();
        boolean enabled=false;
        for (InputMethodInfo info:manager.getEnabledInputMethodList()) {
            if (info.getPackageName().equals(getPackageName())
                    && info.getServiceName().equals(UniversalInputMethodService.class.getName())) {
                enabled=true;break;
            }
        }
        boolean selected=service.equals(Settings.Secure.getString(
                getContentResolver(),Settings.Secure.DEFAULT_INPUT_METHOD));
        imeStatus.setText(selected ? "正在使用" : enabled ? "已启用" : "未启用");
        imeStatus.setTextColor(selected || enabled ? VoiceUiTheme.GREEN : VoiceUiTheme.BROWN);
        imeStatus.setBackground(VoiceUiTheme.surface(this,
                selected || enabled ? VoiceUiTheme.SOFT_GREEN : VoiceUiTheme.SOFT_BROWN,0,14));

        styleButton(permission,!audio);
        styleButton(enable,audio && !enabled);
        styleButton(choose,audio && enabled && !selected);
        enable.setText(enabled ? "输入法已启用" : "打开系统输入法设置");
        choose.setText(selected ? "当前正在使用" : "选择当前输入法");
        enable.setEnabled(!enabled);
        choose.setEnabled(enabled && !selected);
    }

    private void styleButton(Button value,boolean primary) {
        value.setTextColor(primary ? android.graphics.Color.WHITE : VoiceUiTheme.BROWN);
        value.setBackground(VoiceUiTheme.surface(this,
                primary ? VoiceUiTheme.BROWN : VoiceUiTheme.SURFACE,
                primary ? 0 : VoiceUiTheme.BORDER,22));
    }

    private LinearLayout row() {
        LinearLayout result=new LinearLayout(this);
        result.setGravity(android.view.Gravity.CENTER_VERTICAL);
        return result;
    }
    private LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams value=new LinearLayout.LayoutParams(-1,dp(48));
        value.topMargin=dp(10);return value;
    }
    private LinearLayout.LayoutParams sectionParams() {
        LinearLayout.LayoutParams value=new LinearLayout.LayoutParams(-1,-2);
        value.bottomMargin=dp(14);return value;
    }
    private int dp(int value) { return VoiceUiTheme.dp(this,value); }
}
