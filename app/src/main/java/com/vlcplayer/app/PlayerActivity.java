package com.vlcplayer.app;

import android.app.PictureInPictureParams;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.audiofx.AudioEffect;
import android.media.audiofx.Equalizer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.ParcelFileDescriptor;
import android.util.DisplayMetrics;
import android.util.Rational;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.vlcplayer.app.db.AppDatabase;
import com.vlcplayer.app.db.BookmarkItem;
import com.vlcplayer.app.db.HistoryItem;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.util.VLCVideoLayout;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class PlayerActivity extends AppCompatActivity {

    public static final String EXTRA_URI   = "extra_uri";
    public static final String EXTRA_TITLE = "extra_title";

    private LibVLC libVLC;
    private MediaPlayer mediaPlayer;
    private VLCVideoLayout videoLayout;
    private ParcelFileDescriptor currentPfd;
    private Equalizer equalizer;

    private SeekBar seekBar;
    private TextView tvCurrent, tvTotal, tvTitle, tvSpeed;
    private ImageButton btnPlayPause;
    private View controlsOverlay;
    private View lockOverlay;
    private boolean isLocked = false;

    private final Handler handler = new Handler();
    private boolean controlsVisible = true;
    private boolean userSeeking = false;
    private boolean waveletBroadcastSent = false;
    private int audioSessionId = AudioEffect.ERROR_BAD_VALUE;
    private int scaleMode = 0;
    private int screenW, screenH;
    private float playbackSpeed = 1.0f;

    private String uriString, videoTitle;
    private final ExecutorService dbExecutor = Executors.newSingleThreadExecutor();

    private GestureDetector gestureDetector;

    private final Runnable hideControls = () -> {
        if (!isLocked && mediaPlayer != null && mediaPlayer.isPlaying()) {
            controlsOverlay.animate().alpha(0f).setDuration(300)
                    .withEndAction(() -> controlsOverlay.setVisibility(View.GONE));
            controlsVisible = false;
        }
    };

    private final Runnable updateSeekBar = new Runnable() {
        @Override public void run() {
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
        getWindow().addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        );
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getWindow().getAttributes().layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        hideSystemUI();
        setContentView(R.layout.activity_player);

        DisplayMetrics dm = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(dm);
        screenW = dm.widthPixels;
        screenH = dm.heightPixels;

        uriString  = getIntent().getStringExtra(EXTRA_URI);
        videoTitle = getIntent().getStringExtra(EXTRA_TITLE);

        videoLayout     = findViewById(R.id.vlc_video_layout);
        seekBar         = findViewById(R.id.seekBar);
        tvCurrent       = findViewById(R.id.tv_current);
        tvTotal         = findViewById(R.id.tv_total);
        tvTitle         = findViewById(R.id.tv_title);
        tvSpeed         = findViewById(R.id.tv_speed);
        btnPlayPause    = findViewById(R.id.btn_play_pause);
        controlsOverlay = findViewById(R.id.controls_overlay);
        lockOverlay     = findViewById(R.id.lock_overlay);

        tvTitle.setText(videoTitle != null ? videoTitle : "Video");
        tvSpeed.setText("1.0x");

        setupButtons();
        setupGestures();
        setupVLC();

        if (uriString != null) playMedia(uriString);
        else finish();

        handler.post(updateSeekBar);
        scheduleHideControls();
    }

    private void setupButtons() {
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        btnPlayPause.setOnClickListener(v -> togglePlayPause());
        videoLayout.setOnClickListener(v -> { if (!isLocked) toggleControls(); });

        // Tua nhanh/lùi
        findViewById(R.id.btn_forward).setOnClickListener(v -> {
            if (mediaPlayer != null) mediaPlayer.setTime(mediaPlayer.getTime() + 10000);
        });
        findViewById(R.id.btn_rewind).setOnClickListener(v -> {
            if (mediaPlayer != null) mediaPlayer.setTime(Math.max(0, mediaPlayer.getTime() - 10000));
        });

        // Aspect ratio
        findViewById(R.id.btn_aspect).setOnClickListener(v -> cycleAspectRatio());

        // Tốc độ phát
        findViewById(R.id.btn_speed).setOnClickListener(v -> showSpeedDialog());

        // Bookmark
        findViewById(R.id.btn_bookmark).setOnClickListener(v -> addBookmark());

        // Equalizer
        findViewById(R.id.btn_eq).setOnClickListener(v -> showEqualizerDialog());

        // PiP
        findViewById(R.id.btn_pip).setOnClickListener(v -> enterPiP());

        // Lock màn hình
        findViewById(R.id.btn_lock).setOnClickListener(v -> toggleLock());
        if (findViewById(R.id.btn_translate) != null)
            findViewById(R.id.btn_translate).setOnClickListener(v -> showTranslateSubtitleDialog());

        // Unlock button (chỉ hiện khi locked)
        findViewById(R.id.btn_unlock).setOnClickListener(v -> toggleLock());

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (fromUser) tvCurrent.setText(formatTime(p));
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { userSeeking = true; }
            @Override public void onStopTrackingTouch(SeekBar sb) {
                userSeeking = false;
                if (mediaPlayer != null) mediaPlayer.setTime(sb.getProgress());
            }
        });
    }

    private void setupGestures() {
        gestureDetector = new GestureDetector(this,
            new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onScroll(MotionEvent e1, MotionEvent e2,
                        float distX, float distY) {
                    if (isLocked || e1 == null) return false;
                    float x = e1.getX();
                    float dy = distY; // lên = tăng, xuống = giảm
                    if (x < screenW / 2f) {
                        // Trái → điều chỉnh độ sáng
                        adjustBrightness(dy * 0.005f);
                    } else {
                        // Phải → điều chỉnh âm lượng
                        adjustVolume(dy * 0.005f);
                    }
                    return true;
                }

                @Override
                public boolean onDoubleTap(MotionEvent e) {
                    if (isLocked) return false;
                    float x = e.getX();
                    if (x < screenW / 2f) {
                        if (mediaPlayer != null)
                            mediaPlayer.setTime(Math.max(0, mediaPlayer.getTime() - 10000));
                        showGestureHint("⏮ -10s");
                    } else {
                        if (mediaPlayer != null)
                            mediaPlayer.setTime(mediaPlayer.getTime() + 10000);
                        showGestureHint("⏭ +10s");
                    }
                    return true;
                }
            });

        videoLayout.setOnTouchListener((v, event) -> {
            gestureDetector.onTouchEvent(event);
            if (event.getAction() == MotionEvent.ACTION_UP && !isLocked) {
                toggleControls();
            }
            return true;
        });
    }

    private void adjustBrightness(float delta) {
        WindowManager.LayoutParams params = getWindow().getAttributes();
        params.screenBrightness = Math.max(0.01f,
            Math.min(1.0f, params.screenBrightness + delta));
        getWindow().setAttributes(params);
        showGestureHint("☀ " + (int)(params.screenBrightness * 100) + "%");
    }

    private void adjustVolume(float delta) {
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int cur = am.getStreamVolume(AudioManager.STREAM_MUSIC);
        int newVol = Math.max(0, Math.min(max, (int)(cur + delta * max)));
        am.setStreamVolume(AudioManager.STREAM_MUSIC, newVol,
            AudioManager.FLAG_SHOW_UI);
    }

    private void showGestureHint(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private void setupVLC() {
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        audioSessionId = am.generateAudioSessionId();

        ArrayList<String> options = new ArrayList<>();
        options.add("--clock-jitter=0");
        options.add("--clock-synchro=0");
        options.add("--avcodec-threads=0");
        options.add("--network-caching=1500");
        options.add("--aout=android_audiotrack");
        options.add("--audiotrack-session-id=" + audioSessionId);

        libVLC = new LibVLC(this, options);
        mediaPlayer = new MediaPlayer(libVLC);

        // Setup Equalizer
        try {
            equalizer = new Equalizer(0, audioSessionId);
            equalizer.setEnabled(true);
        } catch (Exception ignored) {}

        mediaPlayer.setEventListener(event -> {
            switch (event.type) {
                case MediaPlayer.Event.Playing:
                    runOnUiThread(() -> {
                        btnPlayPause.setImageResource(android.R.drawable.ic_media_pause);
                        applyScaleMode();
                        scheduleHideControls();
                        if (!waveletBroadcastSent) {
                            broadcastAudioSessionOpen();
                            waveletBroadcastSent = true;
                        }
                    });
                    break;
                case MediaPlayer.Event.Paused:
                    runOnUiThread(() ->
                        btnPlayPause.setImageResource(android.R.drawable.ic_media_play));
                    break;
                case MediaPlayer.Event.EndReached:
                    saveHistory();
                    runOnUiThread(this::finish);
                    break;
                case MediaPlayer.Event.EncounteredError:
                    runOnUiThread(() ->
                        Toast.makeText(this, "Lỗi phát video", Toast.LENGTH_SHORT).show());
                    break;
            }
        });

        mediaPlayer.attachViews(videoLayout, null, false, false);
    }

    private void playMedia(String uriString) {
        // Kiểm tra history để resume
        dbExecutor.execute(() -> {
            HistoryItem history = AppDatabase.get(this).dao()
                .getHistoryByUri(uriString);
            final long resumePos = (history != null && history.lastPosition > 5000)
                ? history.lastPosition : 0;

            runOnUiThread(() -> {
                try {
                    Uri uri = Uri.parse(uriString);
                    Media media;
                    if ("content".equals(uri.getScheme())) {
                        closePfd();
                        currentPfd = getContentResolver().openFileDescriptor(uri, "r");
                        if (currentPfd == null) return;
                        media = new Media(libVLC, currentPfd.getFileDescriptor());
                    } else {
                        media = new Media(libVLC, uri);
                    }
                    media.setHWDecoderEnabled(true, false);
                    media.addOption(":file-caching=1500");
                    media.addOption(":codec=mediacodec_ndk,mediacodec,omxil,any");
                    mediaPlayer.setMedia(media);
                    media.release();
                    mediaPlayer.play();

                    // Resume từ vị trí cũ
                    if (resumePos > 0) {
                        handler.postDelayed(() -> {
                            mediaPlayer.setTime(resumePos);
                            Toast.makeText(this,
                                "Tiếp tục từ " + formatTime(resumePos),
                                Toast.LENGTH_SHORT).show();
                        }, 1000);
                    }
                } catch (Exception e) {
                    Toast.makeText(this, "Lỗi: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    private void saveHistory() {
        if (uriString == null) return;
        long pos = mediaPlayer != null ? mediaPlayer.getTime() : 0;
        long dur = mediaPlayer != null ? mediaPlayer.getLength() : 0;
        dbExecutor.execute(() -> {
            AppDatabase.get(this).dao().deleteHistory(uriString);
            AppDatabase.get(this).dao().insertHistory(
                new HistoryItem(uriString,
                    videoTitle != null ? videoTitle : "Video", pos, dur));
        });
    }

    private void addBookmark() {
        if (mediaPlayer == null || uriString == null) return;
        long pos = mediaPlayer.getTime();
        String defaultLabel = "Bookmark " + formatTime(pos);

        android.widget.EditText input = new android.widget.EditText(this);
        input.setText(defaultLabel);

        new AlertDialog.Builder(this)
            .setTitle("Đánh dấu thời điểm")
            .setMessage(formatTime(pos))
            .setView(input)
            .setPositiveButton("Lưu", (d, w) -> {
                String label = input.getText().toString().trim();
                if (label.isEmpty()) label = defaultLabel;
                final String finalLabel = label;
                dbExecutor.execute(() ->
                    AppDatabase.get(this).dao().insertBookmark(
                        new BookmarkItem(uriString,
                            videoTitle != null ? videoTitle : "Video",
                            pos, finalLabel)));
                Toast.makeText(this, "Đã đánh dấu: " + label, Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("Hủy", null)
            .show();
    }

    private void showSpeedDialog() {
        String[] speeds = {"0.25x", "0.5x", "0.75x", "1.0x", "1.25x", "1.5x", "1.75x", "2.0x"};
        float[] speedValues = {0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f};
        int currentIdx = 3; // default 1.0x
        for (int i = 0; i < speedValues.length; i++) {
            if (Math.abs(speedValues[i] - playbackSpeed) < 0.01f) { currentIdx = i; break; }
        }

        new AlertDialog.Builder(this)
            .setTitle("Tốc độ phát")
            .setSingleChoiceItems(speeds, currentIdx, (d, which) -> {
                playbackSpeed = speedValues[which];
                if (mediaPlayer != null) mediaPlayer.setRate(playbackSpeed);
                tvSpeed.setText(speeds[which]);
                d.dismiss();
            })
            .show();
    }

    private void showEqualizerDialog() {
        if (equalizer == null) {
            Toast.makeText(this, "Equalizer không khả dụng", Toast.LENGTH_SHORT).show();
            return;
        }

        short bands = equalizer.getNumberOfBands();
        String[] bandLabels = new String[bands];
        final int[] currentLevels = new int[bands];

        for (short i = 0; i < bands; i++) {
            int freq = (int) equalizer.getCenterFreq(i) / 1000;
            bandLabels[i] = freq >= 1000 ? (freq/1000) + "kHz" : freq + "Hz";
            currentLevels[i] = (equalizer.getBandLevel(i) - equalizer.getBandLevelRange()[0]) / 100;
        }

        // Preset names
        short presets = equalizer.getNumberOfPresets();
        String[] presetNames = new String[presets + 1];
        presetNames[0] = "Tùy chỉnh";
        for (short i = 0; i < presets; i++) {
            presetNames[i + 1] = equalizer.getPresetName(i);
        }

        new AlertDialog.Builder(this)
            .setTitle("Bộ cân bằng âm thanh")
            .setItems(presetNames, (d, which) -> {
                if (which > 0) {
                    equalizer.usePreset((short)(which - 1));
                    Toast.makeText(this, "Preset: " + presetNames[which],
                        Toast.LENGTH_SHORT).show();
                }
            })
            .setPositiveButton("Đóng", null)
            .show();
    }

    private void enterPiP() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                PictureInPictureParams.Builder builder = new PictureInPictureParams.Builder();
                builder.setAspectRatio(new Rational(16, 9));
                enterPictureInPictureMode(builder.build());
            } catch (Exception e) {
                Toast.makeText(this, "PiP không khả dụng", Toast.LENGTH_SHORT).show();
            }
        } else {
            Toast.makeText(this, "Cần Android 8.0+", Toast.LENGTH_SHORT).show();
        }
    }

    private void toggleLock() {
        isLocked = !isLocked;
        if (isLocked) {
            controlsOverlay.setVisibility(View.GONE);
            lockOverlay.setVisibility(View.VISIBLE);
            handler.removeCallbacks(hideControls);
            Toast.makeText(this, "🔒 Màn hình bị khóa", Toast.LENGTH_SHORT).show();
        } else {
            lockOverlay.setVisibility(View.GONE);
            controlsOverlay.setVisibility(View.VISIBLE);
            controlsOverlay.setAlpha(1f);
            controlsVisible = true;
            scheduleHideControls();
            Toast.makeText(this, "🔓 Đã mở khóa", Toast.LENGTH_SHORT).show();
        }
    }

    private void applyScaleMode() {
        if (mediaPlayer == null) return;
        switch (scaleMode) {
            case 0: mediaPlayer.setAspectRatio(null); mediaPlayer.setScale(0); break;
            case 1: mediaPlayer.setAspectRatio(screenW + ":" + screenH); mediaPlayer.setScale(0); break;
            case 2: mediaPlayer.setAspectRatio(screenW + ":" + screenH); mediaPlayer.setScale(1); break;
        }
    }

    private void cycleAspectRatio() {
        scaleMode = (scaleMode + 1) % 3;
        applyScaleMode();
        String[] labels = {"Vừa màn hình", "Lấp đầy", "Kéo dãn"};
        Toast.makeText(this, labels[scaleMode], Toast.LENGTH_SHORT).show();
    }

    private void togglePlayPause() {
        if (mediaPlayer == null) return;
        if (mediaPlayer.isPlaying()) mediaPlayer.pause();
        else mediaPlayer.play();
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
        handler.postDelayed(hideControls, 3500);
    }

    private void broadcastAudioSessionOpen() {
        if (audioSessionId == AudioEffect.ERROR_BAD_VALUE) return;
        Intent i = new Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION);
        i.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, audioSessionId);
        i.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, getPackageName());
        i.putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MOVIE);
        sendBroadcast(i);
    }

    private void broadcastAudioSessionClose() {
        if (audioSessionId == AudioEffect.ERROR_BAD_VALUE) return;
        Intent i = new Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION);
        i.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, audioSessionId);
        i.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, getPackageName());
        sendBroadcast(i);
    }

    private void closePfd() {
        if (currentPfd != null) {
            try { currentPfd.close(); } catch (IOException ignored) {}
            currentPfd = null;
        }
    }

    private String formatTime(long ms) {
        long h = TimeUnit.MILLISECONDS.toHours(ms);
        long m = TimeUnit.MILLISECONDS.toMinutes(ms) % 60;
        long s = TimeUnit.MILLISECONDS.toSeconds(ms) % 60;
        if (h > 0) return String.format(Locale.US, "%d:%02d:%02d", h, m, s);
        return String.format(Locale.US, "%02d:%02d", m, s);
    }

    private void hideSystemUI() {
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUI();
    }

    @Override public void onUserLeaveHint() {
        super.onUserLeaveHint();
        enterPiP(); // Auto PiP khi nhấn Home
    }

    @Override protected void onStop() {
        super.onStop();
        saveHistory();
        mediaPlayer.stop();
        mediaPlayer.detachViews();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        broadcastAudioSessionClose();
        if (equalizer != null) { equalizer.release(); }
        handler.removeCallbacksAndMessages(null);
        mediaPlayer.release();
        libVLC.release();
        closePfd();
    }

    private void showTranslateSubtitleDialog() {
        TranslationManager tm = new TranslationManager(this);
        String[] opts = {"Dich tu URL subtitle", "Doi ngon ngu: " + tm.getTargetLanguageName()};
        new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Dich Subtitle AI")
            .setItems(opts, (d, which) -> {
                if (which == 0) showSrtUrlInput();
                else showChangeLangDialog();
            }).show();
    }

    private void showChangeLangDialog() {
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
        new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Chon ngon ngu dich")
            .setSingleChoiceItems(names, curIdx, (d, w) -> sel[0] = w)
            .setPositiveButton("Luu", (d, w) -> {
                tm.setTargetLanguage(langs[sel[0]][1]);
                Toast.makeText(this, "Da chon: " + langs[sel[0]][0], Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("Huy", null).show();
    }

    private void showSrtUrlInput() {
        android.widget.EditText input = new android.widget.EditText(this);
        input.setHint("https://example.com/subtitle.srt");
        new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("URL file SRT")
            .setView(input)
            .setPositiveButton("Dich", (d, w) -> {
                String url = input.getText().toString().trim();
            })
            .setNegativeButton("Huy", null).show();
    }

    private void startSrtTranslation(String url) {
        android.app.ProgressDialog pd = new android.app.ProgressDialog(this);
        pd.setMessage("Dang tai...");
        pd.setCancelable(false);
        pd.show();
        new Thread(() -> {
            try {
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
                    new java.net.URL(url).openConnection();
                conn.setConnectTimeout(10000);
                java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String ln;
                while ((ln = br.readLine()) != null) sb.append(ln).append("
");
                br.close();
                String srt = sb.toString();
                TranslationManager tm = new TranslationManager(this);
                runOnUiThread(() -> pd.setMessage("Dang dich..."));
                tm.translateSrt(srt, "auto",
                    prog -> runOnUiThread(() -> pd.setMessage(prog)),
                    new TranslationManager.TranslateCallback() {
                        public void onSuccess(String t) {
                            runOnUiThread(() -> { pd.dismiss(); saveSrtAndLoad(t); });
                        }
                        public void onError(String e) {
                            runOnUiThread(() -> { pd.dismiss();
                                Toast.makeText(PlayerActivity.this, e, Toast.LENGTH_SHORT).show(); });
                        }
                    });
            } catch (Exception e) {
                runOnUiThread(() -> { pd.dismiss();
                    Toast.makeText(PlayerActivity.this, "Loi: " + e.getMessage(), Toast.LENGTH_SHORT).show(); });
            }
        }).start();
    }

    private void saveSrtAndLoad(String srtContent) {
        try {
            java.io.File f = new java.io.File(getExternalFilesDir(null), "translated.srt");
            java.io.FileWriter fw = new java.io.FileWriter(f);
            fw.write(srtContent);
            fw.close();
            if (mediaPlayer != null)
                mediaPlayer.addSlave(Media.Slave.Type.Subtitle,
                    android.net.Uri.fromFile(f).toString(), true);
        } catch (Exception e) {
            Toast.makeText(this, "Loi luu: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

}
