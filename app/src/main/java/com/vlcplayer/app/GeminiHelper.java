package com.vlcplayer.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class GeminiHelper {

    private static final String API_URL =
        "https://generativelanguage.googleapis.com/v1beta/models/"
            + "gemini-3.5-flash:generateContent";
    private static final String PREFS = "gemini_prefs";
    private static final String KEY_API = "api_key";

    private final Context context;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());

    public interface Callback {
        void onResult(String result);
        void onError(String error);
    }

    public GeminiHelper(Context context) {
        this.context = context.getApplicationContext();
    }

    public static void saveApiKey(Context context, String apiKey) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_API, apiKey == null ? "" : apiKey.trim()).apply();
    }

    public static String getApiKey(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_API, "").trim();
    }

    public void ask(String prompt, Callback callback) {
        final String apiKey = getApiKey(context);
        if (apiKey.isEmpty()) {
            handler.post(() -> callback.onError("Chua nhap Gemini API key"));
            return;
        }

        executor.execute(() -> {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(API_URL).openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                conn.setRequestProperty("x-goog-api-key", apiKey);
                conn.setDoOutput(true);
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(45000);

                JSONObject part = new JSONObject().put("text", prompt);
                JSONObject content = new JSONObject()
                    .put("parts", new JSONArray().put(part));
                JSONObject generation = new JSONObject()
                    .put("temperature", 0.7)
                    .put("maxOutputTokens", 1024);
                JSONObject body = new JSONObject()
                    .put("contents", new JSONArray().put(content))
                    .put("generationConfig", generation);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }

                int code = conn.getResponseCode();
                InputStream responseStream = code >= 200 && code < 300
                    ? conn.getInputStream() : conn.getErrorStream();
                String response = readText(responseStream);

                if (code == 429) {
                    postError(callback, "Vuot gioi han API. Vui long thu lai sau.");
                } else if (code < 200 || code >= 300) {
                    postError(callback, parseApiError(response, code));
                } else {
                    String result = parseText(response);
                    handler.post(() -> callback.onResult(result));
                }
            } catch (Exception e) {
                postError(callback, e.getMessage() != null ? e.getMessage() : "Loi ket noi Gemini");
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }

    public void translateSubtitle(String text, String targetLang, Callback callback) {
        ask("Dich phu de sau sang " + targetLang
            + ". Chi tra ve ban dich, khong giai thich:\n" + text, callback);
    }

    public void searchVideoSuggestions(String query, Callback callback) {
        ask("De xuat 5 tu khoa tim kiem video lien quan den: " + query
            + ". Moi tu khoa mot dong, khong danh so.", callback);
    }

    private String parseText(String json) throws Exception {
        JSONObject root = new JSONObject(json);
        JSONArray candidates = root.optJSONArray("candidates");
        if (candidates == null || candidates.length() == 0) {
            throw new IllegalStateException("Gemini khong tra ve noi dung");
        }
        JSONArray parts = candidates.getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts");
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < parts.length(); i++) {
            String text = parts.getJSONObject(i).optString("text", "");
            if (!text.isEmpty()) result.append(text);
        }
        if (result.length() == 0) throw new IllegalStateException("Phan hoi Gemini trong");
        return result.toString();
    }

    private String parseApiError(String json, int code) {
        try {
            return "Gemini " + code + ": "
                + new JSONObject(json).getJSONObject("error").optString("message", "Loi API");
        } catch (Exception ignored) {
            return "Loi Gemini API (HTTP " + code + ")";
        }
    }

    private String readText(InputStream input) throws Exception {
        if (input == null) return "";
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            StringBuilder result = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) result.append(line);
            return result.toString();
        }
    }

    private void postError(Callback callback, String error) {
        handler.post(() -> callback.onError(error));
    }

    public void destroy() {
        executor.shutdownNow();
        handler.removeCallbacksAndMessages(null);
    }
}
