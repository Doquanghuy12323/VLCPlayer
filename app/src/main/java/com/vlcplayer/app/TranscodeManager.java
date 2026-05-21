package com.vlcplayer.app;

import android.content.Context;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

public class TranscodeManager {

    public interface Callback {
        void onServerStarted(String lanUrl);
        void onClientConnected(String clientIp);
        void onTranscodeLog(String logLine);
        void onError(String error);
        void onServerStopped();
    }

    private ServerSocket serverSocket;
    private boolean isRunning = false;
    private Process ffmpegProcess;
    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int boundPort = 0;

    public TranscodeManager(Context context) {
        this.context = context.getApplicationContext();
    }

    public String getLocalIpAddress() {
        try {
            WifiManager wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            if (wifiManager != null && wifiManager.isWifiEnabled()) {
                WifiInfo wifiInfo = wifiManager.getConnectionInfo();
                int ip = wifiInfo.getIpAddress();
                if (ip != 0) {
                    return String.format("%d.%d.%d.%d",
                            (ip & 0xff),
                            (ip >> 8 & 0xff),
                            (ip >> 16 & 0xff),
                            (ip >> 24 & 0xff));
                }
            }
        } catch (Exception ignored) {}
        return "127.0.0.1";
    }

    public void startServer(String videoPath, Callback cb) {
        stopServer();
        isRunning = true;

        File file = new File(videoPath.replace("file://", ""));
        if (!file.exists()) {
            cb.onError("Tệp tin video không tồn tại trên bộ nhớ máy!");
            return;
        }

        new Thread(() -> {
            try {
                serverSocket = new ServerSocket(0, 5, InetAddress.getByName("0.0.0.0"));
                boundPort = serverSocket.getLocalPort();
                
                String lanUrl = "http://" + getLocalIpAddress() + ":" + boundPort + "/live.ts";
                handler.post(() -> cb.onServerStarted(lanUrl));

                while (isRunning) {
                    try {
                        Socket clientSocket = serverSocket.accept();
                        handler.post(() -> cb.onClientConnected(clientSocket.getInetAddress().getHostAddress()));
                        new Thread(() -> pipeTranscodeToClient(clientSocket, file, cb)).start();
                    } catch (Exception e) {
                        if (!isRunning) break;
                    }
                }
            } catch (Exception e) {
                handler.post(() -> cb.onError("Lỗi khởi chạy Server: " + e.getMessage()));
            }
        }).start();
    }

    private void pipeTranscodeToClient(Socket socket, File videoFile, Callback cb) {
        try (Socket client = socket;
             BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
             OutputStream out = s = client.getOutputStream()) {

            String requestLine = in.readLine();
            if (requestLine == null) return;

            String httpHeader = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: video/mp2t\r\n" +
                    "Connection: close\r\n" +
                    "Accept-Ranges: none\r\n\r\n";
            out.write(httpHeader.getBytes("UTF-8"));

            List<String> cmd = new ArrayList<>();
            cmd.add("ffmpeg");
            cmd.add("-hwaccel"); cmd.add("mediacodec");
            cmd.add("-i"); cmd.add(videoFile.getAbsolutePath());
            cmd.add("-c:v"); cmd.add("h264_mediacodec");
            cmd.add("-b:v"); cmd.add("2200k");
            cmd.add("-vf"); cmd.add("scale=-2:720");
            cmd.add("-c:a"); cmd.add("aac");
            cmd.add("-b:a"); cmd.add("128k");
            cmd.add("-f"); cmd.add("mpegts");
            cmd.add("pipe:1");

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            ffmpegProcess = pb.start();

            InputStream ffmpegOutputStream = ffmpegProcess.getInputStream();
            byte[] buffer = new byte[16384];
            int read;

            while (isRunning && (read = ffmpegOutputStream.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }

        } catch (Exception e) {
            handler.post(() -> cb.onTranscodeLog("Ngắt kết nối luồng phát sóng."));
        } finally {
            killFFmpegProcess();
        }
    }

    private void killFFmpegProcess() {
        if (ffmpegProcess != null) {
            try {
                ffmpegProcess.destroy();
            } catch (Exception ignored) {}
            ffmpegProcess = null;
        }
    }

    public void stopServer() {
        isRunning = false;
        killFFmpegProcess();
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (Exception ignored) {}
            serverSocket = null;
        }
    }
}
