package io.github.amxooo.voiceinput2pc;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.AudioManager;
import android.media.AudioRecordingConfiguration;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.StatFs;
import android.os.SystemClock;
import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Explicit user-started recorder. Android FGS restrictions remain enforced by the OS. */
public final class VoiceRecordingService extends Service {
    public static final String ACTION_START = "io.github.amxooo.voiceinput2pc.record.START";
    public static final String ACTION_PAUSE = "io.github.amxooo.voiceinput2pc.record.PAUSE";
    public static final String ACTION_RESUME = "io.github.amxooo.voiceinput2pc.record.RESUME";
    public static final String ACTION_STOP = "io.github.amxooo.voiceinput2pc.record.STOP";
    private static final String OWNER = "long-recorder", CHANNEL = "voice-recordings";
    private static final int NOTIFICATION_ID = 3401;
    private static final long STORAGE_RESERVE = 64L * 1024 * 1024;
    private static volatile String state = "IDLE", error = "";
    private static volatile long accumulatedMs, runningSince;
    private static volatile boolean tearingDown;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private MediaRecorder recorder;
    private AudioManager.AudioRecordingCallback silenceCallback;
    private RecordingRepository repository;
    private File activeFile;
    private long startedAt;
    private boolean foreground;
    private volatile boolean destroyed;
    private volatile String terminalState = "IDLE";

