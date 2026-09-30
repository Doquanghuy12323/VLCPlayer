package com.vlcplayer.app;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Extracts one reader session, checking every decompressed byte and output path. */
final class CbzArchiveReader {
    private static final int MAX_PAGES = 2000;
    private static final long MAX_BYTES = 500L * 1024 * 1024;

    interface CancellationCheck {
        boolean isCancelled();
    }

    static List<File> read(InputStream source, File directory,
            CancellationCheck cancellation) throws IOException {
        return read(source, directory, cancellation, MAX_PAGES, MAX_BYTES);
    }

    static List<File> read(InputStream source, File directory,
            CancellationCheck cancellation, int maxPages, long maxBytes) throws IOException {
        if (source == null) throw new IOException("Cannot open CBZ");
        checkCancellation(cancellation);
        if (!directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException("Cannot create page cache");
        }
        String rootPath = directory.getCanonicalPath() + File.separator;
        List<File> pages = new ArrayList<>();
        Set<String> outputPaths = new HashSet<>();
        long totalBytes = 0;
        try (ZipInputStream zip = new ZipInputStream(source)) {
            ZipEntry entry;
            byte[] buffer = new byte[16384];
            while ((entry = zip.getNextEntry()) != null) {
                checkCancellation(cancellation);
                String name = entry.getName().toLowerCase(Locale.US);
                boolean image = !entry.isDirectory() && (name.endsWith(".jpg")
                        || name.endsWith(".jpeg") || name.endsWith(".png")
                        || name.endsWith(".webp") || name.endsWith(".gif"));
                File output = new File(directory, entry.getName());
                String outputPath = output.getCanonicalPath();
                if (!outputPath.startsWith(rootPath)) {
                    throw new IOException("Invalid CBZ path");
                }
                if (image && (pages.size() >= maxPages || !outputPaths.add(outputPath))) {
                    throw new IOException("Too many or duplicate CBZ pages");
                }
                if (entry.getSize() > maxBytes - totalBytes) {
                    throw new IOException("CBZ exceeds unpacked size limit");
                }
                if (image) {
                    File parent = output.getParentFile();
                    if (parent != null && !parent.mkdirs() && !parent.isDirectory()) {
                        throw new IOException("Cannot create page directory");
                    }
                }
                try (FileOutputStream file = image ? new FileOutputStream(output) : null) {
                    int count;
                    while ((count = zip.read(buffer)) != -1) {
                        checkCancellation(cancellation);
                        totalBytes += count;
                        if (totalBytes > maxBytes) {
                            throw new IOException("CBZ exceeds unpacked size limit");
                        }
                        if (file != null) file.write(buffer, 0, count);
                    }
                }
                if (image) pages.add(output);
            }
        }
        checkCancellation(cancellation);
        Collections.sort(pages, (a, b) -> naturalCompare(a.getName(), b.getName()));
        return pages;
    }

    private static void checkCancellation(CancellationCheck cancellation)
            throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted() || cancellation.isCancelled()) {
            throw new InterruptedIOException("CBZ loading cancelled");
        }
    }

    private static int naturalCompare(String a, String b) {
        int ia = 0, ib = 0;
        while (ia < a.length() && ib < b.length()) {
            char ca = a.charAt(ia), cb = b.charAt(ib);
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                long na = 0, nb = 0;
                while (ia < a.length() && Character.isDigit(a.charAt(ia))) {
                    na = Math.min(Integer.MAX_VALUE, na * 10 + a.charAt(ia++) - '0');
                }
                while (ib < b.length() && Character.isDigit(b.charAt(ib))) {
                    nb = Math.min(Integer.MAX_VALUE, nb * 10 + b.charAt(ib++) - '0');
                }
                if (na != nb) return Long.compare(na, nb);
            } else {
                ca = Character.toLowerCase(ca);
                cb = Character.toLowerCase(cb);
                if (ca != cb) return Character.compare(ca, cb);
                ia++;
                ib++;
            }
        }
        return Integer.compare(a.length(), b.length());
    }

    /** Never follows a path outside the exact session directory being discarded. */
    static void deleteSession(File sessionDirectory) {
        try {
            deleteWithin(sessionDirectory, sessionDirectory.getCanonicalPath());
        } catch (IOException ignored) {
            // Cache cleanup can be retried by the operating system.
        }
    }

    private static void deleteWithin(File file, String sessionPath) throws IOException {
        String path = file.getCanonicalPath();
        if (!path.equals(sessionPath) && !path.startsWith(sessionPath + File.separator)) return;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteWithin(child, sessionPath);
        }
        file.delete();
    }
}
