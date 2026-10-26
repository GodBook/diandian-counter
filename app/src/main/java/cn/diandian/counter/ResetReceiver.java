package cn.diandian.counter;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ResetReceiver extends BroadcastReceiver {
    private static final ExecutorService WORK = Executors.newSingleThreadExecutor();
    @Override public void onReceive(Context context, Intent intent) {
        PendingResult pending = goAsync();
        Context application = context.getApplicationContext();
        WORK.execute(() -> {
            try {
                CounterStore store = ((CounterApp) application).store();
                String action = intent.getAction();
                if (Intent.ACTION_TIME_CHANGED.equals(action) || Intent.ACTION_TIMEZONE_CHANGED.equals(action))
                    store.timeChanged(System.currentTimeMillis());
                else {
                    store.reconcile(System.currentTimeMillis());
                    if (Intent.ACTION_BOOT_COMPLETED.equals(action)) store.record("BOOT", "恢复自动重置安排");
                }
                ResetScheduler.schedule(application);
            } finally { pending.finish(); }
        });
    }
}
