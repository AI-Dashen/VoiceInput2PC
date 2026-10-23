package io.github.amxooo.voiceinput2pc;

import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Rect;
import android.test.InstrumentationTestCase;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputConnectionWrapper;
import android.widget.EditText;
import android.widget.FrameLayout;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.UUID;

/** Real parent measurement of detached production views; not OS-bound screen evidence. */
public final class DockParentMeasureIntegrationTest extends InstrumentationTestCase {
    private static final class Ime extends UniversalInputMethodService {
        EditorInfo editor;InputConnection connection;EditText host;
        View root;FrameLayout parent;int viewportWidth,viewportHeight;
        @Override public EditorInfo getCurrentInputEditorInfo(){return editor;}
        @Override public InputConnection getCurrentInputConnection(){return connection;}
    }
    private static Object get(Ime ime,String name)throws Exception {
        Field field=UniversalInputMethodService.class.getDeclaredField(name);field.setAccessible(true);return field.get(ime);
    }
    private static void put(Ime ime,String name,Object value)throws Exception {
        Field field=UniversalInputMethodService.class.getDeclaredField(name);field.setAccessible(true);field.set(ime,value);
    }
    private static void call(Ime ime,String name,Class<?>[] types,Object...args)throws Exception {
        Method method=UniversalInputMethodService.class.getDeclaredMethod(name,types);method.setAccessible(true);method.invoke(ime,args);
    }
    private interface Checked {void run()throws Exception;}
    private void ui(Checked work)throws Throwable {
        runTestOnUiThread(()->{try{work.run();}catch(Exception error){throw new AssertionError(error);}});
    }
    private static int px(Ime ime,int dp){return Math.round(dp*ime.getResources().getDisplayMetrics().density);}
    private Ime fixture()throws Exception {
        Context context=getInstrumentation().getTargetContext();Ime ime=new Ime();
        Method attach=ContextWrapper.class.getDeclaredMethod("attachBaseContext",Context.class);attach.setAccessible(true);attach.invoke(ime,context);
        ime.editor=new EditorInfo();ime.editor.packageName="parent-measure.fixture";ime.editor.fieldId=61;
        ime.editor.inputType=1;ime.editor.imeOptions=EditorInfo.IME_ACTION_SEND;
        ime.host=new EditText(context);
        ime.connection=new InputConnectionWrapper(ime.host.onCreateInputConnection(new EditorInfo()),false) {
            @Override public boolean requestCursorUpdates(int flags){return false;}
            @Override public boolean performEditorAction(int action){throw new AssertionError("No editor action in layout test");}
        };
        put(ime,"prefs",context.getSharedPreferences("parent-measure-"+UUID.randomUUID(),0));
        put(ime,"speech",new SpeechInput(){
            public void start(long generation,OfflineSpeechRecognizer.Listener listener){throw new AssertionError("No recording in layout test");}
            public void finish(){}public void cancel(){}public void close(){}
        });
        put(ime,"keyboard",new KeyboardController(new ChineseInputEngine(){
            public Snapshot snapshot(){return new Snapshot("",Collections.emptyList(),0,false,true,"");}
            public void input(char value){}public void backspace(){}public String select(int index){return null;}
            public void nextPage(){}public void previousPage(){}public void setLayout(Layout layout){}
            public void reset(){}public void close(){}
        }));
        ime.root=ime.onCreateInputView();ime.parent=new FrameLayout(context);
        // Model the framework's initial child params. Only production render may fix the height.
        ime.parent.addView(ime.root,new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,Gravity.BOTTOM));
        ime.viewportWidth=ime.getResources().getDisplayMetrics().widthPixels;
        ime.viewportHeight=ime.getResources().getDisplayMetrics().heightPixels;
        assertTrue("fixture requires room for the 280dp window",ime.viewportHeight>=px(ime,280));
        ime.onStartInput(ime.editor,false);measureParent(ime);return ime;
    }
    private static void forceMeasureTree(View view){
        view.forceLayout();
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)forceMeasureTree(group.getChildAt(i));}
    }
    private static void measureParent(Ime ime){
        forceMeasureTree(ime.parent);
        ime.parent.measure(View.MeasureSpec.makeMeasureSpec(ime.viewportWidth,View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(ime.viewportHeight,View.MeasureSpec.AT_MOST));
        int height=ime.parent.getMeasuredHeight();
        // A detached bottom-aligned parent simulates the window origin. It is not a screenshot.
        ime.parent.layout(0,ime.viewportHeight-height,ime.viewportWidth,ime.viewportHeight);
    }
    private static Rect microphoneBounds(Ime ime)throws Exception {
        View microphone=(View)get(ime,"micIcon");Rect bounds=new Rect(0,0,microphone.getWidth(),microphone.getHeight());
        ime.parent.offsetDescendantRectToMyCoords(microphone,bounds);
        bounds.offset(ime.parent.getLeft(),ime.parent.getTop());return bounds;
    }
    private static View described(View view,String description){
        if(view.getVisibility()!=View.VISIBLE)return null;
        if(description.contentEquals(view.getContentDescription()==null?"":view.getContentDescription()))return view;
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){
            View match=described(group.getChildAt(i),description);if(match!=null)return match;
        }}return null;
    }
    private static void assertFixedWindow(Ime ime,Rect anchor,String phase)throws Exception {
        int expected=px(ime,280);
        assertEquals(phase+" production LP height",expected,ime.root.getLayoutParams().height);
        assertEquals(phase+" root measured height",expected,ime.root.getMeasuredHeight());
        assertEquals(phase+" root layout height",expected,ime.root.getHeight());
        assertEquals(phase+" actual AT_MOST parent height",expected,ime.parent.getMeasuredHeight());
        Rect actual=microphoneBounds(ime);
        assertEquals(phase+" microphone width",px(ime,48),actual.width());
        assertEquals(phase+" microphone height",px(ime,48),actual.height());
        assertEquals(phase+" microphone position in simulated screen",anchor,actual);
        assertTrue(phase+" microphone inside parent",actual.top>=ime.parent.getTop()&&actual.bottom<=ime.parent.getBottom());
    }
    public void testEightParentMeasureRenderAndExpandCyclesKeepExactAnchor()throws Throwable {
        ui(()->{Ime ime=fixture();Rect anchor=microphoneBounds(ime);assertFixedWindow(ime,anchor,"initial");
            for(int cycle=0;cycle<8;cycle++){
                call(ime,"render",new Class<?>[0]);measureParent(ime);assertFixedWindow(ime,anchor,"render "+cycle);
                assertTrue(((View)get(ime,"micIcon")).performAccessibilityAction(AccessibilityNodeInfo.ACTION_EXPAND,null));
                measureParent(ime);assertFixedWindow(ime,anchor,"expand "+cycle);
                measureParent(ime);assertFixedWindow(ime,anchor,"second measure "+cycle);
            }
        });
    }
    public void testEightHubTransferAndCloseCyclesKeepSameParentAndMic()throws Throwable {
        ui(()->{Ime ime=fixture();Rect anchor=microphoneBounds(ime);
            for(int cycle=0;cycle<8;cycle++){
                View microphone=(View)get(ime,"micIcon");assertTrue(microphone.performLongClick());
                measureParent(ime);assertFixedWindow(ime,anchor,"hub "+cycle);
                View devices=described(ime.root,"目标设备");assertNotNull(devices);assertTrue(devices.performClick());
                measureParent(ime);assertFixedWindow(ime,anchor,"transfer "+cycle);
                call(ime,"render",new Class<?>[0]);measureParent(ime);assertFixedWindow(ime,anchor,"transfer repeat "+cycle);
                assertTrue(microphone.performAccessibilityAction(AccessibilityNodeInfo.ACTION_COLLAPSE,null));
                measureParent(ime);assertFixedWindow(ime,anchor,"close "+cycle);
                assertTrue(((ImePanelController)get(ime,"panels")).isPrimary());
            }
        });
    }
    public void testKeyboardRestoresWrapContentThenVoiceRestoresFixedWindow()throws Throwable {
        ui(()->{Ime ime=fixture();Rect anchor=microphoneBounds(ime);
            call(ime,"switchMode",new Class<?>[]{KeyboardController.Mode.class},KeyboardController.Mode.PINYIN);measureParent(ime);
            assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT,ime.root.getLayoutParams().height);
            assertEquals(View.VISIBLE,((View)get(ime,"normalRoot")).getVisibility());
            assertTrue(ime.root.getMeasuredHeight()>0);
            call(ime,"selectVoiceMode",new Class<?>[0]);measureParent(ime);assertFixedWindow(ime,anchor,"return to voice");
        });
    }
}
