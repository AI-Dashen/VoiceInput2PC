package io.github.amxooo.voiceinput2pc;

public final class EditorActionContextPolicyTest {
    private static int checks;
    private static void context(String label, EditorActionContextPolicy.DeleteContext expected,
            int start, int end, int selectedLength, int beforeLength) {
        EditorActionContextPolicy.DeleteContext actual = EditorActionContextPolicy.evaluate(
                start, end, selectedLength, beforeLength);
        if (actual != expected) throw new AssertionError(label + ": " + actual);
        checks++;
    }
    private static void check(String label, boolean expected, boolean actual) {
        if (actual != expected) throw new AssertionError(label + ": " + actual);
        checks++;
    }
    public static void main(String[] args) {
        EditorActionContextPolicy.DeleteContext available = EditorActionContextPolicy.DeleteContext.AVAILABLE;
        EditorActionContextPolicy.DeleteContext atStart = EditorActionContextPolicy.DeleteContext.AT_START;
        EditorActionContextPolicy.DeleteContext unknown = EditorActionContextPolicy.DeleteContext.UNKNOWN;
        context("forward selection at start", available, 0, 4, -1, 0);
        context("reversed selection", available, 4, 0, -1, 0);
        context("selected text without offsets", available, -1, -1, 1, -1);
        context("selected text overrides stale collapsed offsets", available, 0, 0, 1, 0);
        context("one previous char without offsets", available, -1, -1, 0, 1);
        context("previous char overrides stale zero offsets", available, 0, 0, 0, 1);
        context("nonempty preceding text", available, 4, 4, 0, 1);
        context("known document start", atStart, 0, 0, 0, 0);
        context("start with unsupported text queries", atStart, 0, 0, -1, -1);
        context("terminal unsupported queries", unknown, -1, -1, -1, -1);
        context("empty queries without offsets", unknown, -1, -1, 0, 0);
        context("partial offsets", unknown, 0, -1, 0, 0);
        context("other partial offsets", unknown, -1, 0, 0, 0);
        context("positive cursor but unavailable preceding text", unknown, 4, 4, 0, -1);
        context("empty query at positive cursor", unknown, 4, 4, 0, 0);
        context("arbitrary negative sentinel", unknown, -2, -2, -2, -2);
        context("large reversed offsets without subtraction overflow", available,
                Integer.MAX_VALUE, 0, -1, -1);
        for (EditorActionContextPolicy.DeleteContext state : EditorActionContextPolicy.DeleteContext.values()) {
            for (boolean ready : new boolean[]{false, true}) {
                for (boolean busy : new boolean[]{false, true}) {
                    check("delete gate " + state + ready + busy, ready && !busy && state != atStart,
                            EditorActionContextPolicy.canDelete(state, ready, busy));
                    check("independent enter gate " + state + ready + busy, ready && !busy,
                            EditorActionContextPolicy.canEnter(ready, busy));
                }
            }
        }
        check("missing context does not bypass gate", false,
                EditorActionContextPolicy.canDelete(null, true, false));
        check("empty editor can still submit", true, EditorActionContextPolicy.canEnter(true, false));
        System.out.println("EditorActionContextPolicyTest: " + checks + " PASS");
    }
}
