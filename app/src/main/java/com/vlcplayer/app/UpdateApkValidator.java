package com.vlcplayer.app;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Checks the downloaded bytes and identity before handing them to Android's installer. */
public final class UpdateApkValidator {
    private UpdateApkValidator() { }

    public static final class ValidationException extends IOException {
        public final int errorResId;

        ValidationException(int errorResId) {
            super("APK validation failed");
            this.errorResId = errorResId;
        }
    }

    @SuppressWarnings("deprecation")
    public static void verify(Context context, File file, UpdateRelease release)
            throws IOException {
        if (release == null || file == null || !file.isFile()) {
            throw new ValidationException(R.string.update_error_missing_file);
        }
        if (file.length() != release.sizeBytes) {
            throw new ValidationException(R.string.update_error_integrity);
        }
        if (!release.sha256.equals(sha256(file))) {
            throw new ValidationException(R.string.update_error_integrity);
        }
        PackageManager pm = context.getPackageManager();
        int flags = Build.VERSION.SDK_INT >= 28
            ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo archive = pm.getPackageArchiveInfo(file.getAbsolutePath(), flags);
        if (archive == null) throw new ValidationException(R.string.update_error_package);
        if (!context.getPackageName().equals(archive.packageName)
                || !release.packageName.equals(archive.packageName)) {
            throw new ValidationException(R.string.update_error_package);
        }
        PackageInfo installed;
        try {
            installed = pm.getPackageInfo(context.getPackageName(), flags);
        } catch (PackageManager.NameNotFoundException e) {
            throw new ValidationException(R.string.update_error_package);
        }
        long archiveCode = Build.VERSION.SDK_INT >= 28
            ? archive.getLongVersionCode() : archive.versionCode;
        long installedCode = Build.VERSION.SDK_INT >= 28
            ? installed.getLongVersionCode() : installed.versionCode;
        if (archiveCode != release.versionCode || archiveCode <= installedCode
                || !release.versionName.equals(archive.versionName)) {
            throw new ValidationException(R.string.update_error_version);
        }
        if (release.minSdk > Build.VERSION.SDK_INT || (Build.VERSION.SDK_INT >= 24
                && archive.applicationInfo != null
                && archive.applicationInfo.minSdkVersion > Build.VERSION.SDK_INT)) {
            throw new ValidationException(R.string.update_error_incompatible);
        }
        if (Build.VERSION.SDK_INT >= 24 && archive.applicationInfo != null
                && archive.applicationInfo.minSdkVersion != release.minSdk) {
            throw new ValidationException(R.string.update_error_metadata);
        }
        boolean compatible;
        if (Build.VERSION.SDK_INT >= 28) {
            compatible = archive.signingInfo != null && installed.signingInfo != null
                && UpdatePolicy.compatibleSigners(
                    bytes(installed.signingInfo.getApkContentsSigners()),
                    installed.signingInfo.hasMultipleSigners(),
                    bytes(archive.signingInfo.getApkContentsSigners()),
                    archive.signingInfo.hasMultipleSigners(),
                    bytes(archive.signingInfo.getSigningCertificateHistory()));
        } else {
            compatible = UpdatePolicy.sameSignerSet(bytes(installed.signatures),
                bytes(archive.signatures));
        }
        if (!compatible) throw new ValidationException(R.string.update_error_signature);
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
    }

    public static String sha256(File file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (FileInputStream stream = new FileInputStream(file)) {
                byte[] buffer = new byte[65536];
                int count;
                while ((count = stream.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
                    digest.update(buffer, 0, count);
                }
            }
            return UpdatePolicy.hex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 unavailable", e);
        }
    }

    private static byte[][] bytes(Signature[] signatures) {
        if (signatures == null) return null;
        byte[][] result = new byte[signatures.length][];
        for (int index = 0; index < signatures.length; index++) {
            result[index] = signatures[index] == null ? null : signatures[index].toByteArray();
        }
        return result;
    }
}
