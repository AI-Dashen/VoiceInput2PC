package io.github.amxooo.voiceinput2pc;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.UiAutomation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.SystemClock;
import android.provider.Settings;
import android.test.InstrumentationTestCase;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;

/** Real selected IME, own Activity/editor only. Does not select an IME, record, or send. */
public final class GestureBoundDeviceTest extends InstrumentationTestCase {
    private long deadline;
    private final JSONArray steps=new JSONArray();
    private interface Condition {boolean ready() throws Exception;}
    private void await(String label,Condition condition)throws Exception {
        long until=Math.min(deadline,SystemClock.uptimeMillis()+4000);
        do {if(condition.ready())return;SystemClock.sleep(60);}while(SystemClock.uptimeMillis()<until);
        fail("Timed out: "+label);
    }
    private AccessibilityNodeInfo find(AccessibilityNodeInfo node,String pkg,String label,boolean description) {
        if(node==null||!pkg.contentEquals(node.getPackageName()))return null;
        CharSequence value=description?node.getContentDescription():node.getText();
        if(node.isVisibleToUser()&&value!=null&&(description?value.toString().startsWith(label):label.contentEquals(value)))return node;
        for(int i=0;i<node.getChildCount();i++) {
            AccessibilityNodeInfo found=find(node.getChild(i),pkg,label,description);if(found!=null)return found;
        }
        return null;
    }
    private AccessibilityNodeInfo node(UiAutomation automation,String pkg,String label,boolean description) {
        for(AccessibilityWindowInfo window:automation.getWindows()) {
            if(window.getType()!=AccessibilityWindowInfo.TYPE_INPUT_METHOD)continue;
            AccessibilityNodeInfo root=window.getRoot();if(root==null||!root.refresh())continue;
            AccessibilityNodeInfo found=find(root,pkg,label,description);
            if(found!=null&&found.refresh()&&found.isVisibleToUser())return found;
        }
        return null;
    }
    private AccessibilityNodeInfo waitNode(UiAutomation automation,String pkg,String label,boolean description)throws Exception {
        final AccessibilityNodeInfo[] result={null};
        await(label,()->(result[0]=node(automation,pkg,label,description))!=null);return result[0];
    }
    private Rect stableMic(UiAutomation automation,String pkg)throws Exception {
        final Rect[] previous={null};final int[] equal={0};
        await("stable mic",()->{
            AccessibilityNodeInfo mic=node(automation,pkg,"麦克风可用",true);
            if(mic==null){equal[0]=0;previous[0]=null;return false;}
            Rect r=new Rect();mic.getBoundsInScreen(r);
            equal[0]=r.equals(previous[0])?equal[0]+1:1;previous[0]=r;
            return !r.isEmpty()&&equal[0]>=3;
        });return previous[0];
    }
    private Rect freshMicTarget(UiAutomation automation,Activity activity,String pkg,Rect expected,
            JSONObject evidence,String phase)throws Exception {
        ownForeground(automation,activity,pkg);
        AccessibilityNodeInfo mic=node(automation,pkg,"麦克风可用",true);
        assertNotNull("Fresh microphone target absent: "+phase,mic);
        Rect actual=new Rect();mic.getBoundsInScreen(actual);
        Rect windowBounds=new Rect();boolean ownWindow=false;
        for(AccessibilityWindowInfo window:automation.getWindows()) {
            if(window.getType()!=AccessibilityWindowInfo.TYPE_INPUT_METHOD||window.getId()!=mic.getWindowId())continue;
            AccessibilityNodeInfo root=window.getRoot();
            if(root!=null&&pkg.contentEquals(root.getPackageName())) {
                window.getBoundsInScreen(windowBounds);ownWindow=true;break;
            }
        }
        evidence.put(phase,new JSONObject().put("uptime_ms",SystemClock.uptimeMillis())
                .put("mic_bounds",actual.flattenToString()).put("ime_window_bounds",windowBounds.flattenToString()));
        assertTrue("Target not inside own IME window",ownWindow&&windowBounds.contains(actual));
        assertEquals("Microphone moved before injection: "+phase,expected,actual);
        return actual;
    }
    private Rect captureStableMic(UiAutomation automation,Activity activity,String pkg,File dir,String phase,
            JSONObject evidence)throws Exception {
        waitNode(automation,pkg,"麦克风可用",true);
        SystemClock.sleep(600); // Do not mistake the entrance animation's initial pause for stability.
        Rect before=stableMic(automation,pkg);
        evidence.put(phase+"_before_capture",before.flattenToString());
        capture(automation,activity,pkg,dir,phase);
        Rect after=stableMic(automation,pkg);
        evidence.put(phase+"_after_capture",after.flattenToString());
        assertEquals("Microphone moved across screenshot: "+phase,before,after);
        return freshMicTarget(automation,activity,pkg,after,evidence,phase+"_fresh_target");
    }
    private void ownForeground(UiAutomation automation,Activity activity,String pkg) {
        assertFalse("Fixture ended",activity.isFinishing());
        AccessibilityNodeInfo root=automation.getRootInActiveWindow();assertNotNull("No foreground window",root);
        assertEquals("Refuse actions/capture outside own app",pkg,String.valueOf(root.getPackageName()));
    }
    private void event(UiAutomation automation,long down,int action,float x,float y,boolean sync) {
        MotionEvent e=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,0);
        e.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try{assertTrue("Touch injection rejected",automation.injectInputEvent(e,sync));}finally{e.recycle();}
    }
    private void capture(UiAutomation automation,Activity activity,String pkg,File dir,String name)throws Exception {
        // Accessibility/layout can precede the compositor by a frame. Capture the drawn state.
        SystemClock.sleep(160);getInstrumentation().waitForIdleSync();
        ownForeground(automation,activity,pkg);Bitmap b=automation.takeScreenshot();assertNotNull(b);
        try(FileOutputStream out=new FileOutputStream(new File(dir,name+".png"))){assertTrue(b.compress(Bitmap.CompressFormat.PNG,100,out));}
        finally{b.recycle();}
        steps.put(new JSONObject().put("phase",name).put("uptime_ms",SystemClock.uptimeMillis()));
    }
    private String ownText(EditText editor) {
        final String[] text={null};getInstrumentation().runOnMainSync(()->text[0]=editor.getText().toString());return text[0];
    }
    public void testBoundDoubleTapPunctuationAndDeletionContext()throws Exception {
        Context context=getInstrumentation().getTargetContext();String pkg=context.getPackageName();
        File dir=new File(context.getExternalFilesDir(null),"evidence/gesture-bound");assertTrue(dir.isDirectory()||dir.mkdirs());
        JSONObject evidence=new JSONObject();evidence.put("steps",steps);evidence.put("package",pkg);
        evidence.put("scope","Actual OS-bound IME with own fixture editor; no recording or sending requested");
        long started=SystemClock.uptimeMillis();deadline=started+18000;
        UiAutomation automation=null;Activity activity=null;int oldFlags=0;boolean flagsChanged=false;
        final EditText[] editor={null};
        try {
            String current=Settings.Secure.getString(context.getContentResolver(),Settings.Secure.DEFAULT_INPUT_METHOD);
            ComponentName selected=current==null?null:ComponentName.unflattenFromString(current);
            boolean matches=selected!=null&&pkg.equals(selected.getPackageName())
                    &&UniversalInputMethodService.class.getName().equals(selected.getClassName());
            evidence.put("default_ime_matches",matches);
            if(!matches){evidence.put("result","PENDING");fail("PENDING: manually select this app's IME; test never changes settings");}
            automation=getInstrumentation().getUiAutomation();AccessibilityServiceInfo info=automation.getServiceInfo();
            oldFlags=info.flags;info.flags|=AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            automation.setServiceInfo(info);flagsChanged=true;
            activity=getInstrumentation().startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            final Activity host=activity;final UiAutomation ui=automation;
            getInstrumentation().runOnMainSync(()->{
                LinearLayout layout=new LinearLayout(host);layout.setOrientation(LinearLayout.VERTICAL);
                TextView title=new TextView(host);title.setText("Gesture 系统绑定测试 · 自有测试内容");layout.addView(title);
                editor[0]=new EditText(host);editor[0].setId(3902);editor[0].setInputType(1);
                editor[0].setText("Baseline");editor[0].setSelection(editor[0].length());
                layout.addView(editor[0],new LinearLayout.LayoutParams(-1,180));host.setContentView(layout);editor[0].requestFocus();
                ((InputMethodManager)host.getSystemService(Context.INPUT_METHOD_SERVICE)).showSoftInput(editor[0],InputMethodManager.SHOW_IMPLICIT);
            });
            Rect mic;
            AccessibilityNodeInfo initialMic=waitNode(ui,pkg,"麦克风可用",true);
            boolean local=String.valueOf(initialMic.getContentDescription()).contains("本机");
            evidence.put("initial_target_local",local);assertTrue("Fixture requires local target",local);
            AccessibilityNodeInfo initialDelete=waitNode(ui,pkg,"删除，按住连续删除",true);
            evidence.put("initial_delete_visible",initialDelete.isVisibleToUser());
            evidence.put("initial_delete_enabled",initialDelete.isEnabled());
            assertTrue("Baseline at end must enable delete",initialDelete.isEnabled());
            mic=captureStableMic(ui,host,pkg,dir,"default",evidence);
            evidence.put("double_tap_mic_bounds",mic.flattenToString());
            mic=freshMicTarget(ui,host,pkg,mic,evidence,"double_tap_injection_target");
            // Queue both taps without a blocking injection between them: a lone mic tap would record.
            long down=SystemClock.uptimeMillis();
            event(ui,down,MotionEvent.ACTION_DOWN,mic.centerX(),mic.centerY(),false);
            event(ui,down,MotionEvent.ACTION_UP,mic.centerX(),mic.centerY(),false);
            SystemClock.sleep(70);
            long second=SystemClock.uptimeMillis();
            event(ui,second,MotionEvent.ACTION_DOWN,mic.centerX(),mic.centerY(),false);
            event(ui,second,MotionEvent.ACTION_UP,mic.centerX(),mic.centerY(),true);
            await("double tap switches to T9 keyboard without recording UI",()->{
                boolean recording=node(ui,pkg,"正在录音",true)!=null||node(ui,pkg,"正在识别",true)!=null;
                evidence.put("double_tap_recording_ui_observed",recording);
                assertFalse("Double tap entered recording/recognition",recording);
                return node(ui,pkg,"WXYZ",false)!=null;
            });assertEquals("Baseline",ownText(editor[0]));
            capture(ui,host,pkg,dir,"keyboard");
            AccessibilityNodeInfo voice=waitNode(ui,pkg,"语音",false);
            ownForeground(ui,host,pkg);assertTrue(voice.performAction(AccessibilityNodeInfo.ACTION_CLICK));
            mic=captureStableMic(ui,host,pkg,dir,"voice-return",evidence);
            mic=freshMicTarget(ui,host,pkg,mic,evidence,"swipe_injection_target");
            evidence.put("swipe_mic_bounds",mic.flattenToString());
            down=SystemClock.uptimeMillis();float x=mic.exactCenterX(),y=mic.exactCenterY();
            event(ui,down,MotionEvent.ACTION_DOWN,x,y,false);
            event(ui,down,MotionEvent.ACTION_MOVE,x,y-65*context.getResources().getDisplayMetrics().density,true);
            AccessibilityNodeInfo period=waitNode(ui,pkg,"插入。",true);Rect target=new Rect();period.getBoundsInScreen(target);
            assertFalse("Punctuation target empty",target.isEmpty());evidence.put("period_bounds",target.flattenToString());
            capture(ui,host,pkg,dir,"punctuation-menu");
            ownForeground(ui,host,pkg);
            AccessibilityNodeInfo freshPeriod=node(ui,pkg,"插入。",true);assertNotNull(freshPeriod);
            Rect freshTarget=new Rect();freshPeriod.getBoundsInScreen(freshTarget);
            evidence.put("period_after_capture_bounds",freshTarget.flattenToString());
            assertEquals("Punctuation moved across screenshot",target,freshTarget);
            target=freshTarget;event(ui,down,MotionEvent.ACTION_MOVE,target.centerX(),target.centerY(),true);
            event(ui,down,MotionEvent.ACTION_UP,target.centerX(),target.centerY(),true);
            await("period committed to own editor",()->"Baseline。".equals(ownText(editor[0])));
            evidence.put("own_fixture_text_after_swipe",ownText(editor[0]));capture(ui,host,pkg,dir,"punctuation");
            getInstrumentation().runOnMainSync(()->{editor[0].setText("");editor[0].setSelection(0);});
            final int[] absent={0};
            await("delete absent at known start",()->{
                // Require the IME to remain present; a disappearing window is not a pass.
                boolean hidden=node(ui,pkg,"麦克风可用",true)!=null&&node(ui,pkg,"删除，按住连续删除",true)==null;
                absent[0]=hidden?absent[0]+1:0;return absent[0]>=3;
            });capture(ui,host,pkg,dir,"empty-start");
            getInstrumentation().runOnMainSync(()->{editor[0].setText("Baseline");editor[0].setSelection(0,3);});
            AccessibilityNodeInfo delete=waitNode(ui,pkg,"删除，按住连续删除",true);
            assertTrue("Selected text should be deletable",delete.isEnabled());
            final int[] selection=new int[2];getInstrumentation().runOnMainSync(()->{
                selection[0]=editor[0].getSelectionStart();selection[1]=editor[0].getSelectionEnd();});
            assertEquals(0,selection[0]);assertEquals(3,selection[1]);
            capture(ui,host,pkg,dir,"selection");
            assertTrue("Bound test exceeded 20 second budget",SystemClock.uptimeMillis()-started<20000);
            evidence.put("result","PASS");
        } catch(Throwable failure) {
            if(!"PENDING".equals(evidence.optString("result")))evidence.put("result","FAIL");
            evidence.put("error",failure.getClass().getSimpleName()+": "+failure.getMessage());
            if(failure instanceof Exception)throw (Exception)failure;throw (Error)failure;
        } finally {
            // Cleanup precedes evidence I/O, so a full disk cannot leave the fixture in front.
            try {
                if(activity!=null){final Activity host=activity;getInstrumentation().runOnMainSync(()->{
                    try{if(editor[0]!=null)((InputMethodManager)host.getSystemService(Context.INPUT_METHOD_SERVICE))
                            .hideSoftInputFromWindow(editor[0].getWindowToken(),0);}
                    finally{host.finish();}
                });}
            } finally {
                try{if(flagsChanged){AccessibilityServiceInfo reset=automation.getServiceInfo();reset.flags=oldFlags;automation.setServiceInfo(reset);}}
                finally{evidence.put("elapsed_ms",SystemClock.uptimeMillis()-started);
                    try(FileOutputStream out=new FileOutputStream(new File(dir,"gesture-evidence.json"))){out.write(evidence.toString(2).getBytes(StandardCharsets.UTF_8));}}
            }
        }
    }
    public void testRealT9SurvivesSameEditorRestartAndCommitsCandidate()throws Exception {
        Context context=getInstrumentation().getTargetContext();String pkg=context.getPackageName();
        long started=SystemClock.uptimeMillis();deadline=started+18000;
        UiAutomation automation=null;Activity activity=null;int oldFlags=0;boolean flagsChanged=false;
        final EditText[] editor={null};
        try {
            String current=Settings.Secure.getString(context.getContentResolver(),Settings.Secure.DEFAULT_INPUT_METHOD);
            ComponentName selected=current==null?null:ComponentName.unflattenFromString(current);
            assertNotNull("No selected IME",selected);assertEquals(pkg,selected.getPackageName());
            automation=getInstrumentation().getUiAutomation();AccessibilityServiceInfo info=automation.getServiceInfo();
            oldFlags=info.flags;info.flags|=AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            automation.setServiceInfo(info);flagsChanged=true;
            activity=getInstrumentation().startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            final Activity host=activity;final UiAutomation ui=automation;
            getInstrumentation().runOnMainSync(()->{
                LinearLayout layout=new LinearLayout(host);editor[0]=new EditText(host);editor[0].setId(3903);
                editor[0].setInputType(1);editor[0].setText("Baseline");editor[0].setSelection(editor[0].length());
                layout.addView(editor[0],new LinearLayout.LayoutParams(-1,180));host.setContentView(layout);editor[0].requestFocus();
                ((InputMethodManager)host.getSystemService(Context.INPUT_METHOD_SERVICE)).showSoftInput(editor[0],InputMethodManager.SHOW_IMPLICIT);
            });
            AccessibilityNodeInfo mic=waitNode(ui,pkg,"麦克风可用",true);
            assertTrue("T9 accessibility action rejected",mic.performAction(
                    UniversalInputMethodService.ACCESSIBILITY_ACTION_OPEN_T9));
            waitNode(ui,pkg,"WXYZ",false);
            for(String key:new String[]{"MNO","GHI","GHI","ABC","MNO"}) {
                AccessibilityNodeInfo digit=waitNode(ui,pkg,key,false);assertTrue("T9 key rejected: "+key,digit.performAction(AccessibilityNodeInfo.ACTION_CLICK));
                SystemClock.sleep(80);
            }
            waitNode(ui,pkg,"你好",false);assertEquals("lookup digits escaped before selection","Baseline",ownText(editor[0]));
            getInstrumentation().runOnMainSync(()->((InputMethodManager)host.getSystemService(Context.INPUT_METHOD_SERVICE)).restartInput(editor[0]));
            waitNode(ui,pkg,"WXYZ",false);AccessibilityNodeInfo candidate=waitNode(ui,pkg,"你好",false);
            assertEquals("restart leaked lookup digits","Baseline",ownText(editor[0]));
            assertTrue("candidate click rejected",candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK));
            await("candidate committed after restart",()->"Baseline你好".equals(ownText(editor[0])));
            assertTrue("T9 restart E2E exceeded budget",SystemClock.uptimeMillis()-started<18000);
        } finally {
            try {
                if(activity!=null){final Activity host=activity;getInstrumentation().runOnMainSync(()->{
                    try{if(editor[0]!=null)((InputMethodManager)host.getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(editor[0].getWindowToken(),0);}
                    finally{host.finish();}
                });}
            } finally {
                if(flagsChanged){AccessibilityServiceInfo reset=automation.getServiceInfo();reset.flags=oldFlags;automation.setServiceInfo(reset);}
            }
        }
    }

}
