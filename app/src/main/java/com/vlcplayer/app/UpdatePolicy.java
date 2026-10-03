package com.vlcplayer.app;

import java.net.URI;
import java.net.URISyntaxException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Bounds and trust rules shared by release parsing and APK verification. */
final class UpdatePolicy {
    static final String REPOSITORY = "Doquanghuy12323/VLCPlayer";
    static final String PACKAGE_NAME = "com.vlcplayer.app";
    static final String APK_NAME = "app-release.apk";
    static final long MAX_APK_BYTES = 1024L * 1024L * 1024L;

    private UpdatePolicy() { }

    static boolean validVersion(long version) {
        return version > 0 && version <= Integer.MAX_VALUE;
    }

    static boolean validDigest(String digest) {
        return digest != null && digest.matches("[0-9a-f]{64}");
    }

    static boolean validSize(long size) {
        return size > 0 && size <= MAX_APK_BYTES;
    }

    static String releaseUrl(long version) {
        return "https://github.com/" + REPOSITORY + "/releases/tag/v" + version;
    }

    static String assetUrl(long version, String assetName) {
        return "https://github.com/" + REPOSITORY + "/releases/download/v" + version
            + "/" + assetName;
    }

    static boolean trustedAssetUrl(String candidate, long version, String assetName) {
        if (!validVersion(version) || !candidateEquals(candidate, assetUrl(version, assetName))) {
            return false;
        }
        try {
            URI uri = new URI(candidate);
            return "https".equals(uri.getScheme()) && "github.com".equals(uri.getHost())
                && uri.getPort() == -1 && uri.getUserInfo() == null
                && uri.getQuery() == null && uri.getFragment() == null;
        } catch (URISyntaxException e) {
            return false;
        }
    }

    private static boolean candidateEquals(String candidate, String expected) {
        return candidate != null && candidate.equals(expected);
    }

    static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(Character.forDigit((value >>> 4) & 15, 16));
            result.append(Character.forDigit(value & 15, 16));
        }
        return result.toString();
    }

    /** Rotation is accepted only if the new APK proves descent from the current signer. */
    static boolean compatibleSigners(byte[][] installed, boolean installedMultiple,
            byte[][] incoming, boolean incomingMultiple, byte[][] incomingHistory) {
        if (installed == null || incoming == null || installed.length == 0
                || incoming.length == 0) return false;
        for (byte[] certificate : installed) {
            if (certificate == null || certificate.length == 0) return false;
        }
        for (byte[] certificate : incoming) {
            if (certificate == null || certificate.length == 0) return false;
        }
        if (installedMultiple || incomingMultiple) {
            return sameSignerSet(installed, incoming);
        }
        if (installed.length != 1 || incoming.length != 1) return false;
        if (MessageDigest.isEqual(installed[0], incoming[0])) return true;
        if (incomingHistory != null) {
            for (byte[] previous : incomingHistory) {
                if (previous != null && MessageDigest.isEqual(installed[0], previous)) return true;
            }
        }
        return false;
    }

    static boolean sameSignerSet(byte[][] installed, byte[][] incoming) {
        if (installed == null || incoming == null || installed.length == 0
                || installed.length != incoming.length) return false;
        Set<String> oldSet = new HashSet<>();
        Set<String> newSet = new HashSet<>();
        for (byte[] certificate : installed) {
            if (certificate == null || certificate.length == 0) return false;
            oldSet.add(Arrays.toString(certificate));
        }
        for (byte[] certificate : incoming) {
            if (certificate == null || certificate.length == 0) return false;
            newSet.add(Arrays.toString(certificate));
        }
        return oldSet.size() == installed.length && oldSet.equals(newSet);
    }
}
