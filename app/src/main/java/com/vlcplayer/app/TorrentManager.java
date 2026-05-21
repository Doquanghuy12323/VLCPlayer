package com.vlcplayer.app;

import android.os.Handler;
import android.os.Looper;
import android.content.Context;
import com.frostwire.jlibtorrent.SessionManager;
import com.frostwire.jlibtorrent.TorrentHandle;
import com.frostwire.jlibtorrent.TorrentInfo;
import com.frostwire.jlibtorrent.TorrentStatus;
import com.frostwire.jlibtorrent.FileStorage;
import com.frostwire.jlibtorrent.Sha1Hash;
import com.frostwire.jlibtorrent.Priority;

import java.io.File;
import java.io.RandomAccessFile;
import java.io.OutputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Timer;
import java.util.TimerTask;

public class TorrentManager {

    public interface Callback {
        void onProgress(int progress, float downloadSpeed, float uploadSpeed);
        void onReady(String localHttpUrl);
        void onError(String error);
        void onStopped();
        default void onStatusUpdate(String status) {}
    }

    private static SessionManager sessionManager;
    private TorrentHandle torrentHandle;
    private Timer monitorTimer;
    private ServerSocket proxyServerSocket;
    private boolean isProxyRunning = false;
    private int proxyPort = 0;
    
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

                Sha1Hash sha1 = null;

                if (urlOrPath.startsWith("magnet:") || urlOrPath.startsWith("http")) {
                    String optimizedUrl = urlOrPath;
                    if (optimizedUrl.startsWith("magnet:") && !optimizedUrl.contains("&tr=")) {
                        optimizedUrl += "&tr=udp://tracker.opentrackr.org:1337/announce" +
                                        "&tr=udp://open.stealth.si:80/announce" +
                                        "&tr=udp://tracker.coppersurfer.tk:6969/announce";
                    }

                    if (optimizedUrl.startsWith("magnet:?xt=urn:btih:")) {
                        int start = 20;
                        int end = optimizedUrl.indexOf("&", start);
                        String infoHashHex = (end == -1) ? optimizedUrl.substring(start) : optimizedUrl.substring(start, end);
                        sha1 = new Sha1Hash(infoHashHex.trim());
                    }
                    sessionManager.download(optimizedUrl, saveDir);
                } else {
                    File file = new File(urlOrPath.replace("file://", ""));
                    if (!file.exists()) {
                        handler.post(() -> cb.onError("Tệp tin torrent không tồn tại"));
                        return;
                    }
                    TorrentInfo ti = new TorrentInfo(file);
                    sha1 = ti.infoHash();
                    sessionManager.download(ti, saveDir);
                }

