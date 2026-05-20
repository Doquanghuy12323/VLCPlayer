package com.vlcplayer.app;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

public class TorrentActivity extends AppCompatActivity {

    private EditText etMagnet;
    private ImageButton btnPickFile;
    private Button btnStream, btnStop;
    private ProgressBar progressBar;
    private TextView tvStatus, tvSpeed;
    private RecyclerView rvDownloaded;
    private TorrentManager torrentManager;
    private DownloadedAdapter adapter;
    
    // Server Socket thuần Android bảo mật, tương thích hoàn toàn với GitHub Actions
    private ServerSocket localServerSocket;
    private boolean isServerRunning = false;
    private int localServerPort = 0;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(AppLanguageManager.applyLanguage(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_torrent);

        etMagnet   = findViewById(R.id.et_magnet);
        btnPickFile = findViewById(R.id.btn_pick_file);
        btnStream  = findViewById(R.id.btn_stream);
        btnStop    = findViewById(R.id.btn_stop);
        progressBar = findViewById(R.id.progress_bar);
        tvStatus   = findViewById(R.id.tv_status);
        tvSpeed    = findViewById(R.id.tv_speed);
        rvDownloaded = findViewById(R.id.rv_downloaded);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

        torrentManager = new TorrentManager(this);

        rvDownloaded.setLayoutManager(new LinearLayoutManager(this));
        loadDownloadedFiles();

        btnPickFile.setOnClickListener(v -> pickTorrentFile());
        btnStream.setOnClickListener(v -> startStream());
        btnStop.setOnClickListener(v -> stopStream());

        processExternalIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        processExternalIntent(intent);
    }

