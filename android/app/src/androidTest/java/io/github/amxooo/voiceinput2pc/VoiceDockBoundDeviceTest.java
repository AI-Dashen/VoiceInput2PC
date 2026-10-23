package io.github.amxooo.voiceinput2pc;

import android.app.Activity;
import android.app.UiAutomation;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.SystemClock;
import android.provider.Settings;
import android.test.InstrumentationTestCase;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.json.JSONObject;
import org.json.JSONArray;

/** Actual OS-bound IME window. No settings writes, mic, typing, pairing, or network send. */
public final class VoiceDockBoundDeviceTest extends InstrumentationTestCase {
    public void testCurrentImeDockAndKeyboardWindow()throws Exception {
        Context context=getInstrumentation().getTargetContext();String pkg=context.getPackageName();
        String current=Settings.Secure.getString(context.getContentResolver(),Settings.Secure.DEFAULT_INPUT_METHOD);
        ComponentName selected=current==null ? null : ComponentName.unflattenFromString(current);
        assertNotNull("PENDING: no current IME",selected);
        assertEquals("PENDING: select this app's IME manually; test never changes settings",pkg,selected.getPackageName());
        assertEquals("PENDING: wrong current IME service",UniversalInputMethodService.class.getName(),selected.getClassName());
        UiAutomation automation=getInstrumentation().getUiAutomation();
        AccessibilityServiceInfo original=automation.getServiceInfo();int originalFlags=original.flags;
        AccessibilityServiceInfo flags=automation.getServiceInfo();flags.flags|=AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;automation.setServiceInfo(flags);
        Activity activity=getInstrumentation().startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        final EditText[] editor={null};
        File dir=new File(context.getExternalFilesDir(null),"evidence/voice-dock-bound");assertTrue(dir.isDirectory()||dir.mkdirs());
        JSONObject evidence=new JSONObject();evidence.put("package",pkg);evidence.put("default_ime_matches",true);evidence.put("microphone_used",false);
        try {
            getInstrumentation().runOnMainSync(()->{
                // Replace only the running Activity's view with non-sensitive fixture UI.
                LinearLayout layout=new LinearLayout(activity);layout.setOrientation(LinearLayout.VERTICAL);
                TextView title=new TextView(activity);title.setText("Voice Dock 系统输入窗口测试");layout.addView(title);
                editor[0]=new EditText(activity);editor[0].setId(3901);editor[0].setHint("测试输入框：不包含私人数据");editor[0].setInputType(1);
                layout.addView(editor[0],new LinearLayout.LayoutParams(-1,160));activity.setContentView(layout);editor[0].requestFocus();
                ((InputMethodManager)activity.getSystemService(Context.INPUT_METHOD_SERVICE)).showSoftInput(editor[0],InputMethodManager.SHOW_IMPLICIT);
            });
            AccessibilityNodeInfo voice=waitDescription(automation,pkg,"开始录音");
            assertNotNull("Actual compact microphone absent",voice);
            SystemClock.sleep(500);screenshot(automation,activity,pkg,new File(dir,"default.png"));
            // The node found before the screenshot may describe the IME entrance animation.
            // Reacquire + refresh on every sample; compare settled snapshots from both states.
            StableMic before=waitStableMic(automation,pkg,evidence,"before_expand");
            voice=before.node;Rect micBefore=new Rect(before.micBounds);
            evidence.put("mic_before_expand",micBefore.flattenToString());
            evidence.put("ime_window_before_expand",before.windowBounds.flattenToString());
            evidence.put("ime_window_id_before_expand",before.windowId);
            evidence.put("expand_action_uptime_ms",SystemClock.uptimeMillis());
            assertTrue("Expand accessibility action rejected",voice.performAction(AccessibilityNodeInfo.ACTION_EXPAND));
            StableMic afterExpand=waitStableMic(automation,pkg,evidence,"after_expand");
            voice=afterExpand.node;Rect micExpanded=new Rect(afterExpand.micBounds);
            evidence.put("mic_after_expand",micExpanded.flattenToString());
            evidence.put("ime_window_after_expand",afterExpand.windowBounds.flattenToString());
            evidence.put("ime_window_id_after_expand",afterExpand.windowId);
            evidence.put("ime_window_unchanged_on_expand",before.windowId==afterExpand.windowId&&before.windowBounds.equals(afterExpand.windowBounds));
            assertEquals("Microphone anchor moved on expand",micBefore,micExpanded);
            AccessibilityNodeInfo delete=waitDescription(automation,pkg,"删除，按住连续删除");assertNotNull("Upper delete missing",delete);
            assertTrue("Delete node refresh failed",delete.refresh());
            Rect deleteBounds=new Rect();delete.getBoundsInScreen(deleteBounds);
            assertTrue("Delete must be above microphone",deleteBounds.bottom<=micExpanded.top);
            assertEquals(deleteBounds.centerX(),micExpanded.centerX());
            AccessibilityNodeInfo confirm=waitBelowMicAction(automation,pkg,micExpanded);assertNotNull("Lower editor action missing",confirm);
            assertTrue("Editor action node refresh failed",confirm.refresh());
            Rect actionBounds=new Rect();confirm.getBoundsInScreen(actionBounds);
            assertTrue(actionBounds.top>=micExpanded.bottom);assertEquals(micExpanded.centerX(),actionBounds.centerX());
            int slot=Math.round(48*context.getResources().getDisplayMetrics().density);
            for(Rect r:new Rect[]{deleteBounds,micExpanded,actionBounds}){assertEquals(slot,r.width());assertEquals(slot,r.height());}
            evidence.put("quick_delete_bounds",deleteBounds.flattenToString());evidence.put("quick_mic_bounds",micExpanded.flattenToString());
            evidence.put("quick_action_bounds",actionBounds.flattenToString());evidence.put("quick_action_description",String.valueOf(confirm.getContentDescription()));
            evidence.put("quick_anchor_unchanged",true);
            SystemClock.sleep(300);screenshot(automation,activity,pkg,new File(dir,"quick-actions.png"));
            assertFalse("Primary editing controls must not be hidden",voice.performAction(AccessibilityNodeInfo.ACTION_COLLAPSE));
            evidence.put("primary_collapse_rejected",true);
            assertNotNull("Required delete remains visible",waitDescription(automation,pkg,"删除，按住连续删除"));
            StableMic afterCollapse=waitStableMic(automation,pkg,evidence,"after_rejected_collapse");voice=afterCollapse.node;
            Rect micCollapsed=new Rect(afterCollapse.micBounds);
            evidence.put("mic_after_collapse",micCollapsed.flattenToString());
            evidence.put("ime_window_after_collapse",afterCollapse.windowBounds.flattenToString());
            assertEquals("Microphone anchor moved after rejected collapse",micBefore,micCollapsed);
            assertTrue("Actual long press failed",voice.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK));
            AccessibilityNodeInfo keyboard=waitDescription(automation,pkg,"键盘");
            assertNotNull("Actual IME keyboard control absent",keyboard);
            SystemClock.sleep(500);screenshot(automation,activity,pkg,new File(dir,"expanded.png"));
            // Long press now opens the actual More hub directly.
            assertNotNull("Actual More menu not shown",waitDescription(automation,pkg,"历史"));
            assertNotNull("Actual settings menu missing",waitDescription(automation,pkg,"设置"));
            assertNotNull("Actual IME picker entry missing",waitDescription(automation,pkg,"切换输入法"));
            SystemClock.sleep(800);screenshot(automation,activity,pkg,new File(dir,"more.png"));
            assertNotNull("Actual statistics menu missing",waitDescription(automation,pkg,"统计"));
            assertNotNull("Actual device menu missing",waitDescription(automation,pkg,"目标设备"));
            AccessibilityNodeInfo close=waitDescription(automation,pkg,"本机");assertNotNull(close);
            assertTrue(close.performAction(AccessibilityNodeInfo.ACTION_CLICK));
            voice=waitDescription(automation,pkg,"开始录音");assertNotNull(voice);
            assertTrue(voice.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK));
            keyboard=waitDescription(automation,pkg,"键盘");assertNotNull(keyboard);
            Rect bounds=new Rect();keyboard.getBoundsInScreen(bounds);evidence.put("dock_keyboard_bounds",bounds.flattenToString());
            Rect visible=new Rect();getInstrumentation().runOnMainSync(()->activity.getWindow().getDecorView().getWindowVisibleDisplayFrame(visible));
            evidence.put("dock_host_visible_frame",visible.flattenToString());
            SystemClock.sleep(800);
            screenshot(automation,activity,pkg,new File(dir,"dock.png"));
            assertTrue("Actual keyboard click failed",keyboard.performAction(AccessibilityNodeInfo.ACTION_CLICK));
            assertNotNull("Actual letter row absent",waitNode(automation,pkg,"q"));
            Rect after=new Rect();getInstrumentation().runOnMainSync(()->activity.getWindow().getDecorView().getWindowVisibleDisplayFrame(after));
            evidence.put("keyboard_host_visible_frame",after.flattenToString());
            assertTrue("Keyboard did not reduce host visible frame",after.bottom<visible.bottom);
            SystemClock.sleep(800);
            screenshot(automation,activity,pkg,new File(dir,"keyboard.png"));
            evidence.put("result","PASS");
        } catch(Throwable failure) {
            evidence.put("result","FAIL");evidence.put("error",failure.getClass().getSimpleName()+": "+failure.getMessage());
            if(failure instanceof Exception)throw (Exception)failure;
            throw (Error)failure;
        } finally {
            try(FileOutputStream out=new FileOutputStream(new File(dir,"window-evidence.json"))){out.write(evidence.toString(2).getBytes(StandardCharsets.UTF_8));}
            getInstrumentation().runOnMainSync(()->{if(editor[0]!=null)((InputMethodManager)activity.getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(editor[0].getWindowToken(),0);activity.finish();});
            AccessibilityServiceInfo reset=automation.getServiceInfo();reset.flags=originalFlags;automation.setServiceInfo(reset);
        }
    }
    private static final class StableMic {
        final AccessibilityNodeInfo node;
        final Rect micBounds,windowBounds;
        final int windowId;
        StableMic(AccessibilityNodeInfo node,Rect micBounds,Rect windowBounds,int windowId) {
            this.node=node;this.micBounds=micBounds;this.windowBounds=windowBounds;this.windowId=windowId;
        }
    }
    private StableMic freshMic(UiAutomation automation,String pkg) {
        for(AccessibilityWindowInfo window:automation.getWindows()) {
            if(window.getType()!=AccessibilityWindowInfo.TYPE_INPUT_METHOD)continue;
            AccessibilityNodeInfo root=window.getRoot();
            if(root==null||!root.refresh()||!pkg.contentEquals(root.getPackageName()))continue;
            AccessibilityNodeInfo node=description(root,pkg,"开始录音");
            if(node==null||!node.refresh()||!node.isVisibleToUser())continue;
            Rect micBounds=new Rect(),windowBounds=new Rect();
            node.getBoundsInScreen(micBounds);window.getBoundsInScreen(windowBounds);
            if(micBounds.isEmpty()||windowBounds.isEmpty())continue;
            return new StableMic(node,micBounds,windowBounds,window.getId());
        }
        return null;
    }
    private StableMic waitStableMic(UiAutomation automation,String pkg,JSONObject evidence,String phase)throws Exception {
        JSONArray samples=new JSONArray();evidence.put(phase+"_geometry_samples",samples);
        long deadline=SystemClock.uptimeMillis()+6000;StableMic previous=null;int stableCount=0;
        do {
            StableMic current=freshMic(automation,pkg);
            JSONObject sample=new JSONObject();sample.put("uptime_ms",SystemClock.uptimeMillis());
            sample.put("fresh_node_available",current!=null);
            if(current!=null) {
                sample.put("mic_bounds",current.micBounds.flattenToString());
                sample.put("ime_window_bounds",current.windowBounds.flattenToString());sample.put("ime_window_id",current.windowId);
                stableCount=previous!=null&&current.windowId==previous.windowId
                        &&current.micBounds.equals(previous.micBounds)&&current.windowBounds.equals(previous.windowBounds)?stableCount+1:1;
                previous=current;
            } else {previous=null;stableCount=0;}
            sample.put("consecutive_equal_samples",stableCount);samples.put(sample);
            if(stableCount>=3)return current;
            SystemClock.sleep(100);
        } while(SystemClock.uptimeMillis()<deadline);
        throw new AssertionError("IME microphone/window geometry did not settle: "+phase);
    }
    private AccessibilityNodeInfo waitBelowMicAction(UiAutomation automation,String pkg,Rect mic)throws InterruptedException {
        long deadline=SystemClock.uptimeMillis()+3000;
        do {for(AccessibilityWindowInfo window:automation.getWindows()){
            if(window.getType()!=AccessibilityWindowInfo.TYPE_INPUT_METHOD)continue;
            AccessibilityNodeInfo found=belowMicAction(window.getRoot(),pkg,mic);if(found!=null)return found;
        }Thread.sleep(100);}while(SystemClock.uptimeMillis()<deadline);return null;
    }
    private AccessibilityNodeInfo belowMicAction(AccessibilityNodeInfo node,String pkg,Rect mic) {
        if(node==null||!pkg.contentEquals(node.getPackageName()))return null;
        Rect r=new Rect();node.getBoundsInScreen(r);
        if(node.isVisibleToUser()&&node.isClickable()&&node.getContentDescription()!=null
                &&r.centerX()==mic.centerX()&&r.top>=mic.bottom&&r.top<=mic.bottom+mic.height()/2
                &&r.width()==mic.width()&&r.height()==mic.height())return node;
        for(int i=0;i<node.getChildCount();i++){AccessibilityNodeInfo found=belowMicAction(node.getChild(i),pkg,mic);if(found!=null)return found;}
        return null;
    }
    private AccessibilityNodeInfo waitNode(UiAutomation automation,String pkg,String label)throws InterruptedException {
        long deadline=SystemClock.uptimeMillis()+6000;
        do {for(AccessibilityWindowInfo window:automation.getWindows()){
            if(window.getType()!=AccessibilityWindowInfo.TYPE_INPUT_METHOD)continue;
            AccessibilityNodeInfo root=window.getRoot();if(root==null||!pkg.contentEquals(root.getPackageName()))continue;
            List<AccessibilityNodeInfo> nodes=root.findAccessibilityNodeInfosByText(label);
            for(AccessibilityNodeInfo node:nodes)if(node.isVisibleToUser()&&label.contentEquals(node.getText()==null?"":node.getText()))return node;
        }Thread.sleep(100);}while(SystemClock.uptimeMillis()<deadline);return null;
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
    private void screenshot(UiAutomation automation,Activity activity,String pkg,File file)throws Exception {
        AccessibilityNodeInfo root=automation.getRootInActiveWindow();assertNotNull("No focused window",root);
        assertEquals("Refuse screenshot outside own app",pkg,String.valueOf(root.getPackageName()));
        assertFalse("Fixture Activity ended",activity.isFinishing());Bitmap bitmap=automation.takeScreenshot();assertNotNull(bitmap);
        try(FileOutputStream out=new FileOutputStream(file)){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,out));}finally{bitmap.recycle();}
    }
}
