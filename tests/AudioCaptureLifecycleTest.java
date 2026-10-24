package io.github.amxooo.voiceinput2pc;

import java.util.Arrays;

public final class AudioCaptureLifecycleTest {
    public static void main(String[] args) {
        normalStopDoesNotBecomeFailure();
        activeReadFailureRemainsVisible();
        startupBufferKeepsExactOrderAndIsBounded();
        System.out.println("audio capture lifecycle checks passed");
    }

    private static void normalStopDoesNotBecomeFailure() {
        check(AudioCaptureReadPolicy.classify(-3,false)==AudioCaptureReadPolicy.Decision.CLEAN_STOP,
                "stop-induced AudioRecord error must end cleanly");
        check(AudioCaptureReadPolicy.classify(0,false)==AudioCaptureReadPolicy.Decision.CLEAN_STOP,
                "zero read after stop must end cleanly");
        check(AudioCaptureReadPolicy.classify(320,false)==AudioCaptureReadPolicy.Decision.DATA,
                "a final positive block returned during stop must be preserved");
    }

    private static void activeReadFailureRemainsVisible() {
        check(AudioCaptureReadPolicy.classify(-6,true)==AudioCaptureReadPolicy.Decision.FAILURE,
                "dead active recorder must be reported");
        check(AudioCaptureReadPolicy.classify(0,true)==AudioCaptureReadPolicy.Decision.RETRY,
                "transient empty active read may retry under watchdog");
        check(AudioCaptureReadPolicy.classify(320,true)==AudioCaptureReadPolicy.Decision.DATA,
                "positive PCM is data");
    }

    private static void startupBufferKeepsExactOrderAndIsBounded() {
        StartupPcmBuffer buffer=new StartupPcmBuffer(8);
        check(buffer.append(new byte[]{1,2,3}),"first startup PCM");
        check(buffer.append(new byte[]{4,5}),"second startup PCM");
        check(Arrays.equals(new byte[]{1,2,3,4,5},buffer.drain()),"startup PCM order");
        check(buffer.size()==0,"drain clears buffer");
        check(buffer.append(new byte[]{1,2,3,4,5,6,7,8}),"exact capacity");
        check(!buffer.append(new byte[]{9}),"overflow rejected instead of dropping old speech");
    }

    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
