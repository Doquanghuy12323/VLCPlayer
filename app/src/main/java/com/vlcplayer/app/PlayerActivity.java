package com.vlcplayer.app;

import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.util.VLCVideoLayout;

import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public class PlayerActivity extends AppCompatActivity {

    public static final String EXTRA_URI   = "extra_uri";
    public static final String EXTRA_TITLE = "extra_title";

    private LibVLC libVLC;
    private MediaPlayer mediaPlayer;
    private VLCVideoLayout videoLayout;

    private SeekBar seekBar;
    private TextView tvCurrent, tvTotal, tvTitle;
    private ImageButton btnPlayPause, btnBack;
    private View controlsOverlay;

    private final Handler handler = new Handler();
    private boolean controlsVisible = true;
    private boolean userSeeking = false;

    private final Runnable hideControls = () -> {
        if (mediaPlayer != null && mediaPlayer.isPlaying()) {
            controlsOverlay.animate().alpha(0f).setDuration(300)
                    .withEndAction(() -> controlsOverlay.setVisibility(View.GONE));
            controlsVisible = false;
        }
    };

    private final Runnable updateSeekBar = new Runnable() {
        @Override
        public void run() {
            if (mediaPlayer != null && !userSeeking) {
                long pos = mediaPlayer.getTime();
                long len = mediaPlayer.getLength();
                if (len > 0) {
                    seekBar.setMax((int) len);
                    seekBar.setProgress((int) pos);
                    tvCurrent.setText(formatTime(pos));
                    tvTotal.setText(formatTime(len));
                }
            }
            handler.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideSystemUI();
        setContentView(R.layout.activity_player);

        String uriString = getIntent().getStringExtra(EXTRA_URI);
        String title     = getIntent().getStringExtra(EXTRA_TITLE);

        videoLayout    = findViewById(R.id.vlc_video_layout);
        seekBar        = findViewById(R.id.seekBar);
        tvCurrent      = findViewById(R.id.tv_current);
        tvTotal        = findViewById(R.id.tv_total);
        tvTitle        = findViewById(R.id.tv_title);
        btnPlayPause   = findViewById(R.id.btn_play_pause);
        btnBack        = findViewById(R.id.btn_back);
        controlsOverlay = findViewById(R.id.controls_overlay);

        tvTitle.setText(title != null ? title : "Video");

        btnBack.setOnClickListener(v -> finish());
        btnPlayPause.setOnClickListener(v -> togglePlayPause());

        videoLayout.setOnClickListener(v -> toggleControls());

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (fromUser) tvCurrent.setText(formatTime(progress));
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { userSeeking = true; }
            @Override public void onStopTrackingTouch(SeekBar sb) {
                userSeeking = false;
                if (mediaPlayer != null) mediaPlayer.setTime(sb.getProgress());
            }
        });

        // Fast-forward / rewind buttons
        findViewById(R.id.btn_forward).setOnClickListener(v -> {
            if (mediaPlayer != null) mediaPlayer.setTime(mediaPlayer.getTime() + 10000);
        });
        findViewById(R.id.btn_rewind).setOnClickListener(v -> {
            if (mediaPlayer != null) mediaPlayer.setTime(Math.max(0, mediaPlayer.getTime() - 10000));
        });

        // Init VLC
        ArrayList<String> options = new ArrayList<>();
        options.add("--no-drop-late-frames");
        options.add("--no-skip-frames");
        options.add("--rtsp-tcp");
        options.add("-vvv");

        libVLC = new LibVLC(this, options);
        mediaPlayer = new MediaPlayer(libVLC);

        mediaPlayer.setEventListener(event -> {
            switch (event.type) {
                case MediaPlayer.Event.Playing:
                    runOnUiThread(() -> btnPlayPause.setImageResource(android.R.drawable.ic_media_pause));
                    break;
                case MediaPlayer.Event.Paused:
                case MediaPlayer.Event.Stopped:
                    runOnUiThread(() -> btnPlayPause.setImageResource(android.R.drawable.ic_media_play));
                    break;
                case MediaPlayer.Event.EndReached:
                    runOnUiThread(() -> finish());
                    break;
            }
        });

        playMedia(uriString);
        handler.post(updateSeekBar);
        scheduleHideControls();
    }

    private void playMedia(String uriString) {
        Uri uri = Uri.parse(uriString);
        Media media = new Media(libVLC, uri);
        media.setHWDecoderEnabled(true, false);
        mediaPlayer.setMedia(media);
        media.release();
        mediaPlayer.attachViews(videoLayout, null, false, false);
        mediaPlayer.play();
    }

    private void togglePlayPause() {
        if (mediaPlayer == null) return;
        if (mediaPlayer.isPlaying()) {
            mediaPlayer.pause();
        } else {
            mediaPlayer.play();
            scheduleHideControls();
        }
    }

    private void toggleControls() {
        if (controlsVisible) {
            handler.removeCallbacks(hideControls);
            controlsOverlay.animate().alpha(0f).setDuration(300)
                    .withEndAction(() -> controlsOverlay.setVisibility(View.GONE));
            controlsVisible = false;
        } else {
            controlsOverlay.setVisibility(View.VISIBLE);
            controlsOverlay.animate().alpha(1f).setDuration(300);
            controlsVisible = true;
            scheduleHideControls();
        }
    }

    private void scheduleHideControls() {
        handler.removeCallbacks(hideControls);
        handler.postDelayed(hideControls, 3000);
    }

    private String formatTime(long ms) {
        long hours   = TimeUnit.MILLISECONDS.toHours(ms);
        long minutes = TimeUnit.MILLISECONDS.toMinutes(ms) % 60;
        long seconds = TimeUnit.MILLISECONDS.toSeconds(ms) % 60;
        if (hours > 0) {
            return String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(Locale.US, "%02d:%02d", minutes, seconds);
    }

    private void hideSystemUI() {
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            | View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        );
    }

    @Override
    protected void onStop() {
        super.onStop();
        mediaPlayer.stop();
        mediaPlayer.detachViews();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        mediaPlayer.release();
        libVLC.release();
    }
}
