package com.vlcplayer.app;

/** Resolves the video-library scope from the current Android permission grants. */
public final class VideoAccessPolicy {
    public enum Access { FULL, PARTIAL, DENIED }

    private VideoAccessPolicy() {}

    public static Access resolve(int sdkInt, boolean mediaVideoGranted,
            boolean selectedVideoGranted, boolean storageGranted) {
        if (sdkInt >= 33) {
            if (mediaVideoGranted) return Access.FULL;
            if (sdkInt >= 34 && selectedVideoGranted) return Access.PARTIAL;
            return Access.DENIED;
        }
        return storageGranted ? Access.FULL : Access.DENIED;
    }

    public static boolean shouldOpenSettings(int sdkInt, Access access,
            boolean videoPermissionRequested, boolean selectionPermissionRequested,
            boolean showVideoRationale, boolean showSelectionRationale) {
        if (access != Access.DENIED || !videoPermissionRequested) return false;
        // Older releases never requested selected access. Allow its first explicit request.
        if (sdkInt >= 34 && !selectionPermissionRequested) return false;
        if (showVideoRationale) return false;
        return sdkInt < 34 || !showSelectionRationale;
    }
}
