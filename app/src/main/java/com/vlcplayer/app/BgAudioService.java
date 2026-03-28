package com.vlcplayer.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

public class BgAudioService extends Service {
    public static final String ACTION_PAUSE = "com.vlcplayer.PAUSE";
    public static final String ACTION_PLAY = "com.vlcplayer.PLAY";
    public static final String ACTION_CLOSE = "com.vlcplayer.CLOSE";
    private static final String CHANNEL_ID = "vlc_bg_audio";

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String title = intent != null ? intent.getStringExtra("title") : "VLC Audio";
        createChannel();

        int intentFlags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;

        PendingIntent pPlay = PendingIntent.getBroadcast(this, 1, new Intent(ACTION_PLAY), intentFlags);
        PendingIntent pPause = PendingIntent.getBroadcast(this, 2, new Intent(ACTION_PAUSE), intentFlags);
        PendingIntent pClose = PendingIntent.getBroadcast(this, 3, new Intent(ACTION_CLOSE), intentFlags);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText("Đang phát trong nền")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .addAction(android.R.drawable.ic_media_pause, "Pause", pPause)
            .addAction(android.R.drawable.ic_media_play, "Play", pPlay)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Close", pClose)
            .setStyle(new androidx.media.app.NotificationCompat.MediaStyle().setShowActionsInCompactView(0, 1, 2))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(101, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(101, notification);
        }
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Background Audio", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Điều khiển nhạc khi tắt màn hình");
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }
}
