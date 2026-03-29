package com.vlcplayer.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.support.v4.media.session.MediaSessionCompat;

import androidx.core.app.NotificationCompat;

public class MediaPlaybackService extends Service {

    public static final String CHANNEL_ID = "vlcplayer_bg";
    public static final String ACTION_STOP = "action_stop";
    public static final int NOTIF_ID = 1001;

    private final IBinder binder = new LocalBinder();
    private MediaSessionCompat mediaSession;

    public class LocalBinder extends Binder {
        MediaPlaybackService getService() { return MediaPlaybackService.this; }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        mediaSession = new MediaSessionCompat(this, "VLCPlayerSession");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(NOTIF_ID, buildNotification("Dang phat", ""));
        return START_STICKY;
    }

    public void updateNotification(String title, String artist) {
        NotificationManager nm = (NotificationManager)
            getSystemService(NOTIFICATION_SERVICE);
        nm.notify(NOTIF_ID, buildNotification(title, artist));
    }

    private Notification buildNotification(String title, String text) {
        Intent stopIntent = new Intent(this, MediaPlaybackService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent openPi = PendingIntent.getActivity(this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title.isEmpty() ? "VLC Player" : title)
            .setContentText(text.isEmpty() ? "Dang chay nen" : text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(openPi)
            .addAction(android.R.drawable.ic_media_pause, "Dung", stopPi)
            .setStyle(new androidx.media.app.NotificationCompat.MediaStyle()
                .setMediaSession(mediaSession.getSessionToken())
                .setShowActionsInCompactView(0))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "VLC Player Background",
                NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Phat nhac/video nen");
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                .createNotificationChannel(ch);
        }
    }

    @Override public IBinder onBind(Intent i) { return binder; }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mediaSession != null) mediaSession.release();
    }
}
