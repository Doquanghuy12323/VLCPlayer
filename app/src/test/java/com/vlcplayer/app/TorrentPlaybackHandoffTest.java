package com.vlcplayer.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class TorrentPlaybackHandoffTest {
    private static final String SESSION = "session-one";
    private static final String URL = "http://127.0.0.1:4321/stream";

    @Test public void readyWhileResumedAutomaticallyLaunchesExactlyOnce() {
        TorrentPlaybackHandoff policy = new TorrentPlaybackHandoff();
        policy.begin(SESSION, true);
        policy.setResumed(true);
        assertTrue(policy.ready(SESSION, URL));
        assertEquals(URL, policy.reserveLaunch(SESSION, true));
        policy.completeLaunch(true);
        assertFalse(policy.ready(SESSION, URL));
        assertNull(policy.reserveLaunch(SESSION, true));
    }

    @Test public void backgroundReadinessWaitsForExplicitWatchOnReturn() {
        TorrentPlaybackHandoff policy = new TorrentPlaybackHandoff();
        policy.begin(SESSION, true);
        assertFalse(policy.ready(SESSION, URL));
        assertNull(policy.reserveLaunch(SESSION, false));
        policy.setResumed(true);
        assertFalse(policy.ready(SESSION, URL));
        assertNull(policy.reserveLaunch(SESSION, true));
        assertEquals(URL, policy.reserveLaunch(SESSION, false));
    }

    @Test public void staleSessionCannotPublishOrLaunchReadyUrl() {
        TorrentPlaybackHandoff policy = new TorrentPlaybackHandoff();
        policy.begin(SESSION, true);
        policy.setResumed(true);
        assertFalse(policy.ready("old-session", URL));
        assertFalse(policy.canWatch(SESSION));
        policy.ready(SESSION, URL);
        assertNull(policy.reserveLaunch("replacement-session", false));
    }

    @Test public void stopAndReplacementInvalidateOldReadiness() {
        TorrentPlaybackHandoff policy = new TorrentPlaybackHandoff();
        policy.begin(SESSION, true);
        policy.ready(SESSION, URL);
        policy.invalidate();
        policy.setResumed(true);
        assertFalse(policy.ready(SESSION, URL));
        assertFalse(policy.canWatch(SESSION));
        policy.begin("session-two", true);
        assertFalse(policy.ready(SESSION, URL));
        assertFalse(policy.canWatch("session-two"));
    }

    @Test public void restoredLiveSessionNeedsManagerReadinessAndManualWatch() {
        TorrentPlaybackHandoff policy = new TorrentPlaybackHandoff();
        policy.restoreLiveSession(SESSION);
        policy.setResumed(true);
        assertFalse(policy.canWatch(SESSION));
        assertFalse(policy.ready(SESSION, URL));
        assertNull(policy.reserveLaunch(SESSION, true));
        assertEquals(URL, policy.reserveLaunch(SESSION, false));
        policy.restoreLiveSession(null);
        assertFalse(policy.ready(SESSION, URL));
        assertFalse(policy.canWatch(SESSION));
    }

    @Test public void failedLaunchCanBeRetriedManuallyWithoutAutomaticLoop() {
        TorrentPlaybackHandoff policy = new TorrentPlaybackHandoff();
        policy.begin(SESSION, true);
        policy.setResumed(true);
        policy.ready(SESSION, URL);
        assertEquals(URL, policy.reserveLaunch(SESSION, true));
        policy.completeLaunch(false);
        assertNull(policy.reserveLaunch(SESSION, true));
        assertEquals(URL, policy.reserveLaunch(SESSION, false));
    }

    @Test public void doubleTapsAreBlockedUntilPlayerReturns() {
        TorrentPlaybackHandoff policy = new TorrentPlaybackHandoff();
        policy.begin(SESSION, false);
        policy.setResumed(true);
        policy.ready(SESSION, URL);
        assertEquals(URL, policy.reserveLaunch(SESSION, false));
        assertNull(policy.reserveLaunch(SESSION, false));
        policy.completeLaunch(true);
        assertNull(policy.reserveLaunch(SESSION, false));
        policy.setResumed(false);
        policy.setResumed(true);
        assertEquals(URL, policy.reserveLaunch(SESSION, false));
    }
}
