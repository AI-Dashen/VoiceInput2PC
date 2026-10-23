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
import android.widget.TextView;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;

/** Real production layouts + Rime + EditText, detached from system IME window. No mic or foreground operations. */
public final class KeyboardViewportStabilityTest extends InstrumentationTestCase {
    private static final class Ime extends UniversalInputMethodService {
        View root; EditText host; EditorInfo editor; InputConnection connection; int commits, actions;
        public InputConnection getCurrentInputConnection(){return connection;}
        public EditorInfo getCurrentInputEditorInfo(){return editor;}
    }
    private static Object get(Object o,String name)throws Exception {
        Field f=UniversalInputMethodService.class.getDeclaredField(name);f.setAccessible(true);return f.get(o);
    }
    private static void put(Object o,String name,Object value)throws Exception {
        Field f=UniversalInputMethodService.class.getDeclaredField(name);f.setAccessible(true);f.set(o,value);
    }
    private static void call(Object o,String name,Class<?>[] args,Object... values)throws Exception {
        Method m=UniversalInputMethodService.class.getDeclaredMethod(name,args);m.setAccessible(true);m.invoke(o,values);
    }
    private Ime fixture()throws Exception {
        Context c=getInstrumentation().getTargetContext();Ime s=new Ime();
        Method attach=ContextWrapper.class.getDeclaredMethod("attachBaseContext",Context.class);attach.setAccessible(true);attach.invoke(s,c);
        s.editor=new EditorInfo();s.editor.packageName="viewport.fixture";s.editor.fieldId=51;s.editor.inputType=1;
        s.editor.imeOptions=EditorInfo.IME_ACTION_SEND|EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING;
        s.host=new EditText(c);
        s.connection=new InputConnectionWrapper(s.host.onCreateInputConnection(new EditorInfo()),false){
            public boolean commitText(CharSequence text,int cursor){s.commits++;return super.commitText(text,cursor);}
            public boolean performEditorAction(int action){s.actions++;return true;}
        };
        put(s,"prefs",c.getSharedPreferences("viewport-fixture-"+UUID.randomUUID(),0));
        put(s,"keyboard",new KeyboardController(new RimeChineseInputEngine(c)));
        put(s,"speech",new SpeechInput(){
            public void start(long g,OfflineSpeechRecognizer.Listener l){throw new AssertionError("No microphone in viewport test");}
            public void finish(){}public void cancel(){}public void close(){}
        });
        s.root=s.onCreateInputView();s.onStartInput(s.editor,false);
        call(s,"switchMode",new Class[]{KeyboardController.Mode.class},KeyboardController.Mode.PINYIN);
        layout(s);return s;
    }
    private interface Checked{void run()throws Exception;}
    private void ui(Checked r)throws Throwable{runTestOnUiThread(()->{try{r.run();}catch(Exception e){throw new AssertionError(e);}});}
    private void layout(Ime s){
        int w=s.getResources().getDisplayMetrics().widthPixels,h=s.getResources().getDisplayMetrics().heightPixels;
        s.root.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.AT_MOST));
        s.root.layout(0,0,s.root.getMeasuredWidth(),s.root.getMeasuredHeight());
    }
    private static int top(View view){int y=0;View current=view;while(current!=null){
        y+=current.getTop()+Math.round(current.getTranslationY());
        if(current.getParent() instanceof View){current=(View)current.getParent();y-=current.getScrollY();}else current=null;
    }return y;}
    private static int left(View view){int x=0;View current=view;while(current!=null){
        x+=current.getLeft()+Math.round(current.getTranslationX());
        if(current.getParent() instanceof View){current=(View)current.getParent();x-=current.getScrollX();}else current=null;
    }return x;}
    private static TextView key(View view,String text){
        if(view.getVisibility()!=View.VISIBLE)return null;
        if(view instanceof TextView&&text.contentEquals(((TextView)view).getText()))return (TextView)view;
        if(view instanceof ViewGroup){ViewGroup g=(ViewGroup)view;for(int i=0;i<g.getChildCount();i++){TextView v=key(g.getChildAt(i),text);if(v!=null)return v;}}
        return null;
    }
    private int[] geometry(Ime s)throws Exception {
        int[] result=new int[7];result[0]=s.root.getHeight();int i=1;
        for(String name:new String[]{"normalRoot","primaryPanel","keys"}){View v=(View)get(s,name);result[i++]=top(v);result[i++]=v.getHeight();}
        return result;
    }
    private void assertStable(Ime s,int[] initial,String stage)throws Exception {
        layout(s);int[] actual=geometry(s);
        String[] names={"root height","normalRoot top","normalRoot height","primaryPanel top","primaryPanel height","keys top","keys height"};
        for(int i=0;i<initial.length;i++)assertEquals(stage+" "+names[i],initial[i],actual[i]);
    }
    private void tapRoot(Ime s,float x,float y){long now=SystemClock.uptimeMillis();
        MotionEvent down=MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,x,y,0),up=MotionEvent.obtain(now,now+30,MotionEvent.ACTION_UP,x,y,0);
        try{s.root.dispatchTouchEvent(down);s.root.dispatchTouchEvent(up);}finally{down.recycle();up.recycle();}
    }
    public void testRimeComposingCommitAndReentryKeepKeyboardViewportFixed()throws Throwable {
        ui(()->{Ime s=fixture();KeyboardController k=(KeyboardController)get(s,"keyboard");try{
            assertTrue("Real Rime must be available",k.snapshot().available);int[] baseline=geometry(s);
            View compose=(View)get(s,"composition"),scroll=(View)get(s,"candidateScroller");
            for(int cycle=0;cycle<3;cycle++){
                assertEquals(View.INVISIBLE,compose.getVisibility());assertEquals(View.INVISIBLE,scroll.getVisibility());
                assertTrue("invisible preedit retains measured height",compose.getHeight()>0);
                assertTrue("invisible candidates retain measured height",scroll.getHeight()>0);
                TextView n=key((View)get(s,"keys"),"n");assertNotNull(n);assertTrue(n.performClick());
                assertEquals("n",k.snapshot().rawInput);assertEquals(View.VISIBLE,scroll.getVisibility());assertStable(s,baseline,"cycle "+cycle+" n");
                TextView i=key((View)get(s,"keys"),"i");assertNotNull(i);assertTrue(i.performClick());
                assertEquals("ni",k.snapshot().rawInput);assertFalse(k.snapshot().candidates.isEmpty());assertStable(s,baseline,"cycle "+cycle+" ni");
                String expected=k.snapshot().candidates.get(0);TextView candidate=key((View)get(s,"candidates"),expected);assertNotNull(candidate);assertTrue(candidate.performClick());
                assertTrue("candidate confirmed",k.snapshot().rawInput.isEmpty());assertTrue(s.host.getText().toString().endsWith(expected));
                assertStable(s,baseline,"cycle "+cycle+" committed");
                assertEquals(View.INVISIBLE,scroll.getVisibility());
                String host=s.host.getText().toString();int commits=s.commits,actions=s.actions;
                tapRoot(s,left(scroll)+scroll.getWidth()/2f,top(scroll)+scroll.getHeight()/2f);
                assertEquals("empty candidate strip cannot commit",commits,s.commits);assertEquals(actions,s.actions);
                assertEquals(host,s.host.getText().toString());assertTrue(k.snapshot().rawInput.isEmpty());
                assertStable(s,baseline,"cycle "+cycle+" empty strip touch");
            }
        }finally{k.close();}});
    }
}
