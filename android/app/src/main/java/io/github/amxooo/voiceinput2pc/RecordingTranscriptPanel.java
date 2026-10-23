package io.github.amxooo.voiceinput2pc;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Inline, persisted transcript viewer. It never infers a speaker identity or edits audio. */
final class RecordingTranscriptPanel {
    interface SegmentPlayback { void play(File audio, long startMs, long endMs); }
    private static final int PAGE_SIZE = 20;
    private final WeakReference<Activity> owner;
    private final Context app;
    private final File audio;
    private SegmentPlayback playback;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private RecordingTranscriptStore store;
    private LinearLayout root, entries, speakers;
    private TextView status, notice;
    private Button request, cancel, copy;
    private JSONObject snapshot;
    private String fingerprint = "";
    private volatile boolean destroyed;
    private boolean loading, mutating;
    private int page, revision;

    RecordingTranscriptPanel(Activity activity, File audio, SegmentPlayback callback) {
        owner = new WeakReference<>(activity);
        app = activity.getApplicationContext();
        this.audio = audio;
        playback = callback;
    }

    View create() {
        Activity activity = owner.get();
        if (activity == null || destroyed) throw new IllegalStateException("录音页面已关闭");
        root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(0, dp(8), 0, dp(8));
        root.addView(VoiceUiTheme.title(activity, "录音文字与说话人", 15));
        status = label(activity, "正在读取转写状态…", 13);
        root.addView(status);
        LinearLayout actions = row(activity);
        request = VoiceUiTheme.button(activity, "转成文字", true);
        request.setOnClickListener(v -> mutate(() -> RecordingTranscriptionService.enqueue(app, audio), "转写请求已提交，请以进度为准。"));
        cancel = VoiceUiTheme.button(activity, "停止转写", false);
        cancel.setOnClickListener(v -> mutate(() -> RecordingTranscriptionService.cancel(app, audio), "已请求停止转写，原录音保留。"));
        copy = VoiceUiTheme.button(activity, "复制文字", false);
        copy.setOnClickListener(v -> copyAll());
        actions.addView(request, weighted()); actions.addView(cancel, weighted()); actions.addView(copy, weighted());
        root.addView(actions);
        notice = label(activity, "本机转写；编号仅区分本次录音中的声音，不代表真实身份。", 12);
        root.addView(notice);
        speakers = new LinearLayout(activity); speakers.setOrientation(LinearLayout.VERTICAL); root.addView(speakers);
        entries = new LinearLayout(activity); entries.setOrientation(LinearLayout.VERTICAL); root.addView(entries);
        request.setEnabled(false); cancel.setEnabled(false); copy.setEnabled(false);
        refresh();
        return root;
    }

    void refresh() {
        if (destroyed || loading || mutating || root == null) return;
        loading = true;
        final int ticket = revision;
        io.execute(() -> {
            JSONObject result = null; String failure = null;
            try { result = getStore().load(audio); }
            catch (Exception e) { failure = "读取转写失败：" + e.getClass().getSimpleName() + "，原录音保留。"; }
            final JSONObject found = result; final String error = failure;
            if (destroyed) return;
            main.post(() -> {
                loading = false;
                if (!alive() || ticket != revision) return;
                if (error != null) { status.setText(error); request.setEnabled(false); cancel.setEnabled(false); copy.setEnabled(false); return; }
                String next = found == null ? "none" : found.toString();
                if (!next.equals(fingerprint)) { fingerprint = next; snapshot = found; render(); }
            });
        });
    }

    private RecordingTranscriptStore getStore() {
        if (store == null) store = new RecordingTranscriptStore(app);
        return store;
    }

