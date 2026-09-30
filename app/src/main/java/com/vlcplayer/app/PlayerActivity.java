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
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
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
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.switchmaterial.SwitchMaterial;

import com.vlcplayer.app.db.AppDatabase;
import com.vlcplayer.app.db.BookmarkItem;
import com.vlcplayer.app.db.HistoryItem;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.util.VLCVideoLayout;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class PlayerActivity extends AppCompatActivity {

    public static final String EXTRA_URI   = "extra_uri";
    public static final String EXTRA_TITLE = "extra_title";
    public static final String EXTRA_USE_PLAYLIST = "extra_use_playlist";
    public static final String EXTRA_AUTO_CLEANUP_TORRENT = "auto_cleanup_torrent";
    public static final String EXTRA_TORRENT_SESSION_ID = "torrent_session_id";
    public static final String EXTRA_TORRENT_FILE_TOKEN = "torrent_file_token";
    private boolean torrentPlaybackFinished;

    private LibVLC libVLC;
    private MediaPlayer mediaPlayer;
    private VLCVideoLayout videoLayout;
    private View videoTouchLayer;
    private boolean isInBackground = false;
    private long lastPosition = 0;
    private volatile String pendingUri = null;
    private ParcelFileDescriptor currentPfd;
    private boolean userPaused;
    private boolean resumeAfterBackground;
    private boolean resumeAfterFocusLoss;
    private boolean audioFocusRequested;
    private boolean audioFocusHeld;
    private boolean activityResumed;
    private final ExecutorService videoAccessExecutor = Executors.newSingleThreadExecutor();
    private Future<?> videoAccessFuture;
    private CancellationSignal videoAccessCancellation;
    private int videoAccessGeneration;
    private boolean verifyContentAccessOnResume;
    private boolean checkingContentAccess;
    private boolean contentAccessFailure;

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
    private static final String SUBTITLE_SOURCE_PREF = "subtitle_translation_source";
    private static final int MAX_SUBTITLE_BYTES = 4 * 1024 * 1024;
    private final ExecutorService subtitleExecutor = Executors.newSingleThreadExecutor();
    private volatile int subtitleOperationGeneration;
    private int subtitleMediaGeneration;
    private int subtitlePickerMediaGeneration;
    private volatile HttpURLConnection subtitleDownloadConnection;
    private volatile InputStream subtitleReadStream;
    private Future<?> subtitleIoFuture;
    private TranslationManager.SrtTranslationTask subtitleTranslationTask;
    private ProgressDialog subtitleProgressDialog;
    private String subtitlePickerVideoUri;
    private TranslationManager translationManager;
    private final ArrayList<File> attachedSubtitleFiles = new ArrayList<>();
    private File selectedExternalSubtitleFile;
    private boolean subtitleOffSelected;
    private boolean discardAttachedSubtitlesOnNextMedia;
    private int subtitleRestorePendingGeneration = -1;
    private HandyManager handyManager;
    private final ExecutorService dbExecutor = Executors.newSingleThreadExecutor();
    private GestureDetector gestureDetector;
    private String handyCheckedUri;
    private boolean handyConnectInProgress;
    private boolean handyDisconnectInProgress;
    private String pendingHandyScriptUrl;
    private String pendingHandyScriptVideoUri;
    private File pendingPickedScript;
    private String funscriptPickerVideoUri;
    private String activeScriptName;
    private String activeScriptSource;
    private boolean funscriptLoadInProgress;
    private String funscriptMessage;
    private Runnable funscriptRetryAction;
    private int funscriptOperationGeneration;
    private BottomSheetDialog funscriptDialog;
    private TextView funscriptConnectionView, funscriptFileView, funscriptSyncView,
        funscriptMessageView;
    private ProgressBar funscriptProgressView;
    private SwitchMaterial funscriptSyncSwitch;
    private boolean updatingFunscriptSwitch;
    private boolean videoBuffering;
    private final PlaybackProgressGuard handyProgressGuard = new PlaybackProgressGuard();
    private static final int MAX_PLAYBACK_AUTO_RETRIES = 1;
    private static final long PLAYBACK_RETRY_DELAY_MS = 2_000L;
    private static final long PLAYBACK_STABLE_RESET_MS = 15_000L;
    private int playbackAutoRetryCount;
    private boolean handlingPlaybackError;
    private Runnable playbackRetryRunnable;
    private Runnable playbackStableResetRunnable;
    private String pendingRecoveryUri;
    private long pendingRecoveryPositionMs = -1L;
    private long lastKnownPlaybackPositionMs;
    private long lastKnownMediaDurationMs = -1L;
    private long playbackClockBasePositionMs;
    private long playbackClockStartedElapsedMs = -1L;

    private final ActivityResultLauncher<String[]> funscriptPicker =
        registerForActivityResult(new ActivityResultContracts.OpenDocument(),
            this::onFunscriptPicked);

    private final ActivityResultLauncher<String[]> subtitlePicker =
        registerForActivityResult(new ActivityResultContracts.OpenDocument(),
            this::onSubtitlePicked);

    private final Runnable handyCorrectionSync = () -> {
        if (handyManager == null || mediaPlayer == null
                || !handyManager.isScriptReady() || !isHandyPlaybackAllowed()
                || !handyManager.isSynchronizationEnabled()) return;
        if (Math.abs(playbackSpeed - 1.0f) > 0.01f) return;
        handyManager.play(getRawPlaybackPosition(), null);
    };

    private final Runnable handyHealthCheck = new Runnable() {
        @Override public void run() {
            if (handyManager != null && mediaPlayer != null) {
                handyManager.healthCheck(
                    getRawPlaybackPosition(), isHandyPlaybackAllowed());
                if (!isInBackground && handyManager.isConnected()) prepareScriptAfterConnection();
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
            if (mediaPlayer != null) {
                long pos = mediaPlayer.getTime();
                long len = mediaPlayer.getLength();
                if (pos >= 0) lastKnownPlaybackPositionMs = pos;
                if (len > 0) lastKnownMediaDurationMs = len;
                long effectivePosition = getBestKnownPlaybackPosition();
                if (!userSeeking && len > 0) {
                    seekBar.setMax((int) len);
                    seekBar.setProgress((int) effectivePosition);
                    tvCurrent.setText(formatTime(effectivePosition));
                    tvTotal.setText(formatTime(len));
                }
                monitorHandyPlaybackProgress(pos);
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
        pruneCachedSubtitleFiles();

        DisplayMetrics dm = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(dm);
        screenW = dm.widthPixels;
        screenH = dm.heightPixels;

        uriString  = getIntent().getStringExtra(EXTRA_URI);
        if (uriString == null && getIntent().getData() != null) {
            uriString = getIntent().getData().toString();
        }
        videoTitle = getIntent().getStringExtra(EXTRA_TITLE);
        if (!getIntent().getBooleanExtra(EXTRA_USE_PLAYLIST, false)) {
            PlaylistManager.get().clear();
        }

        videoLayout     = findViewById(R.id.vlc_video_layout);
        videoTouchLayer = findViewById(R.id.video_touch_layer);
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
        tvSpeed.setContentDescription(getString(R.string.player_speed) + ": 1.0x");

        updatePlaylistButtons();
        setupButtons();
        setupGestures();
        setupVLC();
        translationManager = new TranslationManager(this);
        handyManager = new HandyManager(this);

        if (uriString != null) {
            playMedia(uriString);
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
            if (mediaPlayer != null) seekPlaybackTo(getBestKnownPlaybackPosition() + 10000);
        });
        findViewById(R.id.btn_rewind).setOnClickListener(v -> {
            if (mediaPlayer != null) seekPlaybackTo(getBestKnownPlaybackPosition() - 10000);
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
        tvSpeed.setOnClickListener(v -> showSpeedDialog());
        findViewById(R.id.btn_more).setOnClickListener(v -> showMoreDialog());
        findViewById(R.id.btn_unlock).setOnClickListener(v -> toggleLock());
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (fromUser) tvCurrent.setText(formatTime(p));
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {
                userSeeking = true;
                blockHandyPlayback();
            }
            @Override public void onStopTrackingTouch(SeekBar sb) {
                userSeeking = false;
                seekPlaybackTo(sb.getProgress());
            }
        });
    }

    private void showMoreDialog() {
        String[] actions = {
            getString(R.string.player_filter),
            getString(nightMode ? R.string.player_night_off : R.string.player_night_on),
            getString(R.string.player_bookmark),
            getString(R.string.player_pip),
            getString(R.string.player_aspect),
            getString(R.string.player_lock),
            getString(R.string.player_audio_subtitles),
            getString(R.string.player_funscript)
        };
        new AlertDialog.Builder(this)
            .setTitle(R.string.player_more)
            .setItems(actions, (dialog, which) -> {
                switch (which) {
                    case 0: showFilterDialog(); break;
                    case 1: toggleNightMode(); break;
                    case 2: addBookmark(); break;
                    case 3: enterPiP(); break;
                    case 4: cycleAspectRatio(); break;
                    case 5: toggleLock(); break;
                    case 6: showAudioSubtitlesDialog(); break;
                    case 7: showFunscriptDialog(); break;
                }
            }).show();
    }

    private void showAudioSubtitlesDialog() {
        String[] actions = {
            getString(R.string.player_audio_track),
            getString(R.string.player_subtitle_track),
            getString(R.string.player_local_srt),
            getString(R.string.player_translate)
        };
        new AlertDialog.Builder(this)
            .setTitle(R.string.player_audio_subtitles)
            .setItems(actions, (dialog, which) -> {
                switch (which) {
                    case 0: showAudioTrackDialog(); break;
                    case 1: showSubtitleTrackDialog(); break;
                    case 2: pickLocalSrt(); break;
                    case 3: showAiSubtitleDialog(); break;
                }
            }).show();
    }

    private void setupGestures() {
        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                if (isLocked) return false;
                android.util.Log.d("PlayerGesture", "single tap confirmed");
                toggleControls();
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
                    if (mediaPlayer != null) seekPlaybackTo(getBestKnownPlaybackPosition() - 10000);
                    Toast.makeText(PlayerActivity.this, "-10s", Toast.LENGTH_SHORT).show();
                } else {
                    if (mediaPlayer != null) seekPlaybackTo(getBestKnownPlaybackPosition() + 10000);
                    Toast.makeText(PlayerActivity.this, "+10s", Toast.LENGTH_SHORT).show();
                }
                return true;
            }
        });
        videoLayout.setOnTouchListener(this::handleVideoTouch);
        videoTouchLayer.setOnTouchListener(this::handleVideoTouch);
    }

    private boolean handleVideoTouch(View view, MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN
                || event.getAction() == MotionEvent.ACTION_UP) {
            android.util.Log.d("PlayerGesture", "touch action=" + event.getAction());
        }
        return gestureDetector != null && gestureDetector.onTouchEvent(event);
    }

    private void seekPlaybackTo(long requestedPositionMs) {
        if (mediaPlayer == null) return;
        long duration = mediaPlayer.getLength();
        long position = Math.max(0, requestedPositionMs);
        if (duration > 0) position = Math.min(position, duration);
        mediaPlayer.setTime(position);
        lastKnownPlaybackPositionMs = position;
        seekBar.setProgress((int) position);
        tvCurrent.setText(formatTime(position));
        if (mediaPlayer.isPlaying()) startPlaybackClockEstimate(position);
        else playbackClockStartedElapsedMs = -1L;
        resetHandyPlaybackWatchdog(position);
        handler.removeCallbacks(handyCorrectionSync);
        if (handyManager != null && handyManager.isScriptReady()) {
            if (mediaPlayer.isPlaying()) syncHandyWithPlayback();
            else handyManager.stopPlayback(null);
        }
    }

    private void monitorHandyPlaybackProgress(long videoTimeMs) {
        if (mediaPlayer == null) return;
        boolean wasAllowed = handyProgressGuard.isAllowed();
        boolean allowed = handyProgressGuard.update(videoTimeMs,
            android.os.SystemClock.elapsedRealtime(),
            !isInBackground && !userPaused && audioFocusHeld && !userSeeking
                && mediaPlayer.isPlaying(), videoBuffering);
        allowed = allowed && !handyDisconnectInProgress
            && (handyManager == null || !handyManager.isStopRequired());
        if (handyManager != null) {
            handyManager.setVideoPlaybackAllowed(allowed);
            if (!allowed) handler.removeCallbacks(handyCorrectionSync);
            else if (!wasAllowed && handyManager.isScriptReady()) syncHandyWithPlayback();
        }
        if (wasAllowed != allowed || funscriptDialog != null) renderFunscriptState();
    }

    private void resetHandyPlaybackWatchdog(long videoTimeMs) {
        handyProgressGuard.reset(videoTimeMs, android.os.SystemClock.elapsedRealtime());
        if (handyManager != null) handyManager.setVideoPlaybackAllowed(false);
    }

    private long getRawPlaybackPosition() {
        return mediaPlayer == null ? -1L : mediaPlayer.getTime();
    }

    private boolean isHandyPlaybackAllowed() {
        return mediaPlayer != null && !isInBackground && !userPaused && audioFocusHeld
            && !videoBuffering && !userSeeking
            && !handyDisconnectInProgress && handyManager != null && !handyManager.isStopRequired()
            && mediaPlayer.isPlaying() && handyProgressGuard.isAllowed();
    }

    private void blockHandyPlayback() {
        handyProgressGuard.update(getRawPlaybackPosition(),
            android.os.SystemClock.elapsedRealtime(), false, videoBuffering);
        handler.removeCallbacks(handyCorrectionSync);
        if (handyManager != null) handyManager.setVideoPlaybackAllowed(false);
        renderFunscriptState();
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
        // RootlessJamesDSP manages audio effects on this session.
        mediaPlayer.setEventListener(event -> {
            switch (event.type) {
                case MediaPlayer.Event.Playing:
                    runOnUiThread(() -> {
                        if (BuildConfig.DEBUG) {
                            android.util.Log.i("VLCRecovery", "MediaPlayer entered Playing state");
                        }
                        handlingPlaybackError = false;
                        cancelPlaybackRetry();
                        restorePendingRecoveryPosition();
                        restoreAttachedSubtitlesIfNeeded();
                        startPlaybackClockEstimate(getBestKnownPlaybackPosition());
                        btnPlayPause.setImageResource(android.R.drawable.ic_media_pause);
                        handler.postDelayed(() -> applyScaleMode(), 200);
                        scheduleHideControls();
                        // Delay de VLC khoi dong audiotrack truoc
                        handler.postDelayed(() -> broadcastAudioSessionOpen(), 300);
                        handler.postDelayed(() -> broadcastAudioSessionOpen(), 1000);
                        monitorHandyPlaybackProgress(getRawPlaybackPosition());
                        syncHandyWithPlayback();
                        schedulePlaybackStableReset();
                    });
                    break;
                case MediaPlayer.Event.Paused:
                    runOnUiThread(() -> {
                        freezePlaybackClockEstimate();
                        cancelPlaybackStableReset();
                        blockHandyPlayback();
                        btnPlayPause.setImageResource(android.R.drawable.ic_media_play);
                    });
                    break;
                case MediaPlayer.Event.Buffering:
                    final float buffered = event.getBuffering();
                    runOnUiThread(() -> {
                        videoBuffering = buffered < 100f;
                        if (videoBuffering) blockHandyPlayback();
                        else monitorHandyPlaybackProgress(getRawPlaybackPosition());
                    });
                    break;
                case MediaPlayer.Event.EndReached:
                    runOnUiThread(this::handlePlaybackEndReached);
                    break;
                case MediaPlayer.Event.EncounteredError:
                    runOnUiThread(this::handlePlaybackError);
                    break;
            }
        });
        mediaPlayer.attachViews(videoLayout, null, false, false);
    }

    private void handlePlaybackEndReached() {
        if (contentAccessFailure || isFinishing() || isDestroyed()) return;
        blockHandyPlayback();
        long position = getBestKnownPlaybackPosition();
        lastKnownPlaybackPositionMs = position;
        playbackClockBasePositionMs = position;
        playbackClockStartedElapsedMs = -1L;
        long duration = getBestKnownMediaDuration();
        long remaining = duration > 0 ? Math.max(0L, duration - position) : -1L;
        long endTolerance = duration > 0
            ? Math.max(10_000L, Math.min(30_000L, duration / 20L))
            : 0L;
        boolean prematureNetworkEnd = isRetryableNetworkStream(uriString)
            && duration > 0 && remaining > endTolerance;

        if (BuildConfig.DEBUG) {
            android.util.Log.i("VLCRecovery",
                "MediaPlayer reached end: position=" + position
                    + "ms duration=" + duration + "ms remaining=" + remaining + "ms");
        }
        if (prematureNetworkEnd) {
            android.util.Log.w("VLCRecovery",
                "Network stream ended prematurely; treating it as a recoverable error");
            handlePlaybackError();
            return;
        }

        cancelPlaybackStableReset();
        if (handyManager != null) handyManager.stopPlayback(null);
        if (duration > 0) {
            lastKnownPlaybackPositionMs = duration;
            playbackClockBasePositionMs = duration;
        }
        saveHistory();
        PlaylistManager pm = PlaylistManager.get();
        if (pm.getRepeatMode() == PlaylistManager.RepeatMode.ONE) {
            playMedia(uriString);
        } else if (pm.hasNext()) {
            playNext();
        } else {
            finish();
        }
    }

    private void handlePlaybackError() {
        if (handlingPlaybackError || isFinishing() || isDestroyed() || isInBackground) return;

        handlingPlaybackError = true;
        blockHandyPlayback();
        cancelPlaybackStableReset();
        handler.removeCallbacks(handyCorrectionSync);
        if (handyManager != null) handyManager.stopPlayback(null);
        btnPlayPause.setImageResource(android.R.drawable.ic_media_play);

        final String failedUri = uriString;
        long failedPosition = getBestKnownPlaybackPosition();
        lastKnownPlaybackPositionMs = failedPosition;
        playbackClockBasePositionMs = failedPosition;
        playbackClockStartedElapsedMs = -1L;

        if (isRetryableNetworkStream(failedUri)
                && playbackAutoRetryCount < MAX_PLAYBACK_AUTO_RETRIES) {
            playbackAutoRetryCount++;
            android.util.Log.w("VLCRecovery",
                "Network playback failed; scheduling automatic retry "
                    + playbackAutoRetryCount + "/" + MAX_PLAYBACK_AUTO_RETRIES);
            final long recoveryPosition = failedPosition;
            Toast.makeText(this,
                "Stream bị gián đoạn, app đang tự kết nối lại...",
                Toast.LENGTH_SHORT).show();

            cancelPlaybackRetry();
            playbackRetryRunnable = () -> {
                playbackRetryRunnable = null;
                if (isFinishing() || isInBackground || failedUri == null
                        || !failedUri.equals(uriString)) {
                    handlingPlaybackError = false;
                    return;
                }
                pendingRecoveryUri = failedUri;
                pendingRecoveryPositionMs = recoveryPosition;
                handlingPlaybackError = false;
                playMedia(failedUri, false);
            };
            handler.postDelayed(playbackRetryRunnable, PLAYBACK_RETRY_DELAY_MS);
            return;
        }

        pendingRecoveryUri = null;
        pendingRecoveryPositionMs = -1L;
        android.util.Log.e("VLCRecovery",
            isRetryableNetworkStream(failedUri)
                ? "Network playback recovery exhausted; stopping safely"
                : "Non-network playback failed; stopping safely");
        try { if (mediaPlayer != null) mediaPlayer.stop(); }
        catch (Exception ignored) {}
        Toast.makeText(this,
            isRetryableNetworkStream(failedUri)
                ? "Không thể khôi phục stream. App đã dừng an toàn."
                : "VLC không thể phát video này. App đã dừng an toàn.",
            Toast.LENGTH_LONG).show();
        finish();
    }

    private boolean isRetryableNetworkStream(String value) {
        if (value == null || value.trim().isEmpty()) return false;
        String scheme = Uri.parse(value).getScheme();
        return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
    }

    private long getBestKnownPlaybackPosition() {
        long position = Math.max(0L, lastKnownPlaybackPositionMs);
        if (mediaPlayer != null) {
            try { position = Math.max(position, mediaPlayer.getTime()); }
            catch (Exception ignored) {}
        }
        if (seekBar != null) position = Math.max(position, seekBar.getProgress());
        if (playbackClockStartedElapsedMs >= 0
                && position <= playbackClockBasePositionMs + 1_000L) {
            long elapsed = Math.max(0L,
                android.os.SystemClock.elapsedRealtime() - playbackClockStartedElapsedMs);
            long estimate = playbackClockBasePositionMs
                + Math.round(elapsed * Math.max(0.1f, playbackSpeed));
            long duration = getBestKnownMediaDuration();
            if (duration > 0) estimate = Math.min(estimate, duration);
            position = Math.max(position, estimate);
        }
        return position;
    }

    private void startPlaybackClockEstimate(long position) {
        playbackClockBasePositionMs = Math.max(0L, position);
        playbackClockStartedElapsedMs = android.os.SystemClock.elapsedRealtime();
    }

    private void freezePlaybackClockEstimate() {
        long position = getBestKnownPlaybackPosition();
        lastKnownPlaybackPositionMs = position;
        playbackClockBasePositionMs = position;
        playbackClockStartedElapsedMs = -1L;
    }

    private long getBestKnownMediaDuration() {
        long duration = Math.max(0L, lastKnownMediaDurationMs);
        if (mediaPlayer != null) {
            try { duration = Math.max(duration, mediaPlayer.getLength()); }
            catch (Exception ignored) {}
        }
        if (seekBar != null) duration = Math.max(duration, seekBar.getMax());
        return duration;
    }

    private void restorePendingRecoveryPosition() {
        if (mediaPlayer == null || pendingRecoveryPositionMs < 0
                || pendingRecoveryUri == null || !pendingRecoveryUri.equals(uriString)) return;
        long position = pendingRecoveryPositionMs;
        pendingRecoveryUri = null;
        pendingRecoveryPositionMs = -1L;
        if (position <= 0) return;
        try {
            long duration = mediaPlayer.getLength();
            if (duration > 0) position = Math.min(position, duration);
            mediaPlayer.setTime(position);
            lastKnownPlaybackPositionMs = position;
            resetHandyPlaybackWatchdog(position);
            android.util.Log.i("VLCRecovery", "Stream resumed at " + position + "ms");
        } catch (Exception e) {
            android.util.Log.w("VLCRecovery", "Could not restore stream position", e);
        }
    }

    private void schedulePlaybackStableReset() {
        cancelPlaybackStableReset();
        final String playingUri = uriString;
        playbackStableResetRunnable = () -> {
            playbackStableResetRunnable = null;
            if (playingUri != null && playingUri.equals(uriString)
                    && mediaPlayer != null && mediaPlayer.isPlaying()) {
                playbackAutoRetryCount = 0;
                if (BuildConfig.DEBUG) {
                    android.util.Log.i("VLCRecovery",
                        "Playback remained stable; automatic retry allowance reset");
                }
            }
        };
        handler.postDelayed(playbackStableResetRunnable, PLAYBACK_STABLE_RESET_MS);
    }

    private void cancelPlaybackRetry() {
        if (playbackRetryRunnable != null) {
            handler.removeCallbacks(playbackRetryRunnable);
            playbackRetryRunnable = null;
        }
    }

    private void cancelPlaybackStableReset() {
        if (playbackStableResetRunnable != null) {
            handler.removeCallbacks(playbackStableResetRunnable);
            playbackStableResetRunnable = null;
        }
    }

    private void cancelPlaybackRecoveryCallbacks() {
        cancelPlaybackRetry();
        cancelPlaybackStableReset();
    }

    private void playNext() {
        VideoItem next = PlaylistManager.get().getNext();
        if (next != null) {
            saveHistory();
            uriString  = next.getUri().toString();
            videoTitle = next.getName();
            tvTitle.setText(videoTitle);
            playMedia(uriString);
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
        playMedia(uri, true);
    }

    private void playMedia(String uri, boolean restoreSavedHistory) {
        if (contentAccessFailure || isFinishing() || isDestroyed()) return;
        cancelVideoAccessValidation();
        verifyContentAccessOnResume = false;
        userPaused = false;
        resumeAfterFocusLoss = false;
        boolean mediaChanged = pendingUri == null || !uri.equals(pendingUri);
        boolean discardSubtitles = mediaChanged || discardAttachedSubtitlesOnNextMedia;
        discardAttachedSubtitlesOnNextMedia = false;
        subtitleMediaGeneration++;
        cancelSubtitleOperation();
        subtitleRestorePendingGeneration = -1;
        if (restoreSavedHistory) {
            lastKnownPlaybackPositionMs = 0L;
            lastKnownMediaDurationMs = -1L;
            playbackClockBasePositionMs = 0L;
            playbackClockStartedElapsedMs = -1L;
            if (seekBar != null) {
                seekBar.setProgress(0);
                seekBar.setMax(0);
            }
            if (tvCurrent != null) tvCurrent.setText(formatTime(0));
        }
        if (mediaChanged || restoreSavedHistory) {
            cancelPlaybackRecoveryCallbacks();
            playbackAutoRetryCount = 0;
            handlingPlaybackError = false;
            pendingRecoveryUri = null;
            pendingRecoveryPositionMs = -1L;
        }
        pendingUri = uri;
        videoBuffering = false;
        resetHandyPlaybackWatchdog(0);
        if (handyManager != null && mediaChanged) {
            handler.removeCallbacks(handyCorrectionSync);
            handyManager.resetScript();
            handyCheckedUri = null;
            funscriptOperationGeneration++;
            pendingHandyScriptUrl = null;
            pendingHandyScriptVideoUri = null;
            pendingPickedScript = null;
            activeScriptName = null;
            activeScriptSource = null;
            funscriptLoadInProgress = false;
            funscriptRetryAction = null;
            funscriptMessage = null;
            renderFunscriptState();
        }
        try {
            Uri u = Uri.parse(uri);
            Media media;
            if ("content".equals(u.getScheme())) {
                ParcelFileDescriptor newPfd = getContentResolver().openFileDescriptor(u, "r");
                if (newPfd == null) throw new IOException("Video descriptor unavailable");
                try {
                    media = new Media(libVLC, newPfd.getFileDescriptor());
                } catch (Exception e) {
                    try { newPfd.close(); } catch (IOException ignored) {}
                    throw e;
                }
                ParcelFileDescriptor oldPfd = currentPfd;
                currentPfd = newPfd;
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
            if (discardSubtitles) clearAttachedSubtitleFiles();
            else if (!attachedSubtitleFiles.isEmpty() || subtitleOffSelected)
                subtitleRestorePendingGeneration = subtitleMediaGeneration;
            if (requestAudioFocus()) mediaPlayer.play();
            else if (audioFocusRequested) resumeAfterFocusLoss = true;
            else Toast.makeText(this, "Không thể lấy quyền phát âm thanh", Toast.LENGTH_SHORT).show();
            videoLayout.post(() -> {
                if (!uri.equals(pendingUri)) return;
                try { mediaPlayer.detachViews(); } catch (Exception ignored) {}
                mediaPlayer.attachViews(videoLayout, null, false, false);
            });
        } catch (Exception e) {
            if ("content".equals(Uri.parse(uri).getScheme())) {
                android.util.Log.w("PlayerActivity", "Content video cannot be opened", e);
                finishForUnavailableContent();
                return;
            }
            Toast.makeText(this, "Loi: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
        if (handyManager != null && handyManager.isConnected()) {
            handler.postDelayed(this::autoPrepareHandyForCurrentVideo, 300);
        }
        if (restoreSavedHistory) {
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
    }


    private void saveHistory() {
        if (uriString == null || contentAccessFailure) return;
        final String historyUri = uriString;
        final String historyTitle = videoTitle != null ? videoTitle : "Video";
        final long duration = getBestKnownMediaDuration();
        long currentPosition = getBestKnownPlaybackPosition();
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
        long pos = getBestKnownPlaybackPosition();
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
                        && handyManager.isSynchronizationEnabled()
                        && Math.abs(vals[w] - 1.0f) > 0.01f) {
                    Toast.makeText(this,
                        "Để The Handy đồng bộ chính xác, tốc độ video được giữ ở 1.0x",
                        Toast.LENGTH_LONG).show();
                    long position = getBestKnownPlaybackPosition();
                    playbackSpeed = 1.0f;
                    if (mediaPlayer != null) mediaPlayer.setRate(1.0f);
                    if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                        startPlaybackClockEstimate(position);
                    }
                    tvSpeed.setText("1.0x");
                    tvSpeed.setContentDescription(getString(R.string.player_speed) + ": 1.0x");
                    d.dismiss();
                    return;
                }
                long position = getBestKnownPlaybackPosition();
                playbackSpeed = vals[w];
                if (mediaPlayer != null) mediaPlayer.setRate(playbackSpeed);
                if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                    startPlaybackClockEstimate(position);
                }
                tvSpeed.setText(speeds[w]);
                tvSpeed.setContentDescription(getString(R.string.player_speed) + ": " + speeds[w]);
                d.dismiss();
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

    private void showAiSubtitleDialog() {
        String source = getSubtitleSourceLanguage();
        String target = translationManager.getTargetLanguage();
        String[] actions = {
            getString(R.string.player_ai_source, languageName(source)),
            getString(R.string.player_ai_target, languageName(target)),
            getString(R.string.player_ai_url)
        };
        new AlertDialog.Builder(this).setTitle(R.string.player_translate)
            .setItems(actions, (dialog, which) -> {
                switch (which) {
                    case 0: showSubtitleLanguageDialog(true); break;
                    case 1: showSubtitleLanguageDialog(false); break;
                    case 2: showSrtUrlInput(); break;
                }
            }).show();
    }

    private String getSubtitleSourceLanguage() {
        return getPreferences(MODE_PRIVATE).getString(SUBTITLE_SOURCE_PREF, "en");
    }

    private String languageName(String code) {
        Locale locale = Locale.forLanguageTag(code);
        return locale.getDisplayLanguage(getResources().getConfiguration().locale);
    }

    private void showSubtitleLanguageDialog(boolean source) {
        String[][] languages = TranslationManager.LANGUAGES;
        String[] names = new String[languages.length];
        String selectedCode = source ? getSubtitleSourceLanguage()
            : translationManager.getTargetLanguage();
        int currentIndex = -1;
        for (int i = 0; i < languages.length; i++) {
            names[i] = languageName(languages[i][1]);
            if (languages[i][1].equals(selectedCode)) currentIndex = i;
        }
        new AlertDialog.Builder(this)
            .setTitle(source ? R.string.player_ai_source_title : R.string.player_ai_target_title)
            .setSingleChoiceItems(names, currentIndex, (dialog, which) -> {
                if (source) {
                    getPreferences(MODE_PRIVATE).edit()
                        .putString(SUBTITLE_SOURCE_PREF, languages[which][1]).apply();
                } else {
                    translationManager.setTargetLanguage(languages[which][1]);
                }
                dialog.dismiss();
                showAiSubtitleDialog();
            })
            .setNegativeButton(R.string.player_ai_cancel, null).show();
    }

    private void showSrtUrlInput() {
        String source = getSubtitleSourceLanguage();
        String target = translationManager.getTargetLanguage();
        if (source.equals(target)) {
            Toast.makeText(this, R.string.player_ai_same_language, Toast.LENGTH_LONG).show();
            return;
        }
        EditText input = new EditText(this);
        input.setHint(R.string.player_ai_url_hint);
        input.setSingleLine(true);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
            | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        new AlertDialog.Builder(this)
            .setTitle(R.string.player_ai_url)
            .setMessage(getString(R.string.player_ai_pair,
                languageName(source), languageName(target)))
            .setView(input)
            .setPositiveButton(R.string.player_ai_translate, (dialog, which) ->
                startSrtDownloadAndTranslate(input.getText().toString().trim(), source))
            .setNegativeButton(R.string.player_ai_cancel, null).show();
    }

    private void startSrtDownloadAndTranslate(String url, String source) {
        Uri parsed = Uri.parse(url);
        String scheme = parsed.getScheme();
        if (scheme == null || (!"http".equalsIgnoreCase(scheme)
                && !"https".equalsIgnoreCase(scheme))) {
            Toast.makeText(this, R.string.player_ai_url_invalid, Toast.LENGTH_LONG).show();
            return;
        }
        if (source.equals(translationManager.getTargetLanguage())) {
            Toast.makeText(this, R.string.player_ai_same_language, Toast.LENGTH_LONG).show();
            return;
        }
        cancelSubtitleOperation();
        final int generation = ++subtitleOperationGeneration;
        final String videoUri = uriString;
        ProgressDialog progress = new ProgressDialog(this);
        progress.setMessage(getString(R.string.player_ai_downloading));
        progress.setCancelable(true);
        progress.setCanceledOnTouchOutside(false);
        progress.setButton(android.content.DialogInterface.BUTTON_NEGATIVE,
            getString(R.string.player_ai_cancel), (dialog, which) -> cancelSubtitleOperation());
        progress.setOnCancelListener(dialog -> cancelSubtitleOperation());
        subtitleProgressDialog = progress;
        progress.show();

        subtitleIoFuture = subtitleExecutor.submit(() -> {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(url).openConnection();
                subtitleDownloadConnection = connection;
                if (generation != subtitleOperationGeneration
                        || Thread.currentThread().isInterrupted()) return;
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(15000);
                connection.setRequestProperty("User-Agent", "VLCPlayer");
                connection.setInstanceFollowRedirects(true);
                int status = connection.getResponseCode();
                if (status < 200 || status >= 300) {
                    throw new IOException("HTTP " + status);
                }
                byte[] bytes;
                try (InputStream input = connection.getInputStream()) {
                    bytes = readSubtitleBytes(input);
                }
                validateSrt(bytes);
                if (generation != subtitleOperationGeneration
                        || Thread.currentThread().isInterrupted()) return;
                String srt = new String(bytes, StandardCharsets.UTF_8);
                runOnUiThread(() -> {
                    if (!isCurrentSubtitleOperation(generation, videoUri)) return;
                    progress.setMessage(getString(R.string.player_ai_translating));
                    subtitleTranslationTask = translationManager.translateSrt(srt, source,
                        message -> runOnUiThread(() -> {
                            if (!isCurrentSubtitleOperation(generation, videoUri)) return;
                            java.util.regex.Matcher percentage = java.util.regex.Pattern
                                .compile("(\\d{1,3})%").matcher(message);
                            if (percentage.find()) {
                                progress.setMessage(getString(R.string.player_ai_progress,
                                    Integer.parseInt(percentage.group(1))));
                            } else progress.setMessage(getString(R.string.player_ai_translating));
                        }), new TranslationManager.TranslateCallback() {
                            @Override public void onSuccess(String translated) {
                                runOnUiThread(() -> saveTranslatedSubtitle(
                                    translated, generation, videoUri));
                            }
                            @Override public void onError(String error) {
                                runOnUiThread(() -> {
                                    if (!isCurrentSubtitleOperation(generation, videoUri)) return;
                                    finishSubtitleOperation();
                                    Toast.makeText(PlayerActivity.this,
                                        getString(R.string.player_ai_translation_failed, error),
                                        Toast.LENGTH_LONG).show();
                                });
                            }
                        });
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (!isCurrentSubtitleOperation(generation, videoUri)) return;
                    finishSubtitleOperation();
                    Toast.makeText(this,
                        getString(R.string.player_ai_download_failed, e.getMessage()),
                        Toast.LENGTH_LONG).show();
                });
            } finally {
                if (connection != null) connection.disconnect();
                if (subtitleDownloadConnection == connection)
                    subtitleDownloadConnection = null;
            }
        });
    }

    private void saveTranslatedSubtitle(String translated, int generation, String videoUri) {
        if (!isCurrentSubtitleOperation(generation, videoUri)) return;
        subtitleIoFuture = subtitleExecutor.submit(() -> {
            File file = null;
            try {
                file = createSubtitleCacheFile();
                byte[] bytes = translated.getBytes(StandardCharsets.UTF_8);
                if (bytes.length > MAX_SUBTITLE_BYTES)
                    throw new IOException(getString(R.string.player_subtitle_too_large));
                try (FileOutputStream output = new FileOutputStream(file)) {
                    output.write(bytes);
                }
                final File result = file;
                runOnUiThread(() -> {
                    if (!isCurrentSubtitleOperation(generation, videoUri)) {
                        result.delete();
                        return;
                    }
                    boolean attached = attachSubtitleFile(result);
                    finishSubtitleOperation();
                    if (attached) Toast.makeText(this,
                        R.string.player_ai_translation_done, Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                if (file != null) file.delete();
                runOnUiThread(() -> {
                    if (!isCurrentSubtitleOperation(generation, videoUri)) return;
                    finishSubtitleOperation();
                    Toast.makeText(this,
                        getString(R.string.player_subtitle_load_failed, e.getMessage()),
                        Toast.LENGTH_LONG).show();
                });
            }
        });
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
            barBrightness = (android.widget.ProgressBar) overlayBrightness.getChildAt(1);
        }
        if (overlayVolume == null) {
            overlayVolume = makeOverlay(false);
            tvVolumeVal = (android.widget.TextView) overlayVolume.getTag();
            barVolume = (android.widget.ProgressBar) overlayVolume.getChildAt(1);
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
        if (checkingContentAccess || contentAccessFailure) return;
        if (mediaPlayer == null) return;
        if (mediaPlayer.isPlaying()) {
            userPaused = true;
            resumeAfterBackground = false;
            resumeAfterFocusLoss = false;
            blockHandyPlayback();
            mediaPlayer.pause();
            abandonAudioFocus();
        } else {
            userPaused = false;
            resumeAfterFocusLoss = false;
            if (requestAudioFocus()) mediaPlayer.play();
            else if (audioFocusRequested) resumeAfterFocusLoss = true;
        }
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
        focusChange -> runOnUiThread(() -> {
            if (mediaPlayer == null || isDestroyed()) return;
            switch (focusChange) {
                case AudioManager.AUDIOFOCUS_LOSS:
                    audioFocusHeld = false;
                    audioFocusRequested = false;
                    resumeAfterFocusLoss = false;
                    blockHandyPlayback();
                    if (mediaPlayer.isPlaying()) mediaPlayer.pause();
                    break;
                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                    audioFocusHeld = false;
                    blockHandyPlayback();
                    if (mediaPlayer.isPlaying()) {
                        resumeAfterFocusLoss = !userPaused;
                        mediaPlayer.pause();
                    }
                    break;
                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                    break;
                case AudioManager.AUDIOFOCUS_GAIN:
                    if (isInBackground || !audioFocusRequested) return;
                    audioFocusHeld = true;
                    if (resumeAfterFocusLoss && !userPaused) {
                        resumeAfterFocusLoss = false;
                        mediaPlayer.play();
                    }
                    handler.postDelayed(this::broadcastAudioSessionOpen, 200);
                    break;
            }
        });

    private boolean requestAudioFocus() {
        if (audioFocusHeld) return true;
        if (audioFocusRequested) return false;
        android.media.AudioManager am =
            (android.media.AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return false;
        int result;
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
            result = am.requestAudioFocus(audioFocusRequest);
        } else {
            result = am.requestAudioFocus(focusListener,
                android.media.AudioManager.STREAM_MUSIC,
                android.media.AudioManager.AUDIOFOCUS_GAIN);
        }
        audioFocusHeld = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        audioFocusRequested = audioFocusHeld;
        return audioFocusHeld;
    }

    private void abandonAudioFocus() {
        if (!audioFocusRequested) return;
        audioFocusRequested = false;
        audioFocusHeld = false;
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



    private void showHandyStatus() {
        if (!handyManager.isConnected()) {
            setFunscriptMessage(getString(R.string.funscript_disconnected), null);
            return;
        }
        handyManager.getStatus(new HandyManager.HandyCallback() {
            @Override public void onSuccess(String message) {
                if (isFinishing() || isDestroyed()) return;
                new AlertDialog.Builder(PlayerActivity.this)
                    .setTitle(R.string.funscript_device_status).setMessage(message)
                    .setPositiveButton(android.R.string.ok, null).show();
                renderFunscriptState();
            }
            @Override public void onError(String error) {
                setFunscriptMessage(error, () -> showHandyStatus());
            }
        });
    }

    private void showKeyInput() {
        if (isFinishing() || isDestroyed()) return;
        EditText input = new EditText(this);
        input.setHint("xxxx-xxxx-xxxx-xxxx");
        input.setText(handyManager.getSavedKey());
        input.setSingleLine(true);
        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle(R.string.funscript_key)
            .setMessage(R.string.funscript_key_help)
            .setView(input)
            .setPositiveButton(R.string.funscript_save_connect, null)
            .setNegativeButton(R.string.cancel, null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            .setOnClickListener(v -> {
                String key = input.getText().toString().trim();
                if (key.isEmpty()) {
                    input.setError(getString(R.string.funscript_key_required));
                    return;
                }
                dialog.dismiss();
                saveAndConnectHandyKey(key);
            }));
        dialog.show();
    }

    private void saveAndConnectHandyKey(String key) {
        Runnable saveNewKey = () -> {
            handyDisconnectInProgress = false;
            try {
                handyManager.saveKey(key);
            } catch (IllegalStateException error) {
                setFunscriptMessage(getString(R.string.funscript_old_device_stop_error,
                    error.getMessage()), () -> saveAndConnectHandyKey(key));
                return;
            }
            handyCheckedUri = null;
            funscriptRetryAction = null;
            autoConnectHandy(true);
        };
        if (!handyManager.isConnected() && !handyManager.isStopRequired()) {
            saveNewKey.run();
            return;
        }
        handyDisconnectInProgress = true;
        blockHandyPlayback();
        handyManager.disconnect(new HandyManager.HandyCallback() {
            @Override public void onSuccess(String message) { saveNewKey.run(); }
            @Override public void onError(String error) {
                handyDisconnectInProgress = false;
                setFunscriptMessage(getString(R.string.funscript_old_device_stop_error, error),
                    () -> saveAndConnectHandyKey(key));
            }
        });
    }

    private void connectHandy() {
        funscriptRetryAction = null;
        autoConnectHandy(true);
    }

    private void autoConnectHandy(boolean interactive) {
        if (handyManager == null) return;
        if (handyDisconnectInProgress) return;
        if (handyManager.getSavedKey().isEmpty()) {
            if (interactive) {
                setFunscriptMessage(getString(R.string.funscript_key_required), null);
                showKeyInput();
            }
            return;
        }
        if (handyManager.isConnected()) {
            prepareScriptAfterConnection();
            renderFunscriptState();
            return;
        }
        if (handyConnectInProgress) return;
        handyConnectInProgress = true;
        setFunscriptMessage(getString(R.string.funscript_connecting), null);
        handyManager.connect(new HandyManager.HandyCallback() {
            @Override public void onSuccess(String message) {
                handyConnectInProgress = false;
                if (isFinishing() || isDestroyed()) return;
                setFunscriptMessage(getString(R.string.funscript_connected), null);
                prepareScriptAfterConnection();
            }
            @Override public void onError(String error) {
                handyConnectInProgress = false;
                if (isFinishing() || isDestroyed()) return;
                setFunscriptMessage(error, () -> connectHandy());
                if (interactive) Toast.makeText(PlayerActivity.this, error,
                    Toast.LENGTH_LONG).show();
            }
        });
    }

    private void prepareScriptAfterConnection() {
        if (funscriptLoadInProgress || funscriptRetryAction != null || isInBackground) return;
        if (pendingHandyScriptUrl != null) uploadPendingHandyScriptUrl();
        else if (pendingPickedScript != null) {
            if (uriString.equals(pendingHandyScriptVideoUri)) {
                loadFunscriptFile(pendingPickedScript, pendingHandyScriptVideoUri,
                    activeScriptSource == null ? getString(R.string.funscript_source_file)
                        : activeScriptSource, true);
            }
        } else autoPrepareHandyForCurrentVideo();
    }
    private void syncHandyWithPlayback() {
        if (handyManager == null || mediaPlayer == null
                || !handyManager.isScriptReady() || !isHandyPlaybackAllowed()
                || !handyManager.isSynchronizationEnabled()) return;

        // REST v2 HSSP plays scripts at real-time speed. Keeping VLC at 1.0x is
        // the only drift-free behavior for the connection-key integration.
        if (Math.abs(playbackSpeed - 1.0f) > 0.01f) {
            long position = getBestKnownPlaybackPosition();
            playbackSpeed = 1.0f;
            mediaPlayer.setRate(1.0f);
            startPlaybackClockEstimate(position);
            tvSpeed.setText("1.0x");
            tvSpeed.setContentDescription(getString(R.string.player_speed) + ": 1.0x");
        }

        handler.removeCallbacks(handyCorrectionSync);
        handyManager.setVideoPlaybackAllowed(true);
        handyManager.play(getRawPlaybackPosition(), new HandyManager.HandyCallback() {
            @Override public void onSuccess(String message) { renderFunscriptState(); }
            @Override public void onError(String error) {
                setFunscriptMessage(error, () -> syncHandyNow());
            }
        });
        // Match the official SDK behavior: a second timestamp after VLC has
        // settled corrects startup/caching latency without continuous jolts.
        handler.postDelayed(handyCorrectionSync, 2_500);
    }

    private void syncHandyNow() {
        if (!handyManager.isConnected() || !handyManager.isScriptReady() || mediaPlayer == null) {
            setFunscriptMessage(getString(R.string.funscript_sync_no_script), null);
            return;
        }
        if (!handyManager.isSynchronizationEnabled() || !isHandyPlaybackAllowed()) {
            setFunscriptMessage(getString(R.string.funscript_sync_waiting), null);
            return;
        }
        long pos = getRawPlaybackPosition();
        handyManager.setVideoPlaybackAllowed(true);
        handyManager.play(pos, new HandyManager.HandyCallback() {
            @Override public void onSuccess(String message) {
                setFunscriptMessage(getString(R.string.funscript_synced_at, formatTime(pos)), null);
            }
            @Override public void onError(String error) {
                setFunscriptMessage(error, () -> syncHandyNow());
            }
        });
    }

    private void disconnectHandy() {
        if (handyDisconnectInProgress) return;
        handyDisconnectInProgress = true;
        funscriptOperationGeneration++;
        funscriptLoadInProgress = false;
        handler.removeCallbacks(handyCorrectionSync);
        renderFunscriptState();
        handyManager.disconnect(new HandyManager.HandyCallback() {
            @Override public void onSuccess(String m) {
                handyDisconnectInProgress = false;
                handyCheckedUri = null;
                setFunscriptMessage(getString(R.string.funscript_disconnected), null);
            }
            @Override public void onError(String e) {
                handyDisconnectInProgress = false;
                setFunscriptMessage(e, () -> disconnectHandy());
                Toast.makeText(PlayerActivity.this, e, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void showFunscriptDialog() {
        if (funscriptDialog != null && funscriptDialog.isShowing()) return;
        funscriptDialog = new BottomSheetDialog(this);
        funscriptDialog.setContentView(R.layout.dialog_funscript);
        funscriptConnectionView = funscriptDialog.findViewById(R.id.funscript_connection);
        funscriptFileView = funscriptDialog.findViewById(R.id.funscript_file);
        funscriptSyncView = funscriptDialog.findViewById(R.id.funscript_sync_state);
        funscriptMessageView = funscriptDialog.findViewById(R.id.funscript_message);
        funscriptProgressView = funscriptDialog.findViewById(R.id.funscript_progress);
        funscriptSyncSwitch = funscriptDialog.findViewById(R.id.funscript_sync_toggle);
        TextView video = funscriptDialog.findViewById(R.id.funscript_video);
        video.setText(getString(R.string.funscript_video, videoTitle == null ? "Video" : videoTitle));
        funscriptSyncSwitch.setOnCheckedChangeListener((button, enabled) -> {
            if (updatingFunscriptSwitch) return;
            if (enabled && handyDisconnectInProgress) {
                renderFunscriptState();
                return;
            }
            setHandySynchronizationEnabled(enabled);
        });
        funscriptDialog.findViewById(R.id.funscript_choose).setOnClickListener(v -> {
            funscriptPickerVideoUri = uriString;
            funscriptPicker.launch(new String[]{"application/json", "text/plain",
                "text/csv", "application/octet-stream", "*/*"});
        });
        funscriptDialog.findViewById(R.id.funscript_auto_find)
            .setOnClickListener(v -> autoFindAndSyncFunscript());
        funscriptDialog.findViewById(R.id.funscript_url)
            .setOnClickListener(v -> showFunscriptUrlInput());
        funscriptDialog.findViewById(R.id.funscript_resync)
            .setOnClickListener(v -> syncHandyNow());
        funscriptDialog.findViewById(R.id.funscript_connect)
            .setOnClickListener(v -> connectHandy());
        funscriptDialog.findViewById(R.id.funscript_key)
            .setOnClickListener(v -> showKeyInput());
        funscriptDialog.findViewById(R.id.funscript_status)
            .setOnClickListener(v -> showHandyStatus());
        funscriptDialog.findViewById(R.id.funscript_disconnect)
            .setOnClickListener(v -> disconnectHandy());
        funscriptDialog.findViewById(R.id.funscript_retry).setOnClickListener(v -> {
            Runnable retry = funscriptRetryAction;
            funscriptRetryAction = null;
            if (handyManager.isStopRequired()) setHandySynchronizationEnabled(false, retry);
            else if (retry != null) retry.run();
        });
        funscriptDialog.setOnDismissListener(d -> {
            funscriptDialog = null;
            funscriptConnectionView = null;
            funscriptFileView = null;
            funscriptSyncView = null;
            funscriptMessageView = null;
            funscriptProgressView = null;
            funscriptSyncSwitch = null;
        });
        funscriptDialog.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);
        renderFunscriptState();
        funscriptDialog.show();
    }

    private void setHandySynchronizationEnabled(boolean enabled) {
        setHandySynchronizationEnabled(enabled, null);
    }

    private void setHandySynchronizationEnabled(boolean enabled, Runnable retryAfterStop) {
        handler.removeCallbacks(handyCorrectionSync);
        handyManager.setSynchronizationEnabled(enabled, new HandyManager.HandyCallback() {
            @Override public void onSuccess(String message) {
                if (isFinishing() || isDestroyed()
                        || enabled != handyManager.isSynchronizationEnabled()) return;
                setFunscriptMessage(getString(enabled ? R.string.funscript_sync_waiting
                    : R.string.funscript_sync_off), enabled ? null : retryAfterStop);
                if (enabled) {
                    if (handyManager.isConnected()) {
                        prepareScriptAfterConnection();
                        syncHandyWithPlayback();
                    } else autoConnectHandy(true);
                }
            }
            @Override public void onError(String error) {
                setFunscriptMessage(error,
                    () -> setHandySynchronizationEnabled(false, retryAfterStop));
                Toast.makeText(PlayerActivity.this, error, Toast.LENGTH_LONG).show();
            }
        });
        renderFunscriptState();
    }

    private void renderFunscriptState() {
        if (funscriptDialog == null || handyManager == null) return;
        TextView video = funscriptDialog.findViewById(R.id.funscript_video);
        video.setText(getString(R.string.funscript_video, videoTitle == null ? "Video" : videoTitle));
        int connection = handyConnectInProgress ? R.string.funscript_connecting
            : handyManager.isConnected() ? R.string.funscript_connected
            : handyManager.getSavedKey().isEmpty() ? R.string.funscript_key_required
            : R.string.funscript_disconnected;
        funscriptConnectionView.setText(connection);
        funscriptFileView.setText(activeScriptName == null
            ? getString(R.string.funscript_no_script)
            : getString(R.string.funscript_selected, activeScriptName,
                activeScriptSource == null ? "" : activeScriptSource));
        int sync = handyManager.isStopRequired() ? R.string.funscript_stop_required
            : !handyManager.isSynchronizationEnabled() ? R.string.funscript_sync_off
            : !handyManager.isScriptReady() ? R.string.funscript_sync_no_script
            : !isHandyPlaybackAllowed() ? R.string.funscript_sync_waiting
            : handyManager.isPlaying() ? R.string.funscript_sync_running
            : R.string.funscript_sync_ready;
        funscriptSyncView.setText(sync);
        updatingFunscriptSwitch = true;
        funscriptSyncSwitch.setChecked(handyManager.isSynchronizationEnabled());
        funscriptSyncSwitch.setEnabled(!handyDisconnectInProgress);
        updatingFunscriptSwitch = false;
        funscriptProgressView.setVisibility(handyConnectInProgress || handyDisconnectInProgress || funscriptLoadInProgress
            ? View.VISIBLE : View.GONE);
        funscriptMessageView.setText(funscriptMessage == null ? "" : funscriptMessage);
        funscriptMessageView.setVisibility(funscriptMessage == null ? View.GONE : View.VISIBLE);
        funscriptDialog.findViewById(R.id.funscript_retry)
            .setVisibility(funscriptRetryAction == null && !handyManager.isStopRequired()
                ? View.GONE : View.VISIBLE);
        int[] sources = {R.id.funscript_choose, R.id.funscript_auto_find, R.id.funscript_url};
        for (int id : sources) funscriptDialog.findViewById(id)
            .setEnabled(!funscriptLoadInProgress && !handyDisconnectInProgress && !handyManager.isStopRequired());
        funscriptDialog.findViewById(R.id.funscript_connect)
            .setEnabled(!handyConnectInProgress && !handyDisconnectInProgress && !handyManager.isConnected());
        funscriptDialog.findViewById(R.id.funscript_key)
            .setEnabled(!handyConnectInProgress && !handyDisconnectInProgress && !funscriptLoadInProgress);
        funscriptDialog.findViewById(R.id.funscript_disconnect)
            .setEnabled(handyManager.isConnected() && !handyConnectInProgress && !handyDisconnectInProgress);
        funscriptDialog.findViewById(R.id.funscript_status)
            .setEnabled(handyManager.isConnected());
        funscriptDialog.findViewById(R.id.funscript_resync).setEnabled(
            handyManager.isSynchronizationEnabled() && handyManager.isScriptReady()
                && isHandyPlaybackAllowed());
    }

    private void setFunscriptMessage(String message, Runnable retry) {
        if (isDestroyed() || isFinishing()) return;
        funscriptMessage = message;
        funscriptRetryAction = retry;
        renderFunscriptState();
    }

    private void showFunscriptUrlInput() {
        EditText input = new EditText(this);
        input.setHint("https://example.com/video.funscript");
        input.setSingleLine(true);
        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle(R.string.funscript_url_action)
            .setMessage(R.string.funscript_upload_note).setView(input)
            .setPositiveButton(R.string.funscript_load_action, null)
            .setNegativeButton(R.string.cancel, null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            .setOnClickListener(v -> {
                String url = input.getText().toString().trim();
                Uri parsed = Uri.parse(url);
                String scheme = parsed.getScheme();
                if (parsed.getHost() == null || parsed.getHost().isEmpty()
                        || (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme))) {
                    input.setError(getString(R.string.funscript_invalid_url));
                    return;
                }
                dialog.dismiss();
                prepareHandyScriptUrl(url);
            }));
        dialog.show();
    }

    private void prepareHandyScriptUrl(String url) {
        funscriptOperationGeneration++;
        pendingHandyScriptUrl = url;
        pendingHandyScriptVideoUri = uriString;
        pendingPickedScript = null;
        funscriptRetryAction = null;
        if (handyManager.isConnected()) uploadPendingHandyScriptUrl();
        else autoConnectHandy(true);
    }

    private boolean isCurrentFunscriptOperation(int generation, String videoUri) {
        return !isDestroyed() && !isFinishing()
            && generation == funscriptOperationGeneration && videoUri.equals(uriString);
    }

    private void uploadPendingHandyScriptUrl() {
        if (pendingHandyScriptUrl == null || !handyManager.isConnected()
                || funscriptLoadInProgress) return;
        final String url = pendingHandyScriptUrl;
        final String videoUri = pendingHandyScriptVideoUri;
        final int generation = funscriptOperationGeneration;
        if (videoUri == null || !videoUri.equals(uriString)) return;
        funscriptLoadInProgress = true;
        activeScriptName = Uri.parse(url).getLastPathSegment();
        if (activeScriptName == null || activeScriptName.isEmpty())
            activeScriptName = getString(R.string.funscript_source_url);
        activeScriptSource = getString(R.string.funscript_source_url);
        setFunscriptMessage(getString(R.string.funscript_loading), null);
        handyManager.uploadAndSetupScript(url, new HandyManager.HandyCallback() {
            @Override public void onSuccess(String message) {
                if (!isCurrentFunscriptOperation(generation, videoUri)) return;
                funscriptLoadInProgress = false;
                pendingHandyScriptUrl = null;
                handyCheckedUri = videoUri;
                setFunscriptMessage(getString(R.string.funscript_loaded), null);
                syncHandyWithPlayback();
            }
            @Override public void onError(String error) {
                if (!isCurrentFunscriptOperation(generation, videoUri)) return;
                funscriptLoadInProgress = false;
                setFunscriptMessage(error, () -> {
                    if (handyManager.isConnected()) uploadPendingHandyScriptUrl();
                    else autoConnectHandy(true);
                });
                Toast.makeText(PlayerActivity.this, error, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void onFunscriptPicked(Uri pickedUri) {
        final String videoUri = funscriptPickerVideoUri;
        funscriptPickerVideoUri = null;
        if (pickedUri == null || videoUri == null) return;
        if (!videoUri.equals(uriString)) {
            setFunscriptMessage(getString(R.string.funscript_video_changed), null);
            Toast.makeText(this, R.string.funscript_video_changed, Toast.LENGTH_LONG).show();
            return;
        }
        final int generation = ++funscriptOperationGeneration;
        funscriptLoadInProgress = true;
        setFunscriptMessage(getString(R.string.funscript_importing), null);
        dbExecutor.execute(() -> {
            try {
                File cachedScript = copyPickedFunscript(pickedUri, videoUri);
                handler.post(() -> {
                    if (!isCurrentFunscriptOperation(generation, videoUri)) return;
                    funscriptLoadInProgress = false;
                    pendingHandyScriptUrl = null;
                    pendingPickedScript = cachedScript;
                    pendingHandyScriptVideoUri = videoUri;
                    activeScriptName = getScriptDisplayName(cachedScript, videoUri);
                    activeScriptSource = getString(R.string.funscript_source_file);
                    funscriptRetryAction = null;
                    if (handyManager.isConnected()) prepareScriptAfterConnection();
                    else autoConnectHandy(true);
                });
            } catch (Exception e) {
                handler.post(() -> {
                    if (!isCurrentFunscriptOperation(generation, videoUri)) return;
                    funscriptLoadInProgress = false;
                    setFunscriptMessage(getString(R.string.funscript_import_error, e.getMessage()), null);
                    Toast.makeText(PlayerActivity.this, funscriptMessage, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private File copyPickedFunscript(Uri pickedUri, String videoUri) throws Exception {
        String baseName = resolveVideoBaseName(videoUri);
        if (baseName == null || baseName.trim().isEmpty()) baseName = "video";
        String pickedName = queryDisplayName(pickedUri);
        String extension = pickedName != null && pickedName.toLowerCase(Locale.US).endsWith(".csv")
            ? ".csv" : ".funscript";
        File destination = FunscriptStore.destinationFor(
            new File(getFilesDir(), "funscripts"), videoUri, baseName, extension);
        File imported = FunscriptStore.importScript(
            getContentResolver().openInputStream(pickedUri), destination, HandyManager::validateScript);
        getSharedPreferences("funscript_bindings", MODE_PRIVATE).edit()
            .putString(videoUri, imported.getAbsolutePath())
            .putString("name:" + videoUri, pickedName == null ? baseName + extension : pickedName)
            .apply();
        return imported;
    }

    private String getScriptDisplayName(File script, String videoUri) {
        android.content.SharedPreferences bindings = getSharedPreferences("funscript_bindings", MODE_PRIVATE);
        return script.getAbsolutePath().equals(bindings.getString(videoUri, ""))
            ? bindings.getString("name:" + videoUri, script.getName()) : script.getName();
    }

    private void loadFunscriptFile(File script, String videoUri, String source, boolean showSuccess) {
        if (funscriptLoadInProgress || !videoUri.equals(uriString)) return;
        final int generation = funscriptOperationGeneration;
        funscriptLoadInProgress = true;
        activeScriptName = getScriptDisplayName(script, videoUri);
        activeScriptSource = source;
        setFunscriptMessage(getString(R.string.funscript_loading), null);
        handyManager.uploadAndSetupScript(script, new HandyManager.HandyCallback() {
            @Override public void onSuccess(String message) {
                if (!isCurrentFunscriptOperation(generation, videoUri)) return;
                funscriptLoadInProgress = false;
                pendingPickedScript = null;
                handyCheckedUri = videoUri;
                setFunscriptMessage(getString(R.string.funscript_loaded), null);
                if (showSuccess) Toast.makeText(PlayerActivity.this,
                    R.string.funscript_loaded, Toast.LENGTH_SHORT).show();
                syncHandyWithPlayback();
            }
            @Override public void onError(String error) {
                if (!isCurrentFunscriptOperation(generation, videoUri)) return;
                funscriptLoadInProgress = false;
                setFunscriptMessage(error, () -> {
                    pendingPickedScript = script;
                    pendingHandyScriptVideoUri = videoUri;
                    if (handyManager.isConnected())
                        loadFunscriptFile(script, videoUri, source, true);
                    else autoConnectHandy(true);
                });
                Toast.makeText(PlayerActivity.this, error, Toast.LENGTH_LONG).show();
            }
        });
    }
    @Override
    protected void onStart() {
        super.onStart();
    }

    @Override
    protected void onResume() {
        super.onResume();
        activityResumed = true;
        // Gui lai session cho DSP khi quay lai app
        if (audioSessionId != android.media.audiofx.AudioEffect.ERROR_BAD_VALUE) {
            handler.postDelayed(() -> broadcastAudioSessionOpen(), 500);
        }
        if (mediaPlayer != null && videoLayout != null && !contentAccessFailure) {
            if (verifyContentAccessOnResume && uriString != null
                    && "content".equals(Uri.parse(uriString).getScheme())) {
                validateContentBeforeResume();
            } else {
                restorePlaybackAfterBackground();
            }
        }
    }

    private void validateContentBeforeResume() {
        cancelVideoAccessValidation();
        final int generation = videoAccessGeneration;
        final int mediaGeneration = subtitleMediaGeneration;
        final String checkedUri = uriString;
        final CancellationSignal cancellation = new CancellationSignal();
        videoAccessCancellation = cancellation;
        checkingContentAccess = true;
        // An existing VLC descriptor can keep working after its URI grant is revoked.
        // Check a fresh descriptor before restoring video or Handy from background.
        if (mediaPlayer.isPlaying()) {
            resumeAfterBackground = !userPaused;
            lastPosition = getBestKnownPlaybackPosition();
            freezePlaybackClockEstimate();
            mediaPlayer.pause();
        }
        isInBackground = true;
        blockHandyPlayback();
        if (handyManager != null) handyManager.stopPlayback(null);
        abandonAudioFocus();
        videoAccessFuture = videoAccessExecutor.submit(() -> {
            boolean readable;
            try (ParcelFileDescriptor descriptor = getContentResolver()
                    .openFileDescriptor(Uri.parse(checkedUri), "r", cancellation)) {
                readable = descriptor != null;
            } catch (Exception ignored) {
                readable = false;
            }
            final boolean canRead = readable;
            handler.post(() -> {
                if (isDestroyed() || isFinishing() || !activityResumed
                        || generation != videoAccessGeneration || cancellation.isCanceled()
                        || mediaGeneration != subtitleMediaGeneration
                        || !checkedUri.equals(uriString)) return;
                videoAccessFuture = null;
                videoAccessCancellation = null;
                checkingContentAccess = false;
                if (!canRead) {
                    finishForUnavailableContent();
                    return;
                }
                verifyContentAccessOnResume = false;
                restorePlaybackAfterBackground();
            });
        });
    }

    private void restorePlaybackAfterBackground() {
        final int generation = videoAccessGeneration;
        final int mediaGeneration = subtitleMediaGeneration;
        videoLayout.post(() -> {
            if (!activityResumed || !isInBackground || isFinishing() || contentAccessFailure
                    || generation != videoAccessGeneration
                    || mediaGeneration != subtitleMediaGeneration) return;
            try {
                boolean shouldResume = resumeAfterBackground && !userPaused;
                mediaPlayer.attachViews(videoLayout, null, false, filtersEnabled);
                if (lastPosition > 0) mediaPlayer.setTime(lastPosition);
                isInBackground = false;
                resumeAfterBackground = false;
                prepareScriptAfterConnection();
                if (shouldResume && requestAudioFocus()) {
                    mediaPlayer.play();
                    handler.postDelayed(() -> broadcastAudioSessionOpen(), 500);
                    handler.postDelayed(() -> broadcastAudioSessionOpen(), 1500);
                }
            } catch (Exception e) {
                android.util.Log.w("PlayerActivity", "Could not restore playback", e);
            }
        });
    }

    private void cancelVideoAccessValidation() {
        videoAccessGeneration++;
        if (videoAccessCancellation != null) videoAccessCancellation.cancel();
        if (videoAccessFuture != null) videoAccessFuture.cancel(true);
        videoAccessCancellation = null;
        videoAccessFuture = null;
        checkingContentAccess = false;
    }

    private void finishForUnavailableContent() {
        if (contentAccessFailure || isFinishing() || isDestroyed()) return;
        contentAccessFailure = true;
        userPaused = true;
        resumeAfterBackground = false;
        resumeAfterFocusLoss = false;
        pendingUri = null;
        cancelVideoAccessValidation();
        cancelPlaybackRecoveryCallbacks();
        cancelSubtitleOperation();
        blockHandyPlayback();
        if (handyManager != null) handyManager.stopPlayback(null);
        try { if (mediaPlayer != null) mediaPlayer.stop(); } catch (Exception ignored) {}
        abandonAudioFocus();
        closePfd();
        Toast.makeText(this, R.string.library_video_unavailable, Toast.LENGTH_LONG).show();
        finish();
    }

    @Override protected void onPause() {
        activityResumed = false;
        verifyContentAccessOnResume = true;
        cancelVideoAccessValidation();
        super.onPause();
    }

    @Override protected void onStop() {
        super.onStop();
        cancelSubtitleOperation();
        isInBackground = true;
        blockHandyPlayback();
        resumeAfterBackground = mediaPlayer != null && !userPaused
            && (mediaPlayer.isPlaying() || resumeAfterFocusLoss || resumeAfterBackground);
        resumeAfterFocusLoss = false;
        saveHistory();
        cancelPlaybackRecoveryCallbacks();
        handlingPlaybackError = false;
        handler.removeCallbacks(handyCorrectionSync);
        if (handyManager != null) handyManager.stopPlayback(null);
        if (mediaPlayer != null) {
            lastPosition = getBestKnownPlaybackPosition();
            freezePlaybackClockEstimate();
            if (mediaPlayer.isPlaying()) mediaPlayer.pause();
        }
        abandonAudioFocus();
        if (isFinishing()) {
            if (mediaPlayer != null) mediaPlayer.stop();
            finishTorrentPlayback();
        }
    }

    private void finishTorrentPlayback() {
        if (torrentPlaybackFinished) return;
        torrentPlaybackFinished = true;
        String sessionId = getIntent().getStringExtra(EXTRA_TORRENT_SESSION_ID);
        if (sessionId != null) TorrentManager.finishPlayback(this, sessionId);
        String fileToken = getIntent().getStringExtra(EXTRA_TORRENT_FILE_TOKEN);
        if (fileToken != null) TorrentManager.finishStoredPlayback(this, fileToken);
    }

    @Override protected void onDestroy() {
        cancelVideoAccessValidation();
        videoAccessExecutor.shutdownNow();
        cancelSubtitleOperation();
        subtitleExecutor.shutdownNow();
        funscriptOperationGeneration++;
        if (funscriptDialog != null) funscriptDialog.dismiss();
        cancelPlaybackRecoveryCallbacks();
        handler.removeCallbacks(handyCorrectionSync);
        handler.removeCallbacks(handyHealthCheck);
        if (handyManager != null) handyManager.destroy();
        super.onDestroy();
        abandonAudioFocus();
        broadcastAudioSessionClose();
        handler.removeCallbacksAndMessages(null);
        if (mediaPlayer != null) mediaPlayer.release();
        clearAttachedSubtitleFiles();
        if (libVLC != null) libVLC.release();
        if (!isChangingConfigurations()) finishTorrentPlayback();
        // Let the final history write queued by onStop complete before exit.
        dbExecutor.shutdown();
        closePfd();
    }
    private void autoPrepareHandyForCurrentVideo() {
        if (handyManager == null || !handyManager.isConnected() || isInBackground
                || funscriptLoadInProgress || funscriptRetryAction != null
                || uriString == null || uriString.equals(handyCheckedUri)) return;
        File script = findMatchingHandyScript(uriString);
        if (script == null) return;
        handyCheckedUri = uriString;
        loadFunscriptFile(script, uriString, getString(R.string.funscript_source_auto), false);
    }
    private File findMatchingHandyScript(String mediaUri) {
        if (mediaUri == null || mediaUri.trim().isEmpty()) return null;
        String boundPath = getSharedPreferences("funscript_bindings", MODE_PRIVATE)
            .getString(mediaUri, null);
        if (boundPath != null) {
            File bound = new File(boundPath);
            if (bound.isFile() && bound.canRead()) return bound;
        }
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
        funscriptOperationGeneration++;
        pendingHandyScriptUrl = null;
        pendingPickedScript = null;
        funscriptRetryAction = null;
        handyCheckedUri = null;
        File script = findMatchingHandyScript(uriString);
        if (script == null) {
            setFunscriptMessage(getString(R.string.funscript_not_found_hint), null);
            return;
        }
        pendingPickedScript = script;
        pendingHandyScriptVideoUri = uriString;
        activeScriptName = getScriptDisplayName(script, uriString);
        activeScriptSource = getString(R.string.funscript_source_auto);
        if (handyManager != null && handyManager.isConnected()) {
            handyCheckedUri = uriString;
            loadFunscriptFile(script, uriString, getString(R.string.funscript_source_auto), true);
        } else {
            autoConnectHandy(true);
        }
    }

    private void showAudioTrackDialog() {
        if (mediaPlayer == null) return;
        MediaPlayer.TrackDescription[] tracks = mediaPlayer.getAudioTracks();
        if (tracks == null || tracks.length == 0) {
            Toast.makeText(this, R.string.player_audio_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        final String videoUri = uriString;
        final int mediaGeneration = subtitleMediaGeneration;
        int current = mediaPlayer.getAudioTrack();
        int currentIndex = -1;
        String[] names = new String[tracks.length];
        for (int i = 0; i < tracks.length; i++) {
            String name = tracks[i].name;
            names[i] = name == null || name.trim().isEmpty() || "-1".equals(name)
                ? getString(R.string.player_track_fallback, i + 1) : name;
            if (tracks[i].id == current) currentIndex = i;
        }
        new AlertDialog.Builder(this)
            .setTitle(R.string.player_audio_track)
            .setSingleChoiceItems(names, currentIndex, (dialog, which) -> {
                if (!isCurrentSubtitleMedia(videoUri, mediaGeneration)) {
                    dialog.dismiss();
                    Toast.makeText(this, R.string.player_media_changed,
                        Toast.LENGTH_SHORT).show();
                    return;
                }
                if (tracks[which].id == current) {
                    dialog.dismiss();
                    return;
                }
                boolean selected;
                try { selected = mediaPlayer.setAudioTrack(tracks[which].id); }
                catch (Exception e) { selected = false; }
                if (selected) dialog.dismiss();
                else Toast.makeText(this, R.string.player_audio_select_failed,
                    Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton(R.string.player_ai_cancel, null).show();
    }

    private void showSubtitleTrackDialog() {
        if (mediaPlayer == null) return;
        final String videoUri = uriString;
        final int mediaGeneration = subtitleMediaGeneration;
        MediaPlayer.TrackDescription[] tracks = mediaPlayer.getSpuTracks();
        int current = mediaPlayer.getSpuTrack();
        ArrayList<Integer> ids = new ArrayList<>();
        ArrayList<String> names = new ArrayList<>();
        ids.add(-1);
        names.add(getString(R.string.player_subtitle_off));
        int currentIndex = current == -1 ? 0 : -1;
        if (tracks != null) {
            for (MediaPlayer.TrackDescription track : tracks) {
                if (track.id == -1) continue;
                ids.add(track.id);
                String name = track.name;
                names.add(name == null || name.trim().isEmpty() || "-1".equals(name)
                    ? getString(R.string.player_track_fallback, names.size()) : name);
                if (track.id == current) currentIndex = ids.size() - 1;
            }
        }
        new AlertDialog.Builder(this)
            .setTitle(R.string.player_subtitle_track)
            .setSingleChoiceItems(names.toArray(new String[0]), currentIndex,
                (dialog, which) -> {
                    if (!isCurrentSubtitleMedia(videoUri, mediaGeneration)) {
                        dialog.dismiss();
                        Toast.makeText(this, R.string.player_media_changed,
                            Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (ids.get(which) == current) {
                        dialog.dismiss();
                        return;
                    }
                    boolean selected;
                    try { selected = mediaPlayer.setSpuTrack(ids.get(which)); }
                    catch (Exception e) { selected = false; }
                    if (selected) {
                        subtitleOffSelected = ids.get(which) == -1;
                        selectedExternalSubtitleFile = null;
                        dialog.dismiss();
                    } else Toast.makeText(this, R.string.player_subtitle_select_failed,
                        Toast.LENGTH_SHORT).show();
                })
            .setNegativeButton(R.string.player_ai_cancel, null).show();
    }

    private void pickLocalSrt() {
        subtitlePickerVideoUri = uriString;
        subtitlePickerMediaGeneration = subtitleMediaGeneration;
        subtitlePicker.launch(new String[]{"application/x-subrip", "text/plain", "*/*"});
    }

    private void onSubtitlePicked(Uri pickedUri) {
        String videoUri = subtitlePickerVideoUri;
        int mediaGeneration = subtitlePickerMediaGeneration;
        subtitlePickerVideoUri = null;
        if (pickedUri == null || videoUri == null) return;
        if (!isCurrentSubtitleMedia(videoUri, mediaGeneration)) {
            Toast.makeText(this, R.string.player_subtitle_video_changed, Toast.LENGTH_LONG).show();
            return;
        }
        cancelSubtitleOperation();
        final int generation = ++subtitleOperationGeneration;
        subtitleIoFuture = subtitleExecutor.submit(() -> {
            File file = null;
            try {
                byte[] bytes;
                try (InputStream input = getContentResolver().openInputStream(pickedUri)) {
                    if (input == null)
                        throw new IOException(getString(R.string.player_subtitle_open_failed));
                    bytes = readSubtitleBytes(input);
                }
                validateSrt(bytes);
                file = createSubtitleCacheFile();
                try (FileOutputStream output = new FileOutputStream(file)) {
                    output.write(bytes);
                }
                final File result = file;
                runOnUiThread(() -> {
                    if (!isCurrentSubtitleOperation(generation, videoUri)
                            || mediaGeneration != subtitleMediaGeneration) {
                        result.delete();
                        return;
                    }
                    boolean attached = attachSubtitleFile(result);
                    finishSubtitleOperation();
                    if (attached) Toast.makeText(this,
                        R.string.player_subtitle_loaded, Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                if (file != null) file.delete();
                runOnUiThread(() -> {
                    if (!isCurrentSubtitleOperation(generation, videoUri)) return;
                    finishSubtitleOperation();
                    Toast.makeText(this,
                        getString(R.string.player_subtitle_load_failed, e.getMessage()),
                        Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private byte[] readSubtitleBytes(InputStream input) throws IOException {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        subtitleReadStream = input;
        try {
            while (true) {
                if (Thread.currentThread().isInterrupted())
                    throw new java.io.InterruptedIOException("Subtitle operation cancelled");
                int count = input.read(buffer);
                if (count == -1) break;
                if (output.size() + count > MAX_SUBTITLE_BYTES)
                    throw new IOException(getString(R.string.player_subtitle_too_large));
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        } finally {
            if (subtitleReadStream == input) subtitleReadStream = null;
        }
    }

    private void validateSrt(byte[] bytes) throws IOException {
        if (!new String(bytes, StandardCharsets.UTF_8).contains("-->"))
            throw new IOException(getString(R.string.player_subtitle_invalid));
    }

    private File createSubtitleCacheFile() throws IOException {
        File directory = new File(getCacheDir(), "subtitles");
        if (!directory.isDirectory() && !directory.mkdirs())
            throw new IOException(getString(R.string.player_subtitle_cache_failed));
        return File.createTempFile("subtitle_", ".srt", directory);
    }

    private void pruneCachedSubtitleFiles() {
        File directory = new File(getCacheDir(), "subtitles");
        File[] stale = directory.listFiles((dir, name) ->
            name.startsWith("subtitle_") && name.endsWith(".srt"));
        if (stale == null) return;
        for (File file : stale) if (file.isFile()) file.delete();
    }

    private void clearAttachedSubtitleFiles() {
        for (File file : attachedSubtitleFiles) file.delete();
        attachedSubtitleFiles.clear();
        selectedExternalSubtitleFile = null;
        subtitleOffSelected = false;
        subtitleRestorePendingGeneration = -1;
    }

    private void restoreAttachedSubtitlesIfNeeded() {
        if (subtitleRestorePendingGeneration != subtitleMediaGeneration
                || mediaPlayer == null || !mediaPlayer.isPlaying()) return;
        subtitleRestorePendingGeneration = -1;
        boolean restored = true;
        for (File file : attachedSubtitleFiles) {
            if (!file.isFile()) {
                restored = false;
                continue;
            }
            try {
                boolean select = file.equals(selectedExternalSubtitleFile);
                if (!mediaPlayer.addSlave(Media.Slave.Type.Subtitle,
                        Uri.fromFile(file), select)) restored = false;
            } catch (Exception e) {
                restored = false;
            }
        }
        if (subtitleOffSelected && mediaPlayer.getSpuTrack() != -1) {
            try { if (!mediaPlayer.setSpuTrack(-1)) restored = false; }
            catch (Exception e) { restored = false; }
        }
        if (!restored) Toast.makeText(this,
            R.string.player_subtitle_restore_failed, Toast.LENGTH_LONG).show();
    }

    private boolean attachSubtitleFile(File file) {
        boolean attached;
        try {
            attached = mediaPlayer != null && mediaPlayer.addSlave(
                Media.Slave.Type.Subtitle, Uri.fromFile(file), true);
        } catch (Exception e) {
            attached = false;
        }
        if (!attached) {
            file.delete();
            Toast.makeText(this, getString(R.string.player_subtitle_load_failed,
                getString(R.string.player_subtitle_select_failed)), Toast.LENGTH_LONG).show();
        } else {
            attachedSubtitleFiles.add(file);
            selectedExternalSubtitleFile = file;
            subtitleOffSelected = false;
        }
        return attached;
    }

    private boolean isCurrentSubtitleMedia(String videoUri, int mediaGeneration) {
        return videoUri != null && videoUri.equals(uriString)
            && mediaGeneration == subtitleMediaGeneration
            && mediaPlayer != null && !isFinishing() && !isDestroyed();
    }

    private boolean isCurrentSubtitleOperation(int generation, String videoUri) {
        return generation == subtitleOperationGeneration
            && videoUri != null && videoUri.equals(uriString)
            && mediaPlayer != null && !isFinishing() && !isDestroyed();
    }

    private void finishSubtitleOperation() {
        subtitleOperationGeneration++;
        subtitleTranslationTask = null;
        subtitleIoFuture = null;
        if (subtitleProgressDialog != null) {
            subtitleProgressDialog.dismiss();
            subtitleProgressDialog = null;
        }
    }

    private void cancelSubtitleOperation() {
        subtitleOperationGeneration++;
        if (subtitleTranslationTask != null) {
            subtitleTranslationTask.cancel();
            subtitleTranslationTask = null;
        }
        if (subtitleIoFuture != null) {
            subtitleIoFuture.cancel(true);
            subtitleIoFuture = null;
        }
        if (subtitleDownloadConnection != null) {
            subtitleDownloadConnection.disconnect();
            subtitleDownloadConnection = null;
        }
        if (subtitleReadStream != null) {
            try { subtitleReadStream.close(); } catch (IOException ignored) {}
            subtitleReadStream = null;
        }
        if (subtitleProgressDialog != null) {
            subtitleProgressDialog.dismiss();
            subtitleProgressDialog = null;
        }
    }


    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        String newUri = intent.getStringExtra(EXTRA_URI);
        if (newUri == null && intent.getData() != null) {
            newUri = intent.getData().toString();
        }
        if (newUri == null || newUri.trim().isEmpty()) return;
        cancelSubtitleOperation();
        subtitleMediaGeneration++;
        discardAttachedSubtitlesOnNextMedia = true;
        saveHistory();
        String previousSession = getIntent().getStringExtra(EXTRA_TORRENT_SESSION_ID);
        boolean sameSession = previousSession != null
            && previousSession.equals(intent.getStringExtra(EXTRA_TORRENT_SESSION_ID));
        String previousFileToken = getIntent().getStringExtra(EXTRA_TORRENT_FILE_TOKEN);
        boolean sameStoredFile = previousSession == null
            && previousFileToken != null && newUri.equals(uriString);
        if (sameStoredFile) {
            String replacementToken = intent.getStringExtra(EXTRA_TORRENT_FILE_TOKEN);
            if ((replacementToken != null && !previousFileToken.equals(replacementToken))
                    || (intent.hasExtra(EXTRA_AUTO_CLEANUP_TORRENT)
                        && !intent.getBooleanExtra(EXTRA_AUTO_CLEANUP_TORRENT, false))) {
                TorrentManager.discardStoredPlayback(previousFileToken);
            } else if (replacementToken == null) {
                intent.putExtra(EXTRA_TORRENT_FILE_TOKEN, previousFileToken);
            }
        }
        if (!sameSession && !sameStoredFile) {
            if (mediaPlayer != null) mediaPlayer.stop();
            finishTorrentPlayback();
        }
        setIntent(intent);
        torrentPlaybackFinished = false;
        if (!intent.getBooleanExtra(EXTRA_USE_PLAYLIST, false)) {
            PlaylistManager.get().clear();
        }
        updatePlaylistButtons();

        uriString = newUri;
        videoTitle = intent.getStringExtra(EXTRA_TITLE);
        if (videoTitle == null || videoTitle.trim().isEmpty()) {
            videoTitle = resolveVideoBaseName(newUri);
        }
        if (videoTitle == null || videoTitle.trim().isEmpty()) videoTitle = "Video";
        tvTitle.setText(videoTitle);
        lastPosition = 0;
        if (isInBackground) resumeAfterBackground = true;
        playMedia(uriString);
    }
}
