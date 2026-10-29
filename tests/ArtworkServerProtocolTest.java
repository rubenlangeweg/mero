package studio.invrse.mero;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.InputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

/** Host checks for the boundary that receives untrusted LAN requests. */
public final class ArtworkServerProtocolTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        ArtworkServer.Request request = read("GET /api/library HTTP/1.1\r\nHost: 192.0.2.10:8787\r\n\r\n");
        check(request.method.equals("GET") && request.path.equals("/api/library") && request.length == 0, "valid GET");
        check(!request.mutation(), "GET does not mutate");
        request = read("POST /api/artists/a/works?title=My%20work&year=1984 HTTP/1.1\r\nHost: 192.0.2.10:8787\r\nContent-Length: 12\r\n\r\n");
        check(request.length == 12 && request.query.equals("title=My%20work&year=1984") && request.mutation(), "upload preserves encoded metadata");
        request = read("POST /api/restore HTTP/1.1\r\nHost: tv\r\nContent-Length: 269484032\r\nContent-Type: application/zip\r\n\r\n");
        check(request.length == 269484032, "restore accepts the bounded library size without allocating its body");
        rejected("POST /api/restore HTTP/1.1\r\nHost: tv\r\nContent-Length: 269484033\r\n\r\n", 413, "rejects oversized library before allocation");
        rejected("PATCH /api/restore HTTP/1.1\r\nHost: tv\r\nContent-Length: 269484032\r\n\r\n", 413, "large-body allowance is exclusive to restore POST");
        rejected("POST /api/restore/extra HTTP/1.1\r\nHost: tv\r\nContent-Length: 269484032\r\n\r\n", 413, "large-body allowance requires the exact restore route");
        rejected("GET /api/playback HTTP/1.1\r\nHost: tv\r\nContent-Length: 1\r\n\r\n", 400, "polling GET cannot carry a body");
        check(!ArtworkServer.refreshesSession(read("GET /api/status HTTP/1.1\r\nHost: tv\r\n\r\n")), "status heartbeat cannot renew authentication");
        check(!ArtworkServer.refreshesSession(read("GET /api/playback HTTP/1.1\r\nHost: tv\r\n\r\n")), "playback heartbeat cannot renew authentication");
        check(ArtworkServer.refreshesSession(read("POST /api/playback HTTP/1.1\r\nHost: tv\r\n\r\n")), "deliberate playback action renews authentication");
        check(ArtworkServer.refreshesSession(read("GET /api/backup HTTP/1.1\r\nHost: tv\r\n\r\n")), "deliberate backup download renews authentication");
        backupTransfer();
        artveeImport();
        rejected("GET / HTTP/1.1\r\n\r\n", 400, "requires Host");
        rejected("GET / HTTP/1.0\r\nHost: 192.0.2.10:8787\r\n\r\n", 400, "rejects ambiguous HTTP version");
        rejected("GET http://evil.example/api/library HTTP/1.1\r\nHost: 192.0.2.10:8787\r\n\r\n", 400, "rejects absolute-form target");
        rejected("GET //evil.example/api/library HTTP/1.1\r\nHost: 192.0.2.10:8787\r\n\r\n", 400, "rejects authority target");
        rejected("GET /api/../library HTTP/1.1\r\nHost: 192.0.2.10:8787\r\n\r\n", 400, "rejects dot traversal");
        rejected("GET /api/%2e%2e/library HTTP/1.1\r\nHost: 192.0.2.10:8787\r\n\r\n", 400, "rejects encoded traversal");
        rejected("GET /api\\library HTTP/1.1\r\nHost: 192.0.2.10:8787\r\n\r\n", 400, "rejects backslash traversal");
        rejected("GET /api/library#fragment HTTP/1.1\r\nHost: 192.0.2.10:8787\r\n\r\n", 400, "rejects fragments");
        rejected("GET / HTTP/1.1\nHost: 192.0.2.10:8787\n\n", 400, "requires unambiguous CRLF framing");
        rejected("GET / HTTP/1.1\r\nHost: first\r\nhOSt: second\r\n\r\n", 400, "rejects duplicate Host");
        rejected("POST /api/pair HTTP/1.1\r\nHost: tv\r\nContent-Length: 3\r\nContent-Length: 7\r\n\r\n", 400, "rejects duplicate body lengths");
        rejected("POST /api/pair HTTP/1.1\r\nHost: tv\r\nTransfer-Encoding: chunked\r\n\r\n", 400, "rejects alternate body framing");
        rejected("POST /api/pair HTTP/1.1\r\nHost: tv\r\nContent-Length: -1\r\n\r\n", 400, "rejects negative body length");
        rejected("POST /api/pair HTTP/1.1\r\nHost: tv\r\nContent-Length: 1x\r\n\r\n", 400, "rejects nonnumeric body length");
        rejected("POST /api/pair HTTP/1.1\r\nHost: tv\r\nContent-Length: 16777217\r\n\r\n", 413, "rejects oversized body before allocation");
        rejected("POST /api/pair HTTP/1.1\r\nHost: tv\r\nExpect: 100-continue\r\n\r\n", 400, "rejects unsupported continuation framing");
        check(ArtworkServer.privatePeer(InetAddress.getByName("192.168.1.2")), "accepts private LAN");
        check(ArtworkServer.privatePeer(InetAddress.getByName("10.0.1.2")), "accepts routed private LAN");
        check(ArtworkServer.privatePeer(InetAddress.getByName("172.16.4.5")), "accepts private subnet");
        check(ArtworkServer.privatePeer(InetAddress.getByName("169.254.4.5")), "accepts local link");
        check(!ArtworkServer.privatePeer(InetAddress.getByName("8.8.8.8")), "rejects public IPv4");
        check(!ArtworkServer.privatePeer(InetAddress.getByName("172.15.4.5")), "rejects public subnet boundary");
        check(!ArtworkServer.privatePeer(InetAddress.getByName("127.0.0.1")), "rejects loopback as a LAN peer");
        check(!ArtworkServer.privatePeer(InetAddress.getByName("::1")), "rejects IPv6");
        System.out.println("ArtworkServerProtocolTest: " + checks + " checks passed");
    }

    private static void backupTransfer() throws Exception {
        File directory = Files.createTempDirectory("mero-transfer-test-").toFile();
        File archive = null;
        try {
            byte[] source = new byte[100_003];
            for (int i = 0; i < source.length; i++) source[i] = (byte) (i % 251);
            final int[] largestRead = { 0 };
            InputStream input = new ByteArrayInputStream(source) {
                @Override public synchronized int read(byte[] buffer, int offset, int length) {
                    largestRead[0] = Math.max(largestRead[0], length);
                    return super.read(buffer, offset, length);
                }
            };
            ArtworkServer.Request request = read("POST /api/restore HTTP/1.1\r\nHost: tv\r\nContent-Type: application/zip\r\nContent-Length: 100003\r\n\r\n");
            archive = ArtworkServer.receiveBackup(request, input, directory);
            check(Arrays.equals(source, Files.readAllBytes(archive.toPath())), "restore transfer preserves bytes");
            check(largestRead[0] <= 32 * 1024, "restore transfer uses fixed-size buffers");
            check(archive.delete(), "finished restore temporary can be removed");
            archive = null;
            try {
                ArtworkServer.receiveBackup(request, new ByteArrayInputStream(new byte[3]), directory);
                throw new AssertionError("truncated restore accepted");
            } catch (EOFException expected) {
                check(directory.list().length == 0, "incomplete restore leaves no temporary file");
            }
            ArtworkServer.Request wrongType = read("POST /api/restore HTTP/1.1\r\nHost: tv\r\nContent-Type: text/plain\r\nContent-Length: 3\r\n\r\n");
            try {
                ArtworkServer.receiveBackup(wrongType, new ByteArrayInputStream(new byte[3]), directory);
                throw new AssertionError("wrong restore content type accepted");
            } catch (ArtworkServer.Failure expected) {
                check(expected.status == 400 && directory.list().length == 0, "rejects non-backup type before writing");
            }
        } finally {
            if (archive != null) archive.delete();
            File[] remaining = directory.listFiles();
            if (remaining != null) for (File file : remaining) file.delete();
            directory.delete();
        }
    }

    private static void artveeImport() {
        check(ArtveeImport.pageUrl(" https://www.artvee.com/dl/tokaido-gojusantsugi-pl-50 ")
                .equals("https://artvee.com/dl/tokaido-gojusantsugi-pl-50/"), "Artvee link is canonicalized");
        for (String link : new String[]{"http://artvee.com/dl/a/", "https://artvee.com.evil.example/dl/a/",
                "https://evilartvee.com/dl/a/", "https://user@artvee.com/dl/a/", "https://artvee.com:8443/dl/a/",
                "https://artvee.com/artist/a/", "https://artvee.com/dl/a/b/", "https://artvee.com/dl/%2e%2e/", "file:///dl/a/"}) {
            try { ArtveeImport.pageUrl(link); throw new AssertionError("Artvee import accepted " + link); }
            catch (IllegalArgumentException expected) { checks++; }
        }
        ArtveeImport.Artwork work = ArtveeImport.parse("<h1 itemprop=\"name\" class=\"product_title entry-title\"><a href=\"x\">\n"
                + "Bauernm&#228;dchen (Farm Girl) (1886) </a></h1><a href=\"https://mdl.artvee.com/sdl/1sdl.jpg?a=1&amp;b=2\">Download</a>");
        check(work.title.equals("Bauernm\u00e4dchen (Farm Girl)") && work.year.equals("1886"), "Artvee title keeps inner parentheses and splits the date");
        check(work.imageUrl.equals("https://mdl.artvee.com/sdl/1sdl.jpg?a=1&b=2"), "Artvee download link is decoded");
        try { ArtveeImport.parse("<a href=\"https://mdl.artvee.com.evil.example/sdl/1.jpg\">"); throw new AssertionError("foreign download accepted"); }
        catch (IllegalArgumentException expected) { checks++; }
    }

    private static ArtworkServer.Request read(String text) throws Exception {
        return ArtworkServer.readRequest(new ByteArrayInputStream(text.getBytes(StandardCharsets.US_ASCII)));
    }

    private static void rejected(String text, int status, String description) throws Exception {
        try { read(text); throw new AssertionError(description + ": accepted"); }
        catch (ArtworkServer.Failure expected) { check(expected.status == status, description); }
    }

    private static void check(boolean result, String description) {
        if (!result) throw new AssertionError(description);
        checks++;
    }
}
