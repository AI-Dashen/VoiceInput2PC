package io.github.amxooo.voiceinput2pc;

import android.content.Context;
import android.content.ContextWrapper;
import android.test.InstrumentationTestCase;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputConnectionWrapper;
import android.widget.EditText;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.UUID;

/** Detached production IME and own EditText only: no foreground app, mic or system binding. */
public final class EditorReadinessIntegrationTest extends InstrumentationTestCase {
    private static final class Ime extends UniversalInputMethodService {
        InputConnection connection;
        EditorInfo editor;
        EditText host;
        int cursorRequests, cursorRequestFlags, actions;
        boolean throwCursorRequest;
        @Override public InputConnection getCurrentInputConnection() { return connection; }
        @Override public EditorInfo getCurrentInputEditorInfo() { return editor; }
    }

    private static Object get(Object object, String name) throws Exception {
        Field field=UniversalInputMethodService.class.getDeclaredField(name);
        field.setAccessible(true);return field.get(object);
    }
    private static void put(Object object, String name, Object value) throws Exception {
        Field field=UniversalInputMethodService.class.getDeclaredField(name);
        field.setAccessible(true);field.set(object,value);
    }
    private static Object call(Ime ime, String name) throws Exception {
        Method method=UniversalInputMethodService.class.getDeclaredMethod(name);
        method.setAccessible(true);return method.invoke(ime);
    }
    private interface Checked { void run() throws Exception; }
    private void ui(Checked action) throws Throwable {
        runTestOnUiThread(()->{try {action.run();} catch(Exception error) {throw new AssertionError(error);}});
    }

    private Ime fixture() throws Exception {
        Context context=getInstrumentation().getTargetContext();Ime ime=new Ime();
        Method attach=ContextWrapper.class.getDeclaredMethod("attachBaseContext",Context.class);
        attach.setAccessible(true);attach.invoke(ime,context);
        ime.editor=new EditorInfo();ime.editor.packageName="readiness.fixture";
        ime.editor.fieldId=51;ime.editor.inputType=1;ime.editor.imeOptions=EditorInfo.IME_ACTION_SEND;
        ime.editor.initialSelStart=-1;ime.editor.initialSelEnd=-1;
        ime.host=new EditText(context);
        ime.connection=new InputConnectionWrapper(ime.host.onCreateInputConnection(new EditorInfo()),false) {
            @Override public boolean performEditorAction(int action) { ime.actions++;return true; }
            @Override public boolean requestCursorUpdates(int flags) {
                ime.cursorRequests++;ime.cursorRequestFlags=flags;
                if(ime.throwCursorRequest)throw new UnsupportedOperationException("fixture cursor monitor unavailable");
                return false;
            }
        };
        put(ime,"prefs",context.getSharedPreferences("readiness-fixture-"+UUID.randomUUID(),0));
        put(ime,"speech",new SpeechInput() {
            public void start(long generation,OfflineSpeechRecognizer.Listener listener) {
                throw new AssertionError("Readiness checks must not start recording");
            }
            public void finish() {} public void cancel() {} public void close() {}
        });
        put(ime,"keyboard",new KeyboardController(new ChineseInputEngine() {
            public Snapshot snapshot() { return new Snapshot("",Collections.emptyList(),0,false,true,""); }
            public void input(char value) {} public void backspace() {} public String select(int index) { return null; }
            public void nextPage() {} public void previousPage() {} public void setLayout(Layout layout) {}
            public void reset() {} public void close() {}
        }));
        ime.onCreateInputView();ime.onStartInput(ime.editor,false);return ime;
    }

    private void assertEditingControls(Ime ime, boolean enabled) throws Exception {
        for(String field:new String[]{"dockDelete","dockAction"}) {
            View button=(View)get(ime,field);
            assertEquals(field+" remains visible",View.VISIBLE,button.getVisibility());
            assertEquals(field+" enabled",enabled,button.isEnabled());
        }
        assertEquals(0,ime.actions);
    }
    private void assertState(Ime ime, EditorReadinessPolicy.State state) throws Exception {
        assertEquals(state,call(ime,"editorReadiness"));
        assertTrue(((String)call(ime,"editingStateSummary")).startsWith("editor="+state+","));
    }

