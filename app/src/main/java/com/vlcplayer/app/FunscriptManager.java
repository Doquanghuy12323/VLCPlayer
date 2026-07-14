package com.vlcplayer.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

public class FunscriptManager {
    private static final String TAG = "FunscriptManager";

    public interface FunscriptCallback {
        void onAction(int position, int speed); // position: 0-100, speed: ms to next
        void onFinished();
    }

    public static class FunscriptAction {
        public long at; // timestamp ms
        public int pos; // position 0-100
        public FunscriptAction(long at, int pos) { this.at = at; this.pos = pos; }
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private List<FunscriptAction> actions = new ArrayList<>();
    private boolean isRunning = false;
    private int currentIndex = 0;
    private FunscriptCallback callback;
    private long startTimeMs = 0;
    private long videoOffsetMs = 0;

    // Parse funscript tu file
    public boolean loadFromFile(File file) {
        try {
            StringBuilder sb = new StringBuilder();
            BufferedReader br = new BufferedReader(new FileReader(file));
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            return parseJson(sb.toString());
        } catch (Exception e) {
            Log.e(TAG, "Load error: " + e.getMessage());
            return false;
        }
    }

    // Parse funscript tu URL
    public void loadFromUrl(String url, Runnable onSuccess, Runnable onError) {
        new Thread(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(10000);
                BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();
                boolean ok = parseJson(sb.toString());
                handler.post(ok ? onSuccess : onError);
            } catch (Exception e) {
                Log.e(TAG, "URL load error: " + e.getMessage());
                handler.post(onError);
            }
        }).start();
    }

    private boolean parseJson(String json) {
        try {
            actions.clear();
            JSONObject root = new JSONObject(json);
            JSONArray acts = root.getJSONArray("actions");
            for (int i = 0; i < acts.length(); i++) {
                JSONObject a = acts.getJSONObject(i);
                actions.add(new FunscriptAction(a.getLong("at"), a.getInt("pos")));
            }
            // Sap xep theo thoi gian
            java.util.Collections.sort(actions, (a, b) -> Long.compare(a.at, b.at));
            Log.d(TAG, "Loaded " + actions.size() + " actions");
            return !actions.isEmpty();
        } catch (Exception e) {
            Log.e(TAG, "Parse error: " + e.getMessage());
            return false;
        }
    }

    // Bat dau sync voi video - goi khi video play
    public void start(long videoTimeMs, FunscriptCallback cb) {
        if (actions.isEmpty()) return;
        cancelSchedule();
        this.callback = cb;
        this.startTimeMs = System.currentTimeMillis() - videoTimeMs;
        this.isRunning = true;
        currentIndex = findActionIndex(videoTimeMs);
        scheduleNext();
    }

    // Dong bo lai khi seek video
    public void seekTo(long videoTimeMs) {
        if (!isRunning) return;
        cancelSchedule();
        startTimeMs = System.currentTimeMillis() - videoTimeMs;
        currentIndex = findActionIndex(videoTimeMs);
        scheduleNext();
    }

    public void pause() {
        cancelSchedule();
    }

    public void resume(long videoTimeMs) {
        if (!isRunning) return;
        startTimeMs = System.currentTimeMillis() - videoTimeMs;
        scheduleNext();
    }

    public void stop() {
        isRunning = false;
        cancelSchedule();
        currentIndex = 0;
    }

    public void unload() {
        stop();
        actions.clear();
        callback = null;
    }

    public boolean isLoaded() { return !actions.isEmpty(); }
    public int getActionCount() { return actions.size(); }

    private void scheduleNext() {
        if (!isRunning || currentIndex >= actions.size()) {
            if (callback != null) callback.onFinished();
            return;
        }
        FunscriptAction action = actions.get(currentIndex);
        long now = System.currentTimeMillis();
        long videoNow = now - startTimeMs;
        long delay = action.at - videoNow;

        // Tinh speed cho action ke tiep
        int speed = 400; // default ms
        if (currentIndex + 1 < actions.size()) {
            speed = (int)(actions.get(currentIndex + 1).at - action.at);
        }
        final int finalSpeed = Math.max(50, speed);
        final int pos = action.pos;

        if (delay < 0) delay = 0;
        // Hen lai gan moc action thay vi bo qua cac action o xa hon 5 giay.
        if (delay > 5000) {
            handler.postDelayed(this::scheduleNext, 5000);
            return;
        }

        handler.postDelayed(() -> {
            if (!isRunning) return;
            if (callback != null) callback.onAction(pos, finalSpeed);
            currentIndex++;
            scheduleNext();
        }, Math.max(0, delay));
    }

    private int findActionIndex(long videoTimeMs) {
        int low = 0;
        int high = actions.size();
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (actions.get(mid).at < videoTimeMs) low = mid + 1;
            else high = mid;
        }
        return low;
    }

    private void cancelSchedule() {
        handler.removeCallbacksAndMessages(null);
    }
}
