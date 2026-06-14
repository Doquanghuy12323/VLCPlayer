package com.vlcplayer.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import org.libtorrent4j.AlertListener;
import org.libtorrent4j.SessionManager;
import org.libtorrent4j.SessionParams;
import org.libtorrent4j.TorrentHandle;
import org.libtorrent4j.TorrentInfo;
import org.libtorrent4j.TorrentStatus;
import org.libtorrent4j.alerts.Alert;
import org.libtorrent4j.alerts.AlertType;
import org.libtorrent4j.alerts.TorrentFinishedAlert;
import org.libtorrent4j.alerts.TorrentErrorAlert;
import org.libtorrent4j.alerts.MetadataReceivedAlert;
import org.libtorrent4j.alerts.BlockFinishedAlert;
import org.libtorrent4j.swig.settings_pack;
import java.io.File;
import java.util.Timer;
import java.util.TimerTask;

public class TorrentManager {

    public interface Callback {
        void onProgress(int progress, float downloadSpeed);
        void onReady(String videoPath);
        void onError(String error);
        void onStopped();
        default void onStatusUpdate(String status) {}
    }

    private static SessionManager session;
    private TorrentHandle handle;
    private Timer monitorTimer;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final File saveDir;
    private boolean readyCalled = false;

    public TorrentManager(Context ctx) {
        saveDir = new File(ctx.getExternalFilesDir(null), "torrents");
        if (!saveDir.exists()) saveDir.mkdirs();

        if (session == null) {
            session = new SessionManager();
            new Thread(() -> {
                try {
                    session.start();
                } catch (Exception e) {}
            }).start();
        }
    }

    public void startStream(String url, Callback cb) {
        stop();
        readyCalled = false;
        handler.post(() -> cb.onStatusUpdate("Dang khoi dong..."));

        new Thread(() -> {
            try {
                String finalUrl = url.trim();
                if (finalUrl.startsWith("file://")) {
                    finalUrl = finalUrl.substring(7);
                }

                if (finalUrl.startsWith("/") || finalUrl.startsWith("file")) {
                    // File .torrent
                    File f = new File(finalUrl.replace("file://", ""));
                    if (!f.exists()) {
                        handler.post(() -> cb.onError("File khong tim thay"));
                        return;
                    }
                    TorrentInfo ti = new TorrentInfo(f);
                    handle = session.download(ti, saveDir);
                } else if (finalUrl.startsWith("magnet:")) {
                    // Them tracker
                    if (!finalUrl.contains("&tr=")) {
                        finalUrl += "&tr=udp%3A%2F%2Ftracker.opentrackr.org%3A1337%2Fannounce"
                            + "&tr=udp%3A%2F%2Fopen.stealth.si%3A80%2Fannounce"
                            + "&tr=udp%3A%2F%2Ftracker.torrent.eu.org%3A451%2Fannounce";
                    }
                    session.fetchMagnet(finalUrl, 30, saveDir);
                    handle = session.find(org.libtorrent4j.Sha1Hash.parseHex(
                        extractInfoHash(finalUrl)));
                } else {
                    handler.post(() -> cb.onError("Link khong hop le"));
                    return;
                }

                if (handle == null) {
                    handler.post(() -> cb.onError("Khong the bat dau download"));
                    return;
                }

                startMonitor(cb);

            } catch (Exception e) {
                handler.post(() -> cb.onError(
                    e.getMessage() != null ? e.getMessage() : "Loi khong xac dinh"));
            }
        }).start();
    }

    private String extractInfoHash(String magnet) {
        try {
            int start = magnet.indexOf("btih:") + 5;
            int end = magnet.indexOf("&", start);
            return end == -1 ? magnet.substring(start) : magnet.substring(start, end);
        } catch (Exception e) {
            return "";
        }
    }

    private void startMonitor(Callback cb) {
        monitorTimer = new Timer();
        monitorTimer.scheduleAtFixedRate(new TimerTask() {
            @Override public void run() {
                if (handle == null || !handle.isValid()) return;
                try {
                    TorrentStatus st = handle.status();
                    int pct = (int)(st.progress() * 100);
                    float dlKb = st.downloadRate() / 1024f;
                    int peers = st.numPeers();
                    String state = st.state().toString();

                    handler.post(() -> {
                        cb.onProgress(pct, dlKb);
                        cb.onStatusUpdate(state + " | " + pct + "% | "
                            + (int)dlKb + " KB/s | Peers:" + peers);
                    });

                    // Bat dau xem khi da tai duoc 2MB
                    if (!readyCalled && pct > 0) {
                        TorrentInfo ti = handle.torrentFile();
                        if (ti != null) {
                            File video = findVideoFile(ti);
                            if (video != null && video.length() > 2 * 1024 * 1024) {
                                readyCalled = true;
                                handler.post(() -> cb.onReady(video.getAbsolutePath()));
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }
        }, 1000, 1000);
    }

    private File findVideoFile(TorrentInfo ti) {
        try {
            org.libtorrent4j.FileStorage fs = ti.files();
            int best = 0;
            long max = 0;
            for (int i = 0; i < fs.numFiles(); i++) {
                String name = fs.fileName(i).toLowerCase();
                if ((name.endsWith(".mp4") || name.endsWith(".mkv")
                        || name.endsWith(".avi") || name.endsWith(".webm"))
                        && fs.fileSize(i) > max) {
                    max = fs.fileSize(i);
                    best = i;
                }
            }
            if (max == 0) return null;
            return new File(saveDir, fs.filePath(best));
        } catch (Exception e) {
            return null;
        }
    }

    public void stop() {
        if (monitorTimer != null) {
            monitorTimer.cancel();
            monitorTimer = null;
        }
        if (handle != null && handle.isValid()) {
            try { session.remove(handle); } catch (Exception ignored) {}
            handle = null;
        }
    }

    public boolean isStreaming() {
        return handle != null && handle.isValid();
    }
}
