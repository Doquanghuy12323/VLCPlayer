package com.vlcplayer.app;

import android.Manifest;
import android.content.Intent;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.vlcplayer.app.db.AppDatabase;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity
    implements VideoAdapter.OnVideoClickListener {

    private static final int REQ_PERMISSION = 100;
    private static final int REQ_VIDEO_DOCUMENT = 2002;
    private static final String PREF_PERMISSION_REQUESTED = "video_permission_requested";

    private enum LibraryState { LOADING, CONTENT, EMPTY, PERMISSION, ERROR }

    private RecyclerView recyclerView;
    private VideoAdapter adapter;
    private ProgressBar progressBar;
    private View libraryState;
    private TextView stateTitle;
    private TextView stateMessage;
    private Button statePrimary;
    private Button stateSecondary;
    private final List<VideoItem> videoList = new ArrayList<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private UpdateManager updateManager;
    private int scanGeneration;
    private boolean returningFromSettings;
    private boolean privacyToggleInProgress;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(AppLanguageManager.applyLanguage(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TorrentManager.cleanupOrphanedCache(this);
        TranscodeManager.cleanupLegacyCache(this);
        new PrivacyManager(this).applyWindowSecurity(this);
        setContentView(R.layout.activity_main);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        recyclerView = findViewById(R.id.recyclerView);
        progressBar  = findViewById(R.id.progress_bar);
        libraryState = findViewById(R.id.library_state);
        stateTitle = findViewById(R.id.state_title);
        stateMessage = findViewById(R.id.state_message);
        statePrimary = findViewById(R.id.state_primary);
        stateSecondary = findViewById(R.id.state_secondary);

        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setHasFixedSize(true);
        // Gioi han cache recyclerview tranh tran bo nho
        recyclerView.setItemViewCacheSize(10);
        recyclerView.setDrawingCacheEnabled(false);

        adapter = new VideoAdapter(videoList, this);
        adapter.setHasStableIds(true);
        recyclerView.setAdapter(adapter);

        View fab = findViewById(R.id.fab);
        fab.setOnClickListener(v -> showOpenChoices());

        checkPermissionsAndLoad();
        updateManager = new UpdateManager(this);
        updateManager.checkForUpdate(true);
        if (savedInstanceState == null) handleShareIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleShareIntent(intent);
    }

    private void handleShareIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        Uri uri = null;
        if (Intent.ACTION_VIEW.equals(action)) {
            uri = intent.getData();
        } else if (Intent.ACTION_SEND.equals(action)) {
            uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (uri == null) {
                String sharedUrl = intent.getStringExtra(Intent.EXTRA_TEXT);
                if (sharedUrl != null && (sharedUrl.startsWith("http://")
                        || sharedUrl.startsWith("https://"))) {
                    openSingleVideo(sharedUrl, getString(R.string.open_video_url));
                    return;
                }
            }
        }
        if (uri != null) {
            openSingleVideo(uri.toString(), getVideoTitle(uri));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Khi quay lai tu PlayerActivity, dọn Glide memory
        Glide.get(this).clearMemory();
        if (returningFromSettings) {
            returningFromSettings = false;
            if (hasVideoPermission()) loadVideos();
            else showLibraryState(LibraryState.PERMISSION);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (adapter != null) adapter.clearCache();
        if (updateManager != null) updateManager.destroy();
        executor.shutdown();
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        Glide.get(this).clearMemory();
        if (adapter != null) adapter.clearCache();
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_MODERATE) {
            Glide.get(this).clearMemory();
        }
    }

    private void checkPermissionsAndLoad() {
        if (hasVideoPermission()) {
            loadVideos();
        } else {
            showLibraryState(LibraryState.PERMISSION);
            if (!getPreferences(MODE_PRIVATE).getBoolean(PREF_PERMISSION_REQUESTED, false)) {
                requestVideoPermission();
            }
        }
    }

    private String videoPermission() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            ? Manifest.permission.READ_MEDIA_VIDEO
            : Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    private boolean hasVideoPermission() {
        return ContextCompat.checkSelfPermission(this, videoPermission())
            == PackageManager.PERMISSION_GRANTED;
    }

    private void requestVideoPermission() {
        if (hasVideoPermission()) {
            loadVideos();
            return;
        }
        String permission = videoPermission();
        boolean wasRequested = getPreferences(MODE_PRIVATE)
            .getBoolean(PREF_PERMISSION_REQUESTED, false);
        if (wasRequested && !ActivityCompat.shouldShowRequestPermissionRationale(
                this, permission)) {
            returningFromSettings = true;
            Intent settings = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName()));
            startActivity(settings);
            return;
        }
        getPreferences(MODE_PRIVATE).edit()
            .putBoolean(PREF_PERMISSION_REQUESTED, true).apply();
        ActivityCompat.requestPermissions(this, new String[]{permission}, REQ_PERMISSION);
    }

    @Override
    public void onRequestPermissionsResult(int req,
            @NonNull String[] perms, @NonNull int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        if (req != REQ_PERMISSION) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED)
            loadVideos();
        else showLibraryState(LibraryState.PERMISSION);
    }

    private void loadVideos() {
        if (!hasVideoPermission()) {
            showLibraryState(LibraryState.PERMISSION);
            return;
        }
        showLibraryState(LibraryState.LOADING);
        final int generation = ++scanGeneration;
        executor.execute(() -> {
            List<VideoItem> items = new ArrayList<>();
            boolean failed = false;
            String[] proj = {
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.DURATION,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.DATA
            };
            String sort = MediaStore.Video.Media.DATE_ADDED + " DESC";
            try (Cursor c = getContentResolver().query(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    proj, null, null, sort)) {
                if (c != null) {
                    int iId   = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID);
                    int iName = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME);
                    int iDur  = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION);
                    int iSize = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE);
                    while (c.moveToNext()) {
                        long id  = c.getLong(iId);
                        Uri uri  = Uri.withAppendedPath(
                            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                            String.valueOf(id));
                        items.add(new VideoItem(
                            id,
                            c.getString(iName),
                            c.getLong(iDur),
                            c.getLong(iSize),
                            c.getString(c.getColumnIndexOrThrow(android.provider.MediaStore.Video.Media.DATA)),
                            uri));
                    }
                } else failed = true;
            } catch (Exception e) {
                android.util.Log.e("MainActivity", "Could not scan videos", e);
                failed = true;
            }
            final boolean scanFailed = failed;
            handler.post(() -> {
                if (isDestroyed() || generation != scanGeneration) return;
                if (scanFailed) {
                    showLibraryState(hasVideoPermission()
                        ? LibraryState.ERROR : LibraryState.PERMISSION);
                    return;
                }
                videoList.clear();
                videoList.addAll(items);
                adapter.notifyDataSetChanged();
                showLibraryState(items.isEmpty() ? LibraryState.EMPTY : LibraryState.CONTENT);
            });
        });
    }

    private void showLibraryState(LibraryState state) {
        recyclerView.setVisibility(state == LibraryState.CONTENT ? View.VISIBLE : View.GONE);
        libraryState.setVisibility(state == LibraryState.CONTENT ? View.GONE : View.VISIBLE);
        progressBar.setVisibility(state == LibraryState.LOADING ? View.VISIBLE : View.GONE);
        statePrimary.setVisibility(View.GONE);
        stateSecondary.setVisibility(View.GONE);
        statePrimary.setOnClickListener(null);
        stateSecondary.setOnClickListener(null);
        switch (state) {
            case LOADING:
                stateTitle.setText(R.string.library_loading_title);
                stateMessage.setText(R.string.library_loading_message);
                break;
            case EMPTY:
                stateTitle.setText(R.string.library_empty_title);
                stateMessage.setText(R.string.library_empty_message);
                setStateActions(R.string.open_local_video, v -> openLocalVideo(),
                    R.string.library_retry, v -> loadVideos());
                break;
            case PERMISSION:
                stateTitle.setText(R.string.library_permission_title);
                stateMessage.setText(R.string.library_permission_message);
                boolean needsSettings = getPreferences(MODE_PRIVATE)
                    .getBoolean(PREF_PERMISSION_REQUESTED, false)
                    && !ActivityCompat.shouldShowRequestPermissionRationale(
                        this, videoPermission());
                setStateActions(needsSettings ? R.string.library_permission_settings
                        : R.string.library_permission_grant,
                    v -> requestVideoPermission(),
                    R.string.open_local_video, v -> openLocalVideo());
                break;
            case ERROR:
                stateTitle.setText(R.string.library_error_title);
                stateMessage.setText(R.string.library_error_message);
                setStateActions(R.string.library_retry, v -> loadVideos(),
                    R.string.open_local_video, v -> openLocalVideo());
                break;
            case CONTENT:
                break;
        }
    }

    private void setStateActions(int primaryLabel, View.OnClickListener primaryAction,
            int secondaryLabel, View.OnClickListener secondaryAction) {
        statePrimary.setText(primaryLabel);
        statePrimary.setOnClickListener(primaryAction);
        statePrimary.setVisibility(View.VISIBLE);
        stateSecondary.setText(secondaryLabel);
        stateSecondary.setOnClickListener(secondaryAction);
        stateSecondary.setVisibility(View.VISIBLE);
    }

    @Override
    public void onVideoClick(VideoItem video) {
        PlaylistManager.get().setQueue(videoList,
            videoList.indexOf(video));
        Intent i = new Intent(this, PlayerActivity.class);
        i.putExtra(PlayerActivity.EXTRA_URI, video.getUri().toString());
        i.putExtra(PlayerActivity.EXTRA_TITLE, video.getName());
        i.putExtra(PlayerActivity.EXTRA_USE_PLAYLIST, true);
        startActivity(i);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(android.view.MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_refresh) {
            checkPermissionsAndLoad();
            return true;
        } else if (id == R.id.action_history) {
            showHistoryDialog();
            return true;
        } else if (id == R.id.action_privacy) {
            showPrivacyDialog();
            return true;
        } else if (id == R.id.action_translate) {
            showTranslateSettings();
            return true;
        } else if (id == R.id.action_clean) {
            cleanApp();
            return true;
        } else if (id == R.id.action_update) {
            Toast.makeText(this, "Dang kiem tra cap nhat...", Toast.LENGTH_SHORT).show();
            if (updateManager != null) updateManager.checkForUpdate(false);
            return true;
        } else if (id == R.id.action_ai) {
            startActivity(new android.content.Intent(this, GeminiChatActivity.class));
            return true;
        } else if (id == R.id.action_manga_local) {
            openMangaFilePicker();
            return true;
        } else if (id == R.id.action_manga_online) {
            startActivity(new android.content.Intent(this, MangaBrowserActivity.class));
            return true;
        } else if (id == R.id.action_torrent) {
            startActivity(new android.content.Intent(this, TorrentActivity.class));
            return true;
        } else if (id == R.id.action_lan_cast) {
            startActivity(new Intent(this, TranscodeActivity.class));
            return true;
        } else if (id == R.id.action_language) {
            showLanguageDialog();
            return true;
        } else if (id == R.id.action_handy) {
            showHandyMainDialog();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void showOpenChoices() {
        new AlertDialog.Builder(this)
            .setTitle(R.string.open_source_title)
            .setItems(new String[]{getString(R.string.open_local_video),
                getString(R.string.open_video_url)}, (dialog, which) -> {
                    if (which == 0) openLocalVideo();
                    else showUrlDialog();
                })
            .show();
    }

    private void openLocalVideo() {
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        picker.addCategory(Intent.CATEGORY_OPENABLE);
        picker.setType("video/*");
        picker.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
            | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(picker, REQ_VIDEO_DOCUMENT);
    }

    private String getVideoTitle(Uri uri) {
        if ("content".equals(uri.getScheme())) {
            try (Cursor cursor = getContentResolver().query(uri,
                    new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    String name = cursor.getString(0);
                    if (name != null && !name.trim().isEmpty()) return name;
                }
            } catch (Exception ignored) {
                // A few document providers do not expose display names.
            }
        }
        String name = uri.getLastPathSegment();
        return name == null || name.trim().isEmpty()
            ? getString(R.string.open_video) : name;
    }

    private void openSingleVideo(String uri, String title) {
        PlaylistManager.get().clear();
        Intent player = new Intent(this, PlayerActivity.class);
        player.putExtra(PlayerActivity.EXTRA_URI, uri);
        player.putExtra(PlayerActivity.EXTRA_TITLE,
            title == null || title.trim().isEmpty() ? getString(R.string.open_video) : title);
        player.putExtra(PlayerActivity.EXTRA_USE_PLAYLIST, false);
        startActivity(player);
    }

    private void showUrlDialog() {
        android.widget.EditText et = new android.widget.EditText(this);
        et.setHint("https://example.com/video.mp4");
        new AlertDialog.Builder(this)
            .setTitle(R.string.url_dialog_title)
            .setView(et)
            .setPositiveButton(R.string.url_dialog_play, (d, w) -> {
                String url = et.getText().toString().trim();
                if (!url.isEmpty()) {
                    if (url.contains("mega.nz") || url.contains("mega.co.nz")) {
                        PlaylistManager.get().clear();
                        Intent i = new Intent(this, MangaBrowserActivity.class);
                        i.putExtra("start_url", url);
                        startActivity(i);
                    } else {
                        openSingleVideo(url, "URL Stream");
                    }
                }
            })
            .setNegativeButton(R.string.cancel, null).show();
    }

    private void showHistoryDialog() {
        executor.execute(() -> {
            List<com.vlcplayer.app.db.HistoryItem> hist =
                AppDatabase.get(this).dao().getHistory();
            handler.post(() -> {
                if (hist.isEmpty()) {
                    Toast.makeText(this, "Chua co lich su xem",
                        Toast.LENGTH_SHORT).show();
                    return;
                }
                String[] names = new String[hist.size()];
                for (int i = 0; i < hist.size(); i++)
                    names[i] = hist.get(i).title;
                new AlertDialog.Builder(this)
                    .setTitle("Lich su xem")
                    .setItems(names, (d, w) -> {
                        com.vlcplayer.app.db.HistoryItem h = hist.get(w);
                        openSingleVideo(h.uri, h.title);
                    })
                    .setNegativeButton("Dong", null).show();
            });
        });
    }

    private void showTranslateSettings() {
        TranslationManager tm = new TranslationManager(this);
        String[][] langs = TranslationManager.LANGUAGES;
        String[] names = new String[langs.length];
        String cur = tm.getTargetLanguage();
        int curIdx = 0;
        for (int i = 0; i < langs.length; i++) {
            names[i] = langs[i][0];
            if (langs[i][1].equals(cur)) curIdx = i;
        }
        final int[] sel = {curIdx};
        new AlertDialog.Builder(this)
            .setTitle("Ngon ngu dich AI")
            .setSingleChoiceItems(names, curIdx, (d, w) -> sel[0] = w)
            .setPositiveButton("Luu", (d, w) -> {
                tm.setTargetLanguage(langs[sel[0]][1]);
                Toast.makeText(this, "Da chon: " + langs[sel[0]][0],
                    Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("Huy", null).show();
    }

    private void cleanApp() {
        long cacheSize = 0;
        if (getCacheDir() != null) cacheSize += getDirSize(getCacheDir());
        if (getExternalCacheDir() != null)
            cacheSize += getDirSize(getExternalCacheDir());
        final long finalSize = cacheSize / 1024;

        Runtime rt = Runtime.getRuntime();
        long usedMb = (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024;

        new AlertDialog.Builder(this)
            .setTitle("Don dep")
            .setMessage("Cache hien tai: " + finalSize + " KB\n"
                + "RAM app dang dung: " + usedMb + " MB\n\n"
                + "Xoa cache thumbnail va lich su cu?")
            .setPositiveButton("Don sach", (d, w) -> {
                new Thread(() -> {
                    Glide.get(this).clearDiskCache();
                    deleteDir(getCacheDir());
                    if (getExternalCacheDir() != null)
                        deleteDir(getExternalCacheDir());
                    AppDatabase.get(this).dao().deleteOldHistory(
                        System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000);
                    System.gc();
                    handler.post(() -> {
                        Glide.get(this).clearMemory();
                        if (adapter != null) adapter.clearCache();
                        Toast.makeText(this, "Da don sach!",
                            Toast.LENGTH_SHORT).show();
                    });
                }).start();
            })
            .setNegativeButton("Huy", null).show();
    }

    private long getDirSize(java.io.File dir) {
        long size = 0;
        if (dir == null) return 0;
        java.io.File[] files = dir.listFiles();
        if (files == null) return 0;
        for (java.io.File f : files)
            size += f.isDirectory() ? getDirSize(f) : f.length();
        return size;
    }

    private void deleteDir(java.io.File dir) {
        if (dir == null) return;
        java.io.File[] files = dir.listFiles();
        if (files != null) for (java.io.File f : files) {
            if (f.isDirectory()) deleteDir(f);
            else f.delete();
        }
    }
    private void showHandyMainDialog() {
        android.content.SharedPreferences p = getSharedPreferences("handy_prefs", MODE_PRIVATE);
        String k = p.getString("connection_key", "");
        String info = k.isEmpty() ? "Chua co key. Lay tai handyfeeling.com" : "Key: " + k.substring(0, Math.min(6,k.length())) + "...";
        android.widget.EditText et = new android.widget.EditText(this);
        et.setHint("Connection Key"); et.setText(k); et.setSingleLine(true);
        new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("The Handy").setMessage(info)
            .setPositiveButton("Nhap Key", (d, w) ->
                new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Connection Key").setView(et)
                    .setPositiveButton("Luu", (d2, w2) -> {
                        String nk = et.getText().toString().trim();
                        if (!nk.isEmpty()) { p.edit().putString("connection_key", nk).apply();
                        android.widget.Toast.makeText(this,"Da luu!",android.widget.Toast.LENGTH_SHORT).show(); }
                    }).setNegativeButton("Huy", null).show())
            .setNegativeButton("Dong", null).show();
    }

    private void showPrivacyDialog() {
        if (privacyToggleInProgress) return;
        boolean current = new PrivacyManager(this).isEnabled();
        boolean next = !current;
        String msg = current ? "Tat che do bao mat?" : "Bat che do bao mat?";
        new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Che do bao mat")
            .setMessage(msg)
            .setPositiveButton("Dong y", (d, w) -> {
                java.util.List<String> paths2 = next
                    ? getVideoPaths() : java.util.Collections.emptyList();
                privacyToggleInProgress = true;
                Toast.makeText(this, R.string.privacy_working, Toast.LENGTH_SHORT).show();
                executor.execute(() -> {
                    int changed = new PrivacyManager(this).setEnabled(next, paths2);
                    handler.post(() -> {
                        if (isDestroyed()) return;
                        privacyToggleInProgress = false;
                        int message = changed < 0
                            ? (next ? R.string.privacy_enable_partial : R.string.privacy_disable_partial)
                            : (next ? R.string.privacy_enabled_count : R.string.privacy_disabled_count);
                        Toast.makeText(this, changed < 0 ? getString(message)
                            : getString(message, changed), Toast.LENGTH_LONG).show();
                        recreate();
                    });
                });
            })
            .setNegativeButton("Huy", null).show();
    }

    private void openMangaFilePicker() {
        android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("*/*");
        intent.putExtra(android.content.Intent.EXTRA_MIME_TYPES,
            new String[]{"application/zip","application/x-cbz"});
        intent.addCategory(android.content.Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent, 2001);
    }

    private void showLanguageDialog() {
        String[][] available = AppLanguageManager.LANGUAGES;
        String[] langs = new String[available.length];
        for (int i = 0; i < available.length; i++) langs[i] = available[i][0];
        new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Ngon ngu / Language")
            .setItems(langs, (d, which) -> {
                AppLanguageManager.saveLanguage(this, available[which][1]);
                android.widget.Toast.makeText(this,
                    "Da chon: " + langs[which],
                    android.widget.Toast.LENGTH_SHORT).show();
                recreate();
            }).show();
    }

    private java.util.List<String> getVideoPaths() {
        java.util.List<String> paths = new java.util.ArrayList<>();
        for (VideoItem item : videoList) {
            if (item.getPath() != null && !item.getPath().trim().isEmpty()) {
                paths.add(item.getPath());
            }
        }
        return paths;
    }


    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_VIDEO_DOCUMENT && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                try {
                    getContentResolver().takePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (SecurityException ignored) {
                    // The current read grant still permits playback when persistence is unavailable.
                }
                openSingleVideo(uri.toString(), getVideoTitle(uri));
            }
            return;
        }
        if (requestCode == 2001 && resultCode == RESULT_OK && data != null) {
            android.net.Uri uri = data.getData();
            if (uri != null) {
                android.content.Intent intent = new android.content.Intent(this, MangaActivity.class);
                intent.setData(uri);
                startActivity(intent);
            }
        }
    }

}
