package io.github.amxooo.voiceinput2pc;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Activity-hosted history: private SQLite, bounded pages, permission-free SAF export. */
final class HistoryScreen {
    static final int EXPORT_REQUEST = 2707;
    private static final int PAGE_SIZE = 50;
    private final Activity activity;
    private final InputHistoryStore store;
    private final Runnable returnToInput;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final int ink = Color.rgb(48, 52, 47), green = Color.rgb(34, 105, 84);
    private EditText keyword, from, to;
    private TextView summary;
    private LinearLayout entries;
    private ScrollView scroll;
    private Button previous, next, export;
    private boolean visible, destroyed, loading, exporting;
    private int generation;
    private String query = "", pendingFormat, pendingKeyword;
    private String restoredKeyword, restoredFrom, restoredTo;
    private HistoryDateRange range = HistoryDateRange.parse("", ""), pendingRange;
    private final List<Long> cursors = new ArrayList<>();
    private int page;
    private long lastId;
    private boolean hasNext;

    HistoryScreen(Activity activity, InputHistoryStore store, Runnable returnToInput) {
        this.activity = activity; this.store = store; this.returnToInput = returnToInput;
    }
    private int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
    private TextView text(String value, int size, int color) {
        TextView v = new TextView(activity); v.setText(value); v.setTextSize(size); v.setTextColor(color); return v;
    }
    private Button button(String value) { return VoiceUiTheme.button(activity,value,false); }
    private EditText field(String hint) {
        EditText v = new EditText(activity); v.setSingleLine(true); v.setTextSize(14); v.setHint(hint);
        v.setTextColor(VoiceUiTheme.INK); v.setHintTextColor(Color.rgb(139,132,123));
        v.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        v.setPadding(dp(12),0,dp(12),0);
        v.setBackground(VoiceUiTheme.surface(activity,Color.WHITE,VoiceUiTheme.BORDER,12));
        return v;
    }
    private LinearLayout card() {
        LinearLayout value=new LinearLayout(activity);value.setOrientation(LinearLayout.VERTICAL);
        value.setPadding(dp(14),dp(12),dp(14),dp(12));
        value.setBackground(VoiceUiTheme.surface(activity,VoiceUiTheme.SURFACE,VoiceUiTheme.BORDER,16));
        return value;
    }
    boolean isVisible() { return visible; }
    void saveState(Bundle state) {
        state.putBoolean("history.visible", visible);
        state.putString("history.query", query);
        state.putLong("history.since", range.since); state.putLong("history.until", range.until);
        state.putString("history.keywordDraft", keyword == null ? restoredKeyword : keyword.getText().toString());
        state.putString("history.fromDraft", from == null ? restoredFrom : from.getText().toString());
        state.putString("history.toDraft", to == null ? restoredTo : to.getText().toString());
        state.putString("history.pendingFormat", pendingFormat);
        state.putString("history.pendingKeyword", pendingKeyword);
        if (pendingRange != null) {
            state.putLong("history.pendingSince", pendingRange.since); state.putLong("history.pendingUntil", pendingRange.until);
        }
        long[] savedCursors = new long[cursors.size()];
        for (int i = 0; i < cursors.size(); i++) savedCursors[i] = cursors.get(i);
        state.putLongArray("history.cursors", savedCursors); state.putInt("history.page", page);
    }
    void restoreState(Bundle state) {
        if (state == null) return;
        visible = state.getBoolean("history.visible", false);
        query = state.getString("history.query", "");
        range = HistoryDateRange.bounds(state.getLong("history.since", 0), state.getLong("history.until", Long.MAX_VALUE));
        restoredKeyword = state.getString("history.keywordDraft");
        restoredFrom = state.getString("history.fromDraft"); restoredTo = state.getString("history.toDraft");
        pendingFormat = state.getString("history.pendingFormat"); pendingKeyword = state.getString("history.pendingKeyword", "");
        if (pendingFormat != null) pendingRange = HistoryDateRange.bounds(state.getLong("history.pendingSince", 0), state.getLong("history.pendingUntil", Long.MAX_VALUE));
        long[] savedCursors = state.getLongArray("history.cursors"); cursors.clear();
        if (savedCursors != null) for (long value : savedCursors) if (value > 0) cursors.add(value);
        page = Math.max(0, Math.min(state.getInt("history.page", 0), cursors.size() - 1));
    }
    void show() {
        if (destroyed) return;
        visible = true;
        LinearLayout root = new LinearLayout(activity); root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(VoiceUiTheme.PAPER);
        root.setPadding(dp(12), dp(8), dp(12), dp(8));
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int left, top, right, bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                android.graphics.Insets ime = insets.getInsets(WindowInsets.Type.ime());
                left = bars.left; top = bars.top; right = bars.right; bottom = Math.max(bars.bottom, ime.bottom);
            } else {
                left = insets.getSystemWindowInsetLeft(); top = insets.getSystemWindowInsetTop();
                right = insets.getSystemWindowInsetRight(); bottom = insets.getSystemWindowInsetBottom();
            }
            v.setPadding(dp(12) + left, dp(8) + top, dp(12) + right, dp(8) + bottom); return insets;
        });
        LinearLayout top = new LinearLayout(activity); top.setGravity(Gravity.CENTER_VERTICAL);
        Button back = button("返回"); back.setContentDescription("返回电脑输入，保留当前草稿");
        back.setOnClickListener(v -> onBackPressed()); top.addView(back,new LinearLayout.LayoutParams(dp(64),dp(40)));
        TextView title = VoiceUiTheme.title(activity,"输入历史",25);
        LinearLayout.LayoutParams titleParams=new LinearLayout.LayoutParams(0,-2,1);titleParams.leftMargin=dp(10);
        top.addView(title,titleParams);
        export = button("导出"); export.setEnabled(!exporting); export.setOnClickListener(v -> chooseExport());
        top.addView(export,new LinearLayout.LayoutParams(dp(64),dp(40))); root.addView(top);
        TextView hint = VoiceUiTheme.text(activity,"本机保存的稳定文字与修订记录",13,VoiceUiTheme.MUTED);
        hint.setPadding(0, dp(8), 0, dp(12)); root.addView(hint);

        LinearLayout filters=card();
        TextView filterTitle=VoiceUiTheme.text(activity,"查找记录",17,VoiceUiTheme.INK);
        filterTitle.setTypeface(Typeface.DEFAULT,Typeface.BOLD);filters.addView(filterTitle);
        keyword = field("搜索文字"); keyword.setId(2701); keyword.setText(restoredKeyword == null ? query : restoredKeyword); restoredKeyword = null;
        LinearLayout.LayoutParams keywordParams=new LinearLayout.LayoutParams(-1,dp(46));keywordParams.topMargin=dp(10);filters.addView(keyword,keywordParams);
        LinearLayout dates = new LinearLayout(activity);
        from = field("开始日期"); from.setId(2702); to = field("结束日期"); to.setId(2703);
        if (range.since != 0) from.setText(localDate(range.since));
        if (range.until != Long.MAX_VALUE) to.setText(localDate(range.until));
        if (restoredFrom != null) { from.setText(restoredFrom); restoredFrom = null; }
        if (restoredTo != null) { to.setText(restoredTo); restoredTo = null; }
        LinearLayout.LayoutParams dateLeft=new LinearLayout.LayoutParams(0,dp(44),1);dateLeft.rightMargin=dp(6);
        LinearLayout.LayoutParams dateRight=new LinearLayout.LayoutParams(0,dp(44),1);dateRight.leftMargin=dp(6);
        dates.addView(from,dateLeft);dates.addView(to,dateRight);
        LinearLayout.LayoutParams dateParams=new LinearLayout.LayoutParams(-1,-2);dateParams.topMargin=dp(8);filters.addView(dates,dateParams);
        Button search = VoiceUiTheme.button(activity,"查询",true); search.setId(2704); search.setOnClickListener(v -> search());
        LinearLayout.LayoutParams searchParams=new LinearLayout.LayoutParams(-1,dp(46));searchParams.topMargin=dp(10);filters.addView(search,searchParams);
        LinearLayout.LayoutParams filterParams=new LinearLayout.LayoutParams(-1,-2);filterParams.bottomMargin=dp(10);root.addView(filters,filterParams);

        summary = VoiceUiTheme.text(activity,"",13,VoiceUiTheme.MUTED); summary.setPadding(dp(4),0,0,dp(8)); root.addView(summary);
        scroll = new ScrollView(activity); scroll.setFillViewport(true);
        entries = new LinearLayout(activity); entries.setOrientation(LinearLayout.VERTICAL); scroll.addView(entries);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout pager = new LinearLayout(activity); pager.setGravity(Gravity.CENTER);
        previous = button("上一页"); previous.setId(2705); previous.setOnClickListener(v -> { if (!loading && page > 0) { page--; loadPage(); } });
        next = button("下一页"); next.setId(2706); next.setOnClickListener(v -> {
            if (!loading && hasNext) { page++; if (cursors.size() <= page) cursors.add(lastId); else cursors.set(page, lastId); loadPage(); }
        });
        LinearLayout.LayoutParams previousParams=new LinearLayout.LayoutParams(0,dp(44),1);previousParams.rightMargin=dp(5);
        LinearLayout.LayoutParams nextParams=new LinearLayout.LayoutParams(0,dp(44),1);nextParams.leftMargin=dp(5);
        pager.addView(previous,previousParams);pager.addView(next,nextParams);
        LinearLayout.LayoutParams pagerParams=new LinearLayout.LayoutParams(-1,-2);pagerParams.topMargin=dp(8);root.addView(pager,pagerParams);
        activity.setContentView(root); root.requestApplyInsets();
        if (cursors.isEmpty()) cursors.add(Long.MAX_VALUE);
        loadPage();
    }
    private String localDate(long time) { return Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).toLocalDate().toString(); }
    private boolean readFilters() {
        try {
            HistoryDateRange chosen = HistoryDateRange.parse(from.getText().toString(), to.getText().toString());
            String chosenKeyword = keyword.getText().toString();
            if (!chosenKeyword.equals(query) || chosen.since != range.since || chosen.until != range.until) {
                query = chosenKeyword; range = chosen; page = 0; cursors.clear(); cursors.add(Long.MAX_VALUE);
            }
            return true;
        } catch (RuntimeException e) { summary.setText("请输入有效日期 YYYY-MM-DD，开始日期须早于结束日期"); return false; }
    }
    private void search() {
        if (!readFilters()) return;
        page = 0; cursors.clear(); cursors.add(Long.MAX_VALUE);
        InputMethodManager keyboard = (InputMethodManager) activity.getSystemService(Activity.INPUT_METHOD_SERVICE);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(keyword.getWindowToken(), 0);
        loadPage();
    }
    private void loadPage() {
        int ticket = ++generation; loading = true; previous.setEnabled(false); next.setEnabled(false);
        summary.setText("正在读取历史…");
        final String term = query; final HistoryDateRange selected = range; final long before = cursors.get(page);
        worker.execute(() -> {
            List<String[]> rows = new ArrayList<>(); long tail = before; boolean more = false; String failure = null;
            try (Cursor c = store.search(term, selected.since, selected.until, before, PAGE_SIZE + 1)) {
                while (c.moveToNext()) {
                    if (rows.size() == PAGE_SIZE) { more = true; break; }
                    tail = c.getLong(c.getColumnIndexOrThrow("id"));
                    String[] row = new String[8]; String[] columns = {"created","source","logical_session","revision","device","status","text","seq"};
                    for (int i = 0; i < columns.length; i++) row[i] = c.getString(c.getColumnIndexOrThrow(columns[i]));
                    rows.add(row);
                }
            } catch (RuntimeException e) { failure = "读取失败，请重试：" + e.getClass().getSimpleName(); }
            final long end = tail; final boolean remaining = more; final String error = failure;
            main.post(() -> {
                if (destroyed || !visible || generation != ticket) return;
                loading = false; entries.removeAllViews(); lastId = end; hasNext = remaining;
                previous.setEnabled(page > 0); next.setEnabled(remaining);
                if (error != null) { summary.setText(error); return; }
                summary.setText(rows.isEmpty() ? "没有符合条件的记录" : "第 " + (page + 1) + " 页 · " + rows.size() + " 条记录");
                for (String[] row : rows) addEntry(row);
                scroll.scrollTo(0,0);
            });
        });
    }
    private void addEntry(String[] row) {
        LinearLayout card = new LinearLayout(activity); card.setOrientation(LinearLayout.VERTICAL); card.setPadding(dp(12),dp(10),dp(12),dp(12));
        card.setBackground(VoiceUiTheme.surface(activity,VoiceUiTheme.SURFACE,VoiceUiTheme.BORDER,14));
        String time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(Long.parseLong(row[0])));
        TextView stamp = text(time + " · " + row[5], 12, VoiceUiTheme.GREEN); card.addView(stamp);
        TextView meta = text("来源 " + row[1] + " · 修订 " + row[3] + " · 序号 " + row[7] + "\n会话 " + row[2] + "\n设备 " + (row[4].isEmpty() ? "未指定" : row[4]),11,VoiceUiTheme.MUTED);
        meta.setPadding(0,dp(6),0,dp(8)); card.addView(meta);
        TextView body = text(row[6],16,ink); body.setTextIsSelectable(true); card.addView(body);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1,-2); params.bottomMargin = dp(8); entries.addView(card,params);
    }
    private void chooseExport() {
        if (exporting || pendingFormat != null || !readFilters()) return;
        loadPage();
        String[] formats = {"txt","md","json","csv"};
        new AlertDialog.Builder(activity).setTitle("导出全部查询结果")
            .setItems(new String[]{"TXT 纯文本","Markdown 文档","JSON 数据","CSV 表格"}, (dialog, which) -> {
                pendingFormat = formats[which]; pendingKeyword = query; pendingRange = range;
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType(which == 2 ? "application/json" : which == 3 ? "text/csv" : "text/plain");
                intent.putExtra(Intent.EXTRA_TITLE,"input-history-" + localDate(System.currentTimeMillis()) + "." + pendingFormat);
                try { activity.startActivityForResult(intent, EXPORT_REQUEST); }
                catch (RuntimeException e) { pendingFormat = null; summary.setText("无法打开文件选择器：" + e.getClass().getSimpleName()); }
            }).setNegativeButton("取消",null).show();
    }
    boolean handleActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != EXPORT_REQUEST) return false;
        if (destroyed) return true;
        if (pendingFormat == null) return true;
        String format = pendingFormat, term = pendingKeyword; HistoryDateRange selected = pendingRange;
        pendingFormat = null;
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) return true;
        android.net.Uri uri = data.getData(); exporting = true;
        if (visible) { export.setEnabled(false); summary.setText("正在导出全部查询结果…"); }
        worker.execute(() -> {
            String message;
            try (OutputStream stream = activity.getContentResolver().openOutputStream(uri,"wt")) {
                if (stream == null) throw new java.io.IOException("无法打开目的文件");
                try (OutputStreamWriter writer = new OutputStreamWriter(stream, StandardCharsets.UTF_8)) {
                    int count = store.export(writer,format,term,selected.since,selected.until);
                    message = "已导出 " + count + " 条记录（" + format.toUpperCase(java.util.Locale.ROOT) + "）";
                }
            } catch (Exception e) { message = "导出失败，目的文件可能不完整，请重新导出：" + e.getClass().getSimpleName(); }
            final String result = message;
            main.post(() -> { exporting = false; if (!destroyed) { if (visible) { export.setEnabled(true); summary.setText(result); } Toast.makeText(activity,result,Toast.LENGTH_LONG).show(); } });
        });
        return true;
    }
    boolean onBackPressed() {
        if (!visible) return false;
        visible = false; generation++;
        returnToInput.run(); return true;
    }
    void destroy() { destroyed = true; visible = false; generation++; worker.shutdown(); }
}
