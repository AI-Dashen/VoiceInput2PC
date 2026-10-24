package io.github.amxooo.voiceinput2pc;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Process;
import android.os.SystemClock;

import java.util.concurrent.atomic.AtomicBoolean;

/** Scoped PCM capture; user-started sessions only, no persisted recordings. */
final class SafeAudioRecorder {
    interface Listener {
        void onStarted(long generation);
        void onProgress(long generation, long samples);
        default void onPcm(long generation, byte[] pcm) {}
        default void onSpeechDetected(long generation) {}
        default void onEndpoint(long generation) {}
        void onStopped(long generation, long samples);
        void onError(long generation, String message);
    }

    private static final int SAMPLE_RATE = 16000;
    private static final int PRE_ROLL_BYTES = SAMPLE_RATE * 2 * 300 / 1000;
    private static final long NO_AUDIO_DATA_TIMEOUT_MS = 2500;
    private final Context context;
    private final Listener listener;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile AudioRecord recorder;
    private volatile Thread thread;
    private volatile long generation;

    SafeAudioRecorder(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    synchronized boolean start(long requestedGeneration) {
        if (running.get() || recorder != null || thread != null) return false;
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            listener.onError(requestedGeneration, "请先在万能输入法设置中授权麦克风");
            return false;
        }
        if (!MicrophoneLease.tryAcquire("ime-voice")) {
            listener.onError(requestedGeneration,"录音机正在使用麦克风，请先结束录音");
            return false;
        }
        int minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minimum <= 0) {
            MicrophoneLease.release("ime-voice");
            listener.onError(requestedGeneration, "设备未提供可用的录音缓冲区");
            return false;
        }
        final int bufferBytes = Math.max(minimum * 2, 4096);
        final AudioRecord created;
        try {
            created = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, bufferBytes);
        } catch (RuntimeException error) {
            MicrophoneLease.release("ime-voice");
            listener.onError(requestedGeneration, "无法创建本地录音：" + error.getClass().getSimpleName());
            return false;
        }
        if (created.getState() != AudioRecord.STATE_INITIALIZED) {
            created.release();
            MicrophoneLease.release("ime-voice");
            listener.onError(requestedGeneration, "设备端录音不可用");
            return false;
        }
        generation = requestedGeneration;
        recorder = created;
        running.set(true);
        thread = new Thread(() -> capture(created, requestedGeneration, bufferBytes), "local-ime-audio");
        thread.start();
        return true;
    }

    private void capture(AudioRecord active, long activeGeneration, int bufferBytes) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
        long samples = 0;
        try {
            active.startRecording();
            if(active.getRecordingState()!=AudioRecord.RECORDSTATE_RECORDING)
                throw new IllegalStateException("AudioRecord did not enter recording state");
            short[] buffer = new short[bufferBytes / 2];
            SpeechEndpointDetector endpoint = new SpeechEndpointDetector(SAMPLE_RATE);
            java.util.ArrayDeque<byte[]> preRoll=new java.util.ArrayDeque<>();
            int preRollBytes=0;boolean forwarding=false;
            boolean firstAudio=false;
            long noDataSince=SystemClock.elapsedRealtime();
            long nextReport = SAMPLE_RATE / 5;
            while (running.get() && active == recorder && activeGeneration == generation) {
                int count = active.read(buffer, 0, buffer.length, AudioRecord.READ_BLOCKING);
                boolean current=running.get() && active==recorder && activeGeneration==generation;
                AudioCaptureReadPolicy.Decision read=AudioCaptureReadPolicy.classify(count,current);
                if(read==AudioCaptureReadPolicy.Decision.CLEAN_STOP)break;
                if(read==AudioCaptureReadPolicy.Decision.FAILURE)
                    throw new IllegalStateException("AudioRecord read="+count);
                if(read==AudioCaptureReadPolicy.Decision.RETRY){
                    if(SystemClock.elapsedRealtime()-noDataSince>=NO_AUDIO_DATA_TIMEOUT_MS)
                        throw new IllegalStateException("AudioRecord returned no data");
                    SystemClock.sleep(5);continue;
                }
                noDataSince=SystemClock.elapsedRealtime();
                if(!firstAudio){firstAudio=true;listener.onStarted(activeGeneration);}
                byte[] pcm=new byte[count*2];
                for(int i=0;i<count;i++){pcm[i*2]=(byte)buffer[i];pcm[i*2+1]=(byte)(buffer[i]>>8);}
                samples += count;
                SpeechEndpointDetector.Decision decision=endpoint.accept(buffer,count);
                if(!forwarding){
                    preRoll.addLast(pcm);preRollBytes+=pcm.length;
                    while(preRollBytes>PRE_ROLL_BYTES&&preRoll.size()>1)preRollBytes-=preRoll.removeFirst().length;
                    if(endpoint.speechSeen()){
                        forwarding=true;listener.onSpeechDetected(activeGeneration);
                        while(!preRoll.isEmpty())listener.onPcm(activeGeneration,preRoll.removeFirst());
                        preRollBytes=0;
                    }
                }else listener.onPcm(activeGeneration,pcm);
                if (decision == SpeechEndpointDetector.Decision.NO_SPEECH_TIMEOUT) {
                    listener.onError(activeGeneration,"5秒内未听到有效语音");
                    break;
                }
                if (decision == SpeechEndpointDetector.Decision.END_OF_SPEECH
                        || decision == SpeechEndpointDetector.Decision.MAX_DURATION) {
                    listener.onEndpoint(activeGeneration);
                    break;
                }
                if (samples >= nextReport) {
                    listener.onProgress(activeGeneration, samples);
                    nextReport = samples + SAMPLE_RATE / 5;
                }
            }
        } catch (SecurityException denied) {
            if(running.get() && active==recorder && activeGeneration==generation)
                listener.onError(activeGeneration, "麦克风权限已被系统拒绝");
        } catch (RuntimeException failure) {
            if(running.get() && active==recorder && activeGeneration==generation)
                listener.onError(activeGeneration, "录音中断：" + failure.getClass().getSimpleName());
        } finally {
            try { if (active.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) active.stop(); }
            catch (RuntimeException ignored) {}
            try { active.release(); } catch (RuntimeException ignored) {}
            synchronized (this) {
                if (recorder == active) {
                    recorder = null;
                    thread = null;
                    running.set(false);
                }
            }
            MicrophoneLease.release("ime-voice");
            listener.onStopped(activeGeneration, samples);
        }
    }

    synchronized void stop() {
        running.set(false);
        AudioRecord active = recorder;
        if (active != null) {
            try { active.stop(); } catch (RuntimeException ignored) {}
        }
    }

    boolean isRunning() { return running.get(); }
    boolean isIdle() { return !running.get() && recorder == null && thread == null; }
    static int sampleRate() { return SAMPLE_RATE; }
}
