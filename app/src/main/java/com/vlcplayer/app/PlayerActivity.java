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
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.io.File;

public class PlayerActivity extends AppCompatActivity {

    public static final String EXTRA_URI   = "extra_uri";
    public static final String EXTRA_TITLE = "extra_title";

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
    private boolean waveletSent = false;
    private int audioSessionId = AudioEffect.ERROR_BAD_VALUE;
    private int scaleMode = 1;
    private int screenW, screenH;
    private float playbackSpeed = 1.0f;

    private String uriString, videoTitle;
    private FunscriptManager funscriptManager = new FunscriptManager();
    private HandyManager handyManager;
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

        if (uriString != null) {
            playMedia(uriString);
            autoDetectAndApplyEQ(videoTitle);
        } else {
            finish();
        }

        handler.post(updateSeekBar);
        handyManager = new HandyManager(this);
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
            if (mediaPlayer != null) mediaPlayer.setTime(mediaPlayer.getTime() + 10000);
        });
        findViewById(R.id.btn_rewind).setOnClickListener(v -> {
            if (mediaPlayer != null) mediaPlayer.setTime(Math.max(0, mediaPlayer.getTime() - 10000));
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
        findViewById(R.id.btn_translate).setOnClickListener(v -> showTranslateDialog());
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
                if (mediaPlayer != null) {
                mediaPlayer.setTime(sb.getProgress());
                if (funscriptManager.isLoaded()) funscriptManager.seekTo(sb.getProgress());
                if (handyManager != null && handyManager.isConnected()) handyManager.seekSync(sb.getProgress());
            }
            }
        });
    }

    private void setupGestures() {
        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onScroll(MotionEvent e1, MotionEvent e2, float dX, float dY) {
                if (isLocked || e1 == null) return false;
                if (e1.getX() < screenW / 2f) adjustBrightness(dY * 0.005f);
                else adjustVolume(dY * 0.005f);
                return true;
            }
            @Override public boolean onDoubleTap(MotionEvent e) {
                if (isLocked) return false;
                if (e.getX() < screenW / 2f) {
                    if (mediaPlayer != null) mediaPlayer.setTime(Math.max(0, mediaPlayer.getTime() - 10000));
                    Toast.makeText(PlayerActivity.this, "-10s", Toast.LENGTH_SHORT).show();
                } else {
                    if (mediaPlayer != null) mediaPlayer.setTime(mediaPlayer.getTime() + 10000);
                    Toast.makeText(PlayerActivity.this, "+10s", Toast.LENGTH_SHORT).show();
                }
                return true;
            }
        });
        videoLayout.setOnTouchListener((v, event) -> {
            gestureDetector.onTouchEvent(event);
            if (event.getAction() == MotionEvent.ACTION_UP && !isLocked) toggleControls();
            return true;
        });
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
        try {
            equalizer = new Equalizer(0, audioSessionId);
            equalizer.setEnabled(true);
        } catch (Exception ignored) {}
        mediaPlayer.setEventListener(event -> {
            switch (event.type) {
                case MediaPlayer.Event.Playing:
                    runOnUiThread(() -> {
                        btnPlayPause.setImageResource(android.R.drawable.ic_media_pause);
                        handler.postDelayed(() -> applyScaleMode(), 200);
                        scheduleHideControls();
                        if (!waveletSent) { broadcastAudioSessionOpen(); waveletSent = true; }
                    });
                    break;
                case MediaPlayer.Event.Paused:
                    runOnUiThread(() -> btnPlayPause.setImageResource(android.R.drawable.ic_media_play));
                    break;
                case MediaPlayer.Event.EndReached:
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
        // Stop hien tai tren UI thread truoc khi load media moi
        if (mediaPlayer != null) {
        pendingUri = uri;
            mediaPlayer.stop();
            mediaPlayer.detachViews();
            mediaPlayer.attachViews(videoLayout, null, false, false);
        }
        dbExecutor.execute(() -> {
            HistoryItem history = AppDatabase.get(this).dao().getHistoryByUri(uri);
            final long resumePos = (history != null && history.lastPosition > 5000) ? history.lastPosition : 0;
            runOnUiThread(() -> {
                try {
                if (!uri.equals(pendingUri)) return;
                    Uri u = Uri.parse(uri);
                    Media media;
                    if ("content".equals(u.getScheme())) {
                        closePfd();
                        currentPfd = getContentResolver().openFileDescriptor(u, "r");
                        if (currentPfd == null) return;
                        media = new Media(libVLC, currentPfd.getFileDescriptor());
                    } else {
                        media = new Media(libVLC, u);
                    }
                    media.setHWDecoderEnabled(true, false);
                    media.addOption(":file-caching=1500");
                    media.addOption(":codec=mediacodec_ndk,mediacodec,omxil,any");
                mediaPlayer.setMedia(media);
                media.release();
                    mediaPlayer.play();
                    if (resumePos > 0) {
                        handler.postDelayed(() -> {
                            mediaPlayer.setTime(resumePos);
                            Toast.makeText(this, "Tiep tuc tu " + formatTime(resumePos), Toast.LENGTH_SHORT).show();
                        }, 1000);
                    }
                } catch (Exception e) {
                    Toast.makeText(this, "Loi: " + e.getMessage(), Toast.LENGTH_LONG).show();
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
                new HistoryItem(uriString, videoTitle != null ? videoTitle : "Video", pos, dur));
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

    private void showTranslateDialog() {
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

    private void adjustBrightness(float delta) {
        WindowManager.LayoutParams p = getWindow().getAttributes();
        if (p.screenBrightness < 0) p.screenBrightness = 0.5f;
        p.screenBrightness = Math.max(0.01f, Math.min(1.0f, p.screenBrightness + delta));
        getWindow().setAttributes(p);
        Toast.makeText(this, "Sang: " + (int)(p.screenBrightness*100) + "%", Toast.LENGTH_SHORT).show();
    }

    private void adjustVolume(float delta) {
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int cur = am.getStreamVolume(AudioManager.STREAM_MUSIC);
        am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.max(0, Math.min(max, (int)(cur + delta * max))), AudioManager.FLAG_SHOW_UI);
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
        String status = handyManager.isConnected() ? "Trang thai: DA KET NOI" : "Trang thai: Chua ket noi";
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
                    handyManager.saveKey(key);
                    Toast.makeText(this, "Da luu key", Toast.LENGTH_SHORT).show();
                }
            })
            .setNegativeButton("Huy", null).show();
    }

    private void connectHandy() {
        if (handyManager.getSavedKey().isEmpty()) { showKeyInput(); return; }
        Toast.makeText(this, "Dang ket noi...", Toast.LENGTH_SHORT).show();
        handyManager.connect(new HandyManager.HandyCallback() {
            @Override public void onSuccess(String m) { runOnUiThread(() -> new AlertDialog.Builder(PlayerActivity.this).setTitle("The Handy").setMessage(m).setPositiveButton("OK", null).show()); }
            @Override public void onError(String e) { runOnUiThread(() -> Toast.makeText(PlayerActivity.this, "Loi: " + e, Toast.LENGTH_LONG).show()); }
        });
    }

    private void showHandyFunscriptDialog() {
        if (!handyManager.isConnected()) { Toast.makeText(this, "Ket noi The Handy truoc!", Toast.LENGTH_SHORT).show(); return; }
        EditText input = new EditText(this);
        input.setHint("URL file .funscript hoac .csv");
        new AlertDialog.Builder(this)
            .setTitle("Load Funscript cho The Handy")
            .setMessage("Nhap URL file funscript (can convert sang CSV)")
            .setView(input)
            .setPositiveButton("Load & Sync", (d, w) -> {
                String url = input.getText().toString().trim();
                if (url.isEmpty()) return;
                Toast.makeText(this, "Dang setup script...", Toast.LENGTH_SHORT).show();
                handyManager.setupScript(url, new HandyManager.HandyCallback() {
                    @Override public void onSuccess(String m) {
                        runOnUiThread(() -> {
                            Toast.makeText(PlayerActivity.this, m, Toast.LENGTH_SHORT).show();
                            syncHandyNow();
                        });
                    }
                    @Override public void onError(String e) { runOnUiThread(() -> Toast.makeText(PlayerActivity.this, "Loi: " + e, Toast.LENGTH_LONG).show()); }
                });
            })
            .setNegativeButton("Huy", null).show();
    }

    private void syncHandyNow() {
        if (!handyManager.isConnected() || mediaPlayer == null) {
            Toast.makeText(this, "Chua ket noi The Handy hoac chua phat video", Toast.LENGTH_SHORT).show();
            return;
        }
        long pos = mediaPlayer.getTime();
        handyManager.play(pos, new HandyManager.HandyCallback() {
            @Override public void onSuccess(String m) { runOnUiThread(() -> Toast.makeText(PlayerActivity.this, "The Handy dong bo tai " + formatTime(pos), Toast.LENGTH_SHORT).show()); }
            @Override public void onError(String e) { runOnUiThread(() -> Toast.makeText(PlayerActivity.this, "Loi dong bo: " + e, Toast.LENGTH_SHORT).show()); }
        });
    }

    private void disconnectHandy() {
        handyManager.stop(new HandyManager.HandyCallback() {
            @Override public void onSuccess(String m) { runOnUiThread(() -> Toast.makeText(PlayerActivity.this, "Da ngat The Handy", Toast.LENGTH_SHORT).show()); }
            @Override public void onError(String e) {}
        });
    }

    private void showFunscriptDialog() {
        String[] opts = {"Load tu URL", "Tu dong tim file .funscript", "Dung funscript"};
        new AlertDialog.Builder(this).setTitle("Funscript")
            .setItems(opts, (d, w) -> {
                if (w == 0) showFunscriptUrlInput();
                else if (w == 1) autoFindAndSyncFunscript();
                else { funscriptManager.stop();
        if (handyManager != null) handyManager.stop(null); Toast.makeText(this, "Da dung funscript", Toast.LENGTH_SHORT).show(); }
            }).show();
    }

    private void showFunscriptUrlInput() {
        EditText input = new EditText(this);
        input.setHint("https://example.com/video.funscript");
        new AlertDialog.Builder(this).setTitle("Funscript URL").setView(input)
            .setPositiveButton("Load", (d, w) -> {
                String url = input.getText().toString().trim();
                if (url.isEmpty()) return;
                Toast.makeText(this, "Dang tai funscript...", Toast.LENGTH_SHORT).show();
                funscriptManager.loadFromUrl(url,
                    () -> { startFunscript(); Toast.makeText(this, "Funscript: " + funscriptManager.getActionCount() + " actions", Toast.LENGTH_SHORT).show(); },
                    () -> Toast.makeText(this, "Loi tai funscript", Toast.LENGTH_SHORT).show());
            }).setNegativeButton("Huy", null).show();
    }

    private void autoLoadFunscript() {
        if (uriString == null) return;
        // Tim file .funscript cung ten voi video
        try {
            android.net.Uri uri = android.net.Uri.parse(uriString);
            String name = uri.getLastPathSegment();
            if (name != null) {
                // Bo extension
                int dot = name.lastIndexOf('.');
                if (dot > 0) name = name.substring(0, dot);
                // Tim trong thu muc Download va Movies
                String[] dirs = {
                    android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS).getAbsolutePath(),
                    android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MOVIES).getAbsolutePath(),
                };
                for (String dir : dirs) {
                    File f = new File(dir, name + ".funscript");
                    if (f.exists()) {
                        if (funscriptManager.loadFromFile(f)) {
                            startFunscript();
                            Toast.makeText(this, "Da load: " + f.getName() + " (" + funscriptManager.getActionCount() + " actions)", Toast.LENGTH_LONG).show();
                            return;
                        }
                    }
                }
                Toast.makeText(this, "Khong tim thay " + name + ".funscript", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "Loi: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void startFunscript() {
        if (!funscriptManager.isLoaded() || mediaPlayer == null) return;
        long pos = mediaPlayer.getTime();
        funscriptManager.start(pos, new FunscriptManager.FunscriptCallback() {
            @Override public void onAction(int position, int speed) {
                // Log action - trong thuc te gui lenh den thiet bi Bluetooth
                android.util.Log.d("Funscript", "pos=" + position + " speed=" + speed + "ms");
                // Hien thi tren UI (tuy chon)
            }
            @Override public void onFinished() {
                runOnUiThread(() -> Toast.makeText(PlayerActivity.this, "Funscript xong", Toast.LENGTH_SHORT).show());
            }
        });
    }
    
    @Override
    protected void onStart() {
        super.onStart();
    }    @Override
    protected void onResume() {
        super.onResume();
        if (mediaPlayer != null && videoLayout != null) {
            videoLayout.post(() -> {
                try {
                    mediaPlayer.attachViews(videoLayout, null, false, false);
                    if (isInBackground) {
                        mediaPlayer.play();
                mediaPlayer.play();
                if (lastPosition > 0) mediaPlayer.setTime(lastPosition);
                        isInBackground = false;
                    }
                } catch (Exception e) {}
            });
        }
    }

    
@Override protected void onStop() {
        super.onStop();
        isInBackground = true;
        saveHistory();
        if (mediaPlayer != null) { lastPosition = mediaPlayer.getTime(); mediaPlayer.pause(); }
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        broadcastAudioSessionClose();
        if (equalizer != null) equalizer.release();
        handler.removeCallbacksAndMessages(null);
        if (mediaPlayer != null) mediaPlayer.release();
        if (libVLC != null) libVLC.release();
        closePfd();
    }
    // Tu dong upload funscript len transfer.sh va lay public URL
    private void uploadFunscriptAndSync(java.io.File file) {
        Toast.makeText(this, "Dang upload funscript...", android.widget.Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                // Doc file thanh bytes truoc
                java.io.FileInputStream fis = new java.io.FileInputStream(file);
                byte[] fileBytes = fis.readAllBytes();
                fis.close();

                java.net.URL url = new java.net.URL("https://transfer.sh/" + file.getName());
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                conn.setRequestMethod("PUT");
                conn.setDoOutput(true);
                conn.setRequestProperty("Max-Days", "1");
                conn.setRequestProperty("Content-Length", String.valueOf(fileBytes.length));
                conn.setConnectTimeout(30000);
                conn.setReadTimeout(60000);
                conn.setFixedLengthStreamingMode(fileBytes.length);

                java.io.OutputStream os = conn.getOutputStream();
                os.write(fileBytes);
                os.flush(); os.close();

                int code = conn.getResponseCode();
                if (code == 200) {
                    byte[] resp = conn.getInputStream().readAllBytes();
                    String publicUrl = new String(resp).trim();
                    runOnUiThread(() -> {
                        Toast.makeText(this, "Upload xong! Dang setup Handy...", android.widget.Toast.LENGTH_SHORT).show();
                        if (handyManager.isConnected()) {
                            handyManager.setupScript(publicUrl, new HandyManager.HandyCallback() {
                                @Override public void onSuccess(String m) {
                                    runOnUiThread(() -> { syncHandyNow(); Toast.makeText(PlayerActivity.this, "The Handy san sang!", android.widget.Toast.LENGTH_SHORT).show(); });
                                }
                                @Override public void onError(String e) {
                                    runOnUiThread(() -> Toast.makeText(PlayerActivity.this, "Loi setup: " + e, android.widget.Toast.LENGTH_LONG).show());
                                }
                            });
                        } else {
                            // Luu URL de dung sau khi ket noi
                            getSharedPreferences("handy_prefs", MODE_PRIVATE).edit()
                                .putString("last_funscript_url", publicUrl).apply();
                            Toast.makeText(this, "Da luu URL. Ket noi Handy roi bam Dong bo.", android.widget.Toast.LENGTH_LONG).show();
                        }
                    });
                } else {
                    runOnUiThread(() -> Toast.makeText(this, "Upload that bai: " + code, android.widget.Toast.LENGTH_SHORT).show());
                }
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Loi upload: " + e.getMessage(), android.widget.Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    // Tim va tu dong load funscript cung ten voi video
    private void autoFindAndSyncFunscript() {
        if (uriString == null || uriString.isEmpty()) {
            android.widget.Toast.makeText(this, "Khong co video dang phat", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        // Lay path thuc tu URI
        String videoPath = uriString;
        if (uriString.startsWith("content://")) {
            android.database.Cursor cursor = getContentResolver().query(
                android.net.Uri.parse(uriString), new String[]{"_data"}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                videoPath = cursor.getString(0);
                cursor.close();
            } else {
                android.widget.Toast.makeText(this, "Khong lay duoc duong dan file", android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
        }
        if (videoPath == null || videoPath.startsWith("http")) {
            android.widget.Toast.makeText(this, "Chi ho tro file local", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        String base = videoPath.replaceAll("\\.[^.]+$", "");
        String[] exts = {".funscript", ".csv"};
        for (String ext : exts) {
            java.io.File f = new java.io.File(base + ext);
            if (f.exists()) {
                new AlertDialog.Builder(this)
                    .setTitle("Tim thay Funscript")
                    .setMessage("Tim thay: " + f.getName() + "\nTu dong upload va sync voi The Handy?")
                    .setPositiveButton("Upload & Sync", (d, w) -> uploadFunscriptAndSync(f))
                    .setNegativeButton("Chi load local", (d, w) -> {
                        if (funscriptManager.loadFromFile(f)) {
                            startFunscript();
                            Toast.makeText(this, "Loaded " + funscriptManager.getActionCount() + " actions", android.widget.Toast.LENGTH_SHORT).show();
                        }
                    })
                    .setNegativeButton("Huy", null).show();
                return;
            }
        }
        Toast.makeText(this, "Khong tim thay file funscript cung ten video", android.widget.Toast.LENGTH_SHORT).show();
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

}