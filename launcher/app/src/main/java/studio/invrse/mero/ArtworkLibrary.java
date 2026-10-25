package studio.invrse.mero;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Private, atomic artwork metadata and normalized images shared by Home and its local manager. */
final class ArtworkLibrary {
    private static final int MAX_UPLOAD = 16 * 1024 * 1024;
    private static final int MAX_METADATA = 512 * 1024;
    private static final int MAX_WORKS = 200;
    private static final long MAX_IMAGES = 256L * 1024 * 1024;
    private final Context context;
    private final File directory;
    private final File imagesDirectory;
    private final AtomicFile metadata;
    private JSONObject data;
    private boolean damaged;

    ArtworkLibrary(Context context) {
        this.context = context.getApplicationContext();
        directory = new File(this.context.getFilesDir(), "artwork-library");
        imagesDirectory = new File(directory, "images");
        metadata = new AtomicFile(new File(directory, "library.json"));
        try {
            if (!imagesDirectory.isDirectory() && !imagesDirectory.mkdirs())
                throw new IOException("Artwork storage is unavailable");
            JSONObject bundled = bundled();
            File base = metadata.getBaseFile();
            if (base.exists() || new File(base.getPath() + ".bak").exists()) {
                try (InputStream input = metadata.openRead()) {
                    data = new JSONObject(read(input, MAX_METADATA));
                    validate(data);
                } catch (Exception invalid) {
                    // Preserve recoverable user data; a bad catalog must not overwrite it with the seed.
                    data = bundled;
                    damaged = true;
                }
            } else {
                data = bundled;
                commit(copy());
            }
            File[] unfinished = imagesDirectory.listFiles();
            if (unfinished != null) for (File file : unfinished)
                if (file.getName().startsWith(".upload-") && file.isFile()) file.delete();
            if (!damaged) cleanUnreferencedImages(imagesDirectory, imagePaths(data));
            File[] restores = directory.listFiles();
            if (restores != null) for (File file : restores)
                if (file.getName().startsWith(".restore-")) removeTree(file);
        } catch (Exception unavailable) {
            throw new IllegalStateException("Cannot open artwork storage", unavailable);
        }
    }

    synchronized JSONObject snapshot() throws Exception {
        JSONObject snapshot = copy();
        snapshot.remove("_undo");
        snapshot.put("canUndo", !damaged && data.has("_undo"));
        if (damaged) snapshot.put("warning", "Saved library needs repair. Bundled artworks are shown.");
        return snapshot;
    }

    synchronized long revision() { return data.optLong("revision", 0); }

    synchronized String createArtist(String name) throws Exception {
        name = label(name, "Artist name", 150, false);
        JSONObject next = copy();
        JSONArray artists = next.getJSONArray("artists");
        if (artists.length() >= 100) throw new IllegalArgumentException("Keep the library below 100 artist folders");
        uniqueName(artists, null, name);
        String id = UUID.randomUUID().toString();
        artists.put(new JSONObject().put("id", id).put("name", name).put("works", new JSONArray()));
        commit(next);
        return id;
    }

    synchronized void updateArtist(String id, String name) throws Exception {
        name = label(name, "Artist name", 150, false);
        JSONObject next = copy();
        JSONObject artist = artist(next, id);
        uniqueName(next.getJSONArray("artists"), id, name);
        artist.put("name", name);
        commit(next);
    }

    synchronized void deleteArtist(String id) throws Exception {
        JSONObject next = copy();
        JSONObject removed = artist(next, id);
        JSONArray artists = next.getJSONArray("artists");
        int index = 0;
        for (int i = 0; i < artists.length(); i++) if (artists.getJSONObject(i).getString("id").equals(id)) {
            index = i;
            artists.remove(i);
            break;
        }
        keepSelection(next);
        JSONObject discarded = data.optJSONObject("_undo");
        next.put("_undo", new JSONObject().put("artist", removed).put("artistIndex", index).put("entireArtist", true));
        commit(next);
        cleanDiscardedUndo(discarded);
    }

    String addWork(String artistId, String title, String year, byte[] bytes) throws Exception {
        safeId(artistId);
        title = label(title, "Artwork title", 150, false);
        year = label(year, "Year", 32, true);
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_UPLOAD)
            throw new IllegalArgumentException("Choose a JPEG, PNG or WebP image up to 16 MB");
        synchronized (this) {
            writable();
            artist(data, artistId);
            if (countWorks(data) >= MAX_WORKS) throw new IllegalArgumentException("Keep the library below 200 artworks");
        }

