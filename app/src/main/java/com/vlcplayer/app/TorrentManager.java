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
import org.libtorrent4j.FileStorage;
import java.io.*;
import java.net.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicInteger;

public class TorrentManager {

    private static final long GIB = 1024L * 1024L * 1024L;
    private static final long STARTUP_HEADROOM_BYTES = 128L * 1024L * 1024L;
    private static volatile TorrentManager activeInstance;

    public static class VideoFileEntry {
        public final int index;
        public final String name;
        public final long size;
        public VideoFileEntry(int index, String name, long size) {
            this.index = index; this.name = name; this.size = size;
        }
    }

    public interface Callback {
        void onProgress(int progress, float downloadSpeed);
        void onReady(String httpUrl);
        void onError(String error);
        void onStopped();
        default void onStatusUpdate(String status) {}
        default void onFilesFound(List<VideoFileEntry> files) {}
    }

    private static SessionManager session;
    private TorrentHandle handle;
    private Timer monitorTimer;
    private ServerSocket proxyServer;
    private volatile boolean proxyRunning = false;
    private volatile int proxyPort = 0;
    private volatile boolean readyCalled = false;
    private volatile TorrentInfo cachedInfo = null;
    private volatile int selectedFileIndex = -1;
    private volatile File selectedVideoFile = null;
    private volatile boolean lowStorageStopping = false;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final File saveDir;
    private static final Object CACHE_LOCK = new Object();
    private final AtomicInteger lifecycleGeneration = new AtomicInteger();

    public TorrentManager(Context ctx) {
        saveDir = getCacheDirectory(ctx);
        if (!saveDir.exists()) saveDir.mkdirs();
        if (session == null) {
            session = new SessionManager();
            new Thread(() -> {
                try { session.start(); } catch (Exception ignored) {}
            }).start();
        }
    }

