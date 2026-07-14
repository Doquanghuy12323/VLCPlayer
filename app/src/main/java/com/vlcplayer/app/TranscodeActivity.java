package com.vlcplayer.app;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class TranscodeActivity extends AppCompatActivity {

    private static final int REQ_VIDEO = 2002;
    private EditText etVideoPath;
    private Button btnStartCast, btnStopCast;
    private ProgressBar progressBar;
    private TextView tvStatus, tvDetails;
    private TranscodeManager transcodeManager;
    private final ExecutorService fileExecutor = Executors.newSingleThreadExecutor();
    private String generatedLanUrl = "";

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(AppLanguageManager.applyLanguage(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_transcode);

        etVideoPath = findViewById(R.id.et_video_path);
        btnStartCast = findViewById(R.id.btn_start_cast);
        btnStopCast = findViewById(R.id.btn_stop_cast);
        progressBar = findViewById(R.id.progress_bar);
        tvStatus = findViewById(R.id.tv_status);
        tvDetails = findViewById(R.id.tv_details);
        transcodeManager = new TranscodeManager(this);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_pick_video).setOnClickListener(v -> openVideoPicker());
        btnStartCast.setOnClickListener(v -> startBroadcasting());
        btnStopCast.setOnClickListener(v -> stopBroadcasting());
        tvStatus.setOnClickListener(v -> copyLanUrl());
    }

    private void openVideoPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("video/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent, REQ_VIDEO);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_VIDEO || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri != null) copyVideoToCache(uri);
    }

    private void copyVideoToCache(Uri uri) {
        progressBar.setVisibility(View.VISIBLE);
        tvStatus.setText("Dang chuan bi video...");
        btnStartCast.setEnabled(false);
        fileExecutor.execute(() -> {
            try {
                String displayName = getDisplayName(uri);
                File file = new File(getCacheDir(), "cast_" + sanitize(displayName));
                try (InputStream input = getContentResolver().openInputStream(uri);
                     FileOutputStream output = new FileOutputStream(file)) {
                    if (input == null) throw new java.io.IOException("Khong mo duoc video");
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
                }
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    etVideoPath.setText(file.getAbsolutePath());
                    btnStartCast.setEnabled(true);
                    startBroadcasting();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    btnStartCast.setEnabled(true);
                    tvStatus.setText("Loi doc video: " + e.getMessage());
                });
            }
        });
    }

    private String getDisplayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) return cursor.getString(0);
        } catch (Exception ignored) {}
        return "video.mp4";
    }

    private String sanitize(String name) {
        String safe = name == null ? "video.mp4" : name.replaceAll("[^a-zA-Z0-9._-]", "_");
        return safe.isEmpty() ? "video.mp4" : safe;
    }

    private void startBroadcasting() {
        String path = etVideoPath.getText().toString().trim();
        if (path.isEmpty()) {
            Toast.makeText(this, "Hay chon video", Toast.LENGTH_SHORT).show();
            return;
        }
        btnStartCast.setEnabled(false);
        btnStopCast.setEnabled(true);
        progressBar.setVisibility(View.VISIBLE);
        tvStatus.setText("Dang khoi dong may chu LAN...");

        transcodeManager.startServer(path, new TranscodeManager.Callback() {
            @Override public void onServerStarted(String lanUrl) {
                generatedLanUrl = lanUrl;
                progressBar.setVisibility(View.GONE);
                tvStatus.setText("Dang phat qua LAN. Cham de copy:\n" + lanUrl);
                tvDetails.setText("Mo link nay bang VLC tren may tinh cung Wi-Fi.");
            }
            @Override public void onClientConnected(String clientIp) {
                tvDetails.setText("Thiet bi " + clientIp + " dang xem");
            }
            @Override public void onTranscodeLog(String logLine) { tvDetails.setText(logLine); }
            @Override public void onError(String error) {
                tvStatus.setText("Loi: " + error);
                resetButtons();
            }
            @Override public void onServerStopped() { resetButtons(); }
        });
    }

    private void copyLanUrl() {
        if (generatedLanUrl.isEmpty()) return;
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("VLC LAN URL", generatedLanUrl));
        Toast.makeText(this, "Da copy link LAN", Toast.LENGTH_SHORT).show();
    }

    private void stopBroadcasting() {
        transcodeManager.stopServer();
        resetButtons();
        tvStatus.setText("May chu LAN da dung");
    }

    private void resetButtons() {
        btnStartCast.setEnabled(!etVideoPath.getText().toString().trim().isEmpty());
        btnStopCast.setEnabled(false);
        progressBar.setVisibility(View.GONE);
        generatedLanUrl = "";
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        transcodeManager.destroy();
        fileExecutor.shutdownNow();
    }
}
