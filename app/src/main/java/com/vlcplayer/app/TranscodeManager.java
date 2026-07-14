package com.vlcplayer.app;

import android.content.Context;
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
import java.util.Collections;
import java.util.Enumeration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
    private final ExecutorService clients = Executors.newCachedThreadPool();
    private final Context appContext;
    private volatile boolean running;
    private ServerSocket serverSocket;
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
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            for (NetworkInterface network : Collections.list(interfaces)) {
                if (!network.isUp() || network.isLoopback()) continue;
                for (InetAddress address : Collections.list(network.getInetAddresses())) {
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()
                            && !address.isLinkLocalAddress()) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {}
        return "127.0.0.1";
    }

    public synchronized void startServer(Uri videoUri, String displayName,
                                         long knownSize, Callback cb) {
        stopServer();
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

        callback = cb;
        running = true;

        clients.execute(() -> {
            try {
                serverSocket = new ServerSocket(0, 20);
                if (!running) {
                    serverSocket.close();
                    return;
                }
                String lanUrl = "http://" + getLocalIpAddress() + ":"
                    + serverSocket.getLocalPort() + "/" + Uri.encode(sourceName);
                handler.post(() -> cb.onServerStarted(lanUrl));

                while (running) {
                    try {
                        Socket socket = serverSocket.accept();
                        clients.execute(() -> serveClient(
                            socket, videoUri, sourceName, sourceSize, cb));
                    } catch (Exception e) {
                        if (running) handler.post(() -> cb.onError("Loi may chu LAN: " + e.getMessage()));
                    }
                }
            } catch (Exception e) {
                running = false;
                handler.post(() -> cb.onError("Khong mo duoc may chu LAN: " + e.getMessage()));
            }
        });
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
                             long fileLength, Callback cb) {
        handler.post(() -> cb.onClientConnected(socket.getInetAddress().getHostAddress()));
        try (Socket client = socket;
             BufferedReader input = new BufferedReader(
                 new InputStreamReader(client.getInputStream(), "ISO-8859-1"));
             OutputStream output = client.getOutputStream()) {
            client.setSoTimeout(30000);
            String request = input.readLine();
            if (request == null || (!request.startsWith("GET ") && !request.startsWith("HEAD "))) return;
            boolean head = request.startsWith("HEAD ");

            long start = 0;
            long end = fileLength - 1;
            boolean partial = false;
            String line;
            while ((line = input.readLine()) != null && !line.isEmpty()) {
                if (line.regionMatches(true, 0, "Range: bytes=", 0, 13)) {
                    String value = line.substring(13).trim();
                    int dash = value.indexOf('-');
                    if (dash >= 0) {
                        if (dash > 0) start = Long.parseLong(value.substring(0, dash));
                        if (dash + 1 < value.length()) end = Long.parseLong(value.substring(dash + 1));
                        partial = true;
                    }
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
                    while (running && remaining > 0) {
                        int read = stream.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                        if (read < 0) break;
                        output.write(buffer, 0, read);
                        remaining -= read;
                    }
                }
            }
        } catch (Exception e) {
            handler.post(() -> cb.onTranscodeLog("Thiet bi khach da ngat ket noi"));
        }
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
        boolean wasRunning = running;
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}
        serverSocket = null;
        if (wasRunning && callback != null) handler.post(callback::onServerStopped);
    }

    public void destroy() {
        stopServer();
        clients.shutdownNow();
        handler.removeCallbacksAndMessages(null);
    }
}
