package studio.invrse.mero;

import android.content.Context;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** A temporary, paired LAN editor. It exposes only the artwork library. */
final class ArtworkServer {
    private static final int PORT = 8787;
    private static final int MAX_IMAGE = 16 * 1024 * 1024;
    private static final int MAX_JSON = 16 * 1024;
    private static final int MAX_BACKUP = 257 * 1024 * 1024;
    private static final long WINDOW_MS = 30 * 60 * 1000L;
    private static final long IDLE_MS = 15 * 60 * 1000L;
    private static final String COOKIE = "mero_artwork";
    private final Context context;
    private final ArtworkLibrary library;
    private final Runnable onChange;
    private final Playback playback;
    private final SecureRandom random = new SecureRandom();
    private final Set<Socket> active = Collections.synchronizedSet(new HashSet<Socket>());
    private final Map<String, Session> sessions = new LinkedHashMap<>();
    private final Map<String, Attempts> attempts = new LinkedHashMap<>();
    private final Semaphore upload = new Semaphore(1);
    private final ThreadFactory threads = runnable -> {
        Thread thread = new Thread(runnable, "Mero artwork manager");
        thread.setDaemon(true);
        return thread;
    };
    private volatile boolean running;
    private volatile long closesAt;
    private volatile Set<String> authorities = Collections.emptySet();
    private String managerAddress = "";
    private String code = "";
    private ServerSocket listener;
    private ThreadPoolExecutor workers;
    private ScheduledExecutorService deadlines;
    private Attempts globalAttempts = new Attempts(0);

    interface Playback {
        JSONObject snapshot() throws Exception;
        JSONObject command(JSONObject input) throws Exception;
    }

    ArtworkServer(Context context, ArtworkLibrary library, Runnable onChange, Playback playback) {
        this.context = context.getApplicationContext();
        this.library = library;
        this.onChange = onChange;
        this.playback = playback;
    }

