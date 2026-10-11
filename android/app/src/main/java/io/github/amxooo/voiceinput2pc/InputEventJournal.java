package io.github.amxooo.voiceinput2pc;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

/**
 * Durable local journal for input events. The original text is never rewritten by
 * classification, indexing or later model processing.
 *
 * v0.7 foundation: integration with CommitTracker and UI follows separately.
 */
public final class InputEventJournal extends SQLiteOpenHelper {
    private static final String DATABASE = "input_history.db";
    private static final int VERSION = 1;

    public InputEventJournal(Context context) {
        super(context.getApplicationContext(), DATABASE, null, VERSION);
        setWriteAheadLoggingEnabled(true);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE input_events (" +
                "event_id TEXT PRIMARY KEY NOT NULL," +
                "session_id TEXT NOT NULL," +
                "created_at_ms INTEGER NOT NULL," +
                "event_type TEXT NOT NULL," +
                "content TEXT NOT NULL," +
                "device_id TEXT," +
                "target_id TEXT," +
                "message_id TEXT," +
                "revision INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX input_events_time ON input_events(created_at_ms, event_id)");
        db.execSQL("CREATE INDEX input_events_session ON input_events(session_id, created_at_ms)");
        db.execSQL("CREATE INDEX input_events_message ON input_events(message_id)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        throw new IllegalStateException("Input history migration must be explicit; refusing destructive upgrade");
    }

    /** Idempotent insert: retransmission of the same event cannot duplicate history. */
    public boolean append(String eventId, String sessionId, long timestampMs,
                          String eventType, String content, String deviceId,
                          String targetId, String messageId, int revision) {
        if (eventId == null || eventId.isEmpty() || sessionId == null || sessionId.isEmpty()
                || eventType == null || eventType.isEmpty() || content == null
                || timestampMs <= 0 || revision < 0) {
            throw new IllegalArgumentException("Invalid input event");
        }
        ContentValues values = new ContentValues();
        values.put("event_id", eventId);
        values.put("session_id", sessionId);
        values.put("created_at_ms", timestampMs);
        values.put("event_type", eventType);
        values.put("content", content);
        values.put("device_id", deviceId);
        values.put("target_id", targetId);
        values.put("message_id", messageId);
        values.put("revision", revision);
        return getWritableDatabase().insertWithOnConflict(
                "input_events", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1;
    }

    /** Chronological pagination; caller must close the returned Cursor. */
    public Cursor page(long fromInclusiveMs, long toExclusiveMs, int limit, int offset) {
        if (limit < 1 || limit > 1000 || offset < 0 || fromInclusiveMs >= toExclusiveMs) {
            throw new IllegalArgumentException("Invalid query range");
        }
        return getReadableDatabase().query(
                "input_events", null,
                "created_at_ms >= ? AND created_at_ms < ?",
                new String[]{Long.toString(fromInclusiveMs), Long.toString(toExclusiveMs)},
                null, null, "created_at_ms ASC, event_id ASC",
                Integer.toString(offset) + "," + Integer.toString(limit));
    }
}
