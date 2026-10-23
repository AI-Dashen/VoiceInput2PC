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
    private static final java.util.concurrent.atomic.AtomicBoolean EXPORT_BUSY = new java.util.concurrent.atomic.AtomicBoolean();
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
    private boolean embedded,stateRestored;
    private String sourceGroup="",pendingSource="";
    private LinearLayout embeddedDates;
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
        state.putString("history.sourceGroup",sourceGroup);state.putString("history.pendingSource",pendingSource);
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
        stateRestored=true;
        visible = state.getBoolean("history.visible", false);
        sourceGroup=state.getString("history.sourceGroup","");pendingSource=state.getString("history.pendingSource","");
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
        Button back = button("返回"); back.setContentDescription("返回输入，保留当前草稿");
        back.setOnClickListener(v -> onBackPressed()); top.addView(back,new LinearLayout.LayoutParams(dp(64),dp(40)));
        TextView title = VoiceUiTheme.title(activity,"输入历史",25);
        LinearLayout.LayoutParams titleParams=new LinearLayout.LayoutParams(0,-2,1);titleParams.leftMargin=dp(10);
        top.addView(title,titleParams);
        export = button("导出"); export.setEnabled(!exporting); export.setOnClickListener(v -> chooseExport());
        top.addView(export,new LinearLayout.LayoutParams(dp(64),dp(40))); root.addView(top);
        TextView hint = VoiceUiTheme.text(activity,"本机保存的输入片段与修订记录；条数和文字量含草稿及发送状态记录，不代表净输入量。",13,VoiceUiTheme.MUTED);
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
    /** The same DAO, pager and export pipeline embedded in the launcher page. */
    View createEmbedded() {
        embedded=true;visible=true;scroll=null;
        if(!stateRestored)range=HistoryDateRange.parse(java.time.LocalDate.now().toString(),"");
        LinearLayout root=new LinearLayout(activity);root.setOrientation(LinearLayout.VERTICAL);
        LinearLayout heading=new LinearLayout(activity);heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.addView(VoiceUiTheme.title(activity,"记录与统计",18),new LinearLayout.LayoutParams(0,-2,1));
        export=button("导出");export.setId(2707);export.setOnClickListener(v -> chooseExport());
        heading.addView(export,new LinearLayout.LayoutParams(dp(64),dp(44)));root.addView(heading);
        LinearLayout ranges=new LinearLayout(activity);
        String[] labels={"今天","本周","累计","日期"};
        for(int i=0;i<labels.length;i++) {
            final int choice=i;Button pick=button(labels[i]);pick.setId(2720+i);
            if(i==0 && !stateRestored){pick.setSelected(true);pick.setBackground(VoiceUiTheme.surface(activity,VoiceUiTheme.SOFT_GREEN,0,8));}
            pick.setOnClickListener(v -> {
                if(choice==3){embeddedDates.setVisibility(embeddedDates.getVisibility()==View.VISIBLE?View.GONE:View.VISIBLE);return;}
                java.time.LocalDate date=java.time.LocalDate.now();
                if(choice==1)date=date.minusDays(date.getDayOfWeek().getValue()-1);
                from.setText(choice==2?"":date.toString());to.setText("");
                for(int n=0;n<3;n++){View child=ranges.getChildAt(n);child.setSelected(n==choice);child.setBackground(VoiceUiTheme.surface(activity,n==choice?VoiceUiTheme.SOFT_GREEN:VoiceUiTheme.SURFACE,0,8));}
                search();
            });
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(44),1);lp.setMargins(dp(2),dp(2),dp(2),dp(2));ranges.addView(pick,lp);
        }
        root.addView(ranges);
        summary=text("正在读取记录…",16,VoiceUiTheme.INK);summary.setId(2710);summary.setPadding(dp(2),dp(10),dp(2),dp(2));root.addView(summary);
        TextView note=text("记录文字量含草稿与修订，非净输入量",12,VoiceUiTheme.MUTED);note.setPadding(dp(2),0,0,dp(10));root.addView(note);
        keyword=field("搜索记录内容");keyword.setId(2701);keyword.setText(restoredKeyword==null?query:restoredKeyword);restoredKeyword=null;
        LinearLayout queryRow=new LinearLayout(activity);queryRow.addView(keyword,new LinearLayout.LayoutParams(0,dp(44),1));
        Button search=button("查找");search.setId(2704);search.setOnClickListener(v -> search());queryRow.addView(search,new LinearLayout.LayoutParams(dp(64),dp(44)));root.addView(queryRow);
        embeddedDates=new LinearLayout(activity);embeddedDates.setOrientation(LinearLayout.VERTICAL);
        LinearLayout dates=new LinearLayout(activity);from=field("开始 YYYY-MM-DD");from.setId(2702);to=field("结束 YYYY-MM-DD");to.setId(2703);
        from.setText(restoredFrom!=null?restoredFrom:range.since==0?"":localDate(range.since));restoredFrom=null;
        to.setText(restoredTo!=null?restoredTo:range.until==Long.MAX_VALUE?"":localDate(range.until));restoredTo=null;
        dates.addView(from,new LinearLayout.LayoutParams(0,dp(44),1));dates.addView(to,new LinearLayout.LayoutParams(0,dp(44),1));embeddedDates.addView(dates);
        TextView dateHint=text("日期筛选也作用于统计和导出；留空表示不限。",12,VoiceUiTheme.MUTED);embeddedDates.addView(dateHint);
        embeddedDates.setVisibility(View.GONE);root.addView(embeddedDates);
        LinearLayout sources=new LinearLayout(activity);String[] sourceLabels={"全部","语音","键盘","电脑"},groups={"","voice","keyboard","computer"};
        for(int i=0;i<groups.length;i++){final String group=groups[i];Button filter=button(sourceLabels[i]);filter.setId(2730+i);filter.setSelected(group.equals(sourceGroup));if(group.equals(sourceGroup))filter.setBackground(VoiceUiTheme.surface(activity,VoiceUiTheme.SOFT_GREEN,0,8));filter.setOnClickListener(v->{sourceGroup=group;for(int n=0;n<sources.getChildCount();n++)sources.getChildAt(n).setBackground(VoiceUiTheme.surface(activity,groups[n].equals(group)?VoiceUiTheme.SOFT_GREEN:VoiceUiTheme.SURFACE,0,8));search();});sources.addView(filter,new LinearLayout.LayoutParams(0,dp(44),1));}root.addView(sources);
        entries=new LinearLayout(activity);entries.setOrientation(LinearLayout.VERTICAL);root.addView(entries);
        LinearLayout pager=new LinearLayout(activity);previous=button("上一页");previous.setId(2705);next=button("更多记录");next.setId(2706);
        previous.setOnClickListener(v->{if(!loading&&page>0){page--;loadPage();}});
        next.setOnClickListener(v->{if(!loading&&hasNext){page++;if(cursors.size()<=page)cursors.add(lastId);else cursors.set(page,lastId);loadPage();}});
        pager.addView(previous,new LinearLayout.LayoutParams(0,dp(44),1));pager.addView(next,new LinearLayout.LayoutParams(0,dp(44),1));root.addView(pager);
        if(cursors.isEmpty())cursors.add(Long.MAX_VALUE);loadPage();return root;
    }
    void refreshEmbedded(){if(embedded&&!destroyed)loadPage();}
    void closeStoreWhenIdle(){destroyed=true;visible=false;generation++;worker.execute(store::close);worker.shutdown();}
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
        final String term = query; final String group=sourceGroup; final HistoryDateRange selected = range; final long before = cursors.get(page);
        worker.execute(() -> {
            List<String[]> rows = new ArrayList<>(); long tail = before; boolean more = false; String failure = null;
            InputHistoryStore.Summary totals = null;
            try (Cursor c = store.search(term, selected.since, selected.until, before, (embedded?3:PAGE_SIZE) + 1,group)) {
                while (c.moveToNext()) {
                    if (rows.size() == (embedded?3:PAGE_SIZE)) { more = true; break; }
                    tail = c.getLong(c.getColumnIndexOrThrow("id"));
                    String[] row = new String[8]; String[] columns = {"created","source","logical_session","revision","device","status","text","seq"};
                    for (int i = 0; i < columns.length; i++) row[i] = c.getString(c.getColumnIndexOrThrow(columns[i]));
                    rows.add(row);
                }
                totals = store.summary(term, selected.since, selected.until,group);
            } catch (RuntimeException e) { failure = "读取失败，请重试：" + e.getClass().getSimpleName(); }
            final long end = tail; final boolean remaining = more; final String error = failure;
            final InputHistoryStore.Summary matched = totals;
            main.post(() -> {
                if (destroyed || !visible || generation != ticket) return;
                loading = false; entries.removeAllViews(); lastId = end; hasNext = remaining;
                previous.setEnabled(page > 0); next.setEnabled(remaining);
                if (error != null) { summary.setText(error); return; }
                summary.setText(embedded ? matched.codePointCount+" 字符    ·    "+matched.recordCount+" 条记录" : rows.isEmpty() ? "没有符合条件的记录" : "第 " + (page + 1) + " 页 · 共 " + matched.recordCount + " 条记录 · 记录文字量 " + matched.codePointCount + " 字符");
                if(embedded && rows.isEmpty())entries.addView(text("暂无符合条件的记录，可切换日期或清除搜索。",14,VoiceUiTheme.MUTED));
                for (String[] row : rows) addEntry(row);
                if(scroll!=null)scroll.scrollTo(0,0);
            });
        });
    }
    private void addEntry(String[] row) {
        LinearLayout item=new LinearLayout(activity);item.setGravity(Gravity.CENTER_VERTICAL);item.setPadding(dp(2),dp(8),0,dp(8));item.setMinimumHeight(dp(64));
        LinearLayout copyBlock=new LinearLayout(activity);copyBlock.setOrientation(LinearLayout.VERTICAL);
        String time=DateTimeFormatter.ofPattern(embedded?"MM-dd HH:mm":"yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(Long.parseLong(row[0])));
        TextView stamp=text(time+" · "+InputHistoryStore.sourceLabel(row[1])+" · "+InputHistoryStore.statusLabel(row[5]),12,VoiceUiTheme.MUTED);copyBlock.addView(stamp);
        TextView body=text(row[6],15,VoiceUiTheme.INK);body.setMaxLines(embedded?2:Integer.MAX_VALUE);body.setEllipsize(android.text.TextUtils.TruncateAt.END);body.setPadding(0,dp(4),dp(4),0);
        body.setContentDescription("展开记录全文");body.setOnClickListener(v->{boolean expanded=body.getMaxLines()>2;body.setMaxLines(expanded?2:Integer.MAX_VALUE);body.setTextIsSelectable(!expanded);body.setContentDescription(expanded?"展开记录全文":"记录全文，可选择复制");});
        copyBlock.addView(body);item.addView(copyBlock,new LinearLayout.LayoutParams(0,-2,1));
        ImeIconButton copy=new ImeIconButton(activity,"copy","复制记录");copy.setOnClickListener(v->{android.content.ClipboardManager clip=(android.content.ClipboardManager)activity.getSystemService(Activity.CLIPBOARD_SERVICE);clip.setPrimaryClip(android.content.ClipData.newPlainText("输入记录",row[6]));Toast.makeText(activity,"已复制",Toast.LENGTH_SHORT).show();});
        item.addView(copy,new LinearLayout.LayoutParams(dp(44),dp(44)));entries.addView(item,new LinearLayout.LayoutParams(-1,-2));
        View divider=new View(activity);divider.setBackgroundColor(VoiceUiTheme.BORDER);entries.addView(divider,new LinearLayout.LayoutParams(-1,dp(1)));
    }
    private void chooseExport() {
        if (EXPORT_BUSY.get()) { summary.setText("已有导出正在进行，请稍候。"); return; }
        if (exporting || pendingFormat != null || !readFilters()) return;
        loadPage();
        String[] formats = {"txt","md","json","csv"};
        new AlertDialog.Builder(activity).setTitle("导出全部查询结果")
            .setItems(new String[]{"TXT 纯文本","Markdown 文档","JSON 数据","CSV 表格"}, (dialog, which) -> {
                pendingFormat = formats[which]; pendingKeyword = query; pendingRange = range;pendingSource=sourceGroup;
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
        String format = pendingFormat, term = pendingKeyword, group=pendingSource; HistoryDateRange selected = pendingRange;
        pendingFormat = null;
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) return true;
        if (!EXPORT_BUSY.compareAndSet(false,true)) { summary.setText("已有导出正在进行，请稍候。"); return true; }
        android.net.Uri uri = data.getData(); exporting = true;
        if (visible) { export.setEnabled(false); summary.setText("正在导出全部查询结果…"); }
        worker.execute(() -> {
            String message;
            try (OutputStream stream = activity.getContentResolver().openOutputStream(uri,"wt")) {
                if (stream == null) throw new java.io.IOException("无法打开目的文件");
                try (OutputStreamWriter writer = new OutputStreamWriter(stream, StandardCharsets.UTF_8)) {
                    int count = store.export(writer,format,term,selected.since,selected.until,group);
                    message = "已导出 " + count + " 条记录（" + format.toUpperCase(java.util.Locale.ROOT) + "）";
                }
            } catch (Exception e) { message = "导出失败，目的文件可能不完整，请重新导出：" + e.getClass().getSimpleName(); }
            EXPORT_BUSY.set(false);
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