    public static String getState() { return state; }
    public static long getElapsed() {
        return accumulatedMs + ("RECORDING".equals(state) ? Math.max(0, SystemClock.elapsedRealtime() - runningSince) : 0);
    }
    public static String getError() { return error; }
    public static boolean isActive() {
        String current = state;
        return tearingDown || "STARTING".equals(current) || "RECORDING".equals(current) || "PAUSED".equals(current) || "SAVING".equals(current);
    }
    public static void command(Context context, String action) {
        if (!ACTION_START.equals(action) && !ACTION_PAUSE.equals(action) && !ACTION_RESUME.equals(action) && !ACTION_STOP.equals(action))
            throw new IllegalArgumentException("非法录音动作");
        if (ACTION_START.equals(action) && isActive()) return;
        if (ACTION_START.equals(action) && context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            state = "ERROR"; error = "请先允许麦克风权限"; return;
        }
        Intent intent = new Intent(context, VoiceRecordingService.class).setAction(action);
        try {
            if (ACTION_START.equals(action)) context.startForegroundService(intent);
            else if (isActive()) context.startService(intent);
        } catch (RuntimeException failure) {
            if (!isActive()) state = "ERROR";
            error = "无法启动录音，请从可见的录音页面重试（" + failure.getClass().getSimpleName() + "）";
        }
    }
    @Override public void onCreate() {
        super.onCreate();
        ensureNotificationChannel(this);
        worker.execute(() -> {
            try { repository = new RecordingRepository(this); repository.recoverInterrupted(); }
            catch (RuntimeException failure) { error = "录音存储不可用"; }
        });
    }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_START.equals(action)) {
            if (isActive() || destroyed) { if (!foreground) stopSelf(startId); return START_NOT_STICKY; }
            terminalState = "IDLE";
            state = "STARTING"; error = ""; accumulatedMs = 0;
            try {
                Notification notification = notification();
                if (Build.VERSION.SDK_INT >= 30)
                    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
                else startForeground(NOTIFICATION_ID, notification);
                foreground = true;
                main.removeCallbacks(tick); main.post(tick);
                worker.execute(this::begin);
            } catch (RuntimeException failure) {
                terminalState = "ERROR"; state = "ERROR"; error = "系统未允许后台录音，请从录音页面重试（" + failure.getClass().getSimpleName() + "）";
                endService();
            }
        } else if (ACTION_PAUSE.equals(action)) worker.execute(this::pause);
        else if (ACTION_RESUME.equals(action)) worker.execute(this::resumeRecording);
        else if (ACTION_STOP.equals(action)) worker.execute(() -> finish(false));
        else if (!isActive()) stopSelf();
        return START_NOT_STICKY;
    }
    private void begin() {
        if (!MicrophoneLease.tryAcquire(OWNER)) { fail("语音输入正在使用麦克风，请先停止语音输入"); return; }
        try {
            if (repository == null) throw new IllegalStateException("录音存储不可用");
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                throw new SecurityException("麦克风权限未授予");
            long available = new StatFs(getFilesDir().getAbsolutePath()).getAvailableBytes();
            if (available < STORAGE_RESERVE + 1024 * 1024) throw new IllegalStateException("存储空间不足，请至少保留65MB");
            startedAt = System.currentTimeMillis(); activeFile = repository.create(startedAt);
            recorder = new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioSamplingRate(44100); recorder.setAudioChannels(1); recorder.setAudioEncodingBitRate(96000);
            recorder.setOutputFile(activeFile.getAbsolutePath());
            recorder.setMaxFileSize(Math.min(1024L * 1024 * 1024, available - STORAGE_RESERVE));
            recorder.setOnErrorListener((r, what, extra) -> enqueue(() -> { if (recorder == r) fail("录音设备异常，已保留现有音频"); }));
            recorder.setOnInfoListener((r, what, extra) -> {
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED || what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED)
                    enqueue(() -> { if (recorder == r) { error = "已达到录音文件安全上限，已停止保存"; finish(false); } });
            });
            if (Build.VERSION.SDK_INT >= 29) {
                silenceCallback = new AudioManager.AudioRecordingCallback() {
                    @Override public void onRecordingConfigChanged(List<AudioRecordingConfiguration> configurations) {
                        for (AudioRecordingConfiguration configuration : configurations) if (configuration.isClientSilenced()) {
                            enqueue(() -> { if ("RECORDING".equals(state)) {
                                pause(); error = "麦克风被系统或其他应用静音，录音已暂停，请手动继续"; updateNotification();
                            }});
                            break;
                        }
                    }
                };
                recorder.registerAudioRecordingCallback(worker, silenceCallback);
            }
            recorder.prepare(); recorder.start();
            publishCaptureState();
            updateNotification();
        } catch (Exception failure) { fail("录音启动失败：" + safeMessage(failure)); }
    }
    private void pause() {
        if (!"RECORDING".equals(state) || recorder == null) return;
        try {
            recorder.pause(); accumulatedMs = getElapsed(); state = "PAUSED"; updateNotification();
        } catch (RuntimeException failure) { fail("录音暂停失败：" + safeMessage(failure)); }
    }
    private void resumeRecording() {
        if (!"PAUSED".equals(state) || recorder == null) return;
        try {
            recorder.resume(); publishCaptureState(); updateNotification();
        } catch (RuntimeException failure) { fail("录音继续失败：" + safeMessage(failure)); }
    }
    /** A resume need not emit another callback when privacy mute was already enabled. */
    private void publishCaptureState() {
        if (Build.VERSION.SDK_INT >= 29) {
            AudioRecordingConfiguration configuration = recorder.getActiveRecordingConfiguration();
            if (configuration != null && configuration.isClientSilenced()) {
                recorder.pause();
                state = "PAUSED";
                error = "麦克风被系统或其他应用静音，录音已暂停，请解除静音后手动继续";
                return;
            }
        }
        runningSince = SystemClock.elapsedRealtime();
        error = "";
        state = "RECORDING";
    }
    private void finish(boolean interrupted) {
        if (recorder == null && activeFile == null) { endService(); return; }
        accumulatedMs = getElapsed(); state = "SAVING"; updateNotification();
        boolean stopped = false;
        try { if (recorder != null) { recorder.stop(); stopped = true; } }
        catch (RuntimeException failure) { error = "录音未能完整封装，已保留中断音频"; interrupted = true; }
        finally { releaseRecorder(); }
        try {
            if (activeFile != null) {
                RecordingRepository.Entry saved=repository.finish(activeFile, startedAt, accumulatedMs, interrupted || !stopped);
                if(!saved.interrupted)try{RecordingTranscriptionService.enqueue(this,saved.file);}catch(RuntimeException jobError){error="录音已保存，自动转写未启动，可从录音文字中重试";}
            }
            terminalState = interrupted || !stopped ? "ERROR" : "IDLE";
        } catch (Exception failure) { terminalState = "ERROR"; error = "保存异常，临时音频已保留：" + safeMessage(failure); }
        activeFile = null; MicrophoneLease.release(OWNER); endService();
    }
    private void fail(String message) {
        error = message; terminalState = "ERROR";
        if (recorder != null || activeFile != null) finish(true);
        else { MicrophoneLease.release(OWNER); endService(); }
        terminalState = "ERROR";
    }
    private void enqueue(Runnable task) {
        try { worker.execute(task); } catch (java.util.concurrent.RejectedExecutionException ignored) { /* Late platform callback after teardown. */ }
    }
    private void releaseRecorder() {
        if (recorder != null) {
            if (Build.VERSION.SDK_INT >= 29 && silenceCallback != null) {
                try { recorder.unregisterAudioRecordingCallback(silenceCallback); } catch (RuntimeException ignored) {}
            }
            try { recorder.release(); } catch (RuntimeException ignored) {}
        }
        recorder = null; silenceCallback = null;
    }
    private void endService() {
        tearingDown = true;
        if (!destroyed) main.post(() -> { main.removeCallbacks(tick); if (foreground) { stopForeground(STOP_FOREGROUND_REMOVE); foreground = false; } stopSelf(); });
    }
    private final Runnable tick = new Runnable() {
        @Override public void run() { if (isActive()) { updateNotification(); main.postDelayed(this, 1000); } }
    };
    private void updateNotification() {
        main.post(() -> { if (foreground) getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification()); });
    }
    public static void ensureNotificationChannel(Context context) {
        NotificationChannel channel = new NotificationChannel(CHANNEL, "录音状态", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("录音计时、暂停和结束"); channel.setSound(null, null);
        context.getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }
    private Notification notification() {
        Intent show = new Intent(this, HomeActivity.class).putExtra("management.section", "recordings")
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent content = PendingIntent.getActivity(this, 3401, show, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String title = "RECORDING".equals(state) ? "正在录音" : "PAUSED".equals(state) ? "录音已暂停" : "SAVING".equals(state) ? "正在保存录音" : "正在启动录音";
        long seconds = getElapsed() / 1000;
        Notification.Builder builder = new Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle(title)
            .setContentText(error.isEmpty() ? String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60) : error)
            .setContentIntent(content).setOngoing(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_SERVICE);
        if ("RECORDING".equals(state)) builder.addAction(android.R.drawable.ic_media_pause, "暂停", actionIntent(ACTION_PAUSE, 1));
        if ("PAUSED".equals(state)) builder.addAction(android.R.drawable.ic_media_play, "继续", actionIntent(ACTION_RESUME, 2));
        builder.addAction(android.R.drawable.ic_menu_save, "结束并保存", actionIntent(ACTION_STOP, 3));
        return builder.build();
    }
    private PendingIntent actionIntent(String action, int request) {
        return PendingIntent.getService(this, request, new Intent(this, VoiceRecordingService.class).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
    private static String safeMessage(Exception error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }
    @Override public void onDestroy() {
        destroyed = true; tearingDown = true;
        main.removeCallbacks(tick);
        worker.execute(() -> {
            try { if (recorder != null || activeFile != null) finish(true); else MicrophoneLease.release(OWNER); }
            finally { state = terminalState; tearingDown = false; }
        });
        worker.shutdown();
        super.onDestroy();
    }
}
