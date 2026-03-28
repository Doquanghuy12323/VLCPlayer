package com.vlcplayer.app;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;

import java.io.File;
import java.io.IOException;

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

        // Tao .nomedia trong tat ca thu muc pho bien
        String[] dirs = {
            Environment.getExternalStorageDirectory().getAbsolutePath(),
            Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS).getAbsolutePath(),
            Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_MOVIES).getAbsolutePath(),
            Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DCIM).getAbsolutePath(),
        };

        for (String path : dirs) {
            File nomedia = new File(path, ".nomedia");
            if (enabled) {
                if (!nomedia.exists()) {
                    try { nomedia.createNewFile(); } catch (IOException ignored) {}
                }
            } else {
                nomedia.delete();
            }
        }

        // Thong bao Media Scanner quet lai
        Intent scan = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
        scan.setData(Uri.fromFile(
            Environment.getExternalStorageDirectory()));
        ctx.sendBroadcast(scan);
    }
}
