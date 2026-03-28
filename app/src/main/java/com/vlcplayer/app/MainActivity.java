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
        // Set toan bo danh sach vao queue, bat dau tu video duoc chon
        int index = videoList.indexOf(video);
        PlaylistManager.get().setQueue(videoList, index);
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
        if (id == R.id.action_translate) { showTranslateLangDialog(); return true; }
        if (id == R.id.action_url) { showUrlDialog(); return true; }
        if (id == R.id.action_history) { showHistoryDialog(); return true; }
        
        if (id == R.id.action_clean) { showCleanDialog(); return true; }
        
        if (id == R.id.action_privacy) {
            showCloudOrVaultDialog();
            return true;
        }
        if (id == R.id.action_update) {
            new UpdateManager(this).checkForUpdate(false); return true; }
        return super.onOptionsItemSelected(item);
    }

    private void showTranslateLangDialog() {
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
                Toast.makeText(this, "Da chon: " + langs[sel[0]][0], Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("Huy", null).show();
    }

    private void showCleanDialog() {
        // Tinh toan cache hien tai
        long thumbCache = getCacheDir().length();
        long extCache = getExternalCacheDir() != null ? getExternalCacheDir().length() : 0;
        long totalCache = (thumbCache + extCache) / 1024; // KB

        Runtime rt = Runtime.getRuntime();
        long usedMem = (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024; // MB
        long maxMem  = rt.maxMemory() / 1024 / 1024;

        String info = "RAM app dang dung: " + usedMem + " MB / " + maxMem + " MB\n"
            + "Cache thumbnail: " + totalCache + " KB\n\n"
            + "Co the don sach:\n"
            + "- Cache thumbnail Glide\n"
            + "- Cache file tam\n"
            + "- Lich su va bookmark cu\n\n"
            + "Luu y: Android quan ly RAM tu dong, app khong the don RAM he thong.";

        new android.app.AlertDialog.Builder(this)
            .setTitle("Don dep & Thong tin")
            .setMessage(info)
            .setPositiveButton("Don sach cache", (d, w) -> cleanAppCache())
            .setNeutralButton("Don lich su cu", (d, w) -> cleanOldHistory())
            .setNegativeButton("Dong", null)
            .show();
    }

    private void cleanAppCache() {
        android.widget.ProgressBar pb = new android.widget.ProgressBar(this,
            null, android.R.attr.progressBarStyleHorizontal);
        pb.setIndeterminate(true);
        android.app.AlertDialog loading = new android.app.AlertDialog.Builder(this)
            .setTitle("Dang don sach...")
            .setView(pb)
            .setCancelable(false)
            .show();

        new Thread(() -> {
            try {
                // 1. Xoa Glide cache (thumbnail)
                com.bumptech.glide.Glide.get(this).clearDiskCache();

                // 2. Xoa cache thu muc app
                deleteDir(getCacheDir());
                if (getExternalCacheDir() != null) deleteDir(getExternalCacheDir());

                // 3. Goi garbage collector
                System.gc();
                Runtime.getRuntime().gc();

                // 4. Xoa thumbnail cache trong adapter
                runOnUiThread(() -> {
                    com.bumptech.glide.Glide.get(this).clearMemory();
                });

                Thread.sleep(500);

                runOnUiThread(() -> {
                    loading.dismiss();
                    Runtime rt = Runtime.getRuntime();
                    long freeMem = rt.freeMemory() / 1024 / 1024;
                    android.widget.Toast.makeText(this,
                        "Da don sach! RAM trong: " + freeMem + " MB",
                        android.widget.Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    loading.dismiss();
                    android.widget.Toast.makeText(this,
                        "Loi: " + e.getMessage(),
                        android.widget.Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }

    private void cleanOldHistory() {
        executor.execute(() -> {
            // Xoa lich su xem qua 30 ngay
            long cutoff = System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000);
            AppDatabase.get(this).dao().deleteOldHistory(cutoff);
            runOnUiThread(() ->
                android.widget.Toast.makeText(this,
                    "Da xoa lich su cu hon 30 ngay",
                    android.widget.Toast.LENGTH_SHORT).show());
        });
    }

    private void deleteDir(java.io.File dir) {
        if (dir == null || !dir.exists()) return;
        java.io.File[] files = dir.listFiles();
        if (files != null) for (java.io.File f : files) {
            if (f.isDirectory()) deleteDir(f);
            else f.delete();
        }
    }

    

    private void showCloudOrVaultDialog() {
        androidx.biometric.BiometricManager bm = androidx.biometric.BiometricManager.from(this);
        int canAuth = bm.canAuthenticate(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK | androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL);
        
        // Bẫy lỗi: Nếu máy không có vân tay hoặc chưa cài mã PIN -> Bỏ qua bảo mật, mở thẳng Cloud
        if (canAuth != androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS) {
            android.widget.Toast.makeText(this, "Chưa cài vân tay/PIN. Mở chế độ mặc định.", android.widget.Toast.LENGTH_LONG).show();
            showVaultMenuDialog();
            return;
        }

        androidx.biometric.BiometricPrompt prompt = new androidx.biometric.BiometricPrompt(this,
            androidx.core.content.ContextCompat.getMainExecutor(this),
            new androidx.biometric.BiometricPrompt.AuthenticationCallback() {
                @Override public void onAuthenticationSucceeded(androidx.biometric.BiometricPrompt.AuthenticationResult result) {
                    showVaultMenuDialog();
                }
                @Override public void onAuthenticationError(int errCode, CharSequence errString) {
                    android.widget.Toast.makeText(MainActivity.this, "Lỗi xác thực: " + errString, android.widget.Toast.LENGTH_SHORT).show();
                }
            });

        androidx.biometric.BiometricPrompt.PromptInfo info = new androidx.biometric.BiometricPrompt.PromptInfo.Builder()
            .setTitle("VLC Vault & Cloud")
            .setSubtitle("Xác thực sinh trắc học để truy cập")
            .setAllowedAuthenticators(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK | androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            .build();
        prompt.authenticate(info);
    }

    private void showNetworkDialog() {
        android.widget.EditText input = new android.widget.EditText(MainActivity.this);
        input.setHint("smb://user:pass@192.168.x.x/phim.mkv hoặc URL Google Drive");
        new android.app.AlertDialog.Builder(MainActivity.this)
            .setTitle("Truy cập Cloud / NAS")
            .setView(input)
            .setPositiveButton("Phát ngay", (d, w) -> {
                String url = input.getText().toString().trim();
                if(!url.isEmpty()) {
                    android.content.Intent i = new android.content.Intent(MainActivity.this, PlayerActivity.class);
                    i.putExtra(PlayerActivity.EXTRA_URI, url);
                    i.putExtra(PlayerActivity.EXTRA_TITLE, "Network Stream");
                    startActivity(i);
                }
            })
            .setNegativeButton("Hủy", null)
            .show();
    }


    private void showVaultMenuDialog() {
        String[] options = {"🌐 Truy cập Cloud / NAS", "🔒 Ẩn video (Tạo .nomedia)", "🔓 Hiện video (Xóa .nomedia)"};
        new android.app.AlertDialog.Builder(this)
            .setTitle("VLC Vault Khóa Sinh Trắc")
            .setItems(options, (d, w) -> {
                if (w == 0) {
                    showNetworkDialog();
                } else if (w == 1) {
                    togglePrivacyMode(true);
                } else if (w == 2) {
                    togglePrivacyMode(false);
                }
            }).show();
    }

    
    private String getRealPathFromURI(android.net.Uri contentUri) {
        if (contentUri == null) return null;
        if ("file".equals(contentUri.getScheme())) return contentUri.getPath();
        String[] proj = { android.provider.MediaStore.Video.Media.DATA };
        try (android.database.Cursor cursor = getContentResolver().query(contentUri, proj, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int col = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Video.Media.DATA);
                return cursor.getString(col);
            }
        } catch (Exception ignored) {}
        return null;
    }


    private void togglePrivacyMode(boolean hide) {
        new Thread(() -> {
            if (videoList == null || videoList.isEmpty()) {
                runOnUiThread(() -> android.widget.Toast.makeText(MainActivity.this, "Không có video để xử lý", android.widget.Toast.LENGTH_SHORT).show());
                return;
            }
            
            java.util.HashSet<String> dirs = new java.util.HashSet<>();
            java.util.ArrayList<String> pathsToUpdate = new java.util.ArrayList<>();
            
            for (VideoItem v : videoList) {
                String path = getRealPathFromURI(v.getUri());
                if (path != null) {
                    pathsToUpdate.add(path);
                    java.io.File f = new java.io.File(path);
                    if (f.getParentFile() != null) {
                        dirs.add(f.getParentFile().getAbsolutePath());
                    }
                }
            }
            
            int count = 0;
            for (String dirPath : dirs) {
                java.io.File nomedia = new java.io.File(dirPath, ".nomedia");
                try {
                    if (hide) {
                        if (!nomedia.exists() && nomedia.createNewFile()) count++;
                    } else {
                        if (nomedia.exists() && nomedia.delete()) count++;
                    }
                } catch (Exception ignored) {}
            }
            
            // XỬ LÝ TRIỆT ĐỂ BỘ SƯU TẬP HỆ THỐNG (GALLERY / MEDIASTORE)
            if (hide) {
                // Xóa cứng bản ghi khỏi hệ thống để biến mất lập tức
                for (String path : pathsToUpdate) {
                    try {
                        getContentResolver().delete(
                            android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                            android.provider.MediaStore.Video.Media.DATA + "=?",
                            new String[]{path}
                        );
                    } catch (Exception ignored) {}
                }
            } else {
                // Ép MediaScanner quét lại để khôi phục ảnh/video lên Bộ sưu tập
                String[] pathArray = pathsToUpdate.toArray(new String[0]);
                android.media.MediaScannerConnection.scanFile(MainActivity.this, pathArray, null, null);
            }
            
            final int finalCount = count;
            runOnUiThread(() -> {
                String msg = hide ? "Đã khóa và xóa bóng khỏi Bộ sưu tập (" + finalCount + " thư mục)." 
                                  : "Đã mở khóa và khôi phục vào Bộ sưu tập (" + finalCount + " thư mục).";
                android.widget.Toast.makeText(MainActivity.this, msg, android.widget.Toast.LENGTH_LONG).show();
            });
        }).start();
    }

}