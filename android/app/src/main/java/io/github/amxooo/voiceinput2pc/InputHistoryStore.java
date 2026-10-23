package io.github.amxooo.voiceinput2pc;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.JSONObject;
import java.io.Writer;

/** Private, append-only input history. Never stores pairing/session tokens or audio. */
final class InputHistoryStore extends SQLiteOpenHelper {
    InputHistoryStore(Context context) { super(context, "input-history.db", null, 1); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE events (id INTEGER PRIMARY KEY AUTOINCREMENT, created INTEGER NOT NULL, "
            + "logical_session TEXT NOT NULL, revision INTEGER NOT NULL, source TEXT NOT NULL, "
            + "device TEXT NOT NULL, status TEXT NOT NULL, message_id TEXT NOT NULL, seq INTEGER NOT NULL, text TEXT NOT NULL)");
        db.execSQL("CREATE INDEX history_time ON events(created,id)");
        db.execSQL("CREATE INDEX history_revision ON events(logical_session,revision)");
    }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        throw new IllegalStateException("History migration not defined");
    }
    synchronized void record(String logicalSession, String source, String device,
                             String status, String messageId, long seq, String text) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            // An explicit ID describes an event, not its text. Keep two identical
            // real commits, but ignore a replay even if another event intervened.
            if (!messageId.isEmpty()) {
                try (Cursor duplicate = db.rawQuery("SELECT 1 FROM events WHERE logical_session=? "
                        + "AND source=? AND device=? AND status=? AND message_id=? AND seq=? AND text=? LIMIT 1",
                        new String[]{logicalSession, source, device, status, messageId, Long.toString(seq), text})) {
                    if (duplicate.moveToFirst()) { db.setTransactionSuccessful(); return; }
                }
            }
            int revision = 1;
            try (Cursor last = db.rawQuery("SELECT revision,text,status,message_id,seq FROM events "
                    + "WHERE logical_session=? ORDER BY id DESC LIMIT 1", new String[]{logicalSession})) {
                if (last.moveToFirst()) {
                    if (messageId.isEmpty() && text.equals(last.getString(1)) && status.equals(last.getString(2))
                            && messageId.equals(last.getString(3)) && seq == last.getLong(4)) {
                        db.setTransactionSuccessful(); return;
                    }
                    revision = last.getInt(0) + 1;
                }
            }
            ContentValues value = new ContentValues();
            value.put("created", System.currentTimeMillis()); value.put("logical_session", logicalSession);
            value.put("revision", revision); value.put("source", source); value.put("device", device);
            value.put("status", status); value.put("message_id", messageId); value.put("seq", seq); value.put("text", text);
            db.insertOrThrow("events", null, value); db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
    synchronized Cursor search(String keyword, long since, long until, long beforeId, int pageSize) {
        return search(keyword,since,until,beforeId,pageSize,"");
    }
    synchronized Cursor search(String keyword,long since,long until,long beforeId,int pageSize,String group) {
        if (since < 0 || until < since || beforeId < 1 || pageSize < 1 || pageSize > 200)
            throw new IllegalArgumentException("Invalid history query");
        return getReadableDatabase().rawQuery("SELECT * FROM events WHERE instr(text,?)>0 "
            + "AND created>=? AND created<=? AND id<?" + sourceFilter(group) + " ORDER BY id DESC LIMIT ?",
            new String[]{keyword, Long.toString(since), Long.toString(until), Long.toString(beforeId), Integer.toString(pageSize)});
    }
    /** Saved events include snapshots, revisions and delivery failures, not net typing. */
    static final class Summary {
        final long recordCount, codePointCount;
        Summary(long recordCount, long codePointCount) {
            this.recordCount = recordCount; this.codePointCount = codePointCount;
        }
    }
    synchronized Summary summary(String keyword, long since, long until) {
        return summary(keyword,since,until,"");
    }
    synchronized Summary summary(String keyword,long since,long until,String group) {
        if (since < 0 || until < since) throw new IllegalArgumentException("Invalid history range");
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*),COALESCE(SUM(length(text)),0) "
                + "FROM events WHERE instr(text,?)>0 AND created>=? AND created<=?" + sourceFilter(group),
                new String[]{keyword, Long.toString(since), Long.toString(until)})) {
            c.moveToFirst(); return new Summary(c.getLong(0), c.getLong(1));
        }
    }
    private static String sourceFilter(String group) {
        if(group==null || group.isEmpty())return "";
        switch(group) {
            case "voice":return " AND source='ime_voice'";
            case "keyboard":return " AND source IN ('ime_keyboard','ime_pinyin','ime_t9','ime_local')";
            case "computer":return " AND source IN ('ime_remote','phone_ime','pc_clipboard')";
            default:throw new IllegalArgumentException("Invalid source group");
        }
    }
    static String sourceLabel(String source) {
        if (source == null) return "其他输入";
        switch (source) {
            case "ime_keyboard": return "键盘输入";
            case "ime_pinyin": return "拼音输入";
            case "ime_t9": return "九键输入";
            case "ime_voice": return "语音输入";
            case "ime_local": return "本机输入";
            case "ime_remote": return "输入法发送到电脑";
            case "phone_ime": return "电脑输入工作区";
            case "pc_clipboard": return "电脑剪贴板";
            default: return "其他输入";
        }
    }
    static String statusLabel(String status) {
        if (status == null) return "已保存记录";
        switch (status.toUpperCase(java.util.Locale.ROOT)) {
            case "INSERTED": case "LOCAL_COMMITTED": return "已输入";
            case "LOCAL": return "已保存本机记录";
            case "SAVED_NOT_INSERTED": return "已保存，尚未输入";
            case "REJECTED": return "发送被拒绝";
            case "DELIVERY_UNKNOWN": return "发送结果待确认";
            case "QUEUED": return "等待发送";
            case "EDIT_CONFLICT": return "修改待确认";
            case "ACTIVE": return "输入中保存的草稿";
            case "PAUSED": return "已暂停的草稿";
            case "ACKNOWLEDGED": return "电脑已确认接收";
            default: return "已保存记录";
        }
    }
    int export(Writer out, String format, String keyword, long since, long until) throws Exception {
        return export(out,format,keyword,since,until,"");
    }
    int export(Writer out,String format,String keyword,long since,long until,String group) throws Exception {
        sourceFilter(group);
        if (!(format.equals("txt") || format.equals("md") || format.equals("json") || format.equals("csv"))
                || since < 0 || until < since) throw new IllegalArgumentException("Invalid history export");
        long snapshotId;
        try(Cursor newest=getReadableDatabase().rawQuery("SELECT COALESCE(MAX(id),0) FROM events",null)) {
            newest.moveToFirst();snapshotId=newest.getLong(0);
        }
        int count = 0;
        if (format.equals("json")) out.write("[");
        if (format.equals("csv")) out.write("id,created,logical_session,revision,source,device,status,message_id,seq,text\r\n");
        try (Cursor c = getReadableDatabase().rawQuery("SELECT * FROM events WHERE instr(text,?)>0 "
                + "AND created>=? AND created<=? AND id<=?"+sourceFilter(group)+" ORDER BY id", new String[]{keyword, Long.toString(since), Long.toString(until),Long.toString(snapshotId)})) {
            while (c.moveToNext()) {
                JSONObject item = new JSONObject();
                for (int i=0; i<c.getColumnCount(); i++) {
                    if (c.getType(i) == Cursor.FIELD_TYPE_INTEGER) item.put(c.getColumnName(i), c.getLong(i));
                    else item.put(c.getColumnName(i), c.getString(i));
                }
                if (format.equals("json")) out.write((count == 0 ? "" : ",") + item.toString());
                else if (format.equals("csv")) {
                    for (int i=0; i<c.getColumnCount(); i++) {
                        if (i>0) out.write(",");
                        String value=c.getString(i);
                        if (c.getType(i) != Cursor.FIELD_TYPE_INTEGER && !value.isEmpty()
                                && "=+-@\t\r".indexOf(value.charAt(0))>=0) value="'"+value;
                        out.write("\""+value.replace("\"", "\"\"")+"\"");
                    }
                    out.write("\r\n");
                } else {
                    String stamp = java.time.Instant.ofEpochMilli(item.getLong("created")).toString();
                    out.write((format.equals("md") ? "\n## " : "") + stamp + " · " + statusLabel(item.getString("status")) + "\n");
                    out.write("来源："+sourceLabel(item.getString("source"))+" · 修订："+item.getLong("revision")+"\n");
                    String text=item.getString("text");
                    if (format.equals("md")) for (String line:text.split("\n",-1)) out.write("    "+line+"\n");
                    else out.write(text+"\n\n");
                }
                count++;
            }
        }
        if (format.equals("json")) out.write("]\n");
        out.flush(); return count;
    }
}