    private void render() {
        if (!alive()) return;
        Activity activity = owner.get();
        JSONObject job = snapshot;
        String state = job == null ? "" : job.optString("state");
        boolean active = "QUEUED".equals(state) || "DECODING".equals(state) || "DIARIZING".equals(state)
                || "TRANSCRIBING".equals(state) || "WAITING_INPUT".equals(state) || "CANCELLING".equals(state);
        JSONArray segments = job == null ? new JSONArray() : job.optJSONArray("segments");
        if (segments == null) segments = new JSONArray();
        int completed = job == null ? 0 : job.optInt("completed_segments");
        int total = job == null ? 0 : job.optInt("total_segments");
        String progress = total > 0 ? " · " + completed + "/" + total + " 段" : "";
        status.setText(stateLabel(state) + progress);
        request.setText("COMPLETED".equals(state) ? "已完成" : "".equals(state) ? "转成文字" : "FAILED".equals(state) || "CANCELLED".equals(state) ? "重试转写" : "转成文字");
        request.setEnabled(!active && !mutating && !"COMPLETED".equals(state));
        cancel.setEnabled(active && !mutating && !"CANCELLING".equals(state));
        copy.setEnabled(hasText(segments));
        String error = job == null ? "" : job.optString("error");
        if (!error.isEmpty()) notice.setText("处理提示：" + trim(error, 500) + "。原录音保留，可重试。");
        else if ("WAITING_INPUT".equals(state)) notice.setText("正在使用语音输入，录音转写暂时让路；结束输入后继续处理。");
        else notice.setText("点击文字回听对应片段；点说话人可纠正归属，点编号可改名。无法确定的声音保留为“待确认”。");
        speakers.removeAllViews();
        for (int id : speakerIds(job)) {
            Button name = VoiceUiTheme.button(activity, speakerLabel(id), false);
            final int speaker = id;
            name.setEnabled(!active && !mutating);
            name.setOnClickListener(v -> rename(speaker));
            speakers.addView(name, params(40));
        }
        entries.removeAllViews();
        if (segments.length() == 0) { entries.addView(label(activity, active ? "正在处理录音，文字将在识别后显示。" : "尚无转写片段。", 13)); return; }
        int lastPage = (segments.length() - 1) / PAGE_SIZE;
        page = Math.min(page, lastPage);
        int start = page * PAGE_SIZE, end = Math.min(start + PAGE_SIZE, segments.length());
        for (int i = start; i < end; i++) {
            JSONObject segment = segments.optJSONObject(i); if (segment == null) continue;
            final int index = i; final long from = segment.optLong("start_ms"), to = segment.optLong("end_ms");
            LinearLayout item = new LinearLayout(activity); item.setOrientation(LinearLayout.VERTICAL);
            item.setPadding(0, dp(8), 0, dp(8));
            LinearLayout heading = row(activity);
            TextView stamp = label(activity, time(from) + "–" + time(to), 12);
            heading.addView(stamp, new LinearLayout.LayoutParams(0, -2, 1));
            int id = segment.optInt("speaker", -1);
            Button attribution = VoiceUiTheme.button(activity, speakerLabel(id) + (id >= 0 && segment.optBoolean("uncertain") ? " · 待确认" : ""), false);
            attribution.setEnabled(!active && !mutating);
            attribution.setOnClickListener(v -> correct(index));
            heading.addView(attribution, new LinearLayout.LayoutParams(-2, dp(40)));
            item.addView(heading);
            String text = segment.optString("text");
            String segmentState = segment.optString("status");
            if (text.isEmpty()) text = "FAILED".equals(segmentState) ? "此片段识别失败，原音频可回听。"
                    : "FAILED".equals(state) || "CANCELLED".equals(state) ? "此片段尚未完成，可重试继续；原音频可回听。" : "等待识别…";
            TextView words = VoiceUiTheme.text(activity, trim(text, 10000), 15, VoiceUiTheme.INK);
            words.setPadding(0, dp(4), 0, dp(8)); words.setMinHeight(dp(44));
            words.setContentDescription("回听 " + time(from) + " 的片段：" + trim(text, 120));
            words.setOnClickListener(v -> { if (playback != null && to > from) playback.play(audio, from, to); });
            item.addView(words); entries.addView(item);
        }
        if (segments.length() > PAGE_SIZE) {
            LinearLayout navigation = row(activity);
            Button previous = VoiceUiTheme.button(activity, "上一组", false); previous.setEnabled(page > 0);
            previous.setOnClickListener(v -> { page--; render(); }); navigation.addView(previous, weighted());
            TextView count = label(activity, (start + 1) + "–" + end + " / " + segments.length(), 12); count.setGravity(Gravity.CENTER);
            navigation.addView(count, weighted());
            Button next = VoiceUiTheme.button(activity, "更多片段", false); next.setEnabled(page < lastPage);
            next.setOnClickListener(v -> { page++; render(); }); navigation.addView(next, weighted()); entries.addView(navigation);
        }
    }

