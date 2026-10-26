package cn.diandian.counter;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.io.IOException;
import java.io.Writer;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** Counter state and its audit trail always commit in the same SQLite transaction. */
public final class CounterStore extends SQLiteOpenHelper {
    private long revision;
    public synchronized long revision() { return revision; }
    public CounterStore(Context context) { this(context, "diandian.db"); }
    CounterStore(Context context, String databaseName) {
        super(context, databaseName, null, 1);
        setWriteAheadLoggingEnabled(true);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE counters (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, "
                + "value INTEGER NOT NULL DEFAULT 0 CHECK(value>=0), color INTEGER NOT NULL, rule_type INTEGER NOT NULL, "
                + "hour INTEGER NOT NULL, minute INTEGER NOT NULL, week_day INTEGER NOT NULL, month_day INTEGER NOT NULL, "
                + "interval_minutes INTEGER NOT NULL, next_reset INTEGER NOT NULL, created_at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE events (id INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER NOT NULL, "
                + "counter_id INTEGER NOT NULL, counter_name TEXT NOT NULL, action TEXT NOT NULL, "
                + "before_value INTEGER, after_value INTEGER, details TEXT NOT NULL)");
        db.execSQL("CREATE INDEX counters_due ON counters(next_reset)");
        db.execSQL("CREATE INDEX events_counter ON events(counter_id, id DESC)");
        db.execSQL("CREATE INDEX events_action ON events(action, id DESC)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        throw new IllegalStateException("Unsupported database migration");
    }

    public synchronized List<Counter> counters() {
        List<Counter> result = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT * FROM counters ORDER BY id", null)) {
            while (c.moveToNext()) result.add(readCounter(c));
        }
        return result;
    }

    public synchronized Counter counter(long id) { return find(getReadableDatabase(), id); }

    private Counter find(SQLiteDatabase db, long id) {
        try (Cursor c = db.rawQuery("SELECT * FROM counters WHERE id=?", new String[]{Long.toString(id)})) {
            if (!c.moveToFirst()) throw new IllegalArgumentException("计数器不存在，可能已被删除");
            return readCounter(c);
        }
    }

    private Counter readCounter(Cursor c) {
        Counter result = new Counter();
        result.id = c.getLong(0); result.name = c.getString(1); result.value = c.getLong(2); result.color = c.getInt(3);
        result.rule.type = c.getInt(4); result.rule.hour = c.getInt(5); result.rule.minute = c.getInt(6);
        result.rule.weekDay = c.getInt(7); result.rule.monthDay = c.getInt(8); result.rule.intervalMinutes = c.getLong(9);
        result.nextReset = c.getLong(10); result.createdAt = c.getLong(11);
        return result;
    }

    private ContentValues values(Counter c) {
        ContentValues v = new ContentValues();
        v.put("name", c.name); v.put("value", c.value); v.put("color", c.color);
        v.put("rule_type", c.rule.type); v.put("hour", c.rule.hour); v.put("minute", c.rule.minute);
        v.put("week_day", c.rule.weekDay); v.put("month_day", c.rule.monthDay);
        v.put("interval_minutes", c.rule.intervalMinutes); v.put("next_reset", c.nextReset); v.put("created_at", c.createdAt);
        return v;
    }

