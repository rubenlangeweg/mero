package studio.invrse.mero;

import java.net.URI;

/** Validation shared by optional Home Assistant setup and its host checks. */
final class HomeAssistantPolicy {
    static String normalizeUrl(String value) throws Exception {
        if (value == null || controls(value))
            throw new IllegalArgumentException("A plain HTTPS origin is required");
        URI uri = new URI(value.trim());
        String path = uri.getRawPath();
        String authority = uri.getRawAuthority();
        int port = uri.getPort();
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getHost().isEmpty() || uri.getUserInfo() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null
                || !("".equals(path) || "/".equals(path))
                || authority == null || authority.endsWith(":")
                || (port != -1 && (port < 1 || port > 65535)))
            throw new IllegalArgumentException("A plain HTTPS origin is required");
        return new URI("https", null, uri.getHost(), port, null, null, null).toASCIIString();
    }

    static void sensor(String id, String label, String unit) {
        if (id == null || !id.matches("sensor\\.[a-z0-9_]+"))
            throw new IllegalArgumentException("Invalid sensor entity");
        if (label == null || unit == null || controls(label) || controls(unit)
                || label.trim().isEmpty() || label.trim().length() > 30 || unit.trim().length() > 8)
            throw new IllegalArgumentException("Invalid sensor label or unit");
    }

    static boolean enabled(boolean hasSavedEnabled, boolean savedEnabled, boolean complete) {
        return complete && (!hasSavedEnabled || savedEnabled);
    }

    private static boolean controls(String value) {
        for (int i = 0; i < value.length(); i++)
            if (value.charAt(i) < 32 || value.charAt(i) == 127) return true;
        return false;
    }

    private HomeAssistantPolicy() {}
}
