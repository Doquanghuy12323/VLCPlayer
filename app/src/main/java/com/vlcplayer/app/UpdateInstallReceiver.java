package com.vlcplayer.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Explicit PackageInstaller callback endpoint; non-exported in the manifest. */
public final class UpdateInstallReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent != null && UpdateManager.INSTALL_ACTION.equals(intent.getAction())) {
            UpdateManager.get(context).onInstallResult(intent);
        }
    }
}
