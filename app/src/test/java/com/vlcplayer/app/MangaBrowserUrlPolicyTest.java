package com.vlcplayer.app;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class MangaBrowserUrlPolicyTest {
    @Test public void acceptsWebUrlsWithoutChangingPathOrQuery() {
        assertEquals("https://example.com/Chapter?Page=2#image", MangaBrowserUrlPolicy.normalize(
                " https://example.com/Chapter?Page=2#image "));
        assertEquals("http://example.com", MangaBrowserUrlPolicy.normalize("http://example.com"));
        assertEquals("HTTPS://example.com/a%20b", MangaBrowserUrlPolicy.normalize("HTTPS://example.com/a%20b"));
    }

    @Test public void suppliesHttpsForHostAndProtocolRelativeAddresses() {
        assertEquals("https://example.com/chapter", MangaBrowserUrlPolicy.normalize("example.com/chapter"));
        assertEquals("https://example.com/chapter", MangaBrowserUrlPolicy.normalize("//example.com/chapter"));
        assertEquals("https://example.com:8080/chapter", MangaBrowserUrlPolicy.normalize("example.com:8080/chapter"));
        assertEquals("https://192.168.1.8:8080/chapter", MangaBrowserUrlPolicy.normalize("192.168.1.8:8080/chapter"));
        assertEquals("https://localhost:8080", MangaBrowserUrlPolicy.normalize("localhost:8080"));
        assertEquals("https://[::1]:8080/chapter", MangaBrowserUrlPolicy.normalize("[::1]:8080/chapter"));
    }

    @Test public void rejectsExecutableAndLocalSchemes() {
        String[] blocked = {"javascript:alert(1)", "javascript:123", "data:text/html,test",
                "file:///sdcard/test.html", "content://provider/test", "intent://example.com",
                "ftp://example.com", "httpx://example.com", "about:blank"};
        for (String url : blocked) assertNull(url, MangaBrowserUrlPolicy.normalize(url));
    }

    @Test public void rejectsBlankAndMalformedUrls() {
        String[] invalid = {"", "  ", "https://", "https:///chapter", "http:example.com",
                "https://example.com:99999", "https://example.com:-1", "https://example.com:port",
                "https://user:pass@example.com", "https://example.com\\chapter",
                "example.com/a b", "https://example.com\n/chapter"};
        assertNull(MangaBrowserUrlPolicy.normalize(null));
        for (String url : invalid) assertNull(url, MangaBrowserUrlPolicy.normalize(url));
    }
}
