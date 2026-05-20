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
        default void onStatusUpdate(String status) {}
    }

    private TorrentStream torrentStream;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Callback callback;

    public TorrentManager(Context ctx) {
        // Đổi lại bộ nhớ ngoài chuyên dụng để loại bỏ giới hạn Quota của phân vùng nội bộ đối với file lớn vài GB
        File saveDir = ctx.getExternalFilesDir("torrents");
        if (saveDir == null) {
            saveDir = new File(ctx.getFilesDir(), "torrents");
        }
        if (!saveDir.exists()) saveDir.mkdirs();

        TorrentOptions options = new TorrentOptions.Builder()
            .saveLocation(saveDir)
            .removeFilesAfterStop(false) // Giữ lại file để hiển thị dưới mục "File đã tải"
            .anonymousMode(false)
            .maxConnections(200)
            .build();

        torrentStream = TorrentStream.init(options);
    }

    public void startStream(String torrentUrl, Callback cb) {
        this.callback = cb;

        torrentStream.addListener(new TorrentListener() {
            @Override
            public void onStreamReady(Torrent torrent) {
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
                        status.downloadSpeed / 1024f,
                        0f
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
            public void onStreamPrepared(Torrent torrent) {
                handler.post(() -> {
                    if (callback != null) callback.onStatusUpdate("Phân tích xong tệp. Đang cấu hình bộ nhớ đệm...");
                });
            }

            @Override
            public void onStreamStarted(Torrent torrent) {
                handler.post(() -> {
                    if (callback != null) callback.onStatusUpdate("Đang kết nối DHT & Dò tìm Seeders/Peers toàn cầu...");
                });
            }

            @Override
            public void onStreamError(Torrent torrent, Exception e) {
                handler.post(() -> {
                    if (callback != null) callback.onError(
                        e.getMessage() != null ? e.getMessage() : "Lỗi hệ thống Torrent ngầm"
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
