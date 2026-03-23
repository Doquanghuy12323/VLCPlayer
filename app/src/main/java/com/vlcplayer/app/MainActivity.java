package com.vlcplayer.app;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.format.DateUtils;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.vlcplayer.app.db.AppDatabase;
import com.vlcplayer.app.db.HistoryItem;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity
        implements VideoAdapter.OnVideoClickListener {

    private static final int PERM_REQUEST = 100;
    private static final int PICK_VIDEO   = 101;

    private RecyclerView recyclerView;
    private VideoAdapter adapter;
    private List<VideoItem> videoList = new ArrayList<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        setSupportActionBar((Toolbar) findViewById(R.id.toolbar));

        recyclerView = findViewById(R.id.recyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new VideoAdapter(videoList, this);
        recyclerView.setAdapter(adapter);

        ((FloatingActionButton) findViewById(R.id.fab))
            .setOnClickListener(v -> openFilePicker());

        checkPermissionsAndLoad();
        new UpdateManager(this).checkForUpdate(true);
    }

    private void checkPermissionsAndLoad() {
        String perm = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            ? Manifest.permission.READ_MEDIA_VIDEO
            : Manifest.permission.READ_EXTERNAL_STORAGE;
        if (ContextCompat.checkSelfPermission(this, perm)
                == PackageManager.PERMISSION_GRANTED) {
            loadVideos();
        } else {
            ActivityCompat.requestPermissions(this, new String[]{perm}, PERM_REQUEST);
        }
    }

    private void loadVideos() {
        videoList.clear();
        ContentResolver cr = getContentResolver();
        String[] proj = {
            MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION, MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATA
        };
        try (Cursor c = cr.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                proj, null, null, MediaStore.Video.Media.DATE_ADDED + " DESC")) {
            if (c != null && c.moveToFirst()) {
                do {
                    long id     = c.getLong(c.getColumnIndexOrThrow(MediaStore.Video.Media._ID));
                    String name = c.getString(c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME));
                    long dur    = c.getLong(c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION));
                    long size   = c.getLong(c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE));
                    String path = c.getString(c.getColumnIndexOrThrow(MediaStore.Video.Media.DATA));
                    Uri uri = Uri.withAppendedPath(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI, String.valueOf(id));
                    videoList.add(new VideoItem(id, name, dur, size, path, uri));
                } while (c.moveToNext());
            }
        }
        adapter.notifyDataSetChanged();
        if (videoList.isEmpty())
            Toast.makeText(this, "Không tìm thấy video", Toast.LENGTH_SHORT).show();
    }

    private void openFilePicker() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("video/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(Intent.createChooser(i, "Chọn video"), PICK_VIDEO);
    }

    private void showUrlDialog() {
        EditText input = new EditText(this);
        input.setHint("https://example.com/video.mp4");

        new AlertDialog.Builder(this)
            .setTitle("Phát từ URL")
            .setView(input)
            .setPositiveButton("Phát", (d, w) -> {
                String url = input.getText().toString().trim();
                if (!url.isEmpty()) {
                    Intent intent = new Intent(this, PlayerActivity.class);
                    intent.putExtra(PlayerActivity.EXTRA_URI, url);
                    intent.putExtra(PlayerActivity.EXTRA_TITLE, url);
                    startActivity(intent);
                }
            })
            .setNegativeButton("Hủy", null)
            .show();
    }

    private void showHistoryDialog() {
        executor.execute(() -> {
            List<HistoryItem> history = AppDatabase.get(this).dao().getHistory();
            runOnUiThread(() -> {
                if (history.isEmpty()) {
                    Toast.makeText(this, "Chưa có lịch sử xem", Toast.LENGTH_SHORT).show();
                    return;
                }
                String[] titles = new String[history.size()];
                for (int i = 0; i < history.size(); i++) {
                    HistoryItem h = history.get(i);
                    String ago = DateUtils.getRelativeTimeSpanString(
                        h.watchedAt).toString();
                    titles[i] = h.title + "\n" + formatTime(h.lastPosition)
                        + " / " + formatTime(h.duration) + " · " + ago;
                }
                new AlertDialog.Builder(this)
                    .setTitle("Lịch sử xem")
                    .setItems(titles, (d, which) -> {
                        HistoryItem h = history.get(which);
                        Intent intent = new Intent(this, PlayerActivity.class);
                        intent.putExtra(PlayerActivity.EXTRA_URI, h.uri);
                        intent.putExtra(PlayerActivity.EXTRA_TITLE, h.title);
                        startActivity(intent);
                    })
                    .setNegativeButton("Xóa tất cả", (d, w) -> {
                        executor.execute(() -> AppDatabase.get(this).dao().clearHistory());
                        Toast.makeText(this, "Đã xóa lịch sử", Toast.LENGTH_SHORT).show();
                    })
                    .show();
            });
        });
    }

    private void showPrivacyDialog() {
        boolean enabled = PrivacyManager.isEnabled(this);
        new AlertDialog.Builder(this)
            .setTitle("Chế độ bảo mật")
            .setMessage(enabled
                ? "Đang bật: Video ẩn khỏi Gallery và các app media khác.\nTắt chế độ bảo mật?"
                : "Bật chế độ bảo mật sẽ ẩn video khỏi Gallery và các app media khác.\nBật không?")
            .setPositiveButton(enabled ? "Tắt" : "Bật", (d, w) -> {
                PrivacyManager.setEnabled(this, !enabled);
                Toast.makeText(this,
                    enabled ? "Đã tắt bảo mật" : "Đã bật bảo mật - Video ẩn khỏi Gallery",
                    Toast.LENGTH_LONG).show();
            })
            .setNegativeButton("Hủy", null)
            .show();
    }

    private String formatTime(long ms) {
        long h = ms / 3600000, m = (ms % 3600000) / 60000, s = (ms % 60000) / 1000;
        if (h > 0) return String.format("%d:%02d:%02d", h, m, s);
        return String.format("%02d:%02d", m, s);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == PICK_VIDEO && res == RESULT_OK && data != null && data.getData() != null) {
            Intent i = new Intent(this, PlayerActivity.class);
            i.putExtra(PlayerActivity.EXTRA_URI, data.getData().toString());
            i.putExtra(PlayerActivity.EXTRA_TITLE, "Video");
            startActivity(i);
        }
    }

    @Override public void onVideoClick(VideoItem video) {
        Intent i = new Intent(this, PlayerActivity.class);
        i.putExtra(PlayerActivity.EXTRA_URI, video.getUri().toString());
        i.putExtra(PlayerActivity.EXTRA_TITLE, video.getName());
        startActivity(i);
    }

    @Override
    public void onRequestPermissionsResult(int req, @NonNull String[] perms,
            @NonNull int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        if (req == PERM_REQUEST && results.length > 0
                && results[0] == PackageManager.PERMISSION_GRANTED) loadVideos();
    }

    @Override public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_refresh) { checkPermissionsAndLoad(); return true; }
        if (id == R.id.action_url) { showUrlDialog(); return true; }
        if (id == R.id.action_history) { showHistoryDialog(); return true; }
        if (id == R.id.action_privacy) { showPrivacyDialog(); return true; }
        if (id == R.id.action_update) {
            new UpdateManager(this).checkForUpdate(false); return true; }
        return super.onOptionsItemSelected(item);
    }
}
