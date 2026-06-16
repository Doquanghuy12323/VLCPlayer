package com.vlcplayer.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import org.libtorrent4j.SessionManager;
import org.libtorrent4j.TorrentHandle;
import org.libtorrent4j.TorrentInfo;
import org.libtorrent4j.TorrentStatus;
import org.libtorrent4j.TorrentFlags;
import org.libtorrent4j.Priority;
import java.io.*;
import java.net.*;
import java.util.Timer;
import java.util.TimerTask;

public class TorrentManager {

    public interface Callback {
        void onProgress(int progress, float downloadSpeed);
        void onReady(String httpUrl);
        void onError(String error);
        void onStopped();
        default void onStatusUpdate(String status) {}
    }

    private static SessionManager session;
    private TorrentHandle handle;
    private Timer monitorTimer;
    private ServerSocket proxyServer;
    private volatile boolean proxyRunning = false;
    private volatile int proxyPort = 0;
    private volatile boolean readyCalled = false;
    private volatile TorrentInfo cachedInfo = null;
    private volatile File cachedVideoFile = null;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final File saveDir;

    public TorrentManager(Context ctx) {
        saveDir = new File(ctx.getCacheDir(), "torrent_stream");
        if (!saveDir.exists()) saveDir.mkdirs();
        if (session == null) {
            session = new SessionManager();
            new Thread(() -> {
                try { session.start(); } catch (Exception ignored) {}
            }).start();
        }
    }

    public void startStream(String url, Callback cb) {
        stop();
        readyCalled = false;
        cachedInfo = null;
        cachedVideoFile = null;
        handler.post(() -> cb.onStatusUpdate("Khoi dong..."));

        new Thread(() -> {
            try {
                String path = url.trim();
                if (path.startsWith("file://")) path = path.substring(7);

                if (path.startsWith("/")) {
                    // File .torrent
                    File f = new File(path);
                    if (!f.exists()) {
                        handler.post(() -> cb.onError("File khong tim thay"));
                        return;
                    }
                    TorrentInfo ti = new TorrentInfo(f);
                    session.download(ti, saveDir);
                    // Doi handle
                    long t = System.currentTimeMillis();
                    while (handle == null && System.currentTimeMillis() - t < 10000) {
                        try { handle = session.find(ti.infoHash()); } catch (Exception ignored) {}
                        if (handle == null) Thread.sleep(300);
                    }
                } else if (path.startsWith("magnet:")) {
                    // Them tracker
                    String magnet = path;
                    if (!magnet.contains("&tr=")) {
                        magnet += "&tr=udp://tracker.opentrackr.org:1337/announce"
                            + "&tr=udp://open.stealth.si:80/announce"
                            + "&tr=udp://tracker.torrent.eu.org:451/announce"
                            + "&tr=udp://9.rarbg.to:2920/announce"
                            + "&tr=udp://tracker.coppersurfer.tk:6969/announce";
                    }
                    handler.post(() -> cb.onStatusUpdate("Tim metadata..."));
                    String hash = extractHash(path);
                    byte[] data = session.fetchMagnet(magnet, 30, saveDir);
                    if (data == null) {
                        handler.post(() -> cb.onError("Khong tim duoc metadata. Kiem tra ket noi mang"));
                        return;
                    }
                    if (!hash.isEmpty()) {
                        long t = System.currentTimeMillis();
                        while (handle == null && System.currentTimeMillis() - t < 8000) {
                            try {
                                handle = session.find(
                                    org.libtorrent4j.Sha1Hash.parseHex(hash));
                            } catch (Exception ignored) {}
                            if (handle == null) Thread.sleep(300);
                        }
                    }
                } else {
                    handler.post(() -> cb.onError("Link khong hop le"));
                    return;
                }

                if (handle == null) {
                    handler.post(() -> cb.onError("Khong bat duoc torrent"));
                    return;
                }

                // Bat sequential + uu tien piece dau/cuoi
                setupDownloadPriority();

                // Cache TorrentInfo
                cachedInfo = handle.torrentFile();
                if (cachedInfo != null) {
                    cachedVideoFile = findVideoFile(cachedInfo);
                }

                // Khoi dong HTTP proxy
                startProxy(cb);

            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : "Loi khong xac dinh";
                handler.post(() -> cb.onError(msg));
            }
        }).start();
    }