    public void testDefaultConnectionEnablesAllThreeControlsWithoutRecording() throws Throwable {
        ui(()->{Ime ime=fixture();assertState(ime,EditorReadinessPolicy.State.READY);
            assertEditingControls(ime,true);View mic=(View)get(ime,"micIcon");
            assertEquals(View.VISIBLE,mic.getVisibility());assertTrue(mic.isEnabled());
            assertTrue(ime.cursorRequests>0);
            assertEquals(InputConnection.CURSOR_UPDATE_IMMEDIATE|InputConnection.CURSOR_UPDATE_MONITOR,ime.cursorRequestFlags);
        });
    }
    public void testConnectionLossDisablesVisibleActionsAndRecoveryEnablesThem() throws Throwable {
        ui(()->{Ime ime=fixture();InputConnection original=ime.connection;
            ime.connection=null;call(ime,"refreshEditorControls");
            assertState(ime,EditorReadinessPolicy.State.DISCONNECTED);assertEditingControls(ime,false);
            ime.connection=original;call(ime,"refreshEditorControls");
            assertState(ime,EditorReadinessPolicy.State.READY);assertEditingControls(ime,true);
        });
    }
    public void testChangedFieldStaysDisabledUntilStartInputBindsNewIdentity() throws Throwable {
        ui(()->{Ime ime=fixture();ime.editor.fieldId=52;call(ime,"refreshEditorControls");
            assertState(ime,EditorReadinessPolicy.State.STALE_EDITOR);assertEditingControls(ime,false);
            ime.onStartInput(ime.editor,false);
            assertState(ime,EditorReadinessPolicy.State.READY);assertEditingControls(ime,true);
        });
    }
    public void testUnknownSelectionAndUnsupportedCursorMonitorRemainReady() throws Throwable {
        ui(()->{Ime ime=fixture();assertEquals(-1,((Integer)get(ime,"lastSelectionStart")).intValue());
            assertEquals(-1,((Integer)get(ime,"lastSelectionEnd")).intValue());
            assertFalse((Boolean)get(ime,"cursorUpdateAccepted"));assertFalse((Boolean)get(ime,"cursorAnchorReceived"));
            assertState(ime,EditorReadinessPolicy.State.READY);assertEditingControls(ime,true);
            ime.host.setText("fixture text must not enter diagnostic summary");
            assertEquals("editor=READY,selection=UNKNOWN,cursor=UNKNOWN,cursor_updates=UNAVAILABLE",call(ime,"editingStateSummary"));
        });
    }
    public void testThrowingCursorMonitorDoesNotDisableConnectedEditor() throws Throwable {
        ui(()->{Ime ime=fixture();ime.throwCursorRequest=true;call(ime,"requestEditorCursorUpdates");
            call(ime,"refreshEditorControls");assertFalse((Boolean)get(ime,"cursorUpdateAccepted"));
            assertState(ime,EditorReadinessPolicy.State.READY);assertEditingControls(ime,true);
        });
    }
    public void testUnbindDisablesActionsEvenWhileFixtureConnectionStillExists() throws Throwable {
        ui(()->{Ime ime=fixture();ime.onUnbindInput();assertNotNull(ime.connection);
            assertState(ime,EditorReadinessPolicy.State.NO_EDITOR);assertEditingControls(ime,false);
            assertFalse((Boolean)get(ime,"editorBound"));assertFalse((Boolean)get(ime,"cursorAnchorReceived"));
            assertFalse((Boolean)get(ime,"cursorUpdateAccepted"));
            ime.onStartInput(ime.editor,false);assertState(ime,EditorReadinessPolicy.State.READY);assertEditingControls(ime,true);
        });
    }
}