                long startTime = System.currentTimeMillis();
                while (torrentHandle == null && System.currentTimeMillis() - startTime < 8000) {
                    if (sha1 != null) {
                        try {
                            for (java.lang.reflect.Method m : SessionManager.class.getDeclaredMethods()) {
                                if (m.getReturnType() == TorrentHandle.class && m.getParameterTypes().length == 1 && m.getParameterTypes()[0] == Sha1Hash.class) {
                                    m.setAccessible(true);
                                    torrentHandle = (TorrentHandle) m.invoke(sessionManager, sha1);
                                    if (torrentHandle != null) break;
                                }
                            }
                            
                            if (torrentHandle == null) {
                                for (java.lang.reflect.Method m : SessionManager.class.getDeclaredMethods()) {
                                    if (m.getParameterTypes().length == 0 && java.util.List.class.isAssignableFrom(m.getReturnType())) {
                                        m.setAccessible(true);
                                        java.util.List<?> list = (java.util.List<?>) m.invoke(sessionManager);
                                        if (list != null) {
                                            for (Object obj : list) {
                                                if (obj instanceof TorrentHandle) {
                                                    TorrentHandle th = (TorrentHandle) obj;
                                                    if (th.infoHash() != null && th.infoHash().toString().equalsIgnoreCase(sha1.toString())) {
                                                        torrentHandle = th;
                                                        break;
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    if (torrentHandle != null) break;
                                }
                            }
                        } catch (Exception ignored) {}
                    }
                    if (torrentHandle != null) break;
                    Thread.sleep(250);
                }

                if (torrentHandle == null) {
                    handler.post(() -> cb.onError("Không thể ánh xạ luồng Torrent Handle"));
                    return;
                }

                // CẤU HÌNH MẠNG CỰC ĐOAN: Tiêm Tracker và Ép tải tuần tự bằng Reflection
                try {
                    // Ép tải tuần tự
                    for (java.lang.reflect.Method m : TorrentHandle.class.getDeclaredMethods()) {
                        if ((m.getName().toLowerCase().contains("sequential")) && m.getParameterTypes().length == 1 && m.getParameterTypes()[0] == boolean.class) {
                            m.setAccessible(true);
                            m.invoke(torrentHandle, true);
                            break;
                        }
                    }

                    // Bơm dàn Tracker công nghiệp để cấp cứu tình trạng 0 Peers
                    Class<?> aeClass = Class.forName("com.frostwire.jlibtorrent.AnnounceEntry");
                    java.lang.reflect.Constructor<?> aeCtor = aeClass.getConstructor(String.class);
                    java.lang.reflect.Method addTrackerMethod = null;
                    
                    for (java.lang.reflect.Method m : TorrentHandle.class.getDeclaredMethods()) {
                        if (m.getName().equals("addTracker") && m.getParameterTypes().length == 1) {
                            addTrackerMethod = m;
                            addTrackerMethod.setAccessible(true);
                            break;
                        }
                    }

                    if (addTrackerMethod != null) {
                        String[] fallbackTrackers = {
                            "udp://tracker.opentrackr.org:1337/announce",
                            "udp://open.stealth.si:80/announce",
                            "udp://tracker.coppersurfer.tk:6969/announce",
                            "udp://tracker.internetwarriors.net:1337/announce",
                            "udp://exodus.desync.com:6969/announce"
                        };
                        for (String tr : fallbackTrackers) {
                            addTrackerMethod.invoke(torrentHandle, aeCtor.newInstance(tr));
                        }
                    }

                    // Ra lệnh cho nhân C++ đánh thức card mạng, kết nối Tracker ngay lập tức
                    for (java.lang.reflect.Method m : TorrentHandle.class.getDeclaredMethods()) {
                        if (m.getName().equals("resume") || m.getName().equals("forceReannounce")) {
                            m.setAccessible(true);
                            m.invoke(torrentHandle);
                        }
                    }
                } catch (Exception ignored) {}

                startLocalHttpProxy(cb);

            } catch (Exception e) {
                handler.post(() -> cb.onError("Lỗi khởi tạo: " + e.getMessage()));
            }
        }).start();
    }

    private void startLocalHttpProxy(Callback cb) {
        try {
            proxyServerSocket = new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"));
            proxyPort = proxyServerSocket.getLocalPort();
            isProxyRunning = true;

            new Thread(() -> {
                while (isProxyRunning) {
                    try {
                        final Socket clientSocket = proxyServerSocket.accept();
                        new Thread(() -> handleHttpClientRequest(clientSocket)).start();
                    } catch (Exception e) {
                        if (!isProxyRunning) break;
                    }
                }
            }).start();

            String localStreamUrl = "http://127.0.0.1:" + proxyPort + "/stream";
            startMonitoring(localStreamUrl, cb);

        } catch (Exception e) {
            handler.post(() -> cb.onError("Lỗi dựng Proxy nội bộ: " + e.getMessage()));
        }
    }

    private void handleHttpClientRequest(Socket socket) {
        try (Socket s = socket;
             BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream()));
             OutputStream out = s.getOutputStream()) {

            String line = in.readLine();
            if (line == null) return;

            long rangeStart = 0;
            long rangeEnd = -1;

            while ((line = in.readLine()) != null && !line.isEmpty()) {
                if (line.toLowerCase().startsWith("range: bytes=")) {
                    String rangeVal = line.substring(13).trim();
                    int dashIdx = rangeVal.indexOf("-");
                    if (dashIdx != -1) {
                        try {
                            rangeStart = Long.parseLong(rangeVal.substring(0, dashIdx));
                            if (dashIdx + 1 < rangeVal.length()) {
                                rangeEnd = Long.parseLong(rangeVal.substring(dashIdx + 1));
                            }
                        } catch (Exception ignored) {}
                    }
                }
            }

            if (torrentHandle == null || !torrentHandle.status().hasMetadata()) {
                out.write("HTTP/1.1 503 Service Unavailable\r\n\r\n".getBytes("UTF-8"));
                return;
            }

            TorrentInfo ti = torrentHandle.torrentFile();
            FileStorage fs = ti.files();
            int videoIdx = 0;
            long maxBytes = 0;
            for (int i = 0; i < fs.numFiles(); i++) {
                if (fs.fileSize(i) > maxBytes) { maxBytes = fs.fileSize(i); videoIdx = i; }
            }

            File videoFile = new File(saveDir, fs.filePath(videoIdx));
            if (!videoFile.exists()) {
                out.write("HTTP/1.1 404 Not Found\r\n\r\n".getBytes("UTF-8"));
                return;
            }

            long fileLength = videoFile.length();
            if (rangeEnd == -1) rangeEnd = fileLength - 1;
            long contentLength = rangeEnd - rangeStart + 1;

            int pieceSize = ti.pieceLength();
            int startPiece = (int) (rangeStart / pieceSize);
            int endPiece = (int) (rangeEnd / pieceSize);
            for (int i = startPiece; i <= Math.min(endPiece, startPiece + 3); i++) {
                try {
                    for (java.lang.reflect.Method m : TorrentHandle.class.getDeclaredMethods()) {
                        if (m.getName().equals("piecePriority") && m.getParameterTypes().length == 2 && m.getParameterTypes()[0] == int.class) {
                            m.setAccessible(true);
                            if (m.getParameterTypes()[1] == int.class) {
                                m.invoke(torrentHandle, i, 7);
                            } else {
                                m.invoke(torrentHandle, i, Priority.SEVEN);
                            }
                            break;
                        }
                    }
                } catch (Exception ignored) {}
            }

            String responseHeader = "HTTP/1.1 206 Partial Content\r\n" +
                    "Content-Type: video/mp4\r\n" +
                    "Content-Range: bytes " + rangeStart + "-" + rangeEnd + "/" + fileLength + "\r\n" +
                    "Content-Length: " + contentLength + "\r\n" +
                    "Connection: close\r\n\r\n";

            out.write(responseHeader.getBytes("UTF-8"));

            try (RandomAccessFile raf = new RandomAccessFile(videoFile, "r")) {
                raf.seek(rangeStart);
                byte[] buffer = new byte[8192];
                long bytesLeft = contentLength;
                while (isProxyRunning && bytesLeft > 0) {
                    int read = raf.read(buffer, 0, (int) Math.min(buffer.length, bytesLeft));
                    if (read == -1) {
                        Thread.sleep(300);
                        continue;
                    }
                    out.write(buffer, 0, read);
                    bytesLeft -= read;
                }
            }

        } catch (Exception ignored) {}
    }

    private void startMonitoring(String localHttpUrl, Callback cb) {
        monitorTimer = new Timer();
        monitorTimer.scheduleAtFixedRate(new TimerTask() {
            private boolean isReadyCalled = false;

            @Override
            public void run() {
                if (torrentHandle == null || !torrentHandle.isValid()) return;

                TorrentStatus status = torrentHandle.status();
                boolean hasMetadata = status.hasMetadata();
                int progress = (int) (status.progress() * 100);
                float dlSpeed = status.downloadRate() / 1024f;
                float ulSpeed = status.uploadRate() / 1024f;
                int numPeers = status.numPeers();

                handler.post(() -> {
                    if (!hasMetadata) {
                        cb.onStatusUpdate("Đang phân tích cấu trúc tệp (Downloading Metadata)...");
                    } else if (progress == 0 && dlSpeed == 0) {
                        cb.onStatusUpdate("Đang đánh thức mạng P2P (Peers: " + numPeers + ")...");
                    } else {
                        cb.onStatusUpdate("Đã bắt được sóng! Đang tải đệm phát mạng...");
                        cb.onProgress(progress, dlSpeed, ulSpeed);
                    }
                });

                if (hasMetadata && !isReadyCalled) {
                    TorrentInfo ti = torrentHandle.torrentFile();
                    FileStorage fs = ti.files();
                    int videoIdx = 0;
                    long maxBytes = 0;
                    for (int i = 0; i < fs.numFiles(); i++) {
                        if (fs.fileSize(i) > maxBytes) { maxBytes = fs.fileSize(i); videoIdx = i; }
                    }
                    File videoFile = new File(saveDir, fs.filePath(videoIdx));
                    
                    // CHỐNG TREO VLC: Yêu cầu tải đủ 2MB an toàn trước khi bung màn hình phát
                    if (videoFile.exists() && videoFile.length() > 2 * 1024 * 1024) {
                        isReadyCalled = true;
                        handler.post(() -> cb.onReady(localHttpUrl));
                    }
                }
            }
        }, 0, 1000);
    }

    public void stop() {
        isProxyRunning = false;
        if (monitorTimer != null) {
            monitorTimer.cancel();
            monitorTimer = null;
        }
        if (proxyServerSocket != null) {
            try { proxyServerSocket.close(); } catch (Exception ignored) {}
            proxyServerSocket = null;
        }
        if (torrentHandle != null && torrentHandle.isValid()) {
            try { sessionManager.remove(torrentHandle); } catch (Exception ignored) {}
            torrentHandle = null;
        }
    }

    public boolean isStreaming() {
        return torrentHandle != null && torrentHandle.isValid();
    }
}
