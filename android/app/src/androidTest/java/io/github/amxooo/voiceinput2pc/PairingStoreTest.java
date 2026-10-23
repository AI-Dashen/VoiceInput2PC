package io.github.amxooo.voiceinput2pc;

import android.content.Context;
import android.content.SharedPreferences;
import android.test.AndroidTestCase;
import java.util.List;
import java.util.UUID;
import org.json.JSONArray;

/** Each test uses a new private preference file and never clears real pairing data. */
public final class PairingStoreTest extends AndroidTestCase {
    private SharedPreferences preferences;
    private String preferenceName;

    @Override protected void setUp() throws Exception {
        super.setUp();
        preferenceName = "pairing-test-" + UUID.randomUUID();
        preferences = getContext().getSharedPreferences(preferenceName,
                Context.MODE_PRIVATE);
    }

    private static String repeat(char value, int count) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < count; i++) text.append(value);
        return text.toString();
    }

    private static PairingConfig lan(String host, char fingerprint) {
        return new PairingConfig(host, 9443, repeat('T', 32), repeat(fingerprint, 64));
    }

    private static PairingConfig identified(String host, String deviceId, char fingerprint) {
        return new PairingConfig(host, 9443, repeat('T', 32), repeat(fingerprint, 64),
                PairingConfig.TRANSPORT_AUTO, "tc" + repeat('Z', 32), deviceId);
    }

    public void testRegisterDoesNotChangeActiveOutput() {
        PairingConfig first=lan("first-pc.local",'a'), second=lan("second-pc.local",'b');
        assertTrue(PairingStore.register(preferences,first));
        assertNull(PairingStore.load(preferences));
        assertTrue(PairingStore.save(preferences,first));
        assertTrue(PairingStore.register(preferences,second));
        assertEquals(first,PairingStore.load(preferences));
        assertEquals(2,PairingStore.list(preferences).size());
        PairingConfig updated=new PairingConfig(first.host,9444,repeat('U',32),first.fingerprint);
        assertTrue(PairingStore.register(preferences,updated));
        assertEquals(first,PairingStore.load(preferences));
        assertTrue(PairingStore.list(preferences).contains(updated));
    }

    public void testMultipleDevicesAndSelectionKeepAllRecords() throws Exception {
        PairingConfig first = lan("first-pc.local", 'a');
        PairingConfig second = lan("second-pc.local", 'b');
        assertTrue(PairingStore.save(preferences, first));
        assertTrue(PairingStore.save(preferences, second));
        assertEquals(second, PairingStore.load(preferences));
        assertEquals(2, PairingStore.list(preferences).size());
        assertTrue(PairingStore.list(preferences).contains(first));
        assertTrue(PairingStore.save(preferences, first));
        assertEquals(first, PairingStore.load(preferences));
        assertEquals(2, PairingStore.list(preferences).size());
        assertEquals(2, new JSONArray(preferences.getString("pair_registry_v1", "[]")).length());
        assertEquals(first.host, preferences.getString("host", ""));
        // A fresh preferences lookup reads the durable result without a store cache.
        SharedPreferences reopened = getContext().getSharedPreferences(
                preferenceName, Context.MODE_PRIVATE);
        assertEquals(first, PairingStore.load(reopened));
        assertEquals(2, PairingStore.list(reopened).size());
    }

    public void testDeviceIdAndFingerprintDeduplicationRetainNames() {
        String id = repeat('1', 32);
        PairingConfig initial = identified("old-pc.local", id, 'a');
        PairingConfig updated = identified("new-pc.local", id, 'b');
        assertTrue(PairingStore.save(preferences, initial));
        assertTrue(PairingStore.rename(preferences, initial, "办公室电脑"));
        assertTrue(PairingStore.save(preferences, updated));
        assertEquals(1, PairingStore.list(preferences).size());
        assertEquals(updated, PairingStore.list(preferences).get(0));
        assertEquals("办公室电脑", PairingStore.label(preferences, updated));
        assertTrue(PairingStore.save(preferences, lan("lan-old.local", 'c')));
        assertTrue(PairingStore.save(preferences, lan("lan-new.local", 'c')));
        assertEquals(2, PairingStore.list(preferences).size());
    }

    public void testNoArbitraryDeviceCap() {
        for (int i = 0; i < 130; i++) {
            String id = String.format(java.util.Locale.ROOT, "%032x", i);
            assertTrue(PairingStore.save(preferences, identified("pc-" + i + ".local", id, 'a')));
        }
        assertEquals(130, PairingStore.list(preferences).size());
    }

    public void testLabelsAndInvalidNamesDoNotChangeSelection() {
        PairingConfig first = lan("first.local", 'a');
        PairingConfig second = lan("second.local", 'b');
        assertTrue(PairingStore.save(preferences, first));
        assertTrue(PairingStore.save(preferences, second));
        assertEquals("电脑 · first.local", PairingStore.label(preferences, first));
        assertTrue(PairingStore.rename(preferences, first, " 家里电脑 "));
        assertEquals("家里电脑", PairingStore.label(preferences, first));
        assertEquals(second, PairingStore.load(preferences));
        assertFalse(PairingStore.rename(preferences, first, "  "));
        assertFalse(PairingStore.rename(preferences, first, repeat('x', 41)));
        assertFalse(PairingStore.rename(preferences, first, "电脑\n名称"));
        assertFalse(PairingStore.rename(preferences, first, null));
        assertFalse(PairingStore.rename(preferences, lan("unknown.local", 'd'), "未知电脑"));
        assertEquals("家里电脑", PairingStore.label(preferences, first));
        assertTrue(PairingStore.rename(preferences, first, repeat('名', 40)));
        assertTrue(PairingStore.rename(preferences, first, "名"));
    }

    public void testLegacyMigrationPreservesActiveAndOldKeys() throws Exception {
        PairingConfig legacy = lan("legacy.local", 'a');
        assertTrue(preferences.edit().putString("pair_host", legacy.host)
                .putInt("pair_port", legacy.port).putString("pair_token", legacy.token)
                .putString("pair_fingerprint", legacy.fingerprint).putString("unrelated", "keep")
                .commit());
        assertEquals(legacy, PairingStore.load(preferences));
        assertEquals(legacy, PairingStore.list(preferences).get(0));
        assertTrue(PairingStore.rename(preferences, legacy, "旧电脑"));
        assertEquals(legacy, PairingStore.load(preferences));
        assertTrue(PairingStore.save(preferences, lan("new.local", 'b')));
        assertEquals(2, PairingStore.list(preferences).size());
        assertTrue(PairingStore.list(preferences).contains(legacy));
        assertEquals("旧电脑", PairingStore.label(preferences, legacy));
        assertEquals("keep", preferences.getString("unrelated", ""));
        assertEquals(2, new JSONArray(preferences.getString("pair_registry_v1", "[]")).length());
    }

    public void testMalformedRegistryKeepsLegacyAndValidEntries() throws Exception {
        PairingConfig first = lan("first.local", 'a');
        PairingConfig second = lan("second.local", 'b');
        assertTrue(PairingStore.save(preferences, first));
        assertTrue(PairingStore.save(preferences, second));
        JSONArray registry = new JSONArray(preferences.getString("pair_registry_v1", "[]"));
        registry.put("invalid");
        registry.put(new org.json.JSONObject().put("token", "bad"));
        assertTrue(preferences.edit().putString("pair_registry_v1", registry.toString()).commit());
        assertEquals(2, PairingStore.list(preferences).size());
        for (String malformed : new String[]{"not-json", "{}", "[null]"}) {
            assertTrue(preferences.edit().putString("pair_registry_v1", malformed).commit());
            List<PairingConfig> list = PairingStore.list(preferences);
            assertEquals(1, list.size());
            assertEquals(second, list.get(0));
        }
        assertTrue(preferences.edit().putInt("pair_registry_v1", 123).commit());
        assertEquals(second, PairingStore.list(preferences).get(0));
        assertTrue(PairingStore.save(preferences, first));
        assertEquals(2, PairingStore.list(preferences).size());
    }

    public void testMalformedLegacyAndNullInputsFailSafe() {
        assertTrue(preferences.edit().putInt("pair_host", 123).putInt("pair_port", 9443)
                .putString("pair_token", repeat('T', 32)).putString("pair_fingerprint", repeat('a', 64))
                .commit());
        assertNull(PairingStore.load(preferences));
        assertTrue(PairingStore.list(preferences).isEmpty());
        assertNull(PairingStore.load(null));
        assertTrue(PairingStore.list(null).isEmpty());
        assertFalse(PairingStore.save(null, lan("pc.local", 'a')));
        assertFalse(PairingStore.save(preferences, null));
    }

    public void testLabelsAndToStringKeepCredentialsPrivate() {
        PairingConfig config = identified("private-pc.local", repeat('2', 32), 'a');
        assertTrue(PairingStore.save(preferences, config));
        for (String text : new String[]{PairingStore.label(preferences, config), config.toString()}) {
            assertFalse(text.contains(config.token));
            assertFalse(text.contains(config.fingerprint));
            assertFalse(text.contains(config.tailcatAddress));
            assertFalse(text.contains(config.deviceId));
        }
    }
}
