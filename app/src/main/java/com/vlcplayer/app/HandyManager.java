package com.vlcplayer.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Connection-key based Handy 1 controller.
 *
 * The official Handy SDK still uses REST v2 for this flow. Firmware 4 keeps the
 * HSSP protocol available, while newer REST v3 account authentication remains a
 * separate integration. All device commands here are serialized and playback
 * stop commands use an independent fail-safe path.
 */
public class HandyManager {
    private static final String TAG = "HandyManager";
    private static final String BASE_URL = "https://www.handyfeeling.com/api/handy/v2";
    private static final String SCRIPT_UPLOAD_URL =
        "https://scripts01.handyfeeling.com/api/script/v0/temp/upload";
    private static final String PREF = "handy_prefs";
    private static final String KEY_TOKEN = "connection_key";

    private static final int HSSP_MODE = 1;
    private static final int SYNC_SAMPLES = 30;
    private static final int SYNC_OUTLIERS = 10;
    private static final long RESYNC_INTERVAL_MS = TimeUnit.HOURS.toMillis(1);
    private static final long HEALTH_CHECK_INTERVAL_MS = TimeUnit.SECONDS.toMillis(30);
    private static final int MAX_CSV_BYTES = 524_288;
    private static final int MAX_SOURCE_BYTES = 2 * 1024 * 1024;

    private static final MediaType JSON_MEDIA_TYPE =
        MediaType.get("application/json; charset=utf-8");
    private static final MediaType CSV_MEDIA_TYPE =
        MediaType.get("text/plain; charset=utf-8");

    public interface HandyCallback {
        void onSuccess(String message);
        void onError(String error);
    }

    private static final class TimeSample {
        final long rtt;
        final long offset;

        TimeSample(long rtt, long offset) {
            this.rtt = rtt;
            this.offset = offset;
        }
    }

    private static final class ScriptPoint {
        final long at;
        final int pos;

        ScriptPoint(long at, int pos) {
            this.at = at;
            this.pos = pos;
        }
    }

    private static final class HandyException extends Exception {
        HandyException(String message) {
            super(message);
        }
    }

    private final Context appContext;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService commandExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService emergencyExecutor = Executors.newSingleThreadExecutor();
    private final AtomicLong playbackGeneration = new AtomicLong();
    private final AtomicLong scriptGeneration = new AtomicLong();
    private final OkHttpClient httpClient = new OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .writeTimeout(12, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build();

    private volatile String connectionKey;
    private volatile boolean connected;
    private volatile boolean scriptReady;
    private volatile boolean playing;
    private volatile boolean desiredPlaying;
    private volatile boolean destroyed;
    private volatile boolean autoReconnectEnabled = true;
    private volatile long serverTimeOffset;
    private volatile long averageRtt;
    private volatile long lastTimeSyncElapsed;
    private volatile long lastHealthCheckElapsed;
    private volatile String firmwareVersion = "?";
    private volatile int firmwareStatus = -1;
    private volatile String model = "?";
    private volatile String branch = "?";
    private volatile String sessionId = "";
    private volatile int currentMode = -1;
    private volatile String lastScriptUrl;
    private volatile long lastRequestedPositionMs;
    private volatile Call activePlaybackCall;

    public HandyManager(Context context) {
        appContext = context.getApplicationContext();
        connectionKey = appContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY_TOKEN, "");
    }

    public void saveKey(String key) {
        scriptGeneration.incrementAndGet();
        connectionKey = key == null ? "" : key.trim();
        autoReconnectEnabled = true;
        connected = false;
        scriptReady = false;
        playing = false;
        desiredPlaying = false;
        appContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(KEY_TOKEN, connectionKey).apply();
    }

    public String getSavedKey() {
        return connectionKey;
    }

    public boolean isConnected() {
        return connected;
    }

    public boolean isScriptReady() {
        return scriptReady;
    }

    public boolean isPlaying() {
        return playing;
    }

