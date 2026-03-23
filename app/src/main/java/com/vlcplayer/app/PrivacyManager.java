package com.vlcplayer.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;

import java.io.File;
import java.io.IOException;

public class PrivacyManager {
    private static final String PREF = "privacy";
    private static final String KEY_ENABLED = "enabled";

    public static boolean isEnabled(Context ctx) {
        return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false);
    }

    public static void setEnabled(Context ctx, boolean enabled) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply();

        // Tạo/xóa file .nomedia trong thư mục app
        File dir = ctx.getExternalFilesDir(null);
        if (dir != null) {
            File nomedia = new File(dir, ".nomedia");
            if (enabled) {
                try { nomedia.createNewFile(); } catch (IOException ignored) {}
            } else {
                nomedia.delete();
            }
        }

        // Tạo .nomedia trong Downloads nếu bật
        File downloads = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOWNLOADS);
        if (downloads != null) {
            File nomedia = new File(downloads, ".vlcplayer_nomedia");
            if (enabled) {
                try { nomedia.createNewFile(); } catch (IOException ignored) {}
            } else {
                nomedia.delete();
            }
        }
    }
}