    public void startStream(String url, Callback cb) {
        activeInstance = this;
        stop();
        final int operationId = lifecycleGeneration.incrementAndGet();
        readyCalled = false;
        lowStorageStopping = false;
        handler.post(() -> cb.onStatusUpdate("Khoi dong..."));

        new Thread(() -> {
            try {
                if (!isCurrent(operationId)) return;
                clearCacheWithRetries(false);
                if (!isCurrent(operationId)) return;
                if (!saveDir.exists() && !saveDir.mkdirs()) {
                    throw new IOException("Khong tao duoc bo nho tam torrent");
                }
                ensureStorageAvailable();

                String path = url.trim();
                if (path.startsWith("file://")) path = path.substring(7);

                if (path.startsWith("/")) {
                    File f = new File(path);
                    if (!f.exists()) {
                        handler.post(() -> cb.onError("File khong tim thay"));
                        return;
                    }
                    TorrentInfo ti = new TorrentInfo(f);
                    session.download(ti, saveDir);
                    long t = System.currentTimeMillis();
                    while (isCurrent(operationId) && handle == null
                            && System.currentTimeMillis() - t < 10000) {
                        try { handle = session.find(ti.infoHash()); } catch (Exception ignored) {}
                        if (handle == null) Thread.sleep(300);
                    }
                    if (!isCurrent(operationId)) return;
                } else if (path.startsWith("http://") || path.startsWith("https://")) {
                    handler.post(() -> cb.onStatusUpdate("Dang tai file .torrent..."));
                    File torrentFile = new File(saveDir, "remote.torrent");
                    HttpURLConnection conn = (HttpURLConnection) new URL(path).openConnection();
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(30000);
                    conn.setInstanceFollowRedirects(true);
                    int responseCode = conn.getResponseCode();
                    if (responseCode < 200 || responseCode >= 300) {
                        throw new IOException("HTTP " + responseCode + " khi tai torrent");
                    }
                    try (InputStream input = conn.getInputStream();
                         OutputStream output = new FileOutputStream(torrentFile)) {
                        byte[] buffer = new byte[8192];
                        int read;
                        while ((read = input.read(buffer)) != -1) {
                            output.write(buffer, 0, read);
                        }
                    } finally {
                        conn.disconnect();
                    }
                    if (!isCurrent(operationId)) return;
                    TorrentInfo ti = new TorrentInfo(torrentFile);
                    session.download(ti, saveDir);
                    long t = System.currentTimeMillis();
                    while (isCurrent(operationId) && handle == null
                            && System.currentTimeMillis() - t < 10000) {
                        try { handle = session.find(ti.infoHash()); } catch (Exception ignored) {}
                        if (handle == null) Thread.sleep(300);
                    }
                    if (!isCurrent(operationId)) return;
                } else if (path.startsWith("magnet:")) {
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
                    if (!isCurrent(operationId)) return;
                    if (data == null) {
                        handler.post(() -> cb.onError("Khong tim duoc metadata. Kiem tra ket noi mang"));
                        return;
                    }
                    if (!hash.isEmpty()) {
                        long t = System.currentTimeMillis();
                        while (isCurrent(operationId) && handle == null
                                && System.currentTimeMillis() - t < 8000) {
                            try {
                                handle = session.find(org.libtorrent4j.Sha1Hash.parseHex(hash));
                            } catch (Exception ignored) {}
                            if (handle == null) Thread.sleep(300);
                        }
                    }
                    if (!isCurrent(operationId)) return;
                } else {
                    handler.post(() -> cb.onError("Link khong hop le"));
                    return;
                }

                if (handle == null) {
                    handler.post(() -> cb.onError("Khong bat duoc torrent"));
                    return;
                }

                try { handle.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD); } catch (Exception ignored) {}

                TorrentInfo ti = null;
                long tMeta = System.currentTimeMillis();
                while (isCurrent(operationId) && ti == null
                        && System.currentTimeMillis() - tMeta < 15000) {
                    ti = handle.torrentFile();
                    if (ti == null) Thread.sleep(200);
                }
                if (!isCurrent(operationId)) return;
                if (ti == null) {
                    handler.post(() -> cb.onError("Khong doc duoc metadata torrent"));
                    return;
                }
                cachedInfo = ti;
                ignoreAllFiles(ti);

                List<VideoFileEntry> videos = listVideoFiles(ti);
                if (videos.isEmpty()) {
                    handler.post(() -> cb.onError("Torrent nay khong chua file video"));
                    return;
                }

                if (videos.size() == 1) {
                    selectFileInternal(videos.get(0).index, cb);
                } else {
                    handler.post(() -> cb.onFilesFound(videos));
                }

            } catch (Exception e) {
                if (!isCurrent(operationId)) return;
                String msg = e.getMessage() != null ? e.getMessage() : "Loi khong xac dinh";
                handler.post(() -> cb.onError(msg));
            }
        }).start();
    }

    public void selectFile(int fileIndex, Callback cb) {
        new Thread(() -> selectFileInternal(fileIndex, cb)).start();
    }

    private void selectFileInternal(int fileIndex, Callback cb) {
        if (handle == null || !handle.isValid() || cachedInfo == null) {
            handler.post(() -> cb.onError("Torrent chua san sang, thu lai sau"));
            return;
        }
        try {
            ensureStorageAvailable();
            FileStorage fs = cachedInfo.files();
            int numFiles = fs.numFiles();
            Priority[] priorities = new Priority[numFiles];
            for (int i = 0; i < numFiles; i++) {
                // Khong tai lien tuc toan bo file. HTTP proxy se chi mo khoa
                // nhung piece VLC dang doc va mot cua so nho phia truoc.
                priorities[i] = Priority.IGNORE;
            }
            handle.prioritizeFiles(priorities);

            selectedFileIndex = fileIndex;
            selectedVideoFile = new File(saveDir, fs.filePath(fileIndex));
            readyCalled = false;

            prioritizeFileEnds(fileIndex);
            startProxy(cb);

        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : "Loi chon file";
            handler.post(() -> cb.onError(msg));
        }
    }

    private void ignoreAllFiles(TorrentInfo info) {
        if (handle == null || !handle.isValid() || info == null) return;
        try {
            Priority[] priorities = new Priority[info.files().numFiles()];
            for (int i = 0; i < priorities.length; i++) priorities[i] = Priority.IGNORE;
            handle.prioritizeFiles(priorities);
        } catch (Exception ignored) {}
    }

    private void ensureStorageAvailable() throws IOException {
        long usable = saveDir.getUsableSpace();
        long reserve = getReservedFreeSpace();
        if (usable > 0 && usable < reserve + STARTUP_HEADROOM_BYTES) {
            throw new IOException("Khong du bo nho an toan de phat torrent; app luon giu lai "
                + formatBytes(reserve) + " trong");
        }
    }

    private long getReservedFreeSpace() {
        long proportional = saveDir.getTotalSpace() / 10L;
        return Math.max(2L * GIB, Math.min(8L * GIB, proportional));
    }

    private static String formatBytes(long bytes) {
        return String.format(java.util.Locale.US, "%.1f GB", bytes / (double) GIB);
    }

    private void prioritizeFileEnds(int fileIndex) {
        if (handle == null || !handle.isValid() || cachedInfo == null) return;
        try {
            FileStorage fs = cachedInfo.files();
            long fileOffset = fs.fileOffset(fileIndex);
            long fileSize = fs.fileSize(fileIndex);
            int pieceLen = cachedInfo.pieceLength();
            if (pieceLen <= 0) return;

            int startPiece = (int) (fileOffset / pieceLen);
            int endPiece = (int) ((fileOffset + fileSize - 1) / pieceLen);

            int headCount = Math.min(8, endPiece - startPiece + 1);
            for (int i = 0; i < headCount; i++) {
                handle.piecePriority(startPiece + i, Priority.TOP_PRIORITY);
            }
            int tailCount = Math.min(2, endPiece - startPiece + 1);
            for (int i = 0; i < tailCount; i++) {
                handle.piecePriority(endPiece - i, Priority.TOP_PRIORITY);
            }
        } catch (Exception ignored) {}
    }

    private void startProxy(Callback cb) throws Exception {
        if (proxyServer != null && !proxyServer.isClosed()) {
            try { proxyServer.close(); } catch (Exception ignored) {}
        }
        proxyServer = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        proxyPort = proxyServer.getLocalPort();
        proxyRunning = true;

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
             BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream()));
             OutputStream out = s.getOutputStream()) {

            String requestLine = in.readLine();
            if (requestLine == null) return;
            boolean headOnly = requestLine.startsWith("HEAD ");

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

            File video = selectedVideoFile;
            TorrentInfo ti = cachedInfo;
            int fIdx = selectedFileIndex;

            if (video == null || ti == null || fIdx < 0) {
                out.write("HTTP/1.1 503 Not Ready\r\nContent-Length: 0\r\n\r\n".getBytes());
                return;
            }

            FileStorage fs = ti.files();
            int pieceLen = ti.pieceLength();
            if (pieceLen <= 0) {
                out.write("HTTP/1.1 503 Not Ready\r\nContent-Length: 0\r\n\r\n".getBytes("UTF-8"));
                return;
            }
            if (pieceLen > 0 && handle != null && handle.isValid()) {
                long fileOffset = fs.fileOffset(fIdx);
                int startPiece = (int) ((fileOffset + rangeStart) / pieceLen);
                int endPieceOfFile = (int) ((fileOffset + fs.fileSize(fIdx) - 1) / pieceLen);
                int reqEndPiece = Math.min(startPiece + 3, endPieceOfFile);
                for (int i = startPiece; i <= reqEndPiece; i++) {
                    try { handle.piecePriority(i, Priority.TOP_PRIORITY); } catch (Exception ignored) {}
                }
                // Tang thoi gian cho len 3 phut - tranh ngat giua video
                // khi mang cham gay VLC hieu nham la het video
                long deadline = System.currentTimeMillis() + 180000;
                while (System.currentTimeMillis() < deadline && proxyRunning) {
                    if (handle == null || !handle.isValid()) break;
                    try { if (handle.havePiece(startPiece)) break; } catch (Exception ignored) { break; }
                    Thread.sleep(80);
                }
            }

            long fileLen = fs.fileSize(fIdx);

            if (rangeStart < 0 || rangeStart >= fileLen || rangeEnd < -1) {
                String invalid = "HTTP/1.1 416 Range Not Satisfiable\r\n"
                    + "Content-Range: bytes */" + fileLen + "\r\n"
                    + "Content-Length: 0\r\n\r\n";
                out.write(invalid.getBytes("UTF-8"));
                return;
            }

            if (rangeEnd < 0 || rangeEnd >= fileLen) rangeEnd = fileLen - 1;
            long contentLen = rangeEnd - rangeStart + 1;

            String name = video.getName().toLowerCase();
            String mime = name.endsWith(".mkv") ? "video/x-matroska"
                : name.endsWith(".mp4") ? "video/mp4"
                : name.endsWith(".avi") ? "video/x-msvideo"
                : name.endsWith(".webm") ? "video/webm"
                : "video/octet-stream";

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
                    + "Content-Range: bytes " + rangeStart + "-" + rangeEnd + "/" + fileLen + "\r\n"
                    + "Content-Length: " + contentLen + "\r\n"
                    + "Accept-Ranges: bytes\r\n"
                    + "Connection: close\r\n\r\n";
            }
            out.write(respHeader.getBytes("UTF-8"));
            if (headOnly) return;

            final long finalRangeStart = rangeStart;
            final long finalContentLen = contentLen;
            try (RandomAccessFile raf = new RandomAccessFile(video, "r")) {
                raf.seek(finalRangeStart);
                byte[] buf = new byte[65536];
                long left = finalContentLen;
                while (proxyRunning && left > 0) {
                    if (handle == null || !handle.isValid()) break;

                    long positionInFile = finalContentLen - left + finalRangeStart;
                    long absoluteOffset = fs.fileOffset(fIdx) + positionInFile;
                    int piece = (int) (absoluteOffset / pieceLen);
                    if (!waitForPiece(piece, 180000)) break;

                    long bytesToPieceEnd = pieceLen - (absoluteOffset % pieceLen);
                    int toRead = (int) Math.min(Math.min(buf.length, left), bytesToPieceEnd);
                    int read = raf.read(buf, 0, toRead);
                    if (read == -1 || read == 0) {
                        Thread.sleep(50);
                        continue;
                    }
                    out.write(buf, 0, read);
                    left -= read;
                }
            }
        } catch (Exception ignored) {}
    }

    private boolean waitForPiece(int piece, long timeoutMs) throws InterruptedException {
        TorrentHandle activeHandle = handle;
        if (activeHandle == null || !activeHandle.isValid()) return false;
        try { activeHandle.piecePriority(piece, Priority.TOP_PRIORITY); }
        catch (Exception ignored) {}

        long deadline = System.currentTimeMillis() + timeoutMs;
        while (proxyRunning && System.currentTimeMillis() < deadline) {
            activeHandle = handle;
            if (activeHandle == null || !activeHandle.isValid()) return false;
            try {
                if (activeHandle.havePiece(piece)) return true;
            } catch (Exception e) {
                return false;
            }
            Thread.sleep(80);
        }
        return false;
    }

    private void startMonitor(Callback cb) {
        if (monitorTimer != null) monitorTimer.cancel();
        monitorTimer = new Timer();
        monitorTimer.scheduleAtFixedRate(new TimerTask() {
            @Override public void run() {
                if (handle == null || !handle.isValid()) return;
                try {
                    TorrentStatus st = handle.status();
                    int pct = (int) (st.progress() * 100);
                    float dlKb = st.downloadRate() / 1024f;
                    int peers = st.numPeers();
                    String state = st.state().toString();

                    if (!lowStorageStopping && saveDir.getUsableSpace() > 0
                            && saveDir.getUsableSpace() < getReservedFreeSpace()) {
                        lowStorageStopping = true;
                        handler.post(() -> cb.onError(
                            "Torrent da tu dung va don cache de bao ve bo nho trong"));
                        TorrentManager.this.stopAndClearCache();
                        return;
                    }

                    handler.post(() -> {
                        cb.onProgress(pct, dlKb);
                        cb.onStatusUpdate(state + " | " + pct + "% | "
                            + (int) dlKb + " KB/s | Peers: " + peers);
                    });

                    if (!readyCalled && cachedInfo != null && selectedFileIndex >= 0) {
                        int pieceLen = cachedInfo.pieceLength();
                        if (pieceLen > 0) {
                            long fileOffset = cachedInfo.files().fileOffset(selectedFileIndex);
                            int firstPiece = (int) (fileOffset / pieceLen);
                            if (handle.havePiece(firstPiece)) {
                                readyCalled = true;
                                String url = "http://127.0.0.1:" + proxyPort + "/stream";
                                handler.post(() -> cb.onReady(url));
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }
        }, 500, 1000);
    }

    private List<VideoFileEntry> listVideoFiles(TorrentInfo ti) {
        List<VideoFileEntry> list = new ArrayList<>();
        try {
            FileStorage fs = ti.files();
            for (int i = 0; i < fs.numFiles(); i++) {
                String n = fs.fileName(i).toLowerCase();
                if (n.endsWith(".mp4") || n.endsWith(".mkv")
                        || n.endsWith(".avi") || n.endsWith(".webm")
                        || n.endsWith(".mov")) {
                    list.add(new VideoFileEntry(i, fs.fileName(i), fs.fileSize(i)));
                }
            }
        } catch (Exception ignored) {}
        return list;
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
        lifecycleGeneration.incrementAndGet();
        proxyRunning = false;
        if (monitorTimer != null) { monitorTimer.cancel(); monitorTimer = null; }
        try { if (proxyServer != null && !proxyServer.isClosed()) proxyServer.close(); }
        catch (Exception ignored) {}
        readyCalled = false;

        // Fix: PHAI xoa handle cu, neu khong startStream() se tuong
        // da co handle va bo qua vong lap tim handle torrent moi
        final TorrentHandle oldHandle = handle;
        handle = null;
        cachedInfo = null;
        selectedFileIndex = -1;
        selectedVideoFile = null;

        if (oldHandle != null) {
            try {
                if (oldHandle.isValid()) session.remove(oldHandle);
            } catch (Exception ignored) {}
        }
    }

    public void stopAndClearCache() {
        stop();
        final int cleanupId = lifecycleGeneration.get();
        if (activeInstance == this) activeInstance = null;
        new Thread(() -> {
            if (isCurrent(cleanupId)) clearCacheWithRetries(true);
        }).start();
    }

    private boolean isCurrent(int operationId) {
        return lifecycleGeneration.get() == operationId;
    }

    public static void stopActiveAndCleanup(Context context) {
        TorrentManager manager = activeInstance;
        if (manager != null) manager.stopAndClearCache();
        else cleanupOrphanedCache(context);
    }

    public void destroy() {
        stopAndClearCache();
    }

    private void clearCacheWithRetries(boolean recreateDirectory) {
        synchronized (CACHE_LOCK) {
            for (int attempt = 0; attempt < 3 && saveDir.exists(); attempt++) {
                deleteDir(saveDir);
                if (saveDir.exists()) {
                    try { Thread.sleep(150L * (attempt + 1)); }
                    catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            if (recreateDirectory && !saveDir.exists()) saveDir.mkdirs();
        }
    }

    public static void cleanupOrphanedCache(Context context) {
        new Thread(() -> {
            synchronized (CACHE_LOCK) {
                TorrentManager active = activeInstance;
                File internal = new File(context.getCacheDir(), "torrent_stream");
                if (active == null || !active.isStreaming()
                        || !sameFile(active.saveDir, internal)) deleteDir(internal);
                File[] externalRoots = context.getExternalCacheDirs();
                if (externalRoots != null) {
                    for (File root : externalRoots) {
                        if (root == null) continue;
                        File external = new File(root, "torrent_stream");
                        if (!sameFile(external, internal)
                                && (active == null || !active.isStreaming()
                                || !sameFile(active.saveDir, external))) deleteDir(external);
                    }
                }
            }
        }).start();
    }

    public static File getCacheDirectory(Context context) {
        File internal = context.getCacheDir();
        File root = internal;
        File[] externalRoots = context.getExternalCacheDirs();
        if (externalRoots != null) {
            for (File external : externalRoots) {
                if (external != null && external.getUsableSpace() > root.getUsableSpace()) {
                    root = external;
                }
            }
        }
        return new File(root, "torrent_stream");
    }

    private static boolean sameFile(File first, File second) {
        if (first == null || second == null) return false;
        try { return first.getCanonicalFile().equals(second.getCanonicalFile()); }
        catch (IOException ignored) { return first.getAbsolutePath().equals(second.getAbsolutePath()); }
    }

    private static void deleteDir(File dir) {
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