    synchronized void start() throws IOException {
        if (isRunning()) return;
        stop();
        List<String> addresses = localAddresses();
        if (addresses.isEmpty()) throw new IOException("Connect the TV to your home network first.");
        ServerSocket server = new ServerSocket();
        try {
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(InetAddress.getByName(addresses.get(0)), PORT), 4);
            server.setSoTimeout(1000);
        } catch (IOException failure) {
            close(server);
            throw new IOException("The artwork manager could not open. Try again.");
        }
        Set<String> hosts = new HashSet<>();
        hosts.add(addresses.get(0) + ":" + PORT);
        authorities = Collections.unmodifiableSet(hosts);
        managerAddress = "http://" + addresses.get(0) + ":" + PORT;
        code = String.format(Locale.ROOT, "%08d", random.nextInt(100_000_000));
        sessions.clear();
        attempts.clear();
        globalAttempts = new Attempts(now());
        closesAt = now() + WINDOW_MS;
        workers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<Runnable>(4), threads);
        ScheduledThreadPoolExecutor clock = new ScheduledThreadPoolExecutor(1, threads);
        clock.setRemoveOnCancelPolicy(true);
        deadlines = clock;
        listener = server;
        running = true;
        ThreadPoolExecutor pool = workers;
        threads.newThread(() -> accept(server, pool, clock)).start();
    }

    synchronized void stop() {
        running = false;
        close(listener);
        listener = null;
        synchronized (active) {
            for (Socket socket : active) close(socket);
            active.clear();
        }
        if (workers != null) workers.shutdownNow();
        if (deadlines != null) deadlines.shutdownNow();
        workers = null;
        deadlines = null;
        sessions.clear();
        attempts.clear();
        code = "";
        managerAddress = "";
        authorities = Collections.emptySet();
    }

    boolean isRunning() { return running && now() < closesAt; }
    synchronized String address() { return isRunning() ? managerAddress : ""; }
    synchronized String pairingCode() { return isRunning() ? code : ""; }

    private void accept(ServerSocket server, ThreadPoolExecutor pool, ScheduledExecutorService clock) {
        try {
            while (running && listener == server && now() < closesAt) {
                Socket socket;
                try { socket = server.accept(); }
                catch (SocketTimeoutException idle) { continue; }
                if (!privatePeer(socket.getInetAddress())) { close(socket); continue; }
                ScheduledFuture<?> deadline = null;
                try {
                    synchronized (this) {
                        if (!isRunning() || listener != server) { close(socket); break; }
                        socket.setSoTimeout(5000);
                        active.add(socket);
                        ScheduledFuture<?> headerDeadline = clock.schedule(() -> close(socket), 10, TimeUnit.SECONDS);
                        deadline = headerDeadline;
                        pool.execute(() -> handle(socket, headerDeadline, clock));
                    }
                } catch (IOException | RejectedExecutionException busy) {
                    if (deadline != null) deadline.cancel(false);
                    active.remove(socket);
                    close(socket);
                }
            }
        } catch (IOException | RejectedExecutionException stopped) {
            // Closing the listener/deadline executor is the normal stop path.
        } finally {
            synchronized (this) {
                if (listener == server) stop();
            }
        }
    }

    private void handle(Socket socket, ScheduledFuture<?> headerDeadline, ScheduledExecutorService clock) {
        ScheduledFuture<?> requestDeadline = null;
        try {
            Socket owned = socket;
            InputStream input = new BufferedInputStream(owned.getInputStream());
            Request request = readRequest(input);
            headerDeadline.cancel(false);
            requestDeadline = clock.schedule(() -> close(owned),
                    request.path.equals("/api/restore") || request.path.equals("/api/backup") ? 180
                            : request.path.endsWith("/import") ? 120 : 60,
                    TimeUnit.SECONDS);
            if (!isRunning()) throw new Failure(503, "The artwork manager is closed. Open it again on the TV.");
            if (!authorities.contains(request.headers.get("host"))) throw new Failure(403, "Open the address shown on the TV.");
            if (request.mutation()) {
                if (!("http://" + request.headers.get("host")).equals(request.headers.get("origin"))
                        || !"1".equals(request.headers.get("x-mero-request")))
                    throw new Failure(403, "Open the artwork manager directly in your browser.");
            }
            if (request.path.equals("/") && request.method.equals("GET")) {
                if (request.length != 0) throw new Failure(400, "Invalid request.");
                int resource = context.getResources().getIdentifier("artwork_manager", "raw", context.getPackageName());
                if (resource == 0) throw new Failure(503, "The artwork manager page is unavailable.");
                try (InputStream page = context.getResources().openRawResource(resource)) {
                    send(owned, 200, "text/html; charset=utf-8", readBounded(page, 256 * 1024), null);
                }
                return;
            }
            if ("cross-site".equals(request.headers.get("sec-fetch-site")))
                throw new Failure(403, "Open the artwork manager directly in your browser.");
            if (request.path.equals("/api/pair") && request.method.equals("POST")) {
                String peer = owned.getInetAddress().getHostAddress();
                checkAttempts(peer);
                JSONObject body = json(request, input);
                String offered = string(body, "code").replace(" ", "");
                if (!offered.matches("[0-9]{8}") || !validCode(offered))
                    throw new Failure(401, "That code did not match. Check the code on your TV.");
                String token = newSession();
                sendJson(owned, 200, new JSONObject(), "Set-Cookie: " + COOKIE + "=" + token
                        + "; HttpOnly; SameSite=Strict; Path=/; Max-Age=1800\r\n");
                return;
            }
            requireSession(request, refreshesSession(request));
            route(owned, request, input);
        } catch (Failure failure) {
            errorQuietly(socket, failure.status, failure.getMessage());
        } catch (JSONException malformed) {
            errorQuietly(socket, 400, "Check the artwork details and try again.");
        } catch (EOFException | SocketTimeoutException incomplete) {
            errorQuietly(socket, 400, "The request was incomplete. Try again.");
        } catch (Exception ignored) {
            errorQuietly(socket, 500, "The artwork manager could not complete that request.");
            // No raw exceptions, headers, codes, paths or credentials enter diagnostics.
        } finally {
            headerDeadline.cancel(false);
            if (requestDeadline != null) requestDeadline.cancel(false);
            active.remove(socket);
            close(socket);
        }
    }

    private void route(Socket socket, Request request, InputStream input) throws Exception {
        try {
            JSONObject result = new JSONObject();
            int status = 200;
            boolean changed = false;
            String[] parts = request.path.split("/");
            if (request.path.equals("/api/library") && request.method.equals("GET")) {
                if (request.length != 0) throw new Failure(400, "Invalid request.");
                sendJson(socket, 200, library.snapshot(), null);
                return;
            } else if (request.path.equals("/api/status") && request.method.equals("GET")) {
                sendJson(socket, 200, sessionStatus(request), null);
                return;
            } else if (request.path.equals("/api/playback") && request.method.equals("GET")) {
                sendJson(socket, 200, playback.snapshot(), null);
                return;
            } else if (request.path.equals("/api/playback") && request.method.equals("POST")) {
                JSONObject command = json(request, input);
                String action = string(command, "action");
                if (action.equals("show")) {
                    id(string(command, "artistId"));
                    id(string(command, "workId"));
                } else if (!action.equals("next") && !action.equals("previous")
                        && !action.equals("pause") && !action.equals("resume"))
                    throw new Failure(400, "Choose an available TV action.");
                sendJson(socket, 200, playback.command(command), null);
                return;
            } else if (request.path.equals("/api/undo") && request.method.equals("POST")) {
                json(request, input);
                library.undoDelete();
                changed = true;
            } else if (request.path.equals("/api/backup") && request.method.equals("GET")) {
                if (!upload.tryAcquire()) throw new Failure(409, "An artwork transfer is already in progress. Try again in a moment.");
                File archive = null;
                try {
                    archive = library.exportBackup();
                    sendFile(socket, archive);
                } finally {
                    if (archive != null) archive.delete();
                    upload.release();
                }
                return;
            } else if (request.path.equals("/api/restore") && request.method.equals("POST")) {
                if (!upload.tryAcquire()) throw new Failure(409, "An artwork transfer is already in progress. Try again in a moment.");
                File archive = null;
                try {
                    archive = receiveBackup(request, input, context.getCacheDir());
                    library.restoreBackup(archive);
                    changed = true;
                } finally {
                    if (archive != null) archive.delete();
                    upload.release();
                }
            } else if (request.path.equals("/api/logout") && request.method.equals("POST")) {
                removeSession(request);
                sendJson(socket, 200, result, "Set-Cookie: " + COOKIE + "=; HttpOnly; SameSite=Strict; Path=/; Max-Age=0\r\n");
                return;
            } else if (request.path.equals("/api/artists") && request.method.equals("POST")) {
                result.put("id", library.createArtist(string(json(request, input), "name")));
                status = 201;
                changed = true;
            } else if (request.path.equals("/api/settings") && request.method.equals("PATCH")) {
                JSONObject data = json(request, input);
                if (!data.has("intervalMinutes") && !data.has("artModeEnabled"))
                    throw new Failure(400, "Choose a TV setting to change.");
                synchronized (library) {
                    JSONObject current = library.snapshot();
                    Object minutes = data.has("intervalMinutes") ? data.get("intervalMinutes") : current.get("intervalMinutes");
                    if (!(minutes instanceof Number) || ((Number) minutes).doubleValue() != ((Number) minutes).intValue())
                        throw new Failure(400, "Choose a rotation interval.");
                    Object artMode = data.has("artModeEnabled") ? data.get("artModeEnabled") : current.optBoolean("artModeEnabled", true);
                    if (!(artMode instanceof Boolean)) throw new Failure(400, "Choose whether Art mode is enabled.");
                    library.updateSettings(((Number) minutes).intValue(), (Boolean) artMode);
                }
                changed = true;
            } else if (parts.length == 4 && parts[1].equals("api") && parts[2].equals("artists")) {
                id(parts[3]);
                if (request.method.equals("PATCH")) library.updateArtist(parts[3], string(json(request, input), "name"));
                else if (request.method.equals("DELETE")) library.deleteArtist(parts[3]);
                else throw new Failure(405, "That action is unavailable.");
                changed = true;
            } else if (parts.length == 5 && parts[1].equals("api") && parts[2].equals("artists") && parts[4].equals("works")
                    && request.method.equals("POST")) {
                id(parts[3]);
                if (!upload.tryAcquire()) throw new Failure(409, "Another image is uploading. Try again in a moment.");
                try {
                    Map<String, String> query = query(request.query);
                    result.put("id", library.addWork(parts[3], query.getOrDefault("title", ""),
                            query.getOrDefault("year", ""), body(request, input, MAX_IMAGE)));
                } finally { upload.release(); }
                status = 201;
                changed = true;
            } else if (parts.length == 5 && parts[1].equals("api") && parts[2].equals("artists") && parts[4].equals("import")
                    && request.method.equals("POST")) {
                id(parts[3]);
                String address = ArtveeImport.pageUrl(string(json(request, input), "url"));
                if (!upload.tryAcquire()) throw new Failure(409, "Another image is uploading. Try again in a moment.");
                try {
                    ArtveeImport.Artwork artwork;
                    try { artwork = ArtveeImport.fetch(address); }
                    catch (IOException unreachable) { throw new Failure(502, "Could not download from Artvee. Check the TV's internet connection and try again."); }
                    result.put("id", library.addWork(parts[3], artwork.title, artwork.year, artwork.image));
                    result.put("title", artwork.title);
                } finally { upload.release(); }
                status = 201;
                changed = true;
            } else if (parts.length == 6 && parts[1].equals("api") && parts[2].equals("artists") && parts[4].equals("works")) {
                id(parts[3]); id(parts[5]);
                if (request.method.equals("DELETE")) library.deleteWork(parts[3], parts[5]);
                else if (request.method.equals("PATCH")) {
                    JSONObject data = json(request, input);
                    Object enabled = data.get("enabled");
                    if (!(enabled instanceof Boolean)) throw new Failure(400, "Choose whether this artwork rotates.");
                    synchronized (library) {
                        JSONObject current = currentWork(library.snapshot(), parts[3], parts[5]);
                        String fit = data.has("fit") ? string(data, "fit") : current.optString("fit", "fill");
                        double focusX = coordinate(data, current, "focusX");
                        double focusY = coordinate(data, current, "focusY");
                        library.updateWork(parts[3], parts[5], string(data, "title"), string(data, "year"),
                                (Boolean) enabled, fit, focusX, focusY);
                    }
                } else throw new Failure(405, "That action is unavailable.");
                changed = true;
            } else if (parts.length == 5 && parts[1].equals("api") && parts[2].equals("images") && request.method.equals("GET")) {
                id(parts[3]); id(parts[4]);
                if (request.length != 0) throw new Failure(400, "Invalid request.");
                try (InputStream image = library.openImage(parts[3], parts[4])) {
                    send(socket, 200, "image/webp", readBounded(image, MAX_IMAGE), null);
                }
                return;
            } else throw new Failure(404, "That artwork or folder was not found.");
            if (changed) {
                try { onChange.run(); } catch (RuntimeException ignored) { /* The persisted change remains valid. */ }
            }
            sendJson(socket, status, result, null);
        } catch (Failure failure) { sendError(socket, failure.status, failure.getMessage()); }
        catch (FileNotFoundException missing) { sendError(socket, 404, "That artwork or folder was not found."); }
        catch (IllegalArgumentException invalid) {
            String message = invalid.getMessage();
            sendError(socket, 400, message != null && message.length() <= 180 && message.indexOf('/') < 0
                    && message.indexOf('\\') < 0 ? message : "Check the artwork details and try again.");
        } catch (JSONException malformed) { sendError(socket, 400, "Check the artwork details and try again."); }
        catch (Exception unavailable) { sendError(socket, 500, "Could not save this change. Try again."); }
    }

    private synchronized boolean validCode(String offered) {
        return isRunning() && MessageDigest.isEqual(code.getBytes(StandardCharsets.US_ASCII), offered.getBytes(StandardCharsets.US_ASCII));
    }

    private synchronized void checkAttempts(String peer) throws Failure {
        long now = now();
        if (now - globalAttempts.started >= 60_000) globalAttempts = new Attempts(now);
        Attempts entry = attempts.get(peer);
        if (entry == null || now - entry.started >= 60_000) {
            entry = new Attempts(now);
            if (attempts.size() >= 64) attempts.remove(attempts.keySet().iterator().next());
            attempts.put(peer, entry);
        }
        if (entry.count >= 5 || globalAttempts.count >= 20) throw new Failure(429, "Too many attempts. Wait one minute and try again.");
        entry.count++;
        globalAttempts.count++;
    }

    private synchronized String newSession() throws Exception {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = hex(bytes);
        if (sessions.size() >= 8) sessions.remove(sessions.keySet().iterator().next());
        sessions.put(digest(token), new Session(now()));
        return token;
    }

    static boolean refreshesSession(Request request) {
        return !request.method.equals("GET") || !(request.path.equals("/api/status") || request.path.equals("/api/playback"));
    }

    private synchronized Session requireSession(Request request, boolean refresh) throws Exception {
        String token = cookie(request.headers.get("cookie"));
        Session session = token == null ? null : sessions.get(digest(token));
        long now = now();
        if (session == null || now - session.lastUsed >= IDLE_MS || !isRunning()) {
            if (token != null) sessions.remove(digest(token));
            throw new Failure(401, "Enter the code shown on your TV.");
        }
        if (refresh) session.lastUsed = now;
        return session;
    }

    private JSONObject sessionStatus(Request request) throws Exception {
        long sharingRemaining;
        long sessionRemaining;
        synchronized (this) {
            Session session = requireSession(request, false);
            long time = now();
            sharingRemaining = Math.max(0, closesAt - time);
            sessionRemaining = Math.max(0, IDLE_MS - (time - session.lastUsed));
        }
        // Do not hold the server monitor during library IO: stop() also runs on the TV's main thread.
        return new JSONObject().put("sharingRemainingMs", sharingRemaining)
                .put("sessionRemainingMs", sessionRemaining)
                .put("libraryRevision", library.revision());
    }

    private synchronized void removeSession(Request request) throws Exception {
        String token = cookie(request.headers.get("cookie"));
        if (token != null) sessions.remove(digest(token));
    }

    private static String cookie(String value) {
        if (value == null) return null;
        String found = null;
        for (String field : value.split(";")) {
            String[] item = field.trim().split("=", 2);
            if (item.length == 2 && item[0].equals(COOKIE)) {
                if (found != null || !item[1].matches("[0-9a-f]{64}")) return null;
                found = item[1];
            }
        }
        return found;
    }

    private static String digest(String value) throws Exception {
        return hex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII)));
    }

    private static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) text.append(Character.forDigit((value >>> 4) & 15, 16)).append(Character.forDigit(value & 15, 16));
        return text.toString();
    }

    static Request readRequest(InputStream input) throws Exception {
        String first = line(input, 4096);
        String[] start = first.split(" ", -1);
        if (start.length != 3 || !start[2].equals("HTTP/1.1")
                || !(start[0].equals("GET") || start[0].equals("POST") || start[0].equals("PATCH") || start[0].equals("DELETE")))
            throw new Failure(400, "Invalid request.");
        if (!start[1].startsWith("/") || start[1].startsWith("//")) throw new Failure(400, "Invalid request.");
        URI target;
        try { target = new URI(start[1]); }
        catch (URISyntaxException invalid) { throw new Failure(400, "Invalid request."); }
        String path = target.getRawPath();
        if (target.getRawFragment() != null || path.indexOf('%') >= 0 || path.indexOf('\\') >= 0
                || path.contains("//") || path.contains("/.") || !path.matches("/[A-Za-z0-9_/-]*"))
            throw new Failure(400, "Invalid request.");
        Map<String, String> headers = new HashMap<>();
        int total = first.length();
        for (int count = 0; ; count++) {
            String row = line(input, 4096);
            total += row.length() + 2;
            if (total > 16 * 1024 || count >= 40) throw new Failure(400, "Request headers are too large.");
            if (row.isEmpty()) break;
            int colon = row.indexOf(':');
            if (colon < 1 || !row.substring(0, colon).matches("[A-Za-z0-9-]+")) throw new Failure(400, "Invalid request.");
            String key = row.substring(0, colon).toLowerCase(Locale.ROOT);
            if (headers.put(key, row.substring(colon + 1).trim()) != null) throw new Failure(400, "Duplicate request header.");
        }
        if (!headers.containsKey("host") || headers.containsKey("transfer-encoding") || headers.containsKey("expect"))
            throw new Failure(400, "Invalid request.");
        String length = headers.getOrDefault("content-length", "0");
        if (!length.matches("[0-9]{1,10}")) throw new Failure(400, "Invalid content length.");
        long size = Long.parseLong(length);
        if (start[0].equals("GET") && size != 0) throw new Failure(400, "Invalid request.");
        boolean restore = start[0].equals("POST") && path.equals("/api/restore");
        if (size > (restore ? MAX_BACKUP : MAX_IMAGE))
            throw new Failure(413, restore ? "Choose a library backup smaller than 257 MB." : "Choose an image smaller than 16 MB.");
        return new Request(start[0], path, target.getRawQuery(), headers, (int) size);
    }

    private static String line(InputStream input, int limit) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        while (true) {
            int value = input.read();
            if (value == -1) throw new EOFException();
            if (value == '\r') {
                if (input.read() != '\n') throw new Failure(400, "Invalid request.");
                return new String(bytes.toByteArray(), StandardCharsets.US_ASCII);
            }
            if (value < 32 || value > 126 || bytes.size() >= limit) throw new Failure(400, "Invalid request.");
            bytes.write(value);
        }
    }

    private static JSONObject json(Request request, InputStream input) throws Exception {
        String type = request.headers.getOrDefault("content-type", "").toLowerCase(Locale.ROOT);
        if (!(type.equals("application/json") || type.startsWith("application/json;")))
            throw new Failure(400, "Send artwork details as JSON.");
        try { return new JSONObject(new String(body(request, input, MAX_JSON), StandardCharsets.UTF_8)); }
        catch (JSONException malformed) { throw new Failure(400, "Check the artwork details and try again."); }
    }

    private static String string(JSONObject body, String key) throws Exception {
        Object value = body.get(key);
        if (!(value instanceof String)) throw new Failure(400, "Check the artwork details and try again.");
        return (String) value;
    }

    private static JSONObject currentWork(JSONObject state, String artistId, String workId) throws Exception {
        org.json.JSONArray artists = state.getJSONArray("artists");
        for (int i = 0; i < artists.length(); i++) {
            JSONObject artist = artists.getJSONObject(i);
            if (!artist.getString("id").equals(artistId)) continue;
            org.json.JSONArray works = artist.getJSONArray("works");
            for (int j = 0; j < works.length(); j++) {
                JSONObject work = works.getJSONObject(j);
                if (work.getString("id").equals(workId)) return work;
            }
        }
        throw new FileNotFoundException("Artwork not found");
    }

    private static double coordinate(JSONObject offered, JSONObject current, String key) throws Exception {
        Object value = offered.has(key) ? offered.get(key) : current.optDouble(key, .5);
        if (!(value instanceof Number)) throw new Failure(400, "Choose a valid artwork position.");
        double coordinate = ((Number) value).doubleValue();
        if (Double.isNaN(coordinate) || Double.isInfinite(coordinate) || coordinate < 0 || coordinate > 1)
            throw new Failure(400, "Choose a valid artwork position.");
        return coordinate;
    }

    static File receiveBackup(Request request, InputStream input, File directory) throws Exception {
        String type = request.headers.getOrDefault("content-type", "").toLowerCase(Locale.ROOT);
        if (!(type.equals("application/zip") || type.equals("application/octet-stream")))
            throw new Failure(400, "Choose an exported artwork library ZIP.");
        if (request.length <= 0 || request.length > MAX_BACKUP) throw new Failure(413, "This library backup is empty or too large.");
        File archive = File.createTempFile("mero-restore-", ".zip", directory);
        boolean received = false;
        try {
            try (FileOutputStream output = new FileOutputStream(archive)) {
                byte[] buffer = new byte[32 * 1024];
                int remaining = request.length;
                while (remaining > 0) {
                    int count = input.read(buffer, 0, Math.min(buffer.length, remaining));
                    if (count < 0) throw new EOFException();
                    output.write(buffer, 0, count);
                    remaining -= count;
                }
                output.getFD().sync();
            }
            received = true;
            return archive;
        } finally {
            if (!received) archive.delete();
        }
    }

    private static byte[] body(Request request, InputStream input, int maximum) throws Exception {
        if (request.length > maximum) throw new Failure(413, "This upload is too large.");
        byte[] bytes = new byte[request.length];
        int offset = 0;
        while (offset < bytes.length) {
            int count = input.read(bytes, offset, bytes.length - offset);
            if (count < 0) throw new EOFException();
            offset += count;
        }
        return bytes;
    }

    private static byte[] readBounded(InputStream input, int maximum) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (bytes.size() + count > maximum) throw new IOException("Resource is too large");
            bytes.write(buffer, 0, count);
        }
        return bytes.toByteArray();
    }

    private static Map<String, String> query(String text) throws Exception {
        Map<String, String> values = new HashMap<>();
        if (text == null || text.isEmpty()) return values;
        for (String part : text.split("&")) {
            String[] pair = part.split("=", 2);
            String key = URLDecoder.decode(pair[0], "UTF-8");
            String value = pair.length == 1 ? "" : URLDecoder.decode(pair[1], "UTF-8");
            if (!(key.equals("title") || key.equals("year")) || values.put(key, value) != null)
                throw new Failure(400, "Check the artwork details and try again.");
        }
        return values;
    }

    private static void id(String value) throws Failure {
        if (!value.matches("[a-z0-9_-]{1,80}")) throw new Failure(404, "That artwork or folder was not found.");
    }

    private static void sendJson(Socket socket, int status, JSONObject value, String extra) throws IOException {
        send(socket, status, "application/json; charset=utf-8", value.toString().getBytes(StandardCharsets.UTF_8), extra);
    }

    private static void sendError(Socket socket, int status, String message) throws IOException {
        JSONObject data = new JSONObject();
        try { data.put("error", message); } catch (JSONException impossible) { throw new IOException("Invalid response"); }
        sendJson(socket, status, data, status == 429 ? "Retry-After: 60\r\n" : null);
    }

    private static void errorQuietly(Socket socket, int status, String message) {
        try { sendError(socket, status, message); } catch (IOException ignored) { }
    }

    private static void send(Socket socket, int status, String type, byte[] bytes, String extra) throws IOException {
        OutputStream output = socket.getOutputStream();
        sendHeaders(output, status, type, bytes.length, extra);
        output.write(bytes);
        output.flush();
    }

    private static void sendFile(Socket socket, File archive) throws IOException {
        long length = archive.length();
        if (length <= 0 || length > MAX_BACKUP) throw new IOException("Library backup is unavailable");
        try (InputStream input = new FileInputStream(archive)) {
            OutputStream output = socket.getOutputStream();
            sendHeaders(output, 200, "application/zip", length,
                    "Content-Disposition: attachment; filename=\"mero-artwork-library.zip\"\r\n");
            byte[] buffer = new byte[32 * 1024];
            long remaining = length;
            while (remaining > 0) {
                int count = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (count < 0) throw new EOFException();
                output.write(buffer, 0, count);
                remaining -= count;
            }
            output.flush();
        }
    }

    private static void sendHeaders(OutputStream output, int status, String type, long length, String extra) throws IOException {
        String reason = status == 200 ? "OK" : status == 201 ? "Created" : "Request failed";
        String head = "HTTP/1.1 " + status + " " + reason + "\r\nContent-Type: " + type
                + "\r\nContent-Length: " + length + "\r\nConnection: close\r\nCache-Control: no-store"
                + "\r\nX-Content-Type-Options: nosniff\r\nX-Frame-Options: DENY"
                + "\r\nReferrer-Policy: no-referrer\r\nContent-Security-Policy: default-src 'none'; script-src 'unsafe-inline';"
                + " style-src 'unsafe-inline'; img-src 'self' blob:; connect-src 'self'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'\r\n"
                + (extra == null ? "" : extra) + "\r\n";
        output.write(head.getBytes(StandardCharsets.US_ASCII));
    }

    private static List<String> localAddresses() throws SocketException {
        List<String> preferred = new ArrayList<>();
        List<String> other = new ArrayList<>();
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        if (interfaces == null) return preferred;
        while (interfaces.hasMoreElements()) {
            NetworkInterface network = interfaces.nextElement();
            String name = network.getName();
            if (!network.isUp() || network.isLoopback() || name.startsWith("tun")
                    || name.startsWith("tap") || name.startsWith("ppp")) continue;
            Enumeration<InetAddress> addresses = network.getInetAddresses();
            while (addresses.hasMoreElements()) {
                InetAddress address = addresses.nextElement();
                if (!privatePeer(address)) continue;
                (network.getName().startsWith("wlan") || network.getName().startsWith("eth") ? preferred : other).add(address.getHostAddress());
            }
        }
        preferred.addAll(other);
        return preferred;
    }

    static boolean privatePeer(InetAddress address) {
        return address instanceof Inet4Address && !address.isLoopbackAddress()
                && (address.isSiteLocalAddress() || address.isLinkLocalAddress());
    }

    private static long now() { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime()); }
    private static void close(java.io.Closeable value) { if (value != null) try { value.close(); } catch (IOException ignored) { } }

    static final class Request {
        final String method, path, query;
        final Map<String, String> headers;
        final int length;
        Request(String method, String path, String query, Map<String, String> headers, int length) {
            this.method = method; this.path = path; this.query = query; this.headers = headers; this.length = length;
        }
        boolean mutation() { return !method.equals("GET"); }
    }
    static final class Failure extends Exception {
        final int status;
        Failure(int status, String message) { super(message); this.status = status; }
    }
    private static final class Session { long lastUsed; Session(long time) { lastUsed = time; } }
    private static final class Attempts { final long started; int count; Attempts(long time) { started = time; } }
}
