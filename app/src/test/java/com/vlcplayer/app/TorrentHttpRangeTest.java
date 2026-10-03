package com.vlcplayer.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class TorrentHttpRangeTest {
    @Test public void noRangeReportsFullMetadata() {
        assertRange(null, 1000, false, 200, 0, 999, 1000);
        assertRange(null, 0, false, 200, 0, -1, 0);
    }

    @Test public void validRangesRemainPartialEvenWhenTheyCoverTheWholeFile() {
        assertRange("bytes=0-", 1000, false, 206, 0, 999, 1000);
        assertRange("bytes=0-999", 1000, false, 206, 0, 999, 1000);
        assertRange("bytes=300-", 1000, false, 206, 300, 999, 700);
        assertRange("bytes=400-500", 1000, false, 206, 400, 500, 101);
        assertRange("bytes=999-2000", 1000, false, 206, 999, 999, 1);
    }

    @Test public void suffixRangesProbeTheEndOfTheVideo() {
        assertRange("bytes=-500", 1000, false, 206, 500, 999, 500);
        assertRange("bytes=-2000", 1000, false, 206, 0, 999, 1000);
        assertRange("bytes=-1", 1000, false, 206, 999, 999, 1);
    }

    @Test public void invalidRangesCannotBecomeNativePieceIndices() {
        String[] invalid = {"bytes=1000-", "bytes=10-9", "bytes=-0", "bytes=-",
            "bytes=", "bytes=1", "bytes=1--2", "bytes=+1-2", "bytes=1-+2",
            "bytes=1 -2", "bytes=a-b", "bytes=0-1,3-4", "",
            "bytes=9223372036854775808-"};
        for (String header : invalid) {
            TorrentHttpRange.Result result = TorrentHttpRange.parse(header, 1000, false);
            assertEquals(header, 416, result.statusCode);
            assertFalse(header, result.satisfiable());
            assertEquals(header, 0, result.length);
        }
        assertEquals(416, TorrentHttpRange.parse("bytes=0-", 0, false).statusCode);
    }

    @Test public void headIgnoresRangeAndNeedsNoDataOrPiecePlan() {
        assertRange("bytes=-500", 1000, true, 200, 0, 999, 1000);
        assertRange("bytes=garbage", 1000, true, 200, 0, 999, 1000);
        assertRange("bytes=0-", 0, true, 200, 0, -1, 0);
    }

    @Test public void largeFilesAndOverflowSafeLengths() {
        assertRange(null, Long.MAX_VALUE, false, 200, 0, Long.MAX_VALUE - 1, Long.MAX_VALUE);
        assertRange("bytes=9223372036854775806-9223372036854775807", Long.MAX_VALUE,
            false, 206, Long.MAX_VALUE - 1, Long.MAX_VALUE - 1, 1);
        assertRange("bytes=-9223372036854775807", Long.MAX_VALUE, false,
            206, 0, Long.MAX_VALUE - 1, Long.MAX_VALUE);
        assertRange("bytes=0-999999999999999999999999", 1000, false, 206, 0, 999, 1000);
        assertRange("bytes=-999999999999999999999999", 1000, false, 206, 0, 999, 1000);
    }

    @Test public void surroundingWhitespaceAndUnitCaseAreAccepted() {
        assertRange("  BYTES=5-10  ", 20, false, 206, 5, 10, 6);
        assertRange("items=0-1", 20, false, 200, 0, 19, 20);
    }

    private static void assertRange(String header, long size, boolean head, int code,
            long start, long end, long length) {
        TorrentHttpRange.Result result = TorrentHttpRange.parse(header, size, head);
        assertTrue(result.satisfiable());
        assertEquals(code, result.statusCode);
        assertEquals(start, result.start);
        assertEquals(end, result.end);
        assertEquals(length, result.length);
    }
}
