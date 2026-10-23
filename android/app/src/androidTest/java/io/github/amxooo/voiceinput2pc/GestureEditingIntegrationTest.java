package io.github.amxooo.voiceinput2pc;

import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Rect;
import android.os.Handler;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputConnectionWrapper;
import android.widget.EditText;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Detached production views, synthetic touches and own EditText; never binds the system IME or records. */
public final class GestureEditingIntegrationTest extends InstrumentationTestCase {
    private final ArrayList<Ime> fixtures=new ArrayList<>();
    private static final class Ime extends UniversalInputMethodService {
        InputConnection connection; EditorInfo editor; EditText host; ViewGroup root;
        int starts, cancels; long speechGeneration, downAt; float x,y;
        // The background query reads this synthetic sample, never a UI-thread EditText.
        volatile String preceding;
        @Override public InputConnection getCurrentInputConnection(){return connection;}
        @Override public EditorInfo getCurrentInputEditorInfo(){return editor;}
    }
    private interface Checked {void run() throws Exception;}
    private void ui(Checked action) throws Throwable {
        runTestOnUiThread(()->{try{action.run();}catch(Exception e){throw new AssertionError(e);}});
    }
    private static Object get(Ime ime,String name)throws Exception {
        Field f=UniversalInputMethodService.class.getDeclaredField(name);f.setAccessible(true);return f.get(ime);
    }
    private static void put(Ime ime,String name,Object value)throws Exception {
        Field f=UniversalInputMethodService.class.getDeclaredField(name);f.setAccessible(true);f.set(ime,value);
    }
    private static Object call(Ime ime,String name)throws Exception {
        Method m=UniversalInputMethodService.class.getDeclaredMethod(name);m.setAccessible(true);return m.invoke(ime);
    }
    private static int dp(Ime ime,int n){return Math.round(n*ime.getResources().getDisplayMetrics().density);}
    private static void layout(Ime ime) {
        for(int i=0;i<2;i++) {
            ime.root.measure(View.MeasureSpec.makeMeasureSpec(dp(ime,400),View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(dp(ime,280),View.MeasureSpec.EXACTLY));
            ime.root.layout(0,0,ime.root.getMeasuredWidth(),ime.root.getMeasuredHeight());
        }
    }
    private Ime fixture()throws Exception {
        Context context=getInstrumentation().getTargetContext();Ime ime=new Ime();fixtures.add(ime);
        Method attach=ContextWrapper.class.getDeclaredMethod("attachBaseContext",Context.class);
        attach.setAccessible(true);attach.invoke(ime,context);
        ime.editor=new EditorInfo();ime.editor.packageName="gesture.fixture";ime.editor.fieldId=81;
        ime.editor.inputType=1;ime.editor.imeOptions=EditorInfo.IME_ACTION_SEND;
        ime.editor.initialSelStart=-1;ime.editor.initialSelEnd=-1;
        ime.host=new EditText(context);ime.host.setText("fixture");ime.host.setSelection(7);
        ime.connection=new InputConnectionWrapper(ime.host.onCreateInputConnection(new EditorInfo()),false) {
            @Override public CharSequence getTextBeforeCursor(int length,int flags) {
                if(length!=1)throw new AssertionError("Context must query only one character");
                return ime.preceding;
            }
            @Override public boolean requestCursorUpdates(int flags){return false;}
        };
        put(ime,"prefs",context.getSharedPreferences("gesture-fixture-"+UUID.randomUUID(),0));
        put(ime,"speech",new SpeechInput(){
            public void start(long generation,OfflineSpeechRecognizer.Listener listener){ime.starts++;ime.speechGeneration=generation;}
            public void finish(){} public void cancel(){ime.cancels++;} public void close(){}
        });
        put(ime,"keyboard",new KeyboardController(new ChineseInputEngine(){
            public Snapshot snapshot(){return new Snapshot("",Collections.emptyList(),0,false,true,"");}
            public void input(char c){} public void backspace(){} public String select(int i){return null;}
            public void nextPage(){} public void previousPage(){} public void setLayout(Layout l){}
            public void reset(){} public void close(){}
        }));
        ime.root=(ViewGroup)ime.onCreateInputView();ime.onStartInput(ime.editor,false);layout(ime);
        View mic=(View)get(ime,"micIcon");Rect r=new Rect(0,0,mic.getWidth(),mic.getHeight());
        ime.root.offsetDescendantRectToMyCoords(mic,r);int[] origin=new int[2];ime.root.getLocationOnScreen(origin);
        ime.x=origin[0]+r.exactCenterX();ime.y=origin[1]+r.exactCenterY();
        assertTrue(mic.getWidth()>0);assertTrue((Boolean)get(ime,"voiceMode"));return ime;
    }
    private static void dispatch(Ime ime,MotionEvent event)throws Exception {
        try {Method m=UniversalInputMethodService.class.getDeclaredMethod("handleDockTouch",MotionEvent.class);
            m.setAccessible(true);assertEquals(Boolean.TRUE,m.invoke(ime,event));
        } finally {event.recycle();}
    }
    private static void touch(Ime ime,int action,float x,float y)throws Exception {
        long now=SystemClock.uptimeMillis();if(action==MotionEvent.ACTION_DOWN)ime.downAt=now;
        dispatch(ime,MotionEvent.obtain(ime.downAt,now,action,x,y,0));
    }
    private static void tap(Ime ime)throws Exception {
        touch(ime,MotionEvent.ACTION_DOWN,ime.x,ime.y);touch(ime,MotionEvent.ACTION_UP,ime.x,ime.y);
    }
    private static void secondPointer(Ime ime)throws Exception {
        MotionEvent.PointerProperties[] properties=new MotionEvent.PointerProperties[2];
        MotionEvent.PointerCoords[] coords=new MotionEvent.PointerCoords[2];
        for(int i=0;i<2;i++) {
            properties[i]=new MotionEvent.PointerProperties();properties[i].id=i;properties[i].toolType=MotionEvent.TOOL_TYPE_FINGER;
            coords[i]=new MotionEvent.PointerCoords();coords[i].x=ime.x+i*10;coords[i].y=ime.y;
            coords[i].pressure=1;coords[i].size=1;
        }
        dispatch(ime,MotionEvent.obtain(ime.downAt,SystemClock.uptimeMillis(),
                MotionEvent.ACTION_POINTER_DOWN|(1<<MotionEvent.ACTION_POINTER_INDEX_SHIFT),2,
                properties,coords,0,0,1,1,0,0,android.view.InputDevice.SOURCE_TOUCHSCREEN,0));
    }
    private void settleTap(){SystemClock.sleep(ViewConfiguration.getDoubleTapTimeout()+150);getInstrumentation().waitForIdleSync();}
    private static void swipeUp(Ime ime)throws Exception {
        touch(ime,MotionEvent.ACTION_DOWN,ime.x,ime.y);
        touch(ime,MotionEvent.ACTION_MOVE,ime.x,ime.y-dp(ime,65));layout(ime);
        assertEquals(View.VISIBLE,((View)get(ime,"punctuationBar")).getVisibility());
        assertEquals("Punctuation must not overlap a visible delete icon",View.INVISIBLE,((View)get(ime,"dockDelete")).getVisibility());
    }
    @Override protected void tearDown()throws Exception {
        try {getInstrumentation().runOnMainSync(()->{
            for(Ime ime:fixtures)try {
                put(ime,"destroyed",true);call(ime,"cancelDockGesture");
                ((Handler)get(ime,"main")).removeCallbacksAndMessages(null);
                ExecutorService executor=(ExecutorService)get(ime,"contextExecutor");if(executor!=null)executor.shutdownNow();
            }catch(Exception e){throw new AssertionError(e);}
        });}finally{super.tearDown();}
    }
    public void testSingleTapWaitsForDoubleTapWindowAndStartsExactlyOnce()throws Throwable {
        final Ime[] f=new Ime[1];ui(()->{f[0]=fixture();tap(f[0]);assertEquals(0,f[0].starts);});
        settleTap();ui(()->assertEquals(1,f[0].starts));settleTap();ui(()->assertEquals(1,f[0].starts));
    }
    public void testDoubleTapSwitchesKeyboardWithoutSpeech()throws Throwable {
        final Ime[] f=new Ime[1];ui(()->{f[0]=fixture();tap(f[0]);tap(f[0]);
            assertFalse((Boolean)get(f[0],"voiceMode"));assertEquals(0,f[0].starts);});
        settleTap();ui(()->{assertEquals(0,f[0].starts);assertFalse((Boolean)get(f[0],"voiceMode"));});
    }
    public void testSwipeSelectsPunctuationIntoOwnEditor()throws Throwable {
        final Ime[] f=new Ime[1];ui(()->{Ime ime=f[0]=fixture();swipeUp(ime);
            View bar=(View)get(ime,"punctuationBar");int[] origin=new int[2];ime.root.getLocationOnScreen(origin);
            float x=origin[0]+bar.getLeft()+bar.getWidth()*1.5f/5f;
            float y=origin[1]+bar.getTop()+bar.getHeight()/2f;
            touch(ime,MotionEvent.ACTION_MOVE,x,y);assertEquals(1,((Integer)get(ime,"punctuationIndex")).intValue());
            touch(ime,MotionEvent.ACTION_UP,x,y);
            assertEquals("fixture。",ime.host.getText().toString());assertEquals(View.GONE,bar.getVisibility());});
        settleTap();ui(()->assertEquals(0,f[0].starts));
    }
    public void testSwipeBackToCenterCancelsPunctuation()throws Throwable {
        final Ime[] f=new Ime[1];ui(()->{Ime ime=f[0]=fixture();swipeUp(ime);
            touch(ime,MotionEvent.ACTION_MOVE,ime.x,ime.y);assertEquals(-1,((Integer)get(ime,"punctuationIndex")).intValue());
            touch(ime,MotionEvent.ACTION_UP,ime.x,ime.y);
            assertEquals("fixture",ime.host.getText().toString());
            assertEquals(View.GONE,((View)get(ime,"punctuationBar")).getVisibility());});
        settleTap();ui(()->assertEquals(0,f[0].starts));
    }
    public void testLongPressThenDragNeverShowsPunctuationOrStartsSpeech()throws Throwable {
        final Ime[] f=new Ime[1];ui(()->{f[0]=fixture();touch(f[0],MotionEvent.ACTION_DOWN,f[0].x,f[0].y);});
        SystemClock.sleep(ViewConfiguration.getLongPressTimeout()+150);getInstrumentation().waitForIdleSync();
        ui(()->{Ime ime=f[0];assertTrue((Boolean)get(ime,"dockLongPressed"));
            float before=((Float)get(ime,"dockYFraction")).floatValue();
            touch(ime,MotionEvent.ACTION_MOVE,ime.x-dp(ime,40),ime.y-dp(ime,50));
            assertTrue((Boolean)get(ime,"dockDragging"));
            assertTrue(before!=((Float)get(ime,"dockYFraction")).floatValue());
            assertEquals(View.GONE,((View)get(ime,"punctuationBar")).getVisibility());
            touch(ime,MotionEvent.ACTION_UP,ime.x-dp(ime,40),ime.y-dp(ime,50));
            assertEquals("fixture",ime.host.getText().toString());});
        settleTap();ui(()->assertEquals(0,f[0].starts));
    }
    public void testCancelAndMultitouchCancelPendingSingleTap()throws Throwable {
        final Ime[] f=new Ime[2];ui(()->{
            for(int i=0;i<2;i++){Ime ime=f[i]=fixture();tap(ime);
                touch(ime,MotionEvent.ACTION_DOWN,ime.x,ime.y);
                if(i==0)touch(ime,MotionEvent.ACTION_CANCEL,ime.x,ime.y);else secondPointer(ime);
                touch(ime,MotionEvent.ACTION_UP,ime.x,ime.y);}
        });settleTap();ui(()->{for(Ime ime:f){assertEquals(0,ime.starts);assertTrue((Boolean)get(ime,"voiceMode"));}});
    }
    public void testFieldSwitchCancelsQueuedTapAndInFlightGesture()throws Throwable {
        final Ime[] f=new Ime[2];ui(()->{
            for(int i=0;i<2;i++){Ime ime=f[i]=fixture();
                if(i==0)tap(ime);else touch(ime,MotionEvent.ACTION_DOWN,ime.x,ime.y);
                ime.editor.fieldId++;ime.onStartInput(ime.editor,false);
                if(i==1)touch(ime,MotionEvent.ACTION_UP,ime.x,ime.y);}
        });settleTap();ui(()->{for(Ime ime:f)assertEquals(0,ime.starts);});
    }
    public void testContextStartSelectionUnknownAndDisconnectedControls()throws Throwable {
        ui(()->{Ime ime=fixture();View delete=(View)get(ime,"dockDelete"),enter=(View)get(ime,"dockAction");
            ime.host.setSelection(0);ime.onUpdateSelection(-1,-1,0,0,-1,-1);call(ime,"refreshEditorControls");
            assertEquals(View.INVISIBLE,delete.getVisibility());assertFalse(delete.isEnabled());assertTrue(enter.isEnabled());
            ime.host.setSelection(0,3);ime.onUpdateSelection(0,0,0,3,-1,-1);call(ime,"refreshEditorControls");
            assertEquals(View.VISIBLE,delete.getVisibility());assertTrue(delete.isEnabled());
            ime.onUpdateSelection(0,3,-1,-1,-1,-1);call(ime,"refreshEditorControls");
            assertEquals(View.VISIBLE,delete.getVisibility());assertTrue(delete.isEnabled());
            ime.connection=null;call(ime,"refreshEditorControls");
            assertEquals(View.VISIBLE,delete.getVisibility());assertFalse(delete.isEnabled());assertFalse(enter.isEnabled());
            assertEquals(0,ime.starts);
        });
    }
    public void testDelayedPreEditProbeCannotHideDeleteWhenHostOmitsSelectionCallback()throws Throwable {
        final Ime[] f=new Ime[1];
        CountDownLatch oldEntered=new CountDownLatch(1),releaseOld=new CountDownLatch(1);
        CountDownLatch newEntered=new CountDownLatch(1),releaseNew=new CountDownLatch(1);
        AtomicInteger queries=new AtomicInteger();
        try {
            ui(()->{
                Ime ime=f[0]=fixture();ime.host.setText("");ime.host.setSelection(0);
                InputConnection delegate=ime.connection;
                ime.connection=new InputConnectionWrapper(delegate,false) {
                    @Override public CharSequence getTextBeforeCursor(int length,int flags) {
                        if(length!=1)throw new AssertionError("Probe must remain bounded to one character");
                        int query=queries.incrementAndGet();
                        CountDownLatch entered=query==1?oldEntered:newEntered;
                        CountDownLatch release=query==1?releaseOld:releaseNew;
                        entered.countDown();
                        try {
                            if(!release.await(5,TimeUnit.SECONDS))throw new AssertionError("Probe gate timed out");
                        }catch(InterruptedException e){Thread.currentThread().interrupt();return null;}
                        return query==1?"":"B";
                    }
                };
                ime.onUpdateSelection(-1,-1,0,0,-1,-1);
                assertEquals(View.INVISIBLE,((View)get(ime,"dockDelete")).getVisibility());
                ((Handler)get(ime,"main")).removeCallbacks((Runnable)get(ime,"contextProbe"));
                call(ime,"probeEditorContext");
            });
            assertTrue("Old empty-editor probe must be in flight",oldEntered.await(3,TimeUnit.SECONDS));
            ui(()->{
                Ime ime=f[0];Method commit=UniversalInputMethodService.class.getDeclaredMethod("commitLiteral",String.class);
                commit.setAccessible(true);commit.invoke(ime,"B");
                assertEquals("B",ime.host.getText().toString());
                // This detached host intentionally does not send onUpdateSelection back to the IME.
                assertEquals(0,((Integer)get(ime,"lastSelectionStart")).intValue());
                assertFalse((Boolean)get(ime,"contextSelectionFresh"));
                assertEquals(View.VISIBLE,((View)get(ime,"dockDelete")).getVisibility());
                assertTrue(((View)get(ime,"dockDelete")).isEnabled());
            });
            releaseOld.countDown();
            assertTrue("Stale completion must schedule a fresh probe",newEntered.await(3,TimeUnit.SECONDS));
            ui(()->{
                assertEquals("Old zero result must not be applied",-1,((Integer)get(f[0],"beforeCursorLength")).intValue());
                assertEquals(View.VISIBLE,((View)get(f[0],"dockDelete")).getVisibility());
                assertTrue(((View)get(f[0],"dockDelete")).isEnabled());
            });
            releaseNew.countDown();
            final boolean[] observed={false};
            for(int attempt=0;attempt<40&&!observed[0];attempt++) {
                SystemClock.sleep(25);
                ui(()->observed[0]=((Integer)get(f[0],"beforeCursorLength")).intValue()==1);
            }
            assertTrue("Fresh post-edit probe must reach the production callback",observed[0]);
            ui(()->{
                assertEquals("B",f[0].host.getText().toString());
                assertEquals(View.VISIBLE,((View)get(f[0],"dockDelete")).getVisibility());
                assertTrue(((View)get(f[0],"dockDelete")).isEnabled());
            });
        } finally {releaseOld.countDown();releaseNew.countDown();}
    }
    public void testPunctuationWhileListeningInvalidatesLateSpeechCallbacks()throws Throwable {
        final Ime[] f=new Ime[1];final long[] staleGeneration=new long[1];
        ui(()->{f[0]=fixture();call(f[0],"toggleVoice");staleGeneration[0]=f[0].speechGeneration;
            assertEquals(1,f[0].starts);assertEquals(Boolean.TRUE,call(f[0],"busy"));});
        f[0].onPartial(staleGeneration[0],"已有语音");getInstrumentation().waitForIdleSync();
        ui(()->{
            Ime ime=f[0];assertEquals("fixture已有语音",ime.host.getText().toString());
            int cancelsBefore=ime.cancels;swipeUp(ime);
            View bar=(View)get(ime,"punctuationBar");int[] origin=new int[2];ime.root.getLocationOnScreen(origin);
            float x=origin[0]+bar.getLeft()+bar.getWidth()*1.5f/5f;
            float y=origin[1]+bar.getTop()+bar.getHeight()/2f;
            touch(ime,MotionEvent.ACTION_MOVE,x,y);touch(ime,MotionEvent.ACTION_UP,x,y);
            assertTrue("Punctuation must cancel the active recognizer",ime.cancels>cancelsBefore);
            assertEquals(Boolean.FALSE,call(ime,"busy"));
            assertEquals("fixture已有语音。",ime.host.getText().toString());
            assertFalse(((InputSession)get(ime,"session")).isCurrent(staleGeneration[0]));
        });
        f[0].onPartial(staleGeneration[0],"迟到的部分");
        f[0].onResult(staleGeneration[0],"迟到的最终结果");
        f[0].onError(staleGeneration[0],"迟到的错误");
        getInstrumentation().waitForIdleSync();
        ui(()->{
            assertEquals("fixture已有语音。",f[0].host.getText().toString());
            assertEquals(Boolean.FALSE,call(f[0],"busy"));assertEquals(1,f[0].starts);
            assertEquals(View.GONE,((View)get(f[0],"punctuationBar")).getVisibility());
        });
    }

}
