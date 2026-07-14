package com.vlcplayer.app;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** I/O helpers that work on every Android version supported by the app. */
public final class IoUtils {
    private IoUtils() {}

    public static byte[] readAllBytes(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }
}
