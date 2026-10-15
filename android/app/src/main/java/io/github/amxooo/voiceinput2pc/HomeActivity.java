package io.github.amxooo.voiceinput2pc;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Launcher management center. Daily input happens only inside the system IME. */
public final class HomeActivity extends Activity {
    private static final int BG=Color.rgb(246,249,250);
    private static final int SURFACE=Color.WHITE;
    private static final int INK=Color.rgb(27,38,49);
    private static final int MUTED=Color.rgb(108,121,133);
    private static final int BORDER=Color.rgb(220,228,232);
    private static final int TEAL=Color.rgb(0,146,126);
    private static final int DARK_TEAL=Color.rgb(39,84,103);
    private static final int SOFT_TEAL=Color.rgb(229,246,241);
    private static final int SOFT_BLUE=Color.rgb(235,242,246);

    private TextView headline,statusChip,imeStatus,micStatus,deviceStatus;
    private Button enableAction,chooseAction;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        if(android.os.Build.VERSION.SDK_INT>=30)getWindow().setDecorFitsSystemWindows(false);

        ScrollView scroll=new ScrollView(this);
        scroll.setFillViewport(true);scroll.setBackgroundColor(BG);
        LinearLayout root=column();
        root.setPadding(dp(16),dp(12),dp(16),dp(20));root.setBackgroundColor(BG);
        if(android.os.Build.VERSION.SDK_INT>=30)root.setOnApplyWindowInsetsListener((v,insets)->{
            android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
            v.setPadding(bars.left+dp(16),bars.top+dp(12),bars.right+dp(16),bars.bottom+dp(20));return insets;
        });

        root.addView(buildHeader(),params(-1,58,0));
        root.addView(buildStatus(),spaceBelow(-2,14));

        TextView section=label("功能",12,MUTED);
        section.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        section.setPadding(dp(4),dp(2),0,dp(7));root.addView(section);

        LinearLayout functions=column();
        functions.setBackground(surface(SURFACE,BORDER,16));
        functions.addView(actionRow(3103,"语","本地输入与语音","键盘、语音和隐私设置",
                v->startActivity(new Intent(this,ImeSettingsActivity.class)),true));
        functions.addView(actionRow(3104,"联","设备与跨端","连接电脑并传输文字",
                v->startActivity(new Intent(this,MainActivity.class)),true));
        functions.addView(actionRow(3105,"记","输入记录","查看已经确认的输入内容",
                v->openHistory(),false));
        root.addView(functions,spaceBelow(-2,14));

