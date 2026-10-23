package io.github.amxooo.voiceinput2pc;
import android.app.Activity;
import android.app.UiAutomation;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.os.SystemClock;
import android.provider.Settings;
import android.test.InstrumentationTestCase;
import android.text.Layout;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.view.Gravity;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;
import org.json.JSONArray;

/** OS-bound own-Activity focus/selection/geometry probe. Does not prove pixels of a blinking caret or WeChat behavior. */
public final class CursorFocusBoundDeviceTest extends InstrumentationTestCase {
    public void testMultilineHostCursorSurvivesDockMenuWithoutRecording()throws Exception {
        Context c=getInstrumentation().getTargetContext();String pkg=c.getPackageName();
        String current=Settings.Secure.getString(c.getContentResolver(),Settings.Secure.DEFAULT_INPUT_METHOD);
        ComponentName selected=current==null?null:ComponentName.unflattenFromString(current);
        assertNotNull("PENDING: no IME selected",selected);
        assertEquals("PENDING: select own IME; test does not change settings",pkg,selected.getPackageName());
        assertEquals(UniversalInputMethodService.class.getName(),selected.getClassName());
        UiAutomation automation=getInstrumentation().getUiAutomation();
        AccessibilityServiceInfo flags=automation.getServiceInfo();int originalFlags=flags.flags;
        flags.flags|=AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;automation.setServiceInfo(flags);
        Activity activity=null;final EditText[] host={null};JSONArray samples=new JSONArray();
        JSONObject evidence=new JSONObject();evidence.put("scope","own Activity; focus + selection + caret geometry, not painted blink pixels");
        evidence.put("microphone_used",false);evidence.put("network_send",false);evidence.put("samples",samples);
        try {
            activity=getInstrumentation().startActivitySync(new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            final Activity own=activity;
            getInstrumentation().runOnMainSync(()->{
                FrameLayout root=new FrameLayout(own);
                TextView title=new TextView(own);title.setText("自有底部多行输入框光标验收，无录音无发送");
                root.addView(title,new FrameLayout.LayoutParams(-1,-2,Gravity.TOP));
                EditText e=new EditText(own);host[0]=e;e.setId(3902);
                e.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
                e.setMinLines(4);e.setMaxLines(6);e.setText("第一行：自有测试内容\n第二行：检查光标状态\n第三行：不读取其他应用\n第四行：光标应在这里");
                FrameLayout.LayoutParams editorParams=new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM);
                editorParams.bottomMargin=Math.round(32*own.getResources().getDisplayMetrics().density);
                root.addView(e,editorParams);own.setContentView(root);
                e.requestFocus();e.setSelection(e.length());
                ((InputMethodManager)own.getSystemService(Context.INPUT_METHOD_SERVICE)).showSoftInput(e,InputMethodManager.SHOW_IMPLICIT);
            });
            AccessibilityNodeInfo mic=waitDescription(automation,pkg,"开始录音");assertNotNull("Own voice dock absent",mic);
            SystemClock.sleep(300);probe(activity,host[0],samples,"initial");
            assertTrue(mic.performAction(AccessibilityNodeInfo.ACTION_EXPAND));SystemClock.sleep(150);probe(activity,host[0],samples,"expanded");
            mic=waitDescription(automation,pkg,"开始录音");assertNotNull(mic);assertTrue(mic.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK));
            assertNotNull(waitDescription(automation,pkg,"历史"));probe(activity,host[0],samples,"more_menu");
            AccessibilityNodeInfo local=waitDescription(automation,pkg,"本机");assertNotNull(local);assertTrue(local.performAction(AccessibilityNodeInfo.ACTION_CLICK));
            assertNotNull(waitDescription(automation,pkg,"开始录音"));
            // Sampling spans multiple normal blink phases; assertions concern focus/geometry only.
            for(int i=0;i<8;i++){SystemClock.sleep(150);probe(activity,host[0],samples,"after_menu_"+i);}
            evidence.put("result","PASS");
        }catch(Throwable t){evidence.put("result","FAIL");evidence.put("error",t.getClass().getSimpleName()+": "+t.getMessage());
            if(t instanceof Exception)throw (Exception)t;throw (Error)t;
        }finally{
            File dir=new File(c.getExternalFilesDir(null),"evidence/cursor-focus-bound");if(!dir.isDirectory())dir.mkdirs();
            try(FileOutputStream out=new FileOutputStream(new File(dir,"focus-evidence.json"))){out.write(evidence.toString(2).getBytes(StandardCharsets.UTF_8));}
            if(activity!=null){final Activity own=activity;getInstrumentation().runOnMainSync(()->{
                if(host[0]!=null)((InputMethodManager)own.getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(host[0].getWindowToken(),0);own.finish();});}
            AccessibilityServiceInfo reset=automation.getServiceInfo();reset.flags=originalFlags;automation.setServiceInfo(reset);
        }
    }
    private void probe(Activity activity,EditText host,JSONArray evidence,String stage)throws Exception {
        final Throwable[] failure={null};getInstrumentation().runOnMainSync(()->{try{
            JSONObject sample=new JSONObject();sample.put("stage",stage);sample.put("uptime",SystemClock.uptimeMillis());
            sample.put("host_focus",host.hasFocus());sample.put("window_focus",host.hasWindowFocus());sample.put("cursor_visible_setting",host.isCursorVisible());
            int start=host.getSelectionStart(),end=host.getSelectionEnd();sample.put("selection_start",start);sample.put("selection_end",end);
            Layout layout=host.getLayout();assertNotNull("Text layout",layout);assertTrue("Selection must be valid",start>=0&&start<=host.length());
            int line=layout.getLineForOffset(start);int[] xy=new int[2];host.getLocationOnScreen(xy);
            int x=xy[0]+host.getTotalPaddingLeft()-host.getScrollX()+Math.round(layout.getPrimaryHorizontal(start));
            int top=xy[1]+host.getTotalPaddingTop()-host.getScrollY()+layout.getLineTop(line);
            int bottom=xy[1]+host.getTotalPaddingTop()-host.getScrollY()+layout.getLineBottom(line);
            Rect cursor=new Rect(x,top,x+Math.max(1,Math.round(host.getResources().getDisplayMetrics().density)),bottom);
            Rect visible=new Rect(),window=new Rect();boolean globallyVisible=host.getLocalVisibleRect(visible);visible.offset(xy[0],xy[1]);
            activity.getWindow().getDecorView().getWindowVisibleDisplayFrame(window);
            sample.put("expected_caret_rect",cursor.flattenToString());sample.put("host_visible_rect",visible.flattenToString());sample.put("window_visible_rect",window.flattenToString());
            sample.put("caret_intersects_host",Rect.intersects(cursor,visible));sample.put("caret_intersects_window",Rect.intersects(cursor,window));evidence.put(sample);
            assertTrue(stage+" host focus",host.hasFocus());assertTrue(stage+" window focus",host.hasWindowFocus());assertTrue(stage+" cursor enabled",host.isCursorVisible());
            assertEquals(stage+" selection stays at fixture end",host.length(),start);assertEquals(start,end);
            assertTrue(stage+" host visible",globallyVisible);assertTrue(stage+" caret inside visible host",Rect.intersects(cursor,visible));assertTrue(stage+" caret inside visible window",Rect.intersects(cursor,window));
        }catch(Throwable t){failure[0]=t;}});
        if(failure[0]!=null){if(failure[0] instanceof Exception)throw (Exception)failure[0];throw (Error)failure[0];}
    }
    private AccessibilityNodeInfo waitDescription(UiAutomation automation,String pkg,String prefix)throws InterruptedException {
        long deadline=SystemClock.uptimeMillis()+6000;
        do {for(AccessibilityWindowInfo window:automation.getWindows()) {
            if(window.getType()!=AccessibilityWindowInfo.TYPE_INPUT_METHOD)continue;
            AccessibilityNodeInfo found=description(window.getRoot(),pkg,prefix);if(found!=null)return found;
        }Thread.sleep(100);}while(SystemClock.uptimeMillis()<deadline);return null;
    }
    private AccessibilityNodeInfo description(AccessibilityNodeInfo node,String pkg,String prefix) {
        if(node==null || !pkg.contentEquals(node.getPackageName()))return null;
        CharSequence d=node.getContentDescription();
        if(node.isVisibleToUser() && d!=null && d.toString().startsWith(prefix))return node;
        for(int i=0;i<node.getChildCount();i++){AccessibilityNodeInfo found=description(node.getChild(i),pkg,prefix);if(found!=null)return found;}
        return null;
    }
}
