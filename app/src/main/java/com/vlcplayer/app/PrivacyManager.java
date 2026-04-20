package com.vlcplayer.app;

import android.content.Context;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.provider.MediaStore;
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

    public void setEnabled(boolean enabled, List<String> filePaths) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply();

        // Thu thap cac folder duy nhat
        java.util.Set<String> folders = new java.util.HashSet<>();
        for (String path : filePaths) {
            File f = new File(path);
            if (f.getParentFile() != null) folders.add(f.getParent());
        }

        for (String folder : folders) {
            File nomedia = new File(folder, ".nomedia");
            if (enabled) {
                // An: tao .nomedia + xoa khoi MediaStore
                try { if (!nomedia.exists()) nomedia.createNewFile(); } 
                catch (Exception ignored) {}
                deleteFromMediaStore(folder);
            } else {
                // Hien: xoa .nomedia + scan lai de hien thi
                if (nomedia.exists()) nomedia.delete();
                MediaScannerConnection.scanFile(ctx, 
                    new String[]{folder}, null, null);
            }
        }
    }

    private void deleteFromMediaStore(String folder) {
        // Xoa tat ca video trong folder khoi MediaStore database
        try {
            Uri uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
            ctx.getContentResolver().delete(uri,
                MediaStore.Video.Media.DATA + " LIKE ?",
                new String[]{folder + "/%"});
        } catch (Exception e) {
            // Neu khong xoa duoc, dung scanFile de force rescan
            MediaScannerConnection.scanFile(ctx,
                new String[]{folder}, null, null);
        }
    }

    public void hideFolder(String folderPath) {
        File nomedia = new File(folderPath, ".nomedia");
        try { if (!nomedia.exists()) nomedia.createNewFile(); } 
        catch (Exception ignored) {}
        deleteFromMediaStore(folderPath);
    }

    public void unhideFolder(String folderPath) {
        File nomedia = new File(folderPath, ".nomedia");
        if (nomedia.exists()) nomedia.delete();
        MediaScannerConnection.scanFile(ctx, 
            new String[]{folderPath}, null, null);
    }
}
