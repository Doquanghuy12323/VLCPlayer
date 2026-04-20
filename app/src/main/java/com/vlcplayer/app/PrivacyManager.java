package com.vlcplayer.app;

import android.content.Context;
import android.media.MediaScannerConnection;
import java.io.File;
import java.util.List;

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

        java.util.Set<String> folders = new java.util.HashSet<>();
        for (String path : filePaths) {
            File f = new File(path);
            if (f.getParentFile() != null) folders.add(f.getParent());
        }

        if (folders.isEmpty()) {
            if (onDone != null) onDone.run();
            return;
        }

        String[] paths = folders.toArray(new String[0]);

        for (String folder : folders) {
            File nomedia = new File(folder, ".nomedia");
            if (enabled) {
                try { if (!nomedia.exists()) nomedia.createNewFile(); }
                catch (Exception ignored) {}
            } else {
                if (nomedia.exists()) nomedia.delete();
            }
        }

        // Scan xong roi goi callback
        MediaScannerConnection.scanFile(ctx, paths, null,
            (path, uri) -> { if (onDone != null) onDone.run(); });
    }
}