    private void setupDownloadPriority() {
        if (handle == null || !handle.isValid()) return;
        try {
            handle.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD);
        } catch (Exception ignored) {}
        try {
            if (cachedInfo == null) cachedInfo = handle.torrentFile();
            if (cachedInfo == null) return;
            int n = cachedInfo.numPieces();
            // Uu tien cao nhat cho 20 piece dau (header)
            for (int i = 0; i < Math.min(20, n); i++) {
                handle.piecePriority(i, Priority.TOP_PRIORITY);
            }
            // Uu tien cao cho 5 piece cuoi (index/moov atom)
            for (int i = Math.max(0, n - 5); i < n; i++) {
                handle.piecePriority(i, Priority.HIGH);
            }
        } catch (Exception ignored) {}
    }

    private void startProxy(Callback cb) throws Exception {
        proxyServer = new ServerSocket(0, 50,
            InetAddress.getByName("127.0.0.1"));
        proxyPort = proxyServer.getLocalPort();
        proxyRunning = true;

        // Thread rieng xu ly tung request
        new Thread(() -> {
            while (proxyRunning) {
                try {
                    Socket client = proxyServer.accept();
                    client.setSoTimeout(60000);
                    new Thread(() -> handleRequest(client)).start();
                } catch (Exception e) {
                    if (!proxyRunning) break;
                }
            }
        }).start();

        startMonitor(cb);
    }

    private void handleRequest(Socket socket) {
        try (Socket s = socket;
             BufferedReader in = new BufferedReader(
                 new InputStreamReader(s.getInputStream()));
             OutputStream out = s.getOutputStream()) {

            // Doc HTTP request
            String requestLine = in.readLine();
            if (requestLine == null) return;

            long rangeStart = 0;
            long rangeEnd = -1;
            String line;
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                if (line.toLowerCase().startsWith("range: bytes=")) {
                    String rv = line.substring(13).trim();
                    int dash = rv.indexOf("-");
                    if (dash >= 0) {
                        try { rangeStart = Long.parseLong(rv.substring(0, dash).trim()); }
                        catch (Exception ignored) {}
                        try {
                            String endStr = rv.substring(dash + 1).trim();
                            if (!endStr.isEmpty()) rangeEnd = Long.parseLong(endStr);
                        } catch (Exception ignored) {}
                    }
                }
            }

            // Lay file video
            File video = cachedVideoFile;
            TorrentInfo ti = cachedInfo;

            if (video == null || ti == null) {
                if (handle != null && handle.isValid()) {
                    ti = handle.torrentFile();
                    if (ti != null) {
                        video = findVideoFile(ti);
                        cachedInfo = ti;
                        cachedVideoFile = video;
                    }
                }
            }

            if (video == null) {
                out.write("HTTP/1.1 503 Not Ready\r\nContent-Length: 0\r\n\r\n".getBytes());
                return;
            }

            // Tinh piece can thiet cho request nay
            int pieceLen = ti.pieceLength();
            if (pieceLen > 0 && handle != null && handle.isValid()) {
                int startPiece = (int)(rangeStart / pieceLen);
                // Uu tien piece cho vi tri hien tai + 8 piece tiep theo
                for (int i = startPiece; i < Math.min(startPiece + 8, ti.numPieces()); i++) {
                    try { handle.piecePriority(i, Priority.TOP_PRIORITY); } catch (Exception ignored) {}
                }

                // Doi piece bat dau san sang (toi da 15 giay)
                long deadline = System.currentTimeMillis() + 15000;
                while (System.currentTimeMillis() < deadline && proxyRunning) {
                    try {
                        if (handle.havePiece(startPiece)) break;
                    } catch (Exception ignored) { break; }
                    Thread.sleep(50);
                }
            }

            // Lay kich thuoc file thuc te
            long fileLen = video.exists() ? video.length() : ti.totalSize();
            if (fileLen <= 0) fileLen = ti.totalSize();

            if (rangeEnd < 0 || rangeEnd >= fileLen) rangeEnd = fileLen - 1;
            long contentLen = rangeEnd - rangeStart + 1;

            // MIME type
            String name = video.getName().toLowerCase();
            String mime = name.endsWith(".mkv") ? "video/x-matroska"
                : name.endsWith(".mp4") ? "video/mp4"
                : name.endsWith(".avi") ? "video/x-msvideo"
                : name.endsWith(".webm") ? "video/webm"
                : "video/octet-stream";

            // Response header
            String respHeader;
            if (rangeStart == 0 && rangeEnd == fileLen - 1) {
                respHeader = "HTTP/1.1 200 OK\r\n"
                    + "Content-Type: " + mime + "\r\n"
                    + "Content-Length: " + fileLen + "\r\n"
                    + "Accept-Ranges: bytes\r\n"
                    + "Connection: close\r\n\r\n";
            } else {
                respHeader = "HTTP/1.1 206 Partial Content\r\n"
                    + "Content-Type: " + mime + "\r\n"
                    + "Content-Range: bytes " + rangeStart + "-"
                    + rangeEnd + "/" + fileLen + "\r\n"
                    + "Content-Length: " + contentLen + "\r\n"
                    + "Accept-Ranges: bytes\r\n"
                    + "Connection: close\r\n\r\n";
            }
            out.write(respHeader.getBytes("UTF-8"));

            // Stream data
            final long finalRangeStart = rangeStart;
            final long finalContentLen = contentLen;
            try (RandomAccessFile raf = new RandomAccessFile(video, "r")) {
                raf.seek(finalRangeStart);
                byte[] buf = new byte[65536];
                long left = finalContentLen;
                int emptyRetry = 0;
                while (proxyRunning && left > 0) {
                    int toRead = (int) Math.min(buf.length, left);
                    int read = raf.read(buf, 0, toRead);
                    if (read == -1 || read == 0) {
                        emptyRetry++;
                        if (emptyRetry > 300) break; // 15 giay
                        Thread.sleep(50);
                        continue;
                    }
                    emptyRetry = 0;
                    out.write(buf, 0, read);
                    left -= read;
                }
            }
        } catch (Exception ignored) {}
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
                            + (int)dlKb + " KB/s | Peers: " + peers);
                    });

                    // Goi onReady ngay khi piece 0 san sang
                    // Khong doi file size - proxy se xu ly
                    if (!readyCalled && handle.havePiece(0)) {
                        readyCalled = true;
                        String url = "http://127.0.0.1:" + proxyPort + "/stream";
                        handler.post(() -> cb.onReady(url));
                    }
                } catch (Exception ignored) {}
            }
        }, 500, 1000);
    }

    private File findVideoFile(TorrentInfo ti) {
        try {
            org.libtorrent4j.FileStorage fs = ti.files();
            int best = -1;
            long max = 0;
            for (int i = 0; i < fs.numFiles(); i++) {
                String n = fs.fileName(i).toLowerCase();
                long sz = fs.fileSize(i);
                if ((n.endsWith(".mp4") || n.endsWith(".mkv")
                        || n.endsWith(".avi") || n.endsWith(".webm")
                        || n.endsWith(".mov")) && sz > max) {
                    max = sz; best = i;
                }
            }
            if (best < 0) return null;
            return new File(saveDir, fs.filePath(best));
        } catch (Exception e) { return null; }
    }

    private String extractHash(String magnet) {
        try {
            int s = magnet.indexOf("btih:") + 5;
            if (s < 5) return "";
            int e = magnet.indexOf("&", s);
            String h = e < 0 ? magnet.substring(s) : magnet.substring(s, e);
            return h.trim().toLowerCase();
        } catch (Exception e) { return ""; }
    }

    public void stop() {
        proxyRunning = false;
        if (monitorTimer != null) { monitorTimer.cancel(); monitorTimer = null; }
        try { if (proxyServer != null && !proxyServer.isClosed()) proxyServer.close(); }
        catch (Exception ignored) {}
        readyCalled = false;
    }

    public void destroy() {
        stop();
        if (handle != null && handle.isValid()) {
            try { session.remove(handle); } catch (Exception ignored) {}
        }
        handle = null;
        cachedInfo = null;
        cachedVideoFile = null;
        deleteDir(saveDir);
    }

    private void deleteDir(File dir) {
        if (dir == null || !dir.exists()) return;
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) {
            if (f.isDirectory()) deleteDir(f); else f.delete();
        }
        dir.delete();
    }

    public boolean isStreaming() {
        return handle != null && handle.isValid() && proxyRunning;
    }
}
