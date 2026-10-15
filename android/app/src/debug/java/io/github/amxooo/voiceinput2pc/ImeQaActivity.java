package io.github.amxooo.voiceinput2pc;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;

/** Debug-only host used to inspect the real IME surface; never packaged in release. */
public final class ImeQaActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18),dp(60),dp(18),dp(18));root.setBackgroundColor(Color.WHITE);
        android.widget.TextView title=new android.widget.TextView(this);title.setText("输入法真机验收");
        title.setTextSize(24);title.setTextColor(Color.rgb(28,39,50));root.addView(title);
        EditText editor=new EditText(this);editor.setId(4901);editor.setTextSize(19);editor.setGravity(Gravity.TOP);
        editor.setHint("点击这里测试语音、删除、模式切换和功能面板");editor.setMinLines(5);
        root.addView(editor,new LinearLayout.LayoutParams(-1,-2));setContentView(root);
        editor.requestFocus();editor.postDelayed(()->{
            InputMethodManager manager=(InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
            manager.showSoftInput(editor,InputMethodManager.SHOW_IMPLICIT);
        },300);
    }
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
}
