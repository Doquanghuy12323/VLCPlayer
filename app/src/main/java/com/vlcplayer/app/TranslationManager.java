package com.vlcplayer.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;

public class TranslationManager {

    private static final String PREF = "translation_prefs";
    private static final String KEY_LANG = "target_language";
    private static final String API_URL = "https://api.mymemory.translated.net/get";
    private static final Pattern EDGE_TAGS = Pattern.compile(
        "^(\\s*(?:<[^>]+>\\s*)*)(.*?)(\\s*(?:</[^>]+>\\s*)*)$");
    private static final Pattern HTML_ENTITY = Pattern.compile(
        "&(#(?:[xX][0-9a-fA-F]+|[0-9]+)|[A-Za-z][A-Za-z0-9]+);");

    private final Context ctx;
    private static final ExecutorService executor = Executors.newFixedThreadPool(3);
    private static final ThreadPoolExecutor srtExecutor =
        (ThreadPoolExecutor) Executors.newFixedThreadPool(2);
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
        this.ctx = ctx.getApplicationContext();
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
                if (json.optInt("responseStatus", 0) == 200) {
                    String translated = json.getJSONObject("responseData").getString("translatedText");
                    mainHandler.post(() -> callback.onSuccess(translated));
                } else {
                    mainHandler.post(() -> callback.onError("Loi dich: " + json.optInt("responseStatus", 0)));
                }
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError("Loi: " + e.getMessage()));
            }
        });
    }

    public static final class SrtTranslationTask {
        private final Object lock = new Object();
        private Future<?> future;
        private HttpURLConnection connection;
        private boolean cancelled;
        private boolean finished;

        public void cancel() {
            Future<?> pending;
            HttpURLConnection active;
            synchronized (lock) {
                cancelled = true;
                pending = future;
                active = connection;
            }
            if (pending != null) pending.cancel(true);
            if (active != null) active.disconnect();
            srtExecutor.purge();
        }

        public boolean isCancelled() {
            synchronized (lock) { return cancelled; }
        }

        void setFuture(Future<?> submitted) {
            synchronized (lock) {
                if (!finished) future = submitted;
                if (cancelled) submitted.cancel(true);
            }
            if (isCancelled()) srtExecutor.purge();
        }

        private void finish() {
            synchronized (lock) {
                finished = true;
                future = null;
                connection = null;
            }
        }

        boolean setConnection(HttpURLConnection active) {
            synchronized (lock) {
                if (cancelled) return false;
                connection = active;
                return true;
            }
        }

        private void clearConnection(HttpURLConnection active) {
            synchronized (lock) {
                if (connection == active) connection = null;
            }
        }

        private void checkCancelled() throws InterruptedException {
            if (isCancelled() || Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Da huy dich phu de");
            }
        }

        void deliver(Runnable callback) {
            synchronized (lock) {
                if (!cancelled) callback.run();
            }
        }
    }

    static final class SrtCue {
        final String index;
        final String timestamp;
        final List<String> textLines;

        SrtCue(String index, String timestamp, List<String> textLines) {
            this.index = index;
            this.timestamp = timestamp;
            this.textLines = textLines;
        }
    }

    static List<SrtCue> parseSrt(String srtContent) throws IOException {
        if (srtContent == null || srtContent.trim().isEmpty()) {
            throw new IOException("Phu de trong");
        }
        String normalized = srtContent.replace("\r\n", "\n").replace('\r', '\n');
        if (normalized.startsWith("\uFEFF")) normalized = normalized.substring(1);
        String[] blocks = normalized.trim().split("\n\\s*\n");
        List<SrtCue> cues = new ArrayList<>(blocks.length);
        int textLineCount = 0;
        for (int i = 0; i < blocks.length; i++) {
            String[] lines = blocks[i].trim().split("\n");
            if (lines.length < 3 || !lines[1].contains("-->")) {
                throw new IOException("Khoi phu de " + (i + 1) + " khong dung dinh dang SRT");
            }
            List<String> textLines = new ArrayList<>();
            for (int j = 2; j < lines.length; j++) {
                String line = lines[j].trim();
                String text = line.replaceAll("<[^>]+>", "").trim();
                if (!text.isEmpty() && text.getBytes(StandardCharsets.UTF_8).length > 500) {
                    throw new IOException("Dong phu de " + (i + 1) + " vuot gioi han 500 byte cua dich vu");
                }
                if (!text.isEmpty()) textLineCount++;
                textLines.add(line);
            }
            cues.add(new SrtCue(lines[0].trim(), lines[1].trim(), textLines));
        }
        if (textLineCount == 0) throw new IOException("Phu de khong co noi dung de dich");
        return cues;
    }

    static String preserveEdgeTags(String original, String translated) {
        Matcher match = EDGE_TAGS.matcher(original);
        if (!match.matches() || match.group(2).contains("<") || match.group(2).contains(">")) {
            return translated;
        }
        return match.group(1) + translated + match.group(3);
    }

    static String decodeHtmlEntities(String value) throws IOException {
        String decoded = value;
        // MyMemory can return a second layer such as &amp;#39;.
        for (int pass = 0; pass < 3; pass++) {
            Matcher matcher = HTML_ENTITY.matcher(decoded);
            if (!matcher.find()) return decoded;
            StringBuffer output = new StringBuffer();
            do {
                String replacement = decodeHtmlEntity(matcher.group(1));
                matcher.appendReplacement(output, Matcher.quoteReplacement(replacement));
            } while (matcher.find());
            matcher.appendTail(output);
            decoded = output.toString();
        }
        return decoded;
    }

    private static String decodeHtmlEntity(String entity) throws IOException {
        if (entity.charAt(0) == '#') {
            boolean hex = entity.length() > 2 && (entity.charAt(1) == 'x' || entity.charAt(1) == 'X');
            try {
                int codePoint = Integer.parseInt(entity.substring(hex ? 2 : 1), hex ? 16 : 10);
                if (!Character.isValidCodePoint(codePoint)
                        || (codePoint >= 0xD800 && codePoint <= 0xDFFF)
                        || (Character.isISOControl(codePoint)
                            && codePoint != '\t' && codePoint != '\n' && codePoint != '\r')) {
                    throw new NumberFormatException("Invalid code point");
                }
                return new String(Character.toChars(codePoint));
            } catch (NumberFormatException e) {
                throw new IOException("Ma ky tu HTML khong hop le: &" + entity + ";", e);
            }
        }
        switch (entity) {
            case "amp": return "&";
            case "lt": return "<";
            case "gt": return ">";
            case "quot": return "\"";
            case "apos": return "'";
            case "nbsp": return "\u00A0";
            case "ndash": return "\u2013";
            case "mdash": return "\u2014";
            case "lsquo": return "\u2018";
            case "rsquo": return "\u2019";
            case "ldquo": return "\u201C";
            case "rdquo": return "\u201D";
            case "hellip": return "\u2026";
            case "bull": return "\u2022";
            case "middot": return "\u00B7";
            case "laquo": return "\u00AB";
            case "raquo": return "\u00BB";
            case "copy": return "\u00A9";
            case "reg": return "\u00AE";
            case "trade": return "\u2122";
            case "euro": return "\u20AC";
            case "aelig": return "\u00E6";
            case "AElig": return "\u00C6";
            case "szlig": return "\u00DF";
            case "eth": return "\u00F0";
            case "ETH": return "\u00D0";
            case "thorn": return "\u00FE";
            case "THORN": return "\u00DE";
            case "oslash": return "\u00F8";
            case "Oslash": return "\u00D8";
            default:
                if (entity.length() > 1) {
                    String accent = entity.substring(1);
                    String mark;
                    switch (accent) {
                        case "grave": mark = "\u0300"; break;
                        case "acute": mark = "\u0301"; break;
                        case "circ": mark = "\u0302"; break;
                        case "tilde": mark = "\u0303"; break;
                        case "uml": mark = "\u0308"; break;
                        case "ring": mark = "\u030A"; break;
                        case "cedil": mark = "\u0327"; break;
                        default: mark = null;
                    }
                    if (mark != null && Character.isLetter(entity.charAt(0))) {
                        return Normalizer.normalize(entity.substring(0, 1) + mark,
                            Normalizer.Form.NFC);
                    }
                }
                throw new IOException("Ma HTML khong ho tro: &" + entity + ";");
        }
    }

    static String checkedSourceLanguage(String sourceLang) throws IOException {
        String source = sourceLang == null ? "" : sourceLang.trim().toLowerCase(Locale.US);
        if (source.equals("auto")) {
            throw new IOException("MyMemory khong ho tro tu nhan dien ngon ngu; hay chon ngon ngu nguon");
        }
        if (!source.matches("[a-z]{2,3}(?:-[a-z0-9]{2,8})?")) {
            throw new IOException("Ma ngon ngu nguon khong hop le");
        }
        return source;
    }

    public SrtTranslationTask translateSrt(String srtContent, String sourceLang,
            ProgressCallback progressCallback, TranslateCallback doneCallback) {
        final SrtTranslationTask task = new SrtTranslationTask();
        final String targetLang = getTargetLanguage();
        Future<?> future = srtExecutor.submit(() -> {
            try {
                task.checkCancelled();
                String source = checkedSourceLanguage(sourceLang);
                if (source.equals(targetLang)) {
                    throw new IOException("Ngon ngu nguon trung ngon ngu dich; hay chon ngon ngu khac");
                }
                List<SrtCue> cues = parseSrt(srtContent);
                StringBuilder result = new StringBuilder();
                for (int i = 0; i < cues.size(); i++) {
                    task.checkCancelled();
                    SrtCue cue = cues.get(i);
                    result.append(cue.index).append('\n').append(cue.timestamp).append('\n');
                    for (String originalLine : cue.textLines) {
                        task.checkCancelled();
                        String text = originalLine.replaceAll("<[^>]+>", "").trim();
                        String translated = text.isEmpty() ? originalLine
                            : preserveEdgeTags(originalLine,
                                translateSrtLine(text, source, targetLang, task));
                        result.append(translated).append('\n');
                        if (!text.isEmpty()) Thread.sleep(200);
                    }
                    result.append('\n');
                    final int progress = (int) ((i + 1) * 100.0 / cues.size());
                    if (progressCallback != null) {
                        mainHandler.post(() -> task.deliver(
                            () -> progressCallback.onProgress(
                                ctx.getString(R.string.player_ai_progress, progress))));
                    }
                }
                final String finalSrt = result.toString();
                mainHandler.post(() -> task.deliver(() -> doneCallback.onSuccess(finalSrt)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                if (!task.isCancelled()) {
                    mainHandler.post(() -> task.deliver(
                        () -> doneCallback.onError("Loi dich SRT: Tac vu bi gian doan")));
                }
            } catch (Exception e) {
                if (!task.isCancelled()) {
                    String detail = e.getMessage() == null ? "Loi khong xac dinh" : e.getMessage();
                    mainHandler.post(() -> task.deliver(
                        () -> doneCallback.onError("Loi dich SRT: " + detail)));
                }
            } finally {
                task.finish();
            }
        });
        task.setFuture(future);
        return task;
    }

    private String translateSrtLine(String text, String source, String target,
                                    SrtTranslationTask task) throws Exception {
        task.checkCancelled();
        String urlStr = API_URL + "?q=" + URLEncoder.encode(text, "UTF-8")
            + "&langpair=" + source + "%7C" + target;
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        if (!task.setConnection(conn)) {
            conn.disconnect();
            throw new InterruptedException("Da huy dich phu de");
        }
        try {
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            int status = conn.getResponseCode();
            task.checkCancelled();
            if (status != HttpURLConnection.HTTP_OK) throw new IOException("HTTP " + status);
            StringBuilder body = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    task.checkCancelled();
                    if (body.length() + line.length() > 2_000_000) {
                        throw new IOException("Phan hoi dich vu qua lon");
                    }
                    body.append(line);
                }
            }
            task.checkCancelled();
            JSONObject json = new JSONObject(body.toString());
            int apiStatus = json.optInt("responseStatus", 0);
            String details = json.optString("responseDetails", "").trim();
            if (apiStatus != 200 || !details.isEmpty() || json.optBoolean("quotaFinished", false)
                    || (json.has("exception_code") && !json.isNull("exception_code"))) {
                String reason = details.isEmpty() ? "responseStatus=" + apiStatus : details;
                if (reason.length() > 200) reason = reason.substring(0, 200);
                throw new IOException("MyMemory: " + reason);
            }
            JSONObject data = json.optJSONObject("responseData");
            Object value = data == null ? null : data.opt("translatedText");
            if (!(value instanceof String) || ((String) value).trim().isEmpty()) {
                throw new IOException("MyMemory tra ve ban dich khong hop le");
            }
            return decodeHtmlEntities((String) value).replace('\r', ' ').replace('\n', ' ');
        } finally {
            task.clearConnection(conn);
            conn.disconnect();
        }
    }
}
