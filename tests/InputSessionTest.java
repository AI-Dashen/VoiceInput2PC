package io.github.amxooo.voiceinput2pc;

import java.util.Objects;

public final class InputSessionTest {
    private static int checks;
    private static void equal(Object expected, Object actual, String label) {
        checks++;
        if (!Objects.equals(expected, actual)) throw new AssertionError(label + ": " + actual);
    }
    private static void check(boolean value, String label) { equal(true, value, label); }

    private static void staleCallbacksCannotCrossFields() {
        InputSession session = new InputSession();
        long old = session.begin("android-current-field", "app.a", 1, 1).generation;
        check(session.startListening(old), "first field starts recording");
        long current = session.begin("android-current-field", "app.b", 2, 1).generation;
        equal(false, session.startRecognizing(old), "old recorder cannot enter recognizing");
        equal(false, session.showPreview(old, "旧文字"), "old ASR cannot preview in new field");
        equal(null, session.commit(old), "old ASR cannot commit in new field");
        check(current != old, "field switch changes generation");
    }

    private static void previewNeedsExplicitCommit() {
        InputSession session = new InputSession();
        long generation = session.begin("android-current-field", "app", 1, 1).generation;
        check(session.startListening(generation), "listen");
        check(session.startRecognizing(generation), "recognize");
        check(session.showPreview(generation, "明天下午三点开会"), "preview");
        equal(InputSession.State.PREVIEW, session.state(), "preview is not committed");
        equal("明天下午三点开会", session.commit(generation), "explicit completion commits");
        equal(InputSession.State.COMMITTED, session.state(), "committed state");
        equal(null, session.commit(generation), "same preview cannot commit twice");
    }

    private static void cancelAndInvalidateAreTerminalForOldWork() {
        InputSession session = new InputSession();
        long generation = session.begin("android-current-field", "app", 1, 1).generation;
        check(session.startListening(generation), "listen before cancel");
        check(session.cancel(generation), "cancel accepted");
        equal("", session.preview(), "cancel clears preview");
        long invalid = session.invalidate();
        check(invalid != generation, "invalidate advances generation");
        equal(false, session.startRecognizing(generation), "cancelled worker stays stale");
    }

    private static void compositionNeverMixesWithAudio() {
        InputSession session = new InputSession();
        long generation = session.begin("android-current-field", "app", 1, 1).generation;
        check(session.compose(generation, "nihao"), "compose pinyin");
        equal("nihao", session.commit(generation), "candidate confirmation path commits once");
        session.begin("android-current-field", "app", 1, 1);
        generation = session.generation();
        check(session.startListening(generation), "audio starts");
        equal(false, session.compose(generation, "bad"), "typing cannot mutate active recording session");
    }

    public static void main(String[] args) {
        staleCallbacksCannotCrossFields();
        previewNeedsExplicitCommit();
        cancelAndInvalidateAreTerminalForOldWork();
        compositionNeverMixesWithAudio();
        System.out.println(checks + " input session checks passed");
    }
}
