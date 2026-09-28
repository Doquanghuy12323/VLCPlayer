package com.vlcplayer.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PlaybackProgressGuardTest {
    @Test
    public void rawProgressRefreshesDeadline() {
        PlaybackProgressGuard guard = new PlaybackProgressGuard();
        assertFalse(guard.isAllowed());
        assertTrue(guard.update(1000, 0, true, false));
        assertTrue(guard.update(1500, 500, true, false));
        assertTrue(guard.update(2000, 1000, true, false));
        assertTrue(guard.update(2000, 2999, true, false));
        assertFalse(guard.update(2000, 3000, true, false));
    }

    @Test
    public void elapsedClockCannotHideStalledRawPosition() {
        PlaybackProgressGuard guard = new PlaybackProgressGuard();
        assertTrue(guard.update(1000, 100, true, false));
        assertTrue(guard.update(1000, 600, true, false));
        assertTrue(guard.update(1000, 1600, true, false));
        assertFalse(guard.update(1000, 2100, true, false));
        assertFalse(guard.update(1000, 5000, true, false));
        assertTrue(guard.update(1100, 5500, true, false));
    }

    @Test
    public void tinyPositionChangesAccumulateButDoNotRefreshUntilThreshold() {
        PlaybackProgressGuard guard = new PlaybackProgressGuard();
        assertTrue(guard.update(1000, 0, true, false));
        assertTrue(guard.update(1040, 1500, true, false));
        assertFalse(guard.update(1099, 2000, true, false));
        assertTrue(guard.update(1100, 2500, true, false));
    }

    @Test
    public void bufferingBlocksEvenWhileRawPositionMoves() {
        PlaybackProgressGuard guard = new PlaybackProgressGuard();
        assertTrue(guard.update(1000, 0, true, false));
        assertFalse(guard.update(1500, 500, true, true));
        assertFalse(guard.update(2000, 1000, true, true));
        assertFalse(guard.update(2000, 1500, true, false));
        assertTrue(guard.update(2100, 2000, true, false));
    }

    @Test
    public void pausedPlaybackRequiresFreshProgressBeforeResuming() {
        PlaybackProgressGuard guard = new PlaybackProgressGuard();
        assertTrue(guard.update(1000, 0, true, false));
        assertFalse(guard.update(1000, 500, false, false));
        assertFalse(guard.update(1000, 1000, true, false));
        assertTrue(guard.update(1100, 1500, true, false));
    }

    @Test
    public void unknownPositionCancelsGraceAndRequiresFreshProgress() {
        PlaybackProgressGuard guard = new PlaybackProgressGuard();
        assertFalse(guard.update(-1, 0, true, false));
        assertFalse(guard.update(1000, 500, true, false));
        assertFalse(guard.update(1099, 1000, true, false));
        assertTrue(guard.update(1100, 1500, true, false));
        assertFalse(guard.update(-1, 2000, true, false));
    }

    @Test
    public void resetWithUnknownPositionCannotGrantStartupGrace() {
        PlaybackProgressGuard guard = new PlaybackProgressGuard();
        guard.reset(-1, 0);
        assertFalse(guard.update(1000, 500, true, false));
        assertTrue(guard.update(1100, 1000, true, false));
    }

    @Test
    public void explicitSeekGetsBoundedGraceAndStillHonorsPause() {
        PlaybackProgressGuard guard = new PlaybackProgressGuard();
        guard.update(20000, 0, true, false);
        guard.reset(5000, 500);
        assertFalse(guard.isAllowed());
        assertTrue(guard.update(5000, 1000, true, false));
        assertFalse(guard.update(5000, 2500, true, false));
        guard.reset(3000, 3000);
        assertFalse(guard.update(3000, 3000, false, false));
        assertFalse(guard.update(3000, 3500, true, false));
        assertTrue(guard.update(3100, 4000, true, false));
    }

    @Test
    public void backwardSeekDoesNotTreatStaleRawPositionAsProgress() {
        PlaybackProgressGuard guard = new PlaybackProgressGuard();
        guard.update(20000, 0, true, false);
        guard.reset(5000, 500);
        assertFalse(guard.update(20000, 500, true, false));
        assertFalse(guard.update(20500, 1000, true, false));
        assertTrue(guard.update(5000, 1500, true, false));
        assertTrue(guard.update(5500, 2000, true, false));
    }

    @Test
    public void forwardSeekWaitsForRawPositionToReachTarget() {
        PlaybackProgressGuard guard = new PlaybackProgressGuard();
        guard.update(5000, 0, true, false);
        guard.reset(20000, 500);
        assertFalse(guard.update(5000, 500, true, false));
        assertFalse(guard.update(5500, 1000, true, false));
        assertTrue(guard.update(20000, 1500, true, false));
    }

    @Test
    public void shortSeekCannotConfirmFromUnchangedPreSeekPosition() {
        PlaybackProgressGuard guard = new PlaybackProgressGuard();
        guard.update(5200, 0, true, false);
        guard.reset(5000, 500);
        assertFalse(guard.update(5200, 500, true, false));
        assertTrue(guard.update(5000, 1000, true, false));
    }

    @Test
    public void offTargetLandingAfterSettleNeedsAnotherProgressSample() {
        PlaybackProgressGuard guard = new PlaybackProgressGuard();
        guard.update(20000, 0, true, false);
        guard.reset(5000, 500);
        assertFalse(guard.update(20000, 1000, true, false));
        assertFalse(guard.update(8000, 2500, true, false));
        assertFalse(guard.update(8000, 3000, true, false));
        assertFalse(guard.update(8099, 3500, true, false));
        assertTrue(guard.update(8100, 4000, true, false));
    }

    @Test
    public void pausedSeekPreservesTargetGateWhenPlaybackResumes() {
        PlaybackProgressGuard guard = new PlaybackProgressGuard();
        guard.update(20000, 0, true, false);
        guard.reset(5000, 500);
        assertFalse(guard.update(20000, 500, false, false));
        assertFalse(guard.update(20000, 1000, true, false));
        assertFalse(guard.update(5000, 1500, true, false));
        assertTrue(guard.update(5100, 2000, true, false));
    }

    @Test
    public void unannouncedBackwardJumpOrClockRegressionBlocksUntilProgress() {
        PlaybackProgressGuard guard = new PlaybackProgressGuard();
        assertTrue(guard.update(2000, 1000, true, false));
        assertFalse(guard.update(1000, 1500, true, false));
        assertTrue(guard.update(1100, 2000, true, false));
        assertFalse(guard.update(1200, 1000, true, false));
        assertFalse(guard.update(1200, 1500, true, false));
        assertTrue(guard.update(1300, 2000, true, false));
    }
}
