package cn.diandian.counter;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

final class UpdateFiles {
    interface Progress { void onProgress(int percent); }
    private UpdateFiles() { }
    static File ready(Context context) { return new File(new File(context.getFilesDir(), "updates"), "update.apk"); }

    static File download(Context context, UpdateManifest release, AtomicBoolean cancelled, Progress progress) throws IOException {
        File target = ready(context), directory = target.getParentFile();
        if (directory == null || (!directory.isDirectory() && !directory.mkdirs())) throw new IOException("无法创建更新目录");
        if (target.isFile()) {
            try { verify(context, target, release); progress.onProgress(100); return target; }
            catch (IOException invalid) { if (!target.delete()) throw new IOException("无法替换旧的更新文件"); }
        }
        File partial = new File(directory, "update.apk.part");
        HttpURLConnection connection = UpdateHttp.open(release.apkUrl);
        boolean complete = false;
        try {
            long announced = connection.getContentLengthLong();
            if (announced > 0 && announced != release.size) throw new IOException("下载包大小与版本信息不一致");
            try (InputStream input = connection.getInputStream(); FileOutputStream output = new FileOutputStream(partial)) {
                byte[] buffer = new byte[32_768]; int read, lastPercent = -1; long total = 0;
                while ((read = input.read(buffer)) != -1) {
                    if (cancelled.get() || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("已取消下载");
                    total += read;
                    if (total > release.size) throw new IOException("下载包大小超出预期");
                    output.write(buffer, 0, read);
                    int percent = (int) (total * 100 / release.size);
                    if (percent != lastPercent) { progress.onProgress(percent); lastPercent = percent; }
                }
                if (total != release.size) throw new IOException("下载未完成，请重试");
                output.getFD().sync();
            }
            if (cancelled.get()) throw new InterruptedIOException("已取消下载");
            verify(context, partial, release);
            if (!partial.renameTo(target)) throw new IOException("无法保存更新安装包");
            complete = true; return target;
        } finally { connection.disconnect(); if (!complete && partial.exists()) partial.delete(); }
    }

    static void verify(Context context, File apk, UpdateManifest release) throws IOException {
        if (!apk.isFile() || apk.length() != release.size || !sha256(apk).equalsIgnoreCase(release.sha256))
            throw new IOException("安装包完整性校验失败，请重新下载");
        try {
            PackageManager packages = context.getPackageManager();
            // Android 9/10 archive parsing collects certificates only when the legacy flag is also set.
            int flags = PackageManager.GET_SIGNATURES;
            if (Build.VERSION.SDK_INT >= 28) flags |= PackageManager.GET_SIGNING_CERTIFICATES;
            PackageInfo archive = packages.getPackageArchiveInfo(apk.getAbsolutePath(), flags);
            PackageInfo installed = packages.getPackageInfo(context.getPackageName(), flags);
            if (archive == null || !context.getPackageName().equals(archive.packageName) || versionCode(archive) != release.versionCode)
                throw new IOException("安装包应用标识或版本不匹配");
            Set<String> current = signers(installed), candidate = signers(archive);
            if (current.isEmpty() || !current.equals(candidate)) throw new IOException("安装包签名与当前应用不一致");
        } catch (PackageManager.NameNotFoundException missing) { throw new IOException("无法校验当前应用签名", missing); }
    }

    static long versionCode(PackageInfo info) { return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode; }

    @SuppressWarnings("deprecation")
    private static Set<String> signers(PackageInfo info) throws IOException {
        Signature[] signatures = Build.VERSION.SDK_INT >= 28 && info.signingInfo != null
                ? info.signingInfo.getApkContentsSigners() : info.signatures;
        Set<String> result = new HashSet<>();
        if (signatures != null) for (Signature signature : signatures) result.add(hex(digest().digest(signature.toByteArray())));
        return result;
    }

    static String sha256(File file) throws IOException {
        MessageDigest digest = digest();
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[32_768]; int size;
            while ((size = input.read(buffer)) != -1) digest.update(buffer, 0, size);
        }
        return hex(digest.digest());
    }
    private static MessageDigest digest() throws IOException {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IOException("设备不支持 SHA-256 校验", impossible); }
    }
    static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(Character.forDigit((value >>> 4) & 15, 16)).append(Character.forDigit(value & 15, 16));
        return result.toString();
    }
}
