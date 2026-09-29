package com.vlcplayer.app;

import org.junit.Test;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class TranslationManagerSrtTest {
    @Test public void parsesMultilineCueWithoutChangingTiming() throws Exception {
        String srt = "\uFEFF1\r\n00:00:01,000 --> 00:00:03,500\r\n"
            + "<i>Hello</i>\r\nHow are you?\r\n\r\n"
            + "2\r\n00:00:04,000 --> 00:00:05,000\r\nGoodbye\r\n";
        List<TranslationManager.SrtCue> cues = TranslationManager.parseSrt(srt);

        assertEquals(2, cues.size());
        assertEquals("1", cues.get(0).index);
        assertEquals("00:00:01,000 --> 00:00:03,500", cues.get(0).timestamp);
        assertEquals(2, cues.get(0).textLines.size());
        assertEquals("<i>Hello</i>", cues.get(0).textLines.get(0));
        assertEquals("How are you?", cues.get(0).textLines.get(1));
    }

    @Test public void rejectsUnsupportedAutoSourceBeforeAnyRequest() throws Exception {
        try {
            TranslationManager.checkedSourceLanguage("auto");
            fail("auto is not a MyMemory language code");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("MyMemory"));
        }
        assertEquals("en", TranslationManager.checkedSourceLanguage(" EN "));
    }

    @Test public void rejectsMalformedOrEmptyCuesBeforeTranslation() throws Exception {
        assertInvalid("1\nmissing timestamp\nHello\n");
        assertInvalid("1\n00:00:01,000 --> 00:00:02,000\n<i></i>\n");
    }

    @Test public void enforcesDecodedUtf8ByteLimitPerApiRequest() throws Exception {
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < 251; i++) line.append('é');
        assertInvalid("1\n00:00:01,000 --> 00:00:02,000\n" + line + "\n");
    }

    @Test public void preservesSimpleSubtitleStylingAroundTranslatedText() {
        assertEquals("<i>Xin chào</i>",
            TranslationManager.preserveEdgeTags("<i>Hello</i>", "Xin chào"));
        assertEquals("<b><i>Xin chào</i></b>",
            TranslationManager.preserveEdgeTags("<b><i>Hello</i></b>", "Xin chào"));
        assertEquals("Xin chào",
            TranslationManager.preserveEdgeTags("Hello <i>friend</i>", "Xin chào"));
    }

    @Test public void decodesMyMemoryHtmlEntitiesWithoutBreakingSubtitleText() throws Exception {
        assertEquals("Elle a dit\u00A0: \"Bonjour\u00A0!\"",
            TranslationManager.decodeHtmlEntities("Elle a dit&#xA0;: &quot;Bonjour&#xA0;!&quot;"));
        assertEquals("It's done & ready",
            TranslationManager.decodeHtmlEntities("It&#39;s done &amp; ready"));
        assertEquals("It's done",
            TranslationManager.decodeHtmlEntities("It&amp;#39;s done"));
        assertEquals("Café déjà vu",
            TranslationManager.decodeHtmlEntities("Caf&eacute; d&eacute;j&agrave; vu"));
    }

    @Test public void rejectsUnknownHtmlEntityRatherThanDisplayingRawCode() throws Exception {
        try {
            TranslationManager.decodeHtmlEntities("A &notKnownEntity; B");
            fail("Unknown entity should fail the translation");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("HTML"));
        }
    }

    @Test public void cancellationInterruptsWorkDisconnectsHttpAndSuppressesCallbacks() throws Exception {
        TranslationManager.SrtTranslationTask task = new TranslationManager.SrtTranslationTask();
        FutureTask<Void> pending = new FutureTask<>(() -> null);
        AtomicInteger delivered = new AtomicInteger();
        final boolean[] disconnected = {false};
        HttpURLConnection connection = new HttpURLConnection(new URL("https://example.test")) {
            @Override public void disconnect() { disconnected[0] = true; }
            @Override public boolean usingProxy() { return false; }
            @Override public void connect() {}
        };

        task.setFuture(pending);
        assertTrue(task.setConnection(connection));
        task.deliver(delivered::incrementAndGet);
        assertEquals(1, delivered.get());
        assertFalse(task.isCancelled());
        task.cancel();
        task.cancel();
        assertTrue(task.isCancelled());
        assertTrue(pending.isCancelled());
        assertTrue(disconnected[0]);
        task.deliver(delivered::incrementAndGet);
        assertEquals(1, delivered.get());
        assertFalse(task.setConnection(connection));
    }

    private void assertInvalid(String srt) throws Exception {
        try {
            TranslationManager.parseSrt(srt);
            fail("Invalid SRT should be rejected");
        } catch (IOException expected) {
            assertFalse(expected.getMessage().isEmpty());
        }
    }
}
