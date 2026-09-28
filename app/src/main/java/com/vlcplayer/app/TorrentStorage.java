package com.vlcplayer.app;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Filesystem rules kept independent of Android and the native torrent session. */
final class TorrentStorage {
    static final String SOURCE_FILE = "source.torrent";
    static final String SOURCE_URL = "source.txt";

    private TorrentStorage() {}

    static File sourceDirectory(File retainedRoot, String source) throws IOException {
        String value = source == null ? "" : source.trim();
        if (value.isEmpty()) throw new IOException("Link torrent rong");
        MessageDigest digest = sha256();
        File local = localSource(value);
        if (local != null) {
            try (InputStream input = new FileInputStream(local)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
            }
        } else {
            digest.update(value.getBytes(StandardCharsets.UTF_8));
        }
        StringBuilder key = new StringBuilder(64);
        for (byte b : digest.digest()) key.append(String.format(java.util.Locale.US, "%02x", b & 0xff));
        return new File(retainedRoot, key.toString());
    }

    static File localSource(String source) {
        String path = source.startsWith("file://") ? source.substring(7) : source;
        return path.startsWith("/") || new File(path).isAbsolute() ? new File(path) : null;
    }

    static void rememberSource(File directory, String source) throws IOException {
        File local = localSource(source);
        if (local != null) {
            File destination = new File(directory, SOURCE_FILE);
            if (!sameFile(local, destination)) {
                try (InputStream input = new FileInputStream(local);
                     FileOutputStream output = new FileOutputStream(destination)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
                }
            }
        } else {
            try (FileOutputStream output = new FileOutputStream(new File(directory, SOURCE_URL))) {
                output.write(source.getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    static String resumeSource(File retainedRoot, File video) throws IOException {
        File root = retainedRoot.getCanonicalFile();
        File canonical = video.getCanonicalFile();
        if (!isDescendant(canonical, root)) return null;
        File sourceDir = canonical.getParentFile();
        while (sourceDir != null && !sameFile(sourceDir.getParentFile(), root)) {
            sourceDir = sourceDir.getParentFile();
        }
        if (sourceDir == null || !isDescendant(sourceDir, root)) return null;
        File metadata = new File(sourceDir, SOURCE_FILE);
        if (metadata.isFile() && isDescendant(metadata.getCanonicalFile(), sourceDir)) {
            return metadata.getAbsolutePath();
        }
        File text = new File(sourceDir, SOURCE_URL);
        if (!text.isFile() || text.length() > 16 * 1024
                || !isDescendant(text.getCanonicalFile(), sourceDir)) return null;
        try (InputStream input = new FileInputStream(text)) {
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (output.size() + read > 16 * 1024) return null;
                output.write(buffer, 0, read);
            }
            String value = new String(output.toByteArray(), StandardCharsets.UTF_8).trim();
            return value.startsWith("magnet:") || value.startsWith("http://")
                || value.startsWith("https://") ? value : null;
        }
    }

    static boolean deleteStoredFile(File target, List<File> roots) {
        return isStoredFile(target, roots) && target.delete();
    }

    static boolean isStoredFile(File target, List<File> roots) {
        if (target == null || !target.isFile()) return false;
        try {
            File canonical = target.getCanonicalFile();
            for (File root : roots) {
                if (root != null && isDescendant(canonical, root.getCanonicalFile())) {
                    return true;
                }
            }
        } catch (IOException ignored) {}
        return false;
    }

    static final class DeletionTokens {
        private final Map<String, File> files = new LinkedHashMap<>();

        synchronized String prepare(File target, List<File> roots) {
            if (!isStoredFile(target, roots)) return null;
            if (files.size() >= 128) files.remove(files.keySet().iterator().next());
            String token = UUID.randomUUID().toString();
            files.put(token, target);
            return token;
        }

        synchronized File take(String token) {
            return token == null ? null : files.remove(token);
        }
    }

    static final class TemporaryOwnership {
        private final Map<String, Long> generations = new LinkedHashMap<>();
        private long nextGeneration;

        synchronized long claim(File directory) {
            long generation = ++nextGeneration;
            generations.put(key(directory), generation);
            return generation;
        }

        synchronized long current(File directory) {
            Long generation = generations.get(key(directory));
            return generation == null ? 0 : generation;
        }

        synchronized boolean isCurrent(File directory, long generation) {
            return current(directory) == generation;
        }

        private String key(File directory) {
            try { return directory.getCanonicalPath(); }
            catch (IOException ignored) { return directory.getAbsolutePath(); }
        }
    }

    static boolean clearTemporaryDirectory(File temporary, File retainedRoot) {
        if (temporary == null || retainedRoot == null) return false;
        try {
            File root = temporary.getCanonicalFile();
            File retained = retainedRoot.getCanonicalFile();
            if (sameFile(root, retained) || isDescendant(root, retained)
                    || isDescendant(retained, root)) return false;
            return deleteTree(root, root);
        } catch (IOException ignored) {
            return false;
        }
    }

    private static boolean deleteTree(File target, File root) throws IOException {
        if (!target.exists()) return true;
        File canonical = target.getCanonicalFile();
        // Never follow links out of the temporary tree or into another branch.
        if (!canonical.equals(target.getAbsoluteFile())) return target.delete();
        if (!sameFile(canonical, root) && !isDescendant(canonical, root)) return false;
        boolean removed = true;
        if (target.isDirectory()) {
            File[] children = target.listFiles();
            if (children == null) return false;
            for (File child : children) removed &= deleteTree(child, root);
        }
        return target.delete() && removed;
    }

    static boolean isDescendant(File target, File root) throws IOException {
        String parent = root.getCanonicalPath() + File.separator;
        return target.getCanonicalPath().startsWith(parent);
    }

    static boolean sameFile(File first, File second) {
        if (first == null || second == null) return false;
        try { return first.getCanonicalFile().equals(second.getCanonicalFile()); }
        catch (IOException ignored) { return false; }
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
