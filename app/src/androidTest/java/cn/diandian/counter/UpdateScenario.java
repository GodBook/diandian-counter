package cn.diandian.counter;

import android.app.Instrumentation;
import android.content.Context;
import android.net.Uri;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Live release checks run only on the dedicated emulator with the release signing key. */
final class UpdateScenario {
    private final Instrumentation instrumentation;
    private int checks;
    UpdateScenario(Instrumentation instrumentation) { this.instrumentation = instrumentation; }
    int run() throws Exception {
        Context context = instrumentation.getTargetContext();
        String json = UpdateHttp.text(UpdateManifest.LATEST, 65_536);
        UpdateManifest release = UpdateManifest.parse(json);
        check(release.versionCode == BuildConfig.VERSION_CODE && release.versionName.equals(BuildConfig.VERSION_NAME), "live GitHub latest matches the tested release");
        check(release.newerThan(1) && !release.newerThan(BuildConfig.VERSION_CODE), "old version detects update and current version does not");
        check(UpdateManifest.parse(release.json()).sha256.equals(release.sha256), "cached update manifest roundtrip");
        boolean rejected = false;
        try { UpdateManifest.parse(json.replace("cn.diandian.counter", "cn.other.counter")); } catch (IOException invalid) { rejected = true; }
        check(rejected, "wrong application manifest is rejected");
        rejected = false;
        try { UpdateHttp.text(UpdateManifest.LATEST, 1); } catch (IOException invalid) { rejected = true; }
        check(rejected, "network response size is bounded");
        android.content.pm.PackageManager packages = context.getPackageManager();
        int flags = android.content.pm.PackageManager.GET_SIGNATURES;
        if (android.os.Build.VERSION.SDK_INT >= 28) flags |= android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES;
        android.content.pm.PackageInfo installed = packages.getPackageInfo(context.getPackageName(), flags);
        android.content.pm.PackageInfo archive = packages.getPackageArchiveInfo(installed.applicationInfo.sourceDir, flags);
        check(archive != null, "platform parses the installed APK archive");
        android.content.pm.Signature[] installedSignatures = android.os.Build.VERSION.SDK_INT >= 28
                ? installed.signingInfo.getApkContentsSigners() : installed.signatures;
        android.content.pm.Signature[] archiveSignatures = android.os.Build.VERSION.SDK_INT >= 28
                ? archive.signingInfo.getApkContentsSigners() : archive.signatures;
        String installedHash = UpdateFiles.hex(java.security.MessageDigest.getInstance("SHA-256").digest(installedSignatures[0].toByteArray()));
        String archiveHash = UpdateFiles.hex(java.security.MessageDigest.getInstance("SHA-256").digest(archiveSignatures[0].toByteArray()));
        check(installedHash.equals(archiveHash), "installed APK parses with same certificate: " + installedHash + " vs " + archiveHash);
        AtomicInteger progress = new AtomicInteger(-1);
        File apk = UpdateFiles.download(context, release, new AtomicBoolean(), percent -> {
            if (percent < progress.get() || percent < 0 || percent > 100) throw new AssertionError("invalid download progress");
            progress.set(percent);
        });
        check(apk.isFile() && apk.length() == release.size, "live APK downloaded completely");
        check(progress.get() == 100, "download progress reaches 100");
        check(UpdateFiles.sha256(apk).equals(release.sha256), "downloaded bytes match release SHA-256");
        UpdateFiles.verify(context, apk, release); checks++;
        UpdateManifest badHash = new UpdateManifest(release.versionCode, release.versionName, release.apkUrl,
                "0000000000000000000000000000000000000000000000000000000000000000", release.size, release.minSdk, "");
        rejected = false; try { UpdateFiles.verify(context, apk, badHash); } catch (IOException invalid) { rejected = true; }
        check(rejected, "APK hash mismatch blocks installation");
        UpdateManifest wrongVersion = new UpdateManifest(release.versionCode + 1, release.versionName, release.apkUrl,
                release.sha256, release.size, release.minSdk, "");
        rejected = false; try { UpdateFiles.verify(context, apk, wrongVersion); } catch (IOException invalid) { rejected = true; }
        check(rejected, "APK version mismatch blocks installation");
        Uri uri = UpdateFileProvider.uri(context);
        try (InputStream file = context.getContentResolver().openInputStream(uri)) {
            check(file != null && file.read() == 'P' && file.read() == 'K', "read-only content URI serves the verified APK");
        }
        rejected = false;
        try { context.getContentResolver().openFileDescriptor(uri, "w"); } catch (IOException | SecurityException denied) { rejected = true; }
        check(rejected, "provider denies write access");
        rejected = false;
        try { context.getContentResolver().openInputStream(Uri.parse("content://" + context.getPackageName() + ".updates/../databases/diandian.db")); }
        catch (IOException | SecurityException | IllegalArgumentException denied) { rejected = true; }
        check(rejected, "provider cannot expose databases or arbitrary paths");

        UpdateManager manager = ((CounterApp) context.getApplicationContext()).updates();
        // Immediately after a real installed update, the manager first clears the obsolete download cache.
        long initializationDeadline = android.os.SystemClock.uptimeMillis() + 10_000;
        while (manager.state().phase == UpdateManager.Phase.CHECKING && android.os.SystemClock.uptimeMillis() < initializationDeadline)
            android.os.SystemClock.sleep(50);
        CountDownLatch checked = new CountDownLatch(1); AtomicReference<UpdateManager.State> result = new AtomicReference<>();
        UpdateManager.Listener listener = state -> {
            if (state.phase == UpdateManager.Phase.CURRENT || state.phase == UpdateManager.Phase.ERROR) { result.set(state); checked.countDown(); }
        };
        instrumentation.runOnMainSync(() -> { manager.attach(listener); manager.check(true); });
        check(checked.await(45, TimeUnit.SECONDS), "manual version check completes");
        instrumentation.runOnMainSync(() -> manager.detach(listener));
        check(result.get() != null && result.get().phase == UpdateManager.Phase.CURRENT, "current release reports already up to date");
        return checks;
    }
    private void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); checks++; }
}
