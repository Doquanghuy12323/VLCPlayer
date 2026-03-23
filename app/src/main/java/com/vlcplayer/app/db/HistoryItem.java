package com.vlcplayer.app.db;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "history")
public class HistoryItem {
    @PrimaryKey(autoGenerate = true)
    public int id;
    public String uri;
    public String title;
    public long lastPosition; // ms
    public long duration;
    public long watchedAt;    // timestamp

    public HistoryItem(String uri, String title, long lastPosition, long duration) {
        this.uri = uri;
        this.title = title;
        this.lastPosition = lastPosition;
        this.duration = duration;
        this.watchedAt = System.currentTimeMillis();
    }
}
