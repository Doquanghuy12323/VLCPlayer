package com.vlcplayer.app;

/** Main-thread state transitions for one user-controlled LAN sharing session. */
public final class LanSharingState {
    public enum Phase { IDLE, PREPARING, READY, STARTING, RUNNING, STOPPED, ERROR }
    public enum Error { NONE, SOURCE_UNAVAILABLE, SIZE_UNKNOWN, NETWORK_UNAVAILABLE, ADDRESS_INVALID, SERVER_FAILED }

    public static final class Snapshot {
        public final Phase phase;
        public final String videoUri;
        public final String videoName;
        public final long videoSize;
        public final String lanUrl;
        public final String clientIp;
        public final boolean clientDisconnected;
        public final Error error;

        private Snapshot(LanSharingState state) {
            phase = state.phase;
            videoUri = state.videoUri;
            videoName = state.videoName;
            videoSize = state.videoSize;
            lanUrl = state.lanUrl;
            clientIp = state.clientIp;
            clientDisconnected = state.clientDisconnected;
            error = state.error;
        }

        public boolean canPickVideo() { return phase != Phase.STARTING && phase != Phase.RUNNING; }
        public boolean canStart() {
            return videoUri != null && (phase == Phase.READY || phase == Phase.STOPPED || phase == Phase.ERROR);
        }
        public boolean canStop() { return phase == Phase.PREPARING || phase == Phase.STARTING || phase == Phase.RUNNING; }
        public boolean isBusy() { return phase == Phase.PREPARING || phase == Phase.STARTING; }
    }

    private long generation;
    private Phase phase = Phase.IDLE;
    private String videoUri;
    private String videoName = "";
    private long videoSize = -1;
    private String lanUrl = "";
    private String clientIp = "";
    private boolean clientDisconnected;
    private Error error = Error.NONE;

    public Snapshot snapshot() { return new Snapshot(this); }

    public long selectVideo(String uri) {
        if (uri == null || uri.isEmpty() || !snapshot().canPickVideo()) return -1;
        generation++;
        videoUri = uri;
        videoName = "";
        videoSize = -1;
        phase = Phase.PREPARING;
        clearSession();
        return generation;
    }

    public void restoreSelection(String uri, String name, long size, boolean stopped) {
        if (videoUri != null || uri == null || uri.isEmpty()) return;
        generation++;
        videoUri = uri;
        videoName = name == null ? "" : name;
        videoSize = size;
        phase = stopped ? Phase.STOPPED : Phase.READY;
        clearSession();
    }

    public boolean prepared(long ticket, String name, long size) {
        if (generation != ticket || phase != Phase.PREPARING) return false;
        videoName = name == null ? "" : name;
        videoSize = size;
        if (size <= 0) {
            phase = Phase.ERROR;
            error = Error.SIZE_UNKNOWN;
        } else {
            phase = Phase.READY;
        }
        return true;
    }

    public long start() {
        if (!snapshot().canStart()) return -1;
        generation++;
        phase = Phase.STARTING;
        clearSession();
        return generation;
    }

    public boolean serverStarted(long ticket, String url) {
        if (generation != ticket || phase != Phase.STARTING || url == null || url.isEmpty()) return false;
        phase = Phase.RUNNING;
        lanUrl = url;
        return true;
    }

    public boolean clientConnected(long ticket, String ip) {
        if (generation != ticket || phase != Phase.RUNNING) return false;
        clientIp = ip == null ? "" : ip;
        clientDisconnected = false;
        return true;
    }

    public boolean clientDisconnected(long ticket) {
        if (generation != ticket || phase != Phase.RUNNING) return false;
        clientIp = "";
        clientDisconnected = true;
        return true;
    }

    public boolean failed(long ticket, Error reason) {
        if (generation != ticket || (phase != Phase.PREPARING && phase != Phase.STARTING && phase != Phase.RUNNING)) {
            return false;
        }
        phase = Phase.ERROR;
        clearSession();
        error = reason == null || reason == Error.NONE ? Error.SERVER_FAILED : reason;
        return true;
    }

    public void stop() {
        generation++;
        phase = videoUri == null ? Phase.IDLE : Phase.STOPPED;
        clearSession();
    }

    public boolean serverStopped(long ticket) {
        if (generation != ticket || (phase != Phase.STARTING && phase != Phase.RUNNING)) return false;
        stop();
        return true;
    }

    private void clearSession() {
        lanUrl = "";
        clientIp = "";
        clientDisconnected = false;
        error = Error.NONE;
    }
}