        String id = UUID.randomUUID().toString();
        File temporary = new File(imagesDirectory, ".upload-" + id);
        File image = null;
        boolean committed = false;
        try {
            normalize(bytes, temporary);
            synchronized (this) {
                JSONObject next = copy();
                JSONObject artist = artist(next, artistId); // The folder may have changed during decoding.
                if (countWorks(next) >= MAX_WORKS) throw new IllegalArgumentException("Keep the library below 200 artworks");
                if (imageBytes(imagesDirectory) + temporary.length() > MAX_IMAGES)
                    throw new IllegalArgumentException("Artwork storage is full. Remove some images first.");
                String relative = "images/" + artistId + "/" + id + ".webp";
                image = resolveImage(relative);
                File parent = image.getParentFile();
                if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot save artwork image");
                if (image.exists() || !temporary.renameTo(image)) throw new IOException("Cannot save artwork image");
                artist.getJSONArray("works").put(new JSONObject().put("id", id).put("title", title)
                        .put("year", year).put("file", relative).put("enabled", true)
                        .put("fit", "fill").put("focusX", .5).put("focusY", .5));
                commit(next);
                committed = true;
            }
            return id;
        } finally {
            temporary.delete();
            if (!committed && image != null) image.delete();
        }
    }

    synchronized void updateWork(String artistId, String workId, String title, String year, boolean enabled) throws Exception {
        JSONObject current = work(data, artistId, workId);
        updateWork(artistId, workId, title, year, enabled, current.optString("fit", "fill"),
                current.optDouble("focusX", .5), current.optDouble("focusY", .5));
    }

    synchronized void updateWork(String artistId, String workId, String title, String year, boolean enabled,
                                 String fit, double focusX, double focusY) throws Exception {
        framing(fit, focusX, focusY);
        JSONObject next = copy();
        JSONObject work = work(next, artistId, workId);
        work.put("title", label(title, "Artwork title", 150, false));
        work.put("year", label(year, "Year", 32, true));
        work.put("enabled", enabled);
        work.put("fit", fit).put("focusX", focusX).put("focusY", focusY);
        requireSelection(next);
        commit(next);
    }

    synchronized void deleteWork(String artistId, String workId) throws Exception {
        JSONObject next = copy();
        JSONObject removed = work(next, artistId, workId);
        JSONArray works = artist(next, artistId).getJSONArray("works");
        int index = 0;
        for (int i = 0; i < works.length(); i++) if (works.getJSONObject(i).getString("id").equals(workId)) {
            index = i;
            works.remove(i);
            break;
        }
        keepSelection(next);
        JSONObject folder = artist(data, artistId);
        JSONObject deleted = new JSONObject().put("id", artistId).put("name", folder.getString("name"))
                .put("works", new JSONArray().put(removed));
        JSONObject discarded = data.optJSONObject("_undo");
        next.put("_undo", new JSONObject().put("artist", deleted).put("workIndex", index).put("entireArtist", false));
        commit(next);
        cleanDiscardedUndo(discarded);
    }

    synchronized void updateSettings(int intervalMinutes) throws Exception {
        updateSettings(intervalMinutes, data.optBoolean("artModeEnabled", true));
    }

    synchronized void updateSettings(int intervalMinutes, boolean artModeEnabled) throws Exception {
        interval(intervalMinutes);
        JSONObject next = copy();
        next.put("intervalMinutes", intervalMinutes);
        next.put("artModeEnabled", artModeEnabled);
        commit(next);
    }

    synchronized void undoDelete() throws Exception {
        JSONObject undo = data.optJSONObject("_undo");
        if (undo == null) throw new IllegalArgumentException("There is no deletion to undo");
        JSONObject next = copy();
        JSONObject removed = new JSONObject(undo.getJSONObject("artist").toString());
        String id = removed.getString("id");
        if (undo.getBoolean("entireArtist")) {
            JSONArray artists = next.getJSONArray("artists");
            for (int i = 0; i < artists.length(); i++) if (artists.getJSONObject(i).getString("id").equals(id))
                throw new IllegalArgumentException("That artist folder already exists");
            uniqueName(artists, null, removed.getString("name"));
            next.put("artists", insert(artists, removed, undo.optInt("artistIndex", artists.length())));
        } else {
            JSONObject folder = artist(next, id);
            JSONArray works = folder.getJSONArray("works");
            JSONObject restored = removed.getJSONArray("works").getJSONObject(0);
            for (int i = 0; i < works.length(); i++) if (works.getJSONObject(i).getString("id").equals(restored.getString("id")))
                throw new IllegalArgumentException("That artwork already exists");
            folder.put("works", insert(works, restored, undo.optInt("workIndex", works.length())));
        }
        next.remove("_undo");
        commit(next);
    }

    synchronized void selectWorks(Set<String> keys) throws Exception {
        if (keys == null || keys.isEmpty()) throw new IllegalArgumentException("Select at least one artwork");
        JSONObject next = copy();
        Set<String> known = new HashSet<>();
        JSONArray artists = next.getJSONArray("artists");
        for (int i = 0; i < artists.length(); i++) {
            JSONObject artist = artists.getJSONObject(i);
            JSONArray works = artist.getJSONArray("works");
            for (int j = 0; j < works.length(); j++) {
                JSONObject work = works.getJSONObject(j);
                String key = artist.getString("id") + "/" + work.getString("id");
                known.add(key);
                work.put("enabled", keys.contains(key));
            }
        }
        if (!known.containsAll(keys)) throw new IllegalArgumentException("An artwork changed. Reload the library.");
        requireSelection(next);
        commit(next);
    }

    synchronized InputStream openImage(String artistId, String workId) throws Exception {
        JSONObject work = work(data, artistId, workId);
        if (work.has("file")) {
            try { return new FileInputStream(resolveImage(work.getString("file"))); }
            catch (FileNotFoundException missing) { throw new FileNotFoundException("Artwork image not found"); }
        }
        int resource = context.getResources().getIdentifier(work.getString("image"), "drawable", context.getPackageName());
        if (resource == 0) throw new IOException("Artwork image is unavailable");
        return context.getResources().openRawResource(resource);
    }

    synchronized File imageFile(String artistId, String workId) throws Exception {
        JSONObject work = work(data, artistId, workId);
        return work.has("file") ? resolveImage(work.getString("file")) : null;
    }

    File imageFile(String relative) throws Exception { return resolveImage(relative); }

    /** Only the artwork library is exported. Preferences, credentials and undo history never enter the ZIP. */
    File exportBackup() throws Exception {
        List<BackupSource> sources = new ArrayList<>();
        File archive = new File(context.getCacheDir(), "mero-artwork-" + UUID.randomUUID() + ".zip");
        File converted = new File(context.getCacheDir(), "mero-artwork-" + UUID.randomUUID() + ".webp");
        boolean complete = false;
        try {
            JSONObject saved;
            // Open immutable images while holding the metadata lock. An unlinked image remains readable
            // through its open descriptor, so encoding the archive never blocks the TV's main thread.
            synchronized (this) {
                writable();
                saved = backupState(data);
                JSONArray artists = saved.getJSONArray("artists");
                for (int i = 0; i < artists.length(); i++) {
                    JSONObject artist = artists.getJSONObject(i);
                    JSONArray works = artist.getJSONArray("works");
                    for (int j = 0; j < works.length(); j++) {
                        JSONObject work = works.getJSONObject(j);
                        JSONObject source = work(data, artist.getString("id"), work.getString("id"));
                        boolean bundled = !source.has("file");
                        long size = bundled ? -1 : resolveImage(source.getString("file")).length();
                        if (!bundled && (size == 0 || size > MAX_UPLOAD))
                            throw new IOException("An artwork image is unavailable or too large to back up");
                        sources.add(new BackupSource(work.getString("file"), bundled, size,
                                openImage(artist.getString("id"), work.getString("id"))));
                    }
                }
            }
            try (FileOutputStream file = new FileOutputStream(archive);
                 ZipOutputStream output = new ZipOutputStream(new BufferedOutputStream(file))) {
                long total = 0;
                for (BackupSource source : sources) {
                    long size = source.size;
                    if (source.bundled) {
                        backupImage(readBytes(source.input, MAX_UPLOAD), converted);
                        size = converted.length();
                    }
                    if (size == 0 || size > MAX_UPLOAD) throw new IOException("An artwork image is too large to back up");
                    total += size;
                    if (total > MAX_IMAGES) throw new IOException("The artwork backup exceeds the storage limit");
                    output.putNextEntry(new ZipEntry(source.path));
                    if (source.bundled) {
                        try (InputStream input = new FileInputStream(converted)) { transfer(input, output); }
                    } else transfer(source.input, output);
                    output.closeEntry();
                }
                output.putNextEntry(new ZipEntry("library.json"));
                output.write(saved.toString().getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
                output.finish();
                output.flush();
                file.getFD().sync();
            }
            if (archive.length() > ArtworkBackupArchive.MAX_ARCHIVE) throw new IOException("The artwork backup is too large");
            complete = true;
            return archive;
        } finally {
            for (BackupSource source : sources) try { source.input.close(); } catch (IOException ignored) {}
            converted.delete();
            if (!complete) archive.delete();
        }
    }

    private static final class BackupSource {
        final String path;
        final boolean bundled;
        final long size;
        final InputStream input;
        BackupSource(String path, boolean bundled, long size, InputStream input) {
            this.path = path; this.bundled = bundled; this.size = size; this.input = input;
        }
    }

    /** Validate and stage first, then switch metadata atomically to fresh image paths. */
    void restoreBackup(File archive) throws Exception {
        File stage = new File(directory, ".restore-" + UUID.randomUUID());
        Set<File> installed = new HashSet<>();
        boolean committed = false;
        long startingRevision;
        synchronized (this) { startingRevision = revision(); }
        try {
            Set<String> entries = ArtworkBackupArchive.unpack(archive, stage);
            JSONObject incoming;
            try (InputStream input = new FileInputStream(new File(stage, "library.json"))) {
                incoming = new JSONObject(read(input, MAX_METADATA));
            }
            // Validate before sanitizing, so malformed fields and paths cannot be silently accepted.
            incoming.remove("_undo");
            validate(incoming);
            JSONObject next = backupState(incoming);
            Set<String> expected = imagePaths(next);
            expected.add("library.json");
            if (!entries.equals(expected)) throw new IllegalArgumentException("The backup images do not match its artwork catalog");
            long bytes = 0;
            for (String path : entries) if (!"library.json".equals(path)) {
                File image = new File(stage, path);
                validateBackupImage(image);
                bytes += image.length();
            }
            if (imagesDirectory.getUsableSpace() < bytes + MAX_METADATA)
                throw new IOException("There is not enough free storage to restore this backup safely");
            synchronized (this) {
                if (revision() != startingRevision) throw new IllegalStateException("The library changed while restoring. Try again.");
                JSONArray artists = next.getJSONArray("artists");
                for (int i = 0; i < artists.length(); i++) {
                    JSONObject artist = artists.getJSONObject(i);
                    String artistId = UUID.randomUUID().toString();
                    artist.put("id", artistId);
                    JSONArray works = artist.getJSONArray("works");
                    for (int j = 0; j < works.length(); j++) {
                        JSONObject work = works.getJSONObject(j);
                        File source = new File(stage, work.getString("file"));
                        String workId = UUID.randomUUID().toString();
                        String path = "images/" + artistId + "/" + workId + ".webp";
                        File destination = resolveImage(path);
                        File folder = destination.getParentFile();
                        if ((!folder.isDirectory() && !folder.mkdirs()) || destination.exists() || !source.renameTo(destination))
                            throw new IOException("Cannot save the restored artwork images");
                        installed.add(destination);
                        work.put("id", workId).put("file", path);
                    }
                }
                next.put("revision", revision());
                boolean wasDamaged = damaged;
                damaged = false;
                try { commit(next); }
                catch (Exception failure) { damaged = wasDamaged; throw failure; }
                committed = true;
                cleanUnreferencedImages(imagesDirectory, imagePaths(data));
            }
        } finally {
            if (!committed) {
                for (File image : installed) image.delete();
            }
            removeTree(stage);
        }
    }

    private static JSONObject backupState(JSONObject source) throws Exception {
        JSONObject result = new JSONObject().put("version", 1).put("revision", 0)
                .put("intervalMinutes", source.getInt("intervalMinutes"))
                .put("artModeEnabled", source.optBoolean("artModeEnabled", true));
        JSONArray artists = new JSONArray();
        JSONArray original = source.getJSONArray("artists");
        for (int i = 0; i < original.length(); i++) {
            JSONObject artist = original.getJSONObject(i);
            String artistId = safeId(artist.getString("id"));
            JSONArray works = new JSONArray();
            JSONArray originals = artist.getJSONArray("works");
            for (int j = 0; j < originals.length(); j++) {
                JSONObject work = originals.getJSONObject(j);
                String id = safeId(work.getString("id"));
                works.put(new JSONObject().put("id", id).put("title", work.getString("title"))
                        .put("year", work.getString("year")).put("enabled", work.getBoolean("enabled"))
                        .put("fit", work.optString("fit", "fill")).put("focusX", work.optDouble("focusX", .5))
                        .put("focusY", work.optDouble("focusY", .5)).put("file", "images/" + artistId + "/" + id + ".webp"));
            }
            artists.put(new JSONObject().put("id", artistId).put("name", artist.getString("name")).put("works", works));
        }
        return result.put("artists", artists);
    }

    private JSONObject bundled() throws Exception {
        try (InputStream input = context.getResources().openRawResource(R.raw.artworks)) {
            JSONObject seed = new JSONObject(read(input, MAX_METADATA));
            seed.put("version", 1).put("revision", 0).put("intervalMinutes", 30).put("artModeEnabled", true);
            JSONArray artists = seed.getJSONArray("artists");
            for (int i = 0; i < artists.length(); i++) {
                JSONArray works = artists.getJSONObject(i).getJSONArray("works");
                for (int j = 0; j < works.length(); j++) works.getJSONObject(j).put("enabled", true);
            }
            validate(seed);
            return seed;
        }
    }

    private JSONObject copy() throws Exception { return new JSONObject(data.toString()); }

    private void writable() {
        if (damaged) throw new IllegalStateException("Saved library needs repair before changes can be saved");
    }

    private void commit(JSONObject next) throws Exception {
        writable();
        next.put("revision", revision() + 1);
        validate(next);
        byte[] encoded = next.toString().getBytes(StandardCharsets.UTF_8);
        if (encoded.length > MAX_METADATA) throw new IllegalArgumentException("Artwork library metadata is full");
        FileOutputStream output = null;
        try {
            output = metadata.startWrite();
            output.write(encoded);
            metadata.finishWrite(output);
            output = null;
            data = next;
        } finally {
            if (output != null) metadata.failWrite(output);
        }
    }

    private void validate(JSONObject state) throws Exception {
        if (state.optInt("version", 1) != 1) throw new IllegalArgumentException("Unsupported artwork library version");
        interval(state.getInt("intervalMinutes"));
        if (!state.has("artModeEnabled")) state.put("artModeEnabled", true);
        if (!(state.get("artModeEnabled") instanceof Boolean)) throw new IllegalArgumentException("Invalid Art mode setting");
        JSONArray artists = state.getJSONArray("artists");
        if (artists.length() > 100) throw new IllegalArgumentException("Too many artist folders");
        Set<String> ids = new HashSet<>();
        Set<String> names = new HashSet<>();
        int count = 0;
        for (int i = 0; i < artists.length(); i++) {
            JSONObject artist = artists.getJSONObject(i);
            String artistId = safeId(artist.getString("id"));
            if (!ids.add(artistId)) throw new IllegalArgumentException("Duplicate artist folder");
            label(artist.getString("name"), "Artist name", 150, false);
            if (!names.add(artist.getString("name").trim().toLowerCase(java.util.Locale.ROOT)))
                throw new IllegalArgumentException("Duplicate artist folder name");
            Set<String> workIds = new HashSet<>();
            JSONArray works = artist.getJSONArray("works");
            for (int j = 0; j < works.length(); j++) {
                JSONObject work = works.getJSONObject(j);
                String workId = safeId(work.getString("id"));
                if (!workIds.add(workId)) throw new IllegalArgumentException("Duplicate artwork");
                validateWork(artistId, work);
                count++;
            }
        }
        if (count == 0) throw new IllegalArgumentException("Keep at least one artwork in the library");
        if (count > MAX_WORKS) throw new IllegalArgumentException("Keep the library below 200 artworks");
        requireSelection(state);
        if (state.has("_undo")) {
            JSONObject undo = state.getJSONObject("_undo");
            JSONObject deleted = undo.getJSONObject("artist");
            String artistId = safeId(deleted.getString("id"));
            label(deleted.getString("name"), "Artist name", 150, false);
            JSONArray works = deleted.getJSONArray("works");
            if (works.length() > MAX_WORKS || (!undo.getBoolean("entireArtist") && works.length() != 1))
                throw new IllegalArgumentException("Invalid deletion recovery record");
            Set<String> workIds = new HashSet<>();
            for (int i = 0; i < works.length(); i++) {
                JSONObject work = works.getJSONObject(i);
                if (!workIds.add(safeId(work.getString("id")))) throw new IllegalArgumentException("Invalid deletion recovery record");
                validateWork(artistId, work);
            }
        }
    }

    private void validateWork(String artistId, JSONObject work) throws Exception {
        String workId = safeId(work.getString("id"));
        label(work.getString("title"), "Artwork title", 150, false);
        label(work.getString("year"), "Year", 32, true);
        if (!(work.get("enabled") instanceof Boolean)) throw new IllegalArgumentException("Invalid artwork selection");
        if (!work.has("fit")) work.put("fit", "fill");
        if (!work.has("focusX")) work.put("focusX", .5);
        if (!work.has("focusY")) work.put("focusY", .5);
        if (!(work.get("focusX") instanceof Number) || !(work.get("focusY") instanceof Number))
            throw new IllegalArgumentException("Invalid artwork position");
        framing(work.getString("fit"), work.getDouble("focusX"), work.getDouble("focusY"));
        if (work.has("file") == work.has("image")) throw new IllegalArgumentException("Artwork needs one image source");
        if (work.has("file")) {
            String expected = "images/" + artistId + "/" + workId + ".webp";
            if (!expected.equals(work.getString("file"))) throw new IllegalArgumentException("Invalid artwork image path");
            resolveImage(expected);
        } else if (!work.getString("image").matches("[a-z][a-z0-9_]*") || context.getResources()
                .getIdentifier(work.getString("image"), "drawable", context.getPackageName()) == 0) {
            throw new IllegalArgumentException("Bundled artwork image is unavailable");
        }
    }

    private static void framing(String fit, double focusX, double focusY) {
        if (!"fill".equals(fit) && !"fit".equals(fit)) throw new IllegalArgumentException("Choose Fill or Fit artwork framing");
        if (Double.isNaN(focusX) || Double.isInfinite(focusX) || Double.isNaN(focusY) || Double.isInfinite(focusY)
                || focusX < 0 || focusX > 1 || focusY < 0 || focusY > 1)
            throw new IllegalArgumentException("Artwork position must be between 0 and 1");
    }

    private static String safeId(String value) {
        if (value == null || !value.matches("[a-z0-9_-]{1,80}")) throw new IllegalArgumentException("Invalid artwork identifier");
        return value;
    }

    private static String label(String value, String name, int limit, boolean optional) {
        if (value == null) value = "";
        value = value.trim();
        if ((!optional && value.isEmpty()) || value.length() > limit)
            throw new IllegalArgumentException(name + " must contain " + (optional ? "at most " : "1 to ") + limit + " characters");
        for (int i = 0; i < value.length(); i++)
            if (value.charAt(i) < 32 || value.charAt(i) == 127) throw new IllegalArgumentException(name + " contains an unsupported character");
        return value;
    }

    private static void interval(int minutes) {
        if (minutes != 5 && minutes != 15 && minutes != 30 && minutes != 60 && minutes != 120)
            throw new IllegalArgumentException("Choose a rotation interval of 5, 15, 30, 60 or 120 minutes");
    }

    private static void uniqueName(JSONArray artists, String except, String name) throws Exception {
        for (int i = 0; i < artists.length(); i++) {
            JSONObject artist = artists.getJSONObject(i);
            if (!artist.getString("id").equals(except) && artist.getString("name").equalsIgnoreCase(name))
                throw new IllegalArgumentException("An artist folder with this name already exists");
        }
    }

    private static JSONObject artist(JSONObject state, String id) throws Exception {
        safeId(id);
        JSONArray artists = state.getJSONArray("artists");
        for (int i = 0; i < artists.length(); i++)
            if (artists.getJSONObject(i).getString("id").equals(id)) return artists.getJSONObject(i);
        throw new FileNotFoundException("Artist folder not found. Reload the library.");
    }

    private static JSONObject work(JSONObject state, String artistId, String workId) throws Exception {
        safeId(workId);
        JSONArray works = artist(state, artistId).getJSONArray("works");
        for (int i = 0; i < works.length(); i++)
            if (works.getJSONObject(i).getString("id").equals(workId)) return works.getJSONObject(i);
        throw new FileNotFoundException("Artwork not found. Reload the library.");
    }

    private static int countWorks(JSONObject state) throws Exception {
        int count = 0;
        JSONArray artists = state.getJSONArray("artists");
        for (int i = 0; i < artists.length(); i++) count += artists.getJSONObject(i).getJSONArray("works").length();
        return count;
    }

    private static void requireSelection(JSONObject state) throws Exception {
        JSONArray artists = state.getJSONArray("artists");
        for (int i = 0; i < artists.length(); i++) {
            JSONArray works = artists.getJSONObject(i).getJSONArray("works");
            for (int j = 0; j < works.length(); j++) if (works.getJSONObject(j).getBoolean("enabled")) return;
        }
        throw new IllegalArgumentException("Select at least one artwork for rotation");
    }

    private static void keepSelection(JSONObject state) throws Exception {
        if (countWorks(state) == 0) throw new IllegalArgumentException("Keep at least one artwork in the library");
        try { requireSelection(state); }
        catch (IllegalArgumentException empty) {
            JSONArray artists = state.getJSONArray("artists");
            for (int i = 0; i < artists.length(); i++) {
                JSONArray works = artists.getJSONObject(i).getJSONArray("works");
                if (works.length() > 0) { works.getJSONObject(0).put("enabled", true); return; }
            }
        }
    }

    private File resolveImage(String relative) throws Exception {
        if (!ArtworkBackupArchive.imagePath(relative))
            throw new IllegalArgumentException("Invalid artwork image path");
        File file = new File(directory, relative).getCanonicalFile();
        String parent = imagesDirectory.getCanonicalPath() + File.separator;
        if (!file.getPath().startsWith(parent)) throw new IllegalArgumentException("Invalid artwork image path");
        return file;
    }

    private static JSONArray insert(JSONArray source, JSONObject value, int position) throws Exception {
        JSONArray result = new JSONArray();
        position = Math.max(0, Math.min(position, source.length()));
        for (int i = 0; i <= source.length(); i++) {
            if (i == position) result.put(value);
            if (i < source.length()) result.put(source.getJSONObject(i));
        }
        return result;
    }

    private static Set<String> imagePaths(JSONObject state) throws Exception {
        Set<String> paths = new HashSet<>();
        JSONArray artists = state.getJSONArray("artists");
        for (int i = 0; i < artists.length(); i++) addImagePaths(artists.getJSONObject(i), paths);
        JSONObject undo = state.optJSONObject("_undo");
        if (undo != null) addImagePaths(undo.getJSONObject("artist"), paths);
        return paths;
    }

    private static void addImagePaths(JSONObject artist, Set<String> paths) throws Exception {
        JSONArray works = artist.getJSONArray("works");
        for (int i = 0; i < works.length(); i++) {
            JSONObject work = works.getJSONObject(i);
            if (work.has("file")) paths.add(work.getString("file"));
        }
    }

    private void cleanDiscardedUndo(JSONObject discarded) {
        if (discarded == null) return;
        try {
            Set<String> retained = imagePaths(data);
            JSONArray works = discarded.getJSONObject("artist").getJSONArray("works");
            for (int i = 0; i < works.length(); i++) {
                JSONObject work = works.getJSONObject(i);
                if (work.has("file") && !retained.contains(work.getString("file"))) deleteImage(work);
            }
        } catch (Exception cleanupFailed) { /* A stale image must not undo a successful metadata commit. */ }
    }

    private void cleanUnreferencedImages(File folder, Set<String> retained) {
        File[] files = folder.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (Files.isSymbolicLink(file.toPath()) || file.getName().startsWith(".upload-")) continue;
            if (file.isDirectory()) {
                cleanUnreferencedImages(file, retained);
                File[] children = file.listFiles();
                if (children != null && children.length == 0) file.delete();
            } else {
                String relative = directory.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/');
                if (!retained.contains(relative)) file.delete();
            }
        }
    }

    private static void validateBackupImage(File file) throws IOException {
        Bitmap bitmap = null;
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), options);
            if (!"image/webp".equals(options.outMimeType) || options.outWidth <= 0 || options.outHeight <= 0
                    || options.outWidth > 2560 || options.outHeight > 1440)
                throw new IOException("A backup image is not a valid TV artwork image");
            options.inJustDecodeBounds = false;
            bitmap = BitmapFactory.decodeFile(file.getPath(), options);
            if (bitmap == null) throw new IOException("A backup image could not be opened");
        } catch (OutOfMemoryError tooLarge) {
            throw new IOException("A backup image is too large for this TV");
        } finally { if (bitmap != null) bitmap.recycle(); }
    }

    private static void transfer(InputStream input, java.io.OutputStream output) throws IOException {
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
    }

    private static void removeTree(File file) {
        if (!Files.isSymbolicLink(file.toPath()) && file.isDirectory()) {
            File[] files = file.listFiles();
            if (files != null) for (File child : files) removeTree(child);
        }
        file.delete();
    }

    private void deleteImage(JSONObject work) {
        try {
            if (work.has("file")) {
                File image = resolveImage(work.getString("file"));
                image.delete();
                File folder = image.getParentFile();
                File[] files = folder.listFiles();
                if (files != null && files.length == 0) folder.delete();
            }
        } catch (Exception cleanupFailed) { /* Committed metadata remains authoritative if cleanup fails. */ }
    }

    private static long imageBytes(File directory) throws IOException {
        long total = 0;
        File[] children = directory.listFiles();
        if (children == null) throw new IOException("Cannot read artwork storage");
        for (File child : children) {
            if (Files.isSymbolicLink(child.toPath())) continue;
            if (child.isDirectory()) total += imageBytes(child);
            else if (child.getName().endsWith(".webp")) total += child.length();
        }
        return total;
    }

    private static void normalize(byte[] bytes, File destination) throws Exception {
        Bitmap bitmap = null;
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
            if (!("image/jpeg".equals(options.outMimeType) || "image/png".equals(options.outMimeType)
                    || "image/webp".equals(options.outMimeType)) || options.outWidth <= 0 || options.outHeight <= 0
                    || (long) options.outWidth * options.outHeight > 40_000_000L)
                throw new IllegalArgumentException("Choose a JPEG, PNG or WebP image smaller than 40 megapixels");
            options.inSampleSize = 1;
            while (options.outWidth / options.inSampleSize > 2560 || options.outHeight / options.inSampleSize > 1440)
                options.inSampleSize *= 2;
            options.inJustDecodeBounds = false;
            bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
            if (bitmap == null) throw new IllegalArgumentException("This image could not be opened");
            try (FileOutputStream output = new FileOutputStream(destination)) {
                if (!bitmap.compress(Bitmap.CompressFormat.WEBP, 90, output)) throw new IOException("Cannot save artwork image");
                output.getFD().sync();
            }
        } catch (OutOfMemoryError tooLarge) {
            throw new IllegalArgumentException("This image is too large for the TV. Try a smaller image.");
        } finally { if (bitmap != null) bitmap.recycle(); }
    }

    private static void backupImage(byte[] bytes, File destination) throws Exception {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        // Bundled TV-ready WebP images already meet the restore contract. Keep their exact bytes
        // so exporting and restoring a library does not repeatedly recompress the same artwork.
        if ("image/webp".equals(options.outMimeType) && options.outWidth > 0 && options.outHeight > 0
                && options.outWidth <= 2560 && options.outHeight <= 1440) {
            try (FileOutputStream output = new FileOutputStream(destination)) {
                output.write(bytes);
                output.getFD().sync();
            }
        } else normalize(bytes, destination);
    }

    private static String read(InputStream input, int limit) throws IOException {
        return new String(readBytes(input, limit), StandardCharsets.UTF_8);
    }

    private static byte[] readBytes(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (output.size() + count > limit) throw new IOException("Artwork catalog is too large");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }
}
