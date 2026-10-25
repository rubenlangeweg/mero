package studio.invrse.mero;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** A renderable snapshot of bundled and locally managed artworks. */
final class ArtworkCatalog {
    static final class Work {
        final String key;
        final String artist;
        final String title;
        final String year;
        final int image;
        final File file;
        final boolean enabled;
        final String fit;
        final float focusX;
        final float focusY;

        Work(String key, String artist, String title, String year, int image, File file, boolean enabled, String fit, float focusX, float focusY) {
            this.key = key;
            this.artist = artist;
            this.title = title;
            this.year = year;
            this.image = image;
            this.file = file;
            this.enabled = enabled;
            this.fit = fit;
            this.focusX = focusX;
            this.focusY = focusY;
        }
    }

    static final class Artist {
        final String id;
        final String name;
        final List<Work> works = new ArrayList<>();

        Artist(String id, String name) { this.id = id; this.name = name; }
    }

    final List<Artist> artists = new ArrayList<>();
    final List<Work> works = new ArrayList<>();
    final int intervalMinutes;
    final long revision;
    final boolean artModeEnabled;

    ArtworkCatalog(Context context) {
        this(context, new ArtworkLibrary(context));
    }

    ArtworkCatalog(Context context, ArtworkLibrary library) {
        try {
            JSONObject snapshot = library.snapshot();
            intervalMinutes = snapshot.getInt("intervalMinutes");
            revision = snapshot.getLong("revision");
            artModeEnabled = snapshot.optBoolean("artModeEnabled", true);
            JSONArray data = snapshot.getJSONArray("artists");
            for (int i = 0; i < data.length(); i++) {
                JSONObject item = data.getJSONObject(i);
                Artist artist = new Artist(item.getString("id"), item.getString("name"));
                JSONArray images = item.getJSONArray("works");
                for (int j = 0; j < images.length(); j++) {
                    JSONObject image = images.getJSONObject(j);
                    // Resolve this snapshot's path without looking up mutable live metadata again.
                    File file = image.has("file") ? library.imageFile(image.getString("file")) : null;
                    int resource = file == null ? context.getResources().getIdentifier(image.getString("image"), "drawable", context.getPackageName()) : 0;
                    if (file == null && resource == 0) throw new IllegalStateException("Missing artwork image");
                    Work work = new Work(item.getString("id") + "/" + image.getString("id"),
                            artist.name, image.getString("title"), image.getString("year"), resource, file, image.getBoolean("enabled"), image.optString("fit", "fill"),
                            (float) image.optDouble("focusX", .5), (float) image.optDouble("focusY", .5));
                    artist.works.add(work);
                    works.add(work);
                }
                artists.add(artist);
            }
            if (works.isEmpty()) throw new IllegalStateException("Artwork catalog is empty");
        } catch (Exception invalid) {
            throw new IllegalStateException("Cannot load artwork catalog", invalid);
        }
    }

    Set<String> keys() {
        Set<String> keys = new HashSet<>();
        for (Work work : works) keys.add(work.key);
        return keys;
    }
}
