package com.vlcplayer.app;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;
import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.channels.FileChannel;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Streams a Storage Access Framework URI directly over LAN without copying it. */
public class TranscodeManager {
    public enum Error {
        NO_VIDEO, SOURCE_UNAVAILABLE, UNKNOWN_SIZE, NO_LAN, INVALID_ADDRESS, SERVER_FAILURE
    }

    public interface Callback {
        void onServerStarted(String lanUrl);
        void onClientConnected(String clientIp);
        void onClientDisconnected();
        void onError(Error error);
        void onServerStopped();
    }

    private static final int MAX_CLIENTS = 4;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor clients = new ThreadPoolExecutor(0, MAX_CLIENTS,
            30, TimeUnit.SECONDS, new SynchronousQueue<>());
    private final ExecutorService preparation = Executors.newCachedThreadPool();
    private final ExecutorService resourceCloser = Executors.newCachedThreadPool();
    private final Context appContext;
    private volatile Session currentSession;
    private long generation;
    private boolean destroyed;

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


    /** Returns immediately; providers, source reads and LAN discovery run on a worker. */
    public void startServer(Uri videoUri, String displayName, long knownSize, Callback callback) {
        final Session session;
        final Session previous;
        synchronized (this) {
            if (destroyed) return;
            previous = currentSession;
            String name = displayName == null || displayName.trim().isEmpty()
                    ? "video" : displayName.trim();
            session = new Session(++generation, videoUri, name, knownSize, callback);
            currentSession = session;
            if (previous != null) previous.cancelled = true;
            session.task = preparation.submit(() -> prepareAndServe(session));
        }
        if (previous != null) closeSession(previous);
    }

    private void prepareAndServe(Session session) {
        if (session.uri == null) {
            failSession(session, Error.NO_VIDEO);
            return;
        }
        final long sourceSize;
        try (SourceHandle source = openSource(session)) {
            ensureCurrent(session);
            long actualSize = source.descriptor.getStatSize();
            sourceSize = actualSize > 0 ? actualSize : session.knownSize;
            // A remembered size must never bypass opening and actually reading the URI.
            if (source.read(new byte[1], 0, 1) < 0) {
                failSession(session, Error.SOURCE_UNAVAILABLE);
                return;
            }
            ensureCurrent(session);
            // Pipes remain supported with sequential skipping for a requested byte range.
            try { source.stream.getChannel().position(0); }
            catch (IOException notSeekable) { /* Fall back to sequential reads per client. */ }
        } catch (Exception unavailable) {
            if (isCurrent(session)) failSession(session, Error.SOURCE_UNAVAILABLE);
            return;
        }
        if (sourceSize <= 0) {
            failSession(session, Error.UNKNOWN_SIZE);
            return;
        }
        if (!isCurrent(session)) return;
        String localIp = getLocalIpAddress();
        if (!isCurrent(session)) return;
        if (localIp == null) {
            failSession(session, Error.NO_LAN);
            return;
        }
        final InetAddress bindAddress;
        try {
            bindAddress = InetAddress.getByName(localIp);
            if (!(bindAddress instanceof Inet4Address) || bindAddress.isLoopbackAddress()
                    || bindAddress.isAnyLocalAddress()) throw new IOException("Invalid LAN address");
        } catch (Exception invalid) {
            failSession(session, Error.INVALID_ADDRESS);
            return;
        }
        String expectedPath = "/stream/" + newSessionToken() + "/video"
                + safeVideoExtension(session.name);
        ServerSocket listener = null;
        try {
            ensureCurrent(session);
            listener = new ServerSocket(0, MAX_CLIENTS, bindAddress);
            synchronized (session) {
                if (!isCurrent(session)) return;
                session.listener = listener;
            }
            String lanUrl = "http://" + localIp + ":" + listener.getLocalPort() + expectedPath;
            handler.post(() -> {
                if (isCurrent(session)) session.callback.onServerStarted(lanUrl);
            });
            while (isCurrent(session)) {
                Socket socket = listener.accept();
                synchronized (session) {
                    if (!isCurrent(session)) {
                        socket.close();
                        break;
                    }
                    session.sockets.add(socket);
                }
                try {
                    clients.execute(() -> serveClient(socket, session, sourceSize, expectedPath));
                } catch (RejectedExecutionException busy) {
                    synchronized (session) { session.sockets.remove(socket); }
                    closeQuietly(socket);
                }
            }
        } catch (Exception serverFailure) {
            if (isCurrent(session)) failSession(session, Error.SERVER_FAILURE);
        } finally {
            closeQuietly(listener);
            synchronized (session) {
                if (session.listener == listener) session.listener = null;
            }
        }
    }

