package com.vlcplayer.app;

/** Pure recovery decisions; successful persisted flags alone never imply a verified APK. */
final class UpdateRecoveryPolicy {
    enum DownloadState { NONE, ACTIVE, SUCCESS, FAILED }
    enum Decision { INSTALLED, INSTALLING, DOWNLOADING, VERIFY, DOWNLOAD_ERROR, MISSING_FILE, AVAILABLE }

    private UpdateRecoveryPolicy() { }

    static Decision decide(long installedVersion, long targetVersion, boolean liveInstall,
            boolean downloadStarted, DownloadState download, boolean fileExists) {
        if (installedVersion >= targetVersion) return Decision.INSTALLED;
        if (liveInstall) return Decision.INSTALLING;
        if (download == DownloadState.FAILED) return Decision.DOWNLOAD_ERROR;
        if (download == DownloadState.ACTIVE) return Decision.DOWNLOADING;
        if (download == DownloadState.SUCCESS || (downloadStarted && fileExists)) return Decision.VERIFY;
        return downloadStarted ? Decision.MISSING_FILE : Decision.AVAILABLE;
    }
}
