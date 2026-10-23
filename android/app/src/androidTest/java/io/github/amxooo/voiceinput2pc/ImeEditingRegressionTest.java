package io.github.amxooo.voiceinput2pc;

import android.content.Context;
import android.content.ContextWrapper;
import android.database.Cursor;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import android.test.InstrumentationTestCase;
import android.widget.EditText;
import android.widget.TextView;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.UUID;

/** Real Android widgets/SQLite with isolated test data; no microphone or private user data. */
public final class ImeEditingRegressionTest extends InstrumentationTestCase {
    private static final class FakeSpeech implements SpeechInput {
        long generation;int starts,finishes,cancels;OfflineSpeechRecognizer.Listener listener;
        public void start(long value,OfflineSpeechRecognizer.Listener callback){generation=value;listener=callback;starts++;}
        public void finish(){finishes++;}
        public void cancel(){cancels++;}
        public void close(){cancel();}
    }
    private static final class TestIme extends UniversalInputMethodService {
        InputConnection connection;EditorInfo editor;
        @Override public InputConnection getCurrentInputConnection(){return connection;}
        @Override public EditorInfo getCurrentInputEditorInfo(){return editor;}
    }
    private static Object field(Object object,String name) throws Exception {
        Field f=UniversalInputMethodService.class.getDeclaredField(name);f.setAccessible(true);return f.get(object);
    }
    private static void set(Object object,String name,Object value) throws Exception {
        Field f=UniversalInputMethodService.class.getDeclaredField(name);f.setAccessible(true);f.set(object,value);
    }
    private static Object call(Object object,String name,Class<?>[] types,Object... args) throws Exception {
        Method m=UniversalInputMethodService.class.getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(object,args);
    }
    private UniversalInputMethodService fixture(String text) throws Exception {
        Context context=getInstrumentation().getTargetContext();
        TestIme service=new TestIme();
        service.editor=new EditorInfo();service.editor.inputType=1;
        service.editor.packageName="fixture";service.editor.fieldId=1;
        Method attach=ContextWrapper.class.getDeclaredMethod("attachBaseContext",Context.class);
        attach.setAccessible(true);attach.invoke(service,context);
        EditText preview=new EditText(context);preview.setText(text);preview.setSelection(preview.length());
        set(service,"speech",new FakeSpeech());
        set(service,"prefs",context.getSharedPreferences("ime-flow-"+UUID.randomUUID(),0));
        set(service,"preview",preview);set(service,"confirm",new TextView(context));
        ChineseInputEngine engine=new ChineseInputEngine() {
            public Snapshot snapshot(){return new Snapshot("",Collections.emptyList(),0,false,true,"");}
            public void input(char value){} public void backspace(){} public String select(int index){return null;}
            public void nextPage(){} public void previousPage(){} public void setLayout(Layout layout){}
            public void reset(){} public void close(){}
        };
        set(service,"keyboard",new KeyboardController(engine));
        InputSession session=(InputSession)field(service,"session");
        long g=session.begin("fixture","fixture",1,1).generation;
        session.startListening(g);session.startRecognizing(g);session.showPreview(g,text);
        set(service,"suspendedPreview",text);
        return service;
    }
    public void testSymbolsEditDraftWithoutWritingHost() throws Throwable {
        runTestOnUiThread(()-> {try {
            UniversalInputMethodService service=fixture("原文");
            call(service,"commitLiteral",new Class[]{String.class},"，");
            InputSession session=(InputSession)field(service,"session");
            assertEquals("原文，",session.preview());
            assertEquals(session.preview(),field(service,"suspendedPreview"));
            assertEquals(InputSession.State.PREVIEW,session.state());
            call(service,"editPreview",new Class[]{String.class,boolean.class},"",true);
            assertEquals("原文",session.preview());
        }catch(Exception e){throw new AssertionError(e);}});
    }
    public void testVoiceContinuesThenUpscreensThenKeepsHostSendAction() throws Throwable {
        final TestIme[] holder={null};final EditText[] host={null};final long[] generation={0};
        final int[] editorActions={0};
        runTestOnUiThread(()-> {try {
            TestIme service=(TestIme)fixture("");holder[0]=service;
            host[0]=new EditText(getInstrumentation().getTargetContext());
            service.editor.imeOptions=EditorInfo.IME_ACTION_SEND;
            InputConnection base=host[0].onCreateInputConnection(new EditorInfo());
            service.connection=new android.view.inputmethod.InputConnectionWrapper(base,false) {
                @Override public boolean performEditorAction(int actionCode) {
                    if (actionCode == EditorInfo.IME_ACTION_SEND) editorActions[0]++;
                    return true;
                }
            };
            service.onCreateInputView();
            InputSession session=(InputSession)field(service,"session");
            session.begin("fixture","fixture",1,1);set(service,"voiceMode",true);
            call(service,"render",new Class[0]);
            TextView voice=(TextView)field(service,"voice");
            TextView action=(TextView)field(service,"enterKey");
            assertEquals("语音",voice.getText().toString());
            assertEquals("发送",action.getText().toString());
            voice.performClick();
            FakeSpeech speech=(FakeSpeech)field(service,"speech");generation[0]=speech.generation;
            assertEquals(1,speech.starts);assertEquals("停止",action.getText().toString());
            action.performClick();assertEquals(1,speech.finishes);
        }catch(Exception e){throw new AssertionError(e);}});
        holder[0].onRecognizing(generation[0]);getInstrumentation().waitForIdleSync();
        runTestOnUiThread(()-> {try {
            TextView action=(TextView)field(holder[0],"enterKey");
            assertEquals("识别中",action.getText().toString());assertFalse(action.isEnabled());
        }catch(Exception e){throw new AssertionError(e);}});
        holder[0].onResult(generation[0],"第一段");getInstrumentation().waitForIdleSync();
        runTestOnUiThread(()-> {try {
            TextView voice=(TextView)field(holder[0],"voice");
            TextView action=(TextView)field(holder[0],"enterKey");
            assertEquals("续说",voice.getText().toString());
            assertEquals("上屏",action.getText().toString());assertTrue(action.isEnabled());
            action.performClick();
            assertEquals("",host[0].getText().toString());
            assertTrue(((TextView)field(holder[0],"status")).getText().toString().contains("检查文字"));
            voice.performClick();
            FakeSpeech speech=(FakeSpeech)field(holder[0],"speech");generation[0]=speech.generation;
            assertEquals(2,speech.starts);assertEquals("停止",action.getText().toString());
        }catch(Exception e){throw new AssertionError(e);}});
        holder[0].onRecognizing(generation[0]);getInstrumentation().waitForIdleSync();
        holder[0].onResult(generation[0],"第二段");getInstrumentation().waitForIdleSync();
        Thread.sleep(450);
        runTestOnUiThread(()-> {try {
            TextView action=(TextView)field(holder[0],"enterKey");
            assertEquals("上屏",action.getText().toString());
            action.performClick();
            assertEquals("第一段第二段",host[0].getText().toString());
            assertEquals("发送",action.getText().toString());
            action.performClick();
            assertEquals(1,editorActions[0]);
        }catch(Exception e){throw new AssertionError(e);}});
    }
    public void testLeftVoiceKeyStartsFromTextMode() throws Throwable {
        runTestOnUiThread(()-> {try {
            TestIme service=(TestIme)fixture("");service.onCreateInputView();
            InputSession session=(InputSession)field(service,"session");
            session.begin("fixture","fixture",1,1);
            call(service,"switchMode",new Class[]{KeyboardController.Mode.class},KeyboardController.Mode.ENGLISH);
            assertFalse((Boolean)field(service,"voiceMode"));
            ((TextView)field(service,"voice")).performClick();
            assertTrue((Boolean)field(service,"voiceMode"));
            assertEquals(1,((FakeSpeech)field(service,"speech")).starts);
            assertEquals("停止",((TextView)field(service,"enterKey")).getText().toString());
        }catch(Exception e){throw new AssertionError(e);}});
    }
    public void testT9ContinuousInputNeverLeaksLookupDigits() throws Throwable {
        runTestOnUiThread(()-> {try {
            TestIme service=(TestIme)fixture("");
            Context context=getInstrumentation().getTargetContext();
            EditText host=new EditText(context);
            service.connection=host.onCreateInputConnection(new EditorInfo());
            service.onCreateInputView();
            set(service,"editorBound",true);
            InputSession session=(InputSession)field(service,"session");
            session.begin("fixture","fixture",1,1);
            final StringBuilder raw=new StringBuilder();
            ChineseInputEngine noMatch=new ChineseInputEngine(){
                public Snapshot snapshot(){
                    String value=raw.toString();
                    return new Snapshot(value,value.isEmpty()?Collections.emptyList():Collections.singletonList(value),
                            0,false,true,"",value,value.length(),0,value.length(),
                            Collections.emptyList(),Collections.emptyList(),"",
                            Collections.emptyList(),-1,false,value);
                }
                public void input(char c){raw.append(c);}
                public void backspace(){if(raw.length()>0)raw.setLength(raw.length()-1);}
                public String select(int index){return null;}
                public void nextPage(){}public void previousPage(){}
                public void setLayout(Layout layout){}public void reset(){raw.setLength(0);}public void close(){}
            };
            set(service,"keyboard",new KeyboardController(noMatch));
            call(service,"switchMode",new Class[]{KeyboardController.Mode.class},KeyboardController.Mode.T9);
            for(int i=0;i<40;i++)call(service,"type",new Class[]{String.class},i%2==0?"2":"3");
            assertEquals("all physical keys reach the decoder",40,raw.length());
            assertEquals("private T9 digits never enter the host editor","",host.getText().toString());
            call(service,"backspace",new Class[0]);
            assertEquals(39,raw.length());
            call(service,"clearComposition",new Class[0]);
            assertEquals(0,raw.length());

            // Chinese 26-key is a real Rime composition mode rather than an English alias.
            final StringBuilder pinyin=new StringBuilder();
            ChineseInputEngine fullPinyin=new ChineseInputEngine(){
                public Snapshot snapshot(){
                    String value=pinyin.toString();
                    return new Snapshot(value,value.isEmpty()?Collections.emptyList():Collections.singletonList("你好"),
                            0,false,true,"",value,value.length(),0,value.length(),
                            Collections.emptyList(),Collections.emptyList(),"",
                            Collections.emptyList(),-1,false,value);
                }
                public void input(char c){pinyin.append(c);}
                public void backspace(){if(pinyin.length()>0)pinyin.setLength(pinyin.length()-1);}
                public String select(int index){if(index!=0)return null;pinyin.setLength(0);return "你好";}
                public void nextPage(){}public void previousPage(){}
                public void setLayout(Layout layout){}public void reset(){pinyin.setLength(0);}public void close(){}
            };
            set(service,"keyboard",new KeyboardController(fullPinyin));
            call(service,"switchMode",new Class[]{KeyboardController.Mode.class},KeyboardController.Mode.PINYIN);
            for(char c:"nihao".toCharArray())call(service,"type",new Class[]{String.class},String.valueOf(c));
            assertEquals("nihao",pinyin.toString());
            call(service,"space",new Class[0]);
            assertEquals("你好",host.getText().toString());
        }catch(Exception e){throw new AssertionError(e);}});
    }
    public void testVoiceDraftTypographyAndSegmentCap() throws Throwable {
        runTestOnUiThread(()-> {try {
            TestIme service=(TestIme)fixture("短文字");service.onCreateInputView();
            EditText preview=(EditText)field(service,"preview");
            call(service,"render",new Class[0]);
            float scale=preview.getResources().getDisplayMetrics().scaledDensity;
            assertEquals(18,Math.round(preview.getTextSize()/scale));assertEquals(5,preview.getMaxLines());
            StringBuilder medium=new StringBuilder();for(int i=0;i<80;i++)medium.append('中');
            preview.setText(medium.toString());call(service,"render",new Class[0]);
            assertEquals(16,Math.round(preview.getTextSize()/scale));
            StringBuilder maximum=new StringBuilder();for(int i=0;i<500;i++)maximum.append('长');
            preview.setText(maximum.toString());call(service,"render",new Class[0]);
            assertEquals(14,Math.round(preview.getTextSize()/scale));
            call(service,"toggleVoice",new Class[0]);
            assertEquals(0,((FakeSpeech)field(service,"speech")).starts);
            assertTrue(((TextView)field(service,"status")).getText().toString().contains("500"));
        }catch(Exception e){throw new AssertionError(e);}});
    }
    public void testEmptyDraftAndReverseSelectionStayEdited() throws Throwable {
        runTestOnUiThread(()-> {try {
            UniversalInputMethodService service=fixture("你好🙂");
            EditText preview=(EditText)field(service,"preview");
            preview.setSelection(preview.length(),0);
            call(service,"editPreview",new Class[]{String.class,boolean.class},"",true);
            assertEquals("",preview.getText().toString());
            assertEquals("",((InputSession)field(service,"session")).preview());
            assertEquals("",call(service,"visibleVoiceDraft",new Class[0]));
            call(service,"editPreview",new Class[]{String.class,boolean.class},"",true);
            assertEquals("",preview.getText().toString());
        }catch(Exception e){throw new AssertionError(e);}});
    }
    public void testCursorMovesByCodePointAndFailedCommitRetainsDraft() throws Throwable {
        runTestOnUiThread(()-> {try {
            UniversalInputMethodService service=fixture("字🙂");
            EditText preview=(EditText)field(service,"preview");
            call(service,"moveCursor",new Class[]{int.class},-1);
            assertEquals(1,preview.getSelectionStart());
            call(service,"confirmVoice",new Class[0]);
            InputSession session=(InputSession)field(service,"session");
            assertEquals(InputSession.State.PREVIEW,session.state());
            assertEquals("字🙂",session.preview());
            assertEquals(1,preview.getSelectionStart());
        }catch(Exception e){throw new AssertionError(e);}});
    }
    public void testKeyboardDraftSurvivesRealViewRecreation() throws Throwable {
        runTestOnUiThread(()-> {try {
            UniversalInputMethodService service=fixture("保留文字");
            service.onCreateInputView();
            call(service,"switchMode",new Class[]{KeyboardController.Mode.class},KeyboardController.Mode.ENGLISH);
            assertFalse((Boolean)field(service,"voiceMode"));
            assertEquals(android.view.View.VISIBLE,((android.view.View)field(service,"keys")).getVisibility());
            call(service,"type",new Class[]{String.class},"SHIFT");
            call(service,"type",new Class[]{String.class},"a");
            InputSession session=(InputSession)field(service,"session");
            assertEquals("保留文字A",session.preview());
            service.onCreateInputView();
            EditText preview=(EditText)field(service,"preview");
            assertEquals("保留文字A",preview.getText().toString());
            assertEquals(android.view.View.VISIBLE,((android.view.View)field(service,"keys")).getVisibility());
            preview.selectAll();
            call(service,"editPreview",new Class[]{String.class,boolean.class},"",true);
            service.onCreateInputView();
            assertEquals("",session.preview());
            assertEquals("",((EditText)field(service,"preview")).getText().toString());
        }catch(Exception e){throw new AssertionError(e);}});
    }
    public void testSensitiveRejectedTextNeverBecomesVisibleDraft() throws Throwable {
        runTestOnUiThread(()-> {try {
            UniversalInputMethodService service=fixture("");
            InputSession session=(InputSession)field(service,"session");
            session.begin("sensitive","fixture",2,129);
            set(service,"sensitiveField",true);
            assertEquals(false,call(service,"routeStable",new Class[]{String.class},"sensitive-fixture"));
            assertEquals("",session.preview());
            assertEquals(InputSession.State.IDLE,session.state());
        }catch(Exception e){throw new AssertionError(e);}});
    }
    public void testSuccessfulConfirmOnceAndRejectedRetry() throws Throwable {
        runTestOnUiThread(()-> {try {
            TestIme service=(TestIme)fixture("确认一次");service.onCreateInputView();
            EditText host=new EditText(getInstrumentation().getTargetContext());
            InputConnection good=host.onCreateInputConnection(new EditorInfo());
            service.connection=new android.view.inputmethod.InputConnectionWrapper(good,false) {
                public boolean commitText(CharSequence text,int cursor){return false;}
            };
            call(service,"confirmVoice",new Class[0]);
            InputSession session=(InputSession)field(service,"session");
            assertEquals("确认一次",session.preview());assertEquals("",host.getText().toString());
            service.connection=new android.view.inputmethod.InputConnectionWrapper(good,false) {
                public boolean commitText(CharSequence text,int cursor){throw new IllegalStateException("fixture refusal");}
            };
            call(service,"confirmVoice",new Class[0]);assertEquals("确认一次",session.preview());
            service.connection=good;
            call(service,"confirmVoice",new Class[0]);call(service,"confirmVoice",new Class[0]);
            assertEquals("确认一次",host.getText().toString());
            assertEquals("",((EditText)field(service,"preview")).getText().toString());
            assertEquals(InputSession.State.IDLE,session.state());
        }catch(Exception e){throw new AssertionError(e);}});
    }
    public void testDeleteHostAndTerminalUseCorrectTarget() throws Throwable {
        runTestOnUiThread(()-> {try {
            TestIme service=(TestIme)fixture("");
            InputSession session=(InputSession)field(service,"session");session.begin("field","fixture",1,1);
            EditText host=new EditText(getInstrumentation().getTargetContext());host.setText("字🙂");host.setSelection(host.length());
            service.connection=host.onCreateInputConnection(new EditorInfo());
            call(service,"backspace",new Class[0]);assertEquals("字",host.getText().toString());
            host.selectAll();call(service,"backspace",new Class[0]);assertEquals("",host.getText().toString());
            final int[] down={0},up={0},surrounding={0};
            service.connection=new android.view.inputmethod.BaseInputConnection(host,false) {
                public boolean sendKeyEvent(android.view.KeyEvent event) {
                    if(event.getKeyCode()==android.view.KeyEvent.KEYCODE_DEL) {
                        if(event.getAction()==0)down[0]++;else up[0]++;
                    }return true;
                }
                public boolean deleteSurroundingText(int before,int after){surrounding[0]++;return true;}
                public boolean deleteSurroundingTextInCodePoints(int before,int after){surrounding[0]++;return true;}
            };
            service.editor.packageName="com.termux";
            call(service,"backspace",new Class[0]);
            assertEquals(1,down[0]);assertEquals(1,up[0]);assertEquals(0,surrounding[0]);
        }catch(Exception e){throw new AssertionError(e);}});
    }
    public void testRemoteDraftEditingNeverTouchesPendingOrHost() throws Throwable {
        runTestOnUiThread(()-> {try {
            TestIme service=(TestIme)fixture("待确认");service.onCreateInputView();
            EditText host=new EditText(getInstrumentation().getTargetContext());service.connection=host.onCreateInputConnection(new EditorInfo());
            OutputTargetController target=(OutputTargetController)field(service,"targets");
            target.onStartInput("fixture",false,false);target.setPairing("test-device","测试电脑");
            assertEquals(OutputTargetController.ToggleResult.OK,target.toggleTarget());target.commitStable("已有");
            call(service,"moveCursor",new Class[]{int.class},-1);
            call(service,"editPreview",new Class[]{String.class,boolean.class},"修改",false);
            assertEquals("已有",target.pendingText());assertEquals("",host.getText().toString());
            call(service,"confirmVoice",new Class[0]);
            assertEquals("已有待确修改认",target.pendingText());assertEquals("",host.getText().toString());
            assertFalse(target.sending());
        }catch(Exception e){throw new AssertionError(e);}});
    }
    public void testFeatureReturnAndNewFieldDoNotCommitDraft() throws Throwable {
        runTestOnUiThread(()-> {try {
            TestIme service=(TestIme)fixture("保存草稿");service.onCreateInputView();
            EditText host=new EditText(getInstrumentation().getTargetContext());service.connection=host.onCreateInputConnection(new EditorInfo());
            call(service,"toggleFeaturePanel",new Class[0]);call(service,"toggleFeaturePanel",new Class[0]);
            assertEquals("保存草稿",((InputSession)field(service,"session")).preview());
            assertEquals("",host.getText().toString());
            EditorInfo same=new EditorInfo();same.inputType=1;same.packageName="fixture";same.fieldId=1;
            InputSession session=(InputSession)field(service,"session");
            session.begin("fixture|1|null|1","fixture",1,1);session.retainRejectedCommit(session.generation(),"保存草稿");
            ((EditText)field(service,"preview")).setText("保存草稿");
            service.onStartInput(same,true);assertEquals("保存草稿",session.preview());
            EditorInfo next=new EditorInfo();next.inputType=1;next.packageName="other.fixture";next.fieldId=2;
            service.editor=next;service.onStartInput(next,false);
            assertEquals("",session.preview());assertEquals("",((EditText)field(service,"preview")).getText().toString());
            assertTrue((Boolean)field(service,"voiceMode"));assertEquals("",host.getText().toString());
        }catch(Exception e){throw new AssertionError(e);}});
    }
    public void testSameEditorRestartPreservesT9ButNewFieldReturnsToVoice() throws Throwable {
        runTestOnUiThread(()-> {try {
            TestIme service=(TestIme)fixture("");service.onCreateInputView();
            EditorInfo same=new EditorInfo();same.inputType=1;same.packageName="fixture";same.fieldId=1;
            service.editor=same;
            InputSession session=(InputSession)field(service,"session");
            session.begin("fixture|1|null|1","fixture",1,1);
            call(service,"switchMode",new Class[]{KeyboardController.Mode.class},KeyboardController.Mode.T9);
            assertFalse((Boolean)field(service,"voiceMode"));
            service.onStartInput(same,true);
            assertFalse("same editor restart must not force voice",(Boolean)field(service,"voiceMode"));
            assertEquals(KeyboardController.Mode.T9,((KeyboardController)field(service,"keyboard")).mode());
            EditorInfo next=new EditorInfo();next.inputType=1;next.packageName="other.fixture";next.fieldId=2;
            service.editor=next;service.onStartInput(next,false);
            assertTrue((Boolean)field(service,"voiceMode"));
        }catch(Exception e){throw new AssertionError(e);}});
    }
    public void testPartialHideAndLateCallbacksCannotEraseDraft() throws Throwable {
        final TestIme[] holder={null};final long[] old={0};
        runTestOnUiThread(()-> {try {
            TestIme service=(TestIme)fixture("原稿");holder[0]=service;service.onCreateInputView();
            InputSession session=(InputSession)field(service,"session");
            session.begin("field","fixture",1,1);session.startListening(session.generation());old[0]=session.generation();
            set(service,"suspendedPreview","已有临时识别");((EditText)field(service,"preview")).setText("已有临时识别");
            service.onFinishInputView(false);
            assertEquals("已有临时识别",session.preview());assertTrue(session.generation()!=old[0]);
            assertEquals(InputSession.State.PREVIEW,session.state());
        }catch(Exception e){throw new AssertionError(e);}});
        holder[0].onPartial(old[0],"迟到");holder[0].onResult(old[0],"迟到");holder[0].onError(old[0],"迟到错误");
        getInstrumentation().waitForIdleSync();
        runTestOnUiThread(()-> {try {
            assertEquals("已有临时识别",((InputSession)field(holder[0],"session")).preview());
        }catch(Exception e){throw new AssertionError(e);}});
    }
    public void testContinuedSpeechInsertsAtDraftCursor() throws Throwable {
        final TestIme[] holder={null};final long[] generation={0};
        runTestOnUiThread(()-> {try {
            TestIme service=(TestIme)fixture("前后");holder[0]=service;service.onCreateInputView();
            ((EditText)field(service,"preview")).setSelection(1);
            call(service,"toggleVoice",new Class[0]);generation[0]=((FakeSpeech)field(service,"speech")).generation;
            assertEquals(1,((FakeSpeech)field(service,"speech")).starts);
        }catch(Exception e){throw new AssertionError(e);}});
        holder[0].onResult(generation[0],"中间");getInstrumentation().waitForIdleSync();
        runTestOnUiThread(()-> {try {
            assertEquals("前中间后",((InputSession)field(holder[0],"session")).preview());
            call(holder[0],"cancelVoice",new Class[0]);
            assertEquals("",((InputSession)field(holder[0],"session")).preview());
        }catch(Exception e){throw new AssertionError(e);}});
    }
    public void testHoldDeleteStopsOnRelease() throws Throwable {
        final TestIme[] holder={null};
        runTestOnUiThread(()-> {try {
            TestIme service=(TestIme)fixture("1234567890");holder[0]=service;service.onCreateInputView();
            TextView key=(TextView)field(service,"deleteKey");
            android.view.MotionEvent down=android.view.MotionEvent.obtain(0,0,0,5,5,0);key.dispatchTouchEvent(down);down.recycle();
        }catch(Exception e){throw new AssertionError(e);}});
        Thread.sleep(700);getInstrumentation().waitForIdleSync();
        final int[] length={0};
        runTestOnUiThread(()-> {try {
            TextView key=(TextView)field(holder[0],"deleteKey");
            android.view.MotionEvent up=android.view.MotionEvent.obtain(0,700,1,5,5,0);key.dispatchTouchEvent(up);up.recycle();
            length[0]=((InputSession)field(holder[0],"session")).preview().length();assertTrue(length[0]<10);
        }catch(Exception e){throw new AssertionError(e);}});
        Thread.sleep(200);getInstrumentation().waitForIdleSync();
        runTestOnUiThread(()-> {try {assertEquals(length[0],((InputSession)field(holder[0],"session")).preview().length());}
            catch(Exception e){throw new AssertionError(e);}});
    }
    public void testRealT9CandidateEditsVoiceDraftWithoutTouchingHost() throws Throwable {
        final RimeChineseInputEngine[] engine={null};
        try {runTestOnUiThread(()-> {try {
            TestIme service=(TestIme)fixture("前后");
            Context original=getInstrumentation().getTargetContext();
            File dir=new File(original.getCacheDir(),"rime-mixed-"+UUID.randomUUID());assertTrue(dir.mkdirs());
            engine[0]=new RimeChineseInputEngine(new ContextWrapper(original){public File getFilesDir(){return dir;}});
            assertTrue(engine[0].snapshot().error,engine[0].snapshot().available);
            engine[0].setLearningEnabled(false);
            set(service,"keyboard",new KeyboardController(engine[0]));
            final int[] writes={0};
            service.connection=new android.view.inputmethod.BaseInputConnection(new EditText(original),false){
                public boolean commitText(CharSequence value,int cursor){writes[0]++;return true;}
                public boolean setComposingText(CharSequence value,int cursor){writes[0]++;return true;}
            };
            service.onCreateInputView();
            call(service,"switchMode",new Class[]{KeyboardController.Mode.class},KeyboardController.Mode.T9);
            EditText preview=(EditText)field(service,"preview");preview.setSelection(1);
            for(char key:"64426".toCharArray())call(service,"type",new Class[]{String.class},String.valueOf(key));
            call(service,"selectSyllable",new Class[]{String.class},"ni");
            call(service,"selectSyllable",new Class[]{String.class},"hao");
            assertEquals("前后",preview.getText().toString());
            assertEquals(0,writes[0]);
            call(service,"confirmVoice",new Class[0]);
            assertEquals(0,writes[0]); // unresolved phonetics cannot accidentally commit the draft
            int candidate=engine[0].snapshot().candidates.indexOf("你好");assertTrue(candidate>=0);
            call(service,"selectCandidate",new Class[]{int.class},candidate);
            assertEquals("前你好后",((InputSession)field(service,"session")).preview());
            assertEquals(0,writes[0]);
            call(service,"confirmVoice",new Class[0]);assertEquals(1,writes[0]);
        }catch(Exception e){throw new AssertionError(e);}});}finally{if(engine[0]!=null)engine[0].close();}
    }
    public void testEmptyListeningHideAndRetryRemainUsable() throws Throwable {
        final UniversalInputMethodService[] holder={null};final long[] old={0};
        runTestOnUiThread(()-> {try {
            UniversalInputMethodService service=fixture("");holder[0]=service;service.onCreateInputView();
            call(service,"toggleVoice",new Class[0]);
            old[0]=((InputSession)field(service,"session")).generation();
            service.onFinishInputView(false);
            assertEquals(InputSession.State.IDLE,((InputSession)field(service,"session")).state());
            service.onResult(old[0],"obsolete-result");
        }catch(Exception e){throw new AssertionError(e);}});
        getInstrumentation().waitForIdleSync();
        runTestOnUiThread(()-> {try {
            UniversalInputMethodService service=holder[0];
            assertEquals("",((InputSession)field(service,"session")).preview());
            call(service,"toggleVoice",new Class[0]);
            assertEquals(2,((FakeSpeech)field(service,"speech")).starts);
            call(service,"cancelVoice",new Class[0]);
        }catch(Exception e){throw new AssertionError(e);}});
    }
    public void testLateEngineRespectsExistingPrivateEditor() throws Throwable {
        runTestOnUiThread(()-> {try {
            TestIme service=(TestIme)fixture("");service.onCreateInputView();
            service.editor.imeOptions=EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING;
            service.onStartInput(service.editor,false);
            final boolean[] learning={true};
            ChineseInputEngine ready=new ChineseInputEngine(){
                public Snapshot snapshot(){return new Snapshot("",Collections.emptyList(),0,false,true,"");}
                public void input(char c){} public void backspace(){} public String select(int i){return null;}
                public void nextPage(){} public void previousPage(){} public void setLayout(Layout l){}
                public void setLearningEnabled(boolean enabled){learning[0]=enabled;}
                public void reset(){} public void close(){}
            };
            call(service,"installReadyKeyboard",new Class[]{ChineseInputEngine.class},ready);
            assertFalse("async engine must inherit the active field policy",learning[0]);
        }catch(Exception e){throw new AssertionError(e);}});
    }
    public void testOldRecorderProgressCannotStopNewSegment() throws Throwable {
        final OfflineSpeechRecognizer[] holder={null};
        runTestOnUiThread(()-> {try {
            OfflineSpeechRecognizer recognizer=new OfflineSpeechRecognizer(getInstrumentation().getTargetContext());holder[0]=recognizer;
            Field g=OfflineSpeechRecognizer.class.getDeclaredField("generation");g.setAccessible(true);g.setLong(recognizer,20);
            Field valid=OfflineSpeechRecognizer.class.getDeclaredField("valid");valid.setAccessible(true);valid.setBoolean(recognizer,true);
            recognizer.onProgress(19,16000L*SpeechEndpointDetector.MAX_SESSION_MS/1000);
        }catch(Exception e){throw new AssertionError(e);}});
        getInstrumentation().waitForIdleSync();
        try {runTestOnUiThread(()-> {try {
            Field finishing=OfflineSpeechRecognizer.class.getDeclaredField("finishing");finishing.setAccessible(true);
            assertFalse(finishing.getBoolean(holder[0]));
            holder[0].onProgress(20,16000L*SpeechEndpointDetector.MAX_SESSION_MS/1000);
        }catch(Exception e){throw new AssertionError(e);}});
        getInstrumentation().waitForIdleSync();
        runTestOnUiThread(()-> {try {
            Field finishing=OfflineSpeechRecognizer.class.getDeclaredField("finishing");finishing.setAccessible(true);
            assertTrue(finishing.getBoolean(holder[0]));
        }catch(Exception e){throw new AssertionError(e);}});
        }finally{runTestOnUiThread(()->holder[0].close());}
    }
    public void testRealPackagedRimeSyllableConstraint() throws Exception {
        Context original=getInstrumentation().getTargetContext();
        File dir=new File(original.getCacheDir(),"rime-fixture-"+UUID.randomUUID());
        assertTrue(dir.mkdirs());
        Context isolated=new ContextWrapper(original) {
            public File getFilesDir(){return dir;}
        };
        RimeChineseInputEngine engine=new RimeChineseInputEngine(isolated);
        try {
            assertTrue(engine.snapshot().error,engine.snapshot().available);
            engine.setLearningEnabled(false);
            engine.setLayout(ChineseInputEngine.Layout.T9);
            for(char c:"64426".toCharArray())engine.input(c);
            ChineseInputEngine.Snapshot all=engine.snapshot();
            assertTrue(all.syllables.toString(),all.syllables.contains("ni"));
            assertTrue(all.syllables.contains("mi"));
            assertTrue(engine.chooseSyllable("mi"));
            ChineseInputEngine.Snapshot mi=engine.snapshot();
            assertEquals("mi",mi.selectedSyllable);
            assertTrue(mi.candidates.toString(),mi.candidates.contains("蜜柑"));
            assertFalse(mi.candidates.contains("你好"));
            assertTrue(engine.chooseSyllable(""));
            assertEquals("64426",engine.snapshot().rawInput);
            assertTrue(engine.chooseSyllable("ni"));
            ChineseInputEngine.Snapshot ni=engine.snapshot();
            assertTrue(ni.candidates.toString(),ni.candidates.contains("你好"));
            assertTrue(engine.chooseSyllable("hao"));
            assertEquals(2,engine.snapshot().syllableSegments.size());
            assertTrue(engine.activateSyllable(0));
            assertTrue(engine.chooseSyllable("ni"));
            assertTrue(engine.chooseSyllable("hao"));
            assertEquals("你好",engine.select(ni.candidates.indexOf("你好")));
            assertEquals("",engine.snapshot().rawInput);
            assertNull(engine.select(99));
            engine.setLayout(ChineseInputEngine.Layout.FULL_PINYIN);
            for(char c:"nihao".toCharArray())engine.input(c);
            assertTrue(engine.snapshot().candidates.contains("你好"));
        } finally {engine.close();}
    }
    public void testRealPackagedRimeT9ContinuousInputAndRapidRecovery() throws Exception {
        Context original=getInstrumentation().getTargetContext();
        File dir=new File(original.getCacheDir(),"rime-stress-"+UUID.randomUUID());
        assertTrue(dir.mkdirs());
        Context isolated=new ContextWrapper(original){public File getFilesDir(){return dir;}};
        RimeChineseInputEngine engine=new RimeChineseInputEngine(isolated);
        try {
            assertTrue(engine.snapshot().error,engine.snapshot().available);
            engine.setLearningEnabled(false);
            KeyboardController controller=new KeyboardController(engine);
            controller.setMode(KeyboardController.Mode.T9);
            String sequence="64426746542644266442674654264426";
            long started=android.os.SystemClock.elapsedRealtime();
            for(char c:sequence.toCharArray())controller.type(String.valueOf(c));
            long elapsed=android.os.SystemClock.elapsedRealtime()-started;
            assertEquals(sequence,controller.snapshot().numericInput);
            controller.backspace();
            assertEquals(sequence.substring(0,sequence.length()-1),controller.snapshot().numericInput);
            controller.reset();
            for(char c:"64426".toCharArray())controller.type(String.valueOf(c));
            assertTrue(engine.chooseSyllable("ni"));
            controller.backspace();
            assertEquals("6442",controller.snapshot().numericInput);
            assertEquals("ni42",controller.snapshot().rawInput.replace("'",""));
            controller.reset();
            for(int i=0;i<60;i++){controller.type("6");controller.backspace();}
            assertEquals("",controller.snapshot().numericInput);
            assertTrue("continuous T9 processing took "+elapsed+"ms",elapsed<30000);
        }finally{engine.close();}
    }
    public void testRealEditorToSqliteAndReadback() throws Throwable {
        Context original=getInstrumentation().getTargetContext();
        File dir=new File(original.getCacheDir(),"history-fixture-"+UUID.randomUUID());
        assertTrue(dir.mkdirs());
        File dbPath=new File(dir,"input-history.db");
        Context isolated=new ContextWrapper(original) {
            public File getDatabasePath(String name){return dbPath;}
            public SQLiteDatabase openOrCreateDatabase(String name,int mode,SQLiteDatabase.CursorFactory factory) {
                return SQLiteDatabase.openOrCreateDatabase(dbPath,factory);
            }
            public SQLiteDatabase openOrCreateDatabase(String name,int mode,SQLiteDatabase.CursorFactory factory,DatabaseErrorHandler handler) {
                return SQLiteDatabase.openOrCreateDatabase(dbPath.getPath(),factory,handler);
            }
        };
        InputHistoryStore store=new InputHistoryStore(isolated);
        try {
            runTestOnUiThread(()-> {
                EditText editor=new EditText(original);
                InputConnection connection=editor.onCreateInputConnection(new EditorInfo());
                for(int i=0;i<2;i++) {
                    String id=UUID.randomUUID().toString();
                    InputCommitCoordinator.Result result=InputCommitCoordinator.commit("记录测试",false,
                        text->connection.commitText(text,1),
                        text->store.record("fixture","ime_voice","本机","inserted",id,0,text));
                    assertTrue(result.accepted);assertTrue(result.historySaved);
                }
                assertEquals("记录测试记录测试",editor.getText().toString());
                InputCommitCoordinator.Result sensitive=InputCommitCoordinator.commit("敏感测试",true,
                    text->connection.commitText(text,1),text->{throw new AssertionError("Sensitive text reached history");});
                assertTrue(sensitive.accepted);assertFalse(sensitive.historySaved);
                assertFalse(InputCommitCoordinator.commit("拒绝测试",false,text->false,
                    text->{throw new AssertionError("Rejected text reached history");}).accepted);
            });
            try(Cursor cursor=store.search("记录测试",0,Long.MAX_VALUE,Long.MAX_VALUE,50)) {
                assertEquals(2,cursor.getCount());
            }
            store.close();
            InputHistoryStore reopened=new InputHistoryStore(isolated);
            try(Cursor cursor=reopened.search("记录测试",0,Long.MAX_VALUE,Long.MAX_VALUE,50)) {
                assertEquals(2,cursor.getCount());
            } finally {reopened.close();}
        } finally {store.close();SQLiteDatabase.deleteDatabase(dbPath);dir.delete();}
    }
}
