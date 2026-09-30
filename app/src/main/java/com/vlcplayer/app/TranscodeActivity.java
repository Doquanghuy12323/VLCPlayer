package com.vlcplayer.app;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.format.Formatter;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;

public class TranscodeActivity extends AppCompatActivity {
    private static final int REQ_VIDEO = 2002;
    private static final String STATE_URI = "video_uri";
    private static final String STATE_NAME = "video_name";
    private static final String STATE_SIZE = "video_size";
    private static final String STATE_STOPPED = "lan_was_stopped";

    private TextView selectedVideo, tvStatus, tvDetails, tvLanUrl;
    private Button btnPickVideo, btnStartCast, btnStopCast, btnCopyUrl;
    private ProgressBar progressBar;
    private LanSharingViewModel viewModel;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(AppLanguageManager.applyLanguage(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_transcode);
        selectedVideo = findViewById(R.id.et_video_path);
        btnPickVideo = findViewById(R.id.btn_pick_video);
        btnStartCast = findViewById(R.id.btn_start_cast);
        btnStopCast = findViewById(R.id.btn_stop_cast);
        btnCopyUrl = findViewById(R.id.btn_copy_lan_url);
        progressBar = findViewById(R.id.progress_bar);
        tvStatus = findViewById(R.id.tv_status);
        tvDetails = findViewById(R.id.tv_details);
        tvLanUrl = findViewById(R.id.tv_lan_url);
        viewModel = new ViewModelProvider(this).get(LanSharingViewModel.class);

        if (savedInstanceState != null) {
            // A retained ViewModel ignores this fallback. A new process restores only the selection.
            viewModel.restoreSelection(savedInstanceState.getString(STATE_URI),
                    savedInstanceState.getString(STATE_NAME, ""),
                    savedInstanceState.getLong(STATE_SIZE, -1),
                    savedInstanceState.getBoolean(STATE_STOPPED));
        }
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        btnPickVideo.setOnClickListener(v -> openVideoPicker());
        btnStartCast.setOnClickListener(v -> viewModel.start());
        btnStopCast.setOnClickListener(v -> viewModel.stop());
        btnCopyUrl.setOnClickListener(v -> copyLanUrl());
        viewModel.getSnapshots().observe(this, this::render);
    }

    private void openVideoPicker() {
        if (!viewModel.getSnapshot().canPickVideo()) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("video/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQ_VIDEO);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_VIDEO || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri != null) viewModel.selectVideo(uri, data.getFlags());
    }

    private void render(LanSharingState.Snapshot snapshot) {
        btnPickVideo.setEnabled(snapshot.canPickVideo());
        btnStartCast.setEnabled(snapshot.canStart());
        btnStartCast.setText(snapshot.phase == LanSharingState.Phase.ERROR ? R.string.lan_retry : R.string.lan_start);
        btnStopCast.setEnabled(snapshot.canStop());
        progressBar.setIndeterminate(true);
        progressBar.setVisibility(snapshot.isBusy() ? View.VISIBLE : View.GONE);

        if (snapshot.videoUri == null) {
            selectedVideo.setText(R.string.lan_select_video);
        } else {
            String name = snapshot.videoName.isEmpty() ? getString(R.string.lan_selected_video) : snapshot.videoName;
            String size = snapshot.videoSize >= 0
                    ? Formatter.formatShortFileSize(this, snapshot.videoSize) : getString(R.string.lan_size_unknown);
            selectedVideo.setText(getString(R.string.lan_video_metadata, name, size));
        }

        String details = "";
        switch (snapshot.phase) {
            case PREPARING:
                tvStatus.setText(R.string.lan_status_preparing);
                break;
            case READY:
                tvStatus.setText(R.string.lan_status_ready);
                details = getString(R.string.lan_details_ready);
                break;
            case STARTING:
                tvStatus.setText(R.string.lan_status_starting);
                break;
            case RUNNING:
                tvStatus.setText(R.string.lan_status_running);
                if (!snapshot.clientIp.isEmpty()) details = getString(R.string.lan_client_connected, snapshot.clientIp);
                else if (snapshot.clientDisconnected) details = getString(R.string.lan_client_disconnected);
                else details = getString(R.string.lan_details_running);
                break;
            case STOPPED:
                tvStatus.setText(R.string.lan_status_stopped);
                details = getString(R.string.lan_details_stopped);
                break;
            case ERROR:
                tvStatus.setText(R.string.lan_status_error);
                details = getString(errorString(snapshot.error));
                break;
            default:
                tvStatus.setText(R.string.lan_status_idle);
                break;
        }
        tvDetails.setText(details);
        tvDetails.setVisibility(details.isEmpty() ? View.GONE : View.VISIBLE);
        boolean hasCurrentLink = snapshot.phase == LanSharingState.Phase.RUNNING && !snapshot.lanUrl.isEmpty();
        tvLanUrl.setText(hasCurrentLink ? snapshot.lanUrl : "");
        tvLanUrl.setVisibility(hasCurrentLink ? View.VISIBLE : View.GONE);
        btnCopyUrl.setEnabled(hasCurrentLink);
    }

    private static int errorString(LanSharingState.Error error) {
        switch (error) {
            case SOURCE_UNAVAILABLE: return R.string.lan_error_source;
            case SIZE_UNKNOWN: return R.string.lan_error_size;
            case NETWORK_UNAVAILABLE: return R.string.lan_error_network;
            case ADDRESS_INVALID: return R.string.lan_error_address;
            default: return R.string.lan_error_server;
        }
    }

    private void copyLanUrl() {
        LanSharingState.Snapshot snapshot = viewModel.getSnapshot();
        if (snapshot.phase != LanSharingState.Phase.RUNNING || snapshot.lanUrl.isEmpty()) return;
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        try {
            if (clipboard == null) throw new IllegalStateException("Clipboard unavailable");
            clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.lan_url_label), snapshot.lanUrl));
            Toast.makeText(this, R.string.lan_copy_success, Toast.LENGTH_SHORT).show();
        } catch (RuntimeException unavailable) {
            Toast.makeText(this, R.string.lan_copy_unavailable, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        LanSharingState.Snapshot snapshot = viewModel.getSnapshot();
        if (snapshot.videoUri != null) {
            outState.putString(STATE_URI, snapshot.videoUri);
            outState.putString(STATE_NAME, snapshot.videoName);
            outState.putLong(STATE_SIZE, snapshot.videoSize);
            outState.putBoolean(STATE_STOPPED, snapshot.phase == LanSharingState.Phase.STOPPED
                    || snapshot.phase == LanSharingState.Phase.STARTING
                    || snapshot.phase == LanSharingState.Phase.RUNNING
                    || snapshot.phase == LanSharingState.Phase.PREPARING);
        }
        super.onSaveInstanceState(outState);
    }
}
