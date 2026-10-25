package studio.invrse.mero;

import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.net.URL;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import javax.net.ssl.HttpsURLConnection;

/** Reads only configured sensor state endpoints. Credentials never enter URLs or diagnostics. */
final class HomeAssistantClient {
    static final class Reading {
        final String label;
        final String value;
        final String unit;
        Reading(String label, String value, String unit) {
            this.label = label;
            this.value = value;
            this.unit = unit;
        }
    }

    static final class Snapshot {
        final List<Reading> readings;
        final String status;
        final long updatedAtMs;
        final boolean enabled;
        Snapshot(List<Reading> readings, String status, long updatedAtMs, boolean enabled) {
            this.readings = Collections.unmodifiableList(new ArrayList<>(readings));
            this.status = status;
            this.updatedAtMs = updatedAtMs;
            this.enabled = enabled;
        }
    }

    private final HomeAssistantStore store;
    private Snapshot lastGood;
    private String lastConfiguration;
    private volatile HttpsURLConnection activeConnection;

    void cancel() {
        HttpsURLConnection connection = activeConnection;
        if (connection != null) {
            try { connection.disconnect(); }
            catch (RuntimeException ignored) { /* The worker remains cancelled and its callbacks invalid. */ }
        }
    }

    HomeAssistantClient(HomeAssistantStore store) { this.store = store; }

    static Snapshot disconnected() {
        return new Snapshot(Collections.emptyList(), "Off", 0, false);
    }

    static Snapshot initial(HomeAssistantStore.Config config) {
        if (!config.enabled) return disconnected();
        List<Reading> readings = new ArrayList<>();
        for (HomeAssistantStore.Sensor sensor : config.sensors)
            readings.add(new Reading(sensor.label, "—", sensor.unit));
        return new Snapshot(readings, "Connecting…", 0, true);
    }

    boolean isEnabled() { return store.load().enabled; }

    Snapshot fetch() {
        HomeAssistantStore.Config config = store.load();
        String identity = config.url + config.sensors.toString() + config.enabled;
        if (!identity.equals(lastConfiguration)) {
            lastGood = null;
            lastConfiguration = identity;
        }
        if (!config.enabled) return disconnected();
        if (config.status != null) return unavailable(config, config.status);
        List<Reading> readings = new ArrayList<>();
        try {
            for (HomeAssistantStore.Sensor sensor : config.sensors) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("Cancelled");
                HttpsURLConnection connection = (HttpsURLConnection)
                        new URL(config.url + "/api/states/" + sensor.id).openConnection();
                activeConnection = connection;
                try {
                    connection.setRequestMethod("GET");
                    connection.setInstanceFollowRedirects(false);
                    connection.setConnectTimeout(3000);
                    connection.setReadTimeout(3000);
                    connection.setRequestProperty("Authorization", "Bearer " + config.token);
                    connection.setRequestProperty("Accept", "application/json");
                    connection.setRequestProperty("User-Agent", "Mero (Android TV; Home Assistant display)");
                    if (Thread.currentThread().isInterrupted()) throw new IOException("Cancelled");
                    int response = connection.getResponseCode();
                    if (response == 401 || response == 403) return unavailable(config, "Reconnect Home Assistant");
                    if (response == 404) {
                        readings.add(new Reading(sensor.label, "—", sensor.unit));
                        continue;
                    }
                    if (response != 200) return unavailable(config, "Offline");
                    try (InputStream input = connection.getInputStream()) {
                        JSONObject state = new JSONObject(readResponse(input));
                        JSONObject attributes = state.optJSONObject("attributes");
                        String unit = attributes == null ? sensor.unit : attributes.optString("unit_of_measurement", sensor.unit);
                        readings.add(new Reading(sensor.label, formatValue(state.optString("state", ""), unit), unit));
                    }
                } finally { activeConnection = null; connection.disconnect(); }
            }
            boolean available = false;
            for (Reading reading : readings) if (!reading.value.equals("—")) available = true;
            Snapshot snapshot = new Snapshot(readings, available ? "" : "Unavailable", System.currentTimeMillis(), true);
            lastGood = snapshot;
            return snapshot;
        } catch (Exception unavailable) {
            return unavailable(config, "Offline");
        }
    }

    private Snapshot unavailable(HomeAssistantStore.Config config, String status) {
        if (lastGood != null) return new Snapshot(lastGood.readings, status, lastGood.updatedAtMs, true);
        List<Reading> readings = new ArrayList<>();
        for (HomeAssistantStore.Sensor sensor : config.sensors)
            readings.add(new Reading(sensor.label, "—", sensor.unit));
        return new Snapshot(readings, status, 0, true);
    }

    static String formatValue(String state, String unit) {
        try {
            double value = Double.parseDouble(state);
            if (!Double.isFinite(value)) return "—";
            DecimalFormat format = new DecimalFormat("%".equals(unit) ? "0.#" : "0.0",
                    DecimalFormatSymbols.getInstance(Locale.ROOT));
            return format.format(value);
        } catch (NumberFormatException invalid) { return "—"; }
    }

    private String readResponse(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (output.size() + count > 65536) throw new IOException("Response too large");
            output.write(buffer, 0, count);
        }
        return output.toString("UTF-8");
    }
}
