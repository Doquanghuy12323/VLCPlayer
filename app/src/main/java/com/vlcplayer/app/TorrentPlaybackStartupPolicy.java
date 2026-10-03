package com.vlcplayer.app;

/** Distinguishes a live torrent failing before playback from a completed video. */
final class TorrentPlaybackStartupPolicy {
    private static final long STARTUP_PROGRESS_LIMIT_MS = 1_000L;

    private TorrentPlaybackStartupPolicy() {}

    static boolean matchesLiveStream(String requestedSession, String liveSession,
                                     String requestedUrl, String liveUrl) {
        return requestedSession != null && !requestedSession.isEmpty()
            && requestedSession.equals(liveSession)
            && requestedUrl != null && !requestedUrl.isEmpty()
            && liveUrl != null && requestedUrl.equals(liveUrl);
    }

    static boolean shouldRecoverUnknownStartupEnd(boolean exactLiveStream,
                                                 long nativeDurationMs,
                                                 long knownNativeDurationMs,
                                                 long maximumNativePositionMs) {
        return exactLiveStream && nativeDurationMs <= 0 && knownNativeDurationMs <= 0
            && maximumNativePositionMs < STARTUP_PROGRESS_LIMIT_MS;
    }
}
