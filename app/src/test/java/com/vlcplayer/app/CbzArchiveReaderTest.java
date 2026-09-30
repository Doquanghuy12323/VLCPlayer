package com.vlcplayer.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class CbzArchiveReaderTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void extractsNestedImagePagesInNaturalOrder() throws Exception {
        File session = temporary.newFolder("pages");
        List<File> pages = CbzArchiveReader.read(archive(
                "chapter/10.PNG", "chapter/2.jpg", "chapter/1.webp", "notes.txt"),
                session, () -> false);
        assertEquals(3, pages.size());
        assertEquals("1.webp", pages.get(0).getName());
        assertEquals("2.jpg", pages.get(1).getName());
        assertEquals("10.PNG", pages.get(2).getName());
        assertTrue(pages.get(0).isFile());
        assertFalse(new File(session, "notes.txt").exists());
    }

    @Test public void traversalCannotWriteOutsideItsSession() throws Exception {
        File session = temporary.newFolder("session");
        assertThrows(IOException.class, () -> CbzArchiveReader.read(
                archive("../escaped.jpg"), session, () -> false));
        assertFalse(new File(temporary.getRoot(), "escaped.jpg").exists());
    }

    @Test public void normalizedDuplicatePagesAreRejected() throws Exception {
        File session = temporary.newFolder("session");
        assertThrows(IOException.class, () -> CbzArchiveReader.read(
                archive("same.png", "nested/../same.png"), session, () -> false));
    }

    @Test public void unpackedBudgetIncludesNonImageEntries() throws Exception {
        File session = temporary.newFolder("session");
        assertThrows(IOException.class, () -> CbzArchiveReader.read(
                archive("metadata.txt", "1.jpg"), session, () -> false, 2000, 3));
    }

    @Test public void pageLimitStopsArchiveAndOnlyDiscardedSessionIsDeleted() throws Exception {
        File first = temporary.newFolder("first-session");
        File other = temporary.newFolder("other-session");
        File otherPage = new File(other, "active.png");
        try (FileOutputStream output = new FileOutputStream(otherPage)) {
            output.write(1);
        }
        assertThrows(IOException.class, () -> CbzArchiveReader.read(
                archive("1.jpg", "2.jpg"), first, () -> false, 1, 100));
        CbzArchiveReader.deleteSession(first);
        assertFalse(first.exists());
        assertTrue(otherPage.exists());
    }

    @Test public void cancellationDuringExtractionClosesZipStream() throws Exception {
        File session = temporary.newFolder("session");
        AtomicInteger checks = new AtomicInteger();
        AtomicBoolean closed = new AtomicBoolean();
        ByteArrayInputStream zip = archive("1.jpg");
        ByteArrayInputStream input = new ByteArrayInputStream(readBytes(zip)) {
            @Override public void close() throws IOException {
                closed.set(true);
                super.close();
            }
        };
        assertThrows(InterruptedIOException.class, () -> CbzArchiveReader.read(
                input, session, () -> checks.incrementAndGet() > 2));
        assertTrue(closed.get());
    }

    @Test public void archiveWithoutImagesHasNoPages() throws Exception {
        assertTrue(CbzArchiveReader.read(archive("notes.txt"),
                temporary.newFolder("session"), () -> false).isEmpty());
    }

    private static ByteArrayInputStream archive(String... names) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (String name : names) {
                zip.putNextEntry(new ZipEntry(name));
                zip.write(new byte[] {1, 2, 3, 4});
                zip.closeEntry();
            }
        }
        return new ByteArrayInputStream(bytes.toByteArray());
    }

    private static byte[] readBytes(ByteArrayInputStream input) {
        byte[] bytes = new byte[input.available()];
        input.read(bytes, 0, bytes.length);
        return bytes;
    }
}
