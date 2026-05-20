package com.vlcplayer.app;

import android.os.Handler;

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
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.frostwire.jlibtorrent.TorrentInfo;
import com.frostwire.jlibtorrent.Sha1Hash;

import java.io.File;
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

        rvDownloaded.setLayoutManager(new LinearLayoutManager(this));
        loadDownloadedFiles();

        btnPickFile.setOnClickListener(v -> pickTorrentFile());
        btnStream.setOnClickListener(v -> startCloudStream());
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
                startCloudStream();
            } else {
                handleTorrentUri(data);
            }
        } else {
            String magnet = intent.getStringExtra("magnet");
            if (magnet != null) etMagnet.setText(magnet);
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

            etMagnet.setText(tempFile.getAbsolutePath());
            startCloudStream();
        } catch (Exception e) {
            Toast.makeText(this, "Lỗi đọc file: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    // KIẾN TRÚC ĐỘT PHÁ: Phân giải Info-Hash chạy mây qua Gateway HTTP Torrentio công nghiệp của Stremio
    private void startCloudStream() {
        String input = etMagnet.getText().toString().trim();
        if (input.isEmpty()) {
            Toast.makeText(this, "Nhập magnet link hoặc chọn file", Toast.LENGTH_SHORT).show();
            return;
        }

        String infoHash = "";
        String displayName = "Torrent Cloud Stream";

        try {
            if (input.startsWith("magnet:?xt=urn:btih:")) {
                int start = 20;
                int end = input.indexOf("&", start);
                infoHash = (end == -1) ? input.substring(start) : input.substring(start, end);
                
                if (input.contains("&dn=")) {
                    int dnStart = input.indexOf("&dn=") + 4;
                    int dnEnd = input.indexOf("&", dnStart);
                    displayName = Uri.decode((dnEnd == -1) ? input.substring(dnStart) : input.substring(dnStart, dnEnd));
                }
            } else {
                File file = new File(input.replace("file://", ""));
                if (file.exists()) {
                    TorrentInfo ti = new TorrentInfo(file);
                    infoHash = ti.infoHash().toString();
                    if (ti.name() != null && !ti.name().isEmpty()) displayName = ti.name();
                }
            }
        } catch (Exception e) {
            Toast.makeText(this, "Lỗi bóc tách cấu trúc: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            return;
        }

        if (infoHash.isEmpty()) {
            Toast.makeText(this, "Đường dẫn đầu vào không hợp lệ hoặc không tìm thấy Info-Hash!", Toast.LENGTH_LONG).show();
            return;
        }

        tvStatus.setText("🚀 Đang đồng bộ máy chủ Torrentio Cloud...");
        progressBar.setVisibility(View.VISIBLE);

        // Tạo đường dẫn bắc cầu HTTP Stream chuẩn hóa qua luồng định tuyến của Torrentio
        // Sử dụng index 0 để ép máy chủ mây tự động bóc tách file video nặng nhất trong Swarm tệp tin
        String torrentioHttpUrl = "https://torrentio.strem.fun/stream/movie/" + infoHash.toLowerCase() + ":0:0.mp4";

        // Nhảy thẳng sang màn hình PlayerActivity để phát trực tuyến bằng nhân LibVLC SurfaceView tức thì!
        Intent intent = new Intent(TorrentActivity.this, PlayerActivity.class);
        intent.putExtra(PlayerActivity.EXTRA_URI, torrentioHttpUrl);
        intent.putExtra(PlayerActivity.EXTRA_TITLE, displayName);
        startActivity(intent);

        handlerPostDelayed();
    }

    private void handlerPostDelayed() {
        new Handler().postDelayed(() -> {
            progressBar.setVisibility(View.GONE);
            tvStatus.setText("Đang phát trên Torrentio Đám mây!");
            loadDownloadedFiles();
        }, 1500);
    }

    private void stopStream() {
        progressBar.setVisibility(View.GONE);
        tvStatus.setText("Đã dừng luồng");
    }

    private void loadDownloadedFiles() {
        File dir = getExternalFilesDir("torrents");
        List<File> files = new ArrayList<>();
        if (dir != null && dir.exists()) {
            File[] all = dir.listFiles();
            if (all != null) {
                for (File f : all) {
                    String name = f.getName().toLowerCase();
                    if (name.endsWith(".mp4") || name.endsWith(".mkv") || name.endsWith(".avi")) {
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
            View v = LayoutInflater.from(p.getContext()).inflate(R.layout.item_torrent_file, p, false);
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
}
