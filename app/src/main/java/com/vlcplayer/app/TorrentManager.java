package com.vlcplayer.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import org.libtorrent4j.SessionManager;
import org.libtorrent4j.TorrentHandle;
import org.libtorrent4j.TorrentInfo;
import org.libtorrent4j.TorrentStatus;
import org.libtorrent4j.TorrentFlags;
import org.libtorrent4j.Priority;
import org.libtorrent4j.FileStorage;
import org.libtorrent4j.AlertListener;
import org.libtorrent4j.PieceIndexBitfield;
import org.libtorrent4j.alerts.Alert;
import org.libtorrent4j.alerts.AlertType;
import org.libtorrent4j.alerts.FileErrorAlert;
import org.libtorrent4j.alerts.FilePrioAlert;
import org.libtorrent4j.alerts.TorrentAlert;
import java.io.*;
import java.net.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class TorrentManager {

    private static final long GIB = 1024L * 1024L * 1024L;
    private static final long STARTUP_HEADROOM_BYTES = 128L * 1024L * 1024L;
    private static final long PIECE_WAIT_TIMEOUT_MS = 180000;
    private static final int MAX_PROXY_CLIENTS = 12;
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
    private String streamSource;
    private volatile Callback callback;
    private volatile String lastStatus;
    private volatile String lastError;
    private final Context appContext;
    private Context localizedContext;
    private String localizedLanguage;
    private static final Object CACHE_LOCK = new Object();
    private final AtomicInteger lifecycleGeneration = new AtomicInteger();
    private final AtomicInteger selectionGeneration = new AtomicInteger();
    private volatile TorrentStreamPolicy streamPolicy;
    private final Object proxyClientsLock = new Object();
    private final Set<Socket> proxyClients = new HashSet<>();
    private final Object piecePlanLock = new Object();
    private final Map<Socket, Set<Integer>> requestWindows = new HashMap<>();
    private final Set<Integer> prioritizedPieces = new HashSet<>();
    private final Set<Integer> urgentPieces = new HashSet<>();

    public TorrentManager(Context ctx) {
        appContext = ctx.getApplicationContext();
        lastStatus = text(R.string.torrent_manager_starting);
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
            streamSource = source;
            streamActive = true;
            activeInstance = this;
            readyCalled = false;
            lowStorageStopping = false;
            lastError = null;
        }
        final Callback cb = new ForwardingCallback(operationId);
        cb.onStatusUpdate(text(R.string.torrent_manager_starting));

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
                            throw new UiException(R.string.torrent_manager_temporary_cleanup_failed);
                        }
                    }
                    if (!isCurrent(operationId)) return;
                    if (!operationDir.exists() && !operationDir.mkdirs()) {
                        throw new UiException(R.string.torrent_manager_directory_failed);
                    }
                    ensureStorageAvailable();
                    if (!cleanupAfterPlayback) TorrentStorage.rememberSource(operationDir, source);
                }

                TorrentInfo ti;
                File local = TorrentStorage.localSource(source);
                if (local != null) {
                    File f = local;
                    if (!f.exists()) {
                        cb.onError(text(R.string.torrent_manager_source_missing));
                        return;
                    }
                    ti = new TorrentInfo(f);
                } else if (source.startsWith("http://") || source.startsWith("https://")) {
                    cb.onStatusUpdate(text(R.string.torrent_manager_downloading_metadata_file));
                    File torrentFile = new File(operationDir, "remote-" + sessionId + ".torrent");
                    HttpURLConnection conn = (HttpURLConnection) new URL(source).openConnection();
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(30000);
                    conn.setInstanceFollowRedirects(true);
                    try {
                        int responseCode = conn.getResponseCode();
                        if (responseCode < 200 || responseCode >= 300) {
                            throw new UiException(R.string.torrent_manager_http_error, responseCode);
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
                    cb.onStatusUpdate(text(R.string.torrent_manager_finding_metadata));
                    byte[] data = session.fetchMagnet(magnet, 30, operationDir);
                    if (!isCurrent(operationId)) return;
                    if (data == null) {
                        cb.onError(text(R.string.torrent_manager_metadata_missing));
                        return;
                    }
                    ti = new TorrentInfo(data);
                } else {
                    cb.onError(text(R.string.torrent_manager_invalid_source));
                    return;
                }

                if (!downloadAndFind(ti, operationDir, operationId)) return;

                List<VideoFileEntry> videos = listVideoFiles(ti);
                if (videos.isEmpty()) {
                    cb.onError(text(R.string.torrent_manager_no_video));
                    return;
                }

                if (videos.size() == 1) {
                    selectFileInternal(videos.get(0).index, cb, operationId);
                } else {
                    cb.onFilesFound(videos);
                }

            } catch (Exception e) {
                if (!isCurrent(operationId)) return;
                cb.onError(userError(e, R.string.torrent_manager_start_failed));
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
            if (session.find(info.infoHash()) != null) {
                throw new UiException(R.string.torrent_manager_previous_stopping);
            }
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
            if (candidate == null) throw new UiException(R.string.torrent_manager_handle_missing);
            handle = candidate;
            cachedInfo = info;
            return true;
        }
    }

    public void selectFile(int fileIndex, Callback cb) {
        final int operationId;
        final int selectionId;
        synchronized (CACHE_LOCK) {
            if (cb != null) callback = cb;
            operationId = lifecycleGeneration.get();
            selectionId = selectionGeneration.incrementAndGet();
        }
        START_EXECUTOR.execute(() -> selectFileInternal(fileIndex,
            new ForwardingCallback(operationId), operationId, selectionId));
    }

    private void selectFileInternal(int fileIndex, Callback cb, int operationId) {
        selectFileInternal(fileIndex, cb, operationId, selectionGeneration.incrementAndGet());
    }

    private void selectFileInternal(int fileIndex, Callback cb, int operationId, int selectionId) {
        TorrentHandle selectedHandle = null;
        TorrentInfo info = null;
        try {
        synchronized (CACHE_LOCK) {
            if (!isSelectionCurrent(operationId, selectionId) || !streamActive) return;
            if (handle == null || !handle.isValid() || cachedInfo == null) {
                throw new UiException(R.string.torrent_manager_not_ready);
            }
            ensureStorageAvailable();
            info = cachedInfo;
            FileStorage fs = info.files();
            if (fileIndex < 0 || fileIndex >= fs.numFiles()) {
                throw new UiException(R.string.torrent_manager_invalid_video_path);
            }
            selectedFileIndex = fileIndex;
            selectedVideoFile = new File(saveDir, fs.filePath(fileIndex));
            if (!TorrentStorage.isDescendant(selectedVideoFile, saveDir)) {
                throw new UiException(R.string.torrent_manager_invalid_video_path);
            }
            readyCalled = false;
            streamPolicy = null;
            selectedHandle = handle;
            closeProxy();
            // Keep file-priority changes from briefly enabling a full-file download.
            selectedHandle.unsetFlags(TorrentFlags.AUTO_MANAGED);
        }

        // A nonzero FILE priority makes native storage write the actual video,
        // instead of keeping downloaded pieces in the hidden .parts file.
        // File priorities reset piece priorities asynchronously: wait for the
        // disk acknowledgement before installing our bounded piece windows.
        if (!awaitStorageReady(selectedHandle, operationId, selectionId, cb)) return;
        selectedHandle.pause();
        if (!prepareSelectedFile(selectedHandle, info, fileIndex, operationId, selectionId)) return;
        TorrentStreamPolicy policy = new TorrentStreamPolicy(info.files().fileOffset(fileIndex),
            info.files().fileSize(fileIndex), info.pieceLength());
        synchronized (CACHE_LOCK) {
            if (!isSelectionCurrent(operationId, selectionId) || handle != selectedHandle) return;
            synchronized (piecePlanLock) {
                requestWindows.clear();
                prioritizedPieces.clear();
                urgentPieces.clear();
                Priority[] pieces = new Priority[info.numPieces()];
                java.util.Arrays.fill(pieces, Priority.IGNORE);
                selectedHandle.clearPieceDeadlines();
                selectedHandle.prioritizePieces(pieces);
                streamPolicy = policy;
                applyPiecePlan(operationId, selectionId, selectedHandle);
            }
            selectedHandle.resume();
            startProxy(cb, operationId, selectionId);
        }

        } catch (Exception e) {
            if (!isSelectionCurrent(operationId, selectionId)) return;
            String msg = userError(e, R.string.torrent_manager_selection_failed);
            cb.onError(msg);
        }
    }

    private boolean prepareSelectedFile(TorrentHandle selectedHandle, TorrentInfo info,
                                        int fileIndex, int operationId, int selectionId)
            throws Exception {
        Semaphore completed = new Semaphore(0);
        AtomicReference<String> failure = new AtomicReference<>();
        Priority[] priorities = new Priority[info.files().numFiles()];
        java.util.Arrays.fill(priorities, Priority.IGNORE);
        priorities[fileIndex] = Priority.DEFAULT;
        AlertListener listener = new AlertListener() {
            @Override public int[] types() {
                return new int[] {AlertType.FILE_PRIO.swig(), AlertType.FILE_ERROR.swig()};
            }
            @Override public void alert(Alert<?> alert) {
                // Native alert objects are valid only during this callback.
                if (!isSelectionCurrent(operationId, selectionId) || handle != selectedHandle
                        || !(alert instanceof TorrentAlert)
                        || !selectedHandle.equals(((TorrentAlert<?>) alert).handle())) return;
                if (alert instanceof FileErrorAlert) {
                    failure.set(((FileErrorAlert) alert).error().getMessage());
                    completed.release();
                } else if (alert instanceof FilePrioAlert) {
                    FilePrioAlert applied = (FilePrioAlert) alert;
                    if (applied.error().isError()) failure.set(applied.error().getMessage());
                    completed.release();
                }
            }
        };
        session.addListener(listener);
        try {
            synchronized (CACHE_LOCK) {
                if (!isSelectionCurrent(operationId, selectionId) || handle != selectedHandle) return false;
                selectedHandle.prioritizeFiles(priorities);
            }
            long deadline = SystemClock.elapsedRealtime() + 15000;
            while (isSelectionCurrent(operationId, selectionId) && handle == selectedHandle) {
                if (completed.tryAcquire(80, TimeUnit.MILLISECONDS)) {
                    if (failure.get() != null) {
                        Log.w("TorrentManager", "Selected file priority failed: " + failure.get());
                        throw new UiException(R.string.torrent_manager_stream_storage_failed);
                    }
                    if (!java.util.Arrays.equals(priorities, selectedHandle.filePriorities())) {
                        // An initial all-IGNORE acknowledgement can already be queued.
                        // Only the completed selected-file migration acknowledges this request.
                        continue;
                    }
                    return true;
                }
                if (SystemClock.elapsedRealtime() >= deadline) {
                    throw new UiException(R.string.torrent_manager_selection_failed);
                }
            }
            return false;
        } finally {
            session.removeListener(listener);
        }
    }

    private boolean awaitStorageReady(TorrentHandle selectedHandle, int operationId,
                                      int selectionId, Callback cb) throws Exception {
        selectedHandle.resume();
        long deadline = SystemClock.elapsedRealtime() + PIECE_WAIT_TIMEOUT_MS;
        while (isSelectionCurrent(operationId, selectionId) && handle == selectedHandle) {
            TorrentStatus status = selectedHandle.status(true);
            if (status.errorCode().isError()) {
                Log.w("TorrentManager", "Torrent storage startup failed: " + status.errorCode().getMessage());
                throw new UiException(R.string.torrent_manager_stream_storage_failed);
            }
            TorrentStatus.State state = status.state();
            if (state == TorrentStatus.State.DOWNLOADING || state == TorrentStatus.State.FINISHED
                    || state == TorrentStatus.State.SEEDING) return true;
            cb.onStatusUpdate(localizedState(state));
            if (SystemClock.elapsedRealtime() >= deadline) {
                throw new UiException(R.string.torrent_manager_selection_failed);
            }
            Thread.sleep(100);
        }
        return false;
    }

    private void ensureStorageAvailable() throws IOException {
        long usable = saveDir.getUsableSpace();
        long reserve = getReservedFreeSpace();
        if (usable > 0 && usable < reserve + STARTUP_HEADROOM_BYTES) {
            throw new UiException(R.string.torrent_manager_low_storage, formatBytes(reserve));
        }
    }

    private long getReservedFreeSpace() {
        long proportional = saveDir.getTotalSpace() / 10L;
        return Math.max(2L * GIB, Math.min(8L * GIB, proportional));
    }

    private static String formatBytes(long bytes) {
        return String.format(java.util.Locale.US, "%.1f GB", bytes / (double) GIB);
    }

    /** Called with piecePlanLock. Only changed pieces cross JNI, once per piece boundary. */
    private void applyPiecePlan(int operationId, int selectionId, TorrentHandle selectedHandle) {
        if (!isSelectionCurrent(operationId, selectionId) || handle != selectedHandle
                || streamPolicy == null || !selectedHandle.isValid()) return;
        TreeMap<Integer, Integer> desired = new TreeMap<>();
        Set<Integer> urgent = new HashSet<>();
        if (!readyCalled) {
            int tailFirst = streamPolicy.pieceAt(Math.max(0,
                streamPolicy.fileSize - TorrentStreamPolicy.TAIL_BYTES));
            for (int piece : streamPolicy.startupPieces()) {
                int distance = piece - streamPolicy.firstPiece;
                if (piece >= tailFirst) distance = Math.min(distance, piece - tailFirst);
                desired.put(piece, (int) Math.min(30000L, distance * 25L));
            }
            urgent.add(streamPolicy.firstPiece);
            urgent.add(tailFirst);
        }
        for (Set<Integer> window : requestWindows.values()) {
            int distance = 0;
            for (int piece : window) {
                int deadline = Math.min(30000, distance * 25);
                Integer existing = desired.get(piece);
                if (existing == null || deadline < existing) desired.put(piece, deadline);
                if (distance++ == 0) urgent.add(piece);
            }
        }
        for (int piece : new HashSet<>(prioritizedPieces)) {
            if (!desired.containsKey(piece)) {
                selectedHandle.resetPieceDeadline(piece);
                selectedHandle.piecePriority(piece, Priority.IGNORE);
                prioritizedPieces.remove(piece);
            }
        }
        for (Map.Entry<Integer, Integer> entry : desired.entrySet()) {
            if (prioritizedPieces.add(entry.getKey())) {
                selectedHandle.piecePriority(entry.getKey(), Priority.TOP_PRIORITY);
                selectedHandle.setPieceDeadline(entry.getKey(), entry.getValue());
            }
        }
        for (int piece : urgent) {
            if (!urgentPieces.contains(piece)) selectedHandle.setPieceDeadline(piece, 0);
        }
        urgentPieces.clear();
        urgentPieces.addAll(urgent);
    }

    private void setRequestWindow(Socket socket, TorrentStreamPolicy policy, long position,
                                  long requestEnd, int operationId, int selectionId,
                                  TorrentHandle selectedHandle) {
        synchronized (piecePlanLock) {
            if (!isRequestCurrent(operationId, selectionId, selectedHandle)) return;
            Set<Integer> window = policy.requestPieces(position, requestEnd);
            if (window.equals(requestWindows.get(socket))) return;
            requestWindows.put(socket, window);
            applyPiecePlan(operationId, selectionId, selectedHandle);
        }
    }

    private void removeRequestWindow(Socket socket, int operationId, int selectionId,
                                     TorrentHandle selectedHandle) {
        synchronized (piecePlanLock) {
            if (!isSelectionCurrent(operationId, selectionId) || handle != selectedHandle) return;
            if (requestWindows.remove(socket) != null) {
                applyPiecePlan(operationId, selectionId, selectedHandle);
            }
        }
    }

    private boolean isSelectionCurrent(int operationId, int selectionId) {
        return isCurrent(operationId) && selectionGeneration.get() == selectionId;
    }

    private boolean isRequestCurrent(int operationId, int selectionId, TorrentHandle selectedHandle) {
        return isSelectionCurrent(operationId, selectionId) && proxyRunning && handle == selectedHandle;
    }

    private void closeProxy() {
        proxyRunning = false;
        if (monitorTimer != null) { monitorTimer.cancel(); monitorTimer = null; }
        try { if (proxyServer != null) proxyServer.close(); }
        catch (IOException ignored) {}
        synchronized (proxyClientsLock) {
            for (Socket socket : proxyClients) {
                try { socket.close(); } catch (IOException ignored) {}
            }
            proxyClients.clear();
        }
        synchronized (piecePlanLock) {
            requestWindows.clear();
            prioritizedPieces.clear();
            urgentPieces.clear();
        }
    }

    private void startProxy(Callback cb, int operationId, int selectionId) throws Exception {
        proxyServer = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        proxyPort = proxyServer.getLocalPort();
        proxyRunning = true;
        final ServerSocket server = proxyServer;

        new Thread(() -> {
            while (isSelectionCurrent(operationId, selectionId) && proxyRunning && proxyServer == server) {
                try {
                    Socket client = server.accept();
                    synchronized (proxyClientsLock) {
                        if (!isSelectionCurrent(operationId, selectionId) || !proxyRunning
                                || proxyClients.size() >= MAX_PROXY_CLIENTS) {
                            client.close();
                            continue;
                        }
                        proxyClients.add(client);
                    }
                    client.setSoTimeout(60000);
                    new Thread(() -> handleRequest(client, operationId, selectionId),
                        "TorrentHttpClient").start();
                } catch (Exception e) {
                    if (!isSelectionCurrent(operationId, selectionId) || !proxyRunning
                            || server.isClosed()) break;
                    Log.w("TorrentManager", "Torrent proxy accept failed", e);
                }
            }
        }, "TorrentHttpServer").start();

        startMonitor(cb, operationId, selectionId);
    }

    private void handleRequest(Socket socket, int operationId, int selectionId) {
        TorrentHandle requestHandle = null;
        try (Socket s = socket;
             BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), "US-ASCII"));
             OutputStream out = s.getOutputStream()) {
            final File video;
            final TorrentStreamPolicy policy;
            synchronized (CACHE_LOCK) {
                if (!isSelectionCurrent(operationId, selectionId)) return;
                requestHandle = handle;
                video = selectedVideoFile;
                policy = streamPolicy;
            }
            String requestLine = in.readLine();
            if (requestLine == null) return;
            boolean headOnly = requestLine.startsWith("HEAD ");
            if (!headOnly && !requestLine.startsWith("GET ")) {
                out.write("HTTP/1.1 405 Method Not Allowed\r\nAllow: GET, HEAD\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes("US-ASCII"));
                return;
            }
            String rangeHeader = null;
            int headerSize = 0;
            String line;
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                headerSize += line.length();
                if (headerSize > 16384) return;
                int colon = line.indexOf(':');
                if (colon > 0 && "range".equalsIgnoreCase(line.substring(0, colon).trim())) {
                    // Duplicate/multiple byte ranges are unsupported, never misreported as a single range.
                    String value = line.substring(colon + 1).trim();
                    rangeHeader = rangeHeader == null ? value : rangeHeader + "," + value;
                }
            }
            if (video == null || policy == null
                    || !isRequestCurrent(operationId, selectionId, requestHandle)) {
                writeUnavailable(out);
                return;
            }
            // Validate byte ranges before calculating indexes or touching native piece APIs.
            TorrentHttpRange.Result range = TorrentHttpRange.parse(rangeHeader, policy.fileSize, headOnly);
            if (!range.satisfiable()) {
                String invalid = "HTTP/1.1 416 Range Not Satisfiable\r\n"
                    + "Content-Range: bytes */" + policy.fileSize + "\r\n"
                    + "Content-Length: 0\r\nConnection: close\r\n\r\n";
                out.write(invalid.getBytes("US-ASCII"));
                return;
            }
            String headers = responseHeaders(video, policy.fileSize, range);
            if (headOnly) {
                out.write(headers.getBytes("US-ASCII"));
                return;
            }
            serveVideo(socket, out, video, policy, range, headers, operationId, selectionId, requestHandle);
        } catch (IOException clientClosed) {
            // VLC closes old connections normally when probing, seeking or cancelling playback.
            Log.d("TorrentManager", "Torrent HTTP client disconnected");
        } catch (Exception unexpected) {
            if (isSelectionCurrent(operationId, selectionId)) {
                Log.w("TorrentManager", "Torrent HTTP request failed", unexpected);
            }
        } finally {
            removeRequestWindow(socket, operationId, selectionId, requestHandle);
            synchronized (proxyClientsLock) { proxyClients.remove(socket); }
        }
    }

    private static String responseHeaders(File video, long fileSize, TorrentHttpRange.Result range) {
        String name = video.getName().toLowerCase(java.util.Locale.ROOT);
        String mime = name.endsWith(".mkv") ? "video/x-matroska"
            : name.endsWith(".mp4") || name.endsWith(".mov") ? "video/mp4"
            : name.endsWith(".avi") ? "video/x-msvideo"
            : name.endsWith(".webm") ? "video/webm" : "application/octet-stream";
        String contentRange = range.statusCode == 206
            ? "Content-Range: bytes " + range.start + "-" + range.end + "/" + fileSize + "\r\n" : "";
        return "HTTP/1.1 " + (range.statusCode == 206 ? "206 Partial Content" : "200 OK") + "\r\n"
            + "Content-Type: " + mime + "\r\n" + contentRange
            + "Content-Length: " + range.length + "\r\n"
            + "Accept-Ranges: bytes\r\nConnection: close\r\n\r\n";
    }

    private static void writeUnavailable(OutputStream out) throws IOException {
        out.write("HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\nRetry-After: 2\r\nConnection: close\r\n\r\n".getBytes("US-ASCII"));
    }

    private void serveVideo(Socket socket, OutputStream out, File video, TorrentStreamPolicy policy,
                            TorrentHttpRange.Result range, String headers, int operationId,
                            int selectionId, TorrentHandle requestHandle) throws IOException {
        boolean headersSent = false;
        byte[] buffer = new byte[65536];
        try (VerifiedFileReader reader = new VerifiedFileReader(video, socket, policy, range.end,
                operationId, selectionId, requestHandle)) {
            long position = range.start;
            long remaining = range.length;
            // First verified bytes and the physical file must exist before promising a body.
            int read = reader.read(position, remaining, buffer);
            out.write(headers.getBytes("US-ASCII"));
            headersSent = true;
            while (read > 0) {
                out.write(buffer, 0, read);
                position += read;
                remaining -= read;
                if (remaining == 0) break;
                read = reader.read(position, remaining, buffer);
            }
        } catch (StreamFailure failure) {
            if (!isRequestCurrent(operationId, selectionId, requestHandle) || socket.isClosed()) return;
            Log.w("TorrentManager", "Torrent verified read failed", failure);
            if (!headersSent) writeUnavailable(out);
            // A missing network piece is retryable and can belong to an old seek connection.
            // Only an actual native/physical storage failure invalidates live readiness.
            if (failure.resource == R.string.torrent_manager_stream_storage_failed) {
                new ForwardingCallback(operationId).onError(text(failure.resource));
            }
        } catch (StreamCancelled cancelled) {
            // Closing/replacing the exact session terminates its connections without a playback error.
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private final class VerifiedFileReader implements Closeable {
        private final RandomAccessFile file;
        private final Socket socket;
        private final TorrentStreamPolicy policy;
        private final long requestEnd;
        private final int operationId;
        private final int selectionId;
        private final TorrentHandle requestHandle;
        private int verifiedPiece = -1;

        VerifiedFileReader(File video, Socket socket, TorrentStreamPolicy policy, long requestEnd,
                           int operationId, int selectionId, TorrentHandle requestHandle)
                throws StreamFailure {
            this.socket = socket;
            this.policy = policy;
            this.requestEnd = requestEnd;
            this.operationId = operationId;
            this.selectionId = selectionId;
            this.requestHandle = requestHandle;
            try { file = new RandomAccessFile(video, "r"); }
            catch (IOException failure) {
                throw new StreamFailure(R.string.torrent_manager_stream_storage_failed, failure);
            }
        }

        int read(long position, long remaining, byte[] buffer)
                throws StreamFailure, StreamCancelled, InterruptedException {
            if (!isRequestCurrent(operationId, selectionId, requestHandle) || socket.isClosed()) {
                throw new StreamCancelled();
            }
            int piece = policy.pieceAt(position);
            if (piece != verifiedPiece) {
                setRequestWindow(socket, policy, position, requestEnd, operationId, selectionId, requestHandle);
                long deadline = SystemClock.elapsedRealtime() + PIECE_WAIT_TIMEOUT_MS;
                while (true) {
                    if (!isRequestCurrent(operationId, selectionId, requestHandle) || socket.isClosed()) {
                        throw new StreamCancelled();
                    }
                    try {
                        if (requestHandle.isValid() && requestHandle.havePiece(piece)) break;
                    } catch (Exception failure) {
                        throw new StreamFailure(R.string.torrent_manager_stream_storage_failed, failure);
                    }
                    if (SystemClock.elapsedRealtime() >= deadline) {
                        throw new StreamFailure(R.string.torrent_manager_stream_unavailable, null);
                    }
                    Thread.sleep(80);
                }
                verifiedPiece = piece;
            }
            int length = policy.readLength(position, remaining, buffer.length);
            long deadline = SystemClock.elapsedRealtime() + 15000;
            while (isRequestCurrent(operationId, selectionId, requestHandle) && !socket.isClosed()) {
                try {
                    file.seek(position);
                    int read = file.read(buffer, 0, length);
                    if (read > 0) return read;
                } catch (IOException failure) {
                    throw new StreamFailure(R.string.torrent_manager_stream_storage_failed, failure);
                }
                // A verified piece with a persistent short physical file is a storage error,
                // never send sparse/unverified bytes or spin indefinitely at EOF.
                if (SystemClock.elapsedRealtime() >= deadline) {
                    throw new StreamFailure(R.string.torrent_manager_stream_storage_failed, null);
                }
                Thread.sleep(80);
            }
            throw new StreamCancelled();
        }

        @Override public void close() throws IOException { file.close(); }
    }

    private static final class StreamFailure extends IOException {
        final int resource;
        StreamFailure(int resource, Exception cause) {
            super("Verified torrent read failed", cause);
            this.resource = resource;
        }
    }

    private static final class StreamCancelled extends Exception {
    }

    private void startMonitor(Callback cb, int operationId, int selectionId) {
        if (monitorTimer != null) monitorTimer.cancel();
        monitorTimer = new Timer();
        monitorTimer.scheduleAtFixedRate(new TimerTask() {
            private long verifiedButUnreadableSince;
            @Override public void run() {
                if (!isSelectionCurrent(operationId, selectionId)) return;
                TorrentHandle monitoredHandle = handle;
                TorrentStreamPolicy policy = streamPolicy;
                File video = selectedVideoFile;
                if (monitoredHandle == null || !monitoredHandle.isValid() || lastError != null) return;
                try {
                    TorrentStatus st = !readyCalled && policy != null
                        ? monitoredHandle.status(TorrentHandle.QUERY_PIECES) : monitoredHandle.status(true);
                    if (!isSelectionCurrent(operationId, selectionId) || handle != monitoredHandle) return;
                    if (st.errorCode().isError()) {
                        Log.w("TorrentManager", "Torrent storage failed: " + st.errorCode().getMessage());
                        cb.onError(text(R.string.torrent_manager_stream_storage_failed));
                        return;
                    }
                    int pct = (int) (st.progress() * 100);
                    float dlKb = st.downloadRate() / 1024f;
                    int peers = st.numPeers();
                    String state = localizedState(st.state());

                    if (!lowStorageStopping && saveDir.getUsableSpace() > 0
                            && saveDir.getUsableSpace() < getReservedFreeSpace()) {
                        synchronized (CACHE_LOCK) {
                            if (!isSelectionCurrent(operationId, selectionId)) return;
                            lowStorageStopping = true;
                            String message = text(autoCleanup
                                ? R.string.torrent_manager_stopped_low_storage_deleted
                                : R.string.torrent_manager_stopped_low_storage_kept);
                            stopForStorage(message);
                        }
                        return;
                    }

                    handler.post(() -> {
                        cb.onProgress(pct, dlKb);
                        cb.onStatusUpdate(text(R.string.torrent_manager_progress,
                            state, pct, (int) dlKb, peers));
                    });

                    if (!readyCalled && policy != null) {
                        PieceIndexBitfield available = st.pieces();
                        boolean buffered = policy.ready(
                            piece -> piece < available.size() && available.getBit(piece), true);
                        // QUERY_PIECES includes hash-passed pieces whose disk writes may
                        // still be pending; havePiece is the completed-write gate.
                        buffered = buffered && policy.ready(monitoredHandle::havePiece, true);
                        if (buffered && readableStartupFile(video, policy)) {
                            synchronized (CACHE_LOCK) {
                                if (!isSelectionCurrent(operationId, selectionId)
                                        || handle != monitoredHandle || !proxyRunning || lastError != null) return;
                                readyCalled = true;
                                synchronized (piecePlanLock) {
                                    applyPiecePlan(operationId, selectionId, monitoredHandle);
                                }
                                String url = "http://127.0.0.1:" + proxyPort + "/stream";
                                handler.post(() -> cb.onReady(url));
                            }
                        } else if (buffered) {
                            long now = SystemClock.elapsedRealtime();
                            if (verifiedButUnreadableSince == 0) verifiedButUnreadableSince = now;
                            if (now - verifiedButUnreadableSince >= 15000) {
                                cb.onError(text(R.string.torrent_manager_stream_storage_failed));
                            }
                        } else {
                            verifiedButUnreadableSince = 0;
                        }
                    }
                } catch (Exception failure) {
                    if (isSelectionCurrent(operationId, selectionId)) {
                        Log.w("TorrentManager", "Torrent buffer monitoring failed", failure);
                    }
                }
            }
        }, 500, 1000);
    }

    private static boolean readableStartupFile(File video, TorrentStreamPolicy policy) {
        if (video == null || !video.isFile() || !video.canRead()) return false;
        try (RandomAccessFile file = new RandomAccessFile(video, "r")) {
            if (file.length() < policy.fileSize || file.read() < 0) return false;
            file.seek(policy.fileSize - 1);
            return file.read() >= 0;
        } catch (IOException notReadableYet) {
            return false;
        }
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
        selectionGeneration.incrementAndGet();
        streamActive = false;
        playbackSessionId = null;
        streamSource = null;
        if (activeInstance == this) activeInstance = null;
        closeProxy();
        readyCalled = false;
        lastError = null;

        // Fix: PHAI xoa handle cu, neu khong startStream() se tuong
        // da co handle va bo qua vong lap tim handle torrent moi
        final TorrentHandle oldHandle = handle;
        handle = null;
        cachedInfo = null;
        selectedFileIndex = -1;
        selectedVideoFile = null;
        streamPolicy = null;

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
                if (!cleared) target.onError(text(R.string.torrent_manager_cleanup_incomplete));
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

    /** Validates a pending handoff against the exact live torrent and proxy session. */
    public String getReadyUrl(String expectedSessionId) {
        synchronized (CACHE_LOCK) {
            if (expectedSessionId == null || !expectedSessionId.equals(playbackSessionId)
                    || activeInstance != this || !streamActive || !readyCalled || !proxyRunning
                    || proxyServer == null || proxyServer.isClosed() || lastError != null) return null;
            return "http://127.0.0.1:" + proxyPort + "/stream";
        }
    }

    /** The original source belongs to this live session, including a retained startup error. */
    public String getStreamSource(String expectedSessionId) {
        synchronized (CACHE_LOCK) {
            if (expectedSessionId == null || !expectedSessionId.equals(playbackSessionId)
                    || activeInstance != this || !streamActive) return null;
            return streamSource;
        }
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
            } else if (lastError != null) {
                // A failed startup can happen while a rotating activity has detached.
                // Replaying its error lets the replacement UI offer Retry instead of hanging.
                current.onError(lastError);
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
            synchronized (CACHE_LOCK) {
                if (!isCurrent(operationId)) return;
                lastError = error;
            }
            postCallback(operationId, target -> target.onError(error));
        }
        @Override public void onStopped() { postCallback(operationId, Callback::onStopped); }
        @Override public void onStatusUpdate(String status) {
            synchronized (CACHE_LOCK) {
                if (!isCurrent(operationId)) return;
                lastStatus = status;
            }
            postCallback(operationId, target -> target.onStatusUpdate(status));
        }
        @Override public void onFilesFound(List<VideoFileEntry> files) {
            List<VideoFileEntry> snapshot = new ArrayList<>(files);
            postCallback(operationId, target -> target.onFilesFound(snapshot));
        }
    }

    private synchronized String text(int resource, Object... arguments) {
        String language = AppLanguageManager.getSavedLanguage(appContext);
        if (localizedContext == null || !language.equals(localizedLanguage)) {
            localizedContext = AppLanguageManager.applyLanguage(appContext);
            localizedLanguage = language;
        }
        return arguments.length == 0 ? localizedContext.getString(resource)
            : localizedContext.getString(resource, arguments);
    }

    private String userError(Exception error, int fallbackResource) {
        if (error instanceof UiException) {
            UiException known = (UiException) error;
            return text(known.resource, known.arguments);
        }
        Log.w("TorrentManager", "Torrent operation failed", error);
        return text(fallbackResource);
    }

    private String localizedState(TorrentStatus.State state) {
        if (state == null) return text(R.string.torrent_manager_state_active);
        switch (state) {
            case CHECKING_FILES:
            case CHECKING_RESUME_DATA:
                return text(R.string.torrent_manager_state_checking);
            case DOWNLOADING_METADATA:
                return text(R.string.torrent_manager_state_metadata);
            case DOWNLOADING:
                return text(R.string.torrent_manager_state_downloading);
            case FINISHED:
                return text(R.string.torrent_manager_state_finished);
            case SEEDING:
                return text(R.string.torrent_manager_state_seeding);
            default:
                return text(R.string.torrent_manager_state_active);
        }
    }

    private static final class UiException extends IOException {
        final int resource;
        final Object[] arguments;

        UiException(int resource, Object... arguments) {
            super("Torrent operation: " + resource);
            this.resource = resource;
            this.arguments = arguments;
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
