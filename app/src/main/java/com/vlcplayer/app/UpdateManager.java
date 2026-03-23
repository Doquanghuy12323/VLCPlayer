package com.vlcplayer.app;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
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

    // ⚠️ Đổi thành username GitHub của bạn
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

    // Gọi khi app mở — kiểm tra update ngầm
    public void checkForUpdate(boolean silent) {
        executor.execute(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection)
                    new URL(API_URL).openConnection();
                conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);

                if (conn.getResponseCode() != 200) return;

                BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();

                JSONObject json = new JSONObject(sb.toString());
                String tagName = json.getString("tag_name"); // "v202503231045"
                String latestVersion = tagName.replace("v", "");

                // Lấy current versionName
                String currentVersion = activity.getPackageManager()
                    .getPackageInfo(activity.getPackageName(), 0).versionName;

                long latest  = Long.parseLong(latestVersion);
                long current = Long.parseLong(currentVersion);

                if (latest > current) {
                    // Lấy download URL của APK
                    JSONArray assets = json.getJSONArray("assets");
                    String downloadUrl = null;
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject asset = assets.getJSONObject(i);
                        if (asset.getString("name").endsWith(".apk")) {
                            downloadUrl = asset.getString("browser_download_url");
                            break;
                        }
                    }
                    if (downloadUrl == null) return;

                    final String url = downloadUrl;
                    final String version = latestVersion;
                    mainHandler.post(() -> showUpdateDialog(version, url));
                } else if (!silent) {
                    mainHandler.post(() ->
                        Toast.makeText(activity, "Đang dùng phiên bản mới nhất!",
                            Toast.LENGTH_SHORT).show());
                }

            } catch (Exception e) {
                if (!silent) {
                    mainHandler.post(() ->
                        Toast.makeText(activity, "Không kiểm tra được update",
                            Toast.LENGTH_SHORT).show());
                }
            }
        });
    }

    private void showUpdateDialog(String newVersion, String downloadUrl) {
        if (activity.isFinishing()) return;
        new AlertDialog.Builder(activity)
            .setTitle("🎉 Có bản cập nhật mới!")
            .setMessage("Phiên bản " + newVersion + " sẵn sàng.\nBạn có muốn tải và cài đặt ngay không?")
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
            .setDescription("Đang tải bản cập nhật...")
            .setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationUri(Uri.fromFile(outFile));

        long downloadId = dm.enqueue(req);

        // Lắng nghe khi tải xong
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                if (id == downloadId) {
                    activity.unregisterReceiver(this);
                    checkDownloadAndInstall(dm, downloadId, outFile);
                }
            }
        };
        activity.registerReceiver(receiver,
            new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
    }

    private void checkDownloadAndInstall(DownloadManager dm, long downloadId, File file) {
        DownloadManager.Query query = new DownloadManager.Query()
            .setFilterById(downloadId);
        Cursor cursor = dm.query(query);
        if (cursor != null && cursor.moveToFirst()) {
            int status = cursor.getInt(
                cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            cursor.close();

            if (status == DownloadManager.STATUS_SUCCESSFUL) {
                installApk(file);
            } else {
                mainHandler.post(() ->
                    Toast.makeText(activity, "Tải thất bại, thử lại sau",
                        Toast.LENGTH_SHORT).show());
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
            Toast.makeText(activity, "Lỗi cài đặt: " + e.getMessage(),
                Toast.LENGTH_LONG).show();
        }
    }
}
