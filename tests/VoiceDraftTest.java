package io.github.amxooo.voiceinput2pc;

import java.util.Objects;

public final class VoiceDraftTest {
    private static int checks;
    private static void equal(Object expected,Object actual,String label) {
        checks++;
        if (!Objects.equals(expected,actual)) throw new AssertionError(label+": "+actual);
    }
    public static void main(String[] args) {
        equal("第一段第二段",VoiceDraft.append("第一段","第二段"),"Chinese joins without fake space");
        equal("hello world",VoiceDraft.append("hello","world"),"English gets a boundary space");
        equal("已经修改。继续",VoiceDraft.append("已经修改。","继续"),"punctuation is preserved");
        equal("保留",VoiceDraft.append("保留","  "),"empty result preserves prior draft");
        System.out.println(checks+" voice draft checks passed");
    }
}
