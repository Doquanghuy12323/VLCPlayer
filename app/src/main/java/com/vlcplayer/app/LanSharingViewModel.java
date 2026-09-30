package com.vlcplayer.app;

import android.app.Application;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.OperationCanceledException;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Keeps the LAN server and its pending work across configuration changes. */
public final class LanSharingViewModel extends AndroidViewModel {
    private final LanSharingState state = new LanSharingState();
    private final MutableLiveData<LanSharingState.Snapshot> snapshots =
            new MutableLiveData<>(state.snapshot());
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService metadataExecutor = Executors.newSingleThreadExecutor();
    private final TranscodeManager manager;
    private MetadataTask metadataTask;
    private boolean cleared;

    public LanSharingViewModel(Application application) {
        super(application);
        manager = new TranscodeManager(application);
        TranscodeManager.cleanupLegacyCache(application);
    }

    public LiveData<LanSharingState.Snapshot> getSnapshots() { return snapshots; }
    public LanSharingState.Snapshot getSnapshot() { return state.snapshot(); }

    public void restoreSelection(String uri, String name, long size, boolean stopped) {
        if (cleared || state.snapshot().videoUri != null) return;
        state.restoreSelection(uri, name, size, stopped);
        publish();
    }

    public void selectVideo(Uri uri, int intentFlags) {
        if (cleared || uri == null) return;
        long ticket = state.selectVideo(uri.toString());
        if (ticket < 0) return;
        cancelMetadata();
        manager.stopServer();
        publish();
        MetadataTask task = new MetadataTask();
        metadataTask = task;
        task.future = metadataExecutor.submit(() -> prepareMetadata(uri, intentFlags, ticket, task));
    }

    private void prepareMetadata(Uri uri, int intentFlags, long ticket, MetadataTask task) {
        ContentResolver resolver = getApplication().getContentResolver();
        String name = "";
        long size = -1;
        try {
            task.throwIfCanceled();
            if ((intentFlags & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0) {
                try {
                    resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (SecurityException | UnsupportedOperationException ignored) {
                    // Some providers only support the temporary grant from the picker.
                }
            }
            task.throwIfCanceled();
            try (Cursor cursor = resolver.query(uri,
                    new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE},
                    null, null, null, task.cancellation)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    int sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE);
                    if (nameColumn >= 0 && !cursor.isNull(nameColumn)) name = cursor.getString(nameColumn);
                    if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) size = cursor.getLong(sizeColumn);
                }
            } catch (OperationCanceledException canceled) {
                throw canceled;
            } catch (Exception ignored) {
                // Metadata may be absent even when the actual document is readable.
            }
            task.throwIfCanceled();
            try (ParcelFileDescriptor descriptor = resolver.openFileDescriptor(uri, "r", task.cancellation)) {
                if (descriptor == null) throw new IOException("Missing video descriptor");
                if (!task.attachDescriptor(descriptor)) throw new OperationCanceledException();
                try {
                    task.throwIfCanceled();
                    long actualSize = descriptor.getStatSize();
                    if (actualSize >= 0) size = actualSize;
                } finally {
                    task.detachDescriptor(descriptor);
                }
            }
            task.throwIfCanceled();
            final String preparedName = name;
            final long preparedSize = size;
            mainHandler.post(() -> {
                if (cleared || task.isCanceled()) return;
                if (metadataTask == task) metadataTask = null;
                if (state.prepared(ticket, preparedName, preparedSize)) publish();
            });
        } catch (OperationCanceledException ignored) {
            // Stop or a replacement selection already invalidated this operation.
        } catch (Exception error) {
            mainHandler.post(() -> {
                if (cleared || task.isCanceled()) return;
                if (metadataTask == task) metadataTask = null;
                if (state.failed(ticket, LanSharingState.Error.SOURCE_UNAVAILABLE)) publish();
            });
        }
    }

    public void start() {
        if (cleared) return;
        long ticket = state.start();
        if (ticket < 0) return;
        cancelMetadata();
        publish();
        LanSharingState.Snapshot selected = state.snapshot();
        manager.startServer(Uri.parse(selected.videoUri), selected.videoName, selected.videoSize,
                new TranscodeManager.Callback() {
                    @Override public void onServerStarted(String lanUrl) {
                        update(() -> state.serverStarted(ticket, lanUrl));
                    }
                    @Override public void onClientConnected(String clientIp) {
                        update(() -> state.clientConnected(ticket, clientIp));
                    }
                    @Override public void onClientDisconnected() {
                        update(() -> state.clientDisconnected(ticket));
                    }
                    @Override public void onError(TranscodeManager.Error error) {
                        update(() -> state.failed(ticket, mapError(error)));
                    }
                    @Override public void onServerStopped() {
                        update(() -> state.serverStopped(ticket));
                    }
                });
    }

    public void stop() {
        if (cleared) return;
        // Invalidate callbacks before canceling IO or notifying the server.
        state.stop();
        cancelMetadata();
        publish();
        manager.stopServer();
    }

    private interface StateUpdate { boolean apply(); }

    private void update(StateUpdate update) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            if (!cleared && update.apply()) publish();
        } else {
            mainHandler.post(() -> {
                if (!cleared && update.apply()) publish();
            });
        }
    }

    private static LanSharingState.Error mapError(TranscodeManager.Error error) {
        if (error == null) return LanSharingState.Error.SERVER_FAILED;
        switch (error) {
            case NO_VIDEO:
            case SOURCE_UNAVAILABLE: return LanSharingState.Error.SOURCE_UNAVAILABLE;
            case UNKNOWN_SIZE: return LanSharingState.Error.SIZE_UNKNOWN;
            case NO_LAN: return LanSharingState.Error.NETWORK_UNAVAILABLE;
            case INVALID_ADDRESS: return LanSharingState.Error.ADDRESS_INVALID;
            default: return LanSharingState.Error.SERVER_FAILED;
        }
    }

    private void publish() { snapshots.setValue(state.snapshot()); }

    private void cancelMetadata() {
        MetadataTask pending = metadataTask;
        metadataTask = null;
        if (pending != null) pending.cancel();
    }

    @Override
    protected void onCleared() {
        cleared = true;
        state.stop();
        cancelMetadata();
        mainHandler.removeCallbacksAndMessages(null);
        metadataExecutor.shutdownNow();
        manager.destroy();
        super.onCleared();
    }

    private static final class MetadataTask {
        final CancellationSignal cancellation = new CancellationSignal();
        private volatile boolean canceled;
        Future<?> future;
        private ParcelFileDescriptor descriptor;

        synchronized boolean attachDescriptor(ParcelFileDescriptor opened) {
            if (isCanceled()) return false;
            descriptor = opened;
            return true;
        }

        synchronized void detachDescriptor(ParcelFileDescriptor opened) {
            if (descriptor == opened) descriptor = null;
        }

        void cancel() {
            canceled = true;
            if (future != null) future.cancel(true);
            final ParcelFileDescriptor opened;
            synchronized (this) {
                opened = descriptor;
                descriptor = null;
            }
            new Thread(() -> {
                try {
                    cancellation.cancel();
                } finally {
                    if (opened != null) {
                        try { opened.close(); } catch (IOException ignored) { }
                    }
                }
            }, "vlc-lan-metadata-cancel").start();
        }

        boolean isCanceled() { return canceled || cancellation.isCanceled(); }

        void throwIfCanceled() {
            if (isCanceled()) throw new OperationCanceledException();
        }
    }
}