    public void connect(HandyCallback callback) {
        if (destroyed) return;
        if (connectionKey.isEmpty()) {
            postError(callback, "Chưa nhập Connection Key");
            return;
        }
        autoReconnectEnabled = true;
        commandExecutor.execute(() -> {
            try {
                connectInternal();
                String updateText = firmwareStatus == 0
                    ? "Firmware đã cập nhật"
                    : firmwareStatus == 1
                        ? "Firmware cần cập nhật"
                        : firmwareStatus == 2
                            ? "Có bản firmware mới"
                            : "Không xác định trạng thái cập nhật";
                postSuccess(callback, "Kết nối thành công\nFirmware: " + firmwareVersion
                    + "\nModel: " + model + "\n" + updateText);
            } catch (Exception e) {
                connected = false;
                playing = false;
                postError(callback, friendlyError(e));
            }
        });
    }

    private void connectInternal() throws Exception {
        JSONObject connectedResponse = apiRequest("GET", "/connected", null, false);
        if (!connectedResponse.optBoolean("connected", false)) {
            throw new HandyException("The Handy chưa ở Online Mode hoặc chưa kết nối Wi-Fi");
        }

        JSONObject info = apiRequest("GET", "/info", null, false);
        String newFirmware = info.optString("fwVersion", "?");
        int majorVersion = parseVersionPart(newFirmware, 0);
        if (majorVersion > 0 && majorVersion < 3) {
            throw new HandyException("Firmware quá cũ. Cần cập nhật The Handy trước khi đồng bộ");
        }

        String newSession = info.optString("sessionId", "");
        boolean sessionChanged = !sessionId.isEmpty() && !sessionId.equals(newSession);
        firmwareVersion = newFirmware;
        firmwareStatus = info.optInt("fwStatus", -1);
        model = info.optString("model", info.optString("hwModel", "?"));
        branch = info.optString("branch", "?");
        sessionId = newSession;

        syncClientServerTime();
        syncHandyServerTimeBestEffort();

        JSONObject status = apiRequest("GET", "/status", null, false);
        currentMode = status.optInt("mode", -1);
        if (sessionChanged) {
            scriptReady = false;
            playing = false;
        }
        connected = true;
        Log.i(TAG, "Connected model=" + model + " firmware=" + firmwareVersion
            + " mode=" + currentMode + " rtt=" + averageRtt + "ms");
    }

    private void syncClientServerTime() throws Exception {
        List<TimeSample> samples = new ArrayList<>();
        for (int i = 0; i < SYNC_SAMPLES && !Thread.currentThread().isInterrupted(); i++) {
            long sent = System.currentTimeMillis();
            try {
                JSONObject response = apiRequest("GET", "/servertime", null, false);
                long received = System.currentTimeMillis();
                long serverTime = response.optLong("serverTime", 0);
                if (serverTime > 0) {
                    long rtt = Math.max(0, received - sent);
                    samples.add(new TimeSample(rtt, serverTime + rtt / 2L - received));
                }
            } catch (Exception sampleError) {
                Log.w(TAG, "Time sample failed: " + sampleError.getMessage());
            }
        }

        if (samples.size() < 5) {
            throw new HandyException("Mạng không đủ ổn định để đồng bộ thời gian với The Handy");
        }
        Collections.sort(samples, (left, right) -> Long.compare(left.rtt, right.rtt));
        int discard = Math.min(SYNC_OUTLIERS, samples.size() / 3);
        int usable = samples.size() - discard;
        long offsetTotal = 0;
        long rttTotal = 0;
        for (int i = 0; i < usable; i++) {
            offsetTotal += samples.get(i).offset;
            rttTotal += samples.get(i).rtt;
        }
        serverTimeOffset = Math.round((double) offsetTotal / usable);
        averageRtt = Math.round((double) rttTotal / usable);
        lastTimeSyncElapsed = SystemClock.elapsedRealtime();
        Log.d(TAG, "Time sync offset=" + serverTimeOffset + "ms rtt=" + averageRtt + "ms");
    }

