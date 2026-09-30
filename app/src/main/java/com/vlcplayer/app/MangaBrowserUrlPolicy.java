package com.vlcplayer.app;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/** Accepts web addresses without allowing local files or executable URL schemes. */
public final class MangaBrowserUrlPolicy {
    private MangaBrowserUrlPolicy() { }

    public static String normalize(String input) {
        if (input == null) return null;
        String candidate = input.trim();
        if (candidate.isEmpty()) return null;
        for (int i = 0; i < candidate.length(); i++) {
            char c = candidate.charAt(i);
            if (Character.isWhitespace(c) || Character.isISOControl(c) || c == '\\') {
                return null;
            }
        }
        if (candidate.startsWith("//")) candidate = "https:" + candidate;
        else if (candidate.startsWith("[")) candidate = "https://" + candidate;
        else if (candidate.matches("(?i)(localhost|[a-z0-9-]+(\\.[a-z0-9-]+)+):[0-9]{1,5}([/?#].*)?")) {
            candidate = "https://" + candidate;
        }
        try {
            URI parsed = new URI(candidate);
            String scheme = parsed.getScheme();
            if (scheme == null) {
                candidate = "https://" + candidate;
            } else if (!"http".equals(scheme.toLowerCase(Locale.ROOT))
                    && !"https".equals(scheme.toLowerCase(Locale.ROOT))) {
                return null;
            }
            URI result = new URI(candidate);
            if (result.getHost() == null || result.getHost().isEmpty()
                    || result.getRawUserInfo() != null || result.getPort() > 65535) {
                return null;
            }
            String resultScheme = result.getScheme();
            if (!"http".equalsIgnoreCase(resultScheme)
                    && !"https".equalsIgnoreCase(resultScheme)) return null;
            return result.toASCIIString();
        } catch (URISyntaxException | IllegalArgumentException e) {
            return null;
        }
    }
}
