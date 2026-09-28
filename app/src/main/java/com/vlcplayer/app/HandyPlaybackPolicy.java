package com.vlcplayer.app;

/**
 * Tracks user consent and video readiness separately from network/device state.
 * A generation identifies the playback intent that created a queued request.
 */
final class HandyPlaybackPolicy {
    static final long NOT_ALLOWED = -1L;

    private boolean synchronizationEnabled;
    private boolean videoPlaybackAllowed;
    private boolean playbackDesired;
    private boolean stopRequired;
    private long generation;

    HandyPlaybackPolicy(boolean synchronizationEnabled) {
        this.synchronizationEnabled = synchronizationEnabled;
    }

    synchronized boolean isSynchronizationEnabled() {
        return synchronizationEnabled;
    }

    synchronized void setSynchronizationEnabled(boolean enabled) {
        if (enabled && stopRequired) return;
        if (!enabled || synchronizationEnabled != enabled) invalidate();
        synchronizationEnabled = enabled;
    }

    synchronized boolean isVideoPlaybackAllowed() {
        return videoPlaybackAllowed;
    }

    synchronized void setVideoPlaybackAllowed(boolean allowed) {
        if (!allowed) invalidate();
        videoPlaybackAllowed = allowed;
    }

    synchronized boolean isPlaybackDesired() {
        return playbackDesired;
    }

    synchronized long requestPlay() {
        if (stopRequired || !synchronizationEnabled || !videoPlaybackAllowed) return NOT_ALLOWED;
        playbackDesired = true;
        return ++generation;
    }

    synchronized long invalidate() {
        playbackDesired = false;
        return ++generation;
    }

    synchronized long getGeneration() {
        return generation;
    }

    synchronized boolean canPlay(long requestGeneration) {
        return !stopRequired && synchronizationEnabled && videoPlaybackAllowed && playbackDesired
            && requestGeneration == generation;
    }

    synchronized boolean isStopRequired() {
        return stopRequired;
    }

    synchronized void requireConfirmedStop() {
        stopRequired = true;
        invalidate();
    }

    synchronized void confirmStop() {
        stopRequired = false;
    }
}
