package com.vlcplayer.app;

import android.content.Context;
import android.media.MediaScannerConnection;
import android.os.Environment;
import java.io.File;

public class PrivacyManager {
    private static final String PREF = "privacy_prefs";
    private static final String KEY_ENABLED = "privacy_enabled";
    private final Context ctx;

    public PrivacyManager(Context ctx) { this.ctx = ctx; }

    public boolean isEnabled() {
        return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false);
    }

    public void setEnabled(boolean enabled, java.util.List<String> paths) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply();
        // Them/xoa .nomedia trong thu muc
        for (String path : paths) {
            File dir = new File(path).getParentFile();
            if (dir == null) continue;
            File nomedia = new File(dir, ".nomedia");
            try {
                if (enabled) { if (!nomedia.exists()) nomedia.createNewFile(); }
                else { if (nomedia.exists()) { nomedia.delete(); MediaScannerConnection.scanFile(ctx, new String[]{dir.getAbsolutePath()}, null, null); } }
            } catch (Exception ignored) {}
        }
    }

    public void hideFolder(String folderPath) {
        File nomedia = new File(folderPath, ".nomedia");
        try { if (!nomedia.exists()) nomedia.createNewFile(); } catch (Exception ignored) {}
    }

    public void unhideFolder(String folderPath) {
        File nomedia = new File(folderPath, ".nomedia");
        if (nomedia.exists()) {
            nomedia.delete();
            MediaScannerConnection.scanFile(ctx, new String[]{folderPath}, null, null);
        }
    }
}
