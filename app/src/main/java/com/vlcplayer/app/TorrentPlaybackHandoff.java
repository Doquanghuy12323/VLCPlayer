package com.vlcplayer.app;

/** Reserves foreground Player launches for one live torrent session. */
public final class TorrentPlaybackHandoff {
    private String sessionId;
    private String readyUrl;
    private boolean resumed;
    private boolean allowAutomatic;
    private boolean readySeen;
    private boolean automaticRequested;
    private boolean launchPending;
    private boolean launchedThisResume;

    public void begin(String session, boolean allowAutoLaunch) {
        invalidate();
        sessionId = session;
        allowAutomatic = allowAutoLaunch;
    }

    /** Reattachment rebuilds readiness from the live manager, never a saved proxy URL. */
    public void restoreLiveSession(String currentSession) { begin(currentSession, false); }

    public void setResumed(boolean value) {
        if (value && !resumed) launchedThisResume = false;
        resumed = value;
    }

    public boolean ready(String session, String url) {
        if (!matches(session) || url == null || url.isEmpty()) return false;
        boolean automatic = !readySeen && allowAutomatic && resumed;
        readySeen = true;
        readyUrl = url;
        allowAutomatic = false;
        automaticRequested = automatic;
        return automatic;
    }

    public boolean canWatch(String currentSession) {
        return matches(currentSession) && readyUrl != null && !readyUrl.isEmpty();
    }

    public String reserveLaunch(String currentSession, boolean automatic) {
        if (!resumed || !canWatch(currentSession) || launchPending || launchedThisResume
                || (automatic && !automaticRequested)) return null;
        automaticRequested = false;
        launchPending = true;
        return readyUrl;
    }

    public void completeLaunch(boolean success) {
        if (!launchPending) return;
        launchPending = false;
        if (success) launchedThisResume = true;
    }

    public String getSessionId() { return sessionId; }

    public void invalidate() {
        sessionId = null;
        readyUrl = null;
        allowAutomatic = false;
        readySeen = false;
        automaticRequested = false;
        launchPending = false;
        launchedThisResume = false;
    }

    private boolean matches(String currentSession) {
        return sessionId != null && sessionId.equals(currentSession);
    }
}