    private void rename(int id) {
        if (!alive() || mutating) return;
        Activity activity = owner.get(); EditText input = new EditText(activity);
        input.setSingleLine(true); input.setText(speakerLabel(id));
        new AlertDialog.Builder(activity).setTitle("修改说话人名称").setMessage("仅修改本次录音中的标签，不会建立个人声纹身份。").setView(input)
                .setNegativeButton("取消", null).setPositiveButton("保存", (dialog, which) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty() || name.length() > 40) { notice.setText("名称须为 1–40 个字符。"); return; }
                    mutate(() -> getStore().renameSpeaker(audio, id, name), "说话人名称已保存。");
                }).show();
    }

    private void correct(int segment) {
        if (!alive() || mutating) return;
        List<Integer> ids = speakerIds(snapshot); ids.add(0, -1);
        String[] labels = new String[ids.size() + 1];
        for (int i = 0; i < ids.size(); i++) labels[i] = speakerLabel(ids.get(i));
        labels[ids.size()] = "新增说话人…";
        new AlertDialog.Builder(owner.get()).setTitle("此片段属于谁？").setItems(labels, (dialog, which) -> {
            if (which == ids.size()) { addSpeaker(segment); return; }
            int speaker = ids.get(which);
            mutate(() -> getStore().correctSpeaker(audio, segment, speaker), "片段归属已保存。");
        }).setNegativeButton("取消", null).show();
    }

    private void addSpeaker(int segment) {
        if (!alive() || mutating) return;
        Activity activity = owner.get();
        EditText input = new EditText(activity);
        input.setSingleLine(true);
        input.setHint("输入称呼，例如：同事甲");
        new AlertDialog.Builder(activity).setTitle("新增说话人")
                .setMessage("为本次录音增加一个标签，并将所选片段归到这个标签；不会建立声纹身份。")
                .setView(input).setNegativeButton("取消", null)
                .setPositiveButton("保存", (dialog, which) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty() || name.length() > 40 || name.contains("\n") || name.contains("\r")) {
                        notice.setText("名称须为 1–40 个字符，且不能换行。"); return;
                    }
                    mutate(() -> {
                        int speaker = getStore().addSpeaker(audio, name);
                        getStore().correctSpeaker(audio, segment, speaker);
                    }, "新说话人及片段归属已保存。");
                }).show();
    }

    private String speakerLabel(int id) {
        if (id < 0) return "待确认";
        return speakerLabel(snapshot, id);
    }

    private static String speakerLabel(JSONObject job, int id) {
        if (id < 0) return "待确认";
        JSONObject names = job == null ? null : job.optJSONObject("speakers");
        String name = names == null ? "" : names.optString(String.valueOf(id));
        if (!name.isEmpty()) return name;
        int position = speakerIds(job).indexOf(id);
        return position < 0 ? "说话人（待命名）" : "说话人" + (position + 1);
    }

    private static List<Integer> speakerIds(JSONObject job) {
        List<Integer> result = new ArrayList<>();
        JSONArray segments = job == null ? null : job.optJSONArray("segments");
        if (segments != null) for (int i = 0; i < segments.length(); i++) {
            JSONObject segment = segments.optJSONObject(i);
            if (segment != null) {
                int id = segment.optInt("speaker", -1);
                if (id >= 0 && !result.contains(id)) result.add(id);
            }
        }
        JSONObject names = job == null ? null : job.optJSONObject("speakers");
        List<Integer> additional = new ArrayList<>();
        if (names != null) {
            java.util.Iterator<String> keys = names.keys();
            while (keys.hasNext()) {
                try {
                    int id = Integer.parseInt(keys.next());
                    if (id >= 0 && !result.contains(id) && !additional.contains(id)) additional.add(id);
                } catch (NumberFormatException ignored) {}
            }
        }
        Collections.sort(additional);
        result.addAll(additional);
        return result;
    }

    private void copyAll() {
        if (!alive() || snapshot == null) return;
        final JSONObject job = snapshot;
        copy.setEnabled(false);
        io.execute(() -> {
            JSONArray segments = job.optJSONArray("segments");
            StringBuilder text = new StringBuilder();
            if (segments != null) for (int i = 0; i < segments.length(); i++) {
                JSONObject segment = segments.optJSONObject(i);
                if (segment == null || segment.optString("text").isEmpty()) continue;
                text.append(time(segment.optLong("start_ms"))).append("｜")
                        .append(speakerLabel(job, segment.optInt("speaker", -1))).append("：")
                        .append(segment.optString("text")).append('\n');
            }
            final String copied = text.toString();
            if (destroyed) return;
            main.post(() -> {
                if (!alive()) return;
                try {
                    ClipboardManager clipboard = (ClipboardManager) app.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (clipboard == null) throw new IllegalStateException();
                    clipboard.setPrimaryClip(ClipData.newPlainText("录音转写", copied));
                    notice.setText("转写文字已复制。");
                } catch (RuntimeException e) { notice.setText("复制失败：" + e.getClass().getSimpleName()); }
                copy.setEnabled(!copied.isEmpty());
            });
        });
    }

    private interface StoreAction { void run() throws Exception; }
    private void mutate(StoreAction action, String success) {
        if (!alive() || mutating) return;
        mutating = true; revision++; render();
        io.execute(() -> { String error = null; try { action.run(); } catch (Exception e) { error = "操作失败：" + e.getClass().getSimpleName() + "，原录音保留。"; }
            final String failure = error; if (destroyed) return;
            main.post(() -> { if (!alive()) return; mutating = false; loading = false; fingerprint = ""; notice.setText(failure == null ? success : failure); refresh(); }); });
    }

    void destroy() {
        destroyed = true; revision++; playback = null; owner.clear(); io.shutdown(); main.removeCallbacksAndMessages(null);
        root = null; entries = null; speakers = null; status = null; notice = null;
        request = null; cancel = null; copy = null; snapshot = null;
    }
    private boolean alive() { Activity activity = owner.get(); return !destroyed && activity != null && !activity.isFinishing() && !activity.isDestroyed(); }
    private int dp(int value) { return VoiceUiTheme.dp(app, value); }
    private TextView label(Activity activity, String value, int size) { TextView text = VoiceUiTheme.text(activity, value, size, VoiceUiTheme.MUTED); text.setPadding(0, dp(4), 0, dp(4)); return text; }
    private LinearLayout row(Activity activity) { LinearLayout row = new LinearLayout(activity); row.setGravity(Gravity.CENTER_VERTICAL); return row; }
    private LinearLayout.LayoutParams params(int height) { return new LinearLayout.LayoutParams(-1, dp(height)); }
    private LinearLayout.LayoutParams weighted() { LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(44), 1); params.setMargins(dp(2), 0, dp(2), 0); return params; }
    private static boolean hasText(JSONArray segments) { for (int i = 0; i < segments.length(); i++) { JSONObject segment = segments.optJSONObject(i); if (segment != null && !segment.optString("text").isEmpty()) return true; } return false; }
    private static String trim(String text, int max) { return text.length() > max ? text.substring(0, max) + "…" : text; }
    private static String time(long ms) { long seconds = Math.max(0, ms / 1000); return String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60); }
    private static String stateLabel(String state) {
        switch (state) {
            case "QUEUED": return "等待处理"; case "DECODING": return "正在读取音频"; case "DIARIZING": return "正在区分说话人";
            case "TRANSCRIBING": return "正在转成文字"; case "WAITING_INPUT": return "等待语音输入结束"; case "COMPLETED": return "转写已完成";
            case "FAILED": return "转写未完成"; case "CANCELLING": return "正在停止，等待当前计算结束";
            case "CANCELLED": return "转写已停止"; case "": return "尚未转写"; default: return "状态待核实";
        }
    }
}
