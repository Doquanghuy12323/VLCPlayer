package com.vlcplayer.app;

import android.app.PictureInPictureParams;
import android.app.ProgressDialog;
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
    private View controlsOverlay, lockOverlay;
    private boolean isLocked = false;

    private final Handler handler = new Handler();
    private boolean controlsVisible = true;
    private boolean userSeeking = false;
    private boolean waveletSent = false;
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
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
            WindowManager.LayoutParams.FLAG_FULLSCREEN);
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
        findViewById(R.id.btn_forward).setOnClickListener(v -> {
            if (mediaPlayer != null) mediaPlayer.setTime(mediaPlayer.getTime() + 10000);
        });
        findViewById(R.id.btn_rewind).setOnClickListener(v -> {
            if (mediaPlayer != null) mediaPlayer.setTime(Math.max(0, mediaPlayer.getTime() - 10000));
        });
        findViewById(R.id.btn_aspect).setOnClickListener(v -> cycleAspectRatio());
        findViewById(R.id.btn_speed).setOnClickListener(v -> showSpeedDialog());
        findViewById(R.id.btn_bookmark).setOnClickListener(v -> addBookmark());
        findViewById(R.id.btn_eq).setOnClickListener(v -> showEqualizerDialog());
        findViewById(R.id.btn_pip).setOnClickListener(v -> enterPiP());
        findViewById(R.id.btn_lock).setOnClickListener(v -> toggleLock());
        findViewById(R.id.btn_unlock).setOnClickListener(v -> toggleLock());
        findViewById(R.id.btn_translate).setOnClickListener(v -> showTranslateDialog());

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
                public boolean onScroll(MotionEvent e1, MotionEvent e2, float dX, float dY) {
                    if (isLocked || e1 == null) return false;
                    if (e1.getX() < screenW / 2f) adjustBrightness(dY * 0.005f);
                    else adjustVolume(dY * 0.005f);
                    return true;
                }
                @Override
                public boolean onDoubleTap(MotionEvent e) {
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

    private void adjustBrightness(float delta) {
        WindowManager.LayoutParams p = getWindow().getAttributes();
        p.screenBrightness = Math.max(0.01f, Math.min(1.0f, p.screenBrightness + delta));
        getWindow().setAttributes(p);
        Toast.makeText(this, "Sang: " + (int)(p.screenBrightness * 100) + "%", Toast.LENGTH_SHORT).show();
    }

    private void adjustVolume(float delta) {
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int cur = am.getStreamVolume(AudioManager.STREAM_MUSIC);
        am.setStreamVolume(AudioManager.STREAM_MUSIC,
            Math.max(0, Math.min(max, (int)(cur + delta * max))),
            AudioManager.FLAG_SHOW_UI);
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
                        applyScaleMode();
                        scheduleHideControls();
                        if (!waveletSent) { broadcastAudioSessionOpen(); waveletSent = true; }
                    });
                    break;
                case MediaPlayer.Event.Paused:
                    runOnUiThread(() -> btnPlayPause.setImageResource(android.R.drawable.ic_media_play));
                    break;
                case MediaPlayer.Event.EndReached:
                    saveHistory();
                    runOnUiThread(this::finish);
                    break;
            }
        });
        mediaPlayer.attachViews(videoLayout, null, false, false);
    }

    private void playMedia(String uriString) {
        dbExecutor.execute(() -> {
            HistoryItem history = AppDatabase.get(this).dao().getHistoryByUri(uriString);
            final long resumePos = (history != null && history.lastPosition > 5000) ? history.lastPosition : 0;
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
            .setTitle("Danh dau thoi diem")
            .setView(input)
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

    // ========== DICH AI ==========

    private void showTranslateDialog() {
        TranslationManager tm = new TranslationManager(this);
        String[] opts = {
            "Dich subtitle tu URL",
            "Doi ngon ngu (hien tai: " + tm.getTargetLanguageName() + ")"
        };
        new AlertDialog.Builder(this)
            .setTitle("Dich AI")
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
        new AlertDialog.Builder(this)
            .setTitle("Chon ngon ngu dich")
            .setSingleChoiceItems(names, curIdx, (d, w) -> sel[0] = w)
            .setPositiveButton("Luu", (d, w) -> {
                tm.setTargetLanguage(langs[sel[0]][1]);
                Toast.makeText(this, "Da chon: " + langs[sel[0]][0], Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("Huy", null).show();
    }

    private void showSrtUrlInput() {
        EditText input = new EditText(this);
        input.setHint("https://example.com/subtitle.srt");
        new AlertDialog.Builder(this)
            .setTitle("URL file SRT")
            .setView(input)
            .setPositiveButton("Dich", (d, w) -> {
                String url = input.getText().toString().trim();
                if (!url.isEmpty()) startSrtDownloadAndTranslate(url);
            })
            .setNegativeButton("Huy", null).show();
    }

    private void startSrtDownloadAndTranslate(String url) {
        ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage("Dang tai subtitle...");
        pd.setCancelable(false);
        pd.show();

        new Thread(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(10000);
                BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String ln;
                while ((ln = br.readLine()) != null) {
                    sb.append(ln).append("\n");
                }
                br.close();
                final String srt = sb.toString();

                runOnUiThread(() -> pd.setMessage("Dang dich..."));

                TranslationManager tm = new TranslationManager(this);
                tm.translateSrt(srt, "auto",
                    msg -> runOnUiThread(() -> pd.setMessage(msg)),
                    new TranslationManager.TranslateCallback() {
                        @Override
                        public void onSuccess(String translated) {
                            runOnUiThread(() -> {
                                pd.dismiss();
                                saveSrtAndLoad(translated);
                            });
                        }
                        @Override
                        public void onError(String error) {
                            runOnUiThread(() -> {
                                pd.dismiss();
                                Toast.makeText(PlayerActivity.this, error, Toast.LENGTH_SHORT).show();
                            });
                        }
                    });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    pd.dismiss();
                    Toast.makeText(this, "Loi tai: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }

    private void saveSrtAndLoad(String srtContent) {
        try {
            File f = new File(getExternalFilesDir(null), "translated.srt");
            FileWriter fw = new FileWriter(f);
            fw.write(srtContent);
            fw.close();
            if (mediaPlayer != null)
                mediaPlayer.addSlave(Media.Slave.Type.Subtitle, Uri.fromFile(f).toString(), true);
            Toast.makeText(this, "Da dich va load subtitle!", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Loi luu: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    // ========== UTILS ==========

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
        String[] labels = {"Vua man hinh", "Lap day", "Keo dan"};
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
        enterPiP();
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
        if (equalizer != null) equalizer.release();
        handler.removeCallbacksAndMessages(null);
        mediaPlayer.release();
        libVLC.release();
        closePfd();
    }
}
