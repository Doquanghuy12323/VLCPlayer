package com.vlcplayer.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class LanSharingStateTest {
    private static final String VIDEO = "content://videos/1";

    @Test public void selectingAndPreparingNeverStartsSharing() {
        LanSharingState state = new LanSharingState();
        assertFalse(state.snapshot().canStart());
        long selection = state.selectVideo(VIDEO);
        assertEquals(LanSharingState.Phase.PREPARING, state.snapshot().phase);
        assertTrue(state.snapshot().canStop());
        assertFalse(state.snapshot().canStart());
        assertTrue(state.prepared(selection, "one.mp4", 100));
        assertEquals(LanSharingState.Phase.READY, state.snapshot().phase);
        assertTrue(state.snapshot().canStart());
        assertEquals("", state.snapshot().lanUrl);
        assertFalse(state.serverStarted(selection, "http://lan/old"));
    }

    @Test public void stopDuringPreparationRejectsItsCompletionAndError() {
        LanSharingState state = new LanSharingState();
        long old = state.selectVideo(VIDEO);
        state.stop();
        assertFalse(state.prepared(old, "old.mp4", 100));
        assertFalse(state.failed(old, LanSharingState.Error.SOURCE_UNAVAILABLE));
        assertEquals(LanSharingState.Phase.STOPPED, state.snapshot().phase);
        assertEquals("", state.snapshot().lanUrl);
    }

    @Test public void stoppedStartupCannotPublishLateUrlOrClientCallbacks() {
        LanSharingState state = readyState();
        long old = state.start();
        state.stop();
        assertFalse(state.serverStarted(old, "http://lan/old"));
        assertFalse(state.clientConnected(old, "192.168.1.4"));
        assertFalse(state.clientDisconnected(old));
        assertFalse(state.failed(old, LanSharingState.Error.SERVER_FAILED));
        assertFalse(state.serverStopped(old));
        assertEquals(LanSharingState.Phase.STOPPED, state.snapshot().phase);
        assertEquals("", state.snapshot().lanUrl);
    }

    @Test public void replacingSelectionRejectsOldMetadata() {
        LanSharingState state = new LanSharingState();
        long old = state.selectVideo(VIDEO);
        long replacement = state.selectVideo("content://videos/2");
        assertFalse(state.prepared(old, "old.mp4", 100));
        assertTrue(state.prepared(replacement, "new.mp4", 200));
        assertEquals("content://videos/2", state.snapshot().videoUri);
        assertEquals("new.mp4", state.snapshot().videoName);
        assertEquals(200, state.snapshot().videoSize);
    }

    @Test public void activeSessionRetainsSnapshotAndRequiresStopBeforeNewSelection() {
        LanSharingState state = readyState();
        long session = state.start();
        assertFalse(state.snapshot().canPickVideo());
        assertEquals(-1, state.selectVideo("content://videos/2"));
        assertTrue(state.serverStarted(session, "http://lan/current"));
        assertEquals(LanSharingState.Phase.RUNNING, state.snapshot().phase);
        assertEquals("http://lan/current", state.snapshot().lanUrl);
        assertFalse(state.snapshot().canStart());
        assertTrue(state.clientConnected(session, "192.168.1.4"));
        assertEquals("http://lan/current", state.snapshot().lanUrl);
        state.stop();
        assertEquals("", state.snapshot().lanUrl);
        assertTrue(state.snapshot().canPickVideo());
    }

    @Test public void restoredSelectionDoesNotRestoreServerOrOverwriteLiveSession() {
        LanSharingState ready = new LanSharingState();
        ready.restoreSelection(VIDEO, "one.mp4", 100, false);
        assertEquals(LanSharingState.Phase.READY, ready.snapshot().phase);
        assertEquals("", ready.snapshot().lanUrl);
        LanSharingState state = new LanSharingState();
        state.restoreSelection(VIDEO, "one.mp4", 100, true);
        assertEquals(LanSharingState.Phase.STOPPED, state.snapshot().phase);
        assertEquals("", state.snapshot().lanUrl);
        assertTrue(state.snapshot().canStart());
        long session = state.start();
        state.serverStarted(session, "http://lan/live");
        state.restoreSelection("content://videos/old", "old.mp4", 1, true);
        assertEquals("http://lan/live", state.snapshot().lanUrl);
        assertEquals(VIDEO, state.snapshot().videoUri);
    }

    @Test public void errorClearsUrlAndRetryRequiresExplicitStart() {
        LanSharingState state = readyState();
        long old = state.start();
        state.serverStarted(old, "http://lan/old");
        assertTrue(state.failed(old, LanSharingState.Error.NETWORK_UNAVAILABLE));
        assertEquals(LanSharingState.Phase.ERROR, state.snapshot().phase);
        assertEquals("", state.snapshot().lanUrl);
        assertTrue(state.snapshot().canStart());
        long retry = state.start();
        assertFalse(state.serverStarted(old, "http://lan/old"));
        assertFalse(state.failed(old, LanSharingState.Error.SERVER_FAILED));
        assertTrue(state.serverStarted(retry, "http://lan/new"));
        assertEquals("http://lan/new", state.snapshot().lanUrl);
    }

    @Test public void unknownSizeReportsErrorAndUnexpectedStopCannotRestart() {
        LanSharingState state = new LanSharingState();
        state.prepared(state.selectVideo(VIDEO), "one.mp4", -1);
        assertEquals(LanSharingState.Error.SIZE_UNKNOWN, state.snapshot().error);
        long session = state.start();
        state.serverStarted(session, "http://lan/current");
        assertTrue(state.serverStopped(session));
        assertFalse(state.serverStarted(session, "http://lan/late"));
        assertEquals(LanSharingState.Phase.STOPPED, state.snapshot().phase);
    }

    private static LanSharingState readyState() {
        LanSharingState state = new LanSharingState();
        state.prepared(state.selectVideo(VIDEO), "one.mp4", 100);
        return state;
    }
}
