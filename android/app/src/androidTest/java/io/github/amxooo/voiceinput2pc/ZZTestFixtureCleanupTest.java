package io.github.amxooo.voiceinput2pc;

import android.content.SharedPreferences;
import android.test.AndroidTestCase;

/** One-time safe cleanup for the exact fixture used by older non-isolated tests. */
public final class ZZTestFixtureCleanupTest extends AndroidTestCase {
    public void testRemoveOnlyKnownLeakedFixture() {
        SharedPreferences prefs=getContext().getSharedPreferences("voiceinput2pc",0);
        PairingConfig value=PairingStore.load(prefs);
        boolean exact=value != null
                && "127.0.0.1".equals(value.host)
                && value.port == 23337
                && "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA".equals(value.token)
                && "abababababababababababababababababababababababababababababababab".equals(value.fingerprint);
        if (!exact) return;
        assertTrue(prefs.edit().clear().commit());
        InputHistoryStore history=new InputHistoryStore(getContext());
        try { history.getWritableDatabase().delete("events",null,null); }
        finally { history.close(); }
    }
}
