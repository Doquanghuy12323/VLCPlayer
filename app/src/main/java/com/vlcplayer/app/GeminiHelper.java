package com.vlcplayer.app;

import android.os.Handler;
import android.os.Looper;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class GeminiHelper {

    private static final String API_URL =
        "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=";
    private final String apiKey;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());

    public interface Callback {
        void onResult(String result);
        void onError(String error);
    }

    public GeminiHelper() {
        this.apiKey = BuildConfig.GEMINI_API_KEY;
    }

    public void ask(String prompt, Callback callback) {
        executor.execute(() -> {
            try {
                URL url = new URL(API_URL + apiKey);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(30000);

                String body = "{\"contents\":[{\"parts\":[{\"text\":\""
                    + prompt.replace("\"", "\\\"").replace("\n", "\\n")
                    + "\"}]}],\"generationConfig\":{\"temperature\":0.7,\"maxOutputTokens\":1024}}";

                OutputStream os = conn.getOutputStream();
                os.write(body.getBytes("UTF-8"));
                os.close();

                int code = conn.getResponseCode();
                BufferedReader br = new BufferedReader(new InputStreamReader(
                    code == 200 ? conn.getInputStream() : conn.getErrorStream(), "UTF-8"));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();

                String response = sb.toString();
                // Parse text from JSON
                // Kiem tra loi
                if (code == 429) {
                    handler.post(() -> callback.onError(
                        "Vượt giới hạn API miễn phí.\nVui lòng thử lại sau ít phút,\nhoặc lấy API key mới tại aistudio.google.com"));
                    return;
                }
                if (code != 200) {
                    handler.post(() -> callback.onError(
                        "Lỗi API (" + code + "). Vui lòng thử lại sau."));
                    return;
                }
                String result = parseText(response);
                handler.post(() -> callback.onResult(result));

            } catch (Exception e) {
                handler.post(() -> callback.onError(e.getMessage()));
            }
        });
    }

    public void translateSubtitle(String text, String targetLang, Callback callback) {
        String prompt = "Dich phu de sau sang " + targetLang +
            ". Chi tra ve ban dich, khong giai thich:\n" + text;
        ask(prompt, callback);
    }

    public void searchVideoSuggestions(String query, Callback callback) {
        String prompt = "De xuat 5 tu khoa tim kiem video tren YouTube lien quan den: " + query +
            ". Format: moi tu khoa tren 1 dong, khong danh so, khong giai thich.";
        ask(prompt, callback);
    }

    private String parseText(String json) {
        try {
            int idx = json.indexOf("\"text\":");
            if (idx < 0) return "Loi: " + json;
            int start = json.indexOf("\"", idx + 7) + 1;
            int end = json.indexOf("\"", start);
            // Handle escaped quotes
            while (end > 0 && json.charAt(end - 1) == '\\') {
                end = json.indexOf("\"", end + 1);
            }
            return json.substring(start, end)
                .replace("\\n", "\n")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\");
        } catch (Exception e) {
            return json;
        }
    }
}
