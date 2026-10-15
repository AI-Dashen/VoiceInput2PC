package io.github.amxooo.voiceinput2pc;

public final class SpeechEndpointDetectorTest {
    public static void main(String[] args) {
        acceptsNaturalPauseAndEndsAfterSixSeconds();
        ignoresShortPause();
        timesOutWithoutSpeech();
        enforcesLongSafetyCeiling();
        System.out.println("speech endpoint checks passed");
    }
    private static void acceptsNaturalPauseAndEndsAfterSixSeconds() {
        SpeechEndpointDetector d=new SpeechEndpointDetector(16000);
        feed(d,(short)2400,400);
        check(feed(d,(short)0,5500)==SpeechEndpointDetector.Decision.CONTINUE,
                "a normal thinking pause must not end dictation");
        check(feed(d,(short)0,600)==SpeechEndpointDetector.Decision.END_OF_SPEECH,
                "six seconds of continuous silence ends dictation");
    }
    private static void ignoresShortPause() {
        SpeechEndpointDetector d=new SpeechEndpointDetector(16000);
        feed(d,(short)2200,400);
        check(feed(d,(short)0,1000)==SpeechEndpointDetector.Decision.CONTINUE,"short pause");
        check(feed(d,(short)2200,200)==SpeechEndpointDetector.Decision.CONTINUE,"speech resumes");
    }
    private static void timesOutWithoutSpeech() {
        SpeechEndpointDetector d=new SpeechEndpointDetector(16000);
        check(feed(d,(short)20,8200)==SpeechEndpointDetector.Decision.NO_SPEECH_TIMEOUT,"no speech timeout");
    }
    private static void enforcesLongSafetyCeiling() {
        SpeechEndpointDetector d=new SpeechEndpointDetector(16000);
        feed(d,(short)2200,400);
        check(feed(d,(short)2200,600000)==SpeechEndpointDetector.Decision.MAX_DURATION,
                "ten minute safety ceiling");
    }
    private static SpeechEndpointDetector.Decision feed(SpeechEndpointDetector d,short value,int millis) {
        short[] block=new short[160]; java.util.Arrays.fill(block,value);
        SpeechEndpointDetector.Decision result=SpeechEndpointDetector.Decision.CONTINUE;
        for(int elapsed=0;elapsed<millis;elapsed+=10) {
            result=d.accept(block,block.length);
            if(result!=SpeechEndpointDetector.Decision.CONTINUE)return result;
        }
        return result;
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
