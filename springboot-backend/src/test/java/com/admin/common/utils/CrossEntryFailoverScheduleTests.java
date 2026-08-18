package com.admin.common.utils;

import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrossEntryFailoverScheduleTests {
    @Test
    void matchesBeijingWeekdayWindow() {
        var spec = new CrossEntryFailoverSchedule.Spec(
                CrossEntryFailoverSchedule.daysMask(List.of(1, 2, 3, 4, 5)), 9 * 60, 11 * 60);

        assertTrue(spec.activeAt(ZonedDateTime.parse("2026-08-17T10:00:00+08:00")));
        assertFalse(spec.activeAt(ZonedDateTime.parse("2026-08-17T11:00:00+08:00")));
        assertFalse(spec.activeAt(ZonedDateTime.parse("2026-08-16T10:00:00+08:00")));
    }

    @Test
    void supportsCrossMidnightWindow() {
        var spec = new CrossEntryFailoverSchedule.Spec(
                CrossEntryFailoverSchedule.daysMask(List.of(1)), 23 * 60, 2 * 60);

        assertTrue(spec.activeAt(ZonedDateTime.parse("2026-08-17T23:30:00+08:00")));
        assertTrue(spec.activeAt(ZonedDateTime.parse("2026-08-18T01:30:00+08:00")));
        assertFalse(spec.activeAt(ZonedDateTime.parse("2026-08-18T02:00:00+08:00")));
    }

    @Test
    void detectsOverlappingWindowsIncludingCrossMidnight() {
        var overnight = new CrossEntryFailoverSchedule.Spec(
                CrossEntryFailoverSchedule.daysMask(List.of(1)), 23 * 60, 2 * 60);
        var nextDay = new CrossEntryFailoverSchedule.Spec(
                CrossEntryFailoverSchedule.daysMask(List.of(2)), 1 * 60, 3 * 60);

        assertTrue(CrossEntryFailoverSchedule.overlaps(overnight, nextDay));
    }
}