    public synchronized Counter save(Counter draft, long now) {
        String name = Strings.strip(draft.name);
        if (name.isEmpty() || name.codePointCount(0, name.length()) > 40)
            throw new IllegalArgumentException("请输入 1–40 个字的名称");
        if (draft.color < 0 || draft.color > 4) throw new IllegalArgumentException("请选择卡片颜色");
        draft.rule.validate();
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            applyDue(db, now);
            Counter result = draft.copy(); result.name = name;
            if (draft.id == 0) {
                result.value = 0; result.createdAt = now;
                result.nextReset = result.rule.nextAfter(now, ZoneId.systemDefault());
                result.id = db.insertOrThrow("counters", null, values(result));
                log(db, now, result, "CREATE", null, 0L, "自动重置：" + result.rule.describe());
            } else {
                Counter old = find(db, result.id);
                result.value = old.value; result.createdAt = old.createdAt;
                result.nextReset = old.rule.equals(result.rule) ? old.nextReset : result.rule.nextAfter(now, ZoneId.systemDefault());
                if (!old.name.equals(result.name)) log(db, now, result, "RENAME", old.value, old.value, old.name + " → " + result.name);
                if (old.color != result.color) log(db, now, result, "COLOR", old.value, old.value, "颜色 " + (old.color + 1) + " → " + (result.color + 1));
                if (!old.rule.equals(result.rule)) log(db, now, result, "RULE", old.value, old.value, old.rule.describe() + " → " + result.rule.describe());
                db.update("counters", values(result), "id=?", new String[]{Long.toString(result.id)});
            }
            db.setTransactionSuccessful(); revision++;
            return result;
        } finally { db.endTransaction(); }
    }

    public synchronized Counter increment(long id, long now) {
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try {
            applyDue(db, now);
            Counter c = find(db, id);
            if (c.value == Long.MAX_VALUE) throw new IllegalArgumentException("已达到可保存的最大计数值");
            long before = c.value++;
            ContentValues v = new ContentValues(); v.put("value", c.value);
            db.update("counters", v, "id=?", new String[]{Long.toString(id)});
            log(db, now, c, "INCREMENT", before, c.value, "点击卡片 +1");
            db.setTransactionSuccessful(); revision++; return c;
        } finally { db.endTransaction(); }
    }

    public synchronized void reset(long id, long now) {
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try {
            applyDue(db, now);
            Counter c = find(db, id);
            ContentValues v = new ContentValues(); v.put("value", 0);
            db.update("counters", v, "id=?", new String[]{Long.toString(id)});
            log(db, now, c, "RESET", c.value, 0L, "手动归零；原有自动重置时间保持不变");
            db.setTransactionSuccessful(); revision++;
        } finally { db.endTransaction(); }
    }

    public synchronized void delete(long id, long now) {
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try {
            applyDue(db, now);
            Counter c = find(db, id);
            log(db, now, c, "DELETE", c.value, null, "删除计数器；保留全部历史记录");
            db.delete("counters", "id=?", new String[]{Long.toString(id)});
            db.setTransactionSuccessful(); revision++;
        } finally { db.endTransaction(); }
    }

    public synchronized boolean reconcile(long now) {
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try {
            boolean changed = applyDue(db, now);
            db.setTransactionSuccessful(); if (changed) revision++; return changed;
        } finally { db.endTransaction(); }
    }

    private boolean applyDue(SQLiteDatabase db, long now) {
        List<Counter> due = new ArrayList<>();
        try (Cursor cursor = db.rawQuery("SELECT * FROM counters WHERE next_reset>0 AND next_reset<=?", new String[]{Long.toString(now)})) {
            while (cursor.moveToNext()) due.add(readCounter(cursor));
        }
        ZoneId zone = ZoneId.systemDefault();
        DateTimeFormatter format = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss XXX").withZone(zone);
        for (Counter c : due) {
            // Preserve every elapsed boundary in the audit trail, even when the app was closed.
            while (c.nextReset > 0 && c.nextReset <= now) {
                log(db, now, c, "AUTO_RESET", c.value, 0L, "计划时间 " + format.format(Instant.ofEpochMilli(c.nextReset))
                        + "；" + c.rule.describe());
                c.value = 0;
                c.nextReset = c.rule.nextAfter(c.nextReset, zone);
            }
            ContentValues v = new ContentValues(); v.put("value", 0); v.put("next_reset", c.nextReset);
            db.update("counters", v, "id=?", new String[]{Long.toString(c.id)});
        }
        return !due.isEmpty();
    }

    public synchronized void timeChanged(long now) {
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try {
            // Previously scheduled boundaries are settled before adopting the new local timezone.
            applyDue(db, now);
            for (Counter c : counters()) {
                if (c.rule.type == ResetRule.NONE || c.rule.type == ResetRule.INTERVAL) continue;
                c.nextReset = c.rule.nextAfter(now, ZoneId.systemDefault());
                ContentValues v = new ContentValues(); v.put("next_reset", c.nextReset);
                db.update("counters", v, "id=?", new String[]{Long.toString(c.id)});
            }
            log(db, now, null, "TIME_CHANGE", null, null, "按当前时区重新安排日历周期：" + ZoneId.systemDefault());
            db.setTransactionSuccessful(); revision++;
        } finally { db.endTransaction(); }
    }

    public synchronized long nextAlarm() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT MIN(next_reset) FROM counters WHERE next_reset>0", null)) {
            return c.moveToFirst() && !c.isNull(0) ? c.getLong(0) : 0;
        }
    }

    public synchronized void record(String action, String details) {
        log(getWritableDatabase(), System.currentTimeMillis(), null, action, null, null, details);
    }

    private void log(SQLiteDatabase db, long now, Counter c, String action, Long before, Long after, String details) {
        ContentValues v = new ContentValues();
        v.put("at", now); v.put("counter_id", c == null ? 0 : c.id); v.put("counter_name", c == null ? "应用" : c.name);
        v.put("action", action); v.put("before_value", before); v.put("after_value", after); v.put("details", details);
        db.insertOrThrow("events", null, v);
    }

    private String filter(long counterId, int category, List<String> args) {
        String where = "1=1";
        if (counterId >= 0) { where += " AND counter_id=?"; args.add(Long.toString(counterId)); }
        return where + switch (category) {
            case 1 -> " AND action='INCREMENT'";
            case 2 -> " AND action IN ('RESET','AUTO_RESET')";
            case 3 -> " AND action IN ('CREATE','RENAME','RULE','COLOR','DELETE')";
            case 4 -> " AND counter_id=0";
            default -> "";
        };
    }

    public synchronized List<AuditEvent> events(long counterId, int category, long beforeId, int limit) {
        List<String> args = new ArrayList<>();
        String where = filter(counterId, category, args);
        if (beforeId > 0) { where += " AND id<?"; args.add(Long.toString(beforeId)); }
        args.add(Integer.toString(limit));
        List<AuditEvent> result = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT * FROM events WHERE " + where + " ORDER BY id DESC LIMIT ?", args.toArray(new String[0]))) {
            while (cursor.moveToNext()) result.add(readEvent(cursor));
        }
        return result;
    }

    private AuditEvent readEvent(Cursor cursor) {
        AuditEvent e = new AuditEvent();
        e.id = cursor.getLong(0); e.at = cursor.getLong(1); e.counterId = cursor.getLong(2);
        e.name = cursor.getString(3); e.action = cursor.getString(4);
        e.before = cursor.isNull(5) ? null : cursor.getLong(5); e.after = cursor.isNull(6) ? null : cursor.getLong(6);
        e.details = cursor.getString(7); return e;
    }

    public synchronized long eventCount(long counterId, int category) {
        List<String> args = new ArrayList<>();
        String where = filter(counterId, category, args);
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM events WHERE " + where, args.toArray(new String[0]))) {
            c.moveToFirst(); return c.getLong(0);
        }
    }

    public synchronized List<Counter> historyCounters() {
        List<Counter> result = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT e.counter_id,e.counter_name,EXISTS(SELECT 1 FROM counters c WHERE c.id=e.counter_id) "
                + "FROM events e WHERE e.id IN (SELECT MAX(id) FROM events WHERE counter_id>0 GROUP BY counter_id) ORDER BY e.counter_id", null)) {
            while (c.moveToNext()) {
                Counter item = new Counter(); item.id = c.getLong(0); item.name = c.getString(1) + (c.getInt(2) == 0 ? "（已删除）" : ""); result.add(item);
            }
        }
        return result;
    }

    public void export(Writer writer) throws IOException {
        writer.write('\uFEFF');
        writer.write("记录ID,操作时间,计数器ID,计数器名称,操作,操作前,操作后,详情\r\n");
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSS XXX").withZone(ZoneId.systemDefault());
        try (Cursor c = getReadableDatabase().rawQuery("SELECT * FROM events ORDER BY id", null)) {
            while (c.moveToNext()) {
                AuditEvent e = readEvent(c);
                writer.write(e.id + "," + Csv.cell(formatter.format(Instant.ofEpochMilli(e.at))) + "," + e.counterId + ","
                        + Csv.cell(e.name) + "," + Csv.cell(AuditEvent.label(e.action)) + ","
                        + (e.before == null ? "" : e.before) + "," + (e.after == null ? "" : e.after) + "," + Csv.cell(e.details) + "\r\n");
            }
        }
    }
}
