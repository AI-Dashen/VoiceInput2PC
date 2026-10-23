package io.github.amxooo.voiceinput2pc;

public final class EditorReadinessPolicyTest {
    public static void main(String[] args) {
        // Explicit truth table; bits are bound, editor, matching identity, connection.
        EditorReadinessPolicy.State[] expected = {
            EditorReadinessPolicy.State.NO_EDITOR, EditorReadinessPolicy.State.NO_EDITOR,
            EditorReadinessPolicy.State.NO_EDITOR, EditorReadinessPolicy.State.NO_EDITOR,
            EditorReadinessPolicy.State.NO_EDITOR, EditorReadinessPolicy.State.NO_EDITOR,
            EditorReadinessPolicy.State.NO_EDITOR, EditorReadinessPolicy.State.NO_EDITOR,
            EditorReadinessPolicy.State.NO_EDITOR, EditorReadinessPolicy.State.NO_EDITOR,
            EditorReadinessPolicy.State.NO_EDITOR, EditorReadinessPolicy.State.NO_EDITOR,
            EditorReadinessPolicy.State.STALE_EDITOR, EditorReadinessPolicy.State.STALE_EDITOR,
            EditorReadinessPolicy.State.DISCONNECTED, EditorReadinessPolicy.State.READY
        };
        int checks = 0;
        for (int mask = 0; mask < 16; mask++) {
            EditorReadinessPolicy.State state = EditorReadinessPolicy.evaluate(
                    (mask & 8) != 0, (mask & 4) != 0, (mask & 2) != 0, (mask & 1) != 0);
            if (state != expected[mask]) throw new AssertionError("readiness mask " + mask + ": " + state);
            checks++;
        }
        for (EditorReadinessPolicy.State state : EditorReadinessPolicy.State.values()) {
            for (boolean busy : new boolean[]{false, true}) {
                boolean expectedAction = state == EditorReadinessPolicy.State.READY && !busy;
                if (EditorReadinessPolicy.canRunLocalAction(state, busy) != expectedAction)
                    throw new AssertionError("action gate " + state + ", busy=" + busy);
                checks++;
            }
        }
        for (boolean busy : new boolean[]{false, true}) {
            if (EditorReadinessPolicy.canRunLocalAction(null, busy)) throw new AssertionError("null state allowed");
            checks++;
        }
        System.out.println("EditorReadinessPolicyTest: " + checks + " PASS (16 readiness, 8 action, 2 null-state)");
    }
}
