package cn.diandian.counter;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public final class ResetScheduler {
    private ResetScheduler() { }
    public static boolean precise(Context context) {
        AlarmManager alarms = context.getSystemService(AlarmManager.class);
        return Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms();
    }
    public static void schedule(Context context) {
        CounterStore store = ((CounterApp) context.getApplicationContext()).store();
        AlarmManager alarms = context.getSystemService(AlarmManager.class);
        PendingIntent pending = PendingIntent.getBroadcast(context, 1,
                new Intent(context, ResetReceiver.class).setAction("cn.diandian.counter.AUTO_RESET"),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        long next = store.nextAlarm();
        alarms.cancel(pending);
        if (next == 0) return;
        long trigger = Math.max(next, System.currentTimeMillis() + 100);
        try {
            if (precise(context)) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pending);
            else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pending);
        } catch (SecurityException denied) {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pending);
        }
    }
}
