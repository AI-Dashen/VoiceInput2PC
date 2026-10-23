package io.github.amxooo.voiceinput2pc;

import android.database.Cursor;
import android.test.AndroidTestCase;
import android.test.RenamingDelegatingContext;
import java.io.StringWriter;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.json.JSONArray;

/** Only uniquely prefixed fixture databases; never opens the user's history. */
public final class InputHistoryIntegrationTest extends AndroidTestCase {
    private RenamingDelegatingContext isolated;
    private InputHistoryStore writer;
    @Override protected void setUp() throws Exception {
        super.setUp();
        isolated = new RenamingDelegatingContext(getContext(), "history-integration-" + UUID.randomUUID() + "-");
        writer = new InputHistoryStore(isolated);
    }
    @Override protected void tearDown() throws Exception {
        try { if (writer != null) writer.close(); }
        finally { super.tearDown(); }
    }
    public void testLocalInputNeedsNoPairingAndIsVisibleFromAnotherHelper() throws Exception {
        // This path has no PairingConfig, preferences, device lookup or network dependency.
        writer.record("ime-session", "ime_keyboard", "本机", "inserted", "commit-1", 0, "哈");
        writer.record("ime-session", "ime_keyboard", "本机", "inserted", "commit-2", 0, "哈");
        writer.record("ime-session", "ime_keyboard", "本机", "inserted", "commit-1", 0, "哈");
        try (InputHistoryStore reader = new InputHistoryStore(isolated)) {
            InputHistoryStore.Summary totals = reader.summary("", 0, Long.MAX_VALUE);
            assertEquals(2L, totals.recordCount); assertEquals(2L, totals.codePointCount);
            try (Cursor c = reader.search("哈", 0, Long.MAX_VALUE, Long.MAX_VALUE, 50)) {
                assertEquals(2, c.getCount()); assertTrue(c.moveToFirst());
                assertEquals("commit-2", c.getString(c.getColumnIndexOrThrow("message_id")));
            }
        }
        writer.close(); writer = new InputHistoryStore(isolated);
        assertEquals(2L, writer.summary("", 0, Long.MAX_VALUE).recordCount);
        assertEquals(1, writer.getReadableDatabase().getVersion());
    }
    public void testStateChangesAndOldSnapshotDedupKeepTheirMeaning() {
        writer.record("workspace", "phone_ime", "pc", "ACTIVE", "", 0, "草稿");
        writer.record("workspace", "phone_ime", "pc", "ACTIVE", "", 0, "草稿");
        assertEquals(1L, writer.summary("", 0, Long.MAX_VALUE).recordCount);
        writer.record("workspace", "phone_ime", "pc", "QUEUED", "send-1", 1, "草稿");
        writer.record("workspace", "phone_ime", "pc", "INSERTED", "send-1", 1, "草稿");
        writer.record("workspace", "phone_ime", "pc", "QUEUED", "send-1", 1, "草稿");
        assertEquals(3L, writer.summary("", 0, Long.MAX_VALUE).recordCount);
        writer.record("workspace", "ime_remote", "other-pc", "INSERTED", "send-1", 1, "草稿");
        assertEquals(4L, writer.summary("", 0, Long.MAX_VALUE).recordCount);
        // Statistics describe saved revisions as well as delivery events, not net typed text.
        assertEquals(8L, writer.summary("", 0, Long.MAX_VALUE).codePointCount);
    }
    public void testDatePagingLiteralSearchAndAllExportsUseSameRows() throws Exception {
        HistoryDateRange day = HistoryDateRange.parse("2026-10-12", "2026-10-12");
        for (int i = 0; i < 107; i++) {
            writer.record("ime", "ime_voice", "本机", "inserted", "event-" + i, 0,
                    i == 0 ? "关键词 =SUM(A1)\n第二行\"" : "关键词 " + i);
        }
        writer.getWritableDatabase().execSQL("UPDATE events SET created=?", new Object[]{day.since});
        writer.getWritableDatabase().execSQL("UPDATE events SET created=? WHERE id=107", new Object[]{day.until});
        writer.record("outside", "ime_keyboard", "本机", "inserted", "outside", 0, "关键词 outside");
        writer.getWritableDatabase().execSQL("UPDATE events SET created=? WHERE id=108", new Object[]{day.until + 1});
        Set<Long> ids = new HashSet<>(); long before = Long.MAX_VALUE;
        while (true) {
            int count = 0;
            try (Cursor page = writer.search("关键词", day.since, day.until, before, 50)) {
                while (page.moveToNext()) {
                    long id = page.getLong(page.getColumnIndexOrThrow("id"));
                    assertTrue(ids.add(id)); assertTrue(id < before); before = id; count++;
                }
            }
            if (count == 0) break;
        }
        assertEquals(107, ids.size());
        assertEquals(107L, writer.summary("关键词", day.since, day.until).recordCount);
        assertEquals(0L, writer.summary("' OR 1=1", 0, Long.MAX_VALUE).recordCount);
        for (String format : new String[]{"txt", "md", "json", "csv"}) {
            StringWriter out = new StringWriter();
            assertEquals(107, writer.export(out, format, "关键词", day.since, day.until));
            assertFalse(out.toString().contains("outside"));
            assertTrue(out.toString().contains("第二行"));
            if (format.equals("json")) {
                JSONArray all = new JSONArray(out.toString()); assertEquals(107, all.length());
                assertEquals("ime_voice", all.getJSONObject(0).getString("source"));
                assertEquals("event-0", all.getJSONObject(0).getString("message_id"));
            } else if (format.equals("csv")) {
                assertTrue(out.toString().startsWith("id,created,logical_session,revision,source,device,status,message_id,seq,text"));
                assertTrue(out.toString().contains("\"\""));
            } else {
                assertTrue(out.toString().contains("语音输入")); assertTrue(out.toString().contains("已输入"));
                assertFalse(out.toString().contains("event-0"));
            }
        }
    }
    public void testSummaryUnicodeAndReadableLabels() {
        writer.record("unicode", "ime_pinyin", "本机", "inserted", "emoji", 0, "😀中");
        assertEquals(2L, writer.summary("", 0, Long.MAX_VALUE).codePointCount);
        assertEquals("拼音输入", InputHistoryStore.sourceLabel("ime_pinyin"));
        assertEquals("九键输入", InputHistoryStore.sourceLabel("ime_t9"));
        assertEquals("已输入", InputHistoryStore.statusLabel("inserted"));
        assertEquals("发送结果待确认", InputHistoryStore.statusLabel("DELIVERY_UNKNOWN"));
        assertEquals("其他输入", InputHistoryStore.sourceLabel("unknown-provider"));
        assertEquals("已保存记录", InputHistoryStore.statusLabel("unknown-state"));
    }
}
