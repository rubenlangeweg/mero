package studio.invrse.mero;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Real ZIP I/O tests for bounded artwork-only extraction. No Android stubs. */
public final class ArtworkBackupArchiveTest {
    private static int checks;
    private static Path root;

    public static void main(String[] args) throws Exception {
        root = Files.createTempDirectory("mero-backup-test-");
        try {
            Map<String, byte[]> valid = new LinkedHashMap<>();
            valid.put("library.json", "{\"version\":1}".getBytes(StandardCharsets.UTF_8));
            valid.put("images/artist/work.webp", new byte[] {1, 2, 3, 4});
            File archive = zip(valid);
            File stage = destination();
            Set<String> names = ArtworkBackupArchive.unpack(archive, stage);
            check(names.equals(valid.keySet()), "Accepted entry list");
            check(Arrays.equals(Files.readAllBytes(new File(stage, "images/artist/work.webp").toPath()), valid.get("images/artist/work.webp")), "Exact file bytes");

            reject(single("../outside.webp", new byte[] {1}), "Traversal");
            reject(single("/images/a/b.webp", new byte[] {1}), "Absolute path");
            reject(single("images/a/../b.webp", new byte[] {1}), "Embedded traversal");
            reject(single("preferences.xml", new byte[] {1}), "Non-artwork data");
            reject(single("library.json", new byte[0]), "Empty catalog");
            reject(single("images/a/b.webp", new byte[] {1}), "Missing catalog");
            reject(single("library.json", new byte[ArtworkBackupArchive.MAX_METADATA + 1]), "Metadata expansion limit");
            Map<String, byte[]> bigImage = new LinkedHashMap<>(valid);
            bigImage.put("images/a/b.webp", new byte[ArtworkBackupArchive.MAX_IMAGE + 1]);
            reject(bigImage, "Image expansion limit");
            Map<String, byte[]> many = single("library.json", new byte[] {1});
            for (int i = 0; i < 201; i++) many.put("images/a/w" + i + ".webp", new byte[] {1});
            reject(many, "Entry count limit");

            Map<String, byte[]> duplicate = single("library.json", new byte[] {1});
            duplicate.put("images/a/a.webp", new byte[] {1});
            duplicate.put("images/a/b.webp", new byte[] {2});
            File duplicates = zip(duplicate);
            byte[] bytes = Files.readAllBytes(duplicates.toPath());
            replace(bytes, "images/a/b.webp", "images/a/a.webp");
            Files.write(duplicates.toPath(), bytes);
            rejectFile(duplicates, "Duplicate names");

            File huge = root.resolve("large.zip").toFile();
            try (RandomAccessFile file = new RandomAccessFile(huge, "rw")) { file.setLength(ArtworkBackupArchive.MAX_ARCHIVE + 1); }
            rejectFile(huge, "Archive size limit");

            File symlinkStage = destination();
            Files.createDirectories(symlinkStage.toPath());
            Path outside = Files.createDirectory(root.resolve("outside"));
            Files.createSymbolicLink(symlinkStage.toPath().resolve("images"), outside);
            try { ArtworkBackupArchive.unpack(archive, symlinkStage); throw new AssertionError("Symlink path accepted"); }
            catch (IOException expected) { checks++; }
            check(Files.list(outside).count() == 0, "Symlink target unchanged");
            System.out.println("Artwork backup ZIP: " + checks + " checks passed");
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> { try { Files.delete(path); } catch (IOException failure) { throw new RuntimeException(failure); } });
            }
        }
    }

    private static Map<String, byte[]> single(String name, byte[] value) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(name, value);
        return entries;
    }
    private static File destination() throws IOException { return Files.createTempDirectory(root, "stage-").toFile(); }
    private static File zip(Map<String, byte[]> entries) throws IOException {
        File archive = Files.createTempFile(root, "library-", ".zip").toFile();
        try (ZipOutputStream output = new ZipOutputStream(new FileOutputStream(archive))) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                output.putNextEntry(new ZipEntry(entry.getKey()));
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
        return archive;
    }
    private static void reject(Map<String, byte[]> entries, String label) throws Exception { rejectFile(zip(entries), label); }
    private static void rejectFile(File archive, String label) throws Exception {
        try { ArtworkBackupArchive.unpack(archive, destination()); throw new AssertionError(label + " accepted"); }
        catch (IOException expected) { checks++; }
    }
    private static void replace(byte[] bytes, String from, String to) {
        byte[] a = from.getBytes(StandardCharsets.UTF_8), b = to.getBytes(StandardCharsets.UTF_8);
        if (a.length != b.length) throw new AssertionError("Patch length");
        for (int i = 0; i <= bytes.length - a.length; i++) {
            boolean same = true;
            for (int j = 0; j < a.length; j++) if (bytes[i + j] != a[j]) { same = false; break; }
            if (same) { System.arraycopy(b, 0, bytes, i, b.length); i += a.length - 1; }
        }
    }
    private static void check(boolean value, String label) { if (!value) throw new AssertionError(label); checks++; }
}
