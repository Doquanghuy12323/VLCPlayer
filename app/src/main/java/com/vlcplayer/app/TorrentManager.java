package com.vlcplayer.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import com.frostwire.jlibtorrent.SessionManager;
import com.frostwire.jlibtorrent.TorrentHandle;
import com.frostwire.jlibtorrent.TorrentInfo;
import com.frostwire.jlibtorrent.TorrentStatus;
import com.frostwire.jlibtorrent.FileStorage;
import java.io.File;
import java.util.Timer;
import java.util.TimerTask;

public class TorrentManager {

    public interface Callback {
        void onProgress(int progress, float downloadSpeed, float uploadSpeed);
        void onReady(String videoPath);
        void onError(String error);
        void onStopped();
        default void onStatusUpdate(String status) {}
    }

    private static SessionManager sessionManager;
    private TorrentHandle torrentHandle;
    private Timer monitorTimer;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final File saveDir;

    public TorrentManager(Context ctx) {
        saveDir = ctx.getExternalFilesDir("torrents");
        if (saveDir != null && !saveDir.exists()) saveDir.mkdirs();

        if (sessionManager == null) {
            sessionManager = new SessionManager();
            new Thread(() -> {
                try {
                    sessionManager.start();
                    // Kích hoạt DHT tìm kiếm node mạng ngang hàng
                    sessionManager.startDht();
                } catch (Exception ignored) {}
            }).start();
        }
    }

    public void startStream(String urlOrPath, Callback cb) {
        stop();

        new Thread(() -> {
            try {
                handler.post(() -> cb.onStatusUpdate("Đang khởi tạo cấu hình P2P Swarm mạng..."));

                if (urlOrPath.startsWith("magnet:") || urlOrPath.startsWith("http")) {
                    String optimizedUrl = urlOrPath;
                    if (optimizedUrl.startsWith("magnet:") && !optimizedUrl.contains("&tr=")) {
                        optimizedUrl += "&tr=udp://tracker.opentrackr.org:1337/announce" +
                                        "&tr=udp://open.stealth.si:80/announce" +
                                        "&tr=udp://tracker.coppersurfer.tk:6969/announce" +
                                        "&tr=udp://tracker.openbittorrent.com:6099/announce";
                    }
                    sessionManager.download(optimizedUrl, saveDir);
                } else {
                    File file = new File(urlOrPath.replace("file://", ""));
                    if (!file.exists()) {
                        handler.post(() -> cb.onError("Tệp tin torrent không tồn tại"));
                        return;
                    }
                    TorrentInfo ti = new TorrentInfo(file);
                    sessionManager.download(ti, saveDir);
                }

                long startTime = System.currentTimeMillis();
                while (torrentHandle == null && System.currentTimeMillis() - startTime < 8000) {
                    // SỬA: Đổi torrentHandles() thành torrents() để khớp thư viện gốc
                    for (TorrentHandle th : sessionManager.torrents()) {
                        torrentHandle = th;
                        break;
                    }
                    Thread.sleep(200);
                }

                if (torrentHandle == null) {
                    handler.post(() -> cb.onError("Không thể liên kết Torrent Handle"));
                    return;
                }

                // SỬA: Đổi setSequentialDownload(true) thành sequentialDownload(true)
                torrentHandle.sequentialDownload(true);

                startMonitoring(cb);

            } catch (Exception e) {
                handler.post(() -> cb.onError("Lỗi: " + e.getMessage()));
            }
        }).start();
    }

    private void startMonitoring(Callback cb) {
        monitorTimer = new Timer();
        monitorTimer.scheduleAtFixedRate(new TimerTask() {
            private boolean isReadyCalled = false;

            @Override
            public void run() {
                if (torrentHandle == null || !torrentHandle.isValid()) return;

                TorrentStatus status = torrentHandle.status();
                boolean hasMetadata = status.hasMetadata();
                int progress = (int) (status.progress() * 100);
                float dlSpeed = status.downloadRate() / 1024f; // KB/s
                float ulSpeed = status.uploadRate() / 1024f;   // KB/s
                int numPeers = status.numPeers();

                handler.post(() -> {
                    if (!hasMetadata) {
                        cb.onStatusUpdate("Đang đồng bộ cấu trúc tệp (Downloading Metadata)...");
                    } else if (progress == 0 && dlSpeed == 0) {
                        cb.onStatusUpdate("Đang kết nối mạng DHT (Peers hiện tại: " + numPeers + ")...");
                    } else {
                        cb.onStatusUpdate("Kết nối thành công! Đang tải đệm video...");
                        cb.onProgress(progress, dlSpeed, ulSpeed);
                    }
                });

                if (hasMetadata && (progress >= 1 || dlSpeed > 50) && !isReadyCalled) {
                    isReadyCalled = true;
                    TorrentInfo ti = torrentHandle.torrentFile();
                    if (ti != null) {
                        FileStorage fs = ti.files();
                        int videoIdx = 0;
                        long maxBytes = 0;
                        for (int i = 0; i < fs.numFiles(); i++) {
                            if (fs.fileSize(i) > maxBytes) {
                                maxBytes = fs.fileSize(i);
                                videoIdx = i;
                            }
                        }
                        File videoFile = new File(saveDir, fs.filePath(videoIdx));
                        handler.post(() -> cb.onReady(videoFile.getAbsolutePath()));
                    }
                }
            }
        }, 0, 1000);
    }

    public void stop() {
        if (monitorTimer != null) {
            monitorTimer.cancel();
            monitorTimer = null;
        }
        if (torrentHandle != null && torrentHandle.isValid()) {
            try {
                sessionManager.remove(torrentHandle);
            } catch (Exception ignored) {}
            torrentHandle = null;
        }
    }

    public boolean isStreaming() {
        return torrentHandle != null && torrentHandle.isValid();
    }
}