    private void processExternalIntent(Intent intent) {
        if (intent == null) return;
        
        if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            Uri data = intent.getData();
            if ("magnet".equals(data.getScheme())) {
                etMagnet.setText(data.toString());
                startStream();
            } else {
                handleTorrentUri(data);
            }
        } else {
            String magnet = intent.getStringExtra("magnet");
            if (magnet != null) {
                etMagnet.setText(magnet);
            }
        }
    }

    private void pickTorrentFile() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent, 1001);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 1001 && resultCode == RESULT_OK && data != null) {
            handleTorrentUri(data.getData());
        }
    }

    private void handleTorrentUri(Uri uri) {
        if (uri == null) return;
        try {
            InputStream is = getContentResolver().openInputStream(uri);
            File tempFile = new File(getCacheDir(), "local_stream.torrent");
            FileOutputStream fos = new FileOutputStream(tempFile);
            byte[] buffer = new byte[1024];
            int length;
            while ((length = is.read(buffer)) > 0) {
                fos.write(buffer, 0, length);
            }
            fos.close();
            is.close();

            FileInputStream fis = new FileInputStream(tempFile);
            byte[] headerBytes = new byte[100];
            int readChars = fis.read(headerBytes);
            fis.close();
            String header = new String(headerBytes, 0, Math.max(0, readChars));

            if (!header.startsWith("d")) {
                new AlertDialog.Builder(this)
                    .setTitle("⚠ Cảnh báo định dạng")
                    .setMessage("File không phải chuẩn Bencode Torrent.\n\nĐây có thể là trang web HTML (do yêu cầu đăng nhập/chặn bot của trình duyệt). Thư viện sẽ không thể đọc được file này!")
                    .setPositiveButton("Đã hiểu", null)
                    .show();
                return;
            }

            etMagnet.setText("file://" + tempFile.getAbsolutePath());
            startStream();
        } catch (Exception e) {
            Toast.makeText(this, "Lỗi đọc file: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void startStream() {
        String url = etMagnet.getText().toString().trim();
        if (url.isEmpty()) {
            Toast.makeText(this, "Nhập magnet link hoặc chọn file", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!url.startsWith("magnet:") && !url.startsWith("http") && !url.startsWith("file://") && !url.startsWith("/")) {
            Toast.makeText(this, "Link không hợp lệ", Toast.LENGTH_SHORT).show();
            return;
        }

        // Khởi động server trung gian đa luồng nếu nhận diện thấy file local
        if (url.startsWith("file://") || url.startsWith("/")) {
            String filePath = url.replace("file://", "");
            File torrentFile = new File(filePath);
            if (!torrentFile.exists()) {
                Toast.makeText(this, "File không tồn tại", Toast.LENGTH_SHORT).show();
                return;
            }
            try {
                stopLocalServer();
                localServerSocket = new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"));
                localServerPort = localServerSocket.getLocalPort();
                isServerRunning = true;

                final File finalFile = torrentFile;
                new Thread(() -> {
                    while (isServerRunning) {
                        try {
                            final Socket socket = localServerSocket.accept();
                            // Chạy mỗi kết nối trên một sub-thread độc lập để tránh deadlock mạng
                            new Thread(() -> {
                                try (Socket s = socket;
                                     OutputStream os = s.getOutputStream();
                                     BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream()))) {
                                    
                                    String line;
                                    while ((line = in.readLine()) != null && !line.isEmpty()) {
                                        // Đọc sạch gói tin request header gửi lên
                                    }

                                    if (finalFile.exists()) {
                                        byte[] bytes = new byte[(int) finalFile.length()];
                                        try (FileInputStream fis = new FileInputStream(finalFile)) {
                                            int offset = 0;
                                            int numRead;
                                            while (offset < bytes.length && (numRead = fis.read(bytes, offset, bytes.length - offset)) >= 0) {
                                                offset += numRead;
                                            }
                                        }

                                        String resHeaders = "HTTP/1.1 200 OK\r\n" +
                                                "Content-Type: application/x-bittorrent\r\n" +
                                                "Content-Length: " + bytes.length + "\r\n" +
                                                "Connection: close\r\n\r\n";

                                        os.write(resHeaders.getBytes("UTF-8"));
                                        os.write(bytes);
                                        os.flush();

                                        // Toast thông báo thời gian thực chứng minh server đã phản hồi
                                        runOnUiThread(() -> Toast.makeText(TorrentActivity.this, "⚡ Local Server: Đã chuyển file torrent sang nhân P2P!", Toast.LENGTH_SHORT).show());
                                    } else {
                                        os.write("HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\n".getBytes("UTF-8"));
                                        os.flush();
                                    }
                                } catch (Exception ignored) {}
                            }).start();
                        } catch (Exception e) {
                            if (!isServerRunning) break;
                        }
                    }
                }).start();

                url = "http://127.0.0.1:" + localServerPort + "/torrent";
            } catch (Exception e) {
                Toast.makeText(this, "Lỗi khởi tạo local server: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                return;
            }
        }

        btnStream.setEnabled(false);
        btnStop.setEnabled(true);
        progressBar.setProgress(0);
        progressBar.setVisibility(View.VISIBLE);
        tvStatus.setText("Đang kết nối...");
        tvSpeed.setText("");

        torrentManager.startStream(url, new TorrentManager.Callback() {
            @Override
            public void onProgress(int progress, float dlSpeed, float ulSpeed) {
                progressBar.setProgress(progress);
                tvStatus.setText("Đang tải: " + progress + "%");
                tvSpeed.setText(String.format("↓ %.1f KB/s  ↑ %.1f KB/s", dlSpeed, ulSpeed));
            }

            @Override
            public void onReady(String videoPath) {
                tvStatus.setText("Sẵn sàng xem!");
                progressBar.setVisibility(View.GONE);
                stopLocalServer();

                Intent intent = new Intent(TorrentActivity.this, PlayerActivity.class);
                intent.putExtra(PlayerActivity.EXTRA_URI, "file://" + videoPath);
                intent.putExtra(PlayerActivity.EXTRA_TITLE, new File(videoPath).getName());
                startActivity(intent);

                loadDownloadedFiles();
            }

            @Override
            public void onError(String error) {
                tvStatus.setText("Lỗi: " + error);
                progressBar.setVisibility(View.GONE);
                btnStream.setEnabled(true);
                btnStop.setEnabled(false);
                stopLocalServer();
                Toast.makeText(TorrentActivity.this, "Lỗi: " + error, Toast.LENGTH_LONG).show();
            }

            @Override
            public void onStopped() {
                tvStatus.setText("Đã dừng");
                progressBar.setVisibility(View.GONE);
                btnStream.setEnabled(true);
                btnStop.setEnabled(false);
                stopLocalServer();
            }
        });
    }

    private void stopStream() {
        torrentManager.stop();
        stopLocalServer();
        btnStream.setEnabled(true);
        btnStop.setEnabled(false);
        progressBar.setVisibility(View.GONE);
        tvStatus.setText("Đã dừng");
    }

    private void stopLocalServer() {
        isServerRunning = false;
        if (localServerSocket != null) {
            try {
                localServerSocket.close();
            } catch (Exception ignored) {}
            localServerSocket = null;
        }
    }

    private void loadDownloadedFiles() {
        File dir = new File(getExternalFilesDir(null), "torrents");
        List<File> files = new ArrayList<>();
        if (dir.exists()) {
            File[] all = dir.listFiles();
            if (all != null) {
                for (File f : all) {
                    String name = f.getName().toLowerCase();
                    if (name.endsWith(".mp4") || name.endsWith(".mkv") ||
                        name.endsWith(".avi") || name.endsWith(".mov")) {
                        files.add(f);
                    }
                }
            }
        }
        adapter = new DownloadedAdapter(files);
        rvDownloaded.setAdapter(adapter);
    }

    class DownloadedAdapter extends RecyclerView.Adapter<DownloadedAdapter.VH> {
        private List<File> files;
        DownloadedAdapter(List<File> files) { this.files = files; }

        @Override public VH onCreateViewHolder(ViewGroup p, int t) {
            View v = LayoutInflater.from(p.getContext())
                .inflate(R.layout.item_torrent_file, p, false);
            return new VH(v);
        }

        @Override public void onBindViewHolder(VH h, int pos) {
            File f = files.get(pos);
            h.tvName.setText(f.getName());
            h.tvSize.setText(formatSize(f.length()));
            h.itemView.setOnClickListener(v -> {
                Intent intent = new Intent(TorrentActivity.this, PlayerActivity.class);
                intent.putExtra(PlayerActivity.EXTRA_URI, "file://" + f.getAbsolutePath());
                intent.putExtra(PlayerActivity.EXTRA_TITLE, f.getName());
                startActivity(intent);
            });
            h.btnDelete.setOnClickListener(v -> {
                f.delete();
                files.remove(pos);
                notifyItemRemoved(pos);
            });
        }

        @Override public int getItemCount() { return files.size(); }

        class VH extends RecyclerView.ViewHolder {
            TextView tvName, tvSize;
            ImageButton btnDelete;
            VH(View v) {
                super(v);
                tvName = v.findViewById(R.id.tv_name);
                tvSize = v.findViewById(R.id.tv_size);
                btnDelete = v.findViewById(R.id.btn_delete);
            }
        }
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024*1024) return String.format("%.1f KB", bytes/1024f);
        if (bytes < 1024*1024*1024) return String.format("%.1f MB", bytes/(1024f*1024));
        return String.format("%.2f GB", bytes/(1024f*1024*1024));
    }

    @Override protected void onDestroy() {
        stopLocalServer();
        super.onDestroy();
    }
}
