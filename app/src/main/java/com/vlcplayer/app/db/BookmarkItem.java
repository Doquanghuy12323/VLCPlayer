package com.vlcplayer.app.db;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "bookmarks")
public class BookmarkItem {
    @PrimaryKey(autoGenerate = true)
    public int id;
    public String uri;
    public String videoTitle;
    public long position;  // ms
    public String label;
    public long createdAt;

    public BookmarkItem(String uri, String videoTitle, long position, String label) {
        this.uri = uri;
        this.videoTitle = videoTitle;
        this.position = position;
        this.label = label;
        this.createdAt = System.currentTimeMillis();
    }
}
