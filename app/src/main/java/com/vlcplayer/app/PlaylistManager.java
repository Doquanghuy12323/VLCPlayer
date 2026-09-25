package com.vlcplayer.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class PlaylistManager {

    public enum RepeatMode { NONE, ONE, ALL }

    private static PlaylistManager instance;
    private final List<VideoItem> queue = new ArrayList<>();
    private final List<Integer> shuffleRemaining = new ArrayList<>();
    private final List<Integer> shuffleHistory = new ArrayList<>();
    private int shuffleCursor = -1;
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
        currentIndex = queue.isEmpty() ? 0 : Math.max(0, Math.min(startIndex, queue.size() - 1));
        resetShuffleTraversal();
    }

    public void addToQueue(VideoItem item) {
        queue.add(item);
        resetShuffleTraversal();
    }

    public void addNext(VideoItem item) {
        queue.add(Math.min(currentIndex + 1, queue.size()), item);
        resetShuffleTraversal();
    }

    public VideoItem getCurrent() {
        if (queue.isEmpty() || currentIndex < 0 || currentIndex >= queue.size())
            return null;
        return queue.get(currentIndex);
    }

    public VideoItem getNext() {
        if (queue.isEmpty()) return null;
        if (shuffle) {
            if (shuffleCursor + 1 < shuffleHistory.size()) {
                currentIndex = shuffleHistory.get(++shuffleCursor);
                return queue.get(currentIndex);
            }
            if (shuffleRemaining.isEmpty() && repeatMode == RepeatMode.ALL) {
                if (queue.size() == 1) return queue.get(currentIndex);
                fillShuffleRemaining();
            }
            if (shuffleRemaining.isEmpty()) return null;
            currentIndex = shuffleRemaining.remove(0);
            shuffleHistory.add(currentIndex);
            shuffleCursor++;
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
        if (shuffle) {
            if (shuffleCursor <= 0) return null;
            currentIndex = shuffleHistory.get(--shuffleCursor);
            return queue.get(currentIndex);
        }
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
        if (queue.isEmpty()) return false;
        if (shuffle) {
            return shuffleCursor + 1 < shuffleHistory.size()
                || !shuffleRemaining.isEmpty()
                || repeatMode == RepeatMode.ALL;
        }
        return currentIndex + 1 < queue.size() || repeatMode == RepeatMode.ALL;
    }

    public boolean hasPrev() {
        return shuffle ? shuffleCursor > 0
            : !queue.isEmpty() && (currentIndex > 0 || repeatMode == RepeatMode.ALL);
    }

    public void toggleShuffle() {
        shuffle = !shuffle;
        resetShuffleTraversal();
    }

    private void resetShuffleTraversal() {
        shuffleHistory.clear();
        shuffleRemaining.clear();
        shuffleCursor = -1;
        if (!shuffle || queue.isEmpty()) return;
        shuffleHistory.add(currentIndex);
        shuffleCursor = 0;
        fillShuffleRemaining();
    }

    private void fillShuffleRemaining() {
        for (int i = 0; i < queue.size(); i++) {
            if (i != currentIndex) shuffleRemaining.add(i);
        }
        Collections.shuffle(shuffleRemaining);
    }
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
    public void setCurrentIndex(int i) {
        if (i < 0 || i >= queue.size()) return;
        currentIndex = i;
        resetShuffleTraversal();
    }
    public int size() { return queue.size(); }
    public void clear() {
        queue.clear();
        currentIndex = 0;
        resetShuffleTraversal();
    }
}
