package com.vlcplayer.app;

import android.app.PictureInPictureParams;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
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
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.vlcplayer.app.db.AppDatabase;
import com.vlcplayer.app.db.BookmarkItem;
import com.vlcplayer.app.db.HistoryItem;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.util.VLCVideoLayout;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class PlayerActivity extends AppCompatActivity {

    public static final String EXTRA_URI   = "extra_uri";
    public static final String EXTRA_TITLE = "extra_title";
    public static final String EXTRA_AUTO_CLEANUP_TORRENT = "auto_cleanup_torrent";

    private LibVLC libVLC;
    private MediaPlayer mediaPlayer;
    private VLCVideoLayout videoLayout;
    private boolean isInBackground = false;
    private long lastPosition = 0;
    private volatile String pendingUri = null;
    private ParcelFileDescriptor currentPfd;
    private Equalizer equalizer;

    private View nightOverlay;
    private boolean nightMode = false;
    private float filterBrightness = 1.0f;
    private float filterContrast   = 1.0f;
    private float filterSaturation = 1.0f;
    private boolean filtersEnabled = false;

    private SeekBar seekBar;
    private TextView tvCurrent, tvTotal, tvTitle, tvSpeed;
    private ImageButton btnPlayPause, btnNext, btnPrev, btnShuffle, btnRepeat;
    private View controlsOverlay, lockOverlay;
    private boolean isLocked = false;

    private final Handler handler = new Handler();
    private boolean controlsVisible = true;
    private boolean userSeeking = false;
    private int audioSessionId = AudioEffect.ERROR_BAD_VALUE;
    private int scaleMode = 1;
    private int screenW, screenH;
    private float playbackSpeed = 1.0f;

    private String uriString, videoTitle;
    private HandyManager handyManager;
    private final ExecutorService dbExecutor = Executors.newSingleThreadExecutor();
    private GestureDetector gestureDetector;
    private String handyCheckedUri;
    private String handyPreparedUri;
    private boolean handyConnectInProgress;
    private int handyPrepareFailures;
    private String pendingHandyScriptUrl;

    private final ActivityResultLauncher<String[]> funscriptPicker =
        registerForActivityResult(new ActivityResultContracts.OpenDocument(),
            this::onFunscriptPicked);

    private final Runnable handyCorrectionSync = () -> {
        if (handyManager == null || mediaPlayer == null
                || !handyManager.isScriptReady() || !mediaPlayer.isPlaying()) return;
        if (Math.abs(playbackSpeed - 1.0f) > 0.01f) return;
        handyManager.play(mediaPlayer.getTime(), null);
    };

    private final Runnable handyHealthCheck = new Runnable() {
        @Override public void run() {
            if (handyManager != null && mediaPlayer != null) {
                handyManager.healthCheck(mediaPlayer.getTime(), mediaPlayer.isPlaying());
                if (handyManager.isConnected()) prepareScriptAfterConnection();
            }
            handler.postDelayed(this, 30_000);
        }
    };

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
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(AppLanguageManager.applyLanguage(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        new PrivacyManager(this).applyWindowSecurity(this);
        getWindow().addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            | WindowManager.LayoutParams.FLAG_FULLSCREEN
            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        setContentView(R.layout.activity_player);
        hideSystemUI();

        DisplayMetrics dm = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(dm);
        screenW = dm.widthPixels;
        screenH = dm.heightPixels;

        uriString  = getIntent().getStringExtra(EXTRA_URI);
        if (uriString == null && getIntent().getData() != null) {
            uriString = getIntent().getData().toString();
        }
        videoTitle = getIntent().getStringExtra(EXTRA_TITLE);

        videoLayout     = findViewById(R.id.vlc_video_layout);
        seekBar         = findViewById(R.id.seekBar);
        tvCurrent       = findViewById(R.id.tv_current);
        tvTotal         = findViewById(R.id.tv_total);
        tvTitle         = findViewById(R.id.tv_title);
        tvSpeed         = findViewById(R.id.tv_speed);
        btnPlayPause    = findViewById(R.id.btn_play_pause);
        btnNext         = findViewById(R.id.btn_next);
        btnPrev         = findViewById(R.id.btn_prev);
        btnShuffle      = findViewById(R.id.btn_shuffle);
        btnRepeat       = findViewById(R.id.btn_repeat);
        controlsOverlay = findViewById(R.id.controls_overlay);
        lockOverlay     = findViewById(R.id.lock_overlay);
        nightOverlay    = findViewById(R.id.night_overlay);

        tvTitle.setText(videoTitle != null ? videoTitle : "Video");
        tvSpeed.setText("1.0x");

        updatePlaylistButtons();
        setupButtons();
        setupGestures();
        setupVLC();
        handyManager = new HandyManager(this);

        if (uriString != null) {
            playMedia(uriString);
            autoDetectAndApplyEQ(videoTitle);
        } else {
            finish();
        }

        handler.post(updateSeekBar);
        autoConnectHandy(false);
        handler.postDelayed(handyHealthCheck, 10_000);
        scheduleHideControls();
    }

    private void updatePlaylistButtons() {
        PlaylistManager pm = PlaylistManager.get();
        if (btnNext != null) btnNext.setAlpha(pm.hasNext() ? 1.0f : 0.4f);
        if (btnPrev != null) btnPrev.setAlpha(pm.hasPrev() ? 1.0f : 0.4f);
        if (btnShuffle != null) btnShuffle.setAlpha(pm.isShuffle() ? 1.0f : 0.5f);
        if (btnRepeat != null) {
            switch (pm.getRepeatMode()) {
                case NONE: btnRepeat.setAlpha(0.4f); break;
                default:   btnRepeat.setAlpha(1.0f); break;
            }
        }
    }

    private void setupButtons() {
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        btnPlayPause.setOnClickListener(v -> togglePlayPause());
        videoLayout.setOnClickListener(v -> { if (!isLocked) toggleControls(); });
        findViewById(R.id.btn_forward).setOnClickListener(v -> {
            if (mediaPlayer != null) seekPlaybackTo(mediaPlayer.getTime() + 10000);
        });
        findViewById(R.id.btn_rewind).setOnClickListener(v -> {
            if (mediaPlayer != null) seekPlaybackTo(mediaPlayer.getTime() - 10000);
        });
        btnNext.setOnClickListener(v -> playNext());
        btnPrev.setOnClickListener(v -> playPrev());
        btnShuffle.setOnClickListener(v -> {
            PlaylistManager.get().toggleShuffle();
            updatePlaylistButtons();
            Toast.makeText(this, PlaylistManager.get().isShuffle() ? "Shuffle: ON" : "Shuffle: OFF", Toast.LENGTH_SHORT).show();
        });
        btnRepeat.setOnClickListener(v -> {
            PlaylistManager.RepeatMode mode = PlaylistManager.get().cycleRepeat();
            updatePlaylistButtons();
            String[] labels = {"Repeat: OFF", "Repeat: ALL", "Repeat: ONE"};
            Toast.makeText(this, labels[mode.ordinal()], Toast.LENGTH_SHORT).show();
        });
        findViewById(R.id.btn_queue).setOnClickListener(v -> showQueueDialog());
        findViewById(R.id.btn_night).setOnClickListener(v -> toggleNightMode());
        findViewById(R.id.btn_filter).setOnClickListener(v -> showFilterDialog());
        findViewById(R.id.btn_aspect).setOnClickListener(v -> cycleAspectRatio());
        findViewById(R.id.btn_speed).setOnClickListener(v -> showSpeedDialog());
        findViewById(R.id.btn_bookmark).setOnClickListener(v -> addBookmark());
        findViewById(R.id.btn_eq).setOnClickListener(v -> showEqualizerDialog());
        findViewById(R.id.btn_pip).setOnClickListener(v -> enterPiP());
        findViewById(R.id.btn_lock).setOnClickListener(v -> toggleLock());
        findViewById(R.id.btn_unlock).setOnClickListener(v -> toggleLock());
        // Mo Gemini AI chat voi context video hien tai
            View btnAiChat = findViewById(R.id.btn_translate);
            if (btnAiChat != null) btnAiChat.setOnClickListener(v -> openGeminiChat());
        View btnFunscript = findViewById(R.id.btn_funscript);
        if (btnFunscript != null) btnFunscript.setOnClickListener(v -> showFunscriptDialog());
        View btnHandy = findViewById(R.id.btn_handy);
        if (btnHandy != null) btnHandy.setOnClickListener(v -> showHandyDialog());

        View btnAudio = findViewById(R.id.btn_audio);
        if (btnAudio != null) btnAudio.setOnClickListener(v -> showAudioTrackDialog());

        // Long press tren title de chon audio track
        View titleView = findViewById(R.id.tv_title);
        if (titleView != null) titleView.setOnLongClickListener(v -> {
            showAudioTrackDialog();
            return true;
        });
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (fromUser) tvCurrent.setText(formatTime(p));
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { userSeeking = true; }
            @Override public void onStopTrackingTouch(SeekBar sb) {
                userSeeking = false;
                seekPlaybackTo(sb.getProgress());
            }
        });
    }

    private void setupGestures() {
        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                if (isLocked) return false;
                videoLayout.performClick();
                return true;
            }

            @Override public boolean onScroll(MotionEvent e1, MotionEvent e2, float dX, float dY) {
                if (isLocked || e1 == null) return false;
                // Up = tang, down = giam - khong clamp
                float delta = dY * 0.003f;
                if (e1.getX() < screenW / 2f) adjustBrightness(delta);
                else adjustVolume(delta);
                return true;
            }
            @Override public boolean onDoubleTap(MotionEvent e) {
                if (isLocked) return false;
                if (e.getX() < screenW / 2f) {
                    if (mediaPlayer != null) seekPlaybackTo(mediaPlayer.getTime() - 10000);
                    Toast.makeText(PlayerActivity.this, "-10s", Toast.LENGTH_SHORT).show();
                } else {
                    if (mediaPlayer != null) seekPlaybackTo(mediaPlayer.getTime() + 10000);
                    Toast.makeText(PlayerActivity.this, "+10s", Toast.LENGTH_SHORT).show();
                }
                return true;
            }
        });
        videoLayout.setOnTouchListener(this::handleVideoTouch);
        bindVideoSurfaceGestureListener();
    }

    private boolean handleVideoTouch(View view, MotionEvent event) {
        return gestureDetector != null && gestureDetector.onTouchEvent(event);
    }

    /** LibVLC inserts/recreates a SurfaceView that otherwise consumes video taps. */
    private void bindVideoSurfaceGestureListener() {
        if (videoLayout == null) return;
        videoLayout.post(() -> bindGestureToVideoSurfaces(videoLayout));
    }

    private void bindGestureToVideoSurfaces(View view) {
        if (view instanceof SurfaceView || view instanceof TextureView) {
            view.setClickable(true);
            view.setOnTouchListener(this::handleVideoTouch);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                bindGestureToVideoSurfaces(group.getChildAt(i));
            }
        }
    }

    private void seekPlaybackTo(long requestedPositionMs) {
        if (mediaPlayer == null) return;
        long duration = mediaPlayer.getLength();
        long position = Math.max(0, requestedPositionMs);
        if (duration > 0) position = Math.min(position, duration);
        mediaPlayer.setTime(position);
        handler.removeCallbacks(handyCorrectionSync);
        if (handyManager != null && handyManager.isScriptReady()) {
            if (mediaPlayer.isPlaying()) syncHandyWithPlayback();
            else handyManager.stopPlayback(null);
        }
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
        requestAudioFocus();
        // Khong tao Equalizer de tranh xung dot voi RootlessJamesDSP
        // DSP se tu quan ly audio effect tren session nay
        mediaPlayer.setEventListener(event -> {
            switch (event.type) {
                case MediaPlayer.Event.Playing:
                    runOnUiThread(() -> {
                        btnPlayPause.setImageResource(android.R.drawable.ic_media_pause);
                        handler.postDelayed(() -> applyScaleMode(), 200);
                        scheduleHideControls();
                        // Delay de VLC khoi dong audiotrack truoc
                        handler.postDelayed(() -> broadcastAudioSessionOpen(), 300);
                        handler.postDelayed(() -> broadcastAudioSessionOpen(), 1000);
                        syncHandyWithPlayback();
                    });
                    break;
                case MediaPlayer.Event.Paused:
                    runOnUiThread(() -> {
                        handler.removeCallbacks(handyCorrectionSync);
                        if (handyManager != null) handyManager.stopPlayback(null);
                        btnPlayPause.setImageResource(android.R.drawable.ic_media_play);
                    });
                    break;
                case MediaPlayer.Event.EndReached:
                    if (handyManager != null) handyManager.stopPlayback(null);
                    saveHistory();
                    runOnUiThread(() -> {
                        PlaylistManager pm = PlaylistManager.get();
                        if (pm.getRepeatMode() == PlaylistManager.RepeatMode.ONE) {
                            playMedia(uriString);
                        } else if (pm.hasNext()) {
                            playNext();
                        } else {
                            finish();
                        }
                    });
                    break;
            }
        });
        mediaPlayer.attachViews(videoLayout, null, false, false);
        bindVideoSurfaceGestureListener();
    }

    private void playNext() {
        VideoItem next = PlaylistManager.get().getNext();
        if (next != null) {
            saveHistory();
            uriString  = next.getUri().toString();
            videoTitle = next.getName();
            tvTitle.setText(videoTitle);
            playMedia(uriString);
            autoDetectAndApplyEQ(videoTitle);
            updatePlaylistButtons();
        } else {
            Toast.makeText(this, "Het danh sach phat", Toast.LENGTH_SHORT).show();
        }
    }

    private void playPrev() {
        VideoItem prev = PlaylistManager.get().getPrev();
        if (prev != null) {
            saveHistory();
            uriString  = prev.getUri().toString();
            videoTitle = prev.getName();
            tvTitle.setText(videoTitle);
            playMedia(uriString);
            autoDetectAndApplyEQ(videoTitle);
            updatePlaylistButtons();
        }
    }

    private void showQueueDialog() {
        PlaylistManager pm = PlaylistManager.get();
        java.util.List<VideoItem> queue = pm.getQueue();
        if (queue.isEmpty()) { Toast.makeText(this, "Hang doi trong", Toast.LENGTH_SHORT).show(); return; }
        String[] titles = new String[queue.size()];
        for (int i = 0; i < queue.size(); i++)
            titles[i] = (i == pm.getCurrentIndex() ? "▶ " : "  ") + queue.get(i).getName();
        new AlertDialog.Builder(this)
            .setTitle("Hang doi (" + queue.size() + " video)")
            .setItems(titles, (d, w) -> {
                pm.setCurrentIndex(w);
                VideoItem item = pm.getCurrent();
                if (item != null) {
                    saveHistory();
                    uriString = item.getUri().toString();
                    videoTitle = item.getName();
                    tvTitle.setText(videoTitle);
                    playMedia(uriString);
                    updatePlaylistButtons();
                }
            }).show();
    }

    private void toggleNightMode() {
        nightMode = !nightMode;
        if (nightOverlay != null) nightOverlay.setVisibility(nightMode ? View.VISIBLE : View.GONE);
        Toast.makeText(this, nightMode ? "Night mode: ON" : "Night mode: OFF", Toast.LENGTH_SHORT).show();
    }

    interface SliderCallback { void onValue(int value); }

    private void showFilterDialog() {
        String[] options = {
            "Sang (Brightness): " + String.format("%.1f", filterBrightness),
            "Tuong phan (Contrast): " + String.format("%.1f", filterContrast),
            "Mau (Saturation): " + String.format("%.1f", filterSaturation),
            filtersEnabled ? "Tat bo loc" : "Bat bo loc",
            "Reset mac dinh"
        };
        new AlertDialog.Builder(this)
            .setTitle("Bo loc video")
            .setItems(options, (d, w) -> {
                switch (w) {
                    case 0: showSliderDialog("Brightness", 0, 200, (int)(filterBrightness*100),
                        val -> { filterBrightness = val/100f; applyFilters(); }); break;
                    case 1: showSliderDialog("Contrast", 0, 200, (int)(filterContrast*100),
                        val -> { filterContrast = val/100f; applyFilters(); }); break;
                    case 2: showSliderDialog("Saturation", 0, 200, (int)(filterSaturation*100),
                        val -> { filterSaturation = val/100f; applyFilters(); }); break;
                    case 3:
                        filtersEnabled = !filtersEnabled;
                        applyFilters();
                        Toast.makeText(this, filtersEnabled ? "Bo loc: ON" : "Bo loc: OFF", Toast.LENGTH_SHORT).show();
                        break;
                    case 4:
                        filterBrightness = 1.0f; filterContrast = 1.0f; filterSaturation = 1.0f;
                        filtersEnabled = false; applyFilters();
                        Toast.makeText(this, "Da reset bo loc", Toast.LENGTH_SHORT).show();
                        break;
                }
            }).show();
    }

    private void showSliderDialog(String title, int min, int max, int current, SliderCallback cb) {
        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        layout.setPadding(48, 24, 48, 24);
        SeekBar slider = new SeekBar(this);
        slider.setMax(max - min);
        slider.setProgress(current - min);
        TextView tvVal = new TextView(this);
        tvVal.setText(String.valueOf(current));
        tvVal.setGravity(android.view.Gravity.CENTER);
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean u) { tvVal.setText(String.valueOf(p + min)); }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
        layout.addView(tvVal);
        layout.addView(slider);
        new AlertDialog.Builder(this)
            .setTitle(title).setView(layout)
            .setPositiveButton("OK", (d, w) -> cb.onValue(slider.getProgress() + min))
            .setNegativeButton("Huy", null).show();
    }

    private void applyFilters() {
        if (videoLayout == null) return;
        if (!filtersEnabled) {
            videoLayout.setLayerType(View.LAYER_TYPE_NONE, null);
            return;
        }
        float c = filterContrast;
        float b = (filterBrightness - 1.0f) * 255f;
        float[] matrix = {
            c, 0, 0, 0, b,
            0, c, 0, 0, b,
            0, 0, c, 0, b,
            0, 0, 0, 1, 0
        };
        ColorMatrix cm = new ColorMatrix(matrix);
        ColorMatrix sat = new ColorMatrix();
        sat.setSaturation(filterSaturation);
        cm.postConcat(sat);
        Paint paint = new Paint();
        paint.setColorFilter(new ColorMatrixColorFilter(cm));
        videoLayout.setLayerType(View.LAYER_TYPE_HARDWARE, paint);
    }

    private void applyScaleMode() {
        if (mediaPlayer == null) return;
        switch (scaleMode) {
            case 0: mediaPlayer.setAspectRatio(null); mediaPlayer.setScale(0); break;
            case 1: mediaPlayer.setAspectRatio(screenW + ":" + screenH); mediaPlayer.setScale(0); break;
            case 2: mediaPlayer.setAspectRatio("16:9"); mediaPlayer.setScale(0); break;
            case 3: mediaPlayer.setAspectRatio("4:3"); mediaPlayer.setScale(0); break;
            case 4: mediaPlayer.setAspectRatio(null); mediaPlayer.setScale(1); break;
        }
    }

    private void cycleAspectRatio() {
        scaleMode = (scaleMode + 1) % 5;
        applyScaleMode();
        String[] labels = {"Best Fit", "Fill", "16:9", "4:3", "Zoom"};
        Toast.makeText(this, labels[scaleMode], Toast.LENGTH_SHORT).show();
    }

    private void playMedia(String uri) {
        boolean mediaChanged = pendingUri == null || !uri.equals(pendingUri);
        pendingUri = uri;
        if (handyManager != null && mediaChanged) {
            handler.removeCallbacks(handyCorrectionSync);
            handyManager.resetScript();
            handyCheckedUri = null;
            handyPreparedUri = null;
            handyPrepareFailures = 0;
        }
        try {
            Uri u = Uri.parse(uri);
            Media media;
            if ("content".equals(u.getScheme())) {
                android.os.ParcelFileDescriptor oldPfd = currentPfd;
                currentPfd = getContentResolver().openFileDescriptor(u, "r");
                if (currentPfd == null) {
                    if (oldPfd != null) try { oldPfd.close(); } catch (Exception ignored) {}
                    return;
                }
                media = new Media(libVLC, currentPfd.getFileDescriptor());
                if (oldPfd != null) try { oldPfd.close(); } catch (Exception ignored) {}
            } else {
                closePfd();
                media = new Media(libVLC, u);
            }
            media.setHWDecoderEnabled(true, false);
            media.addOption(":file-caching=1500");
            media.addOption(":codec=mediacodec_ndk,mediacodec,omxil,any");
            mediaPlayer.setMedia(media);
            media.release();
            mediaPlayer.play();
            videoLayout.post(() -> {
                if (!uri.equals(pendingUri)) return;
                try { mediaPlayer.detachViews(); } catch (Exception ignored) {}
                mediaPlayer.attachViews(videoLayout, null, false, false);
                bindVideoSurfaceGestureListener();
            });
        } catch (Exception e) {
            Toast.makeText(this, "Loi: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
        if (handyManager != null && handyManager.isConnected()) {
            handler.postDelayed(this::autoPrepareHandyForCurrentVideo, 300);
        }
        dbExecutor.execute(() -> {
            if (!uri.equals(pendingUri)) return;
            HistoryItem history = AppDatabase.get(this).dao().getHistoryByUri(uri);
            if (history != null && history.lastPosition > 5000) {
                final long pos = history.lastPosition;
                handler.postDelayed(() -> {
                    if (uri.equals(pendingUri)) {
                        seekPlaybackTo(pos);
                        Toast.makeText(this, "Tiep tuc tu " + formatTime(pos), Toast.LENGTH_SHORT).show();
                    }
                }, 1500);
            }
        });
    }


    private void saveHistory() {
        if (uriString == null) return;
        final String historyUri = uriString;
        final String historyTitle = videoTitle != null ? videoTitle : "Video";
        final long duration = mediaPlayer != null ? mediaPlayer.getLength() : 0;
        long currentPosition = mediaPlayer != null ? mediaPlayer.getTime() : 0;
        if (duration > 0 && currentPosition >= Math.max(0, duration - 5_000)) {
            currentPosition = 0;
        }
        final long savedPosition = currentPosition;
        dbExecutor.execute(() -> {
            AppDatabase.get(this).dao().deleteHistory(historyUri);
            AppDatabase.get(this).dao().insertHistory(
                new HistoryItem(historyUri, historyTitle, savedPosition, duration));
        });
    }

    private void addBookmark() {
        if (mediaPlayer == null || uriString == null) return;
        long pos = mediaPlayer.getTime();
        EditText input = new EditText(this);
        input.setText("Bookmark " + formatTime(pos));
        new AlertDialog.Builder(this)
            .setTitle("Danh dau thoi diem").setView(input)
            .setPositiveButton("Luu", (d, w) -> {
                String label = input.getText().toString().trim();
                if (label.isEmpty()) label = "Bookmark " + formatTime(pos);
                final String fl = label;
                dbExecutor.execute(() -> AppDatabase.get(this).dao().insertBookmark(
                    new BookmarkItem(uriString, videoTitle != null ? videoTitle : "Video", pos, fl)));
                Toast.makeText(this, "Da danh dau: " + fl, Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("Huy", null).show();
    }

    private void showSpeedDialog() {
        String[] speeds = {"0.25x","0.5x","0.75x","1.0x","1.25x","1.5x","1.75x","2.0x"};
        float[] vals = {0.25f,0.5f,0.75f,1.0f,1.25f,1.5f,1.75f,2.0f};
        int cur = 3;
        for (int i = 0; i < vals.length; i++) if (Math.abs(vals[i]-playbackSpeed)<0.01f) { cur=i; break; }
        new AlertDialog.Builder(this)
            .setTitle("Toc do phat")
            .setSingleChoiceItems(speeds, cur, (d, w) -> {
                if (handyManager != null && handyManager.isScriptReady()
                        && Math.abs(vals[w] - 1.0f) > 0.01f) {
                    Toast.makeText(this,
                        "Để The Handy đồng bộ chính xác, tốc độ video được giữ ở 1.0x",
                        Toast.LENGTH_LONG).show();
                    playbackSpeed = 1.0f;
                    if (mediaPlayer != null) mediaPlayer.setRate(1.0f);
                    tvSpeed.setText("1.0x");
                    d.dismiss();
                    return;
                }
                playbackSpeed = vals[w];
                if (mediaPlayer != null) mediaPlayer.setRate(playbackSpeed);
                tvSpeed.setText(speeds[w]);
                d.dismiss();
            }).show();
    }

    private void showEqualizerDialog() {
        if (equalizer == null) { Toast.makeText(this, "EQ khong kha dung", Toast.LENGTH_SHORT).show(); return; }
        short presets = equalizer.getNumberOfPresets();
        String[] names = new String[presets + 1];
        names[0] = "Mac dinh";
        for (short i = 0; i < presets; i++) names[i+1] = equalizer.getPresetName(i);
        new AlertDialog.Builder(this)
            .setTitle("Equalizer")
            .setItems(names, (d, w) -> {
                if (w > 0) equalizer.usePreset((short)(w-1));
                Toast.makeText(this, names[w], Toast.LENGTH_SHORT).show();
            }).show();
    }

    private void enterPiP() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                enterPictureInPictureMode(new PictureInPictureParams.Builder()
                    .setAspectRatio(new Rational(16, 9)).build());
            } catch (Exception e) {
                Toast.makeText(this, "PiP khong kha dung", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void toggleLock() {
        isLocked = !isLocked;
        if (isLocked) {
            controlsOverlay.setVisibility(View.GONE);
            lockOverlay.setVisibility(View.VISIBLE);
            handler.removeCallbacks(hideControls);
            Toast.makeText(this, "Man hinh da khoa", Toast.LENGTH_SHORT).show();
        } else {
            lockOverlay.setVisibility(View.GONE);
            controlsOverlay.setVisibility(View.VISIBLE);
            controlsOverlay.setAlpha(1f);
            controlsVisible = true;
            scheduleHideControls();
            Toast.makeText(this, "Da mo khoa", Toast.LENGTH_SHORT).show();
        }
    }

    private void openGeminiChat() {
        TranslationManager tm = new TranslationManager(this);
        String[] opts = {"Dich subtitle tu URL", "Doi ngon ngu (hien tai: " + tm.getTargetLanguageName() + ")"};
        new AlertDialog.Builder(this).setTitle("Dich AI")
            .setItems(opts, (d, which) -> { if (which == 0) showSrtUrlInput(); else showChangeLangDialog(); }).show();
    }

    private void showChangeLangDialog() {
        TranslationManager tm = new TranslationManager(this);
        String[][] langs = TranslationManager.LANGUAGES;
        String[] names = new String[langs.length];
        String cur = tm.getTargetLanguage();
        int curIdx = 0;
        for (int i = 0; i < langs.length; i++) { names[i] = langs[i][0]; if (langs[i][1].equals(cur)) curIdx = i; }
        final int[] sel = {curIdx};
        new AlertDialog.Builder(this).setTitle("Chon ngon ngu")
            .setSingleChoiceItems(names, curIdx, (d, w) -> sel[0] = w)
            .setPositiveButton("Luu", (d, w) -> {
                tm.setTargetLanguage(langs[sel[0]][1]);
                Toast.makeText(this, "Da chon: " + langs[sel[0]][0], Toast.LENGTH_SHORT).show();
            }).setNegativeButton("Huy", null).show();
    }

    private void showSrtUrlInput() {
        EditText input = new EditText(this);
        input.setHint("https://example.com/subtitle.srt");
        new AlertDialog.Builder(this).setTitle("URL file SRT").setView(input)
            .setPositiveButton("Dich", (d, w) -> {
                String url = input.getText().toString().trim();
                if (!url.isEmpty()) startSrtDownloadAndTranslate(url);
            }).setNegativeButton("Huy", null).show();
    }

    private void startSrtDownloadAndTranslate(String url) {
        ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage("Dang tai subtitle..."); pd.setCancelable(false); pd.show();
        new Thread(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0");
                conn.setInstanceFollowRedirects(true);
                BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String ln;
                while ((ln = br.readLine()) != null) sb.append(ln).append("\n");
                br.close();
                final String srt = sb.toString();
                runOnUiThread(() -> pd.setMessage("Dang dich..."));
                TranslationManager tm = new TranslationManager(this);
                tm.translateSrt(srt, "auto",
                    msg -> runOnUiThread(() -> pd.setMessage(msg)),
                    new TranslationManager.TranslateCallback() {
                        @Override public void onSuccess(String t) {
                            runOnUiThread(() -> { pd.dismiss(); saveSrtAndLoad(t); });
                        }
                        @Override public void onError(String e) {
                            runOnUiThread(() -> { pd.dismiss(); Toast.makeText(PlayerActivity.this, e, Toast.LENGTH_SHORT).show(); });
                        }
                    });
            } catch (Exception e) {
                runOnUiThread(() -> { pd.dismiss(); Toast.makeText(this, "Loi tai: " + e.getMessage(), Toast.LENGTH_SHORT).show(); });
            }
        }).start();
    }

    private void saveSrtAndLoad(String srtContent) {
        try {
            File f = new File(getExternalFilesDir(null), "translated.srt");
            FileWriter fw = new FileWriter(f); fw.write(srtContent); fw.close();
            if (mediaPlayer != null) mediaPlayer.addSlave(Media.Slave.Type.Subtitle, Uri.fromFile(f).toString(), true);
            Toast.makeText(this, "Da dich va load subtitle!", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Loi luu: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void autoDetectAndApplyEQ(String title) {
        if (equalizer == null || title == null) return;
        String lower = title.toLowerCase();
        short presetIndex = -1;
        if (lower.contains("fap") || lower.contains("music") || lower.contains("dance"))
            presetIndex = findPreset(new String[]{"Dance", "Pop"});
        else if (lower.contains("movie") || lower.contains("film"))
            presetIndex = findPreset(new String[]{"Theater", "Movie"});
        else if (lower.contains("anime")) presetIndex = findPreset(new String[]{"Vocal", "Pop"});
        else if (lower.contains("game") || lower.contains("action")) presetIndex = findPreset(new String[]{"Rock"});
        if (presetIndex >= 0) equalizer.usePreset(presetIndex);
    }

    private short findPreset(String[] keywords) {
        if (equalizer == null) return -1;
        short presets = equalizer.getNumberOfPresets();
        for (String kw : keywords)
            for (short i = 0; i < presets; i++)
                if (equalizer.getPresetName(i) != null && equalizer.getPresetName(i).toLowerCase().contains(kw.toLowerCase()))
                    return i;
        return -1;
    }

    // Overlay trai (do sang) va phai (am luong)
    private float volumeLevel = -1f; // Track float giong brightness
    private android.widget.LinearLayout overlayBrightness;
    private android.widget.LinearLayout overlayVolume;
    private android.widget.TextView tvBrightnessVal;
    private android.widget.TextView tvVolumeVal;
    private android.widget.ProgressBar barBrightness;
    private android.widget.ProgressBar barVolume;

    private android.widget.LinearLayout makeOverlay(boolean isLeft) {
        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        layout.setGravity(android.view.Gravity.CENTER);
        layout.setBackgroundColor(0xCC000000);
        layout.setPadding(28, 18, 28, 18);

        // Label text
        android.widget.TextView tv = new android.widget.TextView(this);
        tv.setTextColor(0xFFFFFFFF);
        tv.setTextSize(13);
        tv.setGravity(android.view.Gravity.CENTER);
        tv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);

        // Horizontal progress bar - khong xoay, khong bi clip
        android.widget.ProgressBar pb = new android.widget.ProgressBar(this,
            null, android.R.attr.progressBarStyleHorizontal);
        pb.setMax(100);
        pb.setProgressTintList(android.content.res.ColorStateList.valueOf(
            isLeft ? 0xFFFFD700 : 0xFF4FC3F7));
        pb.setProgressBackgroundTintList(
            android.content.res.ColorStateList.valueOf(0x44FFFFFF));
        android.widget.LinearLayout.LayoutParams pbp =
            new android.widget.LinearLayout.LayoutParams(180, 10);
        pbp.topMargin = 10;
        pbp.gravity = android.view.Gravity.CENTER_HORIZONTAL;

        layout.addView(tv);
        layout.addView(pb, pbp);
        layout.setVisibility(android.view.View.GONE);
        layout.setTag(tv);
        layout.setTag(R.id.btn_filter, pb);

        // Vi tri cach man hinh 60dp de khong bi che
        android.widget.FrameLayout.LayoutParams fp =
            new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT);
        fp.gravity = android.view.Gravity.CENTER_VERTICAL |
            (isLeft ? android.view.Gravity.START : android.view.Gravity.END);
        int margin = (int)(60 * getResources().getDisplayMetrics().density);
        fp.setMarginStart(isLeft ? margin : 0);
        fp.setMarginEnd(isLeft ? 0 : margin);

        android.view.ViewGroup root = (android.view.ViewGroup)
            getWindow().getDecorView().findViewById(android.R.id.content);
        root.addView(layout, fp);
        return layout;
    }

    private void ensureOverlays() {
        if (overlayBrightness == null) {
            overlayBrightness = makeOverlay(true);
            tvBrightnessVal = (android.widget.TextView) overlayBrightness.getTag();
            barBrightness = (android.widget.ProgressBar)
                overlayBrightness.getTag(R.id.btn_filter);
        }
        if (overlayVolume == null) {
            overlayVolume = makeOverlay(false);
            tvVolumeVal = (android.widget.TextView) overlayVolume.getTag();
            barVolume = (android.widget.ProgressBar)
                overlayVolume.getTag(R.id.btn_filter);
        }
    }

    private void showBrightnessOverlay(int percent) {
        ensureOverlays();
        tvBrightnessVal.setText("Bright\n" + percent + "%");
        barBrightness.setProgress(percent);
        overlayBrightness.setVisibility(android.view.View.VISIBLE);
        handler.removeCallbacks(hideBrightness);
        handler.postDelayed(hideBrightness, 1500);
    }

    private void showVolumeOverlay(int percent) {
        ensureOverlays();
        tvVolumeVal.setText("Volume\n" + percent + "%");
        barVolume.setProgress(percent);
        overlayVolume.setVisibility(android.view.View.VISIBLE);
        handler.removeCallbacks(hideVolume);
        handler.postDelayed(hideVolume, 1500);
    }

    private Runnable hideBrightness = () -> {
        if (overlayBrightness != null)
            overlayBrightness.setVisibility(android.view.View.GONE);
    };
    private Runnable hideVolume = () -> {
        if (overlayVolume != null)
            overlayVolume.setVisibility(android.view.View.GONE);
    };

    private void adjustBrightness(float delta) {
        WindowManager.LayoutParams p = getWindow().getAttributes();
        if (p.screenBrightness < 0) p.screenBrightness = 0.5f;
        p.screenBrightness = Math.max(0.01f, Math.min(1.0f, p.screenBrightness + delta));
        getWindow().setAttributes(p);
        showBrightnessOverlay((int)(p.screenBrightness * 100));
    }

    private void adjustVolume(float delta) {
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        // Khoi tao volumeLevel lan dau giong brightness
        if (volumeLevel < 0) {
            volumeLevel = (float) am.getStreamVolume(AudioManager.STREAM_MUSIC) / max;
        }
        // Cong don float lien tuc giong brightness
        volumeLevel = Math.max(0f, Math.min(1f, volumeLevel + delta));
        int next = Math.round(volumeLevel * max);
        am.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0);
        showVolumeOverlay((int)(volumeLevel * 100));
    }

    private void togglePlayPause() {
        if (mediaPlayer == null) return;
        if (mediaPlayer.isPlaying()) mediaPlayer.pause();
        else mediaPlayer.play();
    }

    private void toggleControls() {
        if (controlsVisible) {
            handler.removeCallbacks(hideControls);
            controlsOverlay.animate().alpha(0f).setDuration(300).withEndAction(() -> controlsOverlay.setVisibility(View.GONE));
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

    private android.media.AudioFocusRequest audioFocusRequest;
    private android.media.AudioManager.OnAudioFocusChangeListener focusListener =
        focusChange -> {
            if (mediaPlayer == null) return;
            switch (focusChange) {
                case android.media.AudioManager.AUDIOFOCUS_LOSS:
                    // Chi pause khi mat focus hoan toan (call dien thoai...)
                    runOnUiThread(() -> { if (mediaPlayer.isPlaying()) mediaPlayer.pause(); });
                    break;
                case android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                    // Khong pause - de DSP va cac app khac hoat dong binh thuong
                    break;
                case android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                    // Khong giam volume - giu nguyen
                    break;
                case android.media.AudioManager.AUDIOFOCUS_GAIN:
                    runOnUiThread(() -> {
                        if (!mediaPlayer.isPlaying()) mediaPlayer.play();
                        handler.postDelayed(() -> broadcastAudioSessionOpen(), 200);
                    });
                    break;
            }
        };

    private void requestAudioFocus() {
        android.media.AudioManager am =
            (android.media.AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            android.media.AudioAttributes attrs = new android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE)
                .build();
            audioFocusRequest = new android.media.AudioFocusRequest.Builder(
                android.media.AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener(focusListener)
                .setWillPauseWhenDucked(false)
                .build();
            am.requestAudioFocus(audioFocusRequest);
        } else {
            am.requestAudioFocus(focusListener,
                android.media.AudioManager.STREAM_MUSIC,
                android.media.AudioManager.AUDIOFOCUS_GAIN);
        }
    }

    private void abandonAudioFocus() {
        android.media.AudioManager am =
            (android.media.AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O
                && audioFocusRequest != null) {
            am.abandonAudioFocusRequest(audioFocusRequest);
        } else {
            am.abandonAudioFocus(focusListener);
        }
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
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            | View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUI();
    }

    @Override public void onUserLeaveHint() {
        super.onUserLeaveHint();
        enterPiP();
    }



    private void showHandyDialog() {
        String savedKey = handyManager.getSavedKey();
        String status = handyManager.isConnected()
            ? "Trạng thái: ĐÃ KẾT NỐI" + (handyManager.isScriptReady() ? " · SCRIPT SẴN SÀNG" : "")
            : "Trạng thái: Chưa kết nối";
        String[] opts = {
            status,
            "Nhap / doi Connection Key",
            "Ket noi The Handy",
            "Load Funscript + Sync",
            "Dong bo voi video hien tai",
            "Ngat ket noi"
        };
        new AlertDialog.Builder(this)
            .setTitle("The Handy")
            .setItems(opts, (d, w) -> {
                switch (w) {
                    case 0: showHandyStatus(); break;
                    case 1: showKeyInput(); break;
                    case 2: connectHandy(); break;
                    case 3: showHandyFunscriptDialog(); break;
                    case 4: syncHandyNow(); break;
                    case 5: disconnectHandy(); break;
                }
            }).show();
    }

    private void showHandyStatus() {
        if (!handyManager.isConnected()) {
            Toast.makeText(this, "Chua ket noi The Handy", Toast.LENGTH_SHORT).show();
            return;
        }
        handyManager.getStatus(new HandyManager.HandyCallback() {
            @Override public void onSuccess(String m) { runOnUiThread(() -> new AlertDialog.Builder(PlayerActivity.this).setTitle("The Handy Status").setMessage(m).setPositiveButton("OK", null).show()); }
            @Override public void onError(String e) { runOnUiThread(() -> Toast.makeText(PlayerActivity.this, e, Toast.LENGTH_SHORT).show()); }
        });
    }

    private void showKeyInput() {
        EditText input = new EditText(this);
        input.setHint("xxxx-xxxx-xxxx-xxxx");
        input.setText(handyManager.getSavedKey());
        input.setSingleLine(true);
        new AlertDialog.Builder(this)
            .setTitle("Connection Key")
            .setMessage("Lay key tai: handyfeeling.com")
            .setView(input)
            .setPositiveButton("Luu", (d, w) -> {
                String key = input.getText().toString().trim();
                if (!key.isEmpty()) {
                    saveAndConnectHandyKey(key);
                }
            })
            .setNegativeButton("Huy", null).show();
    }

    private void saveAndConnectHandyKey(String key) {
        Runnable saveNewKey = () -> {
            handyManager.saveKey(key);
            handyCheckedUri = null;
            handyPreparedUri = null;
            Toast.makeText(this, "Đã lưu key, app sẽ tự kết nối", Toast.LENGTH_SHORT).show();
            autoConnectHandy(false);
        };
        if (!handyManager.isConnected()) {
            saveNewKey.run();
            return;
        }
        // Stop the old device before switching keys so it can never be left moving.
        handyManager.disconnect(new HandyManager.HandyCallback() {
            @Override public void onSuccess(String message) {
                saveNewKey.run();
            }

            @Override public void onError(String error) {
                Toast.makeText(PlayerActivity.this,
                    "Không xác nhận được lệnh dừng thiết bị cũ: " + error,
                    Toast.LENGTH_LONG).show();
            }
        });
    }

    private void connectHandy() {
        autoConnectHandy(true);
    }

    private void autoConnectHandy(boolean interactive) {
        if (handyManager == null || handyManager.getSavedKey().isEmpty()) {
            if (interactive) showKeyInput();
            return;
        }
        if (handyManager.isConnected()) {
            prepareScriptAfterConnection();
            return;
        }
        if (handyConnectInProgress) return;
        handyConnectInProgress = true;
        if (interactive) Toast.makeText(this, "Đang kết nối...", Toast.LENGTH_SHORT).show();
        handyManager.connect(new HandyManager.HandyCallback() {
            @Override public void onSuccess(String message) {
                handyConnectInProgress = false;
                prepareScriptAfterConnection();
                if (interactive) {
                    new AlertDialog.Builder(PlayerActivity.this)
                        .setTitle("The Handy")
                        .setMessage(message)
                        .setPositiveButton("OK", null).show();
                } else {
                    Toast.makeText(PlayerActivity.this,
                        "The Handy đã tự kết nối", Toast.LENGTH_SHORT).show();
                }
            }

            @Override public void onError(String error) {
                handyConnectInProgress = false;
                if (interactive) {
                    Toast.makeText(PlayerActivity.this, "Lỗi: " + error, Toast.LENGTH_LONG).show();
                } else {
                    android.util.Log.w("TheHandy", "Auto connect: " + error);
                }
            }
        });
    }

    private void prepareScriptAfterConnection() {
        if (pendingHandyScriptUrl != null) uploadPendingHandyScriptUrl();
        else autoPrepareHandyForCurrentVideo();
    }

    private void showHandyFunscriptDialog() {
        if (!handyManager.isConnected()) { Toast.makeText(this, "Ket noi The Handy truoc!", Toast.LENGTH_SHORT).show(); return; }
        EditText input = new EditText(this);
        input.setHint("URL file .funscript hoặc .csv");
        new AlertDialog.Builder(this)
            .setTitle("Tải Funscript cho The Handy")
            .setMessage("App sẽ tự chuyển sang CSV và tải lên máy chủ tạm chính thức của Handy")
            .setView(input)
            .setPositiveButton("Load & Sync", (d, w) -> {
                String url = input.getText().toString().trim();
                if (url.isEmpty()) return;
                Toast.makeText(this, "Đang xử lý script...", Toast.LENGTH_SHORT).show();
                handyManager.uploadAndSetupScript(url, new HandyManager.HandyCallback() {
                    @Override public void onSuccess(String m) {
                        handyPreparedUri = uriString;
                        Toast.makeText(PlayerActivity.this, m, Toast.LENGTH_SHORT).show();
                        if (mediaPlayer != null && mediaPlayer.isPlaying()) syncHandyWithPlayback();
                    }
                    @Override public void onError(String e) {
                        Toast.makeText(PlayerActivity.this, "Lỗi: " + e, Toast.LENGTH_LONG).show();
                    }
                });
            })
            .setNegativeButton("Huy", null).show();
    }

    private void syncHandyWithPlayback() {
        if (handyManager == null || mediaPlayer == null
                || !handyManager.isScriptReady() || !mediaPlayer.isPlaying()) return;

        // REST v2 HSSP plays scripts at real-time speed. Keeping VLC at 1.0x is
        // the only drift-free behavior for the connection-key integration.
        if (Math.abs(playbackSpeed - 1.0f) > 0.01f) {
            playbackSpeed = 1.0f;
            mediaPlayer.setRate(1.0f);
            tvSpeed.setText("1.0x");
        }

        handler.removeCallbacks(handyCorrectionSync);
        handyManager.play(mediaPlayer.getTime(), null);
        // Match the official SDK behavior: a second timestamp after VLC has
        // settled corrects startup/caching latency without continuous jolts.
        handler.postDelayed(handyCorrectionSync, 2_500);
    }

    private void syncHandyNow() {
        if (!handyManager.isConnected() || !handyManager.isScriptReady() || mediaPlayer == null) {
            Toast.makeText(this, "The Handy chưa kết nối hoặc chưa có funscript", Toast.LENGTH_SHORT).show();
            return;
        }
        long pos = mediaPlayer.getTime();
        handyManager.play(pos, new HandyManager.HandyCallback() {
            @Override public void onSuccess(String m) { runOnUiThread(() -> Toast.makeText(PlayerActivity.this, "The Handy dong bo tai " + formatTime(pos), Toast.LENGTH_SHORT).show()); }
            @Override public void onError(String e) { runOnUiThread(() -> Toast.makeText(PlayerActivity.this, "Loi dong bo: " + e, Toast.LENGTH_SHORT).show()); }
        });
    }

    private void disconnectHandy() {
        handler.removeCallbacks(handyCorrectionSync);
        handyManager.disconnect(new HandyManager.HandyCallback() {
            @Override public void onSuccess(String m) {
                Toast.makeText(PlayerActivity.this, "Đã ngắt The Handy", Toast.LENGTH_SHORT).show();
            }
            @Override public void onError(String e) {
                Toast.makeText(PlayerActivity.this, e, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void showFunscriptDialog() {
        String[] opts = {
            "Tự động tìm và đồng bộ",
            "Chọn file .funscript/.csv",
            "Tải từ URL và đồng bộ",
            "Dừng đồng bộ"
        };
        new AlertDialog.Builder(this).setTitle("Funscript")
            .setItems(opts, (d, w) -> {
                if (w == 0) autoFindAndSyncFunscript();
                else if (w == 1) {
                    funscriptPicker.launch(new String[]{
                        "application/json", "text/plain", "text/csv",
                        "application/octet-stream", "*/*"
                    });
                } else if (w == 2) {
                    showFunscriptUrlInput();
                } else {
                    handler.removeCallbacks(handyCorrectionSync);
                    if (handyManager != null) handyManager.stopPlayback(null);
                    Toast.makeText(this, "Đã dừng đồng bộ The Handy", Toast.LENGTH_SHORT).show();
                }
            }).show();
    }

    private void showFunscriptUrlInput() {
        EditText input = new EditText(this);
        input.setHint("https://example.com/video.funscript");
        new AlertDialog.Builder(this).setTitle("Funscript URL").setView(input)
            .setPositiveButton("Tải và đồng bộ", (d, w) -> {
                String url = input.getText().toString().trim();
                if (url.isEmpty()) return;
                prepareHandyScriptUrl(url);
            }).setNegativeButton("Hủy", null).show();
    }

    private void prepareHandyScriptUrl(String url) {
        String lower = url.toLowerCase(Locale.US);
        if (!lower.startsWith("https://") && !lower.startsWith("http://")) {
            Toast.makeText(this, "URL funscript không hợp lệ", Toast.LENGTH_LONG).show();
            return;
        }
        pendingHandyScriptUrl = url;
        if (handyManager != null && handyManager.isConnected()) {
            uploadPendingHandyScriptUrl();
        } else {
            Toast.makeText(this, "Đang kết nối The Handy...", Toast.LENGTH_SHORT).show();
            autoConnectHandy(false);
        }
    }

    private void uploadPendingHandyScriptUrl() {
        if (pendingHandyScriptUrl == null || handyManager == null
                || !handyManager.isConnected()) return;
        String url = pendingHandyScriptUrl;
        pendingHandyScriptUrl = null;
        Toast.makeText(this, "Đang tải và chuẩn bị funscript...", Toast.LENGTH_SHORT).show();
        handyManager.uploadAndSetupScript(url, new HandyManager.HandyCallback() {
            @Override public void onSuccess(String message) {
                handyPreparedUri = uriString;
                handyPrepareFailures = 0;
                Toast.makeText(PlayerActivity.this,
                    "The Handy đã sẵn sàng", Toast.LENGTH_SHORT).show();
                if (mediaPlayer != null && mediaPlayer.isPlaying()) syncHandyWithPlayback();
            }

            @Override public void onError(String error) {
                Toast.makeText(PlayerActivity.this,
                    "Lỗi The Handy: " + error, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void onFunscriptPicked(Uri pickedUri) {
        if (pickedUri == null || uriString == null) return;
        try {
            getContentResolver().takePersistableUriPermission(pickedUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {
            // A copied app-private file is used, so a persistable grant is optional.
        }
        Toast.makeText(this, "Đang nhập funscript...", Toast.LENGTH_SHORT).show();
        dbExecutor.execute(() -> {
            try {
                File cachedScript = copyPickedFunscript(pickedUri);
                handler.post(() -> preparePickedFunscript(cachedScript));
            } catch (Exception e) {
                handler.post(() -> Toast.makeText(PlayerActivity.this,
                    "Không nhập được funscript: " + e.getMessage(),
                    Toast.LENGTH_LONG).show());
            }
        });
    }

    private File copyPickedFunscript(Uri pickedUri) throws Exception {
        String baseName = resolveVideoBaseName(uriString);
        if (baseName == null || baseName.trim().isEmpty()) {
            throw new IOException("Không xác định được tên video");
        }
        String pickedName = queryDisplayName(pickedUri);
        String extension = pickedName != null
                && pickedName.toLowerCase(Locale.US).endsWith(".csv")
            ? ".csv" : ".funscript";
        File directory = getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS);
        if (directory == null) directory = new File(getFilesDir(), "funscripts");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Không tạo được thư mục funscript của app");
        }
        File destination = new File(directory, baseName + extension);
        int total = 0;
        try (InputStream input = getContentResolver().openInputStream(pickedUri);
             FileOutputStream output = new FileOutputStream(destination, false)) {
            if (input == null) throw new IOException("Không đọc được file đã chọn");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > 2 * 1024 * 1024) {
                    throw new IOException("Funscript vượt giới hạn 2 MB");
                }
                output.write(buffer, 0, read);
            }
        } catch (Exception e) {
            if (destination.exists()) destination.delete();
            throw e;
        }
        if (total == 0) {
            destination.delete();
            throw new IOException("Funscript rỗng");
        }
        return destination;
    }

    private void preparePickedFunscript(File script) {
        handyCheckedUri = null;
        if (handyManager != null && handyManager.isConnected()) {
            handyCheckedUri = uriString;
            uploadFunscriptAndSync(script, true);
        } else {
            Toast.makeText(this,
                "Đã lưu script, đang kết nối The Handy...", Toast.LENGTH_SHORT).show();
            autoConnectHandy(false);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
    }    @Override
    protected void onResume() {
        super.onResume();
        // Gui lai session cho DSP khi quay lai app
        if (audioSessionId != android.media.audiofx.AudioEffect.ERROR_BAD_VALUE) {
            handler.postDelayed(() -> broadcastAudioSessionOpen(), 500);
        }
        if (mediaPlayer != null && videoLayout != null) {
            videoLayout.post(() -> {
                try {
                    // Chi attach lai neu chua attach - tranh reset audio pipeline
                    if (isInBackground) {
                        mediaPlayer.attachViews(videoLayout, null, false,
                            filtersEnabled);
                        bindVideoSurfaceGestureListener();
                    }
                    if (isInBackground) {
                        if (lastPosition > 0) mediaPlayer.setTime(lastPosition);
                        mediaPlayer.play();
                        isInBackground = false;
                        // Gui lai session cho DSP sau khi resume
                        handler.postDelayed(() -> broadcastAudioSessionOpen(), 500);
                        handler.postDelayed(() -> broadcastAudioSessionOpen(), 1500);
                    }
                } catch (Exception e) {}
            });
        }
    }

    
@Override protected void onStop() {
        super.onStop();
        isInBackground = true;
        saveHistory();
        handler.removeCallbacks(handyCorrectionSync);
        if (handyManager != null) handyManager.stopPlayback(null);
        if (mediaPlayer != null) { lastPosition = mediaPlayer.getTime(); mediaPlayer.pause(); }
    }

    @Override protected void onDestroy() {
        if (getIntent().getBooleanExtra(EXTRA_AUTO_CLEANUP_TORRENT, false)) {
            TorrentManager.stopActiveAndCleanup(this);
        }
        handler.removeCallbacks(handyCorrectionSync);
        handler.removeCallbacks(handyHealthCheck);
        if (handyManager != null) handyManager.destroy();
        super.onDestroy();
        broadcastAudioSessionClose();
        if (equalizer != null) equalizer.release();
        handler.removeCallbacksAndMessages(null);
        if (mediaPlayer != null) mediaPlayer.release();
        if (libVLC != null) libVLC.release();
        // Let the final history write queued by onStop complete before exit.
        dbExecutor.shutdown();
        closePfd();
    }
    // Chuyển sang CSV và dùng dịch vụ script tạm chính thức của Handy.
    private void uploadFunscriptAndSync(java.io.File file) {
        uploadFunscriptAndSync(file, true);
    }

    private void uploadFunscriptAndSync(java.io.File file, boolean showSuccess) {
        if (handyManager == null) return;
        Toast.makeText(this, "Đang chuẩn bị funscript...", Toast.LENGTH_SHORT).show();
        handyManager.uploadAndSetupScript(file, new HandyManager.HandyCallback() {
            @Override public void onSuccess(String message) {
                handyPreparedUri = uriString;
                handyPrepareFailures = 0;
                if (showSuccess) {
                    Toast.makeText(PlayerActivity.this,
                        "The Handy đã sẵn sàng", Toast.LENGTH_SHORT).show();
                }
                if (mediaPlayer != null && mediaPlayer.isPlaying()) syncHandyWithPlayback();
            }

            @Override public void onError(String error) {
                handyPreparedUri = null;
                handyCheckedUri = null;
                if (!showSuccess && handyPrepareFailures < 3) {
                    handyPrepareFailures++;
                    handyCheckedUri = null;
                    long retryDelay = 10_000L * handyPrepareFailures;
                    handler.postDelayed(PlayerActivity.this::autoPrepareHandyForCurrentVideo,
                        retryDelay);
                    android.util.Log.w("TheHandy", "Auto script retry "
                        + handyPrepareFailures + ": " + error);
                } else {
                    Toast.makeText(PlayerActivity.this,
                        "Lỗi The Handy: " + error, Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    private void autoPrepareHandyForCurrentVideo() {
        if (handyManager == null || !handyManager.isConnected()
                || uriString == null || uriString.equals(handyCheckedUri)) return;

        File script = findMatchingHandyScript(uriString);
        if (script == null) {
            // Keep checking every health cycle so a newly downloaded script is
            // picked up without reopening the video or pressing another button.
            handyCheckedUri = null;
            return;
        }
        handyCheckedUri = uriString;
        uploadFunscriptAndSync(script, false);
    }

    private File findMatchingHandyScript(String mediaUri) {
        if (mediaUri == null || mediaUri.trim().isEmpty()) return null;
        Uri uri = Uri.parse(mediaUri);
        String scheme = uri.getScheme();
        if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
            return null;
        }

        String baseName = resolveVideoBaseName(mediaUri);
        if (baseName == null || baseName.trim().isEmpty()) return null;

        LinkedHashSet<File> directories = new LinkedHashSet<>();
        File appDownloads = getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS);
        if (appDownloads != null) directories.add(appDownloads);

        if ("content".equalsIgnoreCase(scheme)) {
            File mediaParent = queryMediaParent(uri);
            if (mediaParent != null) directories.add(mediaParent);
        }
        if (scheme == null || "file".equalsIgnoreCase(scheme)) {
            String path = "file".equalsIgnoreCase(scheme) ? uri.getPath() : mediaUri;
            if (path != null) {
                File parent = new File(path).getParentFile();
                if (parent != null) directories.add(parent);
            }
        }

        File downloads = android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_DOWNLOADS);
        File movies = android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_MOVIES);
        File dcim = android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_DCIM);
        File documents = android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_DOCUMENTS);
        directories.add(downloads);
        directories.add(new File(downloads, "videos"));
        directories.add(movies);
        directories.add(dcim);
        directories.add(new File(dcim, "Camera"));
        directories.add(documents);

        String[] extensions = {".funscript", ".csv"};
        for (File directory : directories) {
            if (directory == null) continue;
            for (String extension : extensions) {
                File candidate = new File(directory, baseName + extension);
                if (candidate.isFile() && candidate.canRead()) return candidate;
            }
        }
        return null;
    }

    private String resolveVideoBaseName(String mediaUri) {
        if (mediaUri == null || mediaUri.trim().isEmpty()) return null;
        Uri uri = Uri.parse(mediaUri);
        String displayName = "content".equalsIgnoreCase(uri.getScheme())
            ? queryDisplayName(uri) : null;
        if (displayName == null || displayName.trim().isEmpty()) {
            displayName = uri.getLastPathSegment();
        }
        if (displayName == null || displayName.trim().isEmpty()) return null;
        displayName = Uri.decode(displayName);
        int slash = Math.max(displayName.lastIndexOf('/'), displayName.lastIndexOf('\\'));
        if (slash >= 0) displayName = displayName.substring(slash + 1);
        int dot = displayName.lastIndexOf('.');
        String baseName = dot > 0 ? displayName.substring(0, dot) : displayName;
        return baseName.trim();
    }

    private String queryDisplayName(Uri uri) {
        try (android.database.Cursor cursor = getContentResolver().query(uri,
                new String[]{android.provider.OpenableColumns.DISPLAY_NAME},
                null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) return cursor.getString(0);
        } catch (Exception e) {
            android.util.Log.w("TheHandy", "Cannot read display name", e);
        }
        return null;
    }

    @SuppressWarnings("deprecation")
    private File queryMediaParent(Uri uri) {
        try (android.database.Cursor cursor = getContentResolver().query(uri,
                new String[]{android.provider.MediaStore.MediaColumns.DATA},
                null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String path = cursor.getString(0);
                if (path != null && !path.trim().isEmpty()) {
                    return new File(path).getParentFile();
                }
            }
        } catch (Exception e) {
            android.util.Log.w("TheHandy", "Cannot resolve video directory", e);
        }
        return null;
    }

    // Find and synchronize a matching script without a second confirmation dialog.
    private void autoFindAndSyncFunscript() {
        handyCheckedUri = null;
        File script = findMatchingHandyScript(uriString);
        if (script == null) {
            Toast.makeText(this,
                "Không tìm thấy script cùng tên. Hãy chọn file một lần để app ghi nhớ.",
                Toast.LENGTH_LONG).show();
            return;
        }
        if (handyManager != null && handyManager.isConnected()) {
            handyCheckedUri = uriString;
            uploadFunscriptAndSync(script);
        } else {
            Toast.makeText(this, "Đang kết nối The Handy...", Toast.LENGTH_SHORT).show();
            autoConnectHandy(false);
        }
    }

    private void showAudioTrackDialog() {
        if (mediaPlayer == null) return;
        org.videolan.libvlc.MediaPlayer.TrackDescription[] tracks = mediaPlayer.getAudioTracks();
        if (tracks == null || tracks.length == 0) {
            Toast.makeText(this, "Khong co audio track", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] names = new String[tracks.length];
        for (int i = 0; i < tracks.length; i++) {
            String tname = tracks[i].name;
            if (tname == null || tname.isEmpty() || tname.equals("-1"))
                tname = "Track " + (i + 1);
            names[i] = (tracks[i].id == mediaPlayer.getAudioTrack() ? "▶ " : "   ") + tname;
        }
        int current = mediaPlayer.getAudioTrack();
        int currentIdx = 0;
        for (int i = 0; i < tracks.length; i++) {
            if (tracks[i].id == current) { currentIdx = i; break; }
        }
        final org.videolan.libvlc.MediaPlayer.TrackDescription[] finalTracks = tracks;
        new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Chon audio track")
            .setSingleChoiceItems(names, currentIdx, (d, which) -> {
                mediaPlayer.setAudioTrack(finalTracks[which].id);
                Toast.makeText(this, "Da chon: " + names[which], Toast.LENGTH_SHORT).show();
                d.dismiss();


            })
            .setNegativeButton("Huy", null).show();
    }


    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        if (getIntent().getBooleanExtra(EXTRA_AUTO_CLEANUP_TORRENT, false)
                && !intent.getBooleanExtra(EXTRA_AUTO_CLEANUP_TORRENT, false)) {
            TorrentManager.stopActiveAndCleanup(this);
        }
        setIntent(intent);
        String newUri = intent.getStringExtra(EXTRA_URI);
        if (newUri == null && intent.getData() != null) {
            newUri = intent.getData().toString();
        }
        if (newUri == null || newUri.trim().isEmpty()) return;

        saveHistory();
        uriString = newUri;
        videoTitle = intent.getStringExtra(EXTRA_TITLE);
        if (videoTitle == null || videoTitle.trim().isEmpty()) {
            videoTitle = resolveVideoBaseName(newUri);
        }
        if (videoTitle == null || videoTitle.trim().isEmpty()) videoTitle = "Video";
        tvTitle.setText(videoTitle);
        lastPosition = 0;
        playMedia(uriString);
        autoDetectAndApplyEQ(videoTitle);
    }
}
