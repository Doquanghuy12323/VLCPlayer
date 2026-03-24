package com.vlcplayer.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

public class TranslationManager {

    public static final String[][] LANGUAGES = {
        {"Tiếng Việt", "vi"},
        {"English", "en"},
        {"Tiếng Trung", "zh-CN"},
        {"Tiếng Nhật", "ja"},
        {"Tiếng Hàn", "ko"},
        {"Tiếng Pháp", "fr"},
        {"Tiếng Tây Ban Nha", "es"},
        {"Tiếng Đức", "de"},
        {"Tiếng Thái", "th"},
    };

    // Functional interface - chỉ 1 method → dùng được lambda
    public interface ProgressCallback {
        void onProgress(String message);
    }

    // 2 methods → KHÔNG dùng lambda, phải dùng anonymous class
    public interface TranslateCallback {
        void onSuccess(String translated);
        void onError(String error);
    }

    private static final String PREF = "translation";
    private static final String KEY_LANG = "target_lang";
    private final Context ctx;

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
        return "Tiếng Việt";
    }

    public void setTargetLanguage(String code) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(KEY_LANG, code).apply();
    }

    // Dịch file SRT: tách từng block, dịch text, ghép lại
    public void translateSrt(String srt, String sourceLang,
            ProgressCallback progress, TranslateCallback callback) {

        new Thread(() -> {
            try {
                String targetLang = getTargetLanguage();
                String[] blocks = srt.split("\n\n");
                StringBuilder result = new StringBuilder();
                int total = blocks.length;

                for (int i = 0; i < total; i++) {
                    String block = blocks[i].trim();
                    if (block.isEmpty()) continue;

                    String[] lines = block.split("\n");
                    if (lines.length < 3) {
                        result.append(block).append("\n\n");
                        continue;
                    }

                    // lines[0] = số thứ tự, lines[1] = timestamp, lines[2+] = text
                    StringBuilder textToTranslate = new StringBuilder();
                    for (int j = 2; j < lines.length; j++) {
                        if (j > 2) textToTranslate.append(" ");
                        textToTranslate.append(lines[j]);
                    }

                    String translated = translateText(
                        textToTranslate.toString(), sourceLang, targetLang);

                    result.append(lines[0]).append("\n");
                    result.append(lines[1]).append("\n");
                    result.append(translated).append("\n\n");

                    if (progress != null) {
                        final int pct = (int)((i + 1) * 100.0 / total);
                        progress.onProgress("Đang dịch... " + pct + "% (" + (i+1) + "/" + total + ")");
                    }
                }

                callback.onSuccess(result.toString());

            } catch (Exception e) {
                callback.onError("Lỗi dịch: " + e.getMessage());
            }
        }).start();
    }

    // Dịch một đoạn text qua MyMemory API (miễn phí, không cần key)
    private String translateText(String text, String from, String to) throws Exception {
        if (text == null || text.trim().isEmpty()) return text;
        String encoded = URLEncoder.encode(text, "UTF-8");
        String langPair = (from.equals("auto") ? "en" : from) + "|" + to;
        String urlStr = "https://api.mymemory.translated.net/get?q="
            + encoded + "&langpair=" + langPair;

        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(8000);

        BufferedReader br = new BufferedReader(
            new InputStreamReader(conn.getInputStream(), "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();

        // Parse JSON thô (không cần thư viện)
        String json = sb.toString();
        int idx = json.indexOf("\"translatedText\":\"");
        if (idx >= 0) {
            int start = idx + 18;
            int end = json.indexOf("\"", start);
            if (end > start) {
                return json.substring(start, end)
                    .replace("\\u0027", "'")
                    .replace("\\u003c", "<")
                    .replace("\\u003e", ">")
                    .replace("\\\"", "\"");
            }
        }
        return text; // fallback: trả về text gốc nếu không dịch được
    }
}
