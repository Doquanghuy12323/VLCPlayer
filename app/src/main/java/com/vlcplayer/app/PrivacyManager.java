package com.vlcplayer.app;

import android.content.Context;
import android.media.MediaScannerConnection;
import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

public class PrivacyManager {
    private static final String PREF = "privacy_prefs";
    private static final String KEY_ENABLED = "privacy_enabled";
    private final Context ctx;

    public PrivacyManager(Context ctx) { this.ctx = ctx; }

    public boolean isEnabled() {
        return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false);
    }

    public void setEnabled(boolean enabled, List<String> filePaths, Runnable onDone) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply();

        Set<String> folders = new HashSet<>();
        for (String path : filePaths) {
            File f = new File(path);
            if (f.getParentFile() != null) folders.add(f.getParent());
        }

        // Xu ly .nomedia
        for (String folder : folders) {
            File nomedia = new File(folder, ".nomedia");
            if (enabled) {
                try { if (!nomedia.exists()) nomedia.createNewFile(); }
                catch (Exception ignored) {}
            } else {
                if (nomedia.exists()) nomedia.delete();
            }
        }

        if (enabled) {
            // An: scan .nomedia de bao cho MediaStore biet
            List<String> nomediaPaths = new ArrayList<>();
            for (String folder : folders) {
                nomediaPaths.add(folder + "/.nomedia");
            }
            MediaScannerConnection.scanFile(ctx,
                nomediaPaths.toArray(new String[0]), null,
                (p, u) -> { if (onDone != null) onDone.run(); });
        } else {
            // Hien: scan TUNG FILE de force MediaStore index lai
            if (filePaths.isEmpty()) {
                if (onDone != null) onDone.run();
                return;
            }
            // Lay danh sach tat ca file video trong cac folder
            List<String> videoFiles = new ArrayList<>();
            for (String folder : folders) {
                File dir = new File(folder);
                File[] files = dir.listFiles();
                if (files != null) {
                    for (File f : files) {
                        String name = f.getName().toLowerCase();
                        if (name.endsWith(".mp4") || name.endsWith(".mkv") ||
                            name.endsWith(".avi") || name.endsWith(".mov") ||
                            name.endsWith(".wmv") || name.endsWith(".flv") ||
                            name.endsWith(".webm")) {
                            videoFiles.add(f.getAbsolutePath());
                        }
                    }
                }
            }
            if (videoFiles.isEmpty()) {
                if (onDone != null) onDone.run();
                return;
            }
            // Dem so file da scan xong
            AtomicInteger done = new AtomicInteger(0);
            int total = videoFiles.size();
            MediaScannerConnection.scanFile(ctx,
                videoFiles.toArray(new String[0]), null,
                (p, u) -> {
                    if (done.incrementAndGet() >= total) {
                        if (onDone != null) onDone.run();
                    }
                });
        }
    }
}