    private void syncHandyServerTimeBestEffort() {
        if (parseVersionPart(firmwareVersion, 0) < 3) return;
        try {
            JSONObject response = apiRequest("GET",
                "/hstp/sync?syncCount=" + SYNC_SAMPLES + "&outliers=" + SYNC_OUTLIERS,
                null, false);
            if (response.has("result") && response.optInt("result", -1) != 0) {
                Log.w(TAG, "Handy clock sync returned " + response.optInt("result", -1));
            }
        } catch (Exception e) {
            // Client/server time sync is still valid. Firmware 4 gateways can perform
            // their own device clock synchronization, so this is non-fatal.
            Log.w(TAG, "Handy clock sync skipped: " + e.getMessage());
        }
    }

    public void setupScript(String scriptCsvUrl, HandyCallback callback) {
        if (destroyed) return;
        if (!isHttpUrl(scriptCsvUrl)) {
            postError(callback, "URL script không hợp lệ");
            return;
        }
        final long generation = beginScriptLoad();
        commandExecutor.execute(() -> {
            try {
                ensureConnectedInternal();
                if (setupScriptInternal(scriptCsvUrl, generation)) {
                    postSuccess(callback, "Script đã sẵn sàng");
                }
            } catch (Exception e) {
                postError(callback, friendlyError(e));
            }
        });
    }

    public void uploadAndSetupScript(File file, HandyCallback callback) {
        if (destroyed) return;
        if (file == null || !file.isFile()) {
            postError(callback, "Không tìm thấy file funscript");
            return;
        }
        final long generation = beginScriptLoad();
        commandExecutor.execute(() -> {
            try {
                ensureConnectedInternal();
                byte[] source = readLimited(new FileInputStream(file), MAX_SOURCE_BYTES);
                byte[] csv = convertToCsv(source);
                String scriptUrl = uploadCsv(csv);
                if (setupScriptInternal(scriptUrl, generation)) {
                    postSuccess(callback, "Đã tải và đồng bộ script");
                }
            } catch (Exception e) {
                postError(callback, friendlyError(e));
            }
        });
    }

    public void uploadAndSetupScript(String sourceUrl, HandyCallback callback) {
        if (destroyed) return;
        if (!isHttpUrl(sourceUrl)) {
            postError(callback, "URL funscript không hợp lệ");
            return;
        }
        final long generation = beginScriptLoad();
        commandExecutor.execute(() -> {
            try {
                ensureConnectedInternal();
                byte[] source = downloadScript(sourceUrl);
                byte[] csv = convertToCsv(source);
                String scriptUrl = uploadCsv(csv);
                if (setupScriptInternal(scriptUrl, generation)) {
                    postSuccess(callback, "Đã tải và đồng bộ script");
                }
            } catch (Exception e) {
                postError(callback, friendlyError(e));
            }
        });
    }

    private long beginScriptLoad() {
        long generation = scriptGeneration.incrementAndGet();
        // Do not allow play/health recovery to reuse the previous script while
        // a replacement is being converted, uploaded, or prepared.
        scriptReady = false;
        playing = false;
        lastScriptUrl = null;
        stopPlayback(null);
        return generation;
    }

    private boolean setupScriptInternal(String scriptCsvUrl, long generation) throws Exception {
        if (generation != scriptGeneration.get()) return false;
        JSONObject modeBody = new JSONObject();
        modeBody.put("mode", HSSP_MODE);
        JSONObject modeResponse = apiRequest("PUT", "/mode", modeBody, false);
        int modeResult = modeResponse.optInt("result", -1);
        if (modeResult != 0 && modeResult != 1) {
            throw new HandyException("Không chuyển được The Handy sang chế độ HSSP");
        }
        currentMode = HSSP_MODE;

        JSONObject setupBody = new JSONObject();
        setupBody.put("url", scriptCsvUrl);
        JSONObject setupResponse = apiRequest("PUT", "/hssp/setup", setupBody, false);
        int setupResult = setupResponse.optInt("result", -1);
        if (setupResult != 0 && setupResult != 1) {
            throw new HandyException("The Handy không tải được script, mã " + setupResult);
        }

        if (generation != scriptGeneration.get()) return false;
        ensureHsspLoopDisabled();

        if (generation != scriptGeneration.get()) {
            Log.d(TAG, "Ignoring stale HSSP setup result");
            return false;
        }

        lastScriptUrl = scriptCsvUrl;
        scriptReady = true;
        playing = false;
        Log.i(TAG, "HSSP script ready");
        return true;
    }

