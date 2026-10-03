package com.vlcplayer.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class TorrentPlaybackStartupPolicyTest {
    private static final String SESSION = "torrent-session";
    private static final String URL = "http://127.0.0.1:4321/stream";

    @Test public void liveTorrentWithoutDurationRecoversBeforePlaybackProgress() {
        assertTrue(TorrentPlaybackStartupPolicy.shouldRecoverUnknownStartupEnd(
            matches(SESSION, URL), -1, -1, 0));
        assertTrue(TorrentPlaybackStartupPolicy.shouldRecoverUnknownStartupEnd(
            matches(SESSION, URL), 0, -1, 999));
    }

    @Test public void missingOrReplacementSessionDoesNotRecoverStartupEnd() {
        assertFalse(TorrentPlaybackStartupPolicy.shouldRecoverUnknownStartupEnd(
            matches(null, URL), -1, -1, 0));
        assertFalse(TorrentPlaybackStartupPolicy.shouldRecoverUnknownStartupEnd(
            matches("replacement-session", URL), -1, -1, 0));
        assertFalse(TorrentPlaybackStartupPolicy.matchesLiveStream(null, null, URL, URL));
    }

    @Test public void oldOrUnavailableProxyUrlDoesNotMatchTheLiveStream() {
        assertFalse(matches(SESSION, "http://127.0.0.1:9876/stream"));
        assertFalse(matches(SESSION, null));
        assertFalse(TorrentPlaybackStartupPolicy.matchesLiveStream(SESSION, SESSION, null, null));
        assertFalse(TorrentPlaybackStartupPolicy.matchesLiveStream(SESSION, SESSION, "", ""));
    }

    @Test public void knownNativeDurationKeepsNormalCompletionDecision() {
        assertFalse(TorrentPlaybackStartupPolicy.shouldRecoverUnknownStartupEnd(
            true, 120_000, -1, 0));
        assertFalse(TorrentPlaybackStartupPolicy.shouldRecoverUnknownStartupEnd(
            true, -1, 120_000, 0));
    }

    @Test public void unknownDurationAfterObservedPlaybackIsNotStartupFailure() {
        assertFalse(TorrentPlaybackStartupPolicy.shouldRecoverUnknownStartupEnd(
            true, -1, -1, 1_000));
        // Keep the maximum observed native time even when VLC reports -1 at EOF.
        assertFalse(TorrentPlaybackStartupPolicy.shouldRecoverUnknownStartupEnd(
            true, -1, -1, 120_000));
    }

    private static boolean matches(String liveSession, String liveUrl) {
        return TorrentPlaybackStartupPolicy.matchesLiveStream(SESSION, liveSession, URL, liveUrl);
    }
}
