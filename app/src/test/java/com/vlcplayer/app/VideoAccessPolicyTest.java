package com.vlcplayer.app;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class VideoAccessPolicyTest {

    @Test
    public void legacyVersionsUseOnlyStoragePermission() {
        for (int sdk : new int[]{21, 32}) {
            assertAccess(VideoAccessPolicy.Access.FULL, sdk, false, false, true);
            assertAccess(VideoAccessPolicy.Access.FULL, sdk, true, true, true);
            assertAccess(VideoAccessPolicy.Access.DENIED, sdk, false, false, false);
            assertAccess(VideoAccessPolicy.Access.DENIED, sdk, true, true, false);
        }
    }

    @Test
    public void android13UsesOnlyFullVideoPermission() {
        assertAccess(VideoAccessPolicy.Access.FULL, 33, true, false, false);
        assertAccess(VideoAccessPolicy.Access.FULL, 33, true, true, true);
        assertAccess(VideoAccessPolicy.Access.DENIED, 33, false, true, false);
        assertAccess(VideoAccessPolicy.Access.DENIED, 33, false, false, true);
        assertAccess(VideoAccessPolicy.Access.DENIED, 33, false, true, true);
    }

    @Test
    public void android14AndLaterDistinguishSelectedFromFullAccess() {
        for (int sdk : new int[]{34, 35}) {
            assertAccess(VideoAccessPolicy.Access.PARTIAL, sdk, false, true, false);
            assertAccess(VideoAccessPolicy.Access.FULL, sdk, true, false, false);
            assertAccess(VideoAccessPolicy.Access.FULL, sdk, true, true, false);
            assertAccess(VideoAccessPolicy.Access.DENIED, sdk, false, false, true);
            assertAccess(VideoAccessPolicy.Access.DENIED, sdk, false, false, false);
        }
    }

    @Test
    public void permissionRevocationRecomputesAccessWithoutRetainingOldGrant() {
        assertAccess(VideoAccessPolicy.Access.FULL, 34, true, true, true);
        assertAccess(VideoAccessPolicy.Access.PARTIAL, 34, false, true, true);
        assertAccess(VideoAccessPolicy.Access.DENIED, 34, false, false, true);

        assertAccess(VideoAccessPolicy.Access.FULL, 33, true, false, true);
        assertAccess(VideoAccessPolicy.Access.DENIED, 33, false, false, true);

        assertAccess(VideoAccessPolicy.Access.FULL, 32, true, true, true);
        assertAccess(VideoAccessPolicy.Access.DENIED, 32, true, true, false);
    }

    @Test
    public void android14AllowsFirstSelectedVideoRequestAfterUpgrade() {
        assertSettings(false, 34, VideoAccessPolicy.Access.DENIED,
                true, false, false, false);
        assertSettings(false, 34, VideoAccessPolicy.Access.DENIED,
                false, false, false, false);
    }

    @Test
    public void grantedAccessNeverSendsUserToSettings() {
        assertSettings(false, 34, VideoAccessPolicy.Access.PARTIAL,
                true, true, false, false);
        assertSettings(false, 34, VideoAccessPolicy.Access.FULL,
                true, true, false, false);
    }

    @Test
    public void deniedAndroid14AccessNeedsExhaustedRequestsWithoutRationale() {
        assertSettings(true, 34, VideoAccessPolicy.Access.DENIED,
                true, true, false, false);
        assertSettings(false, 34, VideoAccessPolicy.Access.DENIED,
                true, true, true, false);
        assertSettings(false, 34, VideoAccessPolicy.Access.DENIED,
                true, true, false, true);
    }

    @Test
    public void android13IgnoresSelectedVideoRequestHistory() {
        assertSettings(true, 33, VideoAccessPolicy.Access.DENIED,
                true, false, false, false);
        assertSettings(true, 33, VideoAccessPolicy.Access.DENIED,
                true, true, false, true);
        assertSettings(false, 33, VideoAccessPolicy.Access.DENIED,
                true, false, true, false);
        assertSettings(false, 33, VideoAccessPolicy.Access.DENIED,
                false, true, false, false);
    }

    private static void assertAccess(VideoAccessPolicy.Access expected, int sdk,
                                     boolean mediaVideoGranted, boolean selectedVideoGranted,
                                     boolean storageGranted) {
        assertEquals("SDK " + sdk + " with mediaVideo=" + mediaVideoGranted
                        + ", selectedVideo=" + selectedVideoGranted
                        + ", storage=" + storageGranted,
                expected, VideoAccessPolicy.resolve(sdk, mediaVideoGranted,
                        selectedVideoGranted, storageGranted));
    }

    private static void assertSettings(boolean expected, int sdk, VideoAccessPolicy.Access access,
                                       boolean videoRequested, boolean selectionRequested,
                                       boolean videoRationale, boolean selectionRationale) {
        assertEquals("SDK " + sdk + " with access=" + access
                        + ", videoRequested=" + videoRequested
                        + ", selectionRequested=" + selectionRequested
                        + ", videoRationale=" + videoRationale
                        + ", selectionRationale=" + selectionRationale,
                expected, VideoAccessPolicy.shouldOpenSettings(sdk, access,
                        videoRequested, selectionRequested, videoRationale, selectionRationale));
    }
}
