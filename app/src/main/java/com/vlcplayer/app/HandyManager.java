package com.vlcplayer.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import org.json.JSONObject;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class HandyManager {
    private static final String TAG = "HandyManager";
    private static final String BASE_URL = "https://www.handyfeeling.com/api/handy/v2";
    private static final String PREF = "handy_prefs";
    private static final String KEY_TOKEN = "connection_key";

    public interface HandyCallback {
        void onSuccess(String message);
        void onError(String error);
    }

    private final Context ctx;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private String connectionKey;
    private boolean isConnected = false;
    private long serverTimeOffset = 0; // offset ms de dong bo thoi gian

    public HandyManager(Context ctx) {
        this.ctx = ctx;
        this.connectionKey = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY_TOKEN, "");
    }

    public void saveKey(String key) {
        this.connectionKey = key.trim();
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(KEY_TOKEN, this.connectionKey).apply();
    }

    public String getSavedKey() { return connectionKey; }
    public boolean isConnected() { return isConnected; }

    // === STEP 1: Ket noi va calibrate server time ===
    public void connect(HandyCallback cb) {
        if (connectionKey.isEmpty()) { handler.post(() -> cb.onError("Chua nhap Connection Key")); return; }
        executor.execute(() -> {
            try {
                // 1. Check connected
                JSONObject info = apiGet("/info");
                if (info == null) { handler.post(() -> cb.onError("Khong ket noi duoc The Handy")); return; }
                String fwVersion = info.optString("fwVersion", "?");
                String hwModel = info.optString("hwModel", "?");

                // 2. Calibrate server time de tranh delay
                syncServerTime();

                isConnected = true;
                handler.post(() -> cb.onSuccess("Ket noi thanh cong!\nFirmware: " + fwVersion + "\nModel: " + hwModel));
            } catch (Exception e) {
                handler.post(() -> cb.onError("Loi: " + e.getMessage()));
            }
        });
    }

    // Dong bo server time - goi nhieu lan de co offset chinh xac
    private void syncServerTime() {
        try {
            long totalOffset = 0;
            int rounds = 5;
            for (int i = 0; i < rounds; i++) {
                long t0 = System.currentTimeMillis();
                JSONObject res = apiGet("/servertime");
                long t1 = System.currentTimeMillis();
                if (res != null) {
                    long serverTime = res.optLong("serverTime", 0);
                    long rtt = t1 - t0;
                    long estimatedServerNow = serverTime + rtt / 2;
                    totalOffset += estimatedServerNow - t1;
                }
                Thread.sleep(100);
            }
            serverTimeOffset = totalOffset / rounds;
            Log.d(TAG, "Server time offset: " + serverTimeOffset + "ms");
        } catch (Exception e) {
            Log.e(TAG, "Sync time error: " + e.getMessage());
        }
    }

    // Lay server time da calibrate
    public long getServerTime() {
        return System.currentTimeMillis() + serverTimeOffset;
    }

    // === STEP 2: Upload funscript len Handy cloud va lay script URL ===
    public void setupScript(String scriptCsvUrl, HandyCallback cb) {
        executor.execute(() -> {
            try {
                // Set HSSP mode
                JSONObject modeBody = new JSONObject();
                modeBody.put("mode", 0); // HSSP = 0
                JSONObject modeRes = apiPut("/mode", modeBody);

                // Setup script voi URL
                JSONObject body = new JSONObject();
                body.put("url", scriptCsvUrl);
                body.put("timeout", 30000);
                JSONObject res = apiPut("/hssp/setup", body);

                if (res == null) { handler.post(() -> cb.onError("Loi setup script")); return; }

                int result = res.optInt("result", -1);
                if (result == 0) {
                    handler.post(() -> cb.onSuccess("Script da san sang"));
                } else {
                    handler.post(() -> cb.onError("Setup loi, code: " + result));
                }
            } catch (Exception e) {
                handler.post(() -> cb.onError("Loi: " + e.getMessage()));
            }
        });
    }

    // === STEP 3: Play dong bo voi video ===
    // estimatedServerTime = thoi diem bat dau video tinh theo server time
    public void play(long videoPositionMs, HandyCallback cb) {
        executor.execute(() -> {
            try {
                long estimatedServerTime = getServerTime();
                // Tinh thoi diem server bat dau phat
                // startTime = serverTimeNow - videoPositionMs
                long startTime = estimatedServerTime - videoPositionMs;

                JSONObject body = new JSONObject();
                body.put("estimatedServerTime", estimatedServerTime);
                body.put("startTime", startTime);
                JSONObject res = apiPut("/hssp/play", body);

                if (res != null && res.optInt("result", -1) == 0) {
                    handler.post(() -> cb.onSuccess("Dang phat"));
                } else {
                    handler.post(() -> cb.onError("Loi play: " + (res != null ? res.optString("error") : "null")));
                }
            } catch (Exception e) {
                handler.post(() -> cb.onError("Loi: " + e.getMessage()));
            }
        });
    }

    // Stop
    public void stop(HandyCallback cb) {
        executor.execute(() -> {
            try {
                JSONObject res = apiPut("/hssp/stop", new JSONObject());
                isConnected = false;
                handler.post(() -> {
                    if (cb != null) cb.onSuccess("Da dung");
                });
            } catch (Exception e) {
                if (cb != null) handler.post(() -> cb.onError(e.getMessage()));
            }
        });
    }

    // Sync khi seek video
    public void seekSync(long newVideoPositionMs) {
        if (!isConnected) return;
        play(newVideoPositionMs, new HandyCallback() {
            @Override public void onSuccess(String m) { Log.d(TAG, "Seek sync ok"); }
            @Override public void onError(String e) { Log.e(TAG, "Seek sync error: " + e); }
        });
    }

    // Lay trang thai device
    public void getStatus(HandyCallback cb) {
        executor.execute(() -> {
            try {
                JSONObject res = apiGet("/info");
                if (res != null) {
                    String status = "Firmware: " + res.optString("fwVersion") +
                        "\nConnected: " + res.optBoolean("connected") +
                        "\nMode: " + res.optInt("currentMode");
                    handler.post(() -> cb.onSuccess(status));
                } else {
                    handler.post(() -> cb.onError("Khong lay duoc trang thai"));
                }
            } catch (Exception e) {
                handler.post(() -> cb.onError(e.getMessage()));
            }
        });
    }

    // === HTTP HELPERS ===
    private JSONObject apiGet(String endpoint) {
        try {
            URL url = new URL(BASE_URL + endpoint);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("X-Connection-Key", connectionKey);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);

            int code = conn.getResponseCode();
            if (code == 200) {
                byte[] bytes = conn.getInputStream().readAllBytes();
                return new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            }
            Log.e(TAG, "GET " + endpoint + " -> " + code);
        } catch (Exception e) {
            Log.e(TAG, "GET error: " + e.getMessage());
        }
        return null;
    }

    private JSONObject apiPut(String endpoint, JSONObject body) {
        try {
            URL url = new URL(BASE_URL + endpoint);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("PUT");
            conn.setRequestProperty("X-Connection-Key", connectionKey);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);

            byte[] bodyBytes = body.toString().getBytes(StandardCharsets.UTF_8);
            OutputStream os = conn.getOutputStream();
            os.write(bodyBytes);
            os.close();

            int code = conn.getResponseCode();
            byte[] bytes;
            if (code >= 200 && code < 300) {
                bytes = conn.getInputStream().readAllBytes();
            } else {
                bytes = conn.getErrorStream() != null ? conn.getErrorStream().readAllBytes() : new byte[0];
            }
            if (bytes.length > 0) return new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.e(TAG, "PUT error: " + e.getMessage());
        }
        return null;
    }
}
