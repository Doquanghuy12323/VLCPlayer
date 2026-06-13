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

        if (url.startsWith("magnet:")) {
            // Magnet: them tracker co URL encode dung
            if (!url.contains("&tr=")) {
                StringBuilder sb = new StringBuilder(url);
                for (String tr : TRACKERS) {
                    try {
                        sb.append("&tr=")
                          .append(java.net.URLEncoder.encode(tr, "UTF-8"));
                    } catch (Exception ignored) {
                        sb.append("&tr=").append(tr);
                    }
                }
                url = sb.toString();
            }
        } else {
            // File .torrent: chuan hoa ve duong dan tuyet doi
            String path = url;
            if (path.startsWith("file:///")) {
                path = path.substring(7); // giu 1 slash: /storage/...
            } else if (path.startsWith("file://")) {
                path = path.substring(7);
            }
            // path bay gio la /storage/... hoac /data/...
            File f = new File(path);
            if (!f.exists() || f.length() == 0) {
                cb.onError("Khong doc duoc file: " + path);
                return;
            }
            // TorrentStream can file:// + absolute path
            url = "file://" + f.getAbsolutePath();
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
                        msg = "Khong doc duoc noi dung torrent.\n"
                            + "- Magnet: kiem tra ket noi mang\n"
                            + "- File .torrent: file co the bi hong";
                    } else if (msg.contains("Connection refused")
                            || msg.contains("timed out")) {
                        msg = "Khong ket noi duoc. Kiem tra mang hoac doi thu";
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
