package com.vlcplayer.app;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class TorrentActivity extends AppCompatActivity {

    private EditText etMagnet;
    private android.widget.ImageButton btnPickFile;
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
        btnPickFile.setOnClickListener(v -> pickTorrentFile());
        btnStream  = findViewById(R.id.btn_stream);
        btnStop    = findViewById(R.id.btn_stop);
        progressBar = findViewById(R.id.progress_bar);
        tvStatus   = findViewById(R.id.tv_status);
        tvSpeed    = findViewById(R.id.tv_speed);
        rvDownloaded = findViewById(R.id.rv_downloaded);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

        torrentManager = new TorrentManager(this);

        // Load danh sach file da tai
        rvDownloaded.setLayoutManager(new LinearLayoutManager(this));
        loadDownloadedFiles();

        btnStream.setOnClickListener(v -> startStream());
        btnStop.setOnClickListener(v -> stopStream());

        // Nhan magnet link tu intent
        String magnet = getIntent().getStringExtra("magnet");
        if (magnet != null) {
            etMagnet.setText(magnet);
        }
    }
    private void pickTorrentFile() {
        android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        intent.addCategory(android.content.Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent, 1001);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 1001 && resultCode == RESULT_OK && data != null) {
            android.net.Uri uri = data.getData();
            if (uri != null) {
                try {
                    java.io.InputStream is = getContentResolver().openInputStream(uri);
                    java.io.File tempFile = new java.io.File(getCacheDir(), "local_stream.torrent");
                    java.io.FileOutputStream fos = new java.io.FileOutputStream(tempFile);
                    byte[] buffer = new byte[1024];
                    int length;
                    long totalBytes = 0;
                    while ((length = is.read(buffer)) > 0) {
                        fos.write(buffer, 0, length);
                        totalBytes += length;
                    }
                    fos.close();
                    is.close();

                    // Kiem tra neu file > 5MB -> Chac chan la chon nham file Video hoac file rac!
                    if (totalBytes > 5 * 1024 * 1024) {
                        android.widget.Toast.makeText(this, "⚠ CẢNH BÁO: File bạn chọn nặng " + (totalBytes/1024/1024) + "MB. Đây có vẻ là file Video, không phải file .torrent hợp lệ!", android.widget.Toast.LENGTH_LONG).show();
                    }

                    // Dat luon duong dan tuyet doi, bo tien to file://
                    etMagnet.setText(tempFile.getAbsolutePath());
                    startStream();
                } catch (Exception e) {
                    android.widget.Toast.makeText(this, "Lỗi đọc file: " + e.getMessage(), android.widget.Toast.LENGTH_SHORT).show();
                }
            }
        }
    }
    }


    private void startStream() {
        String url = etMagnet.getText().toString().trim();
        if (url.isEmpty()) {
            Toast.makeText(this, "Nhap magnet link hoac URL torrent", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!url.startsWith("magnet:") && !url.startsWith("http") && !url.startsWith("file://")) {
            Toast.makeText(this, "Link khong hop le", Toast.LENGTH_SHORT).show();
            return;
        }

        // Xoa tien to file:// de thu vien C++ co the doc duoc duong dan that
        if (url.startsWith("file://")) {
            url = url.replace("file://", "");
        }

        btnStream.setEnabled(false);
        btnStop.setEnabled(true);
        progressBar.setProgress(0);
        progressBar.setVisibility(View.VISIBLE);
        tvStatus.setText("Dang ket noi...");
        tvSpeed.setText("");

        torrentManager.startStream(url, new TorrentManager.Callback() {
            @Override
            public void onProgress(int progress, float dlSpeed, float ulSpeed) {
                progressBar.setProgress(progress);
                tvStatus.setText("Dang tai: " + progress + "%");
                tvSpeed.setText(String.format("↓ %.1f KB/s  ↑ %.1f KB/s", dlSpeed, ulSpeed));
            }

            @Override
            public void onReady(String videoPath) {
                tvStatus.setText("San sang xem!");
                progressBar.setVisibility(View.GONE);

                // Mo video trong PlayerActivity
                Intent intent = new Intent(TorrentActivity.this, PlayerActivity.class);
                intent.putExtra(PlayerActivity.EXTRA_URI, "file://" + videoPath);
                intent.putExtra(PlayerActivity.EXTRA_TITLE,
                    new File(videoPath).getName());
                startActivity(intent);

                loadDownloadedFiles();
            }

            @Override
            public void onError(String error) {
                tvStatus.setText("Loi: " + error);
                progressBar.setVisibility(View.GONE);
                btnStream.setEnabled(true);
                btnStop.setEnabled(false);
                Toast.makeText(TorrentActivity.this, "Loi: " + error, Toast.LENGTH_LONG).show();
            }

            @Override
            public void onStopped() {
                tvStatus.setText("Da dung");
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
        tvStatus.setText("Da dung");
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

    // Adapter danh sach da tai
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
            android.widget.ImageButton btnDelete;
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
        // Khong stop khi thoat de tiep tuc tai nen
    }
}
// trigger build Sat May 16 16:27:24 +07 2026
