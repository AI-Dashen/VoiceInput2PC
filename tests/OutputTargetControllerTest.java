package io.github.amxooo.voiceinput2pc;
import java.util.Objects;
public final class OutputTargetControllerTest {
    private static int checks;
    private static void equal(Object e,Object a,String l) { checks++; if (!Objects.equals(e,a)) throw new AssertionError(l+": "+e+" != "+a); }
    private static void check(boolean v,String l) { equal(true,v,l); }
    public static void main(String[] args) {
        OutputTargetController c=new OutputTargetController(); c.onStartInput("app|1",false,false);
        equal(OutputTargetController.ToggleResult.NO_PAIRED_DEVICE,c.toggleTarget(),"pairing required");
        c.setPairing("device-1","办公室电脑"); equal(OutputTargetController.ToggleResult.OK,c.toggleTarget(),"remote selected"); check(c.isRemote(),"remote active");
        check(c.commitStable("你好"),"stable routed"); c.setComposing("ma"); equal(false,c.canSend(),"composition blocks send");
        c.clearComposing(); check(c.canSend(),"stable can send"); OutputTargetController.SendRequest first=c.beginSend("id-1");
        equal("你好",first.text,"request text"); equal(1,first.attempt,"attempt"); check(c.commitStable("世界"),"typing during send");
        check(c.onSendSucceeded("id-1"),"receipt"); equal("世界",c.pendingText(),"suffix kept");
        c.moveCursor(-1); c.commitStable("新"); equal("世新界",c.pendingText(),"cursor insertion");
        c.deleteBackward(); equal("世界",c.pendingText(),"cursor deletion"); c.moveCursor(1);
        OutputTargetController.SendRequest second=c.beginSend("id-2"); check(c.onSendFailed(second.requestId,OutputTargetController.SendFailure.UNKNOWN),"unknown restored");
        OutputTargetController.SendRequest retry=c.beginSend("new-id"); equal("id-2",retry.requestId,"reuse id"); equal(2,retry.attempt,"retry count");
        check(c.onSendFailed(retry.requestId,OutputTargetController.SendFailure.REJECTED),"rejected restored");
        c.commitStable("👋"); c.deleteBackward(); equal("世界",c.pendingText(),"emoji delete");
        c.commitStable("后缀");
        OutputTargetController.SendRequest uncertain=c.beginSend("id-3"); c.commitStable("新增");
        c.onSendFailed(uncertain.requestId,OutputTargetController.SendFailure.UNKNOWN);
        c.deleteBackward();
        OutputTargetController.SendRequest prefixRetry=c.beginSend("ignored");
        equal("id-3",prefixRetry.requestId,"uncertain prefix id reused"); equal("世界后缀",prefixRetry.text,"only uncertain prefix resent");
        equal("新",c.pendingText(),"edited suffix remains pending");
        c.onSendSucceeded(prefixRetry.requestId);
        OutputTargetController restored=new OutputTargetController(); restored.restore(c.snapshot()); equal("新",restored.pendingText(),"restore");
        restored.setPairing("device-1","办公室电脑"); restored.onStartInput("secret",true,false);
        equal(OutputTargetController.ToggleResult.SENSITIVE_FIELD,restored.toggleTarget(),"password blocked");
        c.onStartInput("app|2",false,false); equal(OutputTargetController.Target.LOCAL,c.target(),"new field local"); equal("新",c.pendingText(),"draft retained");
        System.out.println(checks+" output target checks passed");
    }
}
