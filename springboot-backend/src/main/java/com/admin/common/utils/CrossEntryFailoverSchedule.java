package com.admin.common.utils;

import java.time.DayOfWeek;
import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.util.List;

/** Pure time-window rules shared by validation and the failover scheduler. */
public final class CrossEntryFailoverSchedule {
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private CrossEntryFailoverSchedule() {
    }

    public record Spec(int daysMask, int startMinute, int endMinute) {
        public boolean activeAt(ZonedDateTime time) {
            int day = time.getDayOfWeek().getValue();
            int minute = time.getHour() * 60 + time.getMinute();
            if (containsDay(daysMask, day) && minute >= startMinute && minute < endMinute) {
                return true;
            }
            if (startMinute > endMinute && minute < endMinute) {
                int previousDay = day == 1 ? 7 : day - 1;
                return containsDay(daysMask, previousDay);
            }
            return startMinute > endMinute && containsDay(daysMask, day) && minute >= startMinute;
        }
    }

    public static int daysMask(List<Integer> days) {
        if (days == null || days.isEmpty()) throw new IllegalArgumentException("至少选择一个星期");
        int mask = 0;
        for (Integer day : days) {
            if (day == null || day < 1 || day > 7) throw new IllegalArgumentException("星期取值必须为 1-7");
            mask |= 1 << (day - 1);
        }
        return mask;
    }

    public static boolean containsDay(int mask, int day) {
        return day >= 1 && day <= 7 && (mask & (1 << (day - 1))) != 0;
    }

    public static int parseStart(String value) {
        return parse(value, false);
    }

    public static int parseEnd(String value) {
        return parse(value, true);
    }

    private static int parse(String value, boolean allowEndOfDay) {
        if (value == null || !value.matches("(?:[01]\\d|2[0-3]):[0-5]\\d" + (allowEndOfDay ? "|24:00" : ""))) {
            throw new IllegalArgumentException("时间格式必须为 HH:mm");
        }
        if ("24:00".equals(value)) return 1440;
        return Integer.parseInt(value.substring(0, 2)) * 60 + Integer.parseInt(value.substring(3));
    }

    public static boolean validInterval(int startMinute, int endMinute) {
        return startMinute >= 0 && startMinute < 1440 && endMinute > 0 && endMinute <= 1440
                && startMinute != endMinute;
    }

    public static boolean overlaps(Spec left, Spec right) {
        boolean[][] occupied = new boolean[7][1440];
        mark(occupied, left);
        for (int day = 1; day <= 7; day++) {
            if (!containsDay(right.daysMask(), day)) continue;
            int end = right.endMinute();
            if (right.startMinute() < end) {
                if (hasOverlap(occupied, day, right.startMinute(), end)) return true;
            } else {
                if (hasOverlap(occupied, day, right.startMinute(), 1440)) return true;
                int next = day == 7 ? 1 : day + 1;
                if (hasOverlap(occupied, next, 0, end)) return true;
            }
        }
        return false;
    }

    private static void mark(boolean[][] occupied, Spec spec) {
        for (int day = 1; day <= 7; day++) {
            if (!containsDay(spec.daysMask(), day)) continue;
            if (spec.startMinute() < spec.endMinute()) {
                markRange(occupied, day, spec.startMinute(), spec.endMinute());
            } else {
                markRange(occupied, day, spec.startMinute(), 1440);
                markRange(occupied, day == 7 ? 1 : day + 1, 0, spec.endMinute());
            }
        }
    }

    private static void markRange(boolean[][] occupied, int day, int start, int end) {
        for (int minute = start; minute < end; minute++) occupied[day - 1][minute] = true;
    }

    private static boolean hasOverlap(boolean[][] occupied, int day, int start, int end) {
        for (int minute = start; minute < end; minute++) {
            if (occupied[day - 1][minute]) return true;
        }
        return false;
    }

    public static int dayOf(ZonedDateTime time) {
        return DayOfWeek.from(time).getValue();
    }
}
