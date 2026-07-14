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
    private TorrentManager.Callback torrentCallback;
    private boolean playerLaunched;

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
        rvDownloaded.setLayoutManager(new LinearLayoutManager(this));
        loadDownloadedFiles();

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        btnStream.setOnClickListener(v -> startStream());
        btnStop.setOnClickListener(v -> stopStream());
        btnPickFile.setOnClickListener(v -> pickTorrentFile());

        handleIntent(getIntent());
    }

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        String source = intent.getStringExtra("magnet");
        if ((source == null || source.isEmpty()) && intent.getData() != null) {
            source = intent.getData().toString();
        }
        if (source != null && !source.isEmpty()) {
            etMagnet.setText(source);
            etMagnet.post(this::startStream);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
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
                File tmp = new File(getCacheDir(), "stream.torrent");
                try (InputStream is = getContentResolver().openInputStream(uri);
                     FileOutputStream fos = new FileOutputStream(tmp)) {
                    if (is == null) throw new java.io.IOException("Khong mo duoc file torrent");
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = is.read(buf)) != -1) fos.write(buf, 0, len);
                }
                etMagnet.setText("file://" + tmp.getAbsolutePath());
                startStream();
            } catch (Exception e) {
                Toast.makeText(this, "Loi: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void startStream() {
        String url = etMagnet.getText().toString().trim();
        if (url.isEmpty()) {
            Toast.makeText(this, "Nhap magnet link hoac chon file .torrent", Toast.LENGTH_SHORT).show();
            return;
        }

        boolean isValid = url.startsWith("magnet:")
            || url.startsWith("http://")
            || url.startsWith("https://")
            || url.startsWith("file://")
            || url.startsWith("/");
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

        torrentCallback = new TorrentManager.Callback() {
            @Override
            public void onStatusUpdate(String status) {
                runOnUiThread(() -> tvStatus.setText(status));
            }

            @Override
            public void onProgress(int progress, float dlSpeed) {
                runOnUiThread(() -> {
                    progressBar.setProgress(progress);
                    tvSpeed.setText(String.format("%.1f KB/s", dlSpeed));
                });
            }

            @Override
            public void onFilesFound(List<TorrentManager.VideoFileEntry> files) {
                runOnUiThread(() -> showFilePicker(files));
            }

            @Override
            public void onReady(String streamUrl) {
                runOnUiThread(() -> {
                    tvStatus.setText("San sang xem!");
                    progressBar.setVisibility(View.GONE);
                    Intent intent = new Intent(TorrentActivity.this, PlayerActivity.class);
                    intent.putExtra(PlayerActivity.EXTRA_URI, streamUrl);
                    intent.putExtra(PlayerActivity.EXTRA_TITLE, "Torrent Stream");
                    intent.putExtra(PlayerActivity.EXTRA_AUTO_CLEANUP_TORRENT, true);
                    playerLaunched = true;
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
                    torrentManager.stopAndClearCache();
                    Toast.makeText(TorrentActivity.this, "Loi: " + error, Toast.LENGTH_LONG).show();
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
        };

        torrentManager.startStream(url, torrentCallback);
    }

    private void showFilePicker(List<TorrentManager.VideoFileEntry> files) {
        tvStatus.setText("Torrent co " + files.size() + " video - chon file de xem");

        String[] labels = new String[files.size()];
        for (int i = 0; i < files.size(); i++) {
            TorrentManager.VideoFileEntry f = files.get(i);
            String shortName = f.name;
            int slash = shortName.lastIndexOf('/');
            if (slash >= 0) shortName = shortName.substring(slash + 1);
            labels[i] = shortName + "  (" + formatSize(f.size) + ")";
        }

        new AlertDialog.Builder(this)
            .setTitle("Chon video de xem")
            .setItems(labels, (d, which) -> {
                TorrentManager.VideoFileEntry chosen = files.get(which);
                tvStatus.setText("Dang tai: " + chosen.name);
                progressBar.setVisibility(View.VISIBLE);
                torrentManager.selectFile(chosen.index, torrentCallback);
            })
            .setCancelable(false)
            .setNegativeButton("Huy", (d, w) -> stopStream())
            .show();
    }

    private void stopStream() {
        torrentManager.stopAndClearCache();
        btnStream.setEnabled(true);
        btnStop.setEnabled(false);
        progressBar.setVisibility(View.GONE);
        tvStatus.setText("Da dung");
    }

    private void loadDownloadedFiles() {
        File dir = TorrentManager.getCacheDirectory(this);
        List<File> files = new ArrayList<>();
        if (dir.exists()) collectVideoFiles(dir, files);
        rvDownloaded.setAdapter(new DownloadedAdapter(files));
    }

    private void collectVideoFiles(File dir, List<File> out) {
        File[] all = dir.listFiles();
        if (all == null) return;
        for (File f : all) {
            if (f.isDirectory()) {
                collectVideoFiles(f, out);
            } else {
                String n = f.getName().toLowerCase();
                if (n.endsWith(".mp4") || n.endsWith(".mkv")
                        || n.endsWith(".avi") || n.endsWith(".webm")) {
                    out.add(f);
                }
            }
        }
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
                i.putExtra(PlayerActivity.EXTRA_AUTO_CLEANUP_TORRENT, true);
                playerLaunched = true;
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
            ImageButton btnDelete;
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

    @Override
    protected void onResume() {
        super.onResume();
        if (playerLaunched && !torrentManager.isStreaming()) {
            playerLaunched = false;
            btnStream.setEnabled(true);
            btnStop.setEnabled(false);
            progressBar.setVisibility(View.GONE);
            tvSpeed.setText("");
            tvStatus.setText("Da tu dong don cache torrent");
            loadDownloadedFiles();
        }
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (!playerLaunched || !torrentManager.isStreaming()) torrentManager.destroy();
    }
}
