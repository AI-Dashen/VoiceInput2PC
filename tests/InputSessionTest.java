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


    private static void previewCanResumeWithoutCommitting() {
        InputSession session = new InputSession();
        long first = session.begin("android-current-field", "app", 1, 1).generation;
        check(session.startListening(first), "first segment listens");
        check(session.startRecognizing(first), "first segment recognizes");
        check(session.showPreview(first, "第一段"), "first segment preview");
        check(session.updatePreview(first, "第一段已修改"), "edited preview retained");
        InputSession.Identity resumed = session.resumeListening(first);
        check(resumed != null, "preview resumes recording");
        check(resumed.generation != first, "resume advances generation");
        equal("第一段已修改", session.preview(), "resume retains editable draft");
        equal(false, session.showPreview(first, "迟到结果"), "old segment callback is stale");
        check(session.startRecognizing(resumed.generation), "second segment recognizes");
        check(session.showPreview(resumed.generation, "第一段已修改第二段"), "combined preview accepted");
        equal("第一段已修改第二段", session.commit(resumed.generation), "all segments commit once");
    }

    private static void failedContinuationRestoresPriorDraft() {
        InputSession session = new InputSession();
        long first = session.begin("android-current-field", "app", 1, 1).generation;
        session.startListening(first); session.startRecognizing(first); session.showPreview(first, "保留文字");
        InputSession.Identity resumed = session.resumeListening(first);
        check(session.restorePreview(resumed.generation, "保留文字"), "failed segment restores draft");
        equal(InputSession.State.PREVIEW, session.state(), "restored draft is editable");
        equal("保留文字", session.preview(), "prior draft survives failure");
    }

    public static void main(String[] args) {
        staleCallbacksCannotCrossFields();
        previewNeedsExplicitCommit();
        cancelAndInvalidateAreTerminalForOldWork();
        compositionNeverMixesWithAudio();
        previewCanResumeWithoutCommitting();
        failedContinuationRestoresPriorDraft();
        System.out.println(checks + " input session checks passed");
    }
}
