package com.vlcplayer.app;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

import static org.junit.Assert.*;

public class TorrentStorageTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    @Test public void temporaryCleanupLeavesRetainedDownloadsAndSourcesIntact() throws Exception {
        File temporary = folder.newFolder("cache", "torrent_stream");
        File retained = folder.newFolder("files", "torrent_downloads");
        File cacheVideo = write(new File(temporary, "part/video.mp4"), "temporary");
        File durableVideo = write(new File(retained, "source/video.mp4"), "retained");

        assertTrue(TorrentStorage.clearTemporaryDirectory(temporary, retained));
        assertFalse(cacheVideo.exists());
        assertTrue(durableVideo.exists());
        assertEquals("retained", read(durableVideo));
        assertFalse(TorrentStorage.clearTemporaryDirectory(retained, retained));
        assertFalse(TorrentStorage.clearTemporaryDirectory(retained.getParentFile(), retained));
        assertTrue(durableVideo.exists());
    }

    @Test public void fileDeletionRejectsTraversalDirectoriesAndNeighborRoots() throws Exception {
        File retained = folder.newFolder("torrent_downloads");
        File allowed = write(new File(retained, "source/one.mp4"), "one");
        File neighbor = write(new File(retained, "source/two.mp4"), "two");
        File outside = write(new File(folder.getRoot(), "private.txt"), "private");

        assertFalse(TorrentStorage.deleteStoredFile(new File(retained, "../private.txt"), Arrays.asList(retained)));
        assertFalse(TorrentStorage.deleteStoredFile(allowed.getParentFile(), Arrays.asList(retained)));
        assertTrue(TorrentStorage.deleteStoredFile(allowed, Arrays.asList(retained)));
        assertFalse(allowed.exists());
        assertTrue(neighbor.exists());
        assertTrue(outside.exists());
    }

    @Test public void cacheSymlinkDoesNotDeleteRetainedTarget() throws Exception {
        File temporary = folder.newFolder("torrent_stream");
        File retained = folder.newFolder("torrent_downloads");
        File video = write(new File(retained, "movie.mp4"), "retained");
        Files.createSymbolicLink(new File(temporary, "linked").toPath(), retained.toPath());

        assertTrue(TorrentStorage.clearTemporaryDirectory(temporary, retained));
        assertTrue(video.exists());
        assertEquals("retained", read(video));
    }

    @Test public void stableSourceFoldersReuseLocalContentsAndSeparateDifferentSources() throws Exception {
        File retained = folder.newFolder("torrent_downloads");
        File first = write(new File(folder.getRoot(), "picker-one.torrent"), "same metadata");
        File same = write(new File(folder.getRoot(), "picker-two.torrent"), "same metadata");
        File different = write(new File(folder.getRoot(), "picker-three.torrent"), "other metadata");
        File firstDir = TorrentStorage.sourceDirectory(retained, first.getAbsolutePath());

        assertEquals(firstDir, TorrentStorage.sourceDirectory(retained, same.getAbsolutePath()));
        assertNotEquals(firstDir, TorrentStorage.sourceDirectory(retained, different.getAbsolutePath()));
        assertEquals(TorrentStorage.sourceDirectory(retained, " magnet:?xt=urn:btih:one "),
            TorrentStorage.sourceDirectory(retained, "magnet:?xt=urn:btih:one"));
        assertNotEquals(TorrentStorage.sourceDirectory(retained, "magnet:?xt=urn:btih:one"),
            TorrentStorage.sourceDirectory(retained, "magnet:?xt=urn:btih:two"));
        assertTrue(firstDir.getName().matches("[a-f0-9]{64}"));
    }

    @Test public void originalMetadataSurvivesPickerCacheAndResumesInSameFolder() throws Exception {
        File retained = folder.newFolder("torrent_downloads");
        File picked = write(new File(folder.getRoot(), "picked.torrent"), "metadata");
        File sourceDir = TorrentStorage.sourceDirectory(retained, picked.getAbsolutePath());
        assertTrue(sourceDir.mkdirs());
        TorrentStorage.rememberSource(sourceDir, picked.getAbsolutePath());
        File video = write(new File(sourceDir, "nested/movie.mp4"), "partial");
        assertTrue(picked.delete());

        String resumed = TorrentStorage.resumeSource(retained, video);
        assertNotNull(resumed);
        assertEquals("metadata", read(new File(resumed)));
        assertEquals(sourceDir, TorrentStorage.sourceDirectory(retained, resumed));
        assertNull(TorrentStorage.resumeSource(retained, new File(folder.getRoot(), "outside.mp4")));
    }

    @Test public void magnetAndHttpSourcesAreRecoveredWithoutTreatingSparseVideoAsComplete() throws Exception {
        File retained = folder.newFolder("torrent_downloads");
        String source = "magnet:?xt=urn:btih:abcd&dn=Movie";
        File sourceDir = TorrentStorage.sourceDirectory(retained, source);
        assertTrue(sourceDir.mkdirs());
        TorrentStorage.rememberSource(sourceDir, source);
        File partial = write(new File(sourceDir, "Movie/video.mp4"), "partial");

        assertEquals(source, TorrentStorage.resumeSource(retained, partial));
        assertTrue(partial.exists());
    }

    @Test public void storedPlaybackDeletionRequiresAnInternalOneUseToken() throws Exception {
        File retained = folder.newFolder("torrent_downloads");
        File video = write(new File(retained, "source/movie.mp4"), "video");
        File privateFile = write(new File(folder.getRoot(), "private.txt"), "private");
        TorrentStorage.DeletionTokens tokens = new TorrentStorage.DeletionTokens();

        assertNull(tokens.prepare(privateFile, Arrays.asList(retained)));
        assertNull(tokens.take(video.getAbsolutePath()));
        String token = tokens.prepare(video, Arrays.asList(retained));
        assertNotNull(token);
        File authorized = tokens.take(token);
        assertEquals(video, authorized);
        assertTrue(TorrentStorage.deleteStoredFile(authorized, Arrays.asList(retained)));
        assertNull(tokens.take(token));
        assertFalse(video.exists());
        assertTrue(privateFile.exists());
    }

    @Test public void retainedReplacementAllowsOldCleanupButNewTemporaryStreamRevokesIt() throws Exception {
        File temporary = folder.newFolder("torrent_stream");
        File otherVolume = folder.newFolder("external_torrent_stream");
        TorrentStorage.TemporaryOwnership ownership = new TorrentStorage.TemporaryOwnership();
        long first = ownership.claim(temporary);

        // Retained starts claim no temporary root, so ON -> OFF can finish cleanup.
        assertTrue(ownership.isCurrent(temporary, first));
        ownership.claim(otherVolume);
        assertTrue(ownership.isCurrent(temporary, first));
        long replacement = ownership.claim(temporary);
        assertFalse(ownership.isCurrent(temporary, first));
        assertTrue(ownership.isCurrent(temporary, replacement));
    }

    private File write(File file, String value) throws Exception {
        File parent = file.getParentFile();
        if (!parent.isDirectory()) assertTrue(parent.mkdirs());
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(value.getBytes(StandardCharsets.UTF_8));
        }
        return file;
    }

    private String read(File file) throws Exception {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}
