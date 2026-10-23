package io.github.amxooo.voiceinput2pc;

import android.database.Cursor;
import android.test.AndroidTestCase;
import android.test.RenamingDelegatingContext;
import java.io.StringWriter;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.json.JSONArray;

/** Query/export boundary checks against a uniquely named database, never real history. */
public final class HistoryQueryTest extends AndroidTestCase {
    public void testSourceFilterMatchesSummaryAndExportWithoutMigration()throws Exception {
        RenamingDelegatingContext c=new RenamingDelegatingContext(getContext(),"source-filter-"+UUID.randomUUID()+"-");
        InputHistoryStore store=new InputHistoryStore(c);
        try {
            store.record("voice","ime_voice","本机","LOCAL_COMMITTED","v",1,"语音样本");
            store.record("keyboard","ime_pinyin","本机","LOCAL_COMMITTED","k",1,"键盘样本");
            store.record("computer","ime_remote","pc","ACKNOWLEDGED","p",1,"电脑样本");
            for(String group:new String[]{"voice","keyboard","computer"}) {
                assertEquals(1,store.summary("",0,Long.MAX_VALUE,group).recordCount);
                try(Cursor result=store.search("",0,Long.MAX_VALUE,Long.MAX_VALUE,6,group)){assertEquals(1,result.getCount());}
                StringWriter output=new StringWriter();assertEquals(1,store.export(output,"json","",0,Long.MAX_VALUE,group));
                assertEquals(1,new JSONArray(output.toString()).length());
            }
            assertEquals(3,store.summary("",0,Long.MAX_VALUE).recordCount);
            try{store.summary("",0,Long.MAX_VALUE,"invalid");fail("unknown group accepted");}catch(IllegalArgumentException expected){}
        } finally {store.close();}
    }
    public void testInclusiveDatesAndInvalidDates() {
        HistoryDateRange day = HistoryDateRange.parse("2026-10-12", "2026-10-12");
        assertTrue(day.until >= day.since);
        assertEquals(HistoryDateRange.parse("2026-10-13", "").since - 1, day.until);
        assertEquals(Long.MAX_VALUE, HistoryDateRange.parse("", "").until);
        for (String bad : new String[]{"2026-02-30", "12/10/2026", "2026-13-01"}) {
            try { HistoryDateRange.parse(bad, ""); fail("accepted " + bad); }
            catch (RuntimeException expected) { }
        }
        try { HistoryDateRange.parse("2026-10-13", "2026-10-12"); fail("accepted reversed dates"); }
        catch (IllegalArgumentException expected) { }
    }
    public void testPagingAndFilteredExportsShareBoundary() throws Exception {
        RenamingDelegatingContext context = new RenamingDelegatingContext(getContext(), "history-query-" + UUID.randomUUID() + "-");
        InputHistoryStore store = new InputHistoryStore(context);
        try {
            HistoryDateRange day = HistoryDateRange.parse("2026-10-12", "2026-10-12");
            for (int i = 0; i < 137; i++) store.record("logical", "phone_ime", "my-pc", "ACKNOWLEDGED", "id-"+i, i+1, "关键词 \""+i+"\"\n第二行");
            store.getWritableDatabase().execSQL("UPDATE events SET created=?", new Object[]{day.since});
            store.getWritableDatabase().execSQL("UPDATE events SET created=? WHERE id=137", new Object[]{day.until});
            store.record("other", "phone_ime", "my-pc", "LOCAL", "", 0, "关键词 outside");
            store.getWritableDatabase().execSQL("UPDATE events SET created=? WHERE id=138", new Object[]{day.until+1});
            Set<Long> ids = new HashSet<>(); long before = Long.MAX_VALUE;
            while (true) {
                int read = 0;
                try (Cursor page = store.search("关键词", day.since, day.until, before, 50)) {
                    while (page.moveToNext()) { long id = page.getLong(page.getColumnIndexOrThrow("id")); assertTrue(ids.add(id)); before = id; read++; }
                }
                if (read == 0) break;
            }
            assertEquals(137, ids.size());
            for (String format : new String[]{"txt", "md", "json", "csv"}) {
                StringWriter writer = new StringWriter(); assertEquals(137, store.export(writer, format, "关键词", day.since, day.until));
                assertFalse(writer.toString().contains("outside")); assertTrue(writer.toString().contains("第二行"));
                if (format.equals("json")) {
                    JSONArray all = new JSONArray(writer.toString()); assertEquals(137, all.length());
                    assertEquals("my-pc", all.getJSONObject(0).getString("device"));
                    assertEquals("logical", all.getJSONObject(0).getString("logical_session"));
                }
                if (format.equals("csv")) assertTrue(writer.toString().contains("\"\"136\"\""));
            }
        } finally { store.close(); }
    }
}
