package com.vlcplayer.app;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/** Stores validated scripts without overwriting a working script during import. */
public final class FunscriptStore {
    public static final int MAX_SCRIPT_BYTES = 2 * 1024 * 1024;

    public interface Validator {
        void validate(File file) throws Exception;
    }

    private FunscriptStore() { }

    public static File destinationFor(File directory, String mediaUri,
                                      String videoBaseName, String extension) {
        if (directory == null || mediaUri == null || mediaUri.trim().isEmpty()) {
            throw new IllegalArgumentException("Script directory and media URI are required");
        }
        String normalizedExtension = extension == null ? "" : extension.toLowerCase(Locale.US);
        if (!normalizedExtension.startsWith(".")) normalizedExtension = "." + normalizedExtension;
        if (!".funscript".equals(normalizedExtension) && !".csv".equals(normalizedExtension)) {
            throw new IllegalArgumentException("Unsupported script extension");
        }
        String basename = videoBaseName == null ? "video" : videoBaseName;
        basename = basename.replaceAll("[^a-zA-Z0-9_-]", "_");
        if (basename.isEmpty()) basename = "video";
        if (basename.length() > 64) basename = basename.substring(0, 64);
        try {
            File parent = directory.getCanonicalFile();
            File destination = new File(parent, basename + "-" + uriHash(mediaUri)
                + normalizedExtension);
            requireDirectChild(parent, destination);
            return destination;
        } catch (IOException e) {
            throw new IllegalArgumentException("Unsafe script destination", e);
        }
    }

    /**
     * Owns and closes source. Read, close and validation must all succeed before
     * the previous destination is touched. Serialize imports to the same store.
     */
    public static synchronized File importScript(InputStream source, File destination,
                                                  Validator validator) throws Exception {
        if (source == null) throw new IllegalArgumentException("Script source is required");
        File temporary = null;
        try {
            File target;
            // Closing the source before replacement also protects against close errors.
            try (InputStream input = source) {
                if (destination == null || validator == null) {
                    throw new IllegalArgumentException("Script destination and validator are required");
                }
                File parent = destination.getAbsoluteFile().getParentFile().getCanonicalFile();
                if (!parent.isDirectory() && !parent.mkdirs()) {
                    throw new IOException("Cannot create script directory");
                }
                target = new File(parent, destination.getName());
                requireDirectChild(parent, target);
                if (target.exists() && !target.isFile()) {
                    throw new IOException("Script destination is not a file");
                }
                String suffix = target.getName().toLowerCase(Locale.US).endsWith(".csv")
                    ? ".csv" : ".funscript";
                temporary = File.createTempFile(".script-import-", suffix, parent);
                copyBounded(input, temporary);
            }

            validator.validate(temporary);
            // A validator must not redirect the destination through a symlink.
            requireDirectChild(target.getParentFile(), target);
            replaceWithRollback(temporary, target);
            temporary = null;
            return target;
        } finally {
            if (temporary != null) temporary.delete();
        }
    }

    private static void copyBounded(InputStream input, File temporary) throws IOException {
        int total = 0;
        byte[] buffer = new byte[8192];
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (count == 0) {
                    int value = input.read();
                    if (value == -1) break;
                    buffer[0] = (byte) value;
                    count = 1;
                }
                if (count > MAX_SCRIPT_BYTES - total) {
                    throw new IOException("Script exceeds the 2 MiB limit");
                }
                output.write(buffer, 0, count);
                total += count;
            }
            if (total == 0) throw new IOException("Script is empty");
            output.getFD().sync();
        }
    }

    private static void replaceWithRollback(File temporary, File destination) throws IOException {
        File backup = null;
        if (destination.exists()) {
            if (!destination.isFile()) throw new IOException("Script destination is not a file");
            // Android's same-directory rename atomically replaces a file. Prefer
            // that path so process death cannot leave the saved name missing.
            // Some platforms refuse replacement; retain rollback for those.
            if (temporary.renameTo(destination)) return;
            backup = File.createTempFile(".script-backup-", ".bak", destination.getParentFile());
            if (!backup.delete() || !destination.renameTo(backup)) {
                backup.delete();
                throw new IOException("Cannot protect the previous script");
            }
        }
        boolean replaced = false;
        try {
            if (!temporary.renameTo(destination)) {
                throw new IOException("Cannot replace the saved script");
            }
            replaced = true;
        } finally {
            if (backup != null) {
                if (replaced) {
                    backup.delete();
                } else if (!backup.renameTo(destination)) {
                    // Keep the only valid copy when a filesystem refuses rollback.
                    throw new IOException("Cannot restore previous script; preserved at "
                        + backup.getAbsolutePath());
                }
            }
        }
    }

    private static void requireDirectChild(File parent, File destination) throws IOException {
        if (!destination.getCanonicalFile().equals(new File(parent, destination.getName()))) {
            throw new IOException("Script destination escapes its directory");
        }
    }

    private static String uriHash(String mediaUri) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(mediaUri.getBytes(StandardCharsets.UTF_8));
            StringBuilder hash = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                hash.append(Character.forDigit((value >>> 4) & 0x0f, 16));
                hash.append(Character.forDigit(value & 0x0f, 16));
            }
            return hash.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
