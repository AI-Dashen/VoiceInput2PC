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
            int revision = 1;
            try (Cursor last = db.rawQuery("SELECT revision,text,status,message_id,seq FROM events "
                    + "WHERE logical_session=? ORDER BY id DESC LIMIT 1", new String[]{logicalSession})) {
                if (last.moveToFirst()) {
                    if (text.equals(last.getString(1)) && status.equals(last.getString(2))
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
        if (since < 0 || until < since || beforeId < 1 || pageSize < 1 || pageSize > 200)
            throw new IllegalArgumentException("Invalid history query");
        return getReadableDatabase().rawQuery("SELECT * FROM events WHERE instr(text,?)>0 "
            + "AND created>=? AND created<=? AND id<? ORDER BY id DESC LIMIT ?",
            new String[]{keyword, Long.toString(since), Long.toString(until), Long.toString(beforeId), Integer.toString(pageSize)});
    }
    int export(Writer out, String format, String keyword, long since, long until) throws Exception {
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
                + "AND created>=? AND created<=? AND id<=? ORDER BY id", new String[]{keyword, Long.toString(since), Long.toString(until),Long.toString(snapshotId)})) {
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
                    out.write((format.equals("md") ? "\n## " : "") + stamp + " · " + item.getString("status") + "\n");
                    out.write("来源："+item.getString("source")+" · 会话："+item.getString("logical_session")
                        +" · 修订："+item.getLong("revision")+" · 设备："+item.getString("device")
                        +" · 消息："+item.getString("message_id")+" · 顺序："+item.getLong("seq")+"\n");
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
