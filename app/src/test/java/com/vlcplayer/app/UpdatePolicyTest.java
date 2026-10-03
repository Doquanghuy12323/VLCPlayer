package com.vlcplayer.app;

import org.junit.Test;

import static org.junit.Assert.*;

public class UpdatePolicyTest {
    @Test public void rejectsAssetFromDifferentRepositoryOrRelease() {
        String expected = UpdatePolicy.assetUrl(1790784054L, "app-release.apk");
        assertTrue(UpdatePolicy.trustedAssetUrl(expected, 1790784054L, "app-release.apk"));
        assertFalse(UpdatePolicy.trustedAssetUrl(expected, 1790784055L, "app-release.apk"));
        assertFalse(UpdatePolicy.trustedAssetUrl(expected.replace("VLCPlayer", "Other"),
            1790784054L, "app-release.apk"));
        assertFalse(UpdatePolicy.trustedAssetUrl(expected.replace("https:", "http:"),
            1790784054L, "app-release.apk"));
        assertFalse(UpdatePolicy.trustedAssetUrl(expected + "?redirect=other",
            1790784054L, "app-release.apk"));
    }

    @Test public void requiresBoundedVersionSizeAndFullDigest() {
        assertFalse(UpdatePolicy.validVersion(0));
        assertFalse(UpdatePolicy.validVersion(2147483648L));
        assertFalse(UpdatePolicy.validSize(0));
        assertFalse(UpdatePolicy.validSize(UpdatePolicy.MAX_APK_BYTES + 1));
        assertFalse(UpdatePolicy.validDigest("abc"));
        assertFalse(UpdatePolicy.validDigest(new String(new char[64]).replace('\0', 'g')));
        assertTrue(UpdatePolicy.validDigest(new String(new char[64]).replace('\0', 'a')));
    }

    @Test public void acceptsExactCurrentSigner() {
        assertTrue(UpdatePolicy.compatibleSigners(certs(1), false, certs(1), false, null));
        assertFalse(UpdatePolicy.compatibleSigners(certs(1), false, certs(2), false, certs(2)));
    }

    @Test public void acceptsRotationOnlyWhenIncomingLineageContainsInstalledCurrentSigner() {
        assertTrue(UpdatePolicy.compatibleSigners(certs(2), false, certs(3), false, certs(1, 2, 3)));
        assertFalse(UpdatePolicy.compatibleSigners(certs(3), false, certs(2), false, certs(1, 2)));
        assertFalse(UpdatePolicy.compatibleSigners(certs(2), false, certs(3), false, certs(1, 3)));
    }

    @Test public void requiresExactSetForMultipleSigners() {
        assertTrue(UpdatePolicy.compatibleSigners(certs(1, 2), true, certs(2, 1), true, null));
        assertFalse(UpdatePolicy.compatibleSigners(certs(1, 2), true, certs(1), false, certs(1, 2)));
        assertFalse(UpdatePolicy.sameSignerSet(certs(1, 2), certs(1, 1)));
    }

    @Test public void rejectsMissingCertificates() {
        assertFalse(UpdatePolicy.compatibleSigners(null, false, certs(1), false, certs(1)));
        assertFalse(UpdatePolicy.sameSignerSet(new byte[][] {null}, certs(1)));
    }

    private static byte[][] certs(int... values) {
        byte[][] result = new byte[values.length][];
        for (int index = 0; index < values.length; index++) result[index] = new byte[] {(byte) values[index]};
        return result;
    }
}
