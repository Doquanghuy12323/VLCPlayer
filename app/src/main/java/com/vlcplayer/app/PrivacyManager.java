package com.vlcplayer.app;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaScannerConnection;
import android.util.Log;
import android.view.WindowManager;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

public class PrivacyManager {
    private static final String PREF = "privacy_prefs";
    private static final String KEY_ENABLED = "privacy_enabled";
    private static final String KEY_OWNED_MARKERS = "owned_nomedia_markers";
    private static final String MARKER_PREFIX = "VLCPlayer privacy marker:";
    private static final String TAG = "PrivacyManager";
    private final Context ctx;

    public PrivacyManager(Context ctx) { this.ctx = ctx.getApplicationContext(); }

    public boolean isEnabled() {
        return prefs().getBoolean(KEY_ENABLED, false);
    }

    public void applyWindowSecurity(Activity activity) {
        if (isEnabled()) {
            activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        } else {
            activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
    }

    private SharedPreferences prefs() {
        return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    /** Returns the number of markers changed, or -1 if any marker could not be changed. */
    public synchronized int setEnabled(boolean enabled, List<String> paths) {
        // Screenshot protection must still work where scoped storage blocks marker writes.
        if (!prefs().edit().putBoolean(KEY_ENABLED, enabled).commit()) return -1;

        JSONObject owned = readOwned();
        if (owned == null) return -1;
        int changed = 0;
        boolean failed = false;
        if (enabled) {
            Set<String> visited = new HashSet<>();
            if (paths != null) for (String path : paths) {
                if (path == null || path.trim().isEmpty()) continue;
                File dir = new File(path).getAbsoluteFile().getParentFile();
                if (dir == null || !visited.add(dir.getAbsolutePath())) continue;
                int result = createMarker(dir, owned);
                if (result > 0) changed++;
                if (result < 0) failed = true;
            }
        } else {
            // Hidden videos disappear from MediaStore, so the private journal is authoritative.
            Iterator<String> dirs = owned.keys();
            Set<String> recorded = new HashSet<>();
            while (dirs.hasNext()) recorded.add(dirs.next());
            for (String path : recorded) {
                int result = removeMarker(new File(path), owned);
                if (result > 0) changed++;
                if (result < 0) failed = true;
            }
        }
        return failed ? -1 : changed;
    }

    private JSONObject readOwned() {
        try {
            String journal = prefs().getString(KEY_OWNED_MARKERS, "{}");
            return new JSONObject(journal == null ? "{}" : journal);
        } catch (JSONException e) {
            Log.e(TAG, "Cannot read owned privacy markers", e);
            return null; // Never remove a marker when ownership cannot be established.
        }
    }

    private boolean saveOwned(JSONObject owned) {
        return prefs().edit().putString(KEY_OWNED_MARKERS, owned.toString()).commit();
    }

    private int createMarker(File dir, JSONObject owned) {
        File marker = new File(dir, ".nomedia");
        if (marker.exists()) return 0; // It may belong to the user or another app.
        boolean created = false;
        try {
            created = marker.createNewFile();
            if (!created) return 0;
            String token = newToken();
            try (FileOutputStream output = new FileOutputStream(marker)) {
                output.write((MARKER_PREFIX + token + "\n")
                    .getBytes(StandardCharsets.US_ASCII));
            }
            owned.put(dir.getAbsolutePath(), token);
            if (saveOwned(owned)) return 1;
            owned.remove(dir.getAbsolutePath());
        } catch (Exception e) {
            owned.remove(dir.getAbsolutePath());
            Log.w(TAG, "Cannot hide folder: " + dir, e);
        }
        if (created && !marker.delete()) Log.w(TAG, "Could not clean untracked marker: " + marker);
        return -1;
    }

    private int removeMarker(File dir, JSONObject owned) {
        String path = dir.getAbsolutePath();
        File marker = new File(dir, ".nomedia");
        String token = owned.optString(path, "");
        if (marker.exists() && !hasOwnedContent(marker, token)) {
            Log.w(TAG, "Marker changed externally; leaving it in place: " + marker);
            return -1;
        }
        boolean existed = marker.exists();
        if (existed && !marker.delete()) {
            Log.w(TAG, "Cannot remove privacy marker: " + marker);
            return -1;
        }
        owned.remove(path);
        if (!saveOwned(owned)) return -1;
        if (existed) MediaScannerConnection.scanFile(ctx, new String[]{path}, null, null);
        return existed ? 1 : 0;
    }

    private String newToken() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(Character.forDigit((value >>> 4) & 15, 16));
            result.append(Character.forDigit(value & 15, 16));
        }
        return result.toString();
    }

    private boolean hasOwnedContent(File marker, String token) {
        if (token.isEmpty() || marker.length() > 128) return false;
        byte[] bytes = new byte[(int) marker.length()];
        try (FileInputStream input = new FileInputStream(marker)) {
            int offset = 0;
            while (offset < bytes.length) {
                int read = input.read(bytes, offset, bytes.length - offset);
                if (read < 0) return false;
                offset += read;
            }
            if (input.read() != -1) return false;
            return (MARKER_PREFIX + token + "\n")
                .equals(new String(bytes, StandardCharsets.US_ASCII));
        } catch (IOException e) {
            Log.w(TAG, "Cannot verify privacy marker: " + marker, e);
            return false;
        }
    }

    public synchronized void hideFolder(String folderPath) {
        JSONObject owned = readOwned();
        if (owned != null && folderPath != null) createMarker(new File(folderPath), owned);
    }

    public synchronized void unhideFolder(String folderPath) {
        JSONObject owned = readOwned();
        if (owned != null && folderPath != null && owned.has(new File(folderPath).getAbsolutePath())) {
            removeMarker(new File(folderPath), owned);
        }
    }
}
