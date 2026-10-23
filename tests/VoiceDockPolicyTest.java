import io.github.amxooo.voiceinput2pc.VoiceDockPolicy;
public final class VoiceDockPolicyTest {
    private static int count;
    private static void check(boolean value){count++;if(!value)throw new AssertionError("case "+count);}
    public static void main(String[] args){
        check(!VoiceDockPolicy.cursorDiverged(true,0,0,5,5,-1,0));
        check(!VoiceDockPolicy.cursorDiverged(false,0,0,5,5,5,9));
        check(!VoiceDockPolicy.cursorDiverged(false,0,0,5,5,-1,5));
        check(VoiceDockPolicy.cursorDiverged(false,5,5,2,2,5,5));
        check(VoiceDockPolicy.cursorDiverged(false,5,5,2,5,5,5));
        check(!VoiceDockPolicy.cursorDiverged(false,2,2,2,2,-1,5));
        check(!VoiceDockPolicy.canChangeDevice(false,true,"",""));
        check(!VoiceDockPolicy.canChangeDevice(false,false,"草稿",""));
        check(!VoiceDockPolicy.canChangeDevice(false,false,"","unknown-delivery"));
        check(VoiceDockPolicy.canChangeDevice(true,true,"草稿","retry"));
        check(VoiceDockPolicy.canChangeDevice(false,false,"",""));
        check(VoiceDockPolicy.acceptsResult(3,3,"LOCAL","LOCAL"));
        check(!VoiceDockPolicy.acceptsResult(4,3,"LOCAL","LOCAL"));
        check(!VoiceDockPolicy.acceptsResult(3,3,"LOCAL","pc-1"));
        check(!VoiceDockPolicy.acceptsResult(3,3,"pc-1","pc-2"));
        System.out.println("VoiceDockPolicyTest: "+count+" PASS");
    }
}
