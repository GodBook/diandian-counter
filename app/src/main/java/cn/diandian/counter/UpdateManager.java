package cn.diandian.counter;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Application-scoped work survives activity recreation; the counting UI never waits for the network. */
final class UpdateManager {
    enum Phase { IDLE, CHECKING, CURRENT, AVAILABLE, DOWNLOADING, READY, ERROR }
    interface Listener { void changed(State state); }
    static final class State {
        final Phase phase;
        final UpdateManifest release;
        final String message;
        final boolean manual;
        final int progress;
        State(Phase phase, UpdateManifest release, String message, boolean manual, int progress) {
            this.phase = phase; this.release = release; this.message = message; this.manual = manual; this.progress = progress;
        }
    }
    private final Context context;
    private final CounterStore store;
    private final SharedPreferences preferences;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private Listener listener;
    private volatile State state = new State(Phase.IDLE, null, "从 GitHub Releases 获取新版本", false, 0);
    private AtomicBoolean cancelled = new AtomicBoolean();

    UpdateManager(Context context, CounterStore store) {
        this.context = context.getApplicationContext(); this.store = store;
        preferences = context.getSharedPreferences("updates", Context.MODE_PRIVATE);
        String cached = preferences.getString("downloaded_manifest", "");
        if (!cached.isEmpty()) {
            state = new State(Phase.CHECKING, null, "正在检查已下载的更新…", false, 0);
            worker.execute(() -> {
                try {
                    UpdateManifest release = UpdateManifest.parse(cached);
                    if (!release.newerThan(BuildConfig.VERSION_CODE)) throw new IOException("版本已安装");
                    UpdateFiles.verify(this.context, UpdateFiles.ready(this.context), release);
                    publish(new State(Phase.READY, release, "更新已下载，点击安装", false, 100));
                } catch (IOException obsolete) {
                    preferences.edit().remove("downloaded_manifest").remove("pending_install").apply();
                    publish(new State(Phase.IDLE, null, "从 GitHub Releases 获取新版本", false, 0));
                }
            });
        }
    }
    State state() { return state; }
    void attach(Listener listener) { this.listener = listener; listener.changed(state); }
    void detach(Listener listener) { if (this.listener == listener) this.listener = null; }
    boolean automatic() { return preferences.getBoolean("automatic", true); }
    void automatic(boolean value) { preferences.edit().putBoolean("automatic", value).apply(); store.record("SETTING", "启动时检查更新：" + (value ? "开启" : "关闭")); }
    void later(long code) { preferences.edit().putLong("ignored_version", code).apply(); store.record("UPDATE_LATER", "稍后更新，版本号=" + code); }
    boolean ignored(long code) { return preferences.getLong("ignored_version", -1) == code; }
    boolean pendingInstall() { return preferences.getBoolean("pending_install", false); }
    void pendingInstall(boolean value) { preferences.edit().putBoolean("pending_install", value).apply(); }

    void autoCheck() {
        if (!automatic()) return;
        long now = System.currentTimeMillis(), last = preferences.getLong("last_check", 0);
        if (now >= last && now - last < 86_400_000L) return;
        check(false);
    }

    void check(boolean manual) {
        if (state.phase == Phase.CHECKING || state.phase == Phase.DOWNLOADING) return;
        preferences.edit().putLong("last_check", System.currentTimeMillis()).apply();
        publish(new State(Phase.CHECKING, null, "正在检查新版本…", manual, 0));
        store.record("UPDATE_CHECK", manual ? "手动检查 GitHub Releases" : "启动时检查 GitHub Releases");
        worker.execute(() -> {
            try {
                UpdateManifest release = UpdateManifest.parse(UpdateHttp.text(UpdateManifest.LATEST, 65_536));
                if (release.minSdk > Build.VERSION.SDK_INT && release.newerThan(BuildConfig.VERSION_CODE))
                    throw new IOException("新版本需要 Android API " + release.minSdk + " 及以上");
                boolean newer = release.newerThan(BuildConfig.VERSION_CODE);
                store.record("UPDATE_CHECK_RESULT", newer ? "发现新版本 " + release.versionName : "当前已是最新版本 " + BuildConfig.VERSION_NAME);
                publish(new State(newer ? Phase.AVAILABLE : Phase.CURRENT, release,
                        newer ? "发现新版本 " + release.versionName : "当前已是最新版本", manual, 0));
            } catch (IOException failed) { failure(failed, null, manual); }
        });
    }

    void download(UpdateManifest release) {
        if (state.phase == Phase.DOWNLOADING || state.phase == Phase.CHECKING) return;
        cancelled = new AtomicBoolean(); AtomicBoolean token = cancelled;
        preferences.edit().putString("downloaded_manifest", "").apply();
        publish(new State(Phase.DOWNLOADING, release, "正在下载更新…", true, 0));
        store.record("UPDATE_DOWNLOAD", "开始下载 " + release.versionName);
        worker.execute(() -> {
            try {
                UpdateFiles.download(context, release, token, percent -> {
                    if (!token.get()) publish(new State(Phase.DOWNLOADING, release, "下载更新 " + percent + "%", true, percent));
                });
                if (token.get()) return;
                preferences.edit().putString("downloaded_manifest", release.json()).apply();
                store.record("UPDATE_READY", "版本 " + release.versionName + " 已通过大小、SHA-256、应用标识、版本和签名校验");
                publish(new State(Phase.READY, release, "更新已下载，点击安装", true, 100));
            } catch (IOException failed) { if (!token.get()) failure(failed, release, true); }
        });
    }
    void cancelDownload() {
        if (state.phase != Phase.DOWNLOADING) return;
        cancelled.set(true); store.record("UPDATE_CANCEL", "取消更新下载");
        publish(new State(Phase.AVAILABLE, state.release, "下载已取消，可重新下载", false, 0));
    }

    /** Recheck bytes and signature immediately before granting the installer access. */
    void verifyForInstall(UpdateManifest release, Runnable success) {
        worker.execute(() -> {
            try { UpdateFiles.verify(context, UpdateFiles.ready(context), release); main.post(success); }
            catch (IOException failed) { failure(failed, release, true); }
        });
    }
    private void failure(IOException failure, UpdateManifest release, boolean manual) {
        String error = failure.getMessage();
        if (failure instanceof java.net.UnknownHostException || failure instanceof java.net.SocketTimeoutException
                || failure instanceof java.net.SocketException || failure instanceof javax.net.ssl.SSLException || error == null)
            error = "无法连接 GitHub，请检查网络后重试";
        store.record("UPDATE_ERROR", error);
        publish(new State(Phase.ERROR, release, error, manual, 0));
    }
    private void publish(State next) {
        state = next;
        main.post(() -> { if (listener != null && state == next) listener.changed(next); });
    }
}
