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
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class TorrentManager {

    private static final long GIB = 1024L * 1024L * 1024L;
    private static final long STARTUP_HEADROOM_BYTES = 128L * 1024L * 1024L;
    private static volatile TorrentManager activeInstance;
    private static final ExecutorService START_EXECUTOR = Executors.newSingleThreadExecutor();
    private static final TorrentStorage.DeletionTokens STORED_PLAYBACK_TOKENS =
        new TorrentStorage.DeletionTokens();
    private static final TorrentStorage.TemporaryOwnership TEMPORARY_OWNERSHIP =
        new TorrentStorage.TemporaryOwnership();

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
    private volatile TorrentHandle handle;
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
    private final File temporaryDirectory;
    private final File retainedRoot;
    private volatile File saveDir;
    private volatile boolean autoCleanup = true;
    private volatile boolean streamActive;
    private long temporaryGeneration;
    private volatile String playbackSessionId;
    private volatile Callback callback;
    private volatile String lastStatus = "Khoi dong...";
    private static final Object CACHE_LOCK = new Object();
    private final AtomicInteger lifecycleGeneration = new AtomicInteger();

    public TorrentManager(Context ctx) {
        temporaryDirectory = getCacheDirectory(ctx);
        retainedRoot = getRetainedDirectory(ctx);
        saveDir = temporaryDirectory;
        synchronized (CACHE_LOCK) {
            if (session == null) session = new SessionManager();
        }
    }

    public void startStream(String url, Callback cb) {
        startStream(url, true, cb);
    }

    public void startStream(String url, boolean cleanupAfterPlayback, Callback uiCallback) {
        final int operationId;
        final String source = url == null ? "" : url.trim();
        final String sessionId = UUID.randomUUID().toString();
        synchronized (CACHE_LOCK) {
            TorrentManager previous = activeInstance;
            if (previous != null && previous != this) previous.destroy();
            destroy();
            operationId = lifecycleGeneration.incrementAndGet();
            autoCleanup = cleanupAfterPlayback;
            temporaryGeneration = cleanupAfterPlayback ? TEMPORARY_OWNERSHIP.claim(temporaryDirectory) : 0;
            callback = uiCallback;
            playbackSessionId = sessionId;
            streamActive = true;
            activeInstance = this;
            readyCalled = false;
            lowStorageStopping = false;
        }
        final Callback cb = new ForwardingCallback(operationId);
        cb.onStatusUpdate("Khoi dong...");

        // Native metadata/add operations are serialized so cancelled workers cannot
        // install an old handle after a replacement stream has started.
        START_EXECUTOR.execute(() -> {
            try {
                if (!isCurrent(operationId)) return;
                if (!session.isRunning()) session.start();
                if (!isCurrent(operationId)) return;
                final File operationDir = cleanupAfterPlayback ? temporaryDirectory
                    : TorrentStorage.sourceDirectory(retainedRoot, source);
                synchronized (CACHE_LOCK) {
                    if (!isCurrent(operationId)) return;
                    saveDir = operationDir;
                    if (cleanupAfterPlayback) {
                        if (!clearCacheWithRetries(operationDir, false, operationId, temporaryGeneration, false)
                                && isCurrent(operationId)) {
                            throw new IOException("Khong don duoc du lieu torrent tam, thu lai sau");
                        }
                    }
                    if (!isCurrent(operationId)) return;
                    if (!operationDir.exists() && !operationDir.mkdirs()) {
                        throw new IOException("Khong tao duoc thu muc torrent");
                    }
                    ensureStorageAvailable();
                    if (!cleanupAfterPlayback) TorrentStorage.rememberSource(operationDir, source);
                }

                TorrentInfo ti;
                File local = TorrentStorage.localSource(source);
                if (local != null) {
                    File f = local;
                    if (!f.exists()) {
                        cb.onError("File khong tim thay");
                        return;
                    }
                    ti = new TorrentInfo(f);
                } else if (source.startsWith("http://") || source.startsWith("https://")) {
                    cb.onStatusUpdate("Dang tai file .torrent...");
                    File torrentFile = new File(operationDir, "remote-" + sessionId + ".torrent");
                    HttpURLConnection conn = (HttpURLConnection) new URL(source).openConnection();
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(30000);
                    conn.setInstanceFollowRedirects(true);
                    try {
                        int responseCode = conn.getResponseCode();
                        if (responseCode < 200 || responseCode >= 300) {
                            throw new IOException("HTTP " + responseCode + " khi tai torrent");
                        }
                        try (InputStream input = conn.getInputStream();
                             OutputStream output = new FileOutputStream(torrentFile)) {
                            byte[] buffer = new byte[8192];
                            int read;
                            while ((read = input.read(buffer)) != -1) {
                                if (!isCurrent(operationId)) return;
                                output.write(buffer, 0, read);
                            }
                        }
                        if (!isCurrent(operationId)) return;
                        ti = new TorrentInfo(torrentFile);
                    } finally {
                        conn.disconnect();
                        torrentFile.delete();
                    }
                } else if (source.startsWith("magnet:")) {
                    String magnet = source;
                    if (!magnet.contains("&tr=")) {
                        magnet += "&tr=udp://tracker.opentrackr.org:1337/announce"
                            + "&tr=udp://open.stealth.si:80/announce"
                            + "&tr=udp://tracker.torrent.eu.org:451/announce"
                            + "&tr=udp://9.rarbg.to:2920/announce"
                            + "&tr=udp://tracker.coppersurfer.tk:6969/announce";
                    }
                    cb.onStatusUpdate("Tim metadata...");
                    byte[] data = session.fetchMagnet(magnet, 30, operationDir);
                    if (!isCurrent(operationId)) return;
                    if (data == null) {
                        cb.onError("Khong tim duoc metadata. Kiem tra ket noi mang");
                        return;
                    }
                    ti = new TorrentInfo(data);
                } else {
                    cb.onError("Link khong hop le");
                    return;
                }

                if (!downloadAndFind(ti, operationDir, operationId)) return;

                List<VideoFileEntry> videos = listVideoFiles(ti);
                if (videos.isEmpty()) {
                    cb.onError("Torrent nay khong chua file video");
                    return;
                }

                if (videos.size() == 1) {
                    selectFileInternal(videos.get(0).index, cb, operationId);
                } else {
                    cb.onFilesFound(videos);
                }

            } catch (Exception e) {
                if (!isCurrent(operationId)) return;
                String msg = e.getMessage() != null ? e.getMessage() : "Loi khong xac dinh";
                cb.onError(msg);
            }
        });
    }

    private boolean downloadAndFind(TorrentInfo info, File directory, int operationId)
            throws Exception {
        // remove() is asynchronous; wait for the previous registration before reusing
        // the same info hash at a different temporary/retained path.
        TorrentHandle previous = session.find(info.infoHash());
        if (previous != null && previous.isValid()) session.remove(previous);
        long removalDeadline = System.currentTimeMillis() + 5000;
        while (session.find(info.infoHash()) != null && System.currentTimeMillis() < removalDeadline) {
            if (!isCurrent(operationId)) return false;
            Thread.sleep(80);
        }
        synchronized (CACHE_LOCK) {
            if (!isCurrent(operationId)) return false;
            if (session.find(info.infoHash()) != null) throw new IOException("Torrent cu chua dung, thu lai sau");
            Priority[] priorities = new Priority[info.files().numFiles()];
            for (int i = 0; i < priorities.length; i++) priorities[i] = Priority.IGNORE;
            session.download(info, directory, null, priorities, null, TorrentFlags.SEQUENTIAL_DOWNLOAD);
        }
        long deadline = System.currentTimeMillis() + 10000;
        TorrentHandle candidate = null;
        while (candidate == null && System.currentTimeMillis() < deadline) {
            candidate = session.find(info.infoHash());
            if (candidate == null) Thread.sleep(100);
        }
        synchronized (CACHE_LOCK) {
            if (!isCurrent(operationId)) {
                if (candidate != null && candidate.isValid()) session.remove(candidate);
                return false;
            }
            if (candidate == null) throw new IOException("Khong bat duoc torrent");
            handle = candidate;
            cachedInfo = info;
            return true;
        }
    }

    public void selectFile(int fileIndex, Callback cb) {
        final int operationId;
        synchronized (CACHE_LOCK) {
            if (cb != null) callback = cb;
            operationId = lifecycleGeneration.get();
        }
        new Thread(() -> selectFileInternal(fileIndex, new ForwardingCallback(operationId), operationId)).start();
    }

    private void selectFileInternal(int fileIndex, Callback cb, int operationId) {
        synchronized (CACHE_LOCK) {
        if (!isCurrent(operationId) || !streamActive) return;
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
            if (!TorrentStorage.isDescendant(selectedVideoFile, saveDir)) {
                throw new IOException("Duong dan video torrent khong hop le");
            }
            readyCalled = false;

            prioritizeFileEnds(fileIndex);
            startProxy(cb, operationId);

        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : "Loi chon file";
            handler.post(() -> cb.onError(msg));
        }
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

    private void startProxy(Callback cb, int operationId) throws Exception {
        if (proxyServer != null && !proxyServer.isClosed()) {
            try { proxyServer.close(); } catch (Exception ignored) {}
        }
        proxyServer = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        proxyPort = proxyServer.getLocalPort();
        proxyRunning = true;
        final ServerSocket server = proxyServer;

        new Thread(() -> {
            while (isCurrent(operationId) && proxyRunning) {
                try {
                    Socket client = server.accept();
                    client.setSoTimeout(60000);
                    new Thread(() -> handleRequest(client, operationId)).start();
                } catch (Exception e) {
                    if (!isCurrent(operationId) || !proxyRunning) break;
                }
            }
        }).start();

        startMonitor(cb, operationId);
    }

    private void handleRequest(Socket socket, int operationId) {
        try (Socket s = socket;
             BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream()));
             OutputStream out = s.getOutputStream()) {

            if (!isCurrent(operationId)) return;
            final TorrentHandle requestHandle = handle;

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
            if (pieceLen > 0 && requestHandle != null && requestHandle.isValid()) {
                long fileOffset = fs.fileOffset(fIdx);
                int startPiece = (int) ((fileOffset + rangeStart) / pieceLen);
                int endPieceOfFile = (int) ((fileOffset + fs.fileSize(fIdx) - 1) / pieceLen);
                int reqEndPiece = Math.min(startPiece + 3, endPieceOfFile);
                for (int i = startPiece; i <= reqEndPiece; i++) {
                    try { requestHandle.piecePriority(i, Priority.TOP_PRIORITY); } catch (Exception ignored) {}
                }
                // Tang thoi gian cho len 3 phut - tranh ngat giua video
                // khi mang cham gay VLC hieu nham la het video
                long deadline = System.currentTimeMillis() + 180000;
                while (System.currentTimeMillis() < deadline && isCurrent(operationId) && proxyRunning) {
                    if (!requestHandle.isValid()) break;
                    try { if (requestHandle.havePiece(startPiece)) break; } catch (Exception ignored) { break; }
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
                while (isCurrent(operationId) && proxyRunning && left > 0) {
                    if (requestHandle == null || !requestHandle.isValid()) break;

                    long positionInFile = finalContentLen - left + finalRangeStart;
                    long absoluteOffset = fs.fileOffset(fIdx) + positionInFile;
                    int piece = (int) (absoluteOffset / pieceLen);
                    if (!waitForPiece(piece, 180000, operationId, requestHandle)) break;

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

    private boolean waitForPiece(int piece, long timeoutMs, int operationId,
                                 TorrentHandle activeHandle) throws InterruptedException {
        if (activeHandle == null || !activeHandle.isValid()) return false;
        try { activeHandle.piecePriority(piece, Priority.TOP_PRIORITY); }
        catch (Exception ignored) {}

        long deadline = System.currentTimeMillis() + timeoutMs;
        while (isCurrent(operationId) && proxyRunning && System.currentTimeMillis() < deadline) {
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

    private void startMonitor(Callback cb, int operationId) {
        if (monitorTimer != null) monitorTimer.cancel();
        monitorTimer = new Timer();
        monitorTimer.scheduleAtFixedRate(new TimerTask() {
            @Override public void run() {
                synchronized (CACHE_LOCK) {
                if (!isCurrent(operationId)) return;
                if (handle == null || !handle.isValid()) return;
                try {
                    TorrentStatus st = handle.status();
                    int pct = (int) (st.progress() * 100);
                    float dlKb = st.downloadRate() / 1024f;
                    int peers = st.numPeers();
                    String state = st.state().toString();

                    if (!lowStorageStopping && saveDir.getUsableSpace() > 0
                            && saveDir.getUsableSpace() < getReservedFreeSpace()) {
                        synchronized (CACHE_LOCK) {
                            if (!isCurrent(operationId)) return;
                            lowStorageStopping = true;
                            String message = autoCleanup
                                ? "Torrent da tu dung va xoa du lieu tam de bao ve bo nho trong"
                                : "Torrent da tu dung de bao ve bo nho trong; du lieu da tai van duoc giu lai";
                            stopForStorage(message);
                        }
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

    public void stop() {
        int stoppedId = stopInternal();
        postCallback(stoppedId, Callback::onStopped);
    }

    private int stopInternal() {
        synchronized (CACHE_LOCK) {
        lifecycleGeneration.incrementAndGet();
        streamActive = false;
        playbackSessionId = null;
        if (activeInstance == this) activeInstance = null;
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
        return lifecycleGeneration.get();
        }
    }

    public void stopAndClearCache() {
        stopAndClearCache(null);
    }

    private void stopAndClearCache(String errorAfterCleanup) {
        final long cleanupGeneration = autoCleanup ? temporaryGeneration
            : TEMPORARY_OWNERSHIP.current(temporaryDirectory);
        final int cleanupId = stopInternal();
        final File cleanupDirectory = temporaryDirectory;
        START_EXECUTOR.execute(() -> {
            boolean cleared = clearCacheWithRetries(cleanupDirectory, true, cleanupId, cleanupGeneration, true);
            synchronized (CACHE_LOCK) {
                if (!TEMPORARY_OWNERSHIP.isCurrent(cleanupDirectory, cleanupGeneration)) return;
            }
            postCallback(cleanupId, target -> {
                if (!cleared) target.onError("Torrent da dung; khong the xoa het du lieu tam");
                else if (errorAfterCleanup != null) target.onError(errorAfterCleanup);
                else target.onStopped();
            });
        });
    }

    private void stopForStorage(String message) {
        if (autoCleanup) {
            stopAndClearCache(message);
        } else {
            int stoppedId = stopInternal();
            postCallback(stoppedId, target -> {
                target.onError(message);
            });
        }
    }

    private boolean isCurrent(int operationId) {
        return lifecycleGeneration.get() == operationId;
    }

    public static void stopActiveAndCleanup(Context context) {
        stopActive(context);
    }

    public static void stopActive(Context context) {
        synchronized (CACHE_LOCK) {
            TorrentManager manager = activeInstance;
            if (manager != null) manager.destroy();
            else cleanupOrphanedCache(context);
        }
    }

    public static void finishPlayback(Context context, String sessionId) {
        if (sessionId == null) return;
        synchronized (CACHE_LOCK) {
            TorrentManager manager = activeInstance;
            if (manager != null && sessionId.equals(manager.playbackSessionId)) manager.destroy();
        }
    }

    public String getPlaybackSessionId() {
        return playbackSessionId;
    }

    public boolean isAutoCleanupEnabled() {
        return autoCleanup;
    }

    public static TorrentManager getActiveManager() {
        synchronized (CACHE_LOCK) {
            return activeInstance != null && activeInstance.streamActive ? activeInstance : null;
        }
    }

    public void destroy() {
        synchronized (CACHE_LOCK) {
            // A manager created only for browsing retained files owns no cache.
            if (playbackSessionId == null) return;
            if (autoCleanup) stopAndClearCache();
            else stop();
        }
    }

    private boolean clearCacheWithRetries(File directory, boolean recreateDirectory, int operationId,
                                          long storageGeneration, boolean stoppedCleanup) {
        boolean cleared = false;
        for (int attempt = 0; attempt < 3; attempt++) {
            synchronized (CACHE_LOCK) {
                if (!canModifyTemporaryDirectory(directory, operationId, storageGeneration, stoppedCleanup)) return false;
                cleared = TorrentStorage.clearTemporaryDirectory(directory, retainedRoot);
                if (cleared) break;
            }
            try { Thread.sleep(150L * (attempt + 1)); }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        synchronized (CACHE_LOCK) {
            if (!canModifyTemporaryDirectory(directory, operationId, storageGeneration, stoppedCleanup)) return false;
            if (recreateDirectory && !directory.exists()) directory.mkdirs();
        }
        return cleared;
    }

    private boolean canModifyTemporaryDirectory(File directory, int operationId,
                                                long storageGeneration, boolean stoppedCleanup) {
        // A retained replacement does not own temporary bytes. Only a newer
        // temporary stream revokes old cleanup, independently of UI generations.
        if ((!stoppedCleanup && !isCurrent(operationId)) || !sameFile(directory, temporaryDirectory)
                || !TEMPORARY_OWNERSHIP.isCurrent(directory, storageGeneration)) return false;
        TorrentManager active = activeInstance;
        return active == null || (!stoppedCleanup && active == this) || !active.streamActive
            || !active.autoCleanup || !sameFile(active.temporaryDirectory, directory);
    }

    public void attachCallback(Callback uiCallback) {
        synchronized (CACHE_LOCK) {
            callback = uiCallback;
            Callback current = new ForwardingCallback(lifecycleGeneration.get());
            if (!streamActive) {
                current.onStopped();
            } else if (cachedInfo != null && selectedFileIndex < 0) {
                current.onFilesFound(listVideoFiles(cachedInfo));
            } else if (readyCalled && proxyRunning) {
                current.onReady("http://127.0.0.1:" + proxyPort + "/stream");
            } else {
                current.onStatusUpdate(lastStatus);
            }
        }
    }

    public void detachCallback(Callback uiCallback) {
        synchronized (CACHE_LOCK) {
            if (callback == uiCallback) callback = null;
        }
    }

    private interface CallbackAction { void run(Callback target); }

    private void postCallback(int operationId, CallbackAction action) {
        handler.post(() -> {
            Callback target;
            synchronized (CACHE_LOCK) {
                if (!isCurrent(operationId)) return;
                target = callback;
            }
            if (target != null) action.run(target);
        });
    }

    private final class ForwardingCallback implements Callback {
        private final int operationId;
        ForwardingCallback(int operationId) { this.operationId = operationId; }
        @Override public void onProgress(int progress, float speed) {
            postCallback(operationId, target -> target.onProgress(progress, speed));
        }
        @Override public void onReady(String url) {
            postCallback(operationId, target -> target.onReady(url));
        }
        @Override public void onError(String error) {
            postCallback(operationId, target -> target.onError(error));
        }
        @Override public void onStopped() { postCallback(operationId, Callback::onStopped); }
        @Override public void onStatusUpdate(String status) {
            if (isCurrent(operationId)) lastStatus = status;
            postCallback(operationId, target -> target.onStatusUpdate(status));
        }
        @Override public void onFilesFound(List<VideoFileEntry> files) {
            List<VideoFileEntry> snapshot = new ArrayList<>(files);
            postCallback(operationId, target -> target.onFilesFound(snapshot));
        }
    }

    public static File getRetainedDirectory(Context context) {
        return new File(context.getFilesDir(), "torrent_downloads");
    }

    public static List<File> getStorageDirectories(Context context) {
        List<File> roots = getTemporaryDirectories(context);
        roots.add(getRetainedDirectory(context));
        return roots;
    }

    private static List<File> getTemporaryDirectories(Context context) {
        List<File> roots = new ArrayList<>();
        roots.add(new File(context.getCacheDir(), "torrent_stream"));
        File[] externalRoots = context.getExternalCacheDirs();
        if (externalRoots != null) {
            for (File external : externalRoots) {
                if (external == null) continue;
                File candidate = new File(external, "torrent_stream");
                boolean duplicate = false;
                for (File root : roots) if (sameFile(root, candidate)) duplicate = true;
                if (!duplicate) roots.add(candidate);
            }
        }
        return roots;
    }

    public static String getResumeSource(Context context, File video) {
        if (video == null) return null;
        try { return TorrentStorage.resumeSource(getRetainedDirectory(context), video); }
        catch (IOException ignored) { return null; }
    }

    public static boolean deleteStoredFile(Context context, File video) {
        if (video == null) return false;
        synchronized (CACHE_LOCK) {
            TorrentManager active = activeInstance;
            try {
                if (active != null && active.streamActive
                        && TorrentStorage.isDescendant(video, active.saveDir)) return false;
            } catch (IOException ignored) { return false; }
            return TorrentStorage.deleteStoredFile(video, getStorageDirectories(context));
        }
    }

    public static String prepareStoredPlayback(Context context, File video) {
        synchronized (CACHE_LOCK) {
            return STORED_PLAYBACK_TOKENS.prepare(video, getStorageDirectories(context));
        }
    }

    public static boolean finishStoredPlayback(Context context, String token) {
        synchronized (CACHE_LOCK) {
            File video = STORED_PLAYBACK_TOKENS.take(token);
            return video != null && deleteStoredFile(context, video);
        }
    }

    public static void discardStoredPlayback(String token) {
        STORED_PLAYBACK_TOKENS.take(token);
    }

    public static void cleanupOrphanedCache(Context context) {
        final Context appContext = context.getApplicationContext();
        START_EXECUTOR.execute(() -> {
            synchronized (CACHE_LOCK) {
                TorrentManager active = activeInstance;
                for (File directory : getTemporaryDirectories(appContext)) {
                    if (active == null || !active.streamActive || !active.autoCleanup
                            || !sameFile(active.temporaryDirectory, directory)) {
                        TorrentStorage.clearTemporaryDirectory(directory, getRetainedDirectory(appContext));
                    }
                }
            }
        });
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
        return TorrentStorage.sameFile(first, second);
    }

    public boolean isStreaming() {
        return handle != null && handle.isValid() && proxyRunning;
    }
}
