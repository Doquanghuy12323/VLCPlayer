package com.vlcplayer.app;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static com.vlcplayer.app.UpdateRecoveryPolicy.Decision.*;
import static com.vlcplayer.app.UpdateRecoveryPolicy.DownloadState.*;

public class UpdateRecoveryPolicyTest {
    @Test public void installedTargetWinsOverAStaleFailedDownloadOrInstaller() {
        assertEquals(INSTALLED, decision(20, true, true, FAILED, false));
        assertEquals(INSTALLED, decision(21, false, true, ACTIVE, true));
    }

    @Test public void aLiveInstallerIsNotReplacedByDownloadRecovery() {
        assertEquals(INSTALLING, decision(19, true, true, SUCCESS, true));
    }

    @Test public void processDeathDuringDownloadWaitsInsteadOfVerifyingPartialBytes() {
        assertEquals(DOWNLOADING, decision(19, false, true, ACTIVE, true));
    }

    @Test public void completedSystemDownloadAlwaysRequiresVerification() {
        assertEquals(VERIFY, decision(19, false, true, SUCCESS, true));
        assertEquals(VERIFY, decision(19, false, true, SUCCESS, false));
    }

    @Test public void removedSystemRecordCanRecoverOnlyAnExplicitlyStartedOwnedFile() {
        assertEquals(VERIFY, decision(19, false, true, NONE, true));
        assertEquals(AVAILABLE, decision(19, false, false, NONE, true));
    }

    @Test public void aFailedDownloadNeverTreatsItsPartialFileAsReady() {
        assertEquals(DOWNLOAD_ERROR, decision(19, false, true, FAILED, true));
    }

    @Test public void missingSavedJobAndFileOffersARecoverableFailure() {
        assertEquals(MISSING_FILE, decision(19, false, true, NONE, false));
        assertEquals(AVAILABLE, decision(19, false, false, NONE, false));
    }

    private static UpdateRecoveryPolicy.Decision decision(long installed, boolean liveInstall,
            boolean started, UpdateRecoveryPolicy.DownloadState download, boolean file) {
        return UpdateRecoveryPolicy.decide(installed, 20, liveInstall, started, download, file);
    }
}
