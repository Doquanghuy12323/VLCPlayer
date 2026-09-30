package com.vlcplayer.app;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.format.Formatter;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class TorrentActivity extends AppCompatActivity {

    private static final int REQ_PICK_TORRENT = 2001;

    private EditText etMagnet;
    private Button btnStream, btnStop, btnWatch, btnRetry;
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
    private AlertDialog deleteDialog;
    private List<TorrentManager.VideoFileEntry> pendingFiles;
    private int pendingFilesGeneration;
    private boolean activityResumed;
    private boolean updatingSource;
    private String requestSessionId;
    private String lastStreamSource;
    private Uri lastPickedUri;
    private String lastStreamError = "";
    private ErrorKind errorKind = ErrorKind.NONE;
    private ImportTask importTask;
    private final TorrentPlaybackHandoff handoff = new TorrentPlaybackHandoff();
    private final ExecutorService fileExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService importExecutor = Executors.newCachedThreadPool();
    private enum ErrorKind { NONE, IMPORT, STREAM, PLAYER }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(AppLanguageManager.applyLanguage(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_torrent);

        etMagnet    = findViewById(R.id.et_magnet);
        etMagnet.setSaveEnabled(false);
        btnStream   = findViewById(R.id.btn_stream);
        btnStop     = findViewById(R.id.btn_stop);
        btnWatch    = findViewById(R.id.btn_watch);
        btnRetry    = findViewById(R.id.btn_retry);
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
        btnWatch.setOnClickListener(v -> openReadyPlayer(false));
        btnRetry.setOnClickListener(v -> retryLastSource());

        if (savedInstanceState != null) {
            etMagnet.setText(savedInstanceState.getString("torrent_source", ""));
            playerLaunched = savedInstanceState.getBoolean("player_launched", false);
            lastPlayerAutoCleanup = savedInstanceState.getBoolean("player_auto_cleanup", false);
            lastStreamSource = savedInstanceState.getString("last_stream_source");
            String picked = savedInstanceState.getString("last_picked_uri");
            if (picked != null) lastPickedUri = Uri.parse(picked);
            lastStreamError = savedInstanceState.getString("stream_error", "");
            try { errorKind = ErrorKind.valueOf(savedInstanceState.getString("error_kind", "NONE")); }
            catch (IllegalArgumentException ignored) { errorKind = ErrorKind.NONE; }
            if (!reattaching && savedInstanceState.getBoolean("import_pending")) errorKind = ErrorKind.IMPORT;
        }
        etMagnet.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (!updatingSource) {
                    lastPickedUri = null;
                    lastStreamSource = null;
                    clearError();
                }
            }
            @Override public void afterTextChanged(Editable s) { }
        });
        if (reattaching) {
            streamAutoCleanup = torrentManager.isAutoCleanupEnabled();
            autoCleanupSwitch.setChecked(streamAutoCleanup);
            setStreamControls(true);
            requestSessionId = torrentManager.getPlaybackSessionId();
            String liveSource = torrentManager.getStreamSource(requestSessionId);
            String savedSession = savedInstanceState == null ? null
                : savedInstanceState.getString("torrent_session");
            if (requestSessionId == null || !requestSessionId.equals(savedSession) || liveSource == null
                    || !liveSource.equals(lastStreamSource)) {
                lastPickedUri = null;
                playerLaunched = false;
                lastPlayerAutoCleanup = streamAutoCleanup;
                clearError();
            }
            lastStreamSource = liveSource;
            if (liveSource != null) setSourceText(liveSource);
            handoff.restoreLiveSession(requestSessionId);
            torrentCallback = createTorrentCallback(requestGeneration);
            torrentManager.attachCallback(torrentCallback);
        } else {
            playerLaunched = false;
            setStreamControls(false);
            renderError();
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
        etMagnet.setEnabled(!running);
    }

    private boolean isCurrentRequest(int generation) {
        return generation == requestGeneration && !isFinishing() && !isDestroyed();
    }

    private boolean isCurrentSession(int generation) {
        return isCurrentRequest(generation) && requestSessionId != null
            && requestSessionId.equals(torrentManager.getPlaybackSessionId());
    }

    private void setSourceText(String source) {
        updatingSource = true;
        etMagnet.setText(source);
        updatingSource = false;
    }

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        String source = intent.getStringExtra("magnet");
        if ((source == null || source.isEmpty()) && intent.getData() != null) {
            source = intent.getData().toString();
        }
        if (source != null && !source.isEmpty()) {
            if (source.startsWith("content://")) {
                importTorrentFile(Uri.parse(source), intent.getFlags());
                return;
            }
            setSourceText(source);
            startStream();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void pickTorrentFile() {
        if (!btnPickFile.isEnabled()) return;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.setType("*/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i, REQ_PICK_TORRENT);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_PICK_TORRENT && res == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri == null) return;
            importTorrentFile(uri, data.getFlags());
        }
    }

    private void importTorrentFile(Uri uri, int grantFlags) {
        if (isFinishing() || isDestroyed()) return;
        if (torrentManager.getPlaybackSessionId() != null) stopStream();
        final int generation = ++requestGeneration;
        cancelImport();
        clearPendingHandoff();
        clearError();
        lastPickedUri = uri;
        lastStreamSource = null;
        streamAutoCleanup = autoCleanupSwitch.isChecked();
        setStreamControls(true);
        progressBar.setIndeterminate(true);
        progressBar.setVisibility(View.VISIBLE);
        tvStatus.setText(R.string.torrent_importing);
        ImportTask task = new ImportTask();
        importTask = task;
        task.future = importExecutor.submit(() -> {
            File tmp = null;
            try {
                if ((grantFlags & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0) {
                    try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
                    catch (SecurityException | UnsupportedOperationException ignored) { }
                }
                task.checkCanceled();
                File inputs = new File(getCacheDir(), "torrent_inputs");
                if (!inputs.isDirectory() && !inputs.mkdirs())
                    throw new java.io.IOException("Cannot create torrent input directory");
                tmp = File.createTempFile("selected-", ".torrent", inputs);
                try (ParcelFileDescriptor descriptor = getContentResolver().openFileDescriptor(uri, "r", task.cancellation)) {
                    if (descriptor == null) throw new java.io.IOException("Missing torrent descriptor");
                    task.attachDescriptor(descriptor);
                    try (FileOutputStream fos = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[8192];
                    int len;
                    int total = 0;
                    while ((len = task.read(descriptor, buf)) != -1) {
                        task.checkCanceled();
                        total += len;
                        if (total > 8 * 1024 * 1024)
                            throw new java.io.IOException("Torrent metadata exceeds 8 MiB");
                        fos.write(buf, 0, len);
                    }
                    }
                }
                task.checkCanceled();
                final File imported = tmp;
                runOnUiThread(() -> {
                    if (!isCurrentRequest(generation) || task.canceled) {
                        new Thread(imported::delete, "vlc-torrent-input-discard").start();
                        return;
                    }
                    if (importTask == task) importTask = null;
                    String source = Uri.fromFile(imported).toString();
                    setSourceText(source);
                    startStream(source, true);
                });
            } catch (Exception e) {
                if (tmp != null) tmp.delete();
                runOnUiThread(() -> {
                    if (isCurrentRequest(generation) && !task.canceled) {
                        if (importTask == task) importTask = null;
                        setStreamControls(false);
                        progressBar.setVisibility(View.GONE);
                        errorKind = ErrorKind.IMPORT;
                        renderError();
                    }
                });
            } finally {
                task.detachResources();
            }
        });
    }

    private void cancelImport() {
        ImportTask pending = importTask;
        importTask = null;
        if (pending != null) pending.cancel();
    }

    private void startStream() {
        startStream(etMagnet.getText().toString().trim(), false);
    }

    private void startStream(String url, boolean importedSource) {
        if (isFinishing() || isDestroyed()) return;
        if (url.isEmpty()) {
            Toast.makeText(this, R.string.torrent_enter_source, Toast.LENGTH_SHORT).show();
            return;
        }

        boolean isValid = url.startsWith("magnet:")
            || url.startsWith("http://")
            || url.startsWith("https://")
            || url.startsWith("file://")
            || url.startsWith("/");
        if (!isValid) {
            Toast.makeText(this, R.string.torrent_invalid_source, Toast.LENGTH_SHORT).show();
            return;
        }

        if (torrentCallback != null) torrentManager.detachCallback(torrentCallback);
        final int generation = ++requestGeneration;
        cancelImport();
        clearPendingHandoff();
        clearError();
        if (!importedSource) lastPickedUri = null;
        lastStreamSource = url;
        dismissFilePicker();
        playerLaunched = false;
        streamAutoCleanup = autoCleanupSwitch.isChecked();
        filePickerShown = false;
        setStreamControls(true);
        progressBar.setIndeterminate(false);
        progressBar.setProgress(0);
        progressBar.setVisibility(View.VISIBLE);
        tvStatus.setText(R.string.torrent_connecting);
        tvSpeed.setText("");

        torrentCallback = createTorrentCallback(generation);
        torrentManager.startStream(url, streamAutoCleanup, torrentCallback);
        requestSessionId = torrentManager.getPlaybackSessionId();
        handoff.begin(requestSessionId, true);
    }

    private void clearPendingHandoff() {
        handoff.invalidate();
        requestSessionId = null;
        pendingFiles = null;
        dismissFilePicker();
        renderWatch();
    }

    private void clearError() {
        boolean hadError = errorKind != ErrorKind.NONE;
        errorKind = ErrorKind.NONE;
        lastStreamError = "";
        btnRetry.setVisibility(View.GONE);
        if (hadError && torrentManager.getPlaybackSessionId() == null && importTask == null) {
            tvStatus.setText(R.string.torrent_idle);
        }
    }

    private void renderError() {
        if (errorKind == ErrorKind.IMPORT) tvStatus.setText(R.string.torrent_import_error);
        else if (errorKind == ErrorKind.STREAM) tvStatus.setText(getString(R.string.torrent_stream_error, lastStreamError));
        else if (errorKind == ErrorKind.PLAYER) tvStatus.setText(R.string.torrent_player_open_failed);
        boolean retryable = (errorKind == ErrorKind.IMPORT || errorKind == ErrorKind.STREAM)
            && (lastPickedUri != null || (lastStreamSource != null && !lastStreamSource.isEmpty()));
        btnRetry.setVisibility(retryable ? View.VISIBLE : View.GONE);
        btnRetry.setEnabled(retryable);
    }

    private void retryLastSource() {
        if (lastPickedUri != null) importTorrentFile(lastPickedUri, 0);
        else if (lastStreamSource != null) {
            setSourceText(lastStreamSource);
            startStream(lastStreamSource, false);
        }
    }

    private void renderWatch() {
        boolean ready = handoff.canWatch(torrentManager.getPlaybackSessionId())
            && torrentManager.getReadyUrl(handoff.getSessionId()) != null;
        btnWatch.setVisibility(ready ? View.VISIBLE : View.GONE);
        btnWatch.setEnabled(ready && activityResumed);
    }

    private void openReadyPlayer(boolean automatic) {
        String session = torrentManager.getPlaybackSessionId();
        String liveUrl = torrentManager.getReadyUrl(handoff.getSessionId());
        if (liveUrl == null || !handoff.canWatch(session)) {
            handoff.invalidate();
            renderWatch();
            return;
        }
        String url = handoff.reserveLaunch(session, automatic);
        if (url == null) return;
        if (!session.equals(torrentManager.getPlaybackSessionId())
                || !url.equals(torrentManager.getReadyUrl(session))) {
            handoff.completeLaunch(false);
            handoff.invalidate();
            renderWatch();
            return;
        }
        Intent intent = new Intent(this, PlayerActivity.class);
        intent.putExtra(PlayerActivity.EXTRA_URI, url);
        intent.putExtra(PlayerActivity.EXTRA_TITLE, getString(R.string.torrent_player_title));
        intent.putExtra(PlayerActivity.EXTRA_AUTO_CLEANUP_TORRENT, streamAutoCleanup);
        intent.putExtra(PlayerActivity.EXTRA_TORRENT_SESSION_ID, session);
        try {
            startActivity(intent);
            handoff.completeLaunch(true);
            playerLaunched = true;
            lastPlayerAutoCleanup = streamAutoCleanup;
            clearError();
            btnWatch.setEnabled(false);
        } catch (RuntimeException failure) {
            handoff.completeLaunch(false);
            errorKind = ErrorKind.PLAYER;
            renderError();
            renderWatch();
        }
    }

    private TorrentManager.Callback createTorrentCallback(int generation) {
        return new TorrentManager.Callback() {
            @Override
            public void onStatusUpdate(String status) {
                runOnUiThread(() -> {
                    if (isCurrentSession(generation) && errorKind == ErrorKind.NONE
                            && !handoff.canWatch(requestSessionId)) tvStatus.setText(status);
                });
            }

            @Override
            public void onProgress(int progress, float dlSpeed) {
                runOnUiThread(() -> {
                    if (!isCurrentSession(generation)) return;
                    progressBar.setProgress(progress);
                    tvSpeed.setText(getString(R.string.torrent_speed, dlSpeed));
                });
            }

            @Override
            public void onFilesFound(List<TorrentManager.VideoFileEntry> files) {
                runOnUiThread(() -> {
                    if (!isCurrentSession(generation) || filePickerShown) return;
                    pendingFiles = new ArrayList<>(files);
                    pendingFilesGeneration = generation;
                    if (activityResumed) showPendingFilePicker();
                });
            }

            @Override
            public void onReady(String streamUrl) {
                runOnUiThread(() -> {
                    if (!isCurrentSession(generation)) return;
                    boolean automatically = handoff.ready(requestSessionId, streamUrl);
                    if (errorKind != ErrorKind.PLAYER) tvStatus.setText(R.string.torrent_ready);
                    progressBar.setVisibility(View.GONE);
                    pendingFiles = null;
                    dismissFilePicker();
                    renderWatch();
                    if (automatically) openReadyPlayer(true);
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    if (!isCurrentRequest(generation)) return;
                    progressBar.setVisibility(View.GONE);
                    setStreamControls(false);
                    requestGeneration++;
                    cancelImport();
                    clearPendingHandoff();
                    playerLaunched = false;
                    errorKind = ErrorKind.STREAM;
                    lastStreamError = error == null ? "" : error;
                    renderError();
                    torrentManager.detachCallback(torrentCallback);
                    torrentManager.destroy();
                });
            }

            @Override
            public void onStopped() {
                runOnUiThread(() -> {
                    if (!isCurrentRequest(generation)) return;
                    clearPendingHandoff();
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

    private void showPendingFilePicker() {
        if (!activityResumed || pendingFiles == null || filePickerShown
                || !isCurrentSession(pendingFilesGeneration)) return;
        showFilePicker(pendingFiles, pendingFilesGeneration);
    }

    private void showFilePicker(List<TorrentManager.VideoFileEntry> files, int generation) {
        filePickerShown = true;
        tvStatus.setText(getString(R.string.torrent_files_found, files.size()));

        String[] labels = new String[files.size()];
        for (int i = 0; i < files.size(); i++) {
            TorrentManager.VideoFileEntry f = files.get(i);
            String shortName = f.name;
            int slash = shortName.lastIndexOf('/');
            if (slash >= 0) shortName = shortName.substring(slash + 1);
            labels[i] = getString(R.string.torrent_file_entry, shortName, formatSize(f.size));
        }

        filePickerDialog = new AlertDialog.Builder(this)
            .setTitle(R.string.torrent_choose_video)
            .setItems(labels, (d, which) -> {
                filePickerShown = false;
                if (!isCurrentSession(generation)) return;
                pendingFiles = null;
                TorrentManager.VideoFileEntry chosen = files.get(which);
                tvStatus.setText(getString(R.string.torrent_downloading_file, chosen.name));
                progressBar.setVisibility(View.VISIBLE);
                torrentManager.selectFile(chosen.index, torrentCallback);
            })
            .setCancelable(false)
            .setNegativeButton(R.string.torrent_cancel, (d, w) -> {
                filePickerShown = false;
                if (isCurrentRequest(generation)) stopStream();
            })
            .create();
        final AlertDialog shownPicker = filePickerDialog;
        filePickerDialog.setOnDismissListener(d -> {
            if (filePickerDialog == shownPicker) {
                filePickerShown = false;
                filePickerDialog = null;
            }
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
        cancelImport();
        clearPendingHandoff();
        clearError();
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
            h.btnDelete.setContentDescription(getString(R.string.torrent_delete_description, f.getName()));
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
                    setSourceText(resumeSource);
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
                try {
                    startActivity(i);
                    playerLaunched = true;
                } catch (RuntimeException failure) {
                    errorKind = ErrorKind.PLAYER;
                    renderError();
                }
            });
            h.btnDelete.setOnClickListener(v -> {
                int index = h.getBindingAdapterPosition();
                if (index == RecyclerView.NO_POSITION) return;
                if (torrentManager.getPlaybackSessionId() != null) {
                    Toast.makeText(TorrentActivity.this, R.string.torrent_stop_before_delete,
                        Toast.LENGTH_LONG).show();
                    return;
                }
                confirmDelete(data.get(index));
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

    private void confirmDelete(File file) {
        if (deleteDialog != null) deleteDialog.dismiss();
        deleteDialog = new AlertDialog.Builder(this)
            .setTitle(R.string.torrent_delete_title)
            .setMessage(getString(R.string.torrent_delete_confirmation, file.getName()))
            .setNegativeButton(R.string.torrent_cancel, null)
            .setPositiveButton(R.string.torrent_delete_confirm, (dialog, which) -> {
                if (TorrentManager.getActiveManager() != null) {
                    Toast.makeText(this, R.string.torrent_stop_before_delete, Toast.LENGTH_LONG).show();
                    return;
                }
                final Context appContext = getApplicationContext();
                fileExecutor.execute(() -> {
                    boolean active = TorrentManager.getActiveManager() != null;
                    boolean deleted = !active && TorrentManager.deleteStoredFile(appContext, file);
                    runOnUiThread(() -> {
                        if (isDestroyed() || isFinishing()) return;
                        if (deleted) {
                            Toast.makeText(this, R.string.torrent_delete_success, Toast.LENGTH_SHORT).show();
                            loadDownloadedFiles();
                        } else {
                            Toast.makeText(this, active ? R.string.torrent_stop_before_delete
                                : R.string.torrent_delete_failed, Toast.LENGTH_LONG).show();
                        }
                    });
                });
            })
            .create();
        final AlertDialog shownDelete = deleteDialog;
        deleteDialog.setOnDismissListener(dialog -> {
            if (deleteDialog == shownDelete) deleteDialog = null;
        });
        deleteDialog.show();
    }

    private String formatSize(long b) {
        return Formatter.formatShortFileSize(this, b);
    }

    @Override
    protected void onResume() {
        super.onResume();
        activityResumed = true;
        handoff.setResumed(true);
        loadDownloadedFiles();
        rvDownloaded.postDelayed(() -> {
            if (!isDestroyed() && !isFinishing()) loadDownloadedFiles();
        }, 1000);
        if (playerLaunched && torrentManager.getPlaybackSessionId() == null) {
            playerLaunched = false;
            setStreamControls(false);
            progressBar.setVisibility(View.GONE);
            tvSpeed.setText("");
            if (errorKind == ErrorKind.NONE) tvStatus.setText(lastPlayerAutoCleanup ? R.string.torrent_stopped_deleted
                : R.string.torrent_stopped_kept);
        }
        if (requestSessionId != null && !requestSessionId.equals(torrentManager.getPlaybackSessionId())) {
            clearPendingHandoff();
            setStreamControls(false);
            progressBar.setVisibility(View.GONE);
        }
        renderWatch();
        showPendingFilePicker();
        renderError();
    }

    @Override protected void onPause() {
        activityResumed = false;
        handoff.setResumed(false);
        renderWatch();
        dismissFilePicker();
        super.onPause();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("torrent_source", etMagnet.getText().toString());
        state.putBoolean("player_launched", playerLaunched);
        state.putBoolean("player_auto_cleanup", lastPlayerAutoCleanup);
        state.putString("torrent_session", requestSessionId);
        state.putString("last_stream_source", lastStreamSource);
        if (lastPickedUri != null) state.putString("last_picked_uri", lastPickedUri.toString());
        state.putString("error_kind", errorKind.name());
        state.putString("stream_error", lastStreamError);
        state.putBoolean("import_pending", importTask != null);
    }

    @Override protected void onDestroy() {
        requestGeneration++;
        listGeneration++;
        activityResumed = false;
        handoff.invalidate();
        cancelImport();
        dismissFilePicker();
        if (deleteDialog != null) deleteDialog.dismiss();
        if (torrentCallback != null) torrentManager.detachCallback(torrentCallback);
        if (!isChangingConfigurations() && !playerLaunched) torrentManager.destroy();
        fileExecutor.shutdownNow();
        importExecutor.shutdownNow();
        super.onDestroy();
    }

    private static final class ImportTask {
        final CancellationSignal cancellation = new CancellationSignal();
        volatile boolean canceled;
        Future<?> future;
        private ParcelFileDescriptor descriptor;

        synchronized void attachDescriptor(ParcelFileDescriptor opened) throws java.io.IOException {
            checkCanceled();
            descriptor = opened;
        }

        synchronized void detachResources() { descriptor = null; }

        void checkCanceled() throws java.io.IOException {
            if (canceled || Thread.currentThread().isInterrupted() || cancellation.isCanceled()) {
                throw new java.io.IOException("Torrent import cancelled");
            }
        }

        int read(ParcelFileDescriptor opened, byte[] buffer) throws java.io.IOException {
            StructPollfd poll = new StructPollfd();
            poll.fd = opened.getFileDescriptor();
            poll.events = (short) OsConstants.POLLIN;
            while (true) {
                checkCanceled();
                try {
                    int ready = Os.poll(new StructPollfd[]{poll}, 250);
                    checkCanceled();
                    if (ready == 0) continue;
                    if ((poll.revents & (OsConstants.POLLERR | OsConstants.POLLNVAL)) != 0) {
                        throw new java.io.IOException("Torrent descriptor unavailable");
                    }
                    if ((poll.revents & (OsConstants.POLLIN | OsConstants.POLLHUP)) == 0) continue;
                    int bytes = Os.read(poll.fd, buffer, 0, buffer.length);
                    return bytes == 0 ? -1 : bytes;
                } catch (ErrnoException error) {
                    checkCanceled();
                    if (error.errno == OsConstants.EINTR || error.errno == OsConstants.EAGAIN) continue;
                    throw new java.io.IOException("Cannot read torrent input", error);
                }
            }
        }

        void cancel() {
            canceled = true;
            if (future != null) future.cancel(true);
            final ParcelFileDescriptor opened;
            synchronized (this) { opened = descriptor; descriptor = null; }
            new Thread(() -> {
                try { cancellation.cancel(); }
                finally {
                    if (opened != null) {
                        try { opened.close(); } catch (java.io.IOException ignored) { }
                    }
                }
            }, "vlc-torrent-import-cancel").start();
        }
    }
}
