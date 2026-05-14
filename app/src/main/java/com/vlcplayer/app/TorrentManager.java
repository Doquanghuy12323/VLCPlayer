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
        void onProgress(int progress, float downloadSpeed, float uploadSpeed);
        void onReady(String videoPath);
        void onError(String error);
        void onStopped();
    }

    private TorrentStream torrentStream;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Callback callback;

    public TorrentManager(Context ctx) {
        File saveDir = new File(ctx.getExternalFilesDir(null), "torrents");
        if (!saveDir.exists()) saveDir.mkdirs();

        TorrentOptions options = new TorrentOptions.Builder()
            .saveLocation(saveDir)
            .removeFilesAfterStop(false) // Giu file sau khi xem
            .maxConnections(200)
            .build();

        torrentStream = TorrentStream.init(options);
    }

    public void startStream(String torrentUrl, Callback cb) {
        this.callback = cb;

        torrentStream.addListener(new TorrentListener() {
            @Override
            public void onStreamReady(Torrent torrent) {
                // Co du du lieu de bat dau xem
                String path = torrent.getVideoFile().getAbsolutePath();
                handler.post(() -> {
                    if (callback != null) callback.onReady(path);
                });
            }

            @Override
            public void onStreamProgress(Torrent torrent, StreamStatus status) {
                handler.post(() -> {
                    if (callback != null) callback.onProgress(
                        (int)(status.progress * 100),
                        status.downloadSpeed / 1024f, // KB/s
                        status.uploadSpeed / 1024f
                    );
                });
            }

            @Override
            public void onStreamStopped() {
                handler.post(() -> {
                    if (callback != null) callback.onStopped();
                });
            }

            @Override
            public void onStreamPrepared(Torrent torrent) {}

            @Override
            public void onStreamStarted(Torrent torrent) {}

            @Override
            public void onStreamError(Torrent torrent, Exception e) {
                handler.post(() -> {
                    if (callback != null) callback.onError(
                        e.getMessage() != null ? e.getMessage() : "Loi khong xac dinh"
                    );
                });
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
