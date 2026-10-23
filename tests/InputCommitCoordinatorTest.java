package io.github.amxooo.voiceinput2pc;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class InputCommitCoordinatorTest {
    private static int checks;
    private static void equal(Object expected, Object actual, String label) {
        checks++;
        if (!Objects.equals(expected, actual)) throw new AssertionError(label + ": " + actual);
    }
    private static void result(InputCommitCoordinator.Result actual, boolean accepted, boolean saved,
                               InputCommitCoordinator.Error error, String label) {
        equal(accepted, actual.accepted, label + " accepted");
        equal(saved, actual.historySaved, label + " saved");
        equal(error, actual.error, label + " error");
    }
    private static void successAndIdenticalInputsRemainDistinct() {
        List<String> order = new ArrayList<>();
        InputCommitCoordinator.Destination target = text -> { order.add("write:" + text); return true; };
        InputCommitCoordinator.History sink = text -> order.add("history:" + text);
        result(InputCommitCoordinator.commit("哈", false, target, sink), true, true,
                InputCommitCoordinator.Error.NONE, "first commit");
        result(InputCommitCoordinator.commit("哈", false, target, sink), true, true,
                InputCommitCoordinator.Error.NONE, "same text second commit");
        equal(java.util.Arrays.asList("write:哈", "history:哈", "write:哈", "history:哈"), order,
                "each accepted commit records after its own write");
    }
    private static void rejectedAndThrowingTargetsNeverRecord() {
        int[] writes = {0}, records = {0};
        InputCommitCoordinator.History sink = text -> records[0]++;
        result(InputCommitCoordinator.commit("text", false, text -> { writes[0]++; return false; }, sink),
                false, false, InputCommitCoordinator.Error.TARGET_REJECTED, "rejected");
        result(InputCommitCoordinator.commit("text", false, text -> {
            writes[0]++; throw new java.io.IOException("test failure");
        }, sink), false, false, InputCommitCoordinator.Error.TARGET_FAILED, "exception");
        equal(2, writes[0], "one attempt per call");
        equal(0, records[0], "no history for unaccepted target");
    }
    private static void historyFailureDoesNotReplayTarget() {
        int[] writes = {0}, records = {0};
        result(InputCommitCoordinator.commit("already accepted", false, text -> { writes[0]++; return true; },
                text -> { records[0]++; throw new java.io.IOException("history unavailable"); }),
                true, false, InputCommitCoordinator.Error.HISTORY_FAILED, "history failure");
        equal(1, writes[0], "accepted target is never replayed");
        equal(1, records[0], "one record attempt");
    }
    private static void sensitiveTextBypassesHistory() {
        int[] writes = {0};
        result(InputCommitCoordinator.commit("private", true, text -> { writes[0]++; return true; },
                text -> { throw new AssertionError("sensitive text reached history"); }),
                true, false, InputCommitCoordinator.Error.NONE, "sensitive accepted");
        result(InputCommitCoordinator.commit("private", true, text -> { writes[0]++; return false; }, null),
                false, false, InputCommitCoordinator.Error.TARGET_REJECTED, "sensitive rejected");
        equal(2, writes[0], "sensitive input still reaches target");
    }
    private static void emptyAndMissingDependencies() {
        InputCommitCoordinator.Destination forbidden = text -> { throw new AssertionError("empty write"); };
        InputCommitCoordinator.History forbiddenHistory = text -> { throw new AssertionError("empty record"); };
        result(InputCommitCoordinator.commit("", false, forbidden, forbiddenHistory), false, false,
                InputCommitCoordinator.Error.EMPTY_TEXT, "empty");
        result(InputCommitCoordinator.commit(null, false, forbidden, forbiddenHistory), false, false,
                InputCommitCoordinator.Error.EMPTY_TEXT, "null text");
        result(InputCommitCoordinator.commit("value", false, null, forbiddenHistory), false, false,
                InputCommitCoordinator.Error.TARGET_UNAVAILABLE, "missing destination");
        result(InputCommitCoordinator.commit("value", false, text -> true, null), true, false,
                InputCommitCoordinator.Error.HISTORY_UNAVAILABLE, "missing history");
        result(InputCommitCoordinator.commit("private", true, text -> true, null), true, false,
                InputCommitCoordinator.Error.NONE, "sensitive needs no history");
    }
    private static void whitespaceAndUnicodeAreRealInputs() {
        List<String> records = new ArrayList<>();
        for (String text : java.util.Arrays.asList(" ", "\n", "🧑‍💻")) {
            result(InputCommitCoordinator.commit(text, false, value -> true, records::add), true, true,
                    InputCommitCoordinator.Error.NONE, "preserve text");
        }
        equal(java.util.Arrays.asList(" ", "\n", "🧑‍💻"), records, "no trimming or Unicode changes");
    }
    public static void main(String[] args) {
        successAndIdenticalInputsRemainDistinct();
        rejectedAndThrowingTargetsNeverRecord();
        historyFailureDoesNotReplayTarget();
        sensitiveTextBypassesHistory();
        emptyAndMissingDependencies();
        whitespaceAndUnicodeAreRealInputs();
        System.out.println("InputCommitCoordinatorTest: " + checks + " checks passed");
    }
}
