package io.github.amxooo.voiceinput2pc;
public final class OrderedCommitTrackerTest {
    private static void check(boolean value) { if (!value) throw new AssertionError(); }
    public static void main(String[] args) {
        CommitTracker t = new CommitTracker("", "", null, false);
        check(t.startOrdered("session-1", 1));
        t.observe("临时", true, false); check(t.prepare("partial") == null);
        t.observe("你好", false, false);
        CommitTracker.Pending one = t.prepare("one"); check(one.seq == 1);
        t.observe("你好世界", false, false); check(t.prepare("other") == null);
        check(!t.receipt("wrong", true, "inserted")); check(t.pending().seq == 1);
        check(t.receipt("one", true, "inserted"));
        CommitTracker.Pending two = t.prepare("two"); check(two.seq == 2); check(two.text.equals("世界"));
        check(t.receipt("two", true, "inserted"));
        check(t.isActive()); check(t.prepare("empty") == null);
        t.observe("您好世界", false, false); check(t.hasConflict()); check(!t.isActive());
        CommitTracker legacy = new CommitTracker("", "", null, false);
        check(legacy.start("legacy")); legacy.observe("旧协议", false, false);
        check(legacy.prepare("legacy-id").seq == 0);
        CommitTracker restore = new CommitTracker("", "你好", one, false);
        check(!restore.isActive()); check(!restore.startOrdered("new-session", 1));
        check(restore.receipt("one", true, "inserted"));
        System.out.println("OrderedCommitTracker: PASS");
    }
}
