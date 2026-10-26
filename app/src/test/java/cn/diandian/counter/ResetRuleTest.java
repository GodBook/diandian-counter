package cn.diandian.counter;

import org.junit.Test;
import java.time.Instant;
import java.time.ZoneId;
import static org.junit.Assert.*;

public class ResetRuleTest {
    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private long at(String iso) { return Instant.parse(iso).toEpochMilli(); }
    @Test public void disabledRuleHasNoDeadline() {
        assertEquals(0, new ResetRule().nextAfter(at("2026-10-26T00:00:00Z"), SH));
    }
    @Test public void dailyBoundaryIsExclusive() {
        ResetRule r = new ResetRule(); r.type = ResetRule.DAILY; r.hour = 8;
        assertEquals(at("2026-10-26T00:00:00Z"), r.nextAfter(at("2026-10-25T23:59:59Z"), SH));
        assertEquals(at("2026-10-27T00:00:00Z"), r.nextAfter(at("2026-10-26T00:00:00Z"), SH));
    }
    @Test public void weeklyCrossesYearAndUsesMondayAsOne() {
        ResetRule r = new ResetRule(); r.type = ResetRule.WEEKLY; r.weekDay = 1; r.hour = 9;
        assertEquals(at("2027-01-04T01:00:00Z"), r.nextAfter(at("2026-12-31T12:00:00Z"), SH));
    }
    @Test public void monthEndClampsAndRecoversToThirtyFirst() {
        ResetRule r = new ResetRule(); r.type = ResetRule.MONTHLY; r.monthDay = 31;
        long feb = r.nextAfter(at("2026-01-31T00:00:00Z"), SH);
        assertEquals(at("2026-02-27T16:00:00Z"), feb);
        assertEquals(at("2026-03-30T16:00:00Z"), r.nextAfter(feb, SH));
    }
    @Test public void leapYearClampsToTwentyNinth() {
        ResetRule r = new ResetRule(); r.type = ResetRule.MONTHLY; r.monthDay = 31;
        assertEquals(at("2028-02-28T16:00:00Z"), r.nextAfter(at("2028-01-31T00:00:00Z"), SH));
    }
    @Test public void daylightSavingGapRunsAtNextValidLocalTime() {
        ResetRule r = new ResetRule(); r.type = ResetRule.DAILY; r.hour = 2; r.minute = 30;
        ZoneId zone = ZoneId.of("America/New_York");
        long gap = r.nextAfter(at("2026-03-07T08:00:00Z"), zone);
        assertEquals(at("2026-03-08T07:30:00Z"), gap);
        assertEquals(at("2026-03-09T06:30:00Z"), r.nextAfter(gap, zone));
    }
    @Test public void daylightSavingOverlapDoesNotResetTwice() {
        ResetRule r = new ResetRule(); r.type = ResetRule.DAILY; r.hour = 1; r.minute = 30;
        ZoneId zone = ZoneId.of("America/New_York");
        long first = at("2026-11-01T05:30:00Z");
        assertEquals(at("2026-11-02T06:30:00Z"), r.nextAfter(first, zone));
    }
    @Test public void fixedIntervalsIgnoreDaylightSavingAndTimeZone() {
        ResetRule r = new ResetRule(); r.type = ResetRule.INTERVAL; r.intervalMinutes = 1440;
        long start = at("2026-03-07T08:00:00Z");
        assertEquals(start + 86_400_000L, r.nextAfter(start, ZoneId.of("America/New_York")));
        assertEquals(r.nextAfter(start, SH), r.nextAfter(start, ZoneId.of("UTC")));
    }
    @Test public void equalityOnlyIncludesActiveRuleFields() {
        ResetRule a = new ResetRule(), b = new ResetRule(); b.hour = 12;
        assertEquals(a, b); assertEquals(a.hashCode(), b.hashCode());
        a.type = b.type = ResetRule.INTERVAL; assertEquals(a, b);
        b.intervalMinutes++; assertNotEquals(a, b);
    }
    @Test(expected = IllegalArgumentException.class) public void rejectsZeroInterval() {
        ResetRule r = new ResetRule(); r.type = ResetRule.INTERVAL; r.intervalMinutes = 0; r.validate();
    }
    @Test(expected = IllegalArgumentException.class) public void rejectsInvalidWeekday() {
        ResetRule r = new ResetRule(); r.type = ResetRule.WEEKLY; r.weekDay = 8; r.validate();
    }
    @Test public void inactiveDraftFieldsDoNotBlockChangingMode() {
        ResetRule r = new ResetRule(); r.monthDay = 0; r.intervalMinutes = 0; r.type = ResetRule.DAILY;
        assertTrue(r.nextAfter(at("2026-10-26T00:00:00Z"), SH) > 0);
    }
}
