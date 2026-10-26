package cn.diandian.counter;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;

/** Public release metadata contains no user information or credentials. */
final class UpdateManifest {
    static final String REPOSITORY = "https://github.com/GodBook/diandian-counter";
    static final String LATEST = REPOSITORY + "/releases/latest/download/update.json";
    static final long MAX_APK_BYTES = 256L * 1024 * 1024;
    final long versionCode, size;
    final int minSdk;
    final String versionName, apkUrl, sha256, notes;

    UpdateManifest(long code, String version, String url, String hash, long bytes, int sdk, String description) throws IOException {
        if (code <= 0 || code > Integer.MAX_VALUE || version == null || !version.matches("[0-9]+\\.[0-9]+\\.[0-9]+"))
            throw new IOException("更新版本信息无效");
        if (url == null || !url.startsWith(REPOSITORY + "/releases/download/v" + version + "/")
                || !url.endsWith(".apk") || !secureGithubUrl(url)) throw new IOException("更新下载地址无效");
        if (hash == null || !hash.matches("[0-9a-fA-F]{64}") || bytes < 1 || bytes > MAX_APK_BYTES || sdk < 26)
            throw new IOException("更新校验信息无效");
        versionCode = code; versionName = version; apkUrl = url; sha256 = hash; size = bytes; minSdk = sdk;
        notes = description == null ? "" : description.substring(0, Math.min(description.length(), 8000));
    }

    static UpdateManifest parse(String json) throws IOException {
        try {
            JSONObject object = new JSONObject(json);
            if (object.getInt("schemaVersion") != 1 || !"cn.diandian.counter".equals(object.getString("packageName")))
                throw new IOException("更新包与当前应用不匹配");
            return new UpdateManifest(object.getLong("versionCode"), object.getString("versionName"), object.getString("apkUrl"),
                    object.getString("sha256"), object.getLong("size"), object.getInt("minSdk"), object.optString("notes", ""));
        } catch (JSONException invalid) { throw new IOException("服务器返回的版本信息无效", invalid); }
    }

    boolean newerThan(long installedCode) { return versionCode > installedCode; }

    String json() {
        try {
            return new JSONObject().put("schemaVersion", 1).put("packageName", "cn.diandian.counter")
                    .put("versionCode", versionCode).put("versionName", versionName).put("apkUrl", apkUrl)
                    .put("sha256", sha256).put("size", size).put("minSdk", minSdk).put("notes", notes).toString();
        } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
    }

    static boolean secureGithubUrl(String url) {
        try {
            URI uri = new URI(url); String host = uri.getHost();
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getRawUserInfo() == null
                    && (uri.getPort() == -1 || uri.getPort() == 443) && uri.getFragment() == null
                    && host != null && (host.equalsIgnoreCase("github.com") || host.equalsIgnoreCase("release-assets.githubusercontent.com")
                    || host.equalsIgnoreCase("objects.githubusercontent.com") || host.equalsIgnoreCase("github-releases.githubusercontent.com"));
        } catch (URISyntaxException | NullPointerException invalid) { return false; }
    }
}
