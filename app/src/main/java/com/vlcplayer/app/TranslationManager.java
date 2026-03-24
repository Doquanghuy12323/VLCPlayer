package com.vlcplayer.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class TranslationManager {

    private static final String PREF = "translation_prefs";
    private static final String KEY_LANG = "target_language";
    private static final String API_URL = "https://api.mymemory.translated.net/get";

    private final Context ctx;
    private final ExecutorService executor = Executors.newFixedThreadPool(3);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // Danh sách ngôn ngữ hỗ trợ
    public static final String[][] LANGUAGES = {
        {"Tiếng Việt",      "vi"},
        {"English",          "en"},
        {"中文 (Chinese)",   "zh"},
        {"日本語 (Japanese)","ja"},
        {"한국어 (Korean)",  "ko"},
        {"Français",         "fr"},
        {"Español",          "es"},
        {"Deutsch",          "de"},
        {"Português",        "pt"},
        {"Русский",          "ru"},
        {"العربية",          "ar"},
        {"हिन्दी",           "hi"},
        {"Italiano",         "it"},
        {"ภาษาไทย",         "th"},
        {"Bahasa Indonesia", "id"},
    };

    public interface TranslateCallback {
        void onSuccess(String translated);
        void onError(String error);
    }

    public TranslationManager(Context ctx) {
        this.ctx = ctx;
    }

    public String getTargetLanguage() {
        return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY_LANG, "vi");
    }

    public String getTargetLanguageName() {
        String code = getTargetLanguage();
        for (String[] lang : LANGUAGES) {
            if (lang[1].equals(code)) return lang[0];
        }
        return code;
    }

    public void setTargetLanguage(String langCode) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(KEY_LANG, langCode).apply();
    }

    public void translate(String text, String sourceLang, TranslateCallback callback) {
        if (text == null || text.trim().isEmpty()) {
            callback.onError("Văn bản trống");
            return;
        }
        String targetLang = getTargetLanguage();
        if (sourceLang.equals(targetLang)) {
            callback.onSuccess(text);
            return;
        }

        executor.execute(() -> {
            try {
                String encoded = URLEncoder.encode(text, "UTF-8");
                String langPair = sourceLang + "|" + targetLang;
                String urlStr = API_URL + "?q=" + encoded + "&langpair=" + langPair;

                HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);

                BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();

                JSONObject json = new JSONObject(sb.toString());
                int status = json.getInt("responseStatus");
                if (status == 200) {
                    String translated = json.getJSONObject("responseData")
                        .getString("translatedText");
                    mainHandler.post(() -> callback.onSuccess(translated));
                } else {
                    mainHandler.post(() -> callback.onError("Lỗi dịch: " + status));
                }
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError("Lỗi: " + e.getMessage()));
            }
        });
    }

    // Dịch file SRT
    public void translateSrt(String srtContent, String sourceLang,
            TranslateCallback progressCallback, TranslateCallback doneCallback) {
        executor.execute(() -> {
            try {
                String[] blocks = srtContent.split("\n\n");
                StringBuilder result = new StringBuilder();
                int total = blocks.length;

                for (int i = 0; i < blocks.length; i++) {
                    String block = blocks[i].trim();
                    if (block.isEmpty()) continue;

                    String[] lines = block.split("\n");
                    if (lines.length < 3) {
                        result.append(block).append("\n\n");
                        continue;
                    }

                    // Giữ nguyên số thứ tự và timestamp
                    String index     = lines[0];
                    String timestamp = lines[1];

                    // Ghép các dòng text
                    StringBuilder textBuilder = new StringBuilder();
                    for (int j = 2; j < lines.length; j++) {
                        if (j > 2) textBuilder.append(" ");
                        // Loại bỏ thẻ HTML như <i>, <b>
                        textBuilder.append(lines[j]
                            .replaceAll("<[^>]+>", "")
                            .trim());
                    }
                    String text = textBuilder.toString().trim();

                    // Dịch text
                    String encoded = URLEncoder.encode(text, "UTF-8");
                    String langPair = URLEncoder.encode(sourceLang + "|" + getTargetLanguage(), "UTF-8");
                    String urlStr = API_URL + "?q=" + encoded + "&langpair=" + langPair;

                    HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
                    conn.setConnectTimeout(8000);
                    conn.setReadTimeout(8000);

                    BufferedReader br = new BufferedReader(
                        new InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                    br.close();

                    JSONObject json = new JSONObject(sb.toString());
                    String translated = text;
                    if (json.getInt("responseStatus") == 200) {
                        translated = json.getJSONObject("responseData")
                            .getString("translatedText");
                    }

                    result.append(index).append("\n")
                          .append(timestamp).append("\n")
                          .append(translated).append("\n\n");

                    // Thông báo tiến độ
                    final int progress = (int)((i + 1) * 100.0 / total);
                    mainHandler.post(() ->
                        progressCallback.onSuccess("Đang dịch: " + progress + "%"));

                    // Tránh rate limit
                    Thread.sleep(200);
                }

                final String finalSrt = result.toString();
                mainHandler.post(() -> doneCallback.onSuccess(finalSrt));

            } catch (Exception e) {
                mainHandler.post(() -> doneCallback.onError("Lỗi dịch SRT: " + e.getMessage()));
            }
        });
    }
}
