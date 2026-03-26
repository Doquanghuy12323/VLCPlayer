package com.vlcplayer.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class PlaylistManager {

    public enum RepeatMode { NONE, ONE, ALL }

    private static PlaylistManager instance;
    private final List<VideoItem> queue = new ArrayList<>();
    private int currentIndex = 0;
    private RepeatMode repeatMode = RepeatMode.NONE;
    private boolean shuffle = false;

    public static PlaylistManager get() {
        if (instance == null) instance = new PlaylistManager();
        return instance;
    }

    public void setQueue(List<VideoItem> items, int startIndex) {
        queue.clear();
        queue.addAll(items);
        currentIndex = startIndex;
    }

    public void addToQueue(VideoItem item) {
        queue.add(item);
    }

    public void addNext(VideoItem item) {
        queue.add(currentIndex + 1, item);
    }

    public VideoItem getCurrent() {
        if (queue.isEmpty() || currentIndex < 0 || currentIndex >= queue.size())
            return null;
        return queue.get(currentIndex);
    }

    public VideoItem getNext() {
        if (queue.isEmpty()) return null;
        if (shuffle) {
            int next = (int)(Math.random() * queue.size());
            currentIndex = next;
            return queue.get(currentIndex);
        }
        if (currentIndex + 1 < queue.size()) {
            currentIndex++;
            return queue.get(currentIndex);
        }
        if (repeatMode == RepeatMode.ALL) {
            currentIndex = 0;
            return queue.get(currentIndex);
        }
        return null;
    }

    public VideoItem getPrev() {
        if (queue.isEmpty()) return null;
        if (currentIndex - 1 >= 0) {
            currentIndex--;
            return queue.get(currentIndex);
        }
        if (repeatMode == RepeatMode.ALL) {
            currentIndex = queue.size() - 1;
            return queue.get(currentIndex);
        }
        return null;
    }

    public boolean hasNext() {
        if (repeatMode == RepeatMode.ALL || shuffle) return !queue.isEmpty();
        return currentIndex + 1 < queue.size();
    }

    public boolean hasPrev() {
        return currentIndex > 0;
    }

    public void toggleShuffle() { shuffle = !shuffle; }
    public boolean isShuffle() { return shuffle; }

    public RepeatMode cycleRepeat() {
        switch (repeatMode) {
            case NONE: repeatMode = RepeatMode.ALL; break;
            case ALL:  repeatMode = RepeatMode.ONE; break;
            case ONE:  repeatMode = RepeatMode.NONE; break;
        }
        return repeatMode;
    }

    public RepeatMode getRepeatMode() { return repeatMode; }
    public List<VideoItem> getQueue() { return queue; }
    public int getCurrentIndex() { return currentIndex; }
    public void setCurrentIndex(int i) { currentIndex = i; }
    public int size() { return queue.size(); }
    public void clear() { queue.clear(); currentIndex = 0; }
}
