package io.github.amxooo.voiceinput2pc;

import android.content.Context;
import android.content.ContextWrapper;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputConnectionWrapper;
import android.widget.EditText;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.UUID;

/** Production View/editor integration fixture; no real microphone or system-bound IME claim. */
public final class VerticalDockIntegrationTest extends InstrumentationTestCase {
    private static final class Speech implements SpeechInput {
        int starts, finishes; long generation;
        public void start(long g, OfflineSpeechRecognizer.Listener listener) { generation=g; starts++; }
        public void finish() { finishes++; }
        public void cancel() { }
        public void close() { }
    }
    private static final class Ime extends UniversalInputMethodService {
        InputConnection connection; EditorInfo editor; EditText host; Speech speechFake;
        View root; int actionCount, lastAction;
        public InputConnection getCurrentInputConnection() { return connection; }
        public EditorInfo getCurrentInputEditorInfo() { return editor; }
    }
    private static Object get(Object o,String name) throws Exception {
        Field f=UniversalInputMethodService.class.getDeclaredField(name);f.setAccessible(true);return f.get(o);
    }
    private static void render(Ime s) throws Exception {
        Method m=UniversalInputMethodService.class.getDeclaredMethod("render");m.setAccessible(true);m.invoke(s);
    }
    private static void put(Object o,String name,Object value) throws Exception {
        Field f=UniversalInputMethodService.class.getDeclaredField(name);f.setAccessible(true);f.set(o,value);
    }
    private Ime fixture() throws Exception {
        Context c=getInstrumentation().getTargetContext(); Ime s=new Ime();
        Method attach=ContextWrapper.class.getDeclaredMethod("attachBaseContext",Context.class);
        attach.setAccessible(true);attach.invoke(s,c);
        s.editor=new EditorInfo();s.editor.packageName="vertical.fixture";s.editor.fieldId=41;
        s.editor.inputType=1;s.editor.imeOptions=EditorInfo.IME_ACTION_SEND;
        s.host=new EditText(c);s.speechFake=new Speech();
        s.connection=new InputConnectionWrapper(s.host.onCreateInputConnection(new EditorInfo()),false) {
            public boolean performEditorAction(int action) { s.actionCount++;s.lastAction=action;return true; }
        };
        put(s,"prefs",c.getSharedPreferences("vertical-fixture-"+UUID.randomUUID(),0));
        put(s,"speech",s.speechFake);
        put(s,"keyboard",new KeyboardController(new ChineseInputEngine() {
            public Snapshot snapshot(){return new Snapshot("",Collections.emptyList(),0,false,true,"");}
            public void input(char c){} public void backspace(){} public String select(int i){return null;}
            public void nextPage(){}public void previousPage(){}public void setLayout(Layout l){}
            public void reset(){}public void close(){}
        }));
        s.root=s.onCreateInputView();s.onStartInput(s.editor,false);layout(s);return s;
    }
    private interface Checked { void run() throws Exception; }
    private void ui(Checked r) throws Throwable {
        runTestOnUiThread(()->{try{r.run();}catch(Exception e){throw new AssertionError(e);}});
    }
    private int px(Ime s,int dp){return Math.round(dp*s.getResources().getDisplayMetrics().density);}
    private void layout(Ime s){
        int w=s.getResources().getDisplayMetrics().widthPixels,h=s.getResources().getDisplayMetrics().heightPixels;
        s.root.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));
        s.root.layout(0,0,w,h);
    }
    private static View findDescription(View v,String text){
        if(v.getVisibility()!=View.VISIBLE)return null;
        if(text.contentEquals(v.getContentDescription()==null?"":v.getContentDescription()))return v;
        if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++){
            View match=findDescription(g.getChildAt(i),text);if(match!=null)return match;
        }}return null;
    }
    /** Detached production views have no Window token: getLocationInWindow may return (0,0).
     * Compute actual laid-out root-relative coordinates, including scroll and translation.
     * This fixture has no scale/rotation transforms. Never substitute policy's expected values.
     */
    private static int[] location(View v){
        float x=0,y=0;View current=v;
        while(current!=null){
            x+=current.getLeft()+current.getTranslationX();
            y+=current.getTop()+current.getTranslationY();
            android.view.ViewParent parent=current.getParent();
            if(parent instanceof View){View ancestor=(View)parent;x-=ancestor.getScrollX();y-=ancestor.getScrollY();current=ancestor;}
            else current=null;
        }
        return new int[]{Math.round(x),Math.round(y)};
    }
    private static void motion(View v,long down,long time,int action,float x,float y){
        MotionEvent e=MotionEvent.obtain(down,time,action,x,y,0);try{v.dispatchTouchEvent(e);}finally{e.recycle();}
    }
    public void testDetachedCoordinatesIncludeParentOffsetScrollAndTranslation() throws Throwable {
        ui(()->{Context c=getInstrumentation().getTargetContext();android.widget.FrameLayout root=new android.widget.FrameLayout(c);
            android.widget.FrameLayout nested=new android.widget.FrameLayout(c);View leaf=new View(c);root.addView(nested);nested.addView(leaf);
            root.layout(0,0,400,400);nested.layout(17,23,217,223);leaf.layout(5,7,53,55);
            nested.scrollTo(2,3);leaf.setTranslationX(3);leaf.setTranslationY(4);
            int[] actual=location(leaf);assertEquals(23,actual[0]);assertEquals(31,actual[1]);
        });
    }
    public void testVerticalOrderAndNonoverlappingTouchSlots() throws Throwable {
        ui(()->{Ime s=fixture();View dock=(View)get(s,"floatingDock"),mic=(View)get(s,"micIcon"),action=(View)get(s,"dockAction");
            int[] centerBefore=location(mic);render(s);layout(s);
            int[] centerAfter=location(mic);assertEquals(centerBefore[0],centerAfter[0]);assertEquals(centerBefore[1],centerAfter[1]);
            View delete=findDescription(dock,"删除，按住连续删除");assertNotNull(delete);
            assertEquals(View.VISIBLE,delete.getVisibility());assertEquals(View.VISIBLE,action.getVisibility());
            for(View v:new View[]{delete,mic,action}){assertEquals(px(s,48),v.getWidth());assertEquals(px(s,48),v.getHeight());}
            int[] d=location(delete),m=location(mic),a=location(action);
            assertEquals(d[0],m[0]);assertEquals(m[0],a[0]);
            assertTrue("delete above mic without overlap",d[1]+delete.getHeight()<=m[1]);
            assertTrue("action below mic without overlap",m[1]+mic.getHeight()<=a[1]);
            assertEquals(0,s.speechFake.starts);
        });
    }
    public void testAccessibilityCollapseClosesMoreButKeepsRequiredActionsAndAnchor() throws Throwable {
        ui(()->{Ime s=fixture();View mic=(View)get(s,"micIcon"),action=(View)get(s,"dockAction");int[] before=location(mic);
            assertEquals(View.VISIBLE,action.getVisibility());assertTrue(action.isEnabled());
            View delete=findDescription((View)get(s,"floatingDock"),"删除，按住连续删除");assertNotNull(delete);assertTrue(delete.isEnabled());
            assertTrue(mic.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_EXPAND,null));layout(s);
            int[] expanded=location(mic);assertEquals(before[0],expanded[0]);assertEquals(before[1],expanded[1]);
            assertTrue(mic.performLongClick());layout(s);
            assertEquals(ImePanelController.Feature.HUB,((ImePanelController)get(s,"panels")).feature());
            assertEquals(View.GONE,action.getVisibility());assertEquals(View.GONE,delete.getVisibility());
            assertTrue(mic.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_COLLAPSE,null));layout(s);
            assertTrue(((ImePanelController)get(s,"panels")).isPrimary());
            assertEquals(View.VISIBLE,action.getVisibility());assertTrue(action.isEnabled());
            assertEquals(View.VISIBLE,delete.getVisibility());assertTrue(delete.isEnabled());
            int[] restored=location(mic);assertEquals(before[0],restored[0]);assertEquals(before[1],restored[1]);
            assertEquals(0,s.speechFake.starts);assertEquals(0,s.actionCount);
        });
    }
    public void testFinalCallbackKeepsActionsAndPreservesMicAnchor() throws Throwable {
        final Ime[] f={null};final int[][] center={null};
        ui(()->{Ime s=fixture();f[0]=s;View mic=(View)get(s,"micIcon");center[0]=location(mic);
            assertEquals(View.VISIBLE,((View)get(s,"dockAction")).getVisibility());mic.performClick();
        });
        f[0].onResult(f[0].speechFake.generation,"验收文字");getInstrumentation().waitForIdleSync();
        ui(()->{Ime s=f[0];layout(s);View mic=(View)get(s,"micIcon"),action=(View)get(s,"dockAction");
            assertEquals("验收文字",s.host.getText().toString());assertEquals(View.VISIBLE,action.getVisibility());
            assertTrue(action.isEnabled());int[] after=location(mic);assertEquals(center[0][0],after[0]);assertEquals(center[0][1],after[1]);
            assertNotNull(findDescription((View)get(s,"floatingDock"),"删除，按住连续删除"));assertEquals(0,s.actionCount);
        });
    }
    public void testMicClickStopsWithoutSendingAndBusyActionDisabled() throws Throwable {
        ui(()->{Ime s=fixture();View mic=(View)get(s,"micIcon"),action=(View)get(s,"dockAction");
            assertTrue(action.isEnabled());mic.performClick();assertEquals(1,s.speechFake.starts);
            assertEquals(InputSession.State.LISTENING,((InputSession)get(s,"session")).state());
            assertFalse(action.isEnabled());
            View delete=(View)get(s,"dockDelete");assertEquals(View.VISIBLE,delete.getVisibility());assertFalse(delete.isEnabled());
            assertEquals(View.VISIBLE,action.getVisibility());mic.performClick();assertEquals(1,s.speechFake.finishes);
            assertEquals(0,s.actionCount);
        });
    }
    public void testLongPressOpensMoreWithoutRecordingOrSending() throws Throwable {
        ui(()->{Ime s=fixture();assertTrue(((View)get(s,"micIcon")).performLongClick());
            ImePanelController panels=(ImePanelController)get(s,"panels");
            assertEquals(ImePanelController.Feature.HUB,panels.feature());
            assertEquals(0,s.speechFake.starts);assertEquals(0,s.actionCount);
            assertNotNull(findDescription(s.root,"目标设备"));assertNotNull(findDescription(s.root,"统计"));
        });
    }
    public void testDragChangesPositionWithoutStartingSpeech() throws Throwable {
        final Ime[] f={null};final int[][] before={null};final long[] down={0};
        ui(()->{Ime s=fixture();f[0]=s;View mic=(View)get(s,"micIcon");
            before[0]=location((View)get(s,"floatingDock"));down[0]=SystemClock.uptimeMillis();
            motion(mic,down[0],down[0],MotionEvent.ACTION_DOWN,mic.getWidth()/2f,mic.getHeight()/2f);
        });
        Thread.sleep(android.view.ViewConfiguration.getLongPressTimeout()+100L);
        ui(()->{Ime s=f[0];View dock=(View)get(s,"floatingDock"),mic=(View)get(s,"micIcon");
            float x=mic.getWidth()/2f,y=mic.getHeight()/2f;
            motion(mic,down[0],SystemClock.uptimeMillis(),MotionEvent.ACTION_MOVE,x-px(s,60),y-px(s,60));
            motion(mic,down[0],SystemClock.uptimeMillis(),MotionEvent.ACTION_UP,x-px(s,60),y-px(s,60));layout(s);
            int[] after=location(dock);
            assertTrue("long-hold drag must move dock",before[0][0]!=after[0]||before[0][1]!=after[1]);
            assertTrue(after[0]>=0&&after[1]>=0);
            assertTrue(after[0]+dock.getWidth()<=s.root.getWidth());
            assertTrue(after[1]+dock.getHeight()<=s.root.getHeight());
            assertEquals(0,s.speechFake.starts);assertEquals(0,s.actionCount);
        });
    }
    public void testLongPressTouchReleaseDoesNotStartRecording() throws Throwable {
        final Ime[] fixture={null};final long[] down={0};
        ui(()->{Ime s=fixture();fixture[0]=s;View mic=(View)get(s,"micIcon");down[0]=SystemClock.uptimeMillis();
            motion(mic,down[0],down[0],MotionEvent.ACTION_DOWN,mic.getWidth()/2f,mic.getHeight()/2f);
        });
        Thread.sleep(android.view.ViewConfiguration.getLongPressTimeout()+150L);
        getInstrumentation().waitForIdleSync();
        ui(()->{Ime s=fixture[0];View mic=(View)get(s,"micIcon");
            motion(mic,down[0],SystemClock.uptimeMillis(),MotionEvent.ACTION_UP,mic.getWidth()/2f,mic.getHeight()/2f);
            assertTrue(((ImePanelController)get(s,"panels")).isPrimary());
            assertEquals(0,s.speechFake.starts);assertEquals(0,s.actionCount);
        });
    }
    public void testLeavingDeleteTouchAreaCancelsRepeat() throws Throwable {
        final Ime[] fixture={null};
        ui(()->{Ime s=fixture();fixture[0]=s;render(s);layout(s);
            s.host.setText("保护已有文字");s.host.setSelection(s.host.length());
            View delete=findDescription((View)get(s,"floatingDock"),"删除，按住连续删除");assertNotNull(delete);
            long now=SystemClock.uptimeMillis();motion(delete,now,now,MotionEvent.ACTION_DOWN,delete.getWidth()/2f,delete.getHeight()/2f);
            motion(delete,now,now+20,MotionEvent.ACTION_MOVE,-px(s,30),-px(s,30));
        });
        Thread.sleep(600);getInstrumentation().waitForIdleSync();
        ui(()->{Ime s=fixture[0];assertEquals("保护已有文字",s.host.getText().toString());assertFalse((Boolean)get(s,"deleteHeld"));});
    }
    public void testExistingMultilineHostCanDeleteAndActWithoutAnyRecording() throws Throwable {
        ui(()->{Ime s=fixture();s.host.setText("第一行\n第二行\n第三行\n已有文字");s.host.setSelection(s.host.length());
            assertEquals(0,s.speechFake.starts);
            View delete=findDescription((View)get(s,"floatingDock"),"删除，按住连续删除");
            View action=(View)get(s,"dockAction");assertNotNull(delete);
            assertEquals(View.VISIBLE,delete.getVisibility());assertTrue(delete.isEnabled());
            assertEquals(View.VISIBLE,action.getVisibility());assertTrue(action.isEnabled());
            assertTrue(delete.performClick());assertEquals("第一行\n第二行\n第三行\n已有文",s.host.getText().toString());
            assertEquals(0,s.actionCount);assertEquals(0,s.speechFake.starts);
            assertTrue(action.performClick());assertEquals(1,s.actionCount);assertEquals(EditorInfo.IME_ACTION_SEND,s.lastAction);
            assertEquals(0,s.speechFake.starts);
        });
    }
    public void testActionFollowsHostAndDeleteTargetsHost() throws Throwable {
        ui(()->{Ime s=fixture();s.editor.imeOptions=EditorInfo.IME_ACTION_SEARCH;s.onStartInput(s.editor,false);render(s);layout(s);
            View action=(View)get(s,"dockAction");assertEquals("搜索",action.getContentDescription().toString());
            action.performClick();assertEquals(1,s.actionCount);assertEquals(EditorInfo.IME_ACTION_SEARCH,s.lastAction);
            s.host.setText("测试字");s.host.setSelection(3);
            View delete=findDescription((View)get(s,"floatingDock"),"删除，按住连续删除");assertNotNull(delete);
            delete.performClick();assertEquals("测试",s.host.getText().toString());
        });
    }
}
