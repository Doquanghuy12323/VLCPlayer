package com.vlcplayer.app;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

public class TranscodeActivity extends AppCompatActivity {

    private EditText etVideoPath;
    private ImageButton btnPickVideo;
    private Button btnStartCast, btnStopCast;
    private ProgressBar progressBar;
    private TextView tvStatus, tvDetails;
    private TranscodeManager transcodeManager;
    private String generatedLanUrl = "";

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(AppLanguageManager.applyLanguage(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_torrent); // Tận dụng lại file layout có sẵn các ID tương đồng

        etVideoPath  = findViewById(R.id.et_magnet); // ID ô nhập liệu
        btnPickVideo = findViewById(R.id.btn_pick_file); // ID nút chọn file
        btnStartCast = findViewById(R.id.btn_stream); // ID nút Phát
        btnStopCast  = findViewById(R.id.btn_stop); // ID nút Dừng
        progressBar  = findViewById(R.id.progress_bar);
        tvStatus     = findViewById(R.id.tv_status);
        tvDetails    = findViewById(R.id.tv_speed);

        // Đổi tên nhãn nút bấm cho đúng tính năng thuần Video Casting
        btnStartCast.setText("BẮT ĐẦU PHÁT LAN");
        btnStopCast.setText("DỪNG PHÁT LAN");
        etVideoPath.setHint("Đường dẫn file video (MP4, MKV, AVI)...");

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

        transcodeManager = new TranscodeManager(this);

        btnPickVideo.setOnClickListener(v -> openVideoPicker());
        btnStartCast.setOnClickListener(v -> startBroadcasting());
        btnStopCast.setOnClickListener(v -> stopBroadcasting());

        // Chạm vào dòng link LAN hiển thị để tự động sao chép sang PC
        tvStatus.setOnClickListener(v -> {
            if (!generatedLanUrl.isEmpty()) {
                ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clip = ClipData.newPlainText("Transcode URL", generatedLanUrl);
                clipboard.setPrimaryClip(clip);
                Toast.makeText(this, "Đã copy link LAN gánh tải hiệu năng!", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void openVideoPicker() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("video/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent, 2002);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 2002 && resultCode == RESULT_OK && data != null) {
            Uri videoUri = data.getData();
            if (videoUri != null) {
                copyVideoToCacheAndSetPath(videoUri);
            }
        }
    }

    private void copyVideoToCacheAndSetPath(Uri uri) {
        try {
            InputStream is = getContentResolver().openInputStream(uri);
            File tempFile = new File(getCacheDir(), "cast_input_video.mp4");
            FileOutputStream fos = new FileOutputStream(tempFile);
            byte[] buffer = new byte[1024];
            int length;
            while ((length = is.read(buffer)) > 0) {
                fos.write(buffer, 0, length);
            }
            fos.close();
            is.close();

            etVideoPath.setText(tempFile.getAbsolutePath());
            startBroadcasting();
        } catch (Exception e) {
            Toast.makeText(this, "Lỗi nạp file video: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void startBroadcasting() {
        String path = etVideoPath.getText().toString().trim();
        if (path.isEmpty()) {
            Toast.makeText(this, "Vui lòng chọn một file video trong máy!", Toast.LENGTH_SHORT).show();
            return;
        }

        btnStartCast.setEnabled(false);
        btnStopCast.setEnabled(true);
        progressBar.setVisibility(View.VISIBLE);
        tvStatus.setText("Đang khởi động chip đồ họa nén video...");

        transcodeManager.startServer(path, new TranscodeManager.Callback() {
            @Override
            public void onServerStarted(String lanUrl) {
                generatedLanUrl = lanUrl;
                progressBar.setVisibility(View.GONE);
                tvStatus.setText("📺 ĐÃ PHÁT SÓNG! Ấn vào đây để Copy link dán vào PC:\n" + lanUrl);
                tvDetails.setText("Điện thoại đang gánh 100% hiệu năng nén hình ảnh trực tiếp.");
            }

            @Override
            public void onClientConnected(String clientIp) {
                tvDetails.setText("Thiết bị khách (" + clientIp + ") đang kết nối và xem mượt mà!");
            }

            @Override
            public void onTranscodeLog(String logLine) {
                tvDetails.setText(logLine);
            }

            @Override
            public void onError(String error) {
                tvStatus.setText("Lỗi: " + error);
                progressBar.setVisibility(View.GONE);
                stopBroadcasting();
            }

            @Override
            public void onServerStopped() {
                stopBroadcasting();
            }
        });
    }

    private void stopBroadcasting() {
        transcodeManager.stopServer();
        btnStartCast.setEnabled(true);
        btnStopCast.setEnabled(false);
        progressBar.setVisibility(View.GONE);
        tvStatus.setText("Máy chủ phát sóng đã dừng.");
        tvDetails.setText("");
        generatedLanUrl = "";
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        transcodeManager.stopServer();
    }
}
