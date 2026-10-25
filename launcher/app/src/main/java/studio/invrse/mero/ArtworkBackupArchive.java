package studio.invrse.mero;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** The bounded ZIP container used by artwork backups; no app preferences are accepted. */
final class ArtworkBackupArchive {
    static final int MAX_METADATA = 512 * 1024;
    static final int MAX_IMAGE = 16 * 1024 * 1024;
    static final long MAX_IMAGES = 256L * 1024 * 1024;
    static final long MAX_ARCHIVE = MAX_IMAGES + 1024 * 1024;

    static Set<String> unpack(File archive, File destination) throws IOException {
        if (!archive.isFile() || archive.length() == 0 || archive.length() > MAX_ARCHIVE)
            throw new IOException("Choose an artwork backup up to 257 MB");
        if (!destination.isDirectory() && !destination.mkdirs()) throw new IOException("Cannot prepare artwork restore");
        Set<String> entries = new HashSet<>();
        long images = 0;
        byte[] buffer = new byte[8192];
        try (ZipInputStream input = new ZipInputStream(new BufferedInputStream(new FileInputStream(archive)))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                String name = entry.getName();
                boolean metadata = "library.json".equals(name);
                if (entry.isDirectory() || (!metadata && !imagePath(name)) || !entries.add(name) || entries.size() > 201)
                    throw new IOException("This backup contains an unexpected or duplicate file");
                long limit = metadata ? MAX_METADATA : MAX_IMAGE;
                if (entry.getSize() > limit) throw new IOException("A backup file is too large");
                File target = new File(destination, name).getCanonicalFile();
                if (!target.getPath().startsWith(destination.getCanonicalPath() + File.separator))
                    throw new IOException("Invalid backup path");
                File parent = target.getParentFile();
                if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot prepare artwork restore");
                long size = 0;
                try (FileOutputStream output = new FileOutputStream(target)) {
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        size += count;
                        if (size > limit || (!metadata && images + size > MAX_IMAGES))
                            throw new IOException("The artwork backup exceeds the storage limit");
                        output.write(buffer, 0, count);
                    }
                    output.getFD().sync();
                }
                if (size == 0) throw new IOException("The artwork backup contains an empty file");
                if (!metadata) images += size;
                input.closeEntry();
            }
        }
        if (!entries.contains("library.json")) throw new IOException("This is not an artwork library backup");
        return entries;
    }

    static boolean imagePath(String value) {
        return value != null && value.matches("images/[a-z0-9_-]{1,80}/[a-z0-9_-]{1,80}\\.webp");
    }

    private ArtworkBackupArchive() {}
}
