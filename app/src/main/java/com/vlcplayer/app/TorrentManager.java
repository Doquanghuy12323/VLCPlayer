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

    private TorrentStream torrentStream;
    private final Handler handler = new Handler(Looper.getMainLooper());

    public TorrentManager(Context ctx) {
        File saveDir = new File(ctx.getExternalFilesDir(null), "torrents");
        if (!saveDir.exists()) saveDir.mkdirs();

        TorrentOptions options = new TorrentOptions.Builder()
            .saveLocation(saveDir)
            .removeFilesAfterStop(false)
            .maxConnections(200)
            .build();

        torrentStream = TorrentStream.init(options);
    }

    public void startStream(String torrentUrl, Callback cb) {
        if (torrentStream.isStreaming()) {
            torrentStream.stopStream();
        }

        torrentStream.addListener(new TorrentListener() {
            @Override
            public void onStreamPrepared(Torrent torrent) {
                handler.post(() -> cb.onStatusUpdate("Dang chuan bi..."));
            }

            @Override
            public void onStreamStarted(Torrent torrent) {
                handler.post(() -> cb.onStatusUpdate("Dang ket noi P2P..."));
            }

            @Override
            public void onStreamError(Torrent torrent, Exception e) {
                handler.post(() -> cb.onError(
                    e != null && e.getMessage() != null
                        ? e.getMessage() : "Loi khong xac dinh"));
            }

            @Override
            public void onStreamReady(Torrent torrent) {
                String path = torrent.getVideoFile().getAbsolutePath();
                handler.post(() -> cb.onReady(path));
            }

            @Override
            public void onStreamProgress(Torrent torrent, StreamStatus status) {
                handler.post(() -> {
                    cb.onProgress(
                        (int)(status.progress * 100),
                        status.downloadSpeed / 1024f
                    );
                    cb.onStatusUpdate("Dang tai: " + (int)(status.progress*100)
                        + "% | " + (status.downloadSpeed/1024) + " KB/s"
                        + " | Seeds: " + status.seeds);
                });
            }

            @Override
            public void onStreamStopped() {
                handler.post(() -> cb.onStopped());
            }
        });

        torrentStream.startStream(torrentUrl);
    }

    public void stop() {
        if (torrentStream != null && torrentStream.isStreaming()) {
            torrentStream.stopStream();
        }
    }

    public boolean isStreaming() {
        return torrentStream != null && torrentStream.isStreaming();
    }
}