        LinearLayout system=actionRow(3110,"设","系统输入法设置","启用、停用或切换其他输入法",
                v->startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)),false);
        system.setBackground(surface(SURFACE,BORDER,16));root.addView(system,spaceBelow(64,12));

        TextView privacy=label("文字默认只在当前输入框处理；密码框会关闭语音、记录和跨设备输出。",12,MUTED);
        privacy.setPadding(dp(4),dp(2),dp(4),0);root.addView(privacy);

        scroll.addView(root);setContentView(scroll);
    }

    private View buildHeader(){
        LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);
        TextView mark=label("M",15,Color.WHITE);mark.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        mark.setGravity(Gravity.CENTER);mark.setBackground(surface(TEAL,0,10));
        row.addView(mark,new LinearLayout.LayoutParams(dp(38),dp(38)));

        LinearLayout titles=column();titles.setPadding(dp(11),0,0,0);
        TextView title=label("万能输入法",21,INK);title.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        TextView subtitle=label("本地语音 · 中文键盘 · 跨设备",12,MUTED);
        subtitle.setPadding(0,dp(2),0,0);titles.addView(title);titles.addView(subtitle);
        row.addView(titles,new LinearLayout.LayoutParams(0,-2,1));

        TextView version=label("0.8",12,DARK_TEAL);version.setGravity(Gravity.CENTER);
        version.setPadding(dp(10),dp(5),dp(10),dp(5));version.setBackground(surface(SOFT_BLUE,0,14));
        row.addView(version);return row;
    }

    private View buildStatus(){
        LinearLayout card=column();card.setPadding(dp(14),dp(13),dp(14),dp(13));
        card.setBackground(surface(SURFACE,BORDER,16));

        LinearLayout top=new LinearLayout(this);top.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titleBlock=column();
        headline=label("正在检查",17,INK);headline.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        imeStatus=label("",13,MUTED);imeStatus.setPadding(0,dp(3),0,0);
        titleBlock.addView(headline);titleBlock.addView(imeStatus);
        top.addView(titleBlock,new LinearLayout.LayoutParams(0,-2,1));
        statusChip=label("检查中",12,MUTED);statusChip.setGravity(Gravity.CENTER);
        statusChip.setPadding(dp(10),dp(5),dp(10),dp(5));statusChip.setBackground(surface(SOFT_BLUE,0,14));
        top.addView(statusChip);card.addView(top);

        LinearLayout facts=new LinearLayout(this);facts.setGravity(Gravity.CENTER_VERTICAL);
        facts.setPadding(0,dp(11),0,0);
        micStatus=compactFact("语音 · 检查中");facts.addView(micStatus,new LinearLayout.LayoutParams(0,-2,1));
        deviceStatus=compactFact("设备 · 检查中");facts.addView(deviceStatus,new LinearLayout.LayoutParams(0,-2,1));
        card.addView(facts);

        enableAction=primaryAction("去启用输入法");enableAction.setId(3101);
        enableAction.setOnClickListener(v->startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)));
        card.addView(enableAction,actionParams());

        chooseAction=primaryAction("设为当前输入法");chooseAction.setId(3102);
        chooseAction.setOnClickListener(v->((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).showInputMethodPicker());
        card.addView(chooseAction,actionParams());
        return card;
    }

    private LinearLayout actionRow(int id,String icon,String title,String detail,View.OnClickListener click,boolean divider){
        LinearLayout row=new LinearLayout(this);row.setId(id);row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(13),dp(8),dp(12),dp(8));row.setClickable(true);row.setFocusable(true);
        row.setOnClickListener(click);row.setContentDescription(title+"，"+detail);

        TextView glyph=label(icon,13,DARK_TEAL);glyph.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        glyph.setGravity(Gravity.CENTER);glyph.setBackground(surface(SOFT_TEAL,0,10));
        row.addView(glyph,new LinearLayout.LayoutParams(dp(36),dp(36)));

        LinearLayout copy=column();copy.setPadding(dp(11),0,dp(8),0);
        TextView name=label(title,15,INK);name.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        TextView note=label(detail,12,MUTED);note.setPadding(0,dp(2),0,0);
        copy.addView(name);copy.addView(note);row.addView(copy,new LinearLayout.LayoutParams(0,-2,1));
        TextView arrow=label("›",24,MUTED);arrow.setGravity(Gravity.CENTER);row.addView(arrow,new LinearLayout.LayoutParams(dp(24),-1));
        if(divider){
            GradientDrawable dividerBg=surface(SURFACE,0,0);
            row.setBackground(dividerBg);
        }
        row.setMinimumHeight(dp(64));return row;
    }

    private TextView compactFact(String value){
        TextView result=label(value,12,MUTED);result.setSingleLine(true);
        result.setPadding(dp(9),dp(6),dp(9),dp(6));result.setBackground(surface(BG,0,10));return result;
    }

    private Button primaryAction(String value){
        Button button=new Button(this);button.setText(value);button.setAllCaps(false);button.setTextSize(14);
        button.setTextColor(Color.WHITE);button.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        button.setMinHeight(0);button.setMinimumHeight(0);button.setStateListAnimator(null);
        button.setBackgroundTintList(null);button.setBackground(surface(DARK_TEAL,0,11));return button;
    }

    private LinearLayout.LayoutParams actionParams(){
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,dp(42));params.topMargin=dp(11);return params;
    }
    private void openHistory(){
        Intent intent=new Intent(this,MainActivity.class);intent.putExtra(MainActivity.EXTRA_OPEN_HISTORY,true);startActivity(intent);
    }
    @Override protected void onResume(){super.onResume();refresh();}

    private void refresh(){
        if(imeStatus==null)return;
        InputMethodManager manager=(InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
        String service=new ComponentName(this,UniversalInputMethodService.class).flattenToString();
        boolean enabled=false;
        for(InputMethodInfo info:manager.getEnabledInputMethodList())
            if(info.getPackageName().equals(getPackageName())
                    && info.getServiceName().equals(UniversalInputMethodService.class.getName())){enabled=true;break;}
        String current=Settings.Secure.getString(getContentResolver(),Settings.Secure.DEFAULT_INPUT_METHOD);
        boolean selected=service.equals(current);

        if(selected){
            headline.setText("输入法已就绪");imeStatus.setText("当前正在使用万能输入法");
            statusChip.setText("正常");statusChip.setTextColor(TEAL);statusChip.setBackground(surface(SOFT_TEAL,0,14));
        }else if(enabled){
            headline.setText("还差一步");imeStatus.setText("已启用，需要设为当前输入法");
            statusChip.setText("待切换");statusChip.setTextColor(DARK_TEAL);statusChip.setBackground(surface(SOFT_BLUE,0,14));
        }else{
            headline.setText("需要启用");imeStatus.setText("系统尚未允许使用本输入法");
            statusChip.setText("未启用");statusChip.setTextColor(MUTED);statusChip.setBackground(surface(SOFT_BLUE,0,14));
        }
        enableAction.setVisibility(enabled?View.GONE:View.VISIBLE);
        chooseAction.setVisibility(enabled&&!selected?View.VISIBLE:View.GONE);

        boolean audio=checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED;
        micStatus.setText(audio?"语音 · 已授权":"语音 · 未授权");
        PairingConfig active=PairingStore.load(getSharedPreferences("voiceinput2pc",MODE_PRIVATE));
        deviceStatus.setText(active==null?"设备 · 未配对":"设备 · "+PairingStore.label(getSharedPreferences("voiceinput2pc",MODE_PRIVATE),active));
    }

    private LinearLayout column(){LinearLayout value=new LinearLayout(this);value.setOrientation(LinearLayout.VERTICAL);return value;}
    private TextView label(String value,int size,int color){TextView text=new TextView(this);text.setText(value);text.setTextSize(size);text.setTextColor(color);text.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);return text;}
    private GradientDrawable surface(int color,int border,int radius){GradientDrawable bg=new GradientDrawable();bg.setColor(color);bg.setCornerRadius(dp(radius));if(border!=0)bg.setStroke(dp(1),border);return bg;}
    private LinearLayout.LayoutParams params(int width,int height,int weight){return new LinearLayout.LayoutParams(width,height<0?height:dp(height),weight);}
    private LinearLayout.LayoutParams spaceBelow(int height,int bottom){LinearLayout.LayoutParams value=new LinearLayout.LayoutParams(-1,height<0?height:dp(height));value.bottomMargin=dp(bottom);return value;}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
}
