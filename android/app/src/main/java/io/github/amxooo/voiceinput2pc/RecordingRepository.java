package io.github.amxooo.voiceinput2pc;

import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.util.AtomicFile;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Private durable audio storage. No external names or arbitrary paths are accepted. */
final class RecordingRepository {
    static final class Entry {
        final File file;
        final long startedAt;
        final long durationMs;
        final boolean interrupted;
        Entry(File file, long startedAt, long durationMs, boolean interrupted) {
            this.file = file; this.startedAt = startedAt;
            this.durationMs = durationMs; this.interrupted = interrupted;
        }
    }
    private final File directory;
    RecordingRepository(Context context) {
        directory = new File(context.getFilesDir(), "recordings");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException("录音目录无法创建");
    }
    synchronized File create(long startedAt) throws IOException {
        File file = checked(new File(directory, "recording-" + startedAt + "-" + UUID.randomUUID() + ".part.m4a"));
        if (!file.createNewFile()) throw new IOException("录音文件已存在");
        writeMetadata(file, startedAt, 0, true);
        return file;
    }
    synchronized Entry finish(File file, long startedAt, long duration, boolean interrupted) throws IOException {
        file = checked(file);
        if (!file.isFile()) throw new IOException("录音文件不存在");
        // Force audio data to storage before publishing the completed filename.
        try (FileOutputStream out = new FileOutputStream(file, true)) { out.getFD().sync(); }
        File target = file;
        if (file.getName().endsWith(".part.m4a")) {
            target = checked(new File(directory, file.getName().replace(".part.m4a", ".m4a")));
            if (target.exists() || !file.renameTo(target)) throw new IOException("录音保存重命名失败");
        }
        writeMetadata(target, startedAt, Math.max(0, duration), interrupted);
        return new Entry(target, startedAt, Math.max(0, duration), interrupted);
    }
    synchronized void recoverInterrupted() {
        File[] files = directory.listFiles();
        if (files == null) return;
        for (File file : files) if (file.getName().endsWith(".part.m4a")) {
            try {
                checked(file);
                JSONObject data = metadata(file);
                finish(file, data.optLong("started_at", file.lastModified()), measuredDuration(file), true);
            } catch (Exception ignored) {
                // Preserve the original partial audio, including unplayable truncated containers.
            }
        }
    }
    synchronized List<Entry> list() {
        List<Entry> result = new ArrayList<>();
        File[] files = directory.listFiles();
        if (files != null) for (File file : files) {
            if (!file.getName().endsWith(".m4a")) continue;
            try {
                checked(file);
                if (!file.isFile()) continue;
                JSONObject data = metadata(file);
                result.add(new Entry(file, data.optLong("started_at", file.lastModified()),
                    data.optLong("duration_ms", 0), file.getName().endsWith(".part.m4a") || data.optBoolean("interrupted", true)));
            } catch (Exception ignored) { /* Reject links and paths outside the private directory. */ }
        }
        result.sort(Comparator.comparingLong((Entry e) -> e.startedAt).reversed());
        return Collections.unmodifiableList(result);
    }
    private File checked(File file) throws IOException {
        if (file == null || file.getParentFile() == null || !file.getParentFile().getCanonicalFile().equals(directory.getCanonicalFile())
            || !file.getAbsoluteFile().getParentFile().equals(directory.getAbsoluteFile())
            || !file.getCanonicalFile().equals(new File(directory.getCanonicalFile(), file.getName()))
            || !file.getName().matches("recording-[0-9]+-[0-9a-f-]+(?:\\.part)?\\.m4a"))
            throw new IOException("非法录音路径");
        return file;
    }
    private JSONObject metadata(File file) {
        try {
            byte[] bytes = new AtomicFile(new File(directory, file.getName() + ".json")).readFully();
            return new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        } catch (Exception ignored) { return new JSONObject(); }
    }
    private void writeMetadata(File file, long start, long duration, boolean interrupted) throws IOException {
        checked(file);
        AtomicFile atomic = new AtomicFile(new File(directory, file.getName() + ".json"));
        FileOutputStream stream = null;
        try {
            JSONObject json = new JSONObject();
            json.put("started_at", start); json.put("duration_ms", duration); json.put("interrupted", interrupted);
            stream = atomic.startWrite();
            stream.write(json.toString().getBytes(StandardCharsets.UTF_8));
            atomic.finishWrite(stream);
        } catch (Exception error) {
            if (stream != null) atomic.failWrite(stream);
            throw new IOException("录音信息保存失败", error);
        }
    }
    private static long measuredDuration(File file) {
        MediaMetadataRetriever reader = new MediaMetadataRetriever();
        try { reader.setDataSource(file.getAbsolutePath());
            String value = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            return value == null ? 0 : Long.parseLong(value);
        } catch (Exception ignored) { return 0; }
        finally { try { reader.release(); } catch (Exception ignored) {} }
    }
}
