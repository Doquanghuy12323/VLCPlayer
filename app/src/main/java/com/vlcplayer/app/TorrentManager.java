package com.vlcplayer.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import com.github.se_bastiaan.torrentstream.StreamStatus;
import com.github.se_bastiaan.torrentstream.Torrent;
import com.github.se_bastiaan.torrentstream.TorrentOptions;
import com.github.se_bastiaan.torrentstream.TorrentStream;
import com.github.se_bastiaan.torrentstream.listeners.TorrentListener;
import java.io.File;

public class TorrentManager {

    public interface Callback {
        void onProgress(int progress, float downloadSpeed);
        void onReady(String videoPath);
        void onError(String error);
        void onStopped();
        default void onStatusUpdate(String status) {}
    }

    // Singleton - chi khoi tao 1 lan
    private static TorrentStream torrentStream;
    private static File saveDir;
    private TorrentListener currentListener;
    private final Handler handler = new Handler(Looper.getMainLooper());

    public TorrentManager(Context ctx) {
        if (torrentStream == null) {
            saveDir = new File(ctx.getExternalFilesDir(null), "torrents");
            if (!saveDir.exists()) saveDir.mkdirs();

            TorrentOptions options = new TorrentOptions.Builder()
                .saveLocation(saveDir)
                .removeFilesAfterStop(false)
                .build();

            torrentStream = TorrentStream.init(options);
        }
    }

    public void startStream(String torrentUrl, Callback cb) {
        // Xoa listener cu truoc
        if (currentListener != null) {
            torrentStream.removeListener(currentListener);
            currentListener = null;
        }

        // Stop stream cu
        if (torrentStream.isStreaming()) {
            torrentStream.stopStream();
        }

        // Xu ly file:// prefix
        String url = torrentUrl.trim();
        if (url.startsWith("file://")) {
            url = url.substring(7);
        }

        final String finalUrl = url;

        currentListener = new TorrentListener() {
            @Override
            public void onStreamPrepared(Torrent torrent) {
                handler.post(() -> cb.onStatusUpdate("Dang chuan bi torrent..."));
            }

            @Override
            public void onStreamStarted(Torrent torrent) {
                handler.post(() -> cb.onStatusUpdate("Dang ket noi peers..."));
            }

            @Override
            public void onStreamError(Torrent torrent, Exception e) {
                String msg = (e != null && e.getMessage() != null)
                    ? e.getMessage() : "Loi khong xac dinh";
                handler.post(() -> cb.onError(msg));
            }

            @Override
            public void onStreamReady(Torrent torrent) {
                File video = torrent.getVideoFile();
                if (video != null && video.exists()) {
                    handler.post(() -> cb.onReady(video.getAbsolutePath()));
                } else {
                    handler.post(() -> cb.onError("Khong tim thay file video"));
                }
            }

            @Override
            public void onStreamProgress(Torrent torrent, StreamStatus status) {
                int pct = (int)(status.progress * 100);
                float dlKb = status.downloadSpeed / 1024f;
                handler.post(() -> {
                    cb.onProgress(pct, dlKb);
                    cb.onStatusUpdate("Dang tai: " + pct + "% | "
                        + (int)dlKb + " KB/s | Seeds: " + status.seeds);
                });
            }

            @Override
            public void onStreamStopped() {
                handler.post(() -> cb.onStopped());
            }
        };

        torrentStream.addListener(currentListener);
        torrentStream.startStream(finalUrl);
    }

    public void stop() {
        if (currentListener != null) {
            torrentStream.removeListener(currentListener);
            currentListener = null;
        }
        if (torrentStream != null && torrentStream.isStreaming()) {
            torrentStream.stopStream();
        }
    }

    public boolean isStreaming() {
        return torrentStream != null && torrentStream.isStreaming();
    }
}
