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

    private static final String[] TRACKERS = {
        "udp://tracker.opentrackr.org:1337/announce",
        "udp://open.stealth.si:80/announce",
        "udp://tracker.torrent.eu.org:451/announce",
        "udp://tracker.leechers-paradise.org:6969/announce",
        "udp://tracker.coppersurfer.tk:6969/announce",
        "udp://9.rarbg.to:2920/announce",
        "udp://exodus.desync.com:6969/announce"
    };

    private static TorrentStream torrentStream;
    private TorrentListener currentListener;
    private final Handler handler = new Handler(Looper.getMainLooper());

    public TorrentManager(Context ctx) {
        if (torrentStream == null) {
            File saveDir = new File(ctx.getExternalFilesDir(null), "torrents");
            if (!saveDir.exists()) saveDir.mkdirs();
            TorrentOptions options = new TorrentOptions.Builder()
                .saveLocation(saveDir)
                .removeFilesAfterStop(false)
                .maxConnections(200)
                .build();
            torrentStream = TorrentStream.init(options);
        }
    }

    public void startStream(String torrentUrl, Callback cb) {
        // Xoa listener cu
        if (currentListener != null) {
            torrentStream.removeListener(currentListener);
            currentListener = null;
        }
        // Stop stream cu
        if (torrentStream.isStreaming()) {
            torrentStream.stopStream();
            try { Thread.sleep(300); } catch (Exception ignored) {}
        }

        String url = torrentUrl.trim();

        // Magnet: them tracker neu chua co
        if (url.startsWith("magnet:")) {
            if (!url.contains("tr=")) {
                StringBuilder sb = new StringBuilder(url);
                for (String tr : TRACKERS) {
                    sb.append("&tr=").append(tr);
                }
                url = sb.toString();
            }
        }
        // File: strip file:// va kiem tra ton tai
        else if (url.startsWith("file://")) {
            url = url.substring(7);
            File f = new File(url);
            if (!f.exists() || f.length() == 0) {
                cb.onError("File torrent khong hop le: " + url);
                return;
            }
        }
        // Duong dan tuyet doi khong co prefix
        else if (url.startsWith("/")) {
            File f = new File(url);
            if (!f.exists() || f.length() == 0) {
                cb.onError("File khong tim thay: " + url);
                return;
            }
        }

        final String finalUrl = url;
        handler.post(() -> cb.onStatusUpdate("Dang khoi dong..."));

        currentListener = new TorrentListener() {
            @Override
            public void onStreamPrepared(Torrent torrent) {
                handler.post(() -> cb.onStatusUpdate("Dang chuan bi torrent..."));
            }

            @Override
            public void onStreamStarted(Torrent torrent) {
                handler.post(() -> cb.onStatusUpdate("Dang tim peers..."));
            }

            @Override
            public void onStreamError(Torrent torrent, Exception e) {
                String msg = "Loi stream";
                if (e != null && e.getMessage() != null) {
                    msg = e.getMessage();
                    // Giai thich loi ro hon
                    if (msg.contains("No torrent info")) {
                        msg = "Khong doc duoc torrent. Kiem tra magnet link hoac file .torrent";
                    } else if (msg.contains("Connection refused")) {
                        msg = "Khong ket noi duoc tracker. Kiem tra mang";
                    }
                }
                final String finalMsg = msg;
                handler.post(() -> cb.onError(finalMsg));
            }

            @Override
            public void onStreamReady(Torrent torrent) {
                if (torrent == null || torrent.getVideoFile() == null) {
                    handler.post(() -> cb.onError("Khong tim thay file video trong torrent"));
                    return;
                }
                File video = torrent.getVideoFile();
                handler.post(() -> cb.onReady(video.getAbsolutePath()));
            }

            @Override
            public void onStreamProgress(Torrent torrent, StreamStatus status) {
                int pct = (int)(status.progress * 100);
                float dlKb = status.downloadSpeed / 1024f;
                handler.post(() -> {
                    cb.onProgress(pct, dlKb);
                    cb.onStatusUpdate("Tai: " + pct + "% | "
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
