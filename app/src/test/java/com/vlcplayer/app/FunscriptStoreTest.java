package com.vlcplayer.app;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class FunscriptStoreTest {
    private static final byte[] OLD_SCRIPT = "previous valid script".getBytes(StandardCharsets.UTF_8);

    @Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void emptyImportPreservesPreviousScriptAndClosesSource() throws Exception {
        File destination = savedScript();
        TrackedInput source = new TrackedInput(new byte[0]);

        assertThrows(IOException.class,
            () -> FunscriptStore.importScript(source, destination, file -> { }));

        assertTrue(source.closed);
        assertPreviousScriptIsOnlyFile(destination);
    }

    @Test
    public void oversizedImportPreservesPreviousScriptAndClosesSource() throws Exception {
        File destination = savedScript();
        TrackedInput source = new TrackedInput(new byte[FunscriptStore.MAX_SCRIPT_BYTES + 1]);

        assertThrows(IOException.class,
            () -> FunscriptStore.importScript(source, destination, file -> { }));

        assertTrue(source.closed);
        assertPreviousScriptIsOnlyFile(destination);
    }

    @Test
    public void failedValidationPreservesPreviousScript() throws Exception {
        File destination = savedScript();
        TrackedInput source = new TrackedInput("invalid script".getBytes(StandardCharsets.UTF_8));

        IOException error = assertThrows(IOException.class,
            () -> FunscriptStore.importScript(source, destination, file -> {
                assertTrue(source.closed);
                assertArrayEquals(OLD_SCRIPT, Files.readAllBytes(destination.toPath()));
                throw new IOException("Invalid actions");
            }));

        assertEquals("Invalid actions", error.getMessage());
        assertPreviousScriptIsOnlyFile(destination);
    }

    @Test
    public void readFailureAfterPartialCopyPreservesPreviousScript() throws Exception {
        File destination = savedScript();
        boolean[] closed = {false};
        InputStream source = new InputStream() {
            int reads;
            @Override public int read() throws IOException {
                if (reads++ == 0) return 'x';
                throw new IOException("Read failed");
            }
            @Override public void close() { closed[0] = true; }
        };

        assertThrows(IOException.class,
            () -> FunscriptStore.importScript(source, destination, file -> { }));

        assertTrue(closed[0]);
        assertPreviousScriptIsOnlyFile(destination);
    }

    @Test
    public void sourceCloseFailurePreservesPreviousScript() throws Exception {
        File destination = savedScript();
        InputStream source = new ByteArrayInputStream(new byte[]{1, 2, 3}) {
            @Override public void close() throws IOException {
                throw new IOException("Close failed");
            }
        };

        assertThrows(IOException.class,
            () -> FunscriptStore.importScript(source, destination, file -> { }));

        assertPreviousScriptIsOnlyFile(destination);
    }

    @Test
    public void failedReplacementRestoresPreviousScript() throws Exception {
        File destination = savedScript();

        assertThrows(IOException.class, () -> FunscriptStore.importScript(
            new ByteArrayInputStream(new byte[]{1, 2, 3}), destination, file -> {
                // Simulate a vanished temporary file between validation and rename.
                assertTrue(file.delete());
            }));

        assertPreviousScriptIsOnlyFile(destination);
    }

    @Test
    public void validImportReplacesOnlyAfterClosedSourceAndValidation() throws Exception {
        File destination = savedScript();
        byte[] replacement = "new valid script".getBytes(StandardCharsets.UTF_8);
        TrackedInput source = new TrackedInput(replacement);

        File result = FunscriptStore.importScript(source, destination, file -> {
            assertTrue(source.closed);
            assertTrue(file.getName().endsWith(".funscript"));
            assertArrayEquals(OLD_SCRIPT, Files.readAllBytes(destination.toPath()));
            assertArrayEquals(replacement, Files.readAllBytes(file.toPath()));
        });

        assertEquals(destination.getCanonicalFile(), result);
        assertArrayEquals(replacement, Files.readAllBytes(result.toPath()));
        assertEquals(1, result.getParentFile().list().length);
    }

    @Test
    public void exactlyTwoMiBIsAcceptedAndCsvExtensionIsKeptDuringValidation() throws Exception {
        File directory = temporaryFolder.newFolder();
        File destination = FunscriptStore.destinationFor(directory, "content://video/2", "video", ".csv");
        byte[] bytes = new byte[FunscriptStore.MAX_SCRIPT_BYTES];
        bytes[bytes.length - 1] = 42;

        FunscriptStore.importScript(new ByteArrayInputStream(bytes), destination, file -> {
            assertTrue(file.getName().endsWith(".csv"));
            assertEquals(bytes.length, file.length());
        });

        assertArrayEquals(bytes, Files.readAllBytes(destination.toPath()));
    }

    @Test
    public void sameBasenameFromDifferentMediaHasDistinctStableDestinations() throws Exception {
        File directory = temporaryFolder.newFolder();
        File first = FunscriptStore.destinationFor(directory, "content://video/1", "movie", ".funscript");
        File second = FunscriptStore.destinationFor(directory, "content://video/2", "movie", ".funscript");

        assertNotEquals(first, second);
        assertEquals(first, FunscriptStore.destinationFor(directory, "content://video/1", "movie", ".funscript"));
        assertEquals(directory.getCanonicalFile(), first.getParentFile());
    }

    @Test
    public void untrustedBasenameCannotEscapeAndPathExtensionIsRejected() throws Exception {
        File directory = temporaryFolder.newFolder();
        File destination = FunscriptStore.destinationFor(directory, "file:///video", "../../movie\\evil", ".CSV");

        assertEquals(directory.getCanonicalFile(), destination.getParentFile());
        assertFalse(destination.getName().contains(".."));
        assertTrue(destination.getName().endsWith(".csv"));
        assertThrows(IllegalArgumentException.class,
            () -> FunscriptStore.destinationFor(directory, "file:///video", "movie", "../outside"));
    }

    private File savedScript() throws Exception {
        File directory = temporaryFolder.newFolder();
        File destination = FunscriptStore.destinationFor(directory, "content://video/1", "movie", ".funscript");
        Files.write(destination.toPath(), OLD_SCRIPT);
        return destination;
    }

    private void assertPreviousScriptIsOnlyFile(File destination) throws IOException {
        assertArrayEquals(OLD_SCRIPT, Files.readAllBytes(destination.toPath()));
        assertEquals(1, destination.getParentFile().list().length);
    }

    private static final class TrackedInput extends ByteArrayInputStream {
        boolean closed;
        TrackedInput(byte[] bytes) { super(bytes); }
        @Override public void close() throws IOException {
            closed = true;
            super.close();
        }
    }
}
