package com.vlcplayer.app;

import android.content.Context;
import android.content.Intent;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Environment;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class PrivacyManager {

    private static final String PREF = "privacy";
    private static final String KEY  = "enabled";

    public static boolean isEnabled(Context ctx) {
        return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getBoolean(KEY, false);
    }

    public static void setEnabled(Context ctx, boolean enabled) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY, enabled).apply();

        List<String> scanPaths = new ArrayList<>();

        // Tat ca cac thu muc can tao .nomedia
        List<File> dirs = new ArrayList<>();
        dirs.add(Environment.getExternalStorageDirectory());
        dirs.add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS));
        dirs.add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES));
        dirs.add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM));
        dirs.add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES));

        for (File dir : dirs) {
            if (dir == null || !dir.exists()) continue;
            File nomedia = new File(dir, ".nomedia");
            if (enabled) {
                if (!nomedia.exists()) {
                    try {
                        nomedia.createNewFile();
                    } catch (IOException ignored) {}
                }
            } else {
                if (nomedia.exists()) nomedia.delete();
            }
            scanPaths.add(dir.getAbsolutePath());
        }

        // Dung MediaScannerConnection de cap nhat Gallery chinh xac
        String[] paths = scanPaths.toArray(new String[0]);
        MediaScannerConnection.scanFile(ctx, paths, null,
            (path, uri) -> {
                // Gui them broadcast de dam bao Gallery cap nhat
                Intent intent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
                if (uri != null) {
                    intent.setData(uri);
                    ctx.sendBroadcast(intent);
                }
            });

        // Gui broadcast toan bo storage
        ctx.sendBroadcast(new Intent(
            Intent.ACTION_MEDIA_SCANNER_SCAN_FILE,
            Uri.fromFile(Environment.getExternalStorageDirectory())));
    }
}
