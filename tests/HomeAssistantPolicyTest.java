package studio.invrse.mero;

/** Host checks for setup input and the no-configuration/no-network default. */
public final class HomeAssistantPolicyTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        url("https://home.example", "https://home.example");
        url(" HTTPS://home.example/ ", "https://home.example");
        url("https://home.example:8123/", "https://home.example:8123");
        url("https://192.168.50.10:8123", "https://192.168.50.10:8123");
        url("https://[fd00::1]:8123/", "https://[fd00::1]:8123");
        url("https://localhost/", "https://localhost");
        for (String value : new String[]{null, "", " ", "http://home.example", "ftp://home.example",
                "//home.example", "https:///", "https://user:pass@home.example", "https://home.example/path",
                "https://home.example//", "https://home.example?x=1", "https://home.example#token",
                "https://home example", "https://home.example:", "https://home.example:0",
                "https://home.example:65536", "https://home.example:-1", "https://home.example/%2f",
                "https://home.example\r\n", "https://home.example\u007f"}) rejectUrl(value);

        sensor("sensor.room_temperature", "Room", "°C");
        sensor("sensor.power_1", " Current power ", "W");
        sensor("sensor.outside", "Outside", "");
        sensor("sensor.room", repeat('a', 30), repeat('u', 8));
        rejectSensor("light.room", "Room", "°C");
        rejectSensor("sensor.Room", "Room", "°C");
        rejectSensor("sensor.room-temperature", "Room", "°C");
        rejectSensor("sensor.room/other", "Room", "°C");
        rejectSensor(null, "Room", "°C");
        rejectSensor("sensor.room", null, "°C");
        rejectSensor("sensor.room", "Room", null);
        rejectSensor("sensor.room", " ", "°C");
        rejectSensor("sensor.room", repeat('a', 31), "°C");
        rejectSensor("sensor.room", "Room", repeat('u', 9));
        rejectSensor("sensor.room", "Room\n", "°C");
        rejectSensor("sensor.room", "Room", "\t°C");
        rejectSensor("sensor.room", "Room\u007f", "°C");

        for (boolean complete : new boolean[]{false, true}) {
            check(HomeAssistantPolicy.enabled(false, false, complete) == complete,
                    "Legacy setup enables only when complete");
            check(HomeAssistantPolicy.enabled(false, true, complete) == complete,
                    "Absent flag ignores its fallback value");
            check(!HomeAssistantPolicy.enabled(true, false, complete), "Explicit disable always wins");
            check(HomeAssistantPolicy.enabled(true, true, complete) == complete,
                    "Explicit enable cannot bypass complete setup");
        }
        System.out.println("HomeAssistantPolicyTest: " + checks + " checks passed");
    }

    private static void url(String offered, String expected) throws Exception {
        check(expected.equals(HomeAssistantPolicy.normalizeUrl(offered)), "Canonical HTTPS origin");
    }

    private static void rejectUrl(String value) throws Exception {
        try {
            HomeAssistantPolicy.normalizeUrl(value);
            throw new AssertionError("Accepted invalid origin");
        } catch (IllegalArgumentException | java.net.URISyntaxException expected) { checks++; }
    }

    private static void sensor(String id, String label, String unit) {
        HomeAssistantPolicy.sensor(id, label, unit);
        checks++;
    }

    private static void rejectSensor(String id, String label, String unit) {
        try {
            HomeAssistantPolicy.sensor(id, label, unit);
            throw new AssertionError("Accepted invalid sensor details");
        } catch (IllegalArgumentException expected) { checks++; }
    }

    private static String repeat(char value, int count) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < count; i++) result.append(value);
        return result.toString();
    }

    private static void check(boolean result, String message) {
        if (!result) throw new AssertionError(message);
        checks++;
    }
}
