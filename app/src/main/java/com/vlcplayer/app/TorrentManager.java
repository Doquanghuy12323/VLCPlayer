package com.vlcplayer.app;

import android.content.Context;

public class TorrentManager {
    public interface Callback {
        void onProgress(int progress, float downloadSpeed, float uploadSpeed);
        void onReady(String videoPath);
        void onError(String error);
        void onStopped();
        default void onStatusUpdate(String status) {}
    }

    public TorrentManager(Context ctx) {}
    public void startStream(String urlOrPath, Callback cb) {}
    public void stop() {}
    public boolean isStreaming() { return false; }
}