    /** Keep legacy firmware from replaying a completed script unexpectedly. */
    private void ensureHsspLoopDisabled() throws Exception {
        // Firmware 4 implements HSSP on top of HSP. Its HSSP API deliberately
        // has no loop operation, and the legacy v2 /hssp/loop compatibility
        // endpoint returns an unspecified error. HSSP remains non-looping.
        if (parseVersionPart(firmwareVersion, 0) >= 4) {
            Log.d(TAG, "Firmware 4 HSSP: legacy loop endpoint not applicable");
            return;
        }

        Exception lastError = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                JSONObject loopBody = new JSONObject();
                loopBody.put("activated", false);
                JSONObject response = apiRequest("PUT", "/hssp/loop", loopBody, false);
                if (!response.has("result") || response.optInt("result", -1) == 0) {
                    return;
                }
                lastError = new HandyException("result=" + response.optInt("result", -1));
            } catch (Exception e) {
                lastError = e;
            }
            if (attempt < 2) {
                try {
                    Thread.sleep(250L * (attempt + 1));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        // Firmware 4 has been observed returning an error after applying the
        // update. Verify the actual state before rejecting an otherwise valid
        // setup, but never start playback when looping may still be enabled.
        try {
            JSONObject state = apiRequest("GET", "/hssp/loop", null, false);
            boolean resultOk = !state.has("result") || state.optInt("result", -1) == 0;
            if (resultOk && !state.optBoolean("activated", true)) {
                Log.w(TAG, "HSSP loop update returned an error but loop is disabled");
                return;
            }
            if (state.optBoolean("activated", true)) {
                lastError = new HandyException("The Handy vẫn còn bật lặp HSSP");
            }
        } catch (Exception verifyError) {
            if (lastError == null) lastError = verifyError;
        }

        throw new HandyException("Không thể tắt lặp HSSP an toàn: "
            + (lastError != null ? lastError.getMessage() : "unknown"));
    }

    public void play(long videoPositionMs, HandyCallback callback) {
        if (destroyed) return;
        desiredPlaying = true;
        lastRequestedPositionMs = Math.max(0, videoPositionMs);
        final long generation = playbackGeneration.incrementAndGet();
        commandExecutor.execute(() -> {
            try {
                if (generation != playbackGeneration.get()) return;
                ensureConnectedInternal();
                long currentScriptGeneration = scriptGeneration.get();
                String currentScriptUrl = lastScriptUrl;
                if (currentScriptGeneration != scriptGeneration.get()) return;
                if (!scriptReady && currentScriptUrl != null) {
                    if (!setupScriptInternal(currentScriptUrl, currentScriptGeneration)) return;
                }
                if (!scriptReady) {
                    throw new HandyException("Chưa có funscript cho video này");
                }
                if (SystemClock.elapsedRealtime() - lastTimeSyncElapsed > RESYNC_INTERVAL_MS) {
                    syncClientServerTime();
                    syncHandyServerTimeBestEffort();
                }
                if (playInternal(videoPositionMs, generation)) {
                    postSuccess(callback, "The Handy đang phát đồng bộ");
                }
            } catch (Exception e) {
                if (generation == playbackGeneration.get()) {
                    postError(callback, friendlyError(e));
                }
            }
        });
    }

    private boolean playInternal(long videoPositionMs, long generation) throws Exception {
        if (generation != playbackGeneration.get()) return false;
        if (currentMode != HSSP_MODE) {
            JSONObject modeBody = new JSONObject();
            modeBody.put("mode", HSSP_MODE);
            JSONObject modeResponse = apiRequest("PUT", "/mode", modeBody, false);
            int result = modeResponse.optInt("result", -1);
            if (result != 0 && result != 1) {
                throw new HandyException("Không thể bật chế độ đồng bộ HSSP");
            }
            currentMode = HSSP_MODE;
        }

        JSONObject body = new JSONObject();
        body.put("estimatedServerTime", System.currentTimeMillis() + serverTimeOffset);
        body.put("startTime", Math.max(0, videoPositionMs));
        JSONObject response = apiRequest("PUT", "/hssp/play", body, true);
        if (response.optInt("result", -1) != 0) {
            throw new HandyException("The Handy không bắt đầu được script");
        }
        if (generation != playbackGeneration.get()) {
            stopInternalBestEffort();
            return false;
        }
        playing = true;
        Log.d(TAG, "HSSP play accepted at videoTime=" + Math.max(0, videoPositionMs));
        return true;
    }

    public void seekSync(long newVideoPositionMs) {
        play(newVideoPositionMs, new HandyCallback() {
            @Override public void onSuccess(String message) {
                Log.d(TAG, "Seek sync complete");
            }

            @Override public void onError(String error) {
                Log.w(TAG, "Seek sync failed: " + error);
            }
        });
    }

    /** Stops motion while preserving the device connection and loaded script. */
    public void stopPlayback(HandyCallback callback) {
        if (destroyed) return;
        final long stopGeneration = playbackGeneration.incrementAndGet();
        desiredPlaying = false;
        playing = false;
        Call playbackCall = activePlaybackCall;
        if (playbackCall != null) playbackCall.cancel();

        emergencyExecutor.execute(() -> {
            try {
                stopInternal();
                postSuccess(callback, "Đã dừng The Handy");
            } catch (Exception e) {
                postError(callback, friendlyError(e));
            } finally {
                recoverFromStaleStop(stopGeneration);
            }
        });
    }

    private void recoverFromStaleStop(long stopGeneration) {
        if (destroyed || stopGeneration == playbackGeneration.get()
                || !desiredPlaying || !scriptReady) return;
        final long recoveryGeneration = playbackGeneration.get();
        commandExecutor.execute(() -> {
            try {
                if (recoveryGeneration == playbackGeneration.get()
                        && desiredPlaying && scriptReady) {
                    playInternal(lastRequestedPositionMs, recoveryGeneration);
                }
            } catch (Exception e) {
                Log.w(TAG, "Could not recover after stale stop: " + e.getMessage());
            }
        });
    }

    /** Compatibility alias for older activity code. Does not disconnect anymore. */
    public void stop(HandyCallback callback) {
        stopPlayback(callback);
    }

    private void stopInternal() throws Exception {
        if (!connected || (!scriptReady && currentMode != HSSP_MODE)) return;
        JSONObject response = apiRequest("PUT", "/hssp/stop", null, false);
        if (response.has("result") && response.optInt("result", -1) != 0) {
            throw new HandyException("The Handy không xác nhận lệnh dừng");
        }
        playing = false;
        Log.d(TAG, "HSSP stop accepted");
    }

    private void stopInternalBestEffort() {
        try {
            stopInternal();
        } catch (Exception e) {
            Log.w(TAG, "Fail-safe stop failed: " + e.getMessage());
        }
    }

    public void resetScript() {
        scriptGeneration.incrementAndGet();
        playbackGeneration.incrementAndGet();
        desiredPlaying = false;
        playing = false;
        scriptReady = false;
        lastScriptUrl = null;
        stopPlayback(null);
    }

    public void disconnect(HandyCallback callback) {
        if (destroyed) return;
        scriptGeneration.incrementAndGet();
        autoReconnectEnabled = false;
        playbackGeneration.incrementAndGet();
        desiredPlaying = false;
        Call playbackCall = activePlaybackCall;
        if (playbackCall != null) playbackCall.cancel();
        emergencyExecutor.execute(() -> {
            Exception stopError = null;
            try {
                stopInternal();
            } catch (Exception e) {
                stopError = e;
            } finally {
                connected = false;
                scriptReady = false;
                playing = false;
                currentMode = -1;
                lastScriptUrl = null;
            }
            if (stopError == null) postSuccess(callback, "Đã ngắt The Handy");
            else postError(callback, friendlyError(stopError));
        });
    }

    public void healthCheck(long videoPositionMs, boolean videoPlaying) {
        if (destroyed || !autoReconnectEnabled || connectionKey.isEmpty()) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastHealthCheckElapsed < HEALTH_CHECK_INTERVAL_MS) return;
        lastHealthCheckElapsed = now;

        commandExecutor.execute(() -> {
            try {
                JSONObject onlineResponse = apiRequest("GET", "/connected", null, false);
                if (!onlineResponse.optBoolean("connected", false)) {
                    connected = false;
                    scriptReady = false;
                    playing = false;
                    return;
                }

                JSONObject info = apiRequest("GET", "/info", null, false);
                String onlineSession = info.optString("sessionId", "");
                boolean needsReconnect = !connected
                    || (!sessionId.isEmpty() && !sessionId.equals(onlineSession));
                if (needsReconnect) {
                    long currentScriptGeneration = scriptGeneration.get();
                    String scriptUrl = lastScriptUrl;
                    if (currentScriptGeneration != scriptGeneration.get()) return;
                    connectInternal();
                    if (scriptUrl != null) {
                        setupScriptInternal(scriptUrl, currentScriptGeneration);
                    }
                }

                if (videoPlaying && scriptReady && !playing) {
                    long generation = playbackGeneration.incrementAndGet();
                    playInternal(videoPositionMs, generation);
                } else if (!videoPlaying && playing) {
                    stopInternalBestEffort();
                }
            } catch (Exception e) {
                Log.w(TAG, "Health check failed: " + e.getMessage());
            }
        });
    }

