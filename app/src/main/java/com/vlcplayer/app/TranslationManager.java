package com.vlcplayer.app;

import android.content.Context;
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

    public static final String[][] LANGUAGES = {
        {"Tieng Viet",       "vi"},
        {"English",          "en"},
        {"Chinese",          "zh"},
        {"Japanese",         "ja"},
        {"Korean",           "ko"},
        {"Francais",         "fr"},
        {"Espanol",          "es"},
        {"Deutsch",          "de"},
        {"Portugues",        "pt"},
        {"Russian",          "ru"},
        {"Arabic",           "ar"},
        {"Hindi",            "hi"},
        {"Italiano",         "it"},
        {"Thai",             "th"},
        {"Indonesian",       "id"},
    };

    public interface TranslateCallback {
        void onSuccess(String result);
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
            callback.onError("Van ban trong");
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
                BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();
                JSONObject json = new JSONObject(sb.toString());
                if (json.getInt("responseStatus") == 200) {
                    String translated = json.getJSONObject("responseData").getString("translatedText");
                    mainHandler.post(() -> callback.onSuccess(translated));
                } else {
                    mainHandler.post(() -> callback.onError("Loi dich: " + json.getInt("responseStatus")));
                }
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError("Loi: " + e.getMessage()));
            }
        });
    }

    public void translateSrt(String srtContent, String sourceLang,
            ProgressCallback progressCallback, TranslateCallback doneCallback) {
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
                    String index = lines[0];
                    String timestamp = lines[1];
                    StringBuilder textBuilder = new StringBuilder();
                    for (int j = 2; j < lines.length; j++) {
                        if (j > 2) textBuilder.append(" ");
                        textBuilder.append(lines[j].replaceAll("<[^>]+>", "").trim());
                    }
                    String text = textBuilder.toString().trim();
                    String encoded = URLEncoder.encode(text, "UTF-8");
                    String langPair = sourceLang + "|" + getTargetLanguage();
                    String urlStr = API_URL + "?q=" + encoded + "&langpair=" + langPair;
                    HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
                    conn.setConnectTimeout(8000);
                    conn.setReadTimeout(8000);
                    BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                    br.close();
                    JSONObject json = new JSONObject(sb.toString());
                    String translated = text;
                    if (json.getInt("responseStatus") == 200) {
                        translated = json.getJSONObject("responseData").getString("translatedText");
                    }
                    result.append(index).append("\n")
                          .append(timestamp).append("\n")
                          .append(translated).append("\n\n");
                    final int progress = (int)((i + 1) * 100.0 / total);
                    mainHandler.post(() -> progressCallback.onProgress("Dang dich: " + progress + "%"));
                    Thread.sleep(200);
                }
                final String finalSrt = result.toString();
                mainHandler.post(() -> doneCallback.onSuccess(finalSrt));
            } catch (Exception e) {
                mainHandler.post(() -> doneCallback.onError("Loi dich SRT: " + e.getMessage()));
            }
        });
    }
}
