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

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
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

        // Xu ly file hoac link truyen tu ben ngoai (khi mo tu File Manager)
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
            // Neu la link magnet -> Dan vao o text va chay luon
            if ("magnet".equals(data.getScheme())) {
                etMagnet.setText(data.toString());
                startStream();
            } else {
                // Neu la file tu File Manager -> Xu ly doc file va chay
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

    // Ham dung chung de phan tich file Torrent duoc chon tu App hoac tu File Manager
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

            // Kiem tra noi dung file
            FileInputStream fis = new FileInputStream(tempFile);
            byte[] headerBytes = new byte[100];
            int readChars = fis.read(headerBytes);
            fis.close();
            String header = new String(headerBytes, 0, Math.max(0, readChars));

            // File torrent chuan Bencode luon bat dau bang ky tu 'd'
            if (!header.startsWith("d")) {
                new AlertDialog.Builder(this)
                    .setTitle("⚠ Cảnh báo định dạng")
                    .setMessage("File không phải chuẩn Bencode Torrent.\n\nĐây có thể là trang web HTML (do yêu cầu đăng nhập/chặn bot của trình duyệt). Thư viện sẽ không thể đọc được file này!")
                    .setPositiveButton("Đã hiểu", null)
                    .show();
                return;
            }

            etMagnet.setText("file://" + tempFile.getAbsolutePath());
            startStream(); // Tu dong phat ngay
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
                Toast.makeText(TorrentActivity.this, "Lỗi: " + error, Toast.LENGTH_LONG).show();
            }

            @Override
            public void onStopped() {
                tvStatus.setText("Đã dừng");
                progressBar.setVisibility(View.GONE);
                btnStream.setEnabled(true);
                btnStop.setEnabled(false);
            }
        });
    }

    private void stopStream() {
        torrentManager.stop();
        btnStream.setEnabled(true);
        btnStop.setEnabled(false);
        progressBar.setVisibility(View.GONE);
        tvStatus.setText("Đã dừng");
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
        super.onDestroy();
    }
}
