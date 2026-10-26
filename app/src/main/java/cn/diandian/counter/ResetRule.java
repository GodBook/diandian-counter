package cn.diandian.counter;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.Locale;
import java.util.Objects;

/** Calendar rules follow local wall-clock time; intervals follow elapsed epoch time. */
public final class ResetRule {
    public static final int NONE = 0, DAILY = 1, WEEKLY = 2, MONTHLY = 3, INTERVAL = 4;
    public int type = NONE;
    public int hour = 0, minute = 0, weekDay = 1, monthDay = 1;
    public long intervalMinutes = 60;

    public ResetRule copy() {
        ResetRule r = new ResetRule();
        r.type = type; r.hour = hour; r.minute = minute;
        r.weekDay = weekDay; r.monthDay = monthDay; r.intervalMinutes = intervalMinutes;
        return r;
    }

    public void validate() {
        if (type < NONE || type > INTERVAL
                || (type >= DAILY && type <= MONTHLY && (hour < 0 || hour > 23 || minute < 0 || minute > 59))
                || (type == WEEKLY && (weekDay < 1 || weekDay > 7))
                || (type == MONTHLY && (monthDay < 1 || monthDay > 31))
                || (type == INTERVAL && (intervalMinutes < 1 || intervalMinutes > 5_256_000L)))
            throw new IllegalArgumentException("重置周期不在允许的范围内");
    }

    public long nextAfter(long epochMillis, ZoneId zone) {
        validate();
        if (type == NONE) return 0;
        if (type == INTERVAL) return Math.addExact(epochMillis, Math.multiplyExact(intervalMinutes, 60_000L));
        ZonedDateTime now = Instant.ofEpochMilli(epochMillis).atZone(zone);
        LocalDate date = now.toLocalDate();
        LocalTime time = LocalTime.of(hour, minute);
        if (type == DAILY) {
            ZonedDateTime candidate = date.atTime(time).atZone(zone);
            if (candidate.toInstant().toEpochMilli() <= epochMillis)
                candidate = date.plusDays(1).atTime(time).atZone(zone);
            return candidate.toInstant().toEpochMilli();
        }
        if (type == WEEKLY) {
            LocalDate chosen = date.with(TemporalAdjusters.nextOrSame(DayOfWeek.of(weekDay)));
            ZonedDateTime candidate = chosen.atTime(time).atZone(zone);
            if (candidate.toInstant().toEpochMilli() <= epochMillis)
                candidate = chosen.plusWeeks(1).atTime(time).atZone(zone);
            return candidate.toInstant().toEpochMilli();
        }
        YearMonth month = YearMonth.from(date);
        ZonedDateTime candidate = month.atDay(Math.min(monthDay, month.lengthOfMonth())).atTime(time).atZone(zone);
        if (candidate.toInstant().toEpochMilli() <= epochMillis) {
            month = month.plusMonths(1);
            candidate = month.atDay(Math.min(monthDay, month.lengthOfMonth())).atTime(time).atZone(zone);
        }
        return candidate.toInstant().toEpochMilli();
    }

    public String describe() {
        String time = String.format(Locale.CHINA, "%02d:%02d", hour, minute);
        return switch (type) {
            case DAILY -> "每天 " + time;
            case WEEKLY -> "每周" + "一二三四五六日".charAt(weekDay - 1) + " " + time;
            case MONTHLY -> "每月 " + monthDay + " 日 " + time;
            case INTERVAL -> intervalMinutes % 1440 == 0 ? "每 " + intervalMinutes / 1440 + " 天"
                    : intervalMinutes % 60 == 0 ? "每 " + intervalMinutes / 60 + " 小时" : "每 " + intervalMinutes + " 分钟";
            default -> "不自动重置";
        };
    }

    @Override public boolean equals(Object o) {
        if (!(o instanceof ResetRule r) || type != r.type) return false;
        return switch (type) {
            case DAILY -> hour == r.hour && minute == r.minute;
            case WEEKLY -> hour == r.hour && minute == r.minute && weekDay == r.weekDay;
            case MONTHLY -> hour == r.hour && minute == r.minute && monthDay == r.monthDay;
            case INTERVAL -> intervalMinutes == r.intervalMinutes;
            default -> true;
        };
    }

    @Override public int hashCode() {
        return switch (type) {
            case DAILY -> Objects.hash(type, hour, minute);
            case WEEKLY -> Objects.hash(type, hour, minute, weekDay);
            case MONTHLY -> Objects.hash(type, hour, minute, monthDay);
            case INTERVAL -> Objects.hash(type, intervalMinutes);
            default -> Objects.hash(type);
        };
    }
}
