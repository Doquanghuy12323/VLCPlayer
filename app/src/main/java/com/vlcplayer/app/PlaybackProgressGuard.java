package com.vlcplayer.app;

/** Gates device synchronization using observed media time, never an estimated playback clock. */
public final class PlaybackProgressGuard {
    private static final long MIN_PROGRESS_MS = 100;
    private static final long STALL_TIMEOUT_MS = 2000;
    private static final long SEEK_TOLERANCE_MS = 1000;

    private boolean initialized;
    private boolean requiresProgress;
    private boolean allowed;
    private long progressPositionMs;
    private long progressElapsedMs;
    private long lastUpdateElapsedMs;
    private long lastObservedPositionMs = -1;
    private boolean awaitingSeekTarget;
    private long seekTargetMs;
    private long seekStartedElapsedMs;
    private long seekToleranceMs;

    /**
     * Starts a short grace period for a new video or an explicit seek. Raw media time must
     * reach the target before sync is allowed; a pause, buffer, or unknown position cancels
     * permission. If the target is never confirmed, progress after the grace is required.
     */
    public synchronized void reset(long rawPositionMs, long elapsedRealtimeMs) {
        allowed = false;
        initialized = rawPositionMs >= 0 && elapsedRealtimeMs >= 0;
        requiresProgress = !initialized;
        awaitingSeekTarget = initialized;
        if (initialized) {
            seekTargetMs = rawPositionMs;
            seekStartedElapsedMs = elapsedRealtimeMs;
            seekToleranceMs = SEEK_TOLERANCE_MS;
            if (lastObservedPositionMs >= 0 && lastObservedPositionMs != rawPositionMs) {
                long distance = rawPositionMs >= lastObservedPositionMs
                    ? rawPositionMs - lastObservedPositionMs : lastObservedPositionMs - rawPositionMs;
                // Even a short seek must not accept the unchanged pre-seek position.
                seekToleranceMs = Math.min(SEEK_TOLERANCE_MS, distance / 2);
            }
            setBaseline(rawPositionMs, elapsedRealtimeMs);
        }
    }

    /**
     * Call with VLC's raw media position and a monotonic clock. At least 100ms of forward
     * movement refreshes the two-second deadline. After an explicit block, fresh progress
     * must be observed before sync can resume.
     */
    public synchronized boolean update(long rawPositionMs, long elapsedRealtimeMs,
                                       boolean videoPlaying, boolean buffering) {
        if (elapsedRealtimeMs < 0 || (initialized && elapsedRealtimeMs < lastUpdateElapsedMs)) {
            awaitingSeekTarget = false;
            block(rawPositionMs, elapsedRealtimeMs);
            return false;
        }
        if (rawPositionMs < 0 || !videoPlaying || buffering) {
            block(rawPositionMs, elapsedRealtimeMs);
            return false;
        }
        lastObservedPositionMs = rawPositionMs;
        if (awaitingSeekTarget) {
            return updatePendingSeek(rawPositionMs, elapsedRealtimeMs);
        }
        if (!initialized) {
            initialized = true;
            setBaseline(rawPositionMs, elapsedRealtimeMs);
            allowed = !requiresProgress;
            return allowed;
        }

        lastUpdateElapsedMs = elapsedRealtimeMs;
        if (rawPositionMs < progressPositionMs) {
            // Unannounced backward jumps must establish progress; explicit seeks use reset().
            block(rawPositionMs, elapsedRealtimeMs);
            return false;
        }
        if (rawPositionMs - progressPositionMs >= MIN_PROGRESS_MS) {
            setBaseline(rawPositionMs, elapsedRealtimeMs);
            requiresProgress = false;
            allowed = true;
        } else {
            allowed = !requiresProgress && elapsedRealtimeMs - progressElapsedMs < STALL_TIMEOUT_MS;
        }
        return allowed;
    }

    public synchronized boolean isAllowed() {
        return allowed;
    }

    private boolean updatePendingSeek(long rawPositionMs, long elapsedRealtimeMs) {
        lastUpdateElapsedMs = elapsedRealtimeMs;
        long distance = rawPositionMs >= seekTargetMs
            ? rawPositionMs - seekTargetMs : seekTargetMs - rawPositionMs;
        boolean withinGrace = elapsedRealtimeMs - seekStartedElapsedMs < STALL_TIMEOUT_MS;
        if (distance <= seekToleranceMs) {
            awaitingSeekTarget = false;
            initialized = true;
            setBaseline(rawPositionMs, elapsedRealtimeMs);
            requiresProgress = requiresProgress || !withinGrace;
            // Confirming the target does not extend the initial two-second grace.
            progressElapsedMs = seekStartedElapsedMs;
            allowed = !requiresProgress;
        } else if (!withinGrace) {
            // Some VLC/keyframe seeks land away from the target. Establish a baseline,
            // but this sample is never evidence of forward playback by itself.
            awaitingSeekTarget = false;
            initialized = true;
            requiresProgress = true;
            setBaseline(rawPositionMs, elapsedRealtimeMs);
            allowed = false;
        } else {
            allowed = false;
        }
        return allowed;
    }

    private void block(long rawPositionMs, long elapsedRealtimeMs) {
        allowed = false;
        requiresProgress = true;
        initialized = rawPositionMs >= 0 && elapsedRealtimeMs >= 0;
        if (initialized) {
            lastObservedPositionMs = rawPositionMs;
            setBaseline(rawPositionMs, elapsedRealtimeMs);
        }
    }

    private void setBaseline(long rawPositionMs, long elapsedRealtimeMs) {
        progressPositionMs = rawPositionMs;
        progressElapsedMs = elapsedRealtimeMs;
        lastUpdateElapsedMs = elapsedRealtimeMs;
    }
}
