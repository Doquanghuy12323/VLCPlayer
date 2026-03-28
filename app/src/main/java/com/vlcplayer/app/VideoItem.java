package com.vlcplayer.app;

import android.net.Uri;

public class VideoItem {
    private final long id;
    private final String name;
    private final long duration;
    private final long size;
    private final String path;
    private final Uri uri;

    public VideoItem(long id, String name, long duration, long size, String path, Uri uri) {
        this.id = id; this.name = name; this.duration = duration;
        this.size = size; this.path = path; this.uri = uri;
    }

    public long getId()       { return id; }
    public String getName()   { return name; }
    public long getDuration() { return duration; }
    public long getSize()     { return size; }
    public String getPath()   { return path; }
    public Uri getUri()       { return uri; }

    public String getFormattedDuration() {
        long s = duration / 1000;
        long m = s / 60; s %= 60;
        long h = m / 60; m %= 60;
        if (h > 0) return String.format("%d:%02d:%02d", h, m, s);
        return String.format("%02d:%02d", m, s);
    }

    public String getFormattedSize() {
        if (size < 1024 * 1024) return String.format("%.1f KB", size / 1024.0);
        if (size < 1024 * 1024 * 1024) return String.format("%.1f MB", size / (1024.0 * 1024));
        return String.format("%.2f GB", size / (1024.0 * 1024 * 1024));
    }
}
