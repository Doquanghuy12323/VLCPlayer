package com.vlcplayer.app;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.channels.FileChannel;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Streams a Storage Access Framework URI directly over LAN without copying it. */
public class TranscodeManager {

    public interface Callback {
        void onServerStarted(String lanUrl);
        void onClientConnected(String clientIp);
        void onTranscodeLog(String logLine);
        void onError(String error);
        void onServerStopped();
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private static final int MAX_CLIENTS = 4;
    private final ThreadPoolExecutor clients = new ThreadPoolExecutor(0, MAX_CLIENTS,
        30, TimeUnit.SECONDS, new SynchronousQueue<>());
    private final Set<Socket> activeSockets = Collections.synchronizedSet(new HashSet<>());
    private final Context appContext;
    private volatile boolean running;
    private volatile ServerSocket serverSocket;
    private volatile long sessionId;
    private Callback callback;

    public TranscodeManager(Context context) {
        appContext = context.getApplicationContext();
    }

    public static void cleanupLegacyCache(Context context) {
        new Thread(() -> {
            cleanupLegacyCacheDirectory(context.getCacheDir());
            File[] externalRoots = context.getExternalCacheDirs();
            if (externalRoots != null) {
                for (File external : externalRoots) {
                    if (external != null) cleanupLegacyCacheDirectory(external);
                }
            }
        }).start();
    }

