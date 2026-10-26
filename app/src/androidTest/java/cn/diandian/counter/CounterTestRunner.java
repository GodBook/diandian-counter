package cn.diandian.counter;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ContentValues;
import android.content.Context;
import android.os.Bundle;

import java.io.StringWriter;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/** Dependency-free instrumented suite; uses a separate database, never user data. */
public final class CounterTestRunner extends Instrumentation {
    private int checks;
    private boolean ui;
    private String mode;
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); mode = arguments == null ? "data" : arguments.getString("mode", "data"); ui = "ui".equals(mode); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        if ("alarm".equals(mode) || "verify-alarm".equals(mode)) {
            try {
                Context context = getTargetContext(); CounterStore real = ((CounterApp) context.getApplicationContext()).store();
                if ("alarm".equals(mode)) {
                    Counter draft = new Counter(); draft.name = "后台闹钟验证"; draft.rule.type = ResetRule.INTERVAL; draft.rule.intervalMinutes = 1;
                    Counter created = real.save(draft, System.currentTimeMillis()); real.increment(created.id, System.currentTimeMillis());
                    ContentValues values = new ContentValues(); long deadline = System.currentTimeMillis() + 15_000; values.put("next_reset", deadline);
                    real.getWritableDatabase().update("counters", values, "id=?", new String[]{Long.toString(created.id)});
                    ResetScheduler.schedule(context);
                    result.putString("stream", "\nARMED: id=" + created.id + ", deadline=" + deadline + ", precise=" + ResetScheduler.precise(context) + "\n");
                } else {
                    Counter last = real.counters().get(real.counters().size() - 1);
                    List<AuditEvent> events = real.events(last.id, 2, 0, 10);
                    if (last.value != 0 || events.isEmpty() || !events.get(0).action.equals("AUTO_RESET"))
                        throw new AssertionError("Receiver has not reset id=" + last.id + "; value=" + last.value + "; next=" + last.nextReset + "; now=" + System.currentTimeMillis());
                    result.putString("stream", "\nPASS: real background receiver reset id=" + last.id + " after process exit; audit=" + events.get(0).details + "\n");
                }
                finish(Activity.RESULT_OK, result);
            } catch (Throwable failure) { result.putString("stream", "\nFAIL: " + android.util.Log.getStackTraceString(failure)); finish(1, result); }
            return;
        }
        if (ui) {
            try {
                int passed = new UiScenario(this).run();
                result.putString("stream", "\nPASS: " + passed + " UI assertions: create, card taps, edit, multiple counters, reset, history and deletion.\n"); finish(Activity.RESULT_OK, result);
            } catch (Throwable failure) {
                result.putString("stream", "\nFAIL UI: " + android.util.Log.getStackTraceString(failure)); finish(1, result);
            }
            return;
        }
        Context context = getTargetContext();
        String database = "instrumented-verification.db";
        context.deleteDatabase(database);
        CounterStore store = new CounterStore(context, database);
        try {
            long start = System.currentTimeMillis();
            Counter draft = new Counter(); draft.name = "喝水";
            Counter water = store.save(draft, start);
            check(store.counters().size() == 1, "create persisted");
            for (int i = 0; i < 1000; i++) store.increment(water.id, start + i);
            check(store.counter(water.id).value == 1000, "1000 rapid taps keep exact value");
            check(store.eventCount(water.id, 1) == 1000, "every tap has a durable audit entry");

            Counter edited = store.counter(water.id); edited.name = "每日喝水"; edited.color = 2;
            edited.rule.type = ResetRule.INTERVAL; edited.rule.intervalMinutes = 1;
            long ruleStart = start + 2000;
            Counter scheduled = store.save(edited, ruleStart);
            check(scheduled.value == 1000, "renaming and scheduling preserve current value");
            check(scheduled.nextReset == ruleStart + 60_000, "interval anchored at save");
            scheduled = store.save(scheduled, ruleStart + 1000);
            check(scheduled.nextReset == ruleStart + 60_000, "unchanged rule does not shift anchor");
            check(store.eventCount(water.id, 3) == 4, "create rename color and rule each logged");

            store.reconcile(ruleStart + 180_000);
            check(store.counter(water.id).value == 0, "late reconciliation resets value");
            List<AuditEvent> resets = store.events(water.id, 2, 0, 20);
            check(resets.size() == 3, "each of three missed boundaries logged separately");
            check(resets.get(2).before == 1000 && resets.get(1).before == 0 && resets.get(0).before == 0, "catch-up preserves previous period value");
            check(store.counter(water.id).nextReset == ruleStart + 240_000, "next boundary stays on original interval");
            long count = store.eventCount(-1, 0);
            store.reconcile(ruleStart + 180_000);
            check(store.eventCount(-1, 0) == count, "duplicate receiver cannot duplicate automatic resets");
            store.increment(water.id, ruleStart + 181_000); store.reset(water.id, ruleStart + 182_000);
            check(store.counter(water.id).nextReset == ruleStart + 240_000, "manual reset preserves automatic deadline");

            Counter invalid = store.counter(water.id); invalid.name = "　 ";
            long beforeInvalid = store.eventCount(-1, 0);
            boolean rejected = false; try { store.save(invalid, ruleStart + 183_000); } catch (IllegalArgumentException expected) { rejected = true; }
            check(rejected && store.eventCount(-1, 0) == beforeInvalid && store.counter(water.id).name.equals("每日喝水"), "invalid update cannot mutate counter or audit trail");

            Counter other = new Counter(); other.name = "阅读"; other = store.save(other, start);
            long otherId = other.id; AtomicReference<Throwable> failure = new AtomicReference<>(); CountDownLatch done = new CountDownLatch(4);
            for (int i = 0; i < 4; i++) new Thread(() -> {
                try { for (int j = 0; j < 100; j++) store.increment(otherId, start + 1); }
                catch (Throwable error) { failure.set(error); } finally { done.countDown(); }
            }).start();
            done.await(); if (failure.get() != null) throw new AssertionError(failure.get());
            check(store.counter(otherId).value == 400 && store.eventCount(otherId, 1) == 400, "concurrent increments cannot lose counts or logs");

            Set<Long> ids = new HashSet<>(); long before = 0; int pages = 0;
            while (true) {
                List<AuditEvent> batch = store.events(otherId, 1, before, 80);
                if (batch.isEmpty()) break;
                for (AuditEvent event : batch) check(ids.add(event.id), "keyset pagination never repeats a row");
                before = batch.get(batch.size() - 1).id; pages++;
            }
            check(ids.size() == 400 && pages == 5, "history pagination retains all events");
            store.delete(otherId, start + 2);
            check(store.eventCount(otherId, 0) == 402, "deletion retains creation, all taps and deletion event");
            check(store.historyCounters().stream().anyMatch(c -> c.id == otherId && c.name.endsWith("（已删除）")), "deleted counter remains filterable");
            Counter sameName = new Counter(); sameName.name = "阅读"; sameName = store.save(sameName, start + 3);
            check(sameName.id != otherId && sameName.value == 0, "recreating a name cannot reuse deleted identity");

            Counter formula = new Counter(); formula.name = "=1+1,\"名称\""; formula = store.save(formula, start + 4);
            StringWriter csv = new StringWriter(); store.export(csv);
            check(csv.toString().startsWith("\uFEFF记录ID"), "CSV has UTF-8 BOM and header");
            check(csv.toString().contains("\"'=1+1,\"\"名称\"\"\""), "CSV neutralizes formulas and quotes names");
            check(csv.toString().split("\r\n").length == store.eventCount(-1, 0) + 1, "export includes all events without paging truncation");

            ContentValues max = new ContentValues(); max.put("value", Long.MAX_VALUE);
            store.getWritableDatabase().update("counters", max, "id=?", new String[]{Long.toString(formula.id)});
            long maxEvents = store.eventCount(-1, 0); rejected = false;
            try { store.increment(formula.id, start + 5); } catch (IllegalArgumentException expected) { rejected = true; }
            check(rejected && store.counter(formula.id).value == Long.MAX_VALUE && store.eventCount(-1, 0) == maxEvents, "overflow rejects atomically");

            long savedTotal = store.eventCount(-1, 0), savedId = water.id;
            store.close();
            CounterStore reopened = new CounterStore(context, database);
            check(reopened.eventCount(-1, 0) == savedTotal && reopened.counter(savedId).name.equals("每日喝水"), "counter and full audit trail survive reopening database");
            reopened.close();
            result.putString("stream", "\nPASS: " + checks + " assertions; creation, rapid/concurrent taps, rules, catch-up, persistence, deletion, pagination, export and overflow.\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            result.putString("stream", "\nFAIL after " + checks + " checks: " + android.util.Log.getStackTraceString(failure)); finish(1, result);
        } finally { store.close(); context.deleteDatabase(database); }
    }
    private void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description); checks++;
    }
}
