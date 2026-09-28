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
import android.widget.Switch;
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
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
    private Switch autoCleanupSwitch;
    private TextView cleanupHint;
    private boolean streamAutoCleanup;
    private boolean lastPlayerAutoCleanup;
    private int requestGeneration;
    private int listGeneration;
    private boolean filePickerShown;
    private AlertDialog filePickerDialog;
    private final ExecutorService fileExecutor = Executors.newSingleThreadExecutor();

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
        autoCleanupSwitch = findViewById(R.id.sw_stream_only);
        cleanupHint = findViewById(R.id.tv_cleanup_hint);
        autoCleanupSwitch.setChecked(getSharedPreferences("torrent_prefs", MODE_PRIVATE)
            .getBoolean("auto_cleanup", false));
        autoCleanupSwitch.setOnCheckedChangeListener((button, checked) -> {
            getSharedPreferences("torrent_prefs", MODE_PRIVATE).edit()
                .putBoolean("auto_cleanup", checked).apply();
            updateCleanupHint();
        });
        updateCleanupHint();

        torrentManager = TorrentManager.getActiveManager();
        boolean reattaching = torrentManager != null;
        if (!reattaching) torrentManager = new TorrentManager(this);
        rvDownloaded.setLayoutManager(new LinearLayoutManager(this));

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        btnStream.setOnClickListener(v -> startStream());
        btnStop.setOnClickListener(v -> stopStream());
        btnPickFile.setOnClickListener(v -> pickTorrentFile());

        if (savedInstanceState != null) {
            etMagnet.setText(savedInstanceState.getString("torrent_source", ""));
            playerLaunched = savedInstanceState.getBoolean("player_launched", false);
            lastPlayerAutoCleanup = savedInstanceState.getBoolean("player_auto_cleanup", false);
        }
        if (reattaching) {
            streamAutoCleanup = torrentManager.isAutoCleanupEnabled();
            autoCleanupSwitch.setChecked(streamAutoCleanup);
            setStreamControls(true);
            torrentCallback = createTorrentCallback(requestGeneration);
            torrentManager.attachCallback(torrentCallback);
        }
        if (savedInstanceState == null) handleIntent(getIntent());
    }

    private void updateCleanupHint() {
        cleanupHint.setText(autoCleanupSwitch.isChecked()
            ? R.string.torrent_cleanup_on_hint : R.string.torrent_cleanup_off_hint);
    }

    private void setStreamControls(boolean running) {
        btnStream.setEnabled(!running);
        btnStop.setEnabled(running);
        autoCleanupSwitch.setEnabled(!running);
        btnPickFile.setEnabled(!running);
    }

    private boolean isCurrentRequest(int generation) {
        return generation == requestGeneration && !isFinishing() && !isDestroyed();
    }

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        String source = intent.getStringExtra("magnet");
        if ((source == null || source.isEmpty()) && intent.getData() != null) {
            source = intent.getData().toString();
        }
        if (source != null && !source.isEmpty()) {
            if (source.startsWith("content://")) {
                importTorrentFile(Uri.parse(source));
                return;
            }
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
            importTorrentFile(uri);
        }
    }

    private void importTorrentFile(Uri uri) {
        if (isFinishing() || isDestroyed()) return;
        if (torrentManager.getPlaybackSessionId() != null) stopStream();
        final int generation = ++requestGeneration;
        streamAutoCleanup = autoCleanupSwitch.isChecked();
        setStreamControls(true);
        progressBar.setIndeterminate(true);
        progressBar.setVisibility(View.VISIBLE);
        tvStatus.setText(R.string.torrent_importing);
        fileExecutor.execute(() -> {
            File tmp = null;
            try {
                File inputs = new File(getCacheDir(), "torrent_inputs");
                if (!inputs.isDirectory() && !inputs.mkdirs())
                    throw new java.io.IOException("Cannot create torrent input directory");
                tmp = File.createTempFile("selected-", ".torrent", inputs);
                try (InputStream is = getContentResolver().openInputStream(uri);
                     FileOutputStream fos = new FileOutputStream(tmp)) {
                    if (is == null) throw new java.io.IOException("Khong mo duoc file torrent");
                    byte[] buf = new byte[8192];
                    int len;
                    int total = 0;
                    while ((len = is.read(buf)) != -1) {
                        if (Thread.currentThread().isInterrupted())
                            throw new java.io.IOException("Torrent import cancelled");
                        total += len;
                        if (total > 8 * 1024 * 1024)
                            throw new java.io.IOException("Torrent metadata exceeds 8 MiB");
                        fos.write(buf, 0, len);
                    }
                }
                final File imported = tmp;
                runOnUiThread(() -> {
                    if (!isCurrentRequest(generation)) { imported.delete(); return; }
                    etMagnet.setText(Uri.fromFile(imported).toString());
                    startStream();
                });
            } catch (Exception e) {
                if (tmp != null) tmp.delete();
                runOnUiThread(() -> {
                    if (isCurrentRequest(generation)) {
                        setStreamControls(false);
                        progressBar.setVisibility(View.GONE);
                        Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
    }

    private void startStream() {
        if (isFinishing() || isDestroyed()) return;
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

        if (torrentCallback != null) torrentManager.detachCallback(torrentCallback);
        final int generation = ++requestGeneration;
        dismissFilePicker();
        playerLaunched = false;
        streamAutoCleanup = autoCleanupSwitch.isChecked();
        filePickerShown = false;
        setStreamControls(true);
        progressBar.setIndeterminate(false);
        progressBar.setProgress(0);
        progressBar.setVisibility(View.VISIBLE);
        tvStatus.setText("Dang ket noi...");
        tvSpeed.setText("");

        torrentCallback = createTorrentCallback(generation);
        torrentManager.startStream(url, streamAutoCleanup, torrentCallback);
    }

    private TorrentManager.Callback createTorrentCallback(int generation) {
        return new TorrentManager.Callback() {
            @Override
            public void onStatusUpdate(String status) {
                runOnUiThread(() -> {
                    if (isCurrentRequest(generation)) tvStatus.setText(status);
                });
            }

            @Override
            public void onProgress(int progress, float dlSpeed) {
                runOnUiThread(() -> {
                    if (!isCurrentRequest(generation)) return;
                    progressBar.setProgress(progress);
                    tvSpeed.setText(String.format("%.1f KB/s", dlSpeed));
                });
            }

            @Override
            public void onFilesFound(List<TorrentManager.VideoFileEntry> files) {
                runOnUiThread(() -> {
                    if (isCurrentRequest(generation) && !filePickerShown)
                        showFilePicker(files, generation);
                });
            }

            @Override
            public void onReady(String streamUrl) {
                runOnUiThread(() -> {
                    if (!isCurrentRequest(generation) || playerLaunched) return;
                    tvStatus.setText("San sang xem!");
                    progressBar.setVisibility(View.GONE);
                    Intent intent = new Intent(TorrentActivity.this, PlayerActivity.class);
                    intent.putExtra(PlayerActivity.EXTRA_URI, streamUrl);
                    intent.putExtra(PlayerActivity.EXTRA_TITLE, "Torrent Stream");
                    intent.putExtra(PlayerActivity.EXTRA_AUTO_CLEANUP_TORRENT, streamAutoCleanup);
                    intent.putExtra(PlayerActivity.EXTRA_TORRENT_SESSION_ID,
                        torrentManager.getPlaybackSessionId());
                    playerLaunched = true;
                    lastPlayerAutoCleanup = streamAutoCleanup;
                    startActivity(intent);
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    if (!isCurrentRequest(generation)) return;
                    tvStatus.setText("Loi: " + error);
                    progressBar.setVisibility(View.GONE);
                    setStreamControls(false);
                    requestGeneration++;
                    dismissFilePicker();
                    torrentManager.detachCallback(torrentCallback);
                    torrentManager.destroy();
                    Toast.makeText(TorrentActivity.this, "Loi: " + error, Toast.LENGTH_LONG).show();
                });
            }

            @Override
            public void onStopped() {
                runOnUiThread(() -> {
                    if (!isCurrentRequest(generation)) return;
                    dismissFilePicker();
                    tvStatus.setText(streamAutoCleanup ? R.string.torrent_stopped_deleted
                        : R.string.torrent_stopped_kept);
                    progressBar.setVisibility(View.GONE);
                    playerLaunched = false;
                    setStreamControls(false);
                    loadDownloadedFiles();
                });
            }
        };

    }

    private void showFilePicker(List<TorrentManager.VideoFileEntry> files, int generation) {
        filePickerShown = true;
        tvStatus.setText("Torrent co " + files.size() + " video - chon file de xem");

        String[] labels = new String[files.size()];
        for (int i = 0; i < files.size(); i++) {
            TorrentManager.VideoFileEntry f = files.get(i);
            String shortName = f.name;
            int slash = shortName.lastIndexOf('/');
            if (slash >= 0) shortName = shortName.substring(slash + 1);
            labels[i] = shortName + "  (" + formatSize(f.size) + ")";
        }

        filePickerDialog = new AlertDialog.Builder(this)
            .setTitle("Chon video de xem")
            .setItems(labels, (d, which) -> {
                filePickerShown = false;
                if (!isCurrentRequest(generation)) return;
                TorrentManager.VideoFileEntry chosen = files.get(which);
                tvStatus.setText("Dang tai: " + chosen.name);
                progressBar.setVisibility(View.VISIBLE);
                torrentManager.selectFile(chosen.index, torrentCallback);
            })
            .setCancelable(false)
            .setNegativeButton("Huy", (d, w) -> {
                filePickerShown = false;
                if (isCurrentRequest(generation)) stopStream();
            })
            .create();
        filePickerDialog.setOnDismissListener(d -> {
            filePickerShown = false;
            filePickerDialog = null;
        });
        filePickerDialog.show();
    }

    private void dismissFilePicker() {
        if (filePickerDialog != null) filePickerDialog.dismiss();
        filePickerShown = false;
    }

    private void stopStream() {
        final int generation = ++requestGeneration;
        playerLaunched = false;
        dismissFilePicker();
        if (torrentCallback != null) torrentManager.detachCallback(torrentCallback);
        torrentManager.destroy();
        setStreamControls(false);
        progressBar.setVisibility(View.GONE);
        tvStatus.setText(streamAutoCleanup ? R.string.torrent_stopped_deleted
            : R.string.torrent_stopped_kept);
        loadDownloadedFiles();
        rvDownloaded.postDelayed(() -> {
            if (isCurrentRequest(generation)) loadDownloadedFiles();
        }, 1000);
    }

    private void loadDownloadedFiles() {
        final int generation = ++listGeneration;
        fileExecutor.execute(() -> {
            List<File> files = new ArrayList<>();
            for (File dir : TorrentManager.getStorageDirectories(this))
                if (dir.exists()) collectVideoFiles(dir, files);
            runOnUiThread(() -> {
                if (isDestroyed() || isFinishing() || generation != listGeneration) return;
                rvDownloaded.setAdapter(new DownloadedAdapter(files));
            });
        });
    }

    private void collectVideoFiles(File dir, List<File> out) {
        File[] all = dir.listFiles();
        if (all == null) return;
        for (File f : all) {
            if (f.isDirectory()) {
                collectVideoFiles(f, out);
            } else {
                String n = f.getName().toLowerCase(Locale.US);
                if (n.endsWith(".mp4") || n.endsWith(".mkv")
                        || n.endsWith(".avi") || n.endsWith(".webm") || n.endsWith(".mov")) {
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
                int index = h.getBindingAdapterPosition();
                if (index == RecyclerView.NO_POSITION) return;
                File selected = data.get(index);
                if (torrentManager.getPlaybackSessionId() != null) {
                    Toast.makeText(TorrentActivity.this, R.string.torrent_stop_before_open,
                        Toast.LENGTH_LONG).show();
                    return;
                }
                String resumeSource = TorrentManager.getResumeSource(TorrentActivity.this, selected);
                if (resumeSource != null) {
                    etMagnet.setText(resumeSource);
                    startStream();
                    return;
                }
                Intent i = new Intent(TorrentActivity.this, PlayerActivity.class);
                i.putExtra(PlayerActivity.EXTRA_URI, Uri.fromFile(selected).toString());
                i.putExtra(PlayerActivity.EXTRA_TITLE, selected.getName());
                lastPlayerAutoCleanup = autoCleanupSwitch.isChecked();
                i.putExtra(PlayerActivity.EXTRA_AUTO_CLEANUP_TORRENT, lastPlayerAutoCleanup);
                if (lastPlayerAutoCleanup) {
                    String token = TorrentManager.prepareStoredPlayback(TorrentActivity.this, selected);
                    if (token == null) {
                        Toast.makeText(TorrentActivity.this, R.string.torrent_delete_failed,
                            Toast.LENGTH_LONG).show();
                        return;
                    }
                    i.putExtra(PlayerActivity.EXTRA_TORRENT_FILE_TOKEN, token);
                }
                playerLaunched = true;
                startActivity(i);
            });
            h.btnDelete.setOnClickListener(v -> {
                int index = h.getBindingAdapterPosition();
                if (index == RecyclerView.NO_POSITION) return;
                if (torrentManager.getPlaybackSessionId() != null) {
                    Toast.makeText(TorrentActivity.this, R.string.torrent_stop_before_delete,
                        Toast.LENGTH_LONG).show();
                    return;
                }
                if (!TorrentManager.deleteStoredFile(TorrentActivity.this, data.get(index))) {
                    Toast.makeText(TorrentActivity.this, R.string.torrent_delete_failed,
                        Toast.LENGTH_LONG).show();
                    return;
                }
                data.remove(index);
                notifyItemRemoved(index);
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
        loadDownloadedFiles();
        rvDownloaded.postDelayed(() -> {
            if (!isDestroyed() && !isFinishing()) loadDownloadedFiles();
        }, 1000);
        if (playerLaunched && torrentManager.getPlaybackSessionId() == null) {
            playerLaunched = false;
            setStreamControls(false);
            progressBar.setVisibility(View.GONE);
            tvSpeed.setText("");
            tvStatus.setText(lastPlayerAutoCleanup ? R.string.torrent_stopped_deleted
                : R.string.torrent_stopped_kept);
        }
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("torrent_source", etMagnet.getText().toString());
        state.putBoolean("player_launched", playerLaunched);
        state.putBoolean("player_auto_cleanup", lastPlayerAutoCleanup);
    }

    @Override protected void onDestroy() {
        requestGeneration++;
        listGeneration++;
        dismissFilePicker();
        if (torrentCallback != null) torrentManager.detachCallback(torrentCallback);
        if (!isChangingConfigurations() && !playerLaunched) torrentManager.destroy();
        fileExecutor.shutdownNow();
        super.onDestroy();
    }
}
