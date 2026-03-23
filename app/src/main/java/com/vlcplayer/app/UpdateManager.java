package com.vlcplayer.app;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class UpdateManager {

    private static final String GITHUB_USER = "Doquanghuy12323";
    private static final String GITHUB_REPO = "VLCPlayer";
    private static final String API_URL =
        "https://api.github.com/repos/" + GITHUB_USER + "/" + GITHUB_REPO + "/releases/latest";

    private final Activity activity;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public UpdateManager(Activity activity) {
        this.activity = activity;
    }

    public void checkForUpdate(boolean silent) {
        executor.execute(() -> {
            try {
                // Bước 1: Gọi API
                HttpURLConnection conn = (HttpURLConnection) new URL(API_URL).openConnection();
                conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
                conn.setRequestProperty("User-Agent", "VLCPlayer-Android");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);

                int responseCode = conn.getResponseCode();
                if (responseCode != 200) {
                    showToast("Lỗi API: HTTP " + responseCode);
                    return;
                }

                // Bước 2: Đọc response
                BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();

                JSONObject json = new JSONObject(sb.toString());
                String tagName = json.getString("tag_name");
                String latestVersion = tagName.replace("v", "").trim();

                // Bước 3: So sánh version
                String currentVersion;
                try {
                    currentVersion = activity.getPackageManager()
                        .getPackageInfo(activity.getPackageName(), 0).versionName;
                } catch (Exception e) {
                    showToast("Lỗi lấy version hiện tại: " + e.getMessage());
                    return;
                }

                if (!silent) {
                    showToast("Hiện tại: " + currentVersion + " | Mới nhất: " + latestVersion);
                }

                // So sánh dạng số
                long latest, current;
                try {
                    latest  = Long.parseLong(latestVersion);
                    current = Long.parseLong(currentVersion);
                } catch (NumberFormatException e) {
                    // Fallback: so sánh string
                    if (latestVersion.compareTo(currentVersion) <= 0) {
                        if (!silent) showToast("Đang dùng phiên bản mới nhất!");
                        return;
                    }
                    latest  = 1;
                    current = 0;
                }

                if (latest <= current) // Đã là mới nhất {
                    if (!silent) showToast("Đang dùng phiên bản mới nhất!");
                    return;
                }

                // Bước 4: Lấy download URL
                JSONArray assets = json.getJSONArray("assets");
                String downloadUrl = null;

                if (assets.length() == 0) {
                    // Không có asset → dùng source download URL
                    showToast("Không có APK trong release. Vui lòng tải thủ công.");
                    return;
                }

                for (int i = 0; i < assets.length(); i++) {
                    JSONObject asset = assets.getJSONObject(i);
                    String name = asset.getString("name");
                    if (name.endsWith(".apk")) {
                        downloadUrl = asset.getString("browser_download_url");
                        break;
                    }
                }

                if (downloadUrl == null) {
                    showToast("Không tìm thấy file APK trong release");
                    return;
                }

                final String url = downloadUrl;
                final String ver = latestVersion;
                mainHandler.post(() -> showUpdateDialog(ver, url));

            } catch (Exception e) {
                if (!silent) {
                    showToast("Lỗi kiểm tra update: " + e.getMessage());
                }
            }
        });
    }

    private void showToast(String msg) {
        mainHandler.post(() -> Toast.makeText(activity, msg, Toast.LENGTH_LONG).show());
    }

    private void showUpdateDialog(String newVersion, String downloadUrl) {
        if (activity.isFinishing()) return;
        new AlertDialog.Builder(activity)
            .setTitle("🎉 Có bản cập nhật mới!")
            .setMessage("Phiên bản " + newVersion + " sẵn sàng.\nBấm Cập nhật để tải và cài đặt.")
            .setPositiveButton("Cập nhật", (d, w) -> downloadAndInstall(downloadUrl))
            .setNegativeButton("Để sau", null)
            .setCancelable(false)
            .show();
    }

    private void downloadAndInstall(String downloadUrl) {
        Toast.makeText(activity, "Đang tải bản cập nhật...", Toast.LENGTH_SHORT).show();

        DownloadManager dm = (DownloadManager)
            activity.getSystemService(Context.DOWNLOAD_SERVICE);

        File outFile = new File(
            activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
            "vlcplayer-update.apk"
        );
        if (outFile.exists()) outFile.delete();

        DownloadManager.Request req = new DownloadManager.Request(Uri.parse(downloadUrl))
            .setTitle("VLC Player Update")
            .setDescription("Đang tải...")
            .setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationUri(Uri.fromFile(outFile));

        long downloadId = dm.enqueue(req);

        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                if (id == downloadId) {
                    activity.unregisterReceiver(this);
                    checkAndInstall(dm, downloadId, outFile);
                }
            }
        };
        activity.registerReceiver(receiver,
            new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
    }

    private void checkAndInstall(DownloadManager dm, long downloadId, File file) {
        Cursor cursor = dm.query(
            new DownloadManager.Query().setFilterById(downloadId));
        if (cursor != null && cursor.moveToFirst()) {
            int status = cursor.getInt(
                cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            cursor.close();
            if (status == DownloadManager.STATUS_SUCCESSFUL) {
                installApk(file);
            } else {
                showToast("Tải thất bại, thử lại sau");
            }
        }
    }

    private void installApk(File apkFile) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            Uri apkUri;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                apkUri = FileProvider.getUriForFile(
                    activity,
                    activity.getPackageName() + ".provider",
                    apkFile
                );
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } else {
                apkUri = Uri.fromFile(apkFile);
            }
            intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
        } catch (Exception e) {
            showToast("Lỗi cài đặt: " + e.getMessage());
        }
    }
}
