package io.github.amxooo.voiceinput2pc;
import android.test.InstrumentationTestCase;
/** Must execute in the APK's own UID; no Termux shell, model or service needed. */
public final class RimeChineseInputEngineTest extends InstrumentationTestCase {
    public void testRealPinyinAndT9CandidatesAndCommit() throws Exception {
        RimeChineseInputEngine engine = new RimeChineseInputEngine(getInstrumentation().getTargetContext());
        try {
            assertTrue(engine.snapshot().error, engine.snapshot().available);
            for (char c : "nihao".toCharArray()) engine.input(c);
            ChineseInputEngine.Snapshot full = engine.snapshot();
            assertTrue(full.candidates.toString(), full.candidates.contains("你好"));
            assertEquals("你好", engine.select(full.candidates.indexOf("你好")));
            assertTrue(engine.snapshot().rawInput.isEmpty());
            engine.setLayout(ChineseInputEngine.Layout.T9);
            for (char c : "64426".toCharArray()) engine.input(c);
            ChineseInputEngine.Snapshot nine = engine.snapshot();
            assertEquals("64426", nine.rawInput);
            assertTrue(nine.candidates.toString(), nine.candidates.contains("你好"));
            assertEquals("你好", engine.select(nine.candidates.indexOf("你好")));
            assertNull(engine.select(999));
            engine.setLearningEnabled(false);
            for (char c : "64426".toCharArray()) engine.input(c);
            assertTrue(engine.snapshot().candidates.contains("你好"));
            engine.reset(); assertTrue(engine.snapshot().rawInput.isEmpty());
            engine.input('6'); engine.backspace(); assertTrue(engine.snapshot().rawInput.isEmpty());
        } finally { engine.close(); }
        assertFalse(engine.snapshot().available);
    }

    public void testT9TransientDecoderDriftSelfHealsWithoutReset() throws Exception {
        RimeChineseInputEngine engine=new RimeChineseInputEngine(getInstrumentation().getTargetContext());
        try {
            engine.setLearningEnabled(false);engine.setLayout(ChineseInputEngine.Layout.T9);
            engine.input('6');engine.input('4');
            assertEquals("64",engine.snapshot().numericInput);
            java.lang.reflect.Field sessionField=RimeChineseInputEngine.class.getDeclaredField("session");
            sessionField.setAccessible(true);long session=sessionField.getLong(engine);
            java.lang.reflect.Method setInput=RimeChineseInputEngine.class.getDeclaredMethod("nativeSetInput",long.class,String.class);
            setInput.setAccessible(true);assertTrue((Boolean)setInput.invoke(null,session,"2"));
            // The snapshot observes the drift. The next digit must repair through
            // authoritative set_input instead of leaking a native numeric key.
            engine.snapshot();engine.input('4');
            ChineseInputEngine.Snapshot repaired=engine.snapshot();
            assertEquals("644",repaired.numericInput);
            assertEquals("644",repaired.rawInput.replace("'",""));
            assertFalse(repaired.candidates.isEmpty());
        } finally {engine.close();}
    }
}