    public void getStatus(HandyCallback callback) {
        if (destroyed) return;
        commandExecutor.execute(() -> {
            try {
                JSONObject onlineResponse = apiRequest("GET", "/connected", null, false);
                boolean online = onlineResponse.optBoolean("connected", false);
                if (!online) {
                    connected = false;
                    playing = false;
                    postError(callback, "The Handy hiện không Online");
                    return;
                }
                JSONObject info = apiRequest("GET", "/info", null, false);
                JSONObject status = apiRequest("GET", "/status", null, false);
                firmwareVersion = info.optString("fwVersion", firmwareVersion);
                firmwareStatus = info.optInt("fwStatus", firmwareStatus);
                model = info.optString("model", model);
                currentMode = status.optInt("mode", currentMode);
                connected = true;
                String state = status.has("state")
                    ? String.valueOf(status.optInt("state", -1))
                    : "?";
                postSuccess(callback, "Model: " + model
                    + "\nFirmware: " + firmwareVersion
                    + "\nFirmware status: " + firmwareStatusText(firmwareStatus)
                    + "\nOnline: Có"
                    + "\nMode: " + modeText(currentMode)
                    + "\nState: " + state
                    + "\nScript: " + (scriptReady ? "Sẵn sàng" : "Chưa có")
                    + "\nĐộ trễ trung bình: " + averageRtt + " ms");
            } catch (Exception e) {
                postError(callback, friendlyError(e));
            }
        });
    }

