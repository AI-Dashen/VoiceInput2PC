package io.github.amxooo.voiceinput2pc;

import android.app.Activity;
import android.os.Bundle;
import android.test.AndroidTestCase;
import android.test.RenamingDelegatingContext;
import java.util.UUID;

/** Rotation/process restoration while Android's document picker is open. */
public final class HistoryScreenStateTest extends AndroidTestCase {
    public void testPendingExportKeepsOriginalQueryAndBackReturns() {
        InputHistoryStore store = new InputHistoryStore(new RenamingDelegatingContext(getContext(), "history-state-" + UUID.randomUUID() + "-"));
        boolean[] returned = {false};
        HistoryScreen screen = new HistoryScreen(new Activity(), store, () -> returned[0] = true);
        try {
            Bundle incoming = new Bundle();
            incoming.putBoolean("history.visible", true);
            incoming.putString("history.query", "新的查询");
            incoming.putLong("history.since", 0); incoming.putLong("history.until", Long.MAX_VALUE);
            incoming.putString("history.pendingFormat", "csv");
            incoming.putString("history.pendingKeyword", "原始导出关键词");
            incoming.putLong("history.pendingSince", 123); incoming.putLong("history.pendingUntil", 456);
            incoming.putString("history.fromDraft", "未完成日期");
            screen.restoreState(incoming);
            Bundle saved = new Bundle(); screen.saveState(saved);
            assertTrue(screen.isVisible());
            assertEquals("csv", saved.getString("history.pendingFormat"));
            assertEquals("原始导出关键词", saved.getString("history.pendingKeyword"));
            assertEquals(123, saved.getLong("history.pendingSince"));
            assertEquals(456, saved.getLong("history.pendingUntil"));
            assertEquals("未完成日期", saved.getString("history.fromDraft"));
            assertTrue(screen.handleActivityResult(HistoryScreen.EXPORT_REQUEST, Activity.RESULT_CANCELED, null));
            screen.saveState(saved); assertNull(saved.getString("history.pendingFormat"));
            assertTrue(screen.onBackPressed()); assertTrue(returned[0]); assertFalse(screen.isVisible());
            assertFalse(screen.onBackPressed());
        } finally { screen.destroy(); store.close(); }
    }
}
