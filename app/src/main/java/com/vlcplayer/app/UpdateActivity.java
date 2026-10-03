package com.vlcplayer.app;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.text.format.Formatter;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

/** A foreground view of the application-owned updater; downloading outlives this screen. */
public final class UpdateActivity extends AppCompatActivity {
    private static final String STATE_PERMISSION_RETURN = "update_permission_return";

    private UpdateManager manager;
    private TextView installedVersion, latestVersion, downloadSize, status, details, progressText;
    private TextView changelog;
    private View releaseDetails;
    private ProgressBar progress;
    private Button check, primary, cancel, later;
    private boolean resumed;
    private boolean requestedInstall;
    private boolean permissionReturn;
    private final UpdateManager.Listener listener = this::render;
    private final Runnable foregroundContinuation = () -> {
        if (!resumed || isFinishing() || isDestroyed()) return;
        UpdateManager.State state = manager.snapshot().state;
        if (state == UpdateManager.State.CHECKING || state == UpdateManager.State.VERIFYING) {
            // Keep the permission return until recovery has verified the owned APK.
            return;
        }
        if (permissionReturn) {
            permissionReturn = false;
            manager.resumeInstallation(this);
        }
        if (manager.hasPendingInstallationAction()) manager.resumeInstallation(this);
        render(manager.snapshot());
    };

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(AppLanguageManager.applyLanguage(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        new PrivacyManager(this).applyWindowSecurity(this);
        setContentView(R.layout.activity_update);
        manager = UpdateManager.get(this);
        installedVersion = findViewById(R.id.update_installed_version);
        latestVersion = findViewById(R.id.update_latest_version);
        downloadSize = findViewById(R.id.update_download_size);
        status = findViewById(R.id.update_status);
        details = findViewById(R.id.update_details);
        progressText = findViewById(R.id.update_progress_text);
        changelog = findViewById(R.id.update_changelog);
        releaseDetails = findViewById(R.id.update_release_details);
        progress = findViewById(R.id.update_progress);
        check = findViewById(R.id.update_check);
        primary = findViewById(R.id.update_primary);
        cancel = findViewById(R.id.update_cancel);
        later = findViewById(R.id.update_later);

        if (savedInstanceState != null) {
            permissionReturn = savedInstanceState.getBoolean(STATE_PERMISSION_RETURN);
        }
        findViewById(R.id.update_back).setOnClickListener(v -> finish());
        installedVersion.setText(getString(R.string.update_installed_version, installedName()));
        check.setOnClickListener(v -> manager.checkForUpdate());
        cancel.setOnClickListener(v -> manager.cancelDownload());
        later.setOnClickListener(v -> {
            manager.snooze();
            finish();
        });
        render(manager.snapshot());
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        manager.addListener(listener);
        manager.reconcile();
        render(manager.snapshot());
        // AndroidX advances to RESUMED after onResume returns. Continue the explicit
        // permission round trip only after that transition, without consuming it early.
        primary.removeCallbacks(foregroundContinuation);
        primary.post(foregroundContinuation);
    }

    @Override
    protected void onPause() {
        resumed = false;
        primary.removeCallbacks(foregroundContinuation);
        if (requestedInstall
                && manager.snapshot().state == UpdateManager.State.WAITING_PERMISSION) {
            permissionReturn = true;
        }
        requestedInstall = false;
        manager.removeListener(listener);
        super.onPause();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putBoolean(STATE_PERMISSION_RETURN, permissionReturn
                || (requestedInstall
                    && manager.snapshot().state == UpdateManager.State.WAITING_PERMISSION));
        super.onSaveInstanceState(outState);
    }

    private String installedName() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            if (info.versionName != null && !info.versionName.isEmpty()) return info.versionName;
        } catch (android.content.pm.PackageManager.NameNotFoundException ignored) {
            // The build version still describes this running application.
        }
        return BuildConfig.VERSION_NAME;
    }

    private void requestInstallation() {
        if (!resumed || isFinishing() || isDestroyed()) return;
        requestedInstall = true;
        manager.install(this);
    }

    private void render(UpdateManager.Snapshot snapshot) {
        if (isFinishing() || isDestroyed()) return;
        UpdateRelease release = snapshot.release;
        releaseDetails.setVisibility(release == null ? View.GONE : View.VISIBLE);
        if (release != null) {
            latestVersion.setText(getString(R.string.update_latest_version, release.versionName));
            downloadSize.setText(getString(R.string.update_download_size,
                    Formatter.formatShortFileSize(this, release.sizeBytes)));
            changelog.setText(release.changelog == null || release.changelog.trim().isEmpty()
                    ? getString(R.string.update_no_changelog) : release.changelog);
        }

        primary.setVisibility(View.GONE);
        primary.setOnClickListener(null);
        cancel.setVisibility(View.GONE);
        cancel.setText(R.string.update_cancel_download);
        later.setVisibility(View.GONE);
        progress.setVisibility(View.GONE);
        progressText.setVisibility(View.GONE);
        details.setText("");
        boolean reusableFile = snapshot.state == UpdateManager.State.READY
                || snapshot.state == UpdateManager.State.WAITING_PERMISSION
                || (snapshot.state == UpdateManager.State.ERROR && !snapshot.retryDownload
                    && release != null && isInstallationError(snapshot.errorResId));
        check.setEnabled(!isBusy(snapshot.state) && !reusableFile);
        if (reusableFile) {
            cancel.setText(R.string.update_discard_download);
            cancel.setVisibility(View.VISIBLE);
        }

        switch (snapshot.state) {
            case CHECKING:
                status.setText(R.string.update_status_checking);
                showIndeterminate();
                break;
            case AVAILABLE:
                status.setText(R.string.update_status_available);
                details.setText(R.string.update_details_available);
                showPrimary(R.string.update_download, v -> manager.download());
                later.setVisibility(View.VISIBLE);
                break;
            case DOWNLOADING:
                status.setText(R.string.update_status_downloading);
                details.setText(R.string.update_details_downloading);
                progress.setVisibility(View.VISIBLE);
                progressText.setVisibility(View.VISIBLE);
                boolean known = snapshot.totalBytes > 0;
                progress.setIndeterminate(!known);
                if (known) {
                    int percent = (int) Math.min(100, Math.max(0,
                            snapshot.downloadedBytes * 100.0 / snapshot.totalBytes));
                    progress.setProgress(percent);
                    progressText.setText(getString(R.string.update_progress_known, percent,
                            Formatter.formatShortFileSize(this, Math.max(0, snapshot.downloadedBytes)),
                            Formatter.formatShortFileSize(this, snapshot.totalBytes)));
                } else {
                    progressText.setText(getString(R.string.update_progress_unknown,
                            Formatter.formatShortFileSize(this, Math.max(0, snapshot.downloadedBytes))));
                }
                cancel.setVisibility(View.VISIBLE);
                break;
            case VERIFYING:
                status.setText(R.string.update_status_verifying);
                details.setText(R.string.update_details_verifying);
                showIndeterminate();
                cancel.setVisibility(View.VISIBLE);
                break;
            case READY:
                status.setText(R.string.update_status_ready);
                details.setText(R.string.update_details_ready);
                showPrimary(R.string.update_install, v -> requestInstallation());
                later.setVisibility(View.VISIBLE);
                break;
            case WAITING_PERMISSION:
                status.setText(R.string.update_status_permission);
                details.setText(R.string.update_details_permission);
                showPrimary(R.string.update_allow_install, v -> requestInstallation());
                later.setVisibility(View.VISIBLE);
                break;
            case INSTALLING:
                status.setText(R.string.update_status_installing);
                details.setText(R.string.update_details_installing);
                showIndeterminate();
                break;
            case UP_TO_DATE:
                status.setText(R.string.update_status_current);
                details.setText(R.string.update_details_current);
                break;
            case INSTALLED:
                status.setText(R.string.update_status_installed);
                details.setText(R.string.update_details_installed);
                installedVersion.setText(getString(R.string.update_installed_version, installedName()));
                break;
            case ERROR:
                status.setText(R.string.update_status_error);
                details.setText(snapshot.errorResId != 0
                        ? snapshot.errorResId : R.string.update_error_network);
                if (snapshot.retryDownload && release != null) {
                    showPrimary(R.string.update_retry_download, v -> manager.download());
                } else if (release != null && isInstallationError(snapshot.errorResId)) {
                    showPrimary(R.string.update_retry_install, v -> requestInstallation());
                } else {
                    showPrimary(R.string.update_retry, v -> manager.checkForUpdate());
                }
                later.setVisibility(snapshot.updateAvailable ? View.VISIBLE : View.GONE);
                break;
            case IDLE:
            default:
                status.setText(R.string.update_status_idle);
                details.setText(R.string.update_details_idle);
                break;
        }
        if (resumed && (manager.hasPendingInstallationAction()
                || (permissionReturn && snapshot.state == UpdateManager.State.WAITING_PERMISSION))) {
            primary.removeCallbacks(foregroundContinuation);
            primary.post(foregroundContinuation);
        }
    }

    private void showPrimary(int text, View.OnClickListener action) {
        primary.setText(text);
        primary.setOnClickListener(action);
        primary.setVisibility(View.VISIBLE);
    }

    private void showIndeterminate() {
        progress.setIndeterminate(true);
        progress.setVisibility(View.VISIBLE);
    }

    private static boolean isBusy(UpdateManager.State state) {
        return state == UpdateManager.State.CHECKING || state == UpdateManager.State.DOWNLOADING
                || state == UpdateManager.State.VERIFYING || state == UpdateManager.State.INSTALLING;
    }

    private static boolean isInstallationError(int error) {
        return error == R.string.update_error_install || error == R.string.update_error_install_canceled
                || error == R.string.update_error_install_blocked
                || error == R.string.update_error_install_conflict
                || error == R.string.update_error_storage;
    }
}