    public void destroy() {
        if (destroyed) return;
        scriptGeneration.incrementAndGet();
        stopPlayback(null);
        destroyed = true;
        mainHandler.removeCallbacksAndMessages(null);
        commandExecutor.shutdownNow();
        emergencyExecutor.shutdown();
    }

    private void ensureConnectedInternal() throws Exception {
        if (!connected) connectInternal();
    }

    private byte[] downloadScript(String sourceUrl) throws Exception {
        Request request = new Request.Builder()
            .url(sourceUrl)
            .header("Accept", "application/json,text/plain,*/*")
            .header("User-Agent", "VLCPlayer-Android/Handy")
            .get()
            .build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new HandyException("Không tải được funscript, HTTP " + response.code());
            }
            return readLimited(response.body().byteStream(), MAX_SOURCE_BYTES);
        }
    }

    private String uploadCsv(byte[] csv) throws Exception {
        RequestBody fileBody = RequestBody.create(csv, CSV_MEDIA_TYPE);
        MultipartBody multipart = new MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", UUID.randomUUID() + ".csv", fileBody)
            .build();
        Request request = new Request.Builder()
            .url(SCRIPT_UPLOAD_URL)
            .header("Accept", "application/json")
            .post(multipart)
            .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new HandyException("Dịch vụ script Handy trả HTTP " + response.code());
            }
            JSONObject json = new JSONObject(body);
            if (json.has("error")) throw new HandyException(extractApiError(json));
            String url = json.optString("url", "");
            if (!isHttpUrl(url)) {
                throw new HandyException("Dịch vụ Handy không trả về URL script hợp lệ");
            }
            return url;
        }
    }

    private byte[] convertToCsv(byte[] sourceBytes) throws Exception {
        String source = new String(sourceBytes, StandardCharsets.UTF_8).trim();
        if (source.isEmpty()) throw new HandyException("Funscript rỗng");

        List<ScriptPoint> points = source.startsWith("{")
            ? parseFunscript(source)
            : parseCsv(source);
        if (points.size() < 2) {
            throw new HandyException("Funscript cần ít nhất 2 điểm chuyển động");
        }
        Collections.sort(points, (left, right) -> Long.compare(left.at, right.at));

        StringBuilder csv = new StringBuilder("#Created by VLCPlayer Android\n");
        for (ScriptPoint point : points) {
            csv.append(point.at).append(',').append(point.pos).append('\n');
        }
        byte[] output = csv.toString().getBytes(StandardCharsets.UTF_8);
        if (output.length > MAX_CSV_BYTES) {
            throw new HandyException("Script vượt giới hạn 512 KB của The Handy");
        }
        return output;
    }

    private List<ScriptPoint> parseFunscript(String json) throws Exception {
        JSONObject root = new JSONObject(json);
        JSONArray actions = root.optJSONArray("actions");
        if (actions == null) throw new HandyException("File không có danh sách actions");
        List<ScriptPoint> points = new ArrayList<>();
        for (int i = 0; i < actions.length(); i++) {
            JSONObject action = actions.optJSONObject(i);
            if (action == null) continue;
            addValidatedPoint(points, action.optLong("at", -1), action.optInt("pos", -1));
        }
        return points;
    }

    private List<ScriptPoint> parseCsv(String csv) throws Exception {
        List<ScriptPoint> points = new ArrayList<>();
        String[] lines = csv.split("\\r?\\n");
        for (String line : lines) {
            String value = line.trim();
            if (value.isEmpty() || value.startsWith("#")) continue;
            String[] columns = value.split(",");
            if (columns.length != 2) {
                throw new HandyException("CSV không đúng định dạng thời_gian,vị_trí");
            }
            try {
                long at = Long.parseLong(columns[0].trim());
                int pos = Integer.parseInt(columns[1].trim());
                addValidatedPoint(points, at, pos);
            } catch (NumberFormatException e) {
                throw new HandyException("CSV chứa giá trị không hợp lệ");
            }
        }
        return points;
    }

    private void addValidatedPoint(List<ScriptPoint> points, long at, int pos)
            throws HandyException {
        if (at < 0 || pos < 0 || pos > 100) {
            throw new HandyException("Funscript có thời gian hoặc vị trí ngoài giới hạn");
        }
        points.add(new ScriptPoint(at, pos));
    }

    private JSONObject apiRequest(String method, String endpoint, JSONObject jsonBody,
                                  boolean playbackRequest) throws Exception {
        Exception lastError = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            RequestBody requestBody = null;
            if ("PUT".equals(method)) {
                requestBody = jsonBody == null
                    ? RequestBody.create(new byte[0], JSON_MEDIA_TYPE)
                    : RequestBody.create(jsonBody.toString(), JSON_MEDIA_TYPE);
            }
            Request request = new Request.Builder()
                .url(BASE_URL + endpoint)
                .header("X-Connection-Key", connectionKey)
                .header("Accept", "application/json")
                .method(method, requestBody)
                .build();
            Call call = httpClient.newCall(request);
            if (playbackRequest) activePlaybackCall = call;
            try (Response response = call.execute()) {
                ResponseBody responseBody = response.body();
                String text = responseBody != null ? responseBody.string() : "";
                JSONObject json = text.isEmpty() ? new JSONObject() : new JSONObject(text);
                if (!response.isSuccessful()) {
                    String message = extractApiError(json);
                    if ((response.code() == 502 || response.code() == 504) && attempt == 0) {
                        lastError = new HandyException(message);
                        sleepBeforeRetry();
                        continue;
                    }
                    throw new HandyException(message.isEmpty()
                        ? "Handy API trả HTTP " + response.code() : message);
                }
                if (json.has("error")) throw new HandyException(extractApiError(json));
                return json;
            } catch (IOException e) {
                lastError = e;
                if (call.isCanceled() || attempt > 0) throw e;
                sleepBeforeRetry();
            } finally {
                if (playbackRequest && activePlaybackCall == call) activePlaybackCall = null;
            }
        }
        throw lastError != null ? lastError : new HandyException("Không gọi được Handy API");
    }

    private static void sleepBeforeRetry() throws InterruptedException {
        Thread.sleep(200);
    }

    private static byte[] readLimited(InputStream input, int maxBytes) throws Exception {
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = stream.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) throw new HandyException("File funscript quá lớn");
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static boolean isHttpUrl(String value) {
        if (value == null) return false;
        String lower = value.trim().toLowerCase(Locale.US);
        return lower.startsWith("https://") || lower.startsWith("http://");
    }

    private static int parseVersionPart(String version, int index) {
        if (version == null) return -1;
        String[] parts = version.split("\\.");
        if (index >= parts.length) return -1;
        String digits = parts[index].replaceAll("[^0-9].*$", "");
        try {
            return digits.isEmpty() ? -1 : Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String extractApiError(JSONObject response) {
        Object error = response.opt("error");
        if (error instanceof JSONObject) {
            JSONObject object = (JSONObject) error;
            String message = object.optString("message", "");
            String name = object.optString("name", "");
            int code = object.optInt("code", -1);
            if (!message.isEmpty()) return message + (code >= 0 ? " (" + code + ")" : "");
            if (!name.isEmpty()) return name + (code >= 0 ? " (" + code + ")" : "");
        }
        if (error != null && error != JSONObject.NULL) return String.valueOf(error);
        return "Lỗi không xác định từ Handy API";
    }

    private static String firmwareStatusText(int status) {
        if (status == 0) return "Mới nhất";
        if (status == 1) return "Cần cập nhật";
        if (status == 2) return "Có bản cập nhật";
        return "Không xác định";
    }

    private static String modeText(int mode) {
        if (mode == 0) return "HAMP";
        if (mode == 1) return "HSSP";
        if (mode == 2) return "HDSP";
        if (mode == 3) return "MAINTENANCE";
        return "Không xác định";
    }

    private static String friendlyError(Exception error) {
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) return "Không thể giao tiếp với The Handy";
        if (error instanceof IOException) return "Mạng tới The Handy không ổn định: " + message;
        return message;
    }

    private void postSuccess(HandyCallback callback, String message) {
        Log.d(TAG, message);
        if (callback != null && !destroyed) mainHandler.post(() -> callback.onSuccess(message));
    }

    private void postError(HandyCallback callback, String message) {
        Log.w(TAG, message);
        if (callback != null && !destroyed) mainHandler.post(() -> callback.onError(message));
    }
}
