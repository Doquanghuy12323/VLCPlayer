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

public class TranscodeActivity extends AppCompatActivity {

    private static final int REQ_VIDEO = 2002;
    private EditText etVideoPath;
    private Button btnStartCast, btnStopCast;
    private ProgressBar progressBar;
    private TextView tvStatus, tvDetails;
    private TranscodeManager transcodeManager;
    private String generatedLanUrl = "";
    private Uri selectedVideoUri;
    private String selectedVideoName = "";
    private long selectedVideoSize = -1;

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
        TranscodeManager.cleanupLegacyCache(this);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_pick_video).setOnClickListener(v -> openVideoPicker());
        btnStartCast.setOnClickListener(v -> startBroadcasting());
        btnStopCast.setOnClickListener(v -> stopBroadcasting());
        tvStatus.setOnClickListener(v -> copyLanUrl());

        if (savedInstanceState != null) {
            String uri = savedInstanceState.getString("video_uri", "");
            if (!uri.isEmpty()) {
                selectedVideoUri = Uri.parse(uri);
                selectedVideoName = savedInstanceState.getString("video_name", "video");
                selectedVideoSize = savedInstanceState.getLong("video_size", -1);
                etVideoPath.setText(selectedVideoName);
                startBroadcasting();
            }
        }
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
        if (uri != null) prepareVideo(uri, data.getFlags());
    }

    private void prepareVideo(Uri uri, int intentFlags) {
        try {
            int takeFlags = intentFlags
                & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            getContentResolver().takePersistableUriPermission(uri, takeFlags);
        } catch (Exception ignored) {}

        selectedVideoUri = uri;
        selectedVideoName = getDisplayName(uri);
        selectedVideoSize = getDisplaySize(uri);
        etVideoPath.setText(selectedVideoName);
        btnStartCast.setEnabled(true);
        startBroadcasting();
    }

    private String getDisplayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) return cursor.getString(0);
        } catch (Exception ignored) {}
        return "video.mp4";
    }

    private long getDisplaySize(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri,
                new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) {
                return cursor.getLong(0);
            }
        } catch (Exception ignored) {}
        return -1;
    }

    private void startBroadcasting() {
        if (selectedVideoUri == null) {
            Toast.makeText(this, "Hay chon video", Toast.LENGTH_SHORT).show();
            return;
        }
        btnStartCast.setEnabled(false);
        btnStopCast.setEnabled(true);
        progressBar.setVisibility(View.VISIBLE);
        tvStatus.setText("Dang khoi dong may chu LAN...");

        transcodeManager.startServer(selectedVideoUri, selectedVideoName,
            selectedVideoSize, new TranscodeManager.Callback() {
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
        btnStartCast.setEnabled(selectedVideoUri != null);
        btnStopCast.setEnabled(false);
        progressBar.setVisibility(View.GONE);
        generatedLanUrl = "";
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (selectedVideoUri != null) {
            outState.putString("video_uri", selectedVideoUri.toString());
            outState.putString("video_name", selectedVideoName);
            outState.putLong("video_size", selectedVideoSize);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        transcodeManager.destroy();
    }
}
