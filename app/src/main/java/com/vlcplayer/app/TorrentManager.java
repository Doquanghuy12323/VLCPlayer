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
        void onReady(String url); // HTTP URL cho VLC
        void onError(String error);
        void onStopped();
        default void onStatusUpdate(String status) {}
    }

    private static SessionManager session;
    private TorrentHandle handle;
    private Timer monitorTimer;
    private ServerSocket proxyServer;
    private Thread proxyThread;
    private boolean proxyRunning = false;
    private int proxyPort = 0;
    private boolean readyCalled = false;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final File saveDir;

    public TorrentManager(Context ctx) {
        saveDir = new File(ctx.getCacheDir(), "torrent_stream");
        if (!saveDir.exists()) saveDir.mkdirs();
        if (session == null) {
            session = new SessionManager();
            new Thread(() -> { try { session.start(); } catch (Exception e) {} }).start();
        }
    }

    public void startStream(String url, Callback cb) {
        stop();
        readyCalled = false;
        handler.post(() -> cb.onStatusUpdate("Dang khoi dong..."));

        new Thread(() -> {
            try {
                String finalUrl = url.trim();
                if (finalUrl.startsWith("file://")) finalUrl = finalUrl.substring(7);

                if (finalUrl.startsWith("/")) {
                    File f = new File(finalUrl);
                    if (!f.exists()) { handler.post(() -> cb.onError("File khong tim thay")); return; }
                    TorrentInfo ti = new TorrentInfo(f);
                    session.download(ti, saveDir);
                    long t = System.currentTimeMillis();
                    while (handle == null && System.currentTimeMillis() - t < 10000) {
                        handle = session.find(ti.infoHash());
                        if (handle == null) Thread.sleep(200);
                    }
                } else if (finalUrl.startsWith("magnet:")) {
                    String magnetUrl = finalUrl;
                    if (!magnetUrl.contains("&tr=")) {
                        magnetUrl += "&tr=udp://tracker.opentrackr.org:1337/announce"
                            + "&tr=udp://open.stealth.si:80/announce"
                            + "&tr=udp://tracker.torrent.eu.org:451/announce"
                            + "&tr=udp://9.rarbg.to:2920/announce";
                    }
                    String infoHash = extractInfoHash(finalUrl);
                    handler.post(() -> cb.onStatusUpdate("Dang tim metadata..."));
                    byte[] data = session.fetchMagnet(magnetUrl, 30, saveDir);
                    if (data == null) { handler.post(() -> cb.onError("Khong tim duoc metadata")); return; }
                    if (!infoHash.isEmpty()) {
                        long t = System.currentTimeMillis();
                        while (handle == null && System.currentTimeMillis() - t < 5000) {
                            try { handle = session.find(org.libtorrent4j.Sha1Hash.parseHex(infoHash)); } catch (Exception e) {}
                            if (handle == null) Thread.sleep(200);
                        }
                    }
                } else {
                    handler.post(() -> cb.onError("Link khong hop le")); return;
                }

                if (handle == null) { handler.post(() -> cb.onError("Khong bat duoc torrent handle")); return; }

                // Sequential download - tai tu dau den cuoi
                try { handle.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD); } catch (Exception ignored) {}

                // Uu tien piece dau va cuoi
                try {
                    TorrentInfo ti = handle.torrentFile();
                    if (ti != null) {
                        int n = ti.numPieces();
                        for (int i = 0; i < Math.min(15, n); i++)
                            handle.piecePriority(i, Priority.TOP_PRIORITY);
                        for (int i = Math.max(0, n-5); i < n; i++)
                            handle.piecePriority(i, Priority.TOP_PRIORITY);
                    }
                } catch (Exception ignored) {}

                // Bat HTTP proxy server
                startProxy(cb);

            } catch (Exception e) {
                handler.post(() -> cb.onError(e.getMessage() != null ? e.getMessage() : "Loi"));
            }
        }).start();
    }

    private void startProxy(Callback cb) throws Exception {
        proxyServer = new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"));
        proxyPort = proxyServer.getLocalPort();
        proxyRunning = true;

        proxyThread = new Thread(() -> {
            while (proxyRunning) {
                try {
                    Socket client = proxyServer.accept();
                    new Thread(() -> handleClient(client)).start();
                } catch (Exception e) {
                    if (!proxyRunning) break;
                }
            }
        });
        proxyThread.start();

        startMonitor(cb);
    }

    private void handleClient(Socket socket) {
        try (Socket s = socket;
             BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream()));
             OutputStream out = s.getOutputStream()) {

            String line = in.readLine();
            if (line == null) return;

            long rangeStart = 0, rangeEnd = -1;
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                if (line.toLowerCase().startsWith("range: bytes=")) {
                    String r = line.substring(13).trim();
                    int dash = r.indexOf("-");
                    if (dash != -1) {
                        try { rangeStart = Long.parseLong(r.substring(0, dash)); } catch (Exception e) {}
                        try { rangeEnd = dash + 1 < r.length() ? Long.parseLong(r.substring(dash + 1)) : -1; } catch (Exception e) {}
                    }
                }
            }

            if (handle == null || !handle.isValid()) {
                out.write("HTTP/1.1 503 Not Ready\r\n\r\n".getBytes());
                return;
            }

            TorrentInfo ti = handle.torrentFile();
            if (ti == null) { out.write("HTTP/1.1 503 No Info\r\n\r\n".getBytes()); return; }

            File video = findVideoFile(ti);
            if (video == null) { out.write("HTTP/1.1 404 Not Found\r\n\r\n".getBytes()); return; }

            // Uu tien piece can thiet cho seek
            int pieceSize = ti.pieceLength();
            if (pieceSize > 0) {
                int startPiece = (int)(rangeStart / pieceSize);
                int endPiece = (int)(Math.min(rangeStart + 512*1024, ti.totalSize()) / pieceSize);
                for (int i = startPiece; i <= Math.min(endPiece, startPiece + 8); i++) {
                    try { handle.piecePriority(i, Priority.TOP_PRIORITY); } catch (Exception ignored) {}
                }

                // Doi piece bat dau san sang (toi da 30 giay)
                long deadline = System.currentTimeMillis() + 30000;
                while (System.currentTimeMillis() < deadline && proxyRunning) {
                    if (handle.havePiece(startPiece)) break;
                    Thread.sleep(100);
                }
            }

            long fileLen = video.length();
            if (rangeEnd == -1 || rangeEnd >= fileLen) rangeEnd = fileLen - 1;
            long contentLen = rangeEnd - rangeStart + 1;

            String ext = video.getName().toLowerCase();
            String mime = ext.endsWith(".mkv") ? "video/x-matroska" :
                          ext.endsWith(".mp4") ? "video/mp4" : "video/octet-stream";

            String header = "HTTP/1.1 206 Partial Content\r\n"
                + "Content-Type: " + mime + "\r\n"
                + "Content-Range: bytes " + rangeStart + "-" + rangeEnd + "/" + fileLen + "\r\n"
                + "Content-Length: " + contentLen + "\r\n"
                + "Accept-Ranges: bytes\r\n"
                + "Connection: close\r\n\r\n";
            out.write(header.getBytes());

            try (RandomAccessFile raf = new RandomAccessFile(video, "r")) {
                raf.seek(rangeStart);
                byte[] buf = new byte[65536];
                long left = contentLen;
                while (proxyRunning && left > 0) {
                    int toRead = (int) Math.min(buf.length, left);
                    int read = raf.read(buf, 0, toRead);
                    if (read == -1) { Thread.sleep(200); continue; }
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

                    handler.post(() -> {
                        cb.onProgress(pct, dlKb);
                        cb.onStatusUpdate("Tai: " + pct + "% | " + (int)dlKb + " KB/s | Peers: " + peers);
                    });

                    // Phat khi piece dau tien da co
                    if (!readyCalled) {
                        TorrentInfo ti = handle.torrentFile();
                        if (ti != null) {
                            File video = findVideoFile(ti);
                            // Co 2MB va piece 0 da tai
                            if (video != null && video.exists()
                                    && video.length() >= 2L*1024*1024
                                    && handle.havePiece(0)) {
                                readyCalled = true;
                                String streamUrl = "http://127.0.0.1:" + proxyPort + "/stream";
                                handler.post(() -> cb.onReady(streamUrl));
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
            int best = 0; long max = 0;
            for (int i = 0; i < fs.numFiles(); i++) {
                String name = fs.fileName(i).toLowerCase();
                if ((name.endsWith(".mp4") || name.endsWith(".mkv")
                        || name.endsWith(".avi") || name.endsWith(".webm"))
                        && fs.fileSize(i) > max) {
                    max = fs.fileSize(i); best = i;
                }
            }
            if (max == 0) return null;
            return new File(saveDir, fs.filePath(best));
        } catch (Exception e) { return null; }
    }

    private String extractInfoHash(String magnet) {
        try {
            int s = magnet.indexOf("btih:") + 5;
            int e = magnet.indexOf("&", s);
            return e == -1 ? magnet.substring(s) : magnet.substring(s, e);
        } catch (Exception e) { return ""; }
    }

    public void stop() {
        proxyRunning = false;
        if (monitorTimer != null) { monitorTimer.cancel(); monitorTimer = null; }
        try { if (proxyServer != null) proxyServer.close(); } catch (Exception ignored) {}
        readyCalled = false;
    }

    public void destroy() {
        stop();
        if (handle != null && handle.isValid()) {
            try { session.remove(handle); } catch (Exception ignored) {}
            handle = null;
        }
        deleteDir(saveDir);
    }

    private void deleteDir(File dir) {
        if (dir == null || !dir.exists()) return;
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) { if (f.isDirectory()) deleteDir(f); else f.delete(); }
        dir.delete();
    }

    public boolean isStreaming() { return handle != null && handle.isValid(); }
}
