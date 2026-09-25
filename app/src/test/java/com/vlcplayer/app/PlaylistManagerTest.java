package com.vlcplayer.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class PlaylistManagerTest {
    private PlaylistManager playlist;

    @Before
    public void setUp() {
        playlist = PlaylistManager.get();
        playlist.clear();
        if (playlist.isShuffle()) playlist.toggleShuffle();
        while (playlist.getRepeatMode() != PlaylistManager.RepeatMode.NONE) {
            playlist.cycleRepeat();
        }
    }

    private VideoItem video(int id) {
        return new VideoItem(id, "Video " + id, 1000, 1000, null, null);
    }

    @Test
    public void shuffleWithRepeatOffVisitsEachVideoOnce() {
        playlist.setQueue(Arrays.asList(video(1), video(2), video(3)), 0);
        playlist.toggleShuffle();

        Set<Long> seen = new HashSet<>();
        seen.add(playlist.getCurrent().getId());
        while (playlist.hasNext()) {
            assertTrue(seen.add(playlist.getNext().getId()));
        }

        assertEquals(3, seen.size());
        assertNull(playlist.getNext());
    }

    @Test
    public void shuffleWithOneVideoDoesNotRepeatUnlessRepeatAll() {
        playlist.setQueue(Arrays.asList(video(1)), 0);
        playlist.toggleShuffle();
        assertFalse(playlist.hasNext());
        assertNull(playlist.getNext());

        playlist.cycleRepeat();
        assertTrue(playlist.hasNext());
        assertEquals(1, playlist.getNext().getId());
    }

    @Test
    public void shufflePreviousReturnsTheVideoActuallyPlayed() {
        playlist.setQueue(Arrays.asList(video(1), video(2), video(3)), 0);
        playlist.toggleShuffle();
        VideoItem firstNext = playlist.getNext();
        assertEquals(1, playlist.getPrev().getId());
        assertEquals(firstNext.getId(), playlist.getNext().getId());
    }

    @Test
    public void replacingQueueDropsOldEntries() {
        playlist.setQueue(Arrays.asList(video(1), video(2)), 0);
        playlist.toggleShuffle();
        playlist.setQueue(Arrays.asList(video(3)), 0);
        assertEquals(3, playlist.getCurrent().getId());
        assertFalse(playlist.hasNext());
    }

    @Test
    public void repeatAllAllowsPreviousToWrapFromFirstVideo() {
        playlist.setQueue(Arrays.asList(video(1), video(2)), 0);
        playlist.cycleRepeat();
        assertTrue(playlist.hasPrev());
        assertEquals(2, playlist.getPrev().getId());
    }
}
