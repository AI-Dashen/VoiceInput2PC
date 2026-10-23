package io.github.amxooo.voiceinput2pc;

import android.content.Context;
import android.content.ContextWrapper;
import android.test.InstrumentationTestCase;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputConnectionWrapper;
import android.widget.EditText;
import android.widget.TextView;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.UUID;

/** Android editor transaction tests, not real microphone or system-bound overlay evidence. */
public final class VoiceDockIntegrationTest extends InstrumentationTestCase {
    private static final class Speech implements SpeechInput {
        long generation; int starts,cancels;
        public void start(long g,OfflineSpeechRecognizer.Listener listener){generation=g;starts++;}
        public void finish(){} public void cancel(){cancels++;} public void close(){cancel();}
    }
    private static final class Ime extends UniversalInputMethodService {
        InputConnection connection; EditorInfo editor; EditText host; Speech recorder; int sends;
        public InputConnection getCurrentInputConnection(){return connection;}
        public EditorInfo getCurrentInputEditorInfo(){return editor;}
    }
    private static Object get(Object obj,String name)throws Exception {
        Field f=UniversalInputMethodService.class.getDeclaredField(name);f.setAccessible(true);return f.get(obj);
    }
    private static void put(Object obj,String name,Object value)throws Exception {
        Field f=UniversalInputMethodService.class.getDeclaredField(name);f.setAccessible(true);f.set(obj,value);
    }
    private static Object call(Object obj,String name,Class<?>[] types,Object...args)throws Exception {
        Method m=UniversalInputMethodService.class.getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(obj,args);
    }
    private Ime fixture()throws Exception {
        Context c=getInstrumentation().getTargetContext();Ime ime=new Ime();
        Method attach=ContextWrapper.class.getDeclaredMethod("attachBaseContext",Context.class);attach.setAccessible(true);attach.invoke(ime,c);
        ime.editor=new EditorInfo();ime.editor.packageName="dock.fixture";ime.editor.fieldId=21;ime.editor.inputType=1;ime.editor.imeOptions=EditorInfo.IME_ACTION_SEND;
        ime.host=new EditText(c);ime.recorder=new Speech();
        ime.connection=new InputConnectionWrapper(ime.host.onCreateInputConnection(new EditorInfo()),false){
            @Override public boolean performEditorAction(int action){ime.sends++;return true;}
        };
        put(ime,"speech",ime.recorder);put(ime,"prefs",c.getSharedPreferences("dock-fixture-"+UUID.randomUUID(),0));
        put(ime,"keyboard",new KeyboardController(new ChineseInputEngine(){
            public Snapshot snapshot(){return new Snapshot("",Collections.emptyList(),0,false,true,"");}
            public void input(char value){} public void backspace(){} public String select(int i){return null;}
            public void nextPage(){}public void previousPage(){}public void setLayout(Layout l){}public void reset(){}public void close(){}
        }));
        ime.onCreateInputView();ime.onStartInput(ime.editor,false);
        return ime;
    }
    private interface Checked {void run()throws Exception;}
    private void ui(Checked action)throws Throwable {runTestOnUiThread(()->{try{action.run();}catch(Exception e){throw new AssertionError(e);}});}
    private static View described(View v,String description) {
        if(v.getVisibility()!=View.VISIBLE)return null;
        if(description.contentEquals(v.getContentDescription()==null?"":v.getContentDescription()))return v;
        if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++){
            View found=described(g.getChildAt(i),description);if(found!=null)return found;
        }}
        return null;
    }
    private static TextView text(View view,String label){
        if(view.getVisibility()!=View.VISIBLE)return null;
        if(view instanceof TextView && label.contentEquals(((TextView)view).getText()))return (TextView)view;
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){TextView found=text(group.getChildAt(i),label);if(found!=null)return found;}}
        return null;
    }
    public void testDefaultDockHasNoTextEditorAndDoesNotListen()throws Throwable {
        ui(()->{Ime ime=fixture();assertEquals(0,ime.recorder.starts);
            assertEquals(View.VISIBLE,((View)get(ime,"floatingDock")).getVisibility());
            assertEquals(View.GONE,((View)get(ime,"normalRoot")).getVisibility());
            assertEquals(View.GONE,((View)get(ime,"preview")).getVisibility());
            View dock=(View)get(ime,"floatingDock");
            assertEquals(View.VISIBLE,((View)get(ime,"dockAction")).getVisibility());
            assertTrue(((View)get(ime,"dockAction")).isEnabled());
            View delete=described(dock,"删除，按住连续删除");assertNotNull(delete);assertTrue(delete.isEnabled());
            assertNull(described(dock,"键盘"));assertNull(text(dock,"功能"));assertNull(text(dock,"本机"));
            View mic=(View)get(ime,"micIcon");
            int touchSize=Math.round(48*ime.getResources().getDisplayMetrics().density);
            assertEquals(touchSize,mic.getLayoutParams().width);assertEquals(touchSize,mic.getLayoutParams().height);
            assertTrue(mic.performLongClick());assertNotNull(described((View)get(ime,"dockRoot"),"键盘"));
            assertEquals(ImePanelController.Feature.HUB,((ImePanelController)get(ime,"panels")).feature());
            assertEquals(0,ime.recorder.starts);
        });
    }
    public void testFinalDirectlyWritesHostOnceAndNeverSends()throws Throwable {
        final Ime[] s={null};ui(()->{s[0]=fixture();((View)get(s[0],"micIcon")).performClick();assertEquals(1,s[0].recorder.starts);});
        long old=s[0].recorder.generation;s[0].onResult(old,"第一段");getInstrumentation().waitForIdleSync();
        ui(()->{assertEquals("第一段",s[0].host.getText().toString());assertEquals(0,s[0].sends);assertEquals("",((InputSession)get(s[0],"session")).preview());
            ((View)get(s[0],"micIcon")).performClick();assertEquals(2,s[0].recorder.starts);});
        s[0].onResult(old,"迟到重复");s[0].onResult(s[0].recorder.generation,"第二段");getInstrumentation().waitForIdleSync();
        ui(()->{assertEquals("第一段第二段",s[0].host.getText().toString());assertEquals(0,s[0].sends);});
    }
    public void testPartialIsReplacedByFinalNotDuplicated()throws Throwable {
        final Ime[] s={null};ui(()->{s[0]=fixture();((View)get(s[0],"micIcon")).performClick();});
        long g=s[0].recorder.generation;s[0].onPartial(g,"你好");getInstrumentation().waitForIdleSync();
        ui(()->assertEquals("你好",s[0].host.getText().toString()));
        s[0].onResult(g,"你好世界");getInstrumentation().waitForIdleSync();
        ui(()->{assertEquals("你好世界",s[0].host.getText().toString());assertEquals(0,s[0].sends);});
    }
    public void testNewFieldRejectsOldFinal()throws Throwable {
        final Ime[] s={null};final long[] old={0};ui(()->{s[0]=fixture();((View)get(s[0],"micIcon")).performClick();old[0]=s[0].recorder.generation;
            s[0].editor=new EditorInfo();s[0].editor.inputType=1;s[0].editor.packageName="second.fixture";s[0].editor.fieldId=22;s[0].onStartInput(s[0].editor,false);});
        s[0].onResult(old[0],"不应出现");getInstrumentation().waitForIdleSync();
        ui(()->assertEquals("",s[0].host.getText().toString()));
    }
    public void testCursorMoveInvalidatesRunningSpeech()throws Throwable {
        final Ime[] s={null};final long[] old={0};ui(()->{s[0]=fixture();s[0].host.setText("前后");s[0].host.setSelection(2);
            s[0].onUpdateSelection(0,0,2,2,-1,-1);((View)get(s[0],"micIcon")).performClick();old[0]=s[0].recorder.generation;
            s[0].host.setSelection(0);s[0].onUpdateSelection(2,2,0,0,-1,-1);});
        s[0].onResult(old[0],"迟到");getInstrumentation().waitForIdleSync();
        ui(()->{assertEquals("前后",s[0].host.getText().toString());assertTrue(s[0].recorder.cancels>0);});
    }
    public void testRemoteFinalDoesNotTouchHostOrAutoSend()throws Throwable {
        final Ime[] s={null};ui(()->{s[0]=fixture();OutputTargetController target=(OutputTargetController)get(s[0],"targets");target.setPairing("fixture-device","测试电脑");assertEquals(OutputTargetController.ToggleResult.OK,target.toggleTarget());
            ((View)get(s[0],"micIcon")).performClick();});
        s[0].onResult(s[0].recorder.generation,"发往电脑");getInstrumentation().waitForIdleSync();
        ui(()->{OutputTargetController target=(OutputTargetController)get(s[0],"targets");assertEquals("发往电脑",target.pendingText());assertFalse(target.sending());assertEquals("",s[0].host.getText().toString());assertEquals(0,s[0].sends);});
    }
    public void testKeyboardUsesThreeRowsWithLongPressDigitsThenReturnsWithoutRecording()throws Throwable {
        ui(()->{Ime s=fixture();assertTrue(((View)get(s,"micIcon")).performLongClick());
            described((View)get(s,"dockRoot"),"键盘").performClick();
            assertEquals(View.GONE,((View)get(s,"floatingDock")).getVisibility());
            assertEquals(View.VISIBLE,((View)get(s,"normalRoot")).getVisibility());
            assertNull(text((View)get(s,"normalRoot"),"九宫格"));
            ViewGroup rows=(ViewGroup)get(s,"keys");assertEquals(4,rows.getChildCount());
            for(char digit:"1234567890".toCharArray())assertNull("no separate digit key "+digit,text(rows,String.valueOf(digit)));
            String letters="qwertyuiop";
            for(int i=0;i<letters.length();i++)assertTrue(text(rows,letters.substring(i,i+1)).performLongClick());
            assertEquals("1234567890",s.host.getText().toString());
            call(s,"selectVoiceMode",new Class[0]);assertEquals(View.VISIBLE,((View)get(s,"floatingDock")).getVisibility());
            assertNull(described((View)get(s,"floatingDock"),"键盘"));assertEquals(0,s.recorder.starts);
        });
    }
    public void testRejectedHostCommitRetainsRecoverableText()throws Throwable {
        final Ime[] s={null};ui(()->{s[0]=fixture();s[0].connection=new InputConnectionWrapper(s[0].connection,false){
            public boolean commitText(CharSequence text,int cursor){return false;}
        };((View)get(s[0],"micIcon")).performClick();});
        s[0].onResult(s[0].recorder.generation,"不能丢失");getInstrumentation().waitForIdleSync();
        ui(()->{assertEquals("",s[0].host.getText().toString());InputSession state=(InputSession)get(s[0],"session");
            assertEquals(InputSession.State.PREVIEW,state.state());assertEquals("不能丢失",state.preview());assertEquals(0,s[0].sends);
        });
    }
    public void testSameZeroFieldIdFreshSessionRejectsOldFinal()throws Throwable {
        final Ime[] s={null};final long[] old={0};ui(()->{s[0]=fixture();s[0].editor.fieldId=0;s[0].onStartInput(s[0].editor,false);
            ((View)get(s[0],"micIcon")).performClick();old[0]=s[0].recorder.generation;
            s[0].onStartInput(s[0].editor,false);
            assertTrue(((InputSession)get(s[0],"session")).generation()!=old[0]);});
        s[0].onResult(old[0],"旧session");getInstrumentation().waitForIdleSync();ui(()->assertEquals("",s[0].host.getText().toString()));
    }
    public void testMovingCursorAfterPartialPreservesHostTextAndRejectsLateFinal()throws Throwable {
        final Ime[] s={null};ui(()->{s[0]=fixture();s[0].host.setText("原文");s[0].host.setSelection(2);
            s[0].onUpdateSelection(0,0,2,2,-1,-1);((View)get(s[0],"micIcon")).performClick();});
        long g=s[0].recorder.generation;s[0].onPartial(g,"临时");getInstrumentation().waitForIdleSync();
        ui(()->{assertEquals("原文临时",s[0].host.getText().toString());s[0].host.setSelection(0);
            s[0].onUpdateSelection(4,4,0,0,2,4);assertEquals("原文临时",s[0].host.getText().toString());});
        s[0].onResult(g,"迟到覆盖");getInstrumentation().waitForIdleSync();ui(()->assertEquals("原文临时",s[0].host.getText().toString()));
    }

}
