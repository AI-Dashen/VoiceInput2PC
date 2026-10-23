package io.github.amxooo.voiceinput2pc;

/** Pure decisions shared by the direct-editor voice path and its regression tests. */
public final class VoiceDockPolicy {
    private VoiceDockPolicy() {}
    public static boolean cursorDiverged(boolean writing,int oldStart,int oldEnd,int start,int end,
                                         int composingEnd,int expected) {
        if(writing || oldStart==start && oldEnd==end)return false;
        return start!=end || (end!=composingEnd && end!=expected);
    }
    public static boolean canChangeDevice(boolean sameDevice,boolean sending,String pending,String retryId) {
        return sameDevice || !sending && (pending==null || pending.isEmpty())
                && (retryId==null || retryId.isEmpty());
    }
    public static boolean acceptsResult(long expectedGeneration,long generation,String expectedTarget,String actualTarget) {
        return expectedGeneration==generation && expectedTarget!=null && expectedTarget.equals(actualTarget);
    }
}
