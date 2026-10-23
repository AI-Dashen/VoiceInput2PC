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
public final class PinyinContinuousIntegrationTest extends InstrumentationTestCase {
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
        put(ime,"keyboard",new KeyboardController(new RimeChineseInputEngine(c)));
        ime.onCreateInputView();ime.onStartInput(ime.editor,false);
        call(ime,"switchMode",new Class[]{KeyboardController.Mode.class},KeyboardController.Mode.PINYIN);
        return ime;
    }
    private interface Checked {void run()throws Exception;}
    private void ui(Checked action)throws Throwable {runTestOnUiThread(()->{try{action.run();}catch(Exception e){throw new AssertionError(e);}});}
    private static TextView text(View view,String label){
        if(view instanceof TextView && label.contentEquals(((TextView)view).getText()))return (TextView)view;
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){TextView found=text(group.getChildAt(i),label);if(found!=null)return found;}}
        return null;
    }
    public void testLongPinyinCandidatesAndCommit()throws Throwable {
        ui(()->{Ime s=fixture();try {
            KeyboardController k=(KeyboardController)get(s,"keyboard");
            String phrase="jintianwoxiangquchihuoguo";
            for(int i=0;i<phrase.length();i++){
                call(s,"type",new Class[]{String.class},phrase.substring(i,i+1));
                assertEquals("keystroke "+i,phrase.substring(0,i+1),k.snapshot().rawInput);
                assertFalse("No candidates at "+i,k.snapshot().candidates.isEmpty());
            }
            assertTrue(k.snapshot().candidates.toString(),k.snapshot().candidates.get(0).contains("火锅"));
            call(s,"selectCandidate",new Class[]{int.class},0);
            assertTrue(s.host.getText().toString(),s.host.getText().toString().contains("火锅"));
            assertTrue(k.snapshot().rawInput.isEmpty());
        }finally{((KeyboardController)get(s,"keyboard")).close();}});
    }
    public void testMissingComposingSpanCallbackMustNotLoseTypedPinyin()throws Throwable {
        ui(()->{Ime s=fixture();try {
            KeyboardController k=(KeyboardController)get(s,"keyboard");
            String phrase="jintianwoxiangquchihuoguo";
            for(int i=0;i<phrase.length();i++){
                call(s,"type",new Class[]{String.class},phrase.substring(i,i+1));
                // Some editors report the matching cursor without composing span bounds.
                s.onUpdateSelection(i,i,i+1,i+1,-1,-1);
                assertEquals("Missing span callback at "+i,phrase.substring(0,i+1),k.snapshot().rawInput);
            }
        }finally{((KeyboardController)get(s,"keyboard")).close();}});
    }
    public void testEnterCommitsRawLatinInsteadOfFirstChineseCandidate()throws Throwable {
        ui(()->{Ime s=fixture();try {
            KeyboardController k=(KeyboardController)get(s,"keyboard");
            for(char c:"nihao".toCharArray())call(s,"type",new Class[]{String.class},String.valueOf(c));
            assertFalse(k.snapshot().candidates.isEmpty());
            ((View)get(s,"enterKey")).performClick();
            assertEquals("nihao",s.host.getText().toString());
            assertEquals("",k.snapshot().rawInput);
            assertEquals("raw commit must not trigger editor send",0,s.sends);
        }finally{((KeyboardController)get(s,"keyboard")).close();}});
    }

    public void testDeleteAndContinueLongPinyin()throws Throwable {
        ui(()->{Ime s=fixture();try {
            KeyboardController k=(KeyboardController)get(s,"keyboard");
            for(char c:"jintianwoxiangquchihuoguo".toCharArray())call(s,"type",new Class[]{String.class},String.valueOf(c));
            k.backspace();call(s,"syncComposition",new Class[0]);
            assertEquals("jintianwoxiangquchihuogu",k.snapshot().rawInput);
            call(s,"type",new Class[]{String.class},"o");
            assertTrue(k.snapshot().candidates.toString(),k.snapshot().candidates.get(0).contains("火锅"));
        }finally{((KeyboardController)get(s,"keyboard")).close();}});
    }
    public void testDelayedCursorCallbackCannotClearNewerComposition()throws Throwable {
        ui(()->{Ime s=fixture();try {
            KeyboardController k=(KeyboardController)get(s,"keyboard");
            for(char c:"nihaoshijie".toCharArray())call(s,"type",new Class[]{String.class},String.valueOf(c));
            s.onUpdateSelection(1,1,2,2,-1,-1);
            assertEquals("nihaoshijie",k.snapshot().rawInput);
            assertEquals("nihaoshijie",s.host.getText().toString());
        }finally{((KeyboardController)get(s,"keyboard")).close();}});
    }
    public void testActualCursorMovePreservesTextAndStartsFreshComposition()throws Throwable {
        ui(()->{Ime s=fixture();try {
            KeyboardController k=(KeyboardController)get(s,"keyboard");
            for(char c:"nihao".toCharArray())call(s,"type",new Class[]{String.class},String.valueOf(c));
            s.host.setSelection(0);s.onUpdateSelection(5,5,0,0,0,5);
            assertEquals("nihao",s.host.getText().toString());assertEquals("",k.snapshot().rawInput);
            call(s,"type",new Class[]{String.class},"w");
            assertEquals("w",k.snapshot().rawInput);assertEquals("wnihao",s.host.getText().toString());
        }finally{((KeyboardController)get(s,"keyboard")).close();}});
    }
    public void testLongSentenceBeyondTwentyFourLettersAndAbbreviation()throws Throwable {
        ui(()->{Ime s=fixture();try {
            KeyboardController k=(KeyboardController)get(s,"keyboard");
            String phrase="jintianwoxianghepengyouyiqiquchihuoguo";
            for(char c:phrase.toCharArray())call(s,"type",new Class[]{String.class},String.valueOf(c));
            assertEquals(phrase,k.snapshot().rawInput);assertFalse(k.snapshot().candidates.isEmpty());
            assertTrue(k.snapshot().candidates.toString(),k.snapshot().candidates.get(0).contains("火锅"));
            call(s,"selectCandidate",new Class[]{int.class},0);
            for(char c:"nh".toCharArray())call(s,"type",new Class[]{String.class},String.valueOf(c));
            assertTrue(k.snapshot().candidates.toString(),k.snapshot().candidates.contains("你好"));
        }finally{((KeyboardController)get(s,"keyboard")).close();}});
    }
}
