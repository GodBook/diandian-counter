package cn.diandian.counter;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.ContentValues;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;

/** Run only on a dedicated emulator. UI interactions use the actual visible controls. */
final class UiScenario {
    private final Instrumentation instrumentation;
    private final CounterStore store;
    private int checks;
    UiScenario(Instrumentation i) {
        instrumentation = i; store = ((CounterApp) i.getTargetContext().getApplicationContext()).store();
    }
    int run() {
        check(store.counters().isEmpty(), "UI suite requires a fresh application on a dedicated emulator");
        Context context = instrumentation.getTargetContext();
        instrumentation.startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        instrumentation.waitForIdleSync();
        click("创建第一个计数器"); type("计数器名称", "喝水"); click("保存");
        check(store.counters().size() == 1, "create counter through dialog");
        Counter water = store.counters().get(0);
        for (int j = 0; j < 25; j++) click("喝水，当前 " + j + "，点按加一");
        check(store.counter(water.id).value == 25, "entire card increments on every click");
        check(store.eventCount(water.id, 1) == 25, "UI clicks each produce a log");
        click("管理计数器：喝水"); click("修改名称与周期"); type("计数器名称", "每日喝水"); click("保存");
        check(store.counter(water.id).name.equals("每日喝水") && store.counter(water.id).value == 25, "editing UI preserves count");
        click("新建计数器"); type("计数器名称", "阅读页数"); click("保存");
        check(store.counters().size() == 2, "multiple counters visible and independent");
        click("阅读页数，当前 0，点按加一");
        check(store.counter(water.id).value == 25 && store.counters().get(1).value == 1, "second counter has independent value");
        click("管理计数器：每日喝水"); click("重置为 0"); click("重置");
        check(store.counter(water.id).value == 0 && store.eventCount(water.id, 2) == 1, "confirmed reset in UI logged");
        click("操作记录"); check(find("每日喝水", 5000) != null, "history screen renders counter events");
        click("计数器"); click("管理计数器：阅读页数"); click("删除计数器"); click("删除");
        check(store.counters().size() == 1 && store.historyCounters().size() == 2, "UI deletion retains history filter");

        // Background receiver is tested in a separate process using alarm / verify-alarm modes.
        return checks;
    }

    int runUpdate() throws Exception {
        Context context = instrumentation.getTargetContext();
        UpdateManifest release = UpdateManifest.parse(UpdateHttp.text(UpdateManifest.LATEST, 65_536));
        check(release.newerThan(BuildConfig.VERSION_CODE), "upgrade UI test requires a lower installed test version");
        ((CounterApp) context.getApplicationContext()).updates().automatic(false);
        Counter seed = new Counter(); seed.name = "在线升级保留验证"; seed = store.save(seed, System.currentTimeMillis());
        for (int i = 0; i < 17; i++) store.increment(seed.id, System.currentTimeMillis());
        context.getSharedPreferences("verification", Context.MODE_PRIVATE).edit().putLong("upgrade_seed", seed.id).commit();
        instrumentation.startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        click("设置与使用说明"); click("检查更新");
        check(find("发现新版本 " + release.versionName, 30_000) != null, "settings displays the real newer GitHub release");
        click("下载更新");
        check(find("更新已下载", 30_000) != null, "in-app download reaches verified ready dialog");
        click("安装");
        if (find("允许安装更新", 3000) != null) {
            click("前往设置");
            check(find("Allow from this source", 15_000) != null, "opens Android unknown-source settings");
            click("Allow from this source");
            instrumentation.getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);
        }
        long end = SystemClock.uptimeMillis() + 30_000; boolean installer = false;
        while (SystemClock.uptimeMillis() < end) {
            AccessibilityNodeInfo root = instrumentation.getUiAutomation().getRootInActiveWindow();
            if (root != null && root.getPackageName() != null && root.getPackageName().toString().contains("packageinstaller")) { installer = true; break; }
            SystemClock.sleep(200);
        }
        check(installer, "verified APK handed to Android installer with URI permission");
        return checks;
    }

    int verifyUpgrade() throws Exception {
        Context context = instrumentation.getTargetContext();
        long id = context.getSharedPreferences("verification", Context.MODE_PRIVATE).getLong("upgrade_seed", 0);
        Counter seed = store.counter(id);
        check(seed.name.equals("在线升级保留验证") && seed.value == 17, "counter identity, name and value survive installed update");
        check(store.eventCount(id, 1) == 17, "all individual click audit entries survive update");
        check(store.events(id, 3, 0, 20).stream().anyMatch(e -> e.action.equals("CREATE")), "original creation audit survives update");
        check(!((CounterApp) context.getApplicationContext()).updates().automatic(), "settings survive update");
        check(UpdateFiles.versionCode(context.getPackageManager().getPackageInfo(context.getPackageName(), 0)) == 3, "Android actually installed release version 1.1.1");
        return checks;
    }

    private AccessibilityNodeInfo find(String expected, long timeout) {
        long end = SystemClock.uptimeMillis() + timeout;
        do {
            AccessibilityNodeInfo root = instrumentation.getUiAutomation().getRootInActiveWindow();
            AccessibilityNodeInfo node = search(root, expected);
            if (node != null) return node;
            SystemClock.sleep(150);
        } while (SystemClock.uptimeMillis() < end);
        return null;
    }
    private AccessibilityNodeInfo search(AccessibilityNodeInfo node, String expected) {
        if (node == null) return null;
        if (expected.contentEquals(node.getText() == null ? "" : node.getText())
                || expected.contentEquals(node.getContentDescription() == null ? "" : node.getContentDescription())) return node;
        for (int i = 0; i < node.getChildCount(); i++) { AccessibilityNodeInfo found = search(node.getChild(i), expected); if (found != null) return found; }
        return null;
    }
    private void click(String label) {
        AccessibilityNodeInfo node = find(label, 10_000);
        if (node == null) throw new AssertionError("Control not found: " + label);
        while (!node.isClickable() && node.getParent() != null) node = node.getParent();
        if (!node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) throw new AssertionError("Cannot click " + label);
        instrumentation.waitForIdleSync();
    }
    private void type(String label, String value) {
        AccessibilityNodeInfo node = find(label, 10_000); if (node == null) throw new AssertionError("Input not found: " + label);
        Bundle args = new Bundle(); args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) throw new AssertionError("Cannot type into " + label);
        instrumentation.waitForIdleSync();
    }
    private void check(boolean pass, String description) { if (!pass) throw new AssertionError(description); checks++; }
}
