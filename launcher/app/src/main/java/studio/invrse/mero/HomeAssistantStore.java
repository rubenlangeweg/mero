package studio.invrse.mero;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Optional local provisioning; credentials stay encrypted in app-private storage. */
final class HomeAssistantStore {
    static final class Sensor {
        final String id;
        final String label;
        final String unit;
        Sensor(String id, String label, String unit) { this.id = id; this.label = label; this.unit = unit; }
        @Override public String toString() { return id + ":" + label + ":" + unit; }
    }

    static final class Config {
        final String url;
        final String token;
        final List<Sensor> sensors;
        final boolean configured;
        final boolean enabled;
        final String status;
        Config(String url, String token, List<Sensor> sensors, boolean configured, boolean enabled, String status) {
            this.url = url; this.token = token;
            this.sensors = Collections.unmodifiableList(new ArrayList<>(sensors));
            this.configured = configured; this.enabled = enabled; this.status = status;
        }
    }

    private static final String KEY_ALIAS = "mero-home-assistant";
    private final Context context;
    private final SharedPreferences preferences;

    HomeAssistantStore(Context context) {
        this.context = context.getApplicationContext();
        preferences = this.context.getSharedPreferences("home-assistant", Context.MODE_PRIVATE);
    }

    synchronized Config load() {
        try {
            importConfiguration();
            String storedUrl = preferences.getString("url", "");
            String encodedSensors = preferences.getString("entities", null);
            if (storedUrl.isEmpty() || encodedSensors == null)
                return empty("Not configured");
            String url = normalizeUrl(storedUrl);
            JSONArray stored = new JSONArray(encodedSensors);
            JSONArray current = legacyEntities(stored);
            List<Sensor> sensors = sensors(current);
            String token = decrypt();
            boolean complete = token != null && !token.isEmpty();
            boolean hasEnabled = preferences.contains("enabled");
            boolean enabled = HomeAssistantPolicy.enabled(hasEnabled, preferences.getBoolean("enabled", false), complete);
            // Existing private installations already opted in by provisioning a working connection.
            if (!hasEnabled && complete) preferences.edit().putBoolean("enabled", true).apply();
            if (stored.length() != current.length()) preferences.edit().putString("entities", current.toString()).apply();
            return new Config(url, token, sensors, complete, enabled,
                    enabled ? null : complete ? "Off" : "Setup incomplete");
        } catch (Exception invalid) {
            return empty("Setup incomplete");
        }
    }

    private Config empty(String status) {
        return new Config("", null, Collections.emptyList(), false, false, status);
    }

    synchronized void setEnabled(boolean enabled) throws Exception {
        if (enabled && !load().configured) throw new IllegalStateException("Set up Home Assistant first");
        if (!preferences.edit().putBoolean("enabled", enabled).commit())
            throw new IllegalStateException("Cannot save Home Assistant setting");
    }

    synchronized void clear() throws Exception {
        // Stop future reads even if removing a pending provisioning file fails.
        setEnabled(false);
        if (!preferences.edit().putBoolean("ignore_pending_configuration", true).commit())
            throw new IllegalStateException("Cannot stop pending configuration import");
        File file = provisioningFile();
        if (file != null && file.exists() && !file.delete())
            throw new IllegalStateException("Cannot remove the pending configuration file");
        if (!preferences.edit().clear().commit()) throw new IllegalStateException("Cannot remove Home Assistant settings");
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(KEY_ALIAS)) store.deleteEntry(KEY_ALIAS);
    }

    private File provisioningFile() {
        File directory = context.getExternalFilesDir(null);
        return directory == null ? null : new File(directory, "home-assistant.json");
    }

    synchronized void checkConfiguration() throws Exception {
        if (!preferences.edit().remove("ignore_pending_configuration").commit())
            throw new IllegalStateException("Cannot check pending configuration");
        importConfiguration();
    }

    private void importConfiguration() throws Exception {
        if (preferences.getBoolean("ignore_pending_configuration", false)) return;
        File file = provisioningFile();
        if (file == null || !file.isFile()) return;
        if (file.length() > 32768) throw new IllegalArgumentException("Configuration too large");
        JSONObject config = new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
        String url = normalizeUrl(config.getString("url"));
        JSONArray entities = legacyEntities(config.getJSONArray("entities"));
        sensors(entities);
        String token = config.optString("token", "").trim();
        if (token.length() > 8192) throw new IllegalArgumentException("Invalid token");
        for (int i = 0; i < token.length(); i++)
            if (token.charAt(i) <= 32 || token.charAt(i) == 127) throw new IllegalArgumentException("Invalid token");
        boolean sameServer = url.equals(preferences.getString("url", ""));
        if (token.isEmpty() && (!sameServer || decrypt() == null))
            throw new IllegalArgumentException("A new connection needs its own token");
        Object enabledValue = config.has("enabled") ? config.get("enabled") : Boolean.TRUE;
        if (!(enabledValue instanceof Boolean)) throw new IllegalArgumentException("Enabled must be true or false");
        SharedPreferences.Editor edit = preferences.edit().putString("url", url)
                .putString("entities", entities.toString()).putBoolean("enabled", (Boolean) enabledValue);
        if (!token.isEmpty()) {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key());
            edit.putString("token", Base64.encodeToString(cipher.doFinal(token.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP));
            edit.putString("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP));
        }
        if (!edit.commit()) throw new IllegalStateException("Configuration not saved");
        if (!file.delete()) throw new IllegalStateException("Provisioning file not removed");
    }

    static String normalizeUrl(String value) throws Exception { return HomeAssistantPolicy.normalizeUrl(value); }

    private List<Sensor> sensors(JSONArray data) throws Exception {
        if (data.length() < 1 || data.length() > 2) throw new IllegalArgumentException("Choose one or two sensor readings");
        List<Sensor> sensors = new ArrayList<>();
        for (int i = 0; i < data.length(); i++) {
            JSONObject item = data.getJSONObject(i);
            String id = item.getString("id").trim();
            String label = item.getString("label").trim();
            String unit = item.getString("unit").trim();
            HomeAssistantPolicy.sensor(id, label, unit);
            sensors.add(new Sensor(id, label, unit));
        }
        return sensors;
    }

    private JSONArray legacyEntities(JSONArray data) throws Exception {
        // Earlier private versions stored three fixed readings. Retain their first and last.
        if (data.length() == 3) return new JSONArray().put(data.getJSONObject(0)).put(data.getJSONObject(2));
        return data;
    }

    private String decrypt() throws Exception {
        String token = preferences.getString("token", null);
        if (token == null) return null;
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(preferences.getString("iv", ""), Base64.NO_WRAP)));
        return new String(cipher.doFinal(Base64.decode(token, Base64.NO_WRAP)), StandardCharsets.UTF_8);
    }

    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(KEY_ALIAS)) return (SecretKey) store.getKey(KEY_ALIAS, null);
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }
}