    private boolean isCurrent(Session session) {
        return currentSession == session && !session.cancelled;
    }

    private void ensureCurrent(Session session) throws InterruptedIOException {
        if (!isCurrent(session) || Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("LAN sharing cancelled");
        }
    }

    private SourceHandle openSource(Session session) throws IOException {
        ensureCurrent(session);
        SourceHandle source = new SourceHandle(session);
        synchronized (session) {
            ensureCurrent(session);
            session.sources.add(source);
        }
        try {
            ParcelFileDescriptor descriptor = appContext.getContentResolver()
                    .openFileDescriptor(session.uri, "r", source.cancellation);
            if (descriptor == null) throw new IOException("Cannot open source");
            source.registerDescriptor(descriptor);
            source.registerStream(new FileInputStream(descriptor.getFileDescriptor()));
            ensureCurrent(session);
            return source;
        } catch (Exception failure) {
            source.close();
            if (failure instanceof IOException) throw (IOException) failure;
            throw new IOException("Cannot read source", failure);
        }
    }

    private void failSession(Session session, Error error) {
        final long errorGeneration;
        synchronized (this) {
            if (!isCurrent(session) || destroyed) return;
            session.cancelled = true;
            currentSession = null;
            errorGeneration = ++generation;
        }
        closeSession(session);
        handler.post(() -> {
            synchronized (TranscodeManager.this) {
                if (destroyed || generation != errorGeneration || currentSession != null) return;
                session.callback.onError(error);
            }
        });
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


    private void serveClient(Socket socket, Session session, long fileLength, String expectedPath) {
        boolean connected = false;
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
            if (!isCurrent(session) || !expectedPath.equals(parts[1])) {
                sendEmpty(output, "404 Not Found");
                return;
            }
            boolean head = "HEAD".equals(parts[0]);
            long start = 0;
            long end = fileLength - 1;
            boolean partial = false;
            String line;
            int headerCount = 0;
            while ((line = readLineLimited(input, 8192)) != null && !line.isEmpty()) {
                if (++headerCount > 64) throw new IOException("Too many HTTP headers");
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
                            if (dash + 1 < value.length()) end = Long.parseLong(value.substring(dash + 1));
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
            SourceHandle source;
            try {
                source = openSource(session);
                long actualSize = source.descriptor.getStatSize();
                if (actualSize >= 0 && actualSize != fileLength) {
                    source.close();
                    throw new IOException("Source size changed");
                }
            } catch (Exception unavailable) {
                if (isCurrent(session)) {
                    reportUnavailableSource(session, output);
                }
                return;
            }
            try (SourceHandle openedSource = source) {
                if (!head) {
                    try {
                        FileChannel channel = source.stream.getChannel();
                        try { channel.position(start); }
                        catch (IOException notSeekable) { skipFully(source, start, session); }
                    } catch (IOException unavailable) {
                        if (isCurrent(session)) {
                            reportUnavailableSource(session, output);
                        }
                        return;
                    }
                }
                synchronized (session) {
                    ensureCurrent(session);
                    session.connectedSockets.add(socket);
                    connected = true;
                }
                publishClientStatus(session, socket);
                StringBuilder header = new StringBuilder(partial
                        ? "HTTP/1.1 206 Partial Content\r\n" : "HTTP/1.1 200 OK\r\n");
                header.append("Content-Type: ").append(guessMime(session.name)).append("\r\n")
                        .append("Accept-Ranges: bytes\r\n")
                        .append("Content-Length: ").append(length).append("\r\n");
                if (partial) header.append("Content-Range: bytes ").append(start)
                        .append('-').append(end).append('/').append(fileLength).append("\r\n");
                header.append("Connection: close\r\n\r\n");
                output.write(header.toString().getBytes("UTF-8"));
                if (head) return;
                byte[] buffer = new byte[64 * 1024];
                long remaining = length;
                while (isCurrent(session) && remaining > 0) {
                    final int count;
                    try {
                        count = source.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                        if (count < 0) throw new IOException("Source ended before expected length");
                    } catch (IOException unavailable) {
                        if (isCurrent(session)) failSession(session, Error.SOURCE_UNAVAILABLE);
                        return;
                    }
                    output.write(buffer, 0, count);
                    remaining -= count;
                }
            }
        } catch (Exception disconnected) {
            // A closed client socket is independent of whether the source is still available.
        } finally {
            synchronized (session) {
                session.sockets.remove(socket);
                if (connected) session.connectedSockets.remove(socket);
            }
            if (connected) publishClientStatus(session, null);
        }
    }

    private void publishClientStatus(Session session, Socket preferred) {
        handler.post(() -> {
            synchronized (session) {
                if (!isCurrent(session)) return;
                // Inspect the live set at delivery; queued HEAD/GET completion must not
                // clear a newer request or display an IP whose request has already ended.
                if (session.connectedSockets.isEmpty()) {
                    session.callback.onClientDisconnected();
                } else {
                    Socket active = session.connectedSockets.contains(preferred)
                            ? preferred : session.connectedSockets.iterator().next();
                    session.callback.onClientConnected(active.getInetAddress().getHostAddress());
                }
            }
        });
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


    private void skipFully(SourceHandle source, long bytes, Session session) throws IOException {
        long remaining = bytes;
        byte[] discard = new byte[64 * 1024];
        while (remaining > 0) {
            ensureCurrent(session);
            int count = source.read(discard, 0, (int) Math.min(discard.length, remaining));
            if (count < 0) throw new IOException("Cannot seek source");
            remaining -= count;
        }
    }

    private void reportUnavailableSource(Session session, OutputStream output) {
        try { sendEmpty(output, "503 Service Unavailable"); }
        catch (IOException disconnected) { /* Source failure still stops the share. */ }
        failSession(session, Error.SOURCE_UNAVAILABLE);
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


    public void stopServer() {
        final Session stopped;
        final long stoppedGeneration;
        synchronized (this) {
            stopped = currentSession;
            currentSession = null;
            stoppedGeneration = ++generation;
            if (stopped != null) stopped.cancelled = true;
        }
        if (stopped == null) return;
        closeSession(stopped);
        handler.post(() -> {
            synchronized (TranscodeManager.this) {
                if (destroyed || generation != stoppedGeneration || currentSession != null) return;
                stopped.callback.onServerStopped();
            }
        });
    }

    /** Invalidates before scheduling IO closure, so old work cannot publish into a new session. */
    private void closeSession(Session session) {
        final ServerSocket listener;
        final List<Socket> sockets;
        final List<SourceHandle> sources;
        synchronized (session) {
            session.cancelled = true;
            listener = session.listener;
            session.listener = null;
            sockets = new ArrayList<>(session.sockets);
            sources = new ArrayList<>(session.sources);
        }
        if (session.task != null) session.task.cancel(true);
        if (listener != null) closeOffMain(() -> closeQuietly(listener));
        for (Socket socket : sockets) closeOffMain(() -> closeQuietly(socket));
        for (SourceHandle source : sources) closeOffMain(source::close);
    }

    private void closeOffMain(Runnable close) {
        try {
            resourceCloser.execute(close);
        } catch (RejectedExecutionException alreadyDestroyed) {
            // An error worker can finish just after destroy shuts down the closer pool.
            new Thread(close, "vlc-lan-close").start();
        }
    }

    private static void closeQuietly(Closeable resource) {
        if (resource == null) return;
        try { resource.close(); } catch (Exception ignored) {}
    }

    public void destroy() {
        final Session stopped;
        synchronized (this) {
            if (destroyed) return;
            destroyed = true;
            generation++;
            stopped = currentSession;
            currentSession = null;
            if (stopped != null) stopped.cancelled = true;
        }
        if (stopped != null) closeSession(stopped);
        preparation.shutdownNow();
        clients.shutdownNow();
        resourceCloser.shutdown();
        handler.removeCallbacksAndMessages(null);
    }

    private static final class Session {
        final long generation;
        final Uri uri;
        final String name;
        final long knownSize;
        final Callback callback;
        final Set<Socket> sockets = new HashSet<>();
        final Set<SourceHandle> sources = new HashSet<>();
        final Set<Socket> connectedSockets = new HashSet<>();
        volatile boolean cancelled;
        volatile Future<?> task;
        ServerSocket listener;

        Session(long generation, Uri uri, String name, long knownSize, Callback callback) {
            this.generation = generation;
            this.uri = uri;
            this.name = name;
            this.knownSize = knownSize;
            this.callback = callback;
        }
    }

    private static final class SourceHandle implements Closeable {
        final Session session;
        final CancellationSignal cancellation = new CancellationSignal();
        volatile ParcelFileDescriptor descriptor;
        volatile FileInputStream stream;
        private volatile boolean closed;

        SourceHandle(Session session) { this.session = session; }

        void registerDescriptor(ParcelFileDescriptor value) throws IOException {
            synchronized (this) {
                if (!closed && !session.cancelled) {
                    descriptor = value;
                    return;
                }
            }
            closeQuietly(value);
            throw new InterruptedIOException("LAN source cancelled");
        }

        void registerStream(FileInputStream value) throws IOException {
            synchronized (this) {
                if (!closed && !session.cancelled) {
                    stream = value;
                    return;
                }
            }
            closeQuietly(value);
            throw new InterruptedIOException("LAN source cancelled");
        }

        int read(byte[] buffer, int offset, int count) throws IOException {
            StructPollfd poll = new StructPollfd();
            poll.fd = descriptor.getFileDescriptor();
            poll.events = (short) OsConstants.POLLIN;
            StructPollfd[] descriptors = new StructPollfd[]{poll};
            while (true) {
                checkCancelled();
                try {
                    // Android 5–9 descriptor close does not necessarily interrupt a
                    // native pipe read. Bound the wait before this handle's sole reader.
                    int ready = Os.poll(descriptors, 250);
                    checkCancelled();
                    if (ready == 0) continue;
                    if ((poll.revents & (OsConstants.POLLERR | OsConstants.POLLNVAL)) != 0) {
                        throw new IOException("Source descriptor unavailable");
                    }
                    if ((poll.revents & (OsConstants.POLLIN | OsConstants.POLLHUP)) == 0) continue;
                    int result = Os.read(poll.fd, buffer, offset, count);
                    return result == 0 ? -1 : result;
                } catch (ErrnoException error) {
                    checkCancelled();
                    if (error.errno == OsConstants.EINTR || error.errno == OsConstants.EAGAIN) continue;
                    throw new IOException("Cannot read source", error);
                }
            }
        }

        private void checkCancelled() throws InterruptedIOException {
            if (closed || session.cancelled || Thread.currentThread().isInterrupted()) {
                throw new InterruptedIOException("LAN source cancelled");
            }
        }

        @Override public void close() {
            ParcelFileDescriptor oldDescriptor;
            FileInputStream oldStream;
            synchronized (this) {
                if (closed) return;
                closed = true;
                oldDescriptor = descriptor;
                oldStream = stream;
            }
            // Poll-based reads also observe cancellation on Android versions whose
            // descriptor close does not signal a thread blocked in native IO.
            closeQuietly(oldDescriptor);
            closeQuietly(oldStream);
            try { cancellation.cancel(); } catch (Exception ignored) {}
            synchronized (session) { session.sources.remove(this); }
        }
    }
}
