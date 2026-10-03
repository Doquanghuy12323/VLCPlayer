package com.vlcplayer.app;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

public class TorrentStreamPolicyTest {
    private static final long MIB = 1024L * 1024L;

    @Test public void startupUsesBytesInsteadOfAConstantNumberOfPieces() {
        TorrentStreamPolicy policy = new TorrentStreamPolicy(0, 100 * MIB, 256 * 1024);
        Set<Integer> pieces = policy.startupPieces();
        assertEquals(40, pieces.size());
        assertTrue(pieces.contains(0));
        assertTrue(pieces.contains(31));
        assertFalse(pieces.contains(32));
        assertTrue(pieces.contains(392));
        assertTrue(pieces.contains(399));
    }

    @Test public void smallFileIsCappedAndHeadTailOverlapIsDeduplicated() {
        TorrentStreamPolicy policy = new TorrentStreamPolicy(0, 3 * MIB, (int) MIB);
        assertEquals(new HashSet<>(Arrays.asList(0, 1, 2)), policy.startupPieces());
        assertEquals(new HashSet<>(Arrays.asList(2)), policy.requestPieces(2 * MIB, 3 * MIB - 1));
    }

    @Test public void unalignedFileIncludesBoundaryPiecesAndReadsNeverCrossThem() {
        TorrentStreamPolicy policy = new TorrentStreamPolicy(1000, 1200, 1024);
        assertEquals(new HashSet<>(Arrays.asList(0, 1, 2)), policy.startupPieces());
        assertEquals(0, policy.pieceAt(0));
        assertEquals(1, policy.pieceAt(24));
        assertEquals(2, policy.pieceAt(1199));
        assertEquals(24, policy.readLength(0, 1200, 65536));
        assertEquals(1024, policy.readLength(24, 1176, 65536));
        assertEquals(152, policy.readLength(1048, 152, 65536));
    }

    @Test public void readinessRequiresHeadTailAndReadablePhysicalFile() {
        TorrentStreamPolicy policy = new TorrentStreamPolicy(0, 100 * MIB, (int) MIB);
        Set<Integer> available = new HashSet<>(policy.startupPieces());
        assertFalse(policy.ready(available::contains, false));
        available.remove(99);
        assertFalse(policy.ready(available::contains, true));
        available.add(99);
        assertTrue(policy.ready(available::contains, true));
        available.remove(3);
        assertTrue(available.contains(0));
        assertTrue(available.contains(99));
        assertFalse(policy.ready(available::contains, true));
    }

    @Test public void rollingWindowMovesAfterSeekAndStopsAtTheRequestedEnd() {
        TorrentStreamPolicy policy = new TorrentStreamPolicy(0, 100 * MIB, (int) MIB);
        Set<Integer> before = policy.requestPieces(0, 100 * MIB - 1);
        Set<Integer> after = policy.requestPieces(50 * MIB, 100 * MIB - 1);
        assertEquals(8, before.size());
        assertEquals(8, after.size());
        assertFalse(after.contains(0));
        assertTrue(after.contains(50));
        assertTrue(after.contains(57));
        assertFalse(after.contains(58));
        assertEquals(new HashSet<>(Arrays.asList(50, 51)),
            policy.requestPieces(50 * MIB, 52 * MIB - 1));
    }

    @Test public void largeOffsetsAndPartialLastPieceKeepCorrectIndexes() {
        TorrentStreamPolicy policy = new TorrentStreamPolicy(5L * 1024 * MIB + 100,
            4 * MIB + 7, (int) MIB);
        assertEquals(5120, policy.firstPiece);
        assertEquals(5124, policy.lastPiece);
        assertEquals(7, policy.readLength(4 * MIB, 7, 65536));
    }

    @Test(expected = IllegalArgumentException.class)
    public void overflowingTorrentGeometryIsRejected() {
        new TorrentStreamPolicy(Long.MAX_VALUE - 10, 100, 16384);
    }

    @Test(expected = IllegalArgumentException.class)
    public void invalidRangeCannotProduceAPieceIndex() {
        new TorrentStreamPolicy(0, 100, 16384).requestPieces(100, 100);
    }
}
