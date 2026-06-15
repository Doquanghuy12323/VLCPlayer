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
    private boolean streamOnly = false; // Xoa sau khi xem

    public TorrentManager(Context ctx) {
        saveDir = new File(ctx.getCacheDir(), "torrent_stream");
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

    public void setStreamOnly(boolean streamOnly) {
        this.streamOnly = streamOnly;
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

                if (finalUrl.startsWith("/")) {
                    // File .torrent - dung TorrentInfo
                    File f = new File(finalUrl);
                    if (!f.exists()) {
                        final String errPath = finalUrl;
                        handler.post(() -> cb.onError("File khong tim thay: " + errPath));
                        return;
                    }
                    TorrentInfo ti = new TorrentInfo(f);
                    // download() tra ve void - lay handle qua find()
                    session.download(ti, saveDir);
                    // Doi session nhan torrent
                    long t = System.currentTimeMillis();
                    while (handle == null && System.currentTimeMillis() - t < 10000) {
                        handle = session.find(ti.infoHash());
                        if (handle == null) Thread.sleep(200);
                    }
                } else if (finalUrl.startsWith("magnet:")) {
                    // Lay info hash tu magnet
                    String infoHash = extractInfoHash(finalUrl);
                    if (infoHash.isEmpty()) {
                        handler.post(() -> cb.onError("Magnet link khong hop le"));
                        return;
                    }
                    // Them trackers
                    String magnetUrl = finalUrl;
                    if (!magnetUrl.contains("&tr=")) {
                        magnetUrl += "&tr=udp://tracker.opentrackr.org:1337/announce"
                            + "&tr=udp://open.stealth.si:80/announce"
                            + "&tr=udp://tracker.torrent.eu.org:451/announce"
                            + "&tr=udp://9.rarbg.to:2920/announce";
                    }
                    // fetchMagnet block cho den khi co metadata
                    handler.post(() -> cb.onStatusUpdate("Dang tim kiem metadata..."));
                    byte[] data = session.fetchMagnet(magnetUrl, 30, saveDir);
                    if (data == null) {
                        handler.post(() -> cb.onError("Khong tim duoc metadata. Kiem tra mang hoac magnet link"));
                        return;
                    }
                    org.libtorrent4j.Sha1Hash sha1 =
                        org.libtorrent4j.Sha1Hash.parseHex(infoHash);
                    long t = System.currentTimeMillis();
                    while (handle == null && System.currentTimeMillis() - t < 5000) {
                        handle = session.find(sha1);
                        if (handle == null) Thread.sleep(200);
                    }
                } else {
                    handler.post(() -> cb.onError("Link khong hop le"));
                    return;
                }

                if (handle == null) {
                    handler.post(() -> cb.onError("Khong khoi tao duoc torrent handle"));
                    return;
                }

                // Bat sequential download: uu tien tai tu dau file
                // Dam bao VLC co the play ngay khi co du buffer
                try {
                    handle.setFlags(
                        org.libtorrent4j.TorrentFlags.SEQUENTIAL_DOWNLOAD);
                } catch (Exception ignored) {}

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

                    if (!readyCalled && pct > 0) {
                        TorrentInfo ti = handle.torrentFile();
                        if (ti != null) {
                            File video = findVideoFile(ti);
                            // Fix null check truoc
                            if (video == null) return;
                            // Chi can 5MB la VLC co the bat dau phat
                            // Sequential download dam bao phan dau file
                            // duoc tai truoc nen VLC play duoc ngay
                            if (video.exists() && video.length() >= 5L*1024*1024) {
                                readyCalled = true;
                                final String vpath = video.getAbsolutePath();
                                final boolean doDelete = streamOnly;
                                handler.post(() -> cb.onReady(vpath));
                                // Neu stream mode: xoa file sau 1 gio
                                if (doDelete) {
                                    handler.postDelayed(() -> {
                                        try { new File(vpath).delete(); } catch (Exception ignored) {}
                                    }, 60 * 60 * 1000L);
                                }
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
        // Chi dung monitor, KHONG xoa torrent
        // De file nang tiep tuc tai trong nen va luu duoc
        if (monitorTimer != null) {
            monitorTimer.cancel();
            monitorTimer = null;
        }
        // Khong remove handle - giu download tiep tuc
        readyCalled = false;
    }

    // Goi khi thoat app hoan toan
    public void destroy() {
        stop();
        if (handle != null && handle.isValid()) {
            try { session.remove(handle); } catch (Exception ignored) {}
            handle = null;
        }
    }

    public boolean isStreaming() {
        return handle != null && handle.isValid();
    }
}
