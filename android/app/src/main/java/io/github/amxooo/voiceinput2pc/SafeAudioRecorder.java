package io.github.amxooo.voiceinput2pc;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Process;

import java.util.concurrent.atomic.AtomicBoolean;

/** Probe-A recorder: captures no files and discards PCM after lifecycle verification. */
final class SafeAudioRecorder {
    interface Listener {
        void onStarted(long generation);
        void onProgress(long generation, long samples);
        void onStopped(long generation, long samples);
        void onError(long generation, String message);
    }

    private static final int SAMPLE_RATE = 16000;
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
        if (running.get()) return false;
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            listener.onError(requestedGeneration, "请先在万能输入法设置中授权麦克风");
            return false;
        }
        int minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minimum <= 0) {
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
            listener.onError(requestedGeneration, "无法创建本地录音：" + error.getClass().getSimpleName());
            return false;
        }
        if (created.getState() != AudioRecord.STATE_INITIALIZED) {
            created.release();
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
            listener.onStarted(activeGeneration);
            short[] buffer = new short[bufferBytes / 2];
            long nextReport = SAMPLE_RATE / 5;
            while (running.get() && active == recorder && activeGeneration == generation) {
                int count = active.read(buffer, 0, buffer.length, AudioRecord.READ_BLOCKING);
                if (count < 0) throw new IllegalStateException("AudioRecord read=" + count);
                samples += count;
                if (samples >= nextReport) {
                    listener.onProgress(activeGeneration, samples);
                    nextReport = samples + SAMPLE_RATE / 5;
                }
            }
        } catch (SecurityException denied) {
            listener.onError(activeGeneration, "麦克风权限已被系统拒绝");
        } catch (RuntimeException failure) {
            listener.onError(activeGeneration, "录音中断：" + failure.getClass().getSimpleName());
        } finally {
            try { if (active.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) active.stop(); }
            catch (RuntimeException ignored) {}
            active.release();
            synchronized (this) {
                if (recorder == active) {
                    recorder = null;
                    thread = null;
                    running.set(false);
                }
            }
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
    static int sampleRate() { return SAMPLE_RATE; }
}
