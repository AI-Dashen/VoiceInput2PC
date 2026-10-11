package io.github.amxooo.voiceinput2pc;

import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Stores pairing credentials only in Android's private application preferences. */
public final class PairingStore {
    private static final String HOST = "pair_host";
    private static final String PORT = "pair_port";
    private static final String TOKEN = "pair_token";
    private static final String FINGERPRINT = "pair_fingerprint";
    private static final String TRANSPORT = "pair_transport";
    private static final String TAILCAT_ADDRESS = "pair_tailcat_address";
    private static final String DEVICE_ID = "pair_device_id";

    private static final String REGISTRY = "pair_registry_v1";

    private static final class Entry {
        final PairingConfig config;
        final String label;
        Entry(PairingConfig config, String label) {
            this.config = config;
            this.label = label;
        }
    }

    private PairingStore() {}

    public static PairingConfig load(SharedPreferences preferences) {
        if (preferences == null) return null;
        if (!preferences.contains(HOST) || !preferences.contains(PORT)
                || !preferences.contains(TOKEN) || !preferences.contains(FINGERPRINT)) {
            return null;
        }
        try {
            return new PairingConfig(
                    preferences.getString(HOST, null),
                    preferences.getInt(PORT, 0),
                    preferences.getString(TOKEN, null),
                    preferences.getString(FINGERPRINT, null),
                    preferences.getString(TRANSPORT, PairingConfig.TRANSPORT_LAN),
                    preferences.getString(TAILCAT_ADDRESS, ""),
                    preferences.getString(DEVICE_ID, ""));
        } catch (ClassCastException | IllegalArgumentException invalid) {
            return null;
        }
    }

    /** Returns every saved device, including a configuration from older app versions. */
    public static synchronized List<PairingConfig> list(SharedPreferences preferences) {
        List<PairingConfig> result = new ArrayList<>();
        for (Entry entry : readEntries(preferences).values()) result.add(entry.config);
        return result;
    }

    /** UI text deliberately excludes credentials and the private Tailcat address. */
    public static synchronized String label(SharedPreferences preferences, PairingConfig value) {
        if (value == null) return "电脑";
        Entry entry = readEntries(preferences).get(identity(value));
        return entry != null && !entry.label.isEmpty() ? entry.label : "电脑 · " + value.host;
    }

    /** Renaming never selects another device or rewrites the active connection. */
    public static synchronized boolean rename(SharedPreferences preferences,
                                              PairingConfig value, String name) {
        String normalized = normalizeLabel(name);
        if (preferences == null || value == null || normalized == null) return false;
        LinkedHashMap<String, Entry> entries = readEntries(preferences);
        Entry current = entries.get(identity(value));
        if (current == null) return false;
        entries.put(identity(value), new Entry(current.config, normalized));
        try {
            return preferences.edit().putString(REGISTRY, serialize(entries)).commit();
        } catch (JSONException invalid) {
            return false;
        }
    }

    public static synchronized boolean save(SharedPreferences preferences, PairingConfig value) {
        if (preferences == null || value == null) return false;
        LinkedHashMap<String, Entry> entries = readEntries(preferences);
        Entry previous = entries.get(identity(value));
        entries.put(identity(value), new Entry(value, previous == null ? "" : previous.label));
        try {
            // One durable commit keeps selection and its registry entry together.
            return preferences.edit()
                    .putString(REGISTRY, serialize(entries))
                    .putString(HOST, value.host)
                    .putInt(PORT, value.port)
                    .putString(TOKEN, value.token)
                    .putString(FINGERPRINT, value.fingerprint)
                    .putString(TRANSPORT, value.transport)
                    .putString(TAILCAT_ADDRESS, value.tailcatAddress)
                    .putString(DEVICE_ID, value.deviceId)
                    .putString("host", value.host)
                    .commit();
        } catch (JSONException invalid) {
            return false;
        }
    }

    private static String identity(PairingConfig config) {
        return config.deviceId.isEmpty() ? "fingerprint:" + config.fingerprint
                : "device:" + config.deviceId;
    }

    private static String normalizeLabel(String value) {
        if (value == null) return null;
        String name = value.trim();
        int length = name.codePointCount(0, name.length());
        if (length < 1 || length > 40) return null;
        for (int i = 0; i < name.length();) {
            int point = name.codePointAt(i);
            if (Character.isISOControl(point)) return null;
            i += Character.charCount(point);
        }
        return name;
    }

    private static LinkedHashMap<String, Entry> readEntries(SharedPreferences preferences) {
        LinkedHashMap<String, Entry> entries = new LinkedHashMap<>();
        if (preferences == null) return entries;
        try {
            String raw = preferences.getString(REGISTRY, "[]");
            JSONArray stored = new JSONArray(raw == null ? "[]" : raw);
            for (int i = 0; i < stored.length(); i++) {
                try {
                    JSONObject item = stored.getJSONObject(i);
                    PairingConfig config = new PairingConfig(item.getString("host"),
                            item.getInt("port"), item.getString("token"),
                            item.getString("fingerprint"), item.getString("transport"),
                            item.getString("tailcatAddress"), item.getString("deviceId"));
                    String name = normalizeLabel(item.optString("label", ""));
                    entries.put(identity(config), new Entry(config, name == null ? "" : name));
                } catch (JSONException | IllegalArgumentException invalidEntry) {
                    // Skip only this entry; never log pairing payloads or exceptions.
                }
            }
        } catch (JSONException | ClassCastException invalidRegistry) {
            // Legacy active fields remain usable even if registry data is damaged.
        }
        PairingConfig active = load(preferences);
        if (active != null) {
            Entry saved = entries.get(identity(active));
            entries.put(identity(active), new Entry(active, saved == null ? "" : saved.label));
        }
        return entries;
    }

    private static String serialize(LinkedHashMap<String, Entry> entries) throws JSONException {
        JSONArray result = new JSONArray();
        for (Entry entry : entries.values()) {
            PairingConfig config = entry.config;
            result.put(new JSONObject()
                    .put("host", config.host).put("port", config.port)
                    .put("token", config.token).put("fingerprint", config.fingerprint)
                    .put("transport", config.transport).put("tailcatAddress", config.tailcatAddress)
                    .put("deviceId", config.deviceId).put("label", entry.label));
        }
        return result.toString();
    }
}
