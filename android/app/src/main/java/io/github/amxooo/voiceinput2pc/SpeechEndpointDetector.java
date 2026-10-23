package io.github.amxooo.voiceinput2pc;

/**
 * Lightweight local capture guard.
 *
 * <p>Short pauses after speech are not treated as the end of dictation. A person
 * can think, breathe and continue speaking; six continuous seconds of silence
 * finalizes the phrase, while the visible Stop key remains available. The
 * detector also rejects a session that never starts and enforces a long
 * resource-safety ceiling.</p>
 */
final class SpeechEndpointDetector {
    enum Decision { CONTINUE, END_OF_SPEECH, NO_SPEECH_TIMEOUT, MAX_DURATION }
    private static final long START_CONFIRM_MS = 160;
    private static final long END_SILENCE_MS = 6000;
    static final long NO_SPEECH_MS = 5000;
    static final long MAX_SESSION_MS = 120000;
    private final int sampleRate;
    private double noiseFloor = 90;
    private long totalSamples, voiceRunSamples, silenceSamples;
    private boolean speechSeen, finished;

    SpeechEndpointDetector(int sampleRate) { this.sampleRate=sampleRate; }

    Decision accept(short[] samples,int count) {
        if (finished) return Decision.END_OF_SPEECH;
        if (samples == null || count <= 0) return Decision.CONTINUE;
        double sum=0;
        for (int i=0;i<count;i++) sum+=Math.abs((int)samples[i]);
        double energy=sum/count;
        totalSamples+=count;
        double startThreshold=Math.max(320,noiseFloor*3.0);
        if (!speechSeen) {
            if (energy < startThreshold) {
                noiseFloor=noiseFloor*0.94+energy*0.06;
                voiceRunSamples=0;
            } else voiceRunSamples+=count;
            if (millis(voiceRunSamples)>=START_CONFIRM_MS) {
                speechSeen=true;
                silenceSamples=0;
            } else if (millis(totalSamples)>=NO_SPEECH_MS) {
                finished=true; return Decision.NO_SPEECH_TIMEOUT;
            }
        } else {
            double continueThreshold=Math.max(240,noiseFloor*2.2);
            if (energy >= continueThreshold) silenceSamples=0;
            else silenceSamples+=count;
            if (millis(silenceSamples)>=END_SILENCE_MS) {
                finished=true; return Decision.END_OF_SPEECH;
            }
        }
        if (millis(totalSamples)>=MAX_SESSION_MS) {
            finished=true; return Decision.MAX_DURATION;
        }
        return Decision.CONTINUE;
    }

    private long millis(long samples) { return samples*1000L/sampleRate; }
    boolean speechSeen() { return speechSeen; }
}
