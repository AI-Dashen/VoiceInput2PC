package io.github.amxooo.voiceinput2pc;

import java.util.Objects;

public final class ImePanelControllerTest {
    private static int checks;
    private static void equal(Object expected, Object actual, String label) {
        checks++;
        if (!Objects.equals(expected, actual))
            throw new AssertionError(label + ": expected " + expected + ", got " + actual);
    }
    private static void check(boolean value, String label) { equal(true, value, label); }

    private static void primarySecondaryReturnPreservesInputSnapshot() {
        ImePanelController state = new ImePanelController();
        check(state.isPrimary(), "starts primary");
        check(state.enterSecondary(7, KeyboardController.Mode.T9, "646", 4, 4),
                "enter secondary");
        equal(ImePanelController.Level.SECONDARY, state.level(), "secondary level");
        equal(ImePanelController.Feature.HUB, state.feature(), "secondary hub");
        check(state.openFeature(ImePanelController.Feature.TRANSFER), "open transfer");
        equal(ImePanelController.Feature.TRANSFER, state.feature(), "transfer selected");

        ImePanelController.ReturnResult result = state.returnPrimary(7);
        check(result.restored, "same editor restores");
        equal(KeyboardController.Mode.T9, result.snapshot.mode, "mode preserved");
        equal("646", result.snapshot.rawInput, "composition preserved");
        equal(4, result.snapshot.selectionStart, "selection start preserved");
        check(state.isPrimary(), "return primary");
    }

    private static void staleEditorNeverRestores() {
        ImePanelController state = new ImePanelController();
        check(state.enterSecondary(4, KeyboardController.Mode.PINYIN, "ni", 2, 2),
                "capture old editor");
        ImePanelController.ReturnResult stale = state.returnPrimary(5);
        equal(false, stale.restored, "generation mismatch rejected");
        equal(null, stale.snapshot, "stale snapshot withheld");
        check(state.isPrimary(), "stale state discarded");
    }

    private static void transitionsAreBoundedAndResettable() {
        ImePanelController state = new ImePanelController();
        equal(false, state.openFeature(ImePanelController.Feature.STATISTICS),
                "feature cannot open from primary");
        equal(false, state.enterSecondary(0, KeyboardController.Mode.PINYIN, "", 0, 0),
                "invalid generation rejected");
        check(state.enterSecondary(1, KeyboardController.Mode.ENGLISH, "", 0, 0),
                "valid entry");
        equal(false, state.enterSecondary(1, KeyboardController.Mode.ENGLISH, "", 0, 0),
                "duplicate entry rejected");
        check(state.openFeature(ImePanelController.Feature.STATISTICS), "stats selected");
        check(state.openFeature(ImePanelController.Feature.SETTINGS), "settings selected");
        state.reset();
        check(state.isPrimary(), "explicit reset");
        equal(ImePanelController.Feature.HUB, state.feature(), "feature reset");
        equal(null, state.snapshot(), "snapshot cleared");
    }

    public static void main(String[] args) {
        primarySecondaryReturnPreservesInputSnapshot();
        staleEditorNeverRestores();
        transitionsAreBoundedAndResettable();
        System.out.println(checks + " IME panel checks passed");
    }
}
