package studio.invrse.mero;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.HttpsURLConnection;

/** Imports one public-domain image from an Artvee artwork page. Contacts only artvee.com and mdl.artvee.com. */
final class ArtveeImport {
    private static final int MAX_PAGE = 2 * 1024 * 1024;
    private static final int MAX_IMAGE = 16 * 1024 * 1024;
    private static final long TIMEOUT_MS = 100_000;
    private static final Pattern SLUG = Pattern.compile("/dl/([a-z0-9-]{1,200})/?");
    private static final Pattern TITLE = Pattern.compile("<h1[^>]*class=\"[^\"]*product_title[^\"]*\"[^>]*>(.*?)</h1>", Pattern.DOTALL);
    private static final Pattern DOWNLOAD = Pattern.compile("href=\"(https://mdl\\.artvee\\.com/sdl/[^\"]+)\"");
    private static final Pattern DATED = Pattern.compile("(.*?)\\s*\\(([^()]*[0-9][^()]*)\\)");
    private static final Pattern ENTITY = Pattern.compile("&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|amp|lt|gt|quot|apos|nbsp);");

    static final class Artwork {
        final String title, year, imageUrl;
        byte[] image;
        Artwork(String title, String year, String imageUrl) { this.title = title; this.year = year; this.imageUrl = imageUrl; }
    }

    static Artwork fetch(String address) throws IOException {
        long deadline = System.nanoTime() / 1_000_000 + TIMEOUT_MS;
        Artwork artwork = parse(new String(get(pageUrl(address), MAX_PAGE, deadline), StandardCharsets.UTF_8));
        artwork.image = get(artwork.imageUrl, MAX_IMAGE, deadline);
        return artwork;
    }

    /** Returns the canonical page URL, or rejects anything other than an Artvee artwork page. */
    static String pageUrl(String value) {
        String invalid = "Paste the link of an artwork page on artvee.com.";
        if (value == null || value.trim().isEmpty() || value.trim().length() > 400) throw new IllegalArgumentException(invalid);
        URI uri;
        try { uri = new URI(value.trim()); }
        catch (URISyntaxException malformed) { throw new IllegalArgumentException(invalid); }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        Matcher slug = SLUG.matcher(uri.getRawPath() == null ? "" : uri.getRawPath());
        if (!"https".equalsIgnoreCase(uri.getScheme()) || !(host.equals("artvee.com") || host.equals("www.artvee.com"))
                || uri.getRawUserInfo() != null || uri.getPort() != -1 || !slug.matches())
            throw new IllegalArgumentException(invalid);
        return "https://artvee.com/dl/" + slug.group(1) + "/";
    }

    static Artwork parse(String html) {
        Matcher download = DOWNLOAD.matcher(html);
        if (!download.find()) throw new IllegalArgumentException("That Artvee page has no standard download.");
        String imageUrl = decode(download.group(1));
        URI image;
        try { image = new URI(imageUrl); }
        catch (URISyntaxException malformed) { throw new IllegalArgumentException("That Artvee page has no standard download."); }
        if (!"https".equals(image.getScheme()) || !"mdl.artvee.com".equals(image.getHost())
                || image.getPort() != -1 || image.getRawUserInfo() != null)
            throw new IllegalArgumentException("That Artvee page has no standard download.");

        Matcher heading = TITLE.matcher(html);
        String title = heading.find() ? text(heading.group(1)) : "";
        String year = "";
        Matcher dated = DATED.matcher(title);
        if (dated.matches() && !dated.group(1).isEmpty() && dated.group(2).trim().length() <= 32) {
            title = dated.group(1);
            year = dated.group(2).trim();
        }
        if (title.isEmpty()) title = "Untitled";
        if (title.length() > 150) title = title.substring(0, 150).trim();
        return new Artwork(title, year, imageUrl);
    }

    private static String text(String html) {
        String plain = decode(html.replaceAll("<[^>]*>", " "));
        StringBuilder clean = new StringBuilder(plain.length());
        for (int i = 0; i < plain.length(); i++) {
            char value = plain.charAt(i);
            clean.append(value < 32 || value == 127 || Character.isWhitespace(value) || value == '\u00a0' ? ' ' : value);
        }
        return clean.toString().replaceAll(" +", " ").trim();
    }

    private static String decode(String value) {
        Matcher entity = ENTITY.matcher(value);
        StringBuffer result = new StringBuffer();
        while (entity.find()) {
            String name = entity.group(1);
            String replacement;
            if (name.startsWith("#")) {
                int code = name.length() > 1 && (name.charAt(1) == 'x' || name.charAt(1) == 'X')
                        ? Integer.parseInt(name.substring(2), 16) : Integer.parseInt(name.substring(1));
                replacement = Character.isValidCodePoint(code) ? new String(Character.toChars(code)) : " ";
            } else if (name.equals("amp")) replacement = "&";
            else if (name.equals("lt")) replacement = "<";
            else if (name.equals("gt")) replacement = ">";
            else if (name.equals("quot")) replacement = "\"";
            else if (name.equals("apos")) replacement = "'";
            else replacement = "\u00a0";
            entity.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        entity.appendTail(result);
        return result.toString();
    }

    private static byte[] get(String address, int maximum, long deadline) throws IOException {
        HttpsURLConnection connection = (HttpsURLConnection) new URL(address).openConnection();
        try {
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(20_000);
            connection.setRequestProperty("User-Agent", "Mero (Android TV; Artvee import)");
            int response = connection.getResponseCode();
            if (response == 404 || response == 410) throw new IllegalArgumentException("That Artvee artwork was not found.");
            if (response != 200) throw new IOException("Unexpected response");
            if (connection.getContentLengthLong() > maximum) throw new IllegalArgumentException("The Artvee image is larger than 16 MB.");
            try (InputStream input = connection.getInputStream()) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[32 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (bytes.size() + count > maximum) throw new IllegalArgumentException("The Artvee image is larger than 16 MB.");
                    if (System.nanoTime() / 1_000_000 > deadline) throw new IOException("Download took too long");
                    bytes.write(buffer, 0, count);
                }
                return bytes.toByteArray();
            }
        } finally { connection.disconnect(); }
    }

    private ArtveeImport() {}
}