    private static void cleanupLegacyCacheDirectory(File cacheDir) {
        File[] files = cacheDir.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isFile() && file.getName().startsWith("cast_")) {
                try { file.delete(); } catch (Exception ignored) {}
            }
        }
    }

    public String getLocalIpAddress() {
        try {
            ConnectivityManager manager = (ConnectivityManager)
                appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (manager != null) {
                // Only advertise an address on a local network, never a cellular interface.
                for (Network network : manager.getAllNetworks()) {
                    NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
                    if (capabilities == null || (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                            && !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) continue;
                    LinkProperties properties = manager.getLinkProperties(network);
                    if (properties == null) continue;
                    for (LinkAddress link : properties.getLinkAddresses()) {
                        InetAddress address = link.getAddress();
                        if (address instanceof Inet4Address && !address.isLoopbackAddress()
                                && !address.isLinkLocalAddress()) return address.getHostAddress();
                    }
                }
            }
            // Hotspot interfaces are not always listed as a ConnectivityManager network.
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            for (NetworkInterface network : Collections.list(interfaces)) {
                if (!network.isUp() || network.isLoopback()) continue;
                String name = network.getName().toLowerCase(java.util.Locale.US);
                if (!(name.startsWith("wlan") || name.startsWith("swlan")
                        || name.startsWith("ap") || name.startsWith("eth"))) continue;
                for (InetAddress address : Collections.list(network.getInetAddresses())) {
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()
                            && !address.isLinkLocalAddress()) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    public synchronized void startServer(Uri videoUri, String displayName,
                                         long knownSize, Callback cb) {
        stopServerInternal(false);
        if (videoUri == null) {
            cb.onError("Chua chon video");
            return;
        }

        long sourceSize = probeSize(videoUri, knownSize);
        if (sourceSize <= 0) {
            cb.onError("Khong xac dinh duoc dung luong video");
            return;
        }
        String sourceName = displayName == null || displayName.trim().isEmpty()
            ? "video" : displayName.trim();

        String localIp = getLocalIpAddress();
        if (localIp == null) {
            cb.onError("Khong tim thay dia chi Wi-Fi/LAN");
            return;
        }
        final InetAddress bindAddress;
        try {
            bindAddress = InetAddress.getByName(localIp);
        } catch (Exception e) {
            cb.onError("Dia chi LAN khong hop le");
            return;
        }
        // An ASCII path survives URL normalization by players; the real file name
        // stays local and is used only to select the response Content-Type.
        String expectedPath = "/stream/" + newSessionToken() + "/video"
            + safeVideoExtension(sourceName);
        callback = cb;
        running = true;
        long currentSession = ++sessionId;

        new Thread(() -> {
            ServerSocket listener = null;
            try {
                listener = new ServerSocket(0, MAX_CLIENTS, bindAddress);
                synchronized (this) {
                    if (!isSessionCurrent(currentSession)) return;
                    serverSocket = listener;
                }
                String lanUrl = "http://" + localIp + ":"
                    + listener.getLocalPort() + expectedPath;
                handler.post(() -> {
                    if (isSessionCurrent(currentSession)) cb.onServerStarted(lanUrl);
                });

                while (isSessionCurrent(currentSession)) {
                    try {
                        Socket socket = listener.accept();
                        if (!isSessionCurrent(currentSession)) {
                            socket.close();
                            break;
                        }
                        activeSockets.add(socket);
                        try {
                            clients.execute(() -> {
                                try {
                                    serveClient(socket, videoUri, sourceName, sourceSize,
                                        expectedPath, currentSession, cb);
                                } finally {
                                    activeSockets.remove(socket);
                                }
                            });
                        } catch (RejectedExecutionException busy) {
                            activeSockets.remove(socket);
                            try { socket.close(); } catch (Exception ignored) {}
                        }
                    } catch (Exception e) {
                        if (isSessionCurrent(currentSession)) {
                            running = false;
                            handler.post(() -> {
                                if (sessionId == currentSession)
                                    cb.onError("Loi may chu LAN: " + e.getMessage());
                            });
                        }
                        break;
                    }
                }
            } catch (Exception e) {
                if (isSessionCurrent(currentSession)) {
                    running = false;
                    handler.post(() -> cb.onError("Khong mo duoc may chu LAN: " + e.getMessage()));
                }
            } finally {
                try { if (listener != null) listener.close(); } catch (Exception ignored) {}
                synchronized (this) {
                    if (serverSocket == listener) serverSocket = null;
                }
            }
        }, "vlc-lan-server").start();
    }

    private boolean isSessionCurrent(long id) {
        return running && sessionId == id;
    }

    private String newSessionToken() {
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        StringBuilder token = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            token.append(Character.forDigit((value >>> 4) & 15, 16));
            token.append(Character.forDigit(value & 15, 16));
        }
        return token.toString();
    }

    private String safeVideoExtension(String name) {
        String lower = name.toLowerCase(java.util.Locale.US);
        for (String extension : new String[]{".mp4", ".mkv", ".webm", ".avi", ".mov"}) {
            if (lower.endsWith(extension)) return extension;
        }
        return "";
    }

    private long probeSize(Uri uri, long knownSize) {
        if (knownSize > 0) return knownSize;
        try (ParcelFileDescriptor descriptor =
                 appContext.getContentResolver().openFileDescriptor(uri, "r")) {
            return descriptor == null ? -1 : descriptor.getStatSize();
        } catch (Exception ignored) {
            return -1;
        }
    }

    private void serveClient(Socket socket, Uri videoUri, String displayName,
                             long fileLength, String expectedPath, long currentSession,
                             Callback cb) {
        try (Socket client = socket;
             BufferedReader input = new BufferedReader(
                 new InputStreamReader(client.getInputStream(), "ISO-8859-1"));
             OutputStream output = client.getOutputStream()) {
            client.setSoTimeout(30000);
            String request = readLineLimited(input, 4096);
            if (request == null) return;
            String[] parts = request.split(" ", 3);
            if (parts.length != 3 || !("GET".equals(parts[0]) || "HEAD".equals(parts[0]))
                    || !("HTTP/1.1".equals(parts[2]) || "HTTP/1.0".equals(parts[2]))) {
                sendEmpty(output, "400 Bad Request");
                return;
            }
            if (!isSessionCurrent(currentSession) || !expectedPath.equals(parts[1])) {
                sendEmpty(output, "404 Not Found");
                return;
            }
            boolean head = "HEAD".equals(parts[0]);
            handler.post(() -> {
                if (isSessionCurrent(currentSession))
                    cb.onClientConnected(socket.getInetAddress().getHostAddress());
            });

            long start = 0;
            long end = fileLength - 1;
            boolean partial = false;
            String line;
            int headerCount = 0;
            while ((line = readLineLimited(input, 8192)) != null && !line.isEmpty()) {
                if (++headerCount > 64) throw new java.io.IOException("Too many HTTP headers");
                if (line.regionMatches(true, 0, "Range: bytes=", 0, 13)) {
                    String value = line.substring(13).trim();
                    int dash = value.indexOf('-');
                    if (dash < 0 || value.indexOf(',', dash) >= 0) {
                        start = fileLength;
                        break;
                    }
                    try {
                        if (dash == 0) {
                            long suffix = Long.parseLong(value.substring(1));
                            if (suffix <= 0) throw new NumberFormatException();
                            start = Math.max(0, fileLength - suffix);
                        } else {
                            start = Long.parseLong(value.substring(0, dash));
                            if (dash + 1 < value.length())
                                end = Long.parseLong(value.substring(dash + 1));
                        }
                    } catch (NumberFormatException invalidRange) {
                        start = fileLength;
                        break;
                    }
                    partial = true;
                }
            }

            if (start < 0 || start >= fileLength || end < start) {
                output.write(("HTTP/1.1 416 Range Not Satisfiable\r\n"
                    + "Content-Range: bytes */" + fileLength + "\r\n"
                    + "Content-Length: 0\r\n\r\n").getBytes("UTF-8"));
                return;
            }
            end = Math.min(end, fileLength - 1);
            long length = end - start + 1;
            String mime = guessMime(displayName);
            StringBuilder header = new StringBuilder(partial
                ? "HTTP/1.1 206 Partial Content\r\n" : "HTTP/1.1 200 OK\r\n");
            header.append("Content-Type: ").append(mime).append("\r\n")
                .append("Accept-Ranges: bytes\r\n")
                .append("Content-Length: ").append(length).append("\r\n");
            if (partial) header.append("Content-Range: bytes ").append(start)
                .append('-').append(end).append('/').append(fileLength).append("\r\n");
            header.append("Connection: close\r\n\r\n");
            output.write(header.toString().getBytes("UTF-8"));
            if (head) return;

            try (ParcelFileDescriptor descriptor =
                     appContext.getContentResolver().openFileDescriptor(videoUri, "r")) {
                if (descriptor == null) throw new java.io.IOException("Khong mo duoc video");
                try (FileInputStream stream = new FileInputStream(descriptor.getFileDescriptor())) {
                    FileChannel channel = stream.getChannel();
                    try {
                        channel.position(start);
                    } catch (Exception notSeekable) {
                        skipFully(stream, start);
                    }
                    byte[] buffer = new byte[64 * 1024];
                    long remaining = length;
                    while (isSessionCurrent(currentSession) && remaining > 0) {
                        int read = stream.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                        if (read < 0) break;
                        output.write(buffer, 0, read);
                        remaining -= read;
                    }
                }
            }
        } catch (Exception e) {
            if (isSessionCurrent(currentSession)) {
                handler.post(() -> {
                    if (isSessionCurrent(currentSession))
                        cb.onTranscodeLog("Thiet bi khach da ngat ket noi");
                });
            }
        }
    }

    private String readLineLimited(BufferedReader input, int limit) throws java.io.IOException {
        StringBuilder line = new StringBuilder();
        int next;
        while ((next = input.read()) != -1) {
            if (next == '\n') return line.toString();
            if (next != '\r') line.append((char) next);
            if (line.length() > limit) throw new java.io.IOException("HTTP line too long");
        }
        return line.length() == 0 ? null : line.toString();
    }

    private void sendEmpty(OutputStream output, String status) throws java.io.IOException {
        output.write(("HTTP/1.1 " + status + "\r\nContent-Length: 0\r\n"
            + "Connection: close\r\n\r\n").getBytes("ISO-8859-1"));
    }

    private void skipFully(FileInputStream stream, long bytes) throws java.io.IOException {
        long remaining = bytes;
        byte[] discard = new byte[64 * 1024];
        while (remaining > 0) {
            long skipped = stream.skip(remaining);
            if (skipped > 0) {
                remaining -= skipped;
                continue;
            }
            int read = stream.read(discard, 0, (int) Math.min(discard.length, remaining));
            if (read < 0) throw new java.io.IOException("Khong the tua video nguon");
            remaining -= read;
        }
    }

    private String guessMime(String name) {
        String lower = name.toLowerCase(java.util.Locale.US);
        if (lower.endsWith(".mp4")) return "video/mp4";
        if (lower.endsWith(".mkv")) return "video/x-matroska";
        if (lower.endsWith(".webm")) return "video/webm";
        if (lower.endsWith(".avi")) return "video/x-msvideo";
        if (lower.endsWith(".mov")) return "video/quicktime";
        return "application/octet-stream";
    }

    public synchronized void stopServer() {
        stopServerInternal(true);
    }

    private synchronized void stopServerInternal(boolean notify) {
        boolean wasRunning = running;
        running = false;
        sessionId++;
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}
        serverSocket = null;
        synchronized (activeSockets) {
            for (Socket socket : activeSockets) {
                try { socket.close(); } catch (Exception ignored) {}
            }
            activeSockets.clear();
        }
        Callback oldCallback = callback;
        if (notify && wasRunning && oldCallback != null) handler.post(oldCallback::onServerStopped);
    }

    public void destroy() {
        stopServer();
        clients.shutdownNow();
        handler.removeCallbacksAndMessages(null);
    }
}
