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
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public class TorrentActivity extends AppCompatActivity {

    private static final int REQ_PICK_TORRENT = 2001;

    private EditText etMagnet;
    private Button btnStream, btnStop;
    private ImageButton btnPickFile;
    private ProgressBar progressBar;
    private TextView tvStatus, tvSpeed;
    private RecyclerView rvDownloaded;
    private TorrentManager torrentManager;
    private android.widget.Switch swStreamOnly;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(AppLanguageManager.applyLanguage(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_torrent);

        etMagnet    = findViewById(R.id.et_magnet);
        btnStream   = findViewById(R.id.btn_stream);
        btnStop     = findViewById(R.id.btn_stop);
        btnPickFile = findViewById(R.id.btn_pick_file);
        progressBar = findViewById(R.id.progress_bar);
        tvStatus    = findViewById(R.id.tv_status);
        tvSpeed     = findViewById(R.id.tv_speed);
        rvDownloaded = findViewById(R.id.rv_downloaded);

        torrentManager = new TorrentManager(this);
        swStreamOnly = findViewById(R.id.sw_stream_only);
        rvDownloaded.setLayoutManager(new LinearLayoutManager(this));
        loadDownloadedFiles();

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        btnStream.setOnClickListener(v -> startStream());
        btnStop.setOnClickListener(v -> stopStream());
        btnPickFile.setOnClickListener(v -> pickTorrentFile());

        // Nhan magnet tu intent khac
        String magnet = getIntent().getStringExtra("magnet");
        if (magnet != null) etMagnet.setText(magnet);
    }

    private void pickTorrentFile() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("*/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(i, REQ_PICK_TORRENT);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_PICK_TORRENT && res == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri == null) return;
            try {
                // Copy file .torrent vao cache roi stream
                InputStream is = getContentResolver().openInputStream(uri);
                File tmp = new File(getCacheDir(), "stream.torrent");
                FileOutputStream fos = new FileOutputStream(tmp);
                byte[] buf = new byte[4096];
                int len;
                while ((len = is.read(buf)) > 0) fos.write(buf, 0, len);
                fos.close();
                is.close();
                // Dung file:// path
                // Pass duong dan tuyet doi - TorrentStream tu xu ly
                etMagnet.setText("file://" + tmp.getAbsolutePath());
                Toast.makeText(this, "Da chon: " + tmp.getName(),
                    Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "Loi: " + e.getMessage(),
                    Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void startStream() {
        String url = etMagnet.getText().toString().trim();
        if (url.isEmpty()) {
            Toast.makeText(this, "Nhap magnet link hoac chon file .torrent",
                Toast.LENGTH_SHORT).show();
            return;
        }

        boolean isValid = url.startsWith("magnet:")
            || url.startsWith("http")
            || url.startsWith("file://")
            || url.startsWith("/"); // duong dan tuyet doi
        if (!isValid) {
            Toast.makeText(this, "Link khong hop le", Toast.LENGTH_SHORT).show();
            return;
        }

        btnStream.setEnabled(false);
        btnStop.setEnabled(true);
        progressBar.setProgress(0);
        progressBar.setVisibility(View.VISIBLE);
        tvStatus.setText("Dang ket noi...");
        tvSpeed.setText("");

        // Set stream only mode truoc khi bat dau
        torrentManager.setStreamOnly(
            swStreamOnly != null && swStreamOnly.isChecked());
        torrentManager.startStream(url, new TorrentManager.Callback() {
            @Override
            public void onStatusUpdate(String status) {
                tvStatus.setText(status);
            }

            @Override
            public void onProgress(int progress, float dlSpeed) {
                progressBar.setProgress(progress);
                tvSpeed.setText(String.format("%.1f KB/s", dlSpeed));
            }

            @Override
            public void onReady(String streamUrl) {
                runOnUiThread(() -> {
                    tvStatus.setText("San sang xem!");
                    progressBar.setVisibility(View.GONE);
                    // Mo VLC voi HTTP URL - stream truc tiep
                    Intent intent = new Intent(TorrentActivity.this, PlayerActivity.class);
                    intent.putExtra(PlayerActivity.EXTRA_URI, streamUrl);
                    intent.putExtra(PlayerActivity.EXTRA_TITLE, "Torrent Stream");
                    startActivity(intent);
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    tvStatus.setText("Loi: " + error);
                    progressBar.setVisibility(View.GONE);
                    btnStream.setEnabled(true);
                    btnStop.setEnabled(false);
                    Toast.makeText(TorrentActivity.this,
                        "Loi: " + error, Toast.LENGTH_LONG).show();
                });
            }

            @Override
            public void onStopped() {
                runOnUiThread(() -> {
                    tvStatus.setText("Da dung");
                    progressBar.setVisibility(View.GONE);
                    btnStream.setEnabled(true);
                    btnStop.setEnabled(false);
                });
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
                    String n = f.getName().toLowerCase();
                    if (n.endsWith(".mp4") || n.endsWith(".mkv")
                            || n.endsWith(".avi") || n.endsWith(".mov")
                            || n.endsWith(".webm")) {
                        files.add(f);
                    }
                }
            }
        }
        rvDownloaded.setAdapter(new DownloadedAdapter(files));
    }

    class DownloadedAdapter extends RecyclerView.Adapter<DownloadedAdapter.VH> {
        private List<File> data;
        DownloadedAdapter(List<File> d) { data = d; }

        @Override public VH onCreateViewHolder(ViewGroup p, int t) {
            View v = LayoutInflater.from(p.getContext())
                .inflate(R.layout.item_torrent_file, p, false);
            return new VH(v);
        }

        @Override public void onBindViewHolder(VH h, int pos) {
            File f = data.get(pos);
            h.tvName.setText(f.getName());
            h.tvSize.setText(formatSize(f.length()));
            h.itemView.setOnClickListener(v -> {
                Intent i = new Intent(TorrentActivity.this, PlayerActivity.class);
                i.putExtra(PlayerActivity.EXTRA_URI, "file://" + f.getAbsolutePath());
                i.putExtra(PlayerActivity.EXTRA_TITLE, f.getName());
                startActivity(i);
            });
            h.btnDelete.setOnClickListener(v -> {
                f.delete();
                data.remove(pos);
                notifyItemRemoved(pos);
            });
        }

        @Override public int getItemCount() { return data.size(); }

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

    private String formatSize(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024*1024) return String.format("%.1f KB", b/1024f);
        if (b < 1024*1024*1024) return String.format("%.1f MB", b/(1024f*1024));
        return String.format("%.2f GB", b/(1024f*1024*1024));
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        torrentManager.stop();
        // Xoa het file torrent khi thoat
        new Thread(() -> {
            try {
                File cacheDir = new File(getCacheDir(), "torrent_stream");
                deleteDir(cacheDir);
            } catch (Exception ignored) {}
        }).start();
    }

    private void deleteDir(File dir) {
        if (dir == null || !dir.exists()) return;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) deleteDir(f);
                else f.delete();
            }
        }
        dir.delete();
    }
}
