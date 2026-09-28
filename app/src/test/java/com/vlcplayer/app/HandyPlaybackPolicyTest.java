package com.vlcplayer.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class HandyPlaybackPolicyTest {
    @Test
    public void loadedPreferenceStillRequiresVideoPlayback() {
        HandyPlaybackPolicy policy = new HandyPlaybackPolicy(true);
        assertEquals(HandyPlaybackPolicy.NOT_ALLOWED, policy.requestPlay());
        policy.setVideoPlaybackAllowed(true);
        assertTrue(policy.canPlay(policy.requestPlay()));
    }

    @Test
    public void disablingInvalidatesQueuedPlayAndCannotBeUndoneByVideoResume() {
        HandyPlaybackPolicy policy = new HandyPlaybackPolicy(true);
        policy.setVideoPlaybackAllowed(true);
        long queuedPlay = policy.requestPlay();

        policy.setSynchronizationEnabled(false);
        policy.setVideoPlaybackAllowed(false);
        policy.setVideoPlaybackAllowed(true);

        assertFalse(policy.canPlay(queuedPlay));
        assertEquals(HandyPlaybackPolicy.NOT_ALLOWED, policy.requestPlay());
    }

    @Test
    public void enablingDoesNotRevivePreviousPlayOrStartMotion() {
        HandyPlaybackPolicy policy = new HandyPlaybackPolicy(true);
        policy.setVideoPlaybackAllowed(true);
        long previousPlay = policy.requestPlay();
        policy.setSynchronizationEnabled(false);
        policy.setSynchronizationEnabled(true);

        assertFalse(policy.canPlay(previousPlay));
        assertFalse(policy.canPlay(policy.getGeneration()));
        assertTrue(policy.canPlay(policy.requestPlay()));
    }

    @Test
    public void pauseInvalidatesHealthSnapshotEvenAfterVideoResumes() {
        HandyPlaybackPolicy policy = new HandyPlaybackPolicy(true);
        policy.setVideoPlaybackAllowed(true);
        long healthSnapshot = policy.requestPlay();

        policy.setVideoPlaybackAllowed(false);
        policy.setVideoPlaybackAllowed(true);
        long resumedPlay = policy.requestPlay();

        assertFalse(policy.canPlay(healthSnapshot));
        assertTrue(policy.canPlay(resumedPlay));
    }

    @Test
    public void staleStopMayOnlyRecoverTheLatestPlaybackIntent() {
        HandyPlaybackPolicy policy = new HandyPlaybackPolicy(true);
        policy.setVideoPlaybackAllowed(true);
        long firstPlay = policy.requestPlay();
        long stopped = policy.invalidate();
        long latestPlay = policy.requestPlay();

        assertFalse(policy.canPlay(firstPlay));
        assertFalse(policy.canPlay(stopped));
        assertTrue(policy.canPlay(latestPlay));
        policy.setVideoPlaybackAllowed(false);
        assertFalse(policy.canPlay(latestPlay));
    }

    @Test
    public void failedStopBlocksFreshPlayUntilStopIsConfirmed() {
        HandyPlaybackPolicy policy = new HandyPlaybackPolicy(true);
        policy.setVideoPlaybackAllowed(true);
        long originalPlay = policy.requestPlay();
        policy.requireConfirmedStop();

        assertTrue(policy.isStopRequired());
        assertFalse(policy.canPlay(originalPlay));
        assertEquals(HandyPlaybackPolicy.NOT_ALLOWED, policy.requestPlay());

        policy.confirmStop();
        assertFalse(policy.canPlay(originalPlay));
        assertTrue(policy.canPlay(policy.requestPlay()));
    }

    @Test
    public void enablingAfterFailedExplicitStopCannotChangeSavedIntent() {
        HandyPlaybackPolicy policy = new HandyPlaybackPolicy(true);
        policy.setVideoPlaybackAllowed(true);
        policy.setSynchronizationEnabled(false);
        policy.requireConfirmedStop();
        policy.setSynchronizationEnabled(true);

        assertFalse(policy.isSynchronizationEnabled());
        assertEquals(HandyPlaybackPolicy.NOT_ALLOWED, policy.requestPlay());

        policy.confirmStop();
        policy.setSynchronizationEnabled(true);
        assertTrue(policy.isSynchronizationEnabled());
        assertFalse(policy.canPlay(policy.getGeneration()));
    }
}
