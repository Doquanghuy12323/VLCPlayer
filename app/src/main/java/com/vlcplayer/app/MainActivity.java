package com.vlcplayer.app;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
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

    private RecyclerView recyclerView;
    private VideoAdapter adapter;
    private ProgressBar progressBar;
    private TextView tvEmpty;
    private final List<VideoItem> videoList = new ArrayList<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        recyclerView = findViewById(R.id.recyclerView);
        progressBar  = findViewById(R.id.progress_bar);
        tvEmpty      = findViewById(R.id.tv_empty);

        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setHasFixedSize(true);
        // Gioi han cache recyclerview tranh tran bo nho
        recyclerView.setItemViewCacheSize(10);
        recyclerView.setDrawingCacheEnabled(false);

        adapter = new VideoAdapter(videoList, this);
        adapter.setHasStableIds(true);
        recyclerView.setAdapter(adapter);

        View fab = findViewById(R.id.fab);
        if (fab != null) fab.setOnClickListener(v -> showUrlDialog());

        checkPermissionsAndLoad();
        new UpdateManager(this).checkForUpdate(true);
        handleShareIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
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
        }
        if (uri != null) {
            final Uri finalUri = uri;
            handler.postDelayed(() -> {
                Intent player = new Intent(this, PlayerActivity.class);
                player.putExtra(PlayerActivity.EXTRA_URI, finalUri.toString());
                String name = finalUri.getLastPathSegment();
                player.putExtra(PlayerActivity.EXTRA_TITLE,
                    name != null ? name : "Video");
                startActivity(player);
            }, 300);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Khi quay lai tu PlayerActivity, dọn Glide memory
        Glide.get(this).clearMemory();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (adapter != null) adapter.clearCache();
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
        String perm = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            ? Manifest.permission.READ_MEDIA_VIDEO
            : Manifest.permission.READ_EXTERNAL_STORAGE;
        if (ContextCompat.checkSelfPermission(this, perm)
                == PackageManager.PERMISSION_GRANTED) {
            loadVideos();
        } else {
            ActivityCompat.requestPermissions(this,
                new String[]{perm}, REQ_PERMISSION);
        }
    }

    @Override
    public void onRequestPermissionsResult(int req,
            @NonNull String[] perms, @NonNull int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        if (req == REQ_PERMISSION && results.length > 0
                && results[0] == PackageManager.PERMISSION_GRANTED) {
            loadVideos();
        } else {
            Toast.makeText(this, "Can quyen truy cap bo nho",
                Toast.LENGTH_LONG).show();
        }
    }

    private void loadVideos() {
        if (progressBar != null) progressBar.setVisibility(View.VISIBLE);
        executor.execute(() -> {
            List<VideoItem> items = new ArrayList<>();
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
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            handler.post(() -> {
                if (progressBar != null) progressBar.setVisibility(View.GONE);
                videoList.clear();
                videoList.addAll(items);
                adapter.notifyDataSetChanged();
                if (tvEmpty != null)
                    tvEmpty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
            });
        });
    }

    @Override
    public void onVideoClick(VideoItem video) {
        PlaylistManager.get().setQueue(videoList,
            videoList.indexOf(video));
        Intent i = new Intent(this, PlayerActivity.class);
        i.putExtra(PlayerActivity.EXTRA_URI, video.getUri().toString());
        i.putExtra(PlayerActivity.EXTRA_TITLE, video.getName());
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
        if (id == R.id.menu_privacy) {
            showPrivacyDialog();
            return true;
        } else if (id == R.id.menu_language) {
            showLanguageDialog();
            return true;
        } else if (id == R.id.menu_handy) {
            showHandyMainDialog();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void togglePrivacy() {
        boolean current = new PrivacyManager(this).isEnabled();
        boolean next = !current;
        java.util.List<String> paths2 = new java.util.ArrayList<>();
        new PrivacyManager(this).setEnabled(next, paths2);
        Toast.makeText(this,
            next ? "Che do bao mat: BAT (Gallery se an video)"
                 : "Che do bao mat: TAT (Gallery se hien video lai)",
            Toast.LENGTH_LONG).show();
    }

    private void showUrlDialog() {
        android.widget.EditText et = new android.widget.EditText(this);
        et.setHint("https://example.com/video.mp4");
        new AlertDialog.Builder(this)
            .setTitle("Phat tu URL")
            .setView(et)
            .setPositiveButton("Phat", (d, w) -> {
                String url = et.getText().toString().trim();
                if (!url.isEmpty()) {
                    Intent i = new Intent(this, PlayerActivity.class);
                    i.putExtra(PlayerActivity.EXTRA_URI, url);
                    i.putExtra(PlayerActivity.EXTRA_TITLE, "URL Stream");
                    startActivity(i);
                }
            })
            .setNegativeButton("Huy", null).show();
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
                        Intent i = new Intent(this, PlayerActivity.class);
                        i.putExtra(PlayerActivity.EXTRA_URI, h.uri);
                        i.putExtra(PlayerActivity.EXTRA_TITLE, h.title);
                        startActivity(i);
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
        boolean current = new PrivacyManager(this).isEnabled();
        boolean next = !current;
        String msg = current ? "Tat che do bao mat?" : "Bat che do bao mat?";
        new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Che do bao mat")
            .setMessage(msg)
            .setPositiveButton("Dong y", (d, w) -> {
                java.util.List<String> paths2 = new java.util.ArrayList<>();
                new PrivacyManager(this).setEnabled(next, paths2);
                android.widget.Toast.makeText(this,
                    next ? "Da bat bao mat" : "Da tat bao mat",
                    android.widget.Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("Huy", null).show();
    }

        private void showLanguageDialog() {
        String[] langs = {"Tieng Viet", "English"};
        String[] codes = {"vi", "en"};
        new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Ngon ngu / Language")
            .setItems(langs, (d, which) -> {
                android.content.SharedPreferences prefs =
                    getSharedPreferences("app_prefs", MODE_PRIVATE);
                prefs.edit().putString("language", codes[which]).apply();
                android.widget.Toast.makeText(this,
                    "Da chon: " + langs[which],
                    android.widget.Toast.LENGTH_SHORT).show();
                recreate();
            }).show();
    }

}