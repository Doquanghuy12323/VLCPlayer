package com.vlcplayer.app;

import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Application-owned update state. Activities observe it and perform foreground handoffs. */
public final class UpdateManager {
    public enum State {
        IDLE, CHECKING, AVAILABLE, DOWNLOADING, VERIFYING, READY, WAITING_PERMISSION,
        INSTALLING, UP_TO_DATE, ERROR, INSTALLED
    }

    public static final class Snapshot {
        public final State state;
        public final UpdateRelease release;
        public final long downloadedBytes;
        public final long totalBytes;
        public final int errorResId;
        public final boolean retryDownload;
        public final boolean updateAvailable;

        private Snapshot(State state, UpdateRelease release, long bytes, int error,
                boolean retry, boolean available) {
            this.state = state;
            this.release = release;
            this.downloadedBytes = bytes;
            this.totalBytes = release == null ? 0 : release.sizeBytes;
            this.errorResId = error;
            this.retryDownload = retry;
            this.updateAvailable = available;
        }
    }

    public interface Listener { void onChanged(Snapshot snapshot); }

    private static final String API_URL =
            "https://api.github.com/repos/Doquanghuy12323/VLCPlayer/releases/latest";
    private static final String PREFS = "durable_update_prefs";
    static final String INSTALL_ACTION = "com.vlcplayer.app.UPDATE_INSTALL_RESULT";
    private static final long CHECK_INTERVAL = 24 * 60 * 60 * 1000L;
    private static final int NOTIFICATION_ID = 4301;
    private static final String CHANNEL_ID = "app_updates";
    private static UpdateManager instance;

    public static synchronized UpdateManager get(Context context) {
        if (instance == null) instance = new UpdateManager(context.getApplicationContext());
        return instance;
    }

    private final Context app;
    private final SharedPreferences prefs;
    private final DownloadManager downloads;
    private final PackageInstaller installer;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<Listener> listeners = new LinkedHashSet<>();
    private volatile Snapshot snapshot;
    private volatile long generation;
    private UpdateRelease release;
    private long installedVersion;
    private long downloadId = -1;
    private boolean downloadStarted;
    private int installSession = -1;
    private String installNonce;
    private boolean orphanInstall;
    private boolean installRequested;
    private boolean waitingPermission;
    private boolean reconcileInFlight;
    private boolean restoring;
    private boolean checking;
    private Intent pendingConfirmation;
    private boolean confirmationLaunched;
    private long promptedVersion = -1;
    private long promptedAt;
    private int savedInstallError;

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!listeners.isEmpty()
                    && (snapshot.state == State.DOWNLOADING || snapshot.state == State.INSTALLING)) {
                reconcile();
                main.postDelayed(this, 2000);
            }
        }
    };

    private UpdateManager(Context app) {
        this.app = app;
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        downloads = (DownloadManager) app.getSystemService(Context.DOWNLOAD_SERVICE);
        installer = app.getPackageManager().getPackageInstaller();
        installedVersion = installedVersion();
        try {
            String saved = prefs.getString("release", null);
            if (saved != null) {
                release = UpdateRelease.parse(saved);
                if (!app.getPackageName().equals(release.packageName)) release = null;
            }
        } catch (IOException ignored) { release = null; }
        if (release != null) {
            downloadId = prefs.getLong("download_id", -1);
            downloadStarted = prefs.getBoolean("download_started", false);
            installSession = prefs.getInt("install_session", -1);
            installNonce = prefs.getString("install_nonce", null);
            installRequested = prefs.getBoolean("install_requested", false);
            waitingPermission = prefs.getBoolean("waiting_permission", false);
            savedInstallError = installErrorResource(prefs.getString("install_error", ""));
            orphanInstall = installSession >= 0;
        } else {
            prefs.edit().remove("download_id").remove("download_started")
                    .remove("install_session").remove("install_nonce")
                    .remove("install_requested").remove("waiting_permission")
                    .remove("install_error").apply();
        }
        State initial = release == null ? State.IDLE
                : downloadStarted || installSession >= 0 ? State.CHECKING : State.AVAILABLE;
        restoring = initial == State.CHECKING;
        snapshot = new Snapshot(initial, release,
                0, 0, false, release != null && release.versionCode > installedVersion);
        // Register before any enqueue. Broadcasts are hints; query the owned ID and verify bytes.
        BroadcastReceiver completed = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())
                        && intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) == downloadId) {
                    reconcile();
                }
            }
        };
        ContextCompat.registerReceiver(app, completed,
                new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                ContextCompat.RECEIVER_EXPORTED);
        main.post(this::reconcile);
    }

    public Snapshot snapshot() { return snapshot; }

    public void addListener(Listener listener) {
        onMain(() -> {
            listeners.add(listener);
            listener.onChanged(snapshot);
            schedulePoll();
        });
    }

    public void removeListener(Listener listener) {
        onMain(() -> {
            listeners.remove(listener);
            if (listeners.isEmpty()) main.removeCallbacks(poll);
        });
    }

    public void checkAutomatically() {
        onMain(() -> {
            reconcile();
            long last = prefs.getLong("last_check", 0);
            long now = System.currentTimeMillis();
            if (last == 0 || now < last || now - last >= CHECK_INTERVAL) checkForUpdate();
        });
    }

    public void checkForUpdate() {
        onMain(() -> {
            if (checking || restoring || isBusy() || snapshot.state == State.READY
                    || snapshot.state == State.WAITING_PERMISSION
                    || (snapshot.state == State.ERROR && !snapshot.retryDownload
                        && isReusableInstallError(snapshot.errorResId))) {
                reconcile();
                return;
            }
            checking = true;
            reconcileInFlight = false;
            long epoch = ++generation;
            prefs.edit().putLong("last_check", System.currentTimeMillis()).apply();
            publish(State.CHECKING, 0, false, 0);
            io.execute(() -> {
                UpdateRelease found = null;
                int error = R.string.update_error_network;
                try {
                    String response = readUrl(API_URL, 1024 * 1024);
                    error = R.string.update_error_metadata;
                    JSONObject github = new JSONObject(response);
                    String tag = github.getString("tag_name");
                    if (!tag.matches("v[1-9][0-9]*")) throw new IOException("Invalid release tag");
                    long code = Long.parseLong(tag.substring(1));
                    JSONArray assets = github.getJSONArray("assets");
                    String metadataUrl = null;
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject asset = assets.getJSONObject(i);
                        if ("update.json".equals(asset.optString("name"))
                                && "uploaded".equals(asset.optString("state"))) {
                            if (metadataUrl != null) throw new IOException("Duplicate metadata");
                            metadataUrl = asset.optString("browser_download_url");
                        }
                    }
                    if (!UpdatePolicy.trustedAssetUrl(metadataUrl, code, "update.json")) {
                        throw new IOException("Missing update manifest");
                    }
                    error = R.string.update_error_network;
                    String metadata = readUrl(metadataUrl, 128 * 1024);
                    error = R.string.update_error_metadata;
                    UpdateRelease candidate = UpdateRelease.parse(metadata);
                    candidate.validateAgainst(github, Build.VERSION.SDK_INT, app.getPackageName());
                    found = candidate;
                } catch (Exception ignored) { /* Localized errors do not expose raw server text. */ }
                UpdateRelease result = found;
                int errorId = error;
                main.post(() -> {
                    if (epoch != generation) return;
                    checking = false;
                    installedVersion = installedVersion();
                    if (result == null) {
                        publish(State.ERROR, errorId, false, 0);
                    } else if (result.versionCode <= installedVersion) {
                        clearOwnedDownload();
                        release = null;
                        prefs.edit().remove("release").apply();
                        publish(State.UP_TO_DATE, 0, false, 0);
                    } else {
                        if (release == null || release.versionCode != result.versionCode) {
                            clearOwnedDownload();
                        }
                        release = result;
                        prefs.edit().putString("release", release.toJson()).apply();
                        publish(State.AVAILABLE, 0, false, 0);
                    }
                });
            });
        });
    }

    public boolean canPromptAutomatically() {
        Snapshot value = snapshot;
        long now = System.currentTimeMillis();
        return (value.state == State.AVAILABLE || value.state == State.READY)
                && value.updateAvailable && value.release != null
                && (promptedVersion != value.release.versionCode || now < promptedAt
                    || now - promptedAt >= CHECK_INTERVAL)
                && (prefs.getLong("snooze_version", -1) != value.release.versionCode
                    || now >= prefs.getLong("snooze_until", 0));
    }

    public void markPromptShown() {
        onMain(() -> {
            if (release != null) {
                promptedVersion = release.versionCode;
                promptedAt = System.currentTimeMillis();
            }
        });
    }

    public void snooze() {
        onMain(() -> {
            // The stored deadline also applies when this process stays alive overnight.
            promptedVersion = -1;
            if (release != null) prefs.edit().putLong("snooze_version", release.versionCode)
                    .putLong("snooze_until", System.currentTimeMillis() + CHECK_INTERVAL).apply();
        });
    }

    public void download() {
        onMain(() -> {
            if (release == null || isBusy() || checking || snapshot.state == State.READY
                    || snapshot.state == State.WAITING_PERMISSION) return;
            ++generation;
            reconcileInFlight = false;
            restoring = false;
            clearOwnedDownload();
            try {
                if (downloads == null) throw new IOException("No download service");
                File destination = ownedFile(release);
                if (destination == null) throw new IOException("No download directory");
                if (destination.exists()) throw new IOException("Cannot remove previous APK");
                File dir = destination.getParentFile();
                if (dir == null || (!dir.isDirectory() && !dir.mkdirs())) {
                    throw new IOException("No download directory");
                }
                downloadStarted = true;
                // A crash between enqueue and persisting its ID can recover the owned destination.
                if (!prefs.edit().putString("release", release.toJson())
                        .putBoolean("download_started", true).putLong("download_id", -1).commit()) {
                    throw new IOException("Cannot persist download");
                }
                DownloadManager.Request request = new DownloadManager.Request(Uri.parse(release.apkUrl))
                        .setTitle(app.getString(R.string.update_title))
                        .setDescription(app.getString(R.string.update_download_description))
                        .setMimeType("application/vnd.android.package-archive")
                        // Only verified APKs produce the app's ready-to-install notification.
                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                        .setDestinationUri(Uri.fromFile(destination));
                downloadId = downloads.enqueue(request);
                if (!prefs.edit().putLong("download_id", downloadId).commit()) {
                    downloads.remove(downloadId);
                    throw new IOException("Cannot persist download ID");
                }
                publish(State.DOWNLOADING, 0, false, 0);
                reconcile();
            } catch (Exception ignored) {
                clearOwnedDownload();
                publish(State.ERROR, R.string.update_error_storage, true, 0);
            }
        });
    }

    public void cancelDownload() {
        onMain(() -> {
            if (snapshot.state != State.DOWNLOADING && snapshot.state != State.VERIFYING
                    && snapshot.state != State.READY && snapshot.state != State.WAITING_PERMISSION
                    && snapshot.state != State.ERROR) return;
            ++generation;
            reconcileInFlight = false;
            restoring = false;
            clearInstallState();
            clearOwnedDownload();
            publish(release == null ? State.IDLE : State.AVAILABLE, 0, false, 0);
        });
    }

    /** Reconciles actual service/file/package state; a persisted READY flag is never trusted. */
    public void reconcile() {
        onMain(() -> {
            if (release == null || reconcileInFlight || checking
                    || snapshot.state == State.VERIFYING) return;
            reconcileInFlight = true;
            long epoch = generation;
            UpdateRelease target = release;
            long knownId = downloadId;
            boolean started = downloadStarted;
            int oldSession = orphanInstall ? installSession : -1;
            boolean liveInstalling = snapshot.state == State.INSTALLING && !orphanInstall;
            int retainedInstallError = savedInstallError;
            if (oldSession >= 0) {
                // Recover this owned orphan before dispatching worker IO. Main-thread
                // callbacks cannot race the abandon, and its ledger survives until cleanup.
                try { installer.abandonSession(oldSession); } catch (Exception ignored) { }
                clearInstallState();
            }
            io.execute(() -> {
                Recovery result = new Recovery();
                result.version = installedVersion();
                result.id = knownId;
                try {
                    if (result.version >= target.versionCode) {
                        result.state = State.INSTALLED;
                    } else if (liveInstalling) {
                        result.state = State.INSTALLING;
                    } else {
                        File file = ownedFile(target);
                        if (file == null) throw new IOException("Unavailable directory");
                        DownloadStatus status = queryDownload(knownId, file, started);
                        result.id = status.id;
                        result.bytes = status.bytes;
                        UpdateRecoveryPolicy.Decision decision = UpdateRecoveryPolicy.decide(
                                result.version, target.versionCode, false, started,
                                downloadState(status.status), file.isFile());
                        if (decision == UpdateRecoveryPolicy.Decision.DOWNLOAD_ERROR) {
                            result.state = State.ERROR;
                            result.error = status.reason == DownloadManager.ERROR_INSUFFICIENT_SPACE
                                    ? R.string.update_error_storage : R.string.update_error_download;
                            result.retry = true;
                        } else if (decision == UpdateRecoveryPolicy.Decision.DOWNLOADING) {
                            result.state = State.DOWNLOADING;
                        } else if (decision == UpdateRecoveryPolicy.Decision.VERIFY) {
                            main.post(() -> {
                                if (epoch == generation) publish(State.VERIFYING, 0, false, 0);
                            });
                            UpdateApkValidator.verify(app, file, target);
                            result.bytes = target.sizeBytes;
                            result.state = State.READY;
                        } else if (decision == UpdateRecoveryPolicy.Decision.MISSING_FILE) {
                            result.state = State.ERROR;
                            result.error = R.string.update_error_missing_file;
                            result.retry = true;
                        } else {
                            result.state = State.AVAILABLE;
                        }
                    }
                } catch (UpdateApkValidator.ValidationException e) {
                    result.state = State.ERROR;
                    result.error = e.errorResId;
                    result.retry = canRetryDownload(e.errorResId);
                } catch (Exception ignored) {
                    result.state = State.ERROR;
                    result.error = R.string.update_error_storage;
                    result.retry = true;
                }
                main.post(() -> {
                    if (epoch != generation) return;
                    reconcileInFlight = false;
                    restoring = false;
                    installedVersion = result.version;
                    downloadId = result.id;
                    prefs.edit().putLong("download_id", downloadId).apply();
                    if (result.state == State.INSTALLED) {
                        ++generation;
                        clearInstallState();
                        clearOwnedDownload();
                        publish(State.INSTALLED, 0, false, target.sizeBytes);
                    } else if (result.state == State.READY) {
                        if (retainedInstallError != 0) {
                            publish(State.ERROR, retainedInstallError, false, result.bytes);
                        } else {
                            notifyReady();
                            publish(waitingPermission && installRequested
                                    ? State.WAITING_PERMISSION : State.READY, 0, false, result.bytes);
                        }
                    } else {
                        publish(result.state, result.error, result.retry, result.bytes);
                    }
                });
            });
        });
    }

    /** Called only by an explicit button while its Activity is resumed. */
    public void install(Activity activity) {
        if (!isResumed(activity)) return;
        if (release == null || (snapshot.state != State.READY
                && snapshot.state != State.WAITING_PERMISSION
                && !(snapshot.state == State.ERROR && !snapshot.retryDownload
                    && isReusableInstallError(snapshot.errorResId)))) return;
        installRequested = true;
        prefs.edit().putBoolean("install_requested", true).apply();
        if (!canInstallPackages()) {
            waitingPermission = true;
            prefs.edit().putBoolean("waiting_permission", true).apply();
            publish(State.WAITING_PERMISSION, 0, false, release.sizeBytes);
            try {
                activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + app.getPackageName())));
            } catch (Exception ignored) {
                clearInstallState();
                publish(State.ERROR, R.string.update_error_install_blocked, false, release.sizeBytes);
            }
            return;
        }
        beginInstall();
    }

    /** No Activity is retained; foreground calls alone may launch a pending user action. */
    public void resumeInstallation(Activity activity) {
        if (!isResumed(activity) || !installRequested) return;
        if (waitingPermission && snapshot.state == State.WAITING_PERMISSION) {
            if (!canInstallPackages()) {
                clearInstallState();
                publish(State.ERROR, R.string.update_error_install_blocked, false,
                        release == null ? 0 : release.sizeBytes);
            } else {
                waitingPermission = false;
                prefs.edit().putBoolean("waiting_permission", false).apply();
                beginInstall();
            }
        } else if (pendingConfirmation != null && !confirmationLaunched
                && snapshot.state == State.INSTALLING) {
            confirmationLaunched = true;
            Intent confirmation = pendingConfirmation;
            pendingConfirmation = null;
            cancelReadyNotification();
            try {
                activity.startActivity(new Intent(confirmation));
            } catch (Exception ignored) {
                abandonInstall();
                publish(State.ERROR, R.string.update_error_install, false,
                        release == null ? 0 : release.sizeBytes);
            }
        }
    }

    public boolean hasPendingInstallationAction() {
        return installRequested && ((pendingConfirmation != null && !confirmationLaunched)
                || (waitingPermission && snapshot.state == State.WAITING_PERMISSION
                    && canInstallPackages()));
    }

    private void beginInstall() {
        if (release == null || installSession >= 0 || snapshot.state == State.INSTALLING) return;
        waitingPermission = false;
        prefs.edit().putBoolean("waiting_permission", false).apply();
        long epoch = ++generation;
        reconcileInFlight = false;
        UpdateRelease target = release;
        publish(State.INSTALLING, 0, false, target.sizeBytes);
        cancelReadyNotification();
        io.execute(() -> {
            int sessionId = -1;
            String sessionNonce = null;
            try {
                File file = ownedFile(target);
                if (file == null) throw new IOException("Missing file");
                UpdateApkValidator.verify(app, file, target);
                if (epoch != generation) return;
                PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                        PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                params.setAppPackageName(app.getPackageName());
                params.setSize(target.sizeBytes);
                if (Build.VERSION.SDK_INT >= 31) {
                    params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED);
                }
                sessionId = installer.createSession(params);
                sessionNonce = UUID.randomUUID().toString();
                // Track the session before writing a potentially large APK. Process death
                // during staging must leave an owned session that startup can reclaim.
                if (!prefs.edit().putInt("install_session", sessionId)
                        .putString("install_nonce", sessionNonce)
                        .putBoolean("install_requested", true).commit()) {
                    throw new IOException("Cannot persist staging session");
                }
                try (PackageInstaller.Session session = installer.openSession(sessionId);
                        InputStream input = new FileInputStream(file);
                        OutputStream output = session.openWrite("base.apk", 0, target.sizeBytes)) {
                    MessageDigest digest = MessageDigest.getInstance("SHA-256");
                    byte[] buffer = new byte[64 * 1024];
                    long copied = 0;
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        if (epoch != generation) throw new IOException("Canceled session");
                        copied += count;
                        if (copied > target.sizeBytes) {
                            throw new UpdateApkValidator.ValidationException(R.string.update_error_integrity);
                        }
                        output.write(buffer, 0, count);
                        digest.update(buffer, 0, count);
                    }
                    session.fsync(output);
                    // Verify the bytes actually copied into Android's private installer staging.
                    if (copied != target.sizeBytes
                            || !UpdatePolicy.hex(digest.digest()).equals(target.sha256)) {
                        throw new UpdateApkValidator.ValidationException(R.string.update_error_integrity);
                    }
                }
                int readySession = sessionId;
                String readyNonce = sessionNonce;
                main.post(() -> {
                    if (epoch != generation) {
                        try { installer.abandonSession(readySession); } catch (Exception ignored) { }
                        return;
                    }
                    installSession = readySession;
                    installNonce = readyNonce;
                    orphanInstall = false;
                    confirmationLaunched = false;
                    if (prefs.getInt("install_session", -1) != readySession
                            || !readyNonce.equals(prefs.getString("install_nonce", null))) {
                        abandonInstall();
                        publish(State.ERROR, R.string.update_error_storage, false, target.sizeBytes);
                        return;
                    }
                    Intent callback = new Intent(app, UpdateInstallReceiver.class)
                            .setAction(INSTALL_ACTION)
                            .setData(Uri.parse("vlcplayer-update://install/" + installNonce));
                    int flags = PendingIntent.FLAG_UPDATE_CURRENT;
                    if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
                    PendingIntent status = PendingIntent.getBroadcast(app, readySession, callback, flags);
                    io.execute(() -> {
                        try (PackageInstaller.Session session = installer.openSession(readySession)) {
                            session.commit(status.getIntentSender());
                        } catch (Exception ignored) {
                            main.post(() -> {
                                if (epoch != generation) return;
                                abandonInstall();
                                publish(State.ERROR, R.string.update_error_install, false, target.sizeBytes);
                            });
                        }
                    });
                });
            } catch (Exception e) {
                if (sessionId >= 0) {
                    try { installer.abandonSession(sessionId); } catch (Exception ignored) { }
                }
                int error = e instanceof UpdateApkValidator.ValidationException
                        ? ((UpdateApkValidator.ValidationException) e).errorResId
                        : R.string.update_error_install;
                main.post(() -> {
                    if (epoch != generation) return;
                    clearInstallState();
                    publish(State.ERROR, error, canRetryDownload(error), target.sizeBytes);
                });
            }
        });
    }

    void onInstallResult(Intent intent) {
        onMain(() -> {
            if (!INSTALL_ACTION.equals(intent.getAction()) || installSession < 0
                    || installNonce == null || intent.getData() == null
                    || !Uri.parse("vlcplayer-update://install/" + installNonce).equals(intent.getData())
                    || intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1) != installSession) return;
            // Only our explicit non-exported receiver accepts this owned-session callback.
            ++generation;
            reconcileInFlight = false;
            orphanInstall = false;
            int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS,
                    PackageInstaller.STATUS_FAILURE);
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                if (confirmationLaunched) return;
                Intent confirmation;
                if (Build.VERSION.SDK_INT >= 33) {
                    confirmation = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class);
                } else {
                    confirmation = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                }
                if (confirmation == null) {
                    abandonInstall();
                    publish(State.ERROR, R.string.update_error_install, false,
                            release == null ? 0 : release.sizeBytes);
                } else {
                    pendingConfirmation = confirmation;
                    confirmationLaunched = false;
                    notifyReady();
                    publish(State.INSTALLING, 0, false, release == null ? 0 : release.sizeBytes);
                }
            } else if (status == PackageInstaller.STATUS_SUCCESS) {
                installedVersion = installedVersion();
                clearInstallState();
                if (release != null && installedVersion >= release.versionCode) {
                    clearOwnedDownload();
                    publish(State.INSTALLED, 0, false, release.sizeBytes);
                } else {
                    publish(State.ERROR, R.string.update_error_install, false,
                            release == null ? 0 : release.sizeBytes);
                }
            } else {
                int error = status == PackageInstaller.STATUS_FAILURE_ABORTED
                        ? R.string.update_error_install_canceled
                        : status == PackageInstaller.STATUS_FAILURE_BLOCKED
                        ? R.string.update_error_install_blocked
                        : status == PackageInstaller.STATUS_FAILURE_CONFLICT
                        ? R.string.update_error_install_conflict
                        : status == PackageInstaller.STATUS_FAILURE_STORAGE
                        ? R.string.update_error_storage
                        : status == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE
                        ? R.string.update_error_incompatible : R.string.update_error_install;
                abandonInstall();
                publish(State.ERROR, error, false, release == null ? 0 : release.sizeBytes);
            }
        });
    }

    private DownloadStatus queryDownload(long id, File file, boolean recover) {
        DownloadStatus result = new DownloadStatus();
        if (downloads == null) return result;
        DownloadManager.Query query = new DownloadManager.Query();
        if (id > 0) query.setFilterById(id);
        if (id <= 0 && !recover) return result;
        try (Cursor cursor = downloads.query(query)) {
            if (cursor == null) return result;
            while (cursor.moveToNext()) {
                String uri = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI));
                // A persisted integer alone cannot authorize removing/adopting a download job.
                if (!Uri.fromFile(file).toString().equals(uri)) continue;
                result.id = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID));
                result.status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                result.reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));
                result.bytes = Math.max(0, cursor.getLong(cursor.getColumnIndexOrThrow(
                        DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)));
                return result;
            }
        }
        return result;
    }

    private File ownedFile(UpdateRelease target) throws IOException {
        File directory = app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (directory == null) return null;
        File root = directory.getCanonicalFile();
        File file = new File(root, "vlcplayer-update-" + target.versionCode + ".apk").getCanonicalFile();
        if (!root.equals(file.getParentFile())) throw new IOException("Not an owned update file");
        return file;
    }

    private void clearOwnedDownload() {
        cancelReadyNotification();
        if (release != null) {
            try {
                File file = ownedFile(release);
                if (file != null) {
                    DownloadStatus status = queryDownload(downloadId, file, downloadStarted);
                    if (status.id > 0 && downloads != null) downloads.remove(status.id);
                    if (file.isFile()) file.delete();
                }
            } catch (Exception ignored) { }
        }
        downloadId = -1;
        downloadStarted = false;
        prefs.edit().remove("download_id").remove("download_started").apply();
    }

    private void abandonInstall() {
        if (installSession >= 0) {
            try { installer.abandonSession(installSession); } catch (Exception ignored) { }
        }
        clearInstallState();
    }

    private void clearInstallState() {
        installSession = -1;
        installNonce = null;
        orphanInstall = false;
        installRequested = false;
        waitingPermission = false;
        pendingConfirmation = null;
        confirmationLaunched = false;
        prefs.edit().remove("install_session").remove("install_nonce")
                .remove("install_requested").remove("waiting_permission")
                .remove("install_error").apply();
        savedInstallError = 0;
        cancelReadyNotification();
    }

    private boolean isBusy() {
        return snapshot.state == State.DOWNLOADING || snapshot.state == State.VERIFYING
                || snapshot.state == State.INSTALLING;
    }

    private boolean canInstallPackages() {
        return Build.VERSION.SDK_INT < 26 || app.getPackageManager().canRequestPackageInstalls();
    }

    private static boolean isResumed(Activity activity) {
        if (activity.isFinishing() || activity.isDestroyed()) return false;
        if (activity instanceof LifecycleOwner) {
            return ((LifecycleOwner) activity).getLifecycle().getCurrentState()
                    .isAtLeast(Lifecycle.State.RESUMED);
        }
        return activity.hasWindowFocus();
    }

    private long installedVersion() {
        try {
            PackageInfo info = app.getPackageManager().getPackageInfo(app.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
        } catch (PackageManager.NameNotFoundException ignored) { return Long.MAX_VALUE; }
    }

    private void publish(State state, int error, boolean retry, long bytes) {
        if (state == State.ERROR && !retry && isReusableInstallError(error)) {
            savedInstallError = error;
            prefs.edit().putString("install_error", installErrorName(error)).apply();
        } else if (state != State.VERIFYING && state != State.CHECKING) {
            savedInstallError = 0;
            prefs.edit().remove("install_error").apply();
        }
        snapshot = new Snapshot(state, release, bytes, error, retry,
                release != null && release.versionCode > installedVersion);
        for (Listener listener : new ArrayList<>(listeners)) listener.onChanged(snapshot);
        schedulePoll();
    }

    private void schedulePoll() {
        main.removeCallbacks(poll);
        if (!listeners.isEmpty() && (snapshot.state == State.DOWNLOADING
                || snapshot.state == State.INSTALLING)) main.postDelayed(poll, 2000);
    }

    private void onMain(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) action.run();
        else main.post(action);
    }

    private static boolean canRetryDownload(int error) {
        return error == R.string.update_error_missing_file || error == R.string.update_error_integrity;
    }

    private static UpdateRecoveryPolicy.DownloadState downloadState(int status) {
        if (status == DownloadManager.STATUS_SUCCESSFUL) return UpdateRecoveryPolicy.DownloadState.SUCCESS;
        if (status == DownloadManager.STATUS_FAILED) return UpdateRecoveryPolicy.DownloadState.FAILED;
        if (status == DownloadManager.STATUS_PENDING || status == DownloadManager.STATUS_PAUSED
                || status == DownloadManager.STATUS_RUNNING) return UpdateRecoveryPolicy.DownloadState.ACTIVE;
        return UpdateRecoveryPolicy.DownloadState.NONE;
    }

    private static boolean isReusableInstallError(int error) {
        return error == R.string.update_error_install || error == R.string.update_error_install_canceled
                || error == R.string.update_error_install_blocked
                || error == R.string.update_error_install_conflict || error == R.string.update_error_storage;
    }

    private static String installErrorName(int error) {
        if (error == R.string.update_error_install_canceled) return "canceled";
        if (error == R.string.update_error_install_blocked) return "blocked";
        if (error == R.string.update_error_install_conflict) return "conflict";
        if (error == R.string.update_error_storage) return "storage";
        return "install";
    }

    private static int installErrorResource(String name) {
        if ("canceled".equals(name)) return R.string.update_error_install_canceled;
        if ("blocked".equals(name)) return R.string.update_error_install_blocked;
        if ("conflict".equals(name)) return R.string.update_error_install_conflict;
        if ("storage".equals(name)) return R.string.update_error_storage;
        if ("install".equals(name)) return R.string.update_error_install;
        return 0;
    }

    private void notifyReady() {
        NotificationManager manager = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null || (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(app,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)) return;
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(new NotificationChannel(
                CHANNEL_ID, app.getString(R.string.update_notification_title), NotificationManager.IMPORTANCE_DEFAULT));
        PendingIntent open = PendingIntent.getActivity(app, NOTIFICATION_ID,
                new Intent(app, UpdateActivity.class), PendingIntent.FLAG_UPDATE_CURRENT
                        | PendingIntent.FLAG_IMMUTABLE);
        try {
            manager.notify(NOTIFICATION_ID, new NotificationCompat.Builder(app, CHANNEL_ID)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle(app.getString(R.string.update_notification_title))
                    .setContentText(app.getString(R.string.update_notification_ready))
                    .setContentIntent(open).setAutoCancel(true).setOnlyAlertOnce(true).build());
        } catch (SecurityException ignored) { /* In-app state remains usable without permission. */ }
    }

    private void cancelReadyNotification() {
        NotificationManager manager = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.cancel(NOTIFICATION_ID);
    }

    private static String readUrl(String url, int limit) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("User-Agent", "VLCPlayer-Android");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) throw new IOException("HTTP failure");
            try (InputStream input = connection.getInputStream();
                    ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (output.size() + count > limit) throw new IOException("Response too large");
                    output.write(buffer, 0, count);
                }
                return new String(output.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally { connection.disconnect(); }
    }

    private static final class DownloadStatus {
        long id = -1;
        int status;
        int reason;
        long bytes;
    }

    private static final class Recovery {
        State state;
        long version;
        long id;
        long bytes;
        int error;
        boolean retry;
    }
}
