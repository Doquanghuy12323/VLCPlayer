package com.vlcplayer.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;

/** Validated metadata for one signed APK in this repository. */
public final class UpdateRelease {
    public final long versionCode;
    public final String versionName;
    public final String packageName;
    public final int minSdk;
    public final String apkName;
    public final String apkUrl;
    public final long sizeBytes;
    public final String sha256;
    public final String changelog;
    public final String releaseUrl;

    private UpdateRelease(JSONObject json) throws JSONException, IOException {
        if (integer(json, "schemaVersion") != 1) throw invalid();
        versionCode = integer(json, "versionCode");
        versionName = string(json, "versionName");
        packageName = string(json, "packageName");
        long declaredSdk = integer(json, "minSdk");
        if (declaredSdk < 21 || declaredSdk > 100) throw invalid();
        minSdk = (int) declaredSdk;
        apkName = string(json, "apkName");
        apkUrl = string(json, "apkUrl");
        sizeBytes = integer(json, "sizeBytes");
        sha256 = string(json, "sha256");
        changelog = string(json, "changelog");
        releaseUrl = string(json, "releaseUrl");
        if (!UpdatePolicy.validVersion(versionCode) || versionName.trim().isEmpty()
                || versionName.length() > 128 || !UpdatePolicy.PACKAGE_NAME.equals(packageName)
                || minSdk < 21 || minSdk > 100 || !UpdatePolicy.APK_NAME.equals(apkName)
                || !UpdatePolicy.trustedAssetUrl(apkUrl, versionCode, apkName)
                || !UpdatePolicy.releaseUrl(versionCode).equals(releaseUrl)
                || !UpdatePolicy.validSize(sizeBytes) || !UpdatePolicy.validDigest(sha256)
                || changelog.length() > 65536) throw invalid();
    }

    public static UpdateRelease parse(String json) throws IOException {
        if (json == null || json.length() > 262144) throw invalid();
        try {
            return new UpdateRelease(new JSONObject(json));
        } catch (JSONException | IllegalArgumentException e) {
            throw new IOException("Invalid update metadata", e);
        }
    }

    public void validateAgainst(JSONObject githubRelease, int sdk, String installedPackage)
            throws IOException {
        try {
            if (!packageName.equals(installedPackage) || minSdk > sdk
                    || githubRelease.getBoolean("draft")
                    || githubRelease.getBoolean("prerelease")
                    || !("v" + versionCode).equals(githubRelease.getString("tag_name"))
                    || !releaseUrl.equals(githubRelease.getString("html_url"))) throw invalid();
            JSONArray assets = githubRelease.getJSONArray("assets");
            int apkCount = 0;
            int manifestCount = 0;
            for (int index = 0; index < assets.length(); index++) {
                JSONObject asset = assets.getJSONObject(index);
                if (apkName.equals(asset.getString("name"))) {
                    apkCount++;
                    if (!apkUrl.equals(asset.getString("browser_download_url"))
                            || sizeBytes != asset.getLong("size")
                            || !"uploaded".equals(asset.getString("state"))) throw invalid();
                    String digest = asset.optString("digest", "");
                    if (!digest.isEmpty() && !"null".equals(digest)
                            && !("sha256:" + sha256).equals(digest)) throw invalid();
                } else if ("update.json".equals(asset.getString("name"))) {
                    manifestCount++;
                    if (!UpdatePolicy.trustedAssetUrl(asset.getString("browser_download_url"),
                            versionCode, "update.json")
                            || !"uploaded".equals(asset.getString("state"))) throw invalid();
                }
            }
            if (apkCount != 1 || manifestCount != 1) throw invalid();
        } catch (JSONException | IllegalArgumentException e) {
            throw new IOException("Invalid release assets", e);
        }
    }

    public String toJson() {
        try {
            return new JSONObject().put("schemaVersion", 1).put("versionCode", versionCode)
                .put("versionName", versionName).put("packageName", packageName)
                .put("minSdk", minSdk).put("apkName", apkName).put("apkUrl", apkUrl)
                .put("sizeBytes", sizeBytes).put("sha256", sha256)
                .put("changelog", changelog).put("releaseUrl", releaseUrl).toString();
        } catch (JSONException e) {
            throw new IllegalStateException("Validated release could not be saved", e);
        }
    }

    private static IOException invalid() {
        return new IOException("Invalid update metadata or incompatible release");
    }

    private static long integer(JSONObject json, String key) throws JSONException, IOException {
        Object value = json.get(key);
        if (!(value instanceof Integer) && !(value instanceof Long)) throw invalid();
        return ((Number) value).longValue();
    }

    private static String string(JSONObject json, String key) throws JSONException, IOException {
        Object value = json.get(key);
        if (!(value instanceof String)) throw invalid();
        return (String) value;
    }
}
