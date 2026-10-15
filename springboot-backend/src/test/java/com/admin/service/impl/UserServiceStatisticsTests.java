package com.admin.service.impl;

import com.admin.entity.StatisticsFlow;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UserServiceStatisticsTests {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final long NOW = ZonedDateTime.of(2026, 10, 12, 14, 20, 0, 0, ZONE).toInstant().toEpochMilli();

    private static StatisticsFlow sample(long userId, int day, int hour, long flow) {
        StatisticsFlow sample = new StatisticsFlow();
        sample.setUserId(userId);
        sample.setFlow(flow);
        sample.setCreatedTime(ZonedDateTime.of(2026, 10, day, hour, 0, 2, 0, ZONE).toInstant().toEpochMilli());
        return sample;
    }

    private static StatisticsFlow sample(long userId, int day, int hour, long in, long out) {
        StatisticsFlow sample = sample(userId, day, hour, in + out);
        sample.setInFlow(in);
        sample.setOutFlow(out);
        return sample;
    }

    @Test
    void hourlyBucketsCarryInboundAndOutboundSeparately() {
        List<StatisticsFlow> buckets = UserServiceImpl.bucketLast24Hours(
                List.of(sample(1L, 12, 13, 100L, 20L), sample(2L, 12, 13, 5L, 7L)), 1L, NOW, ZONE);

        assertEquals(105L, buckets.get(1).getInFlow());
        assertEquals(27L, buckets.get(1).getOutFlow());
        assertEquals(132L, buckets.get(1).getFlow());
    }

    @Test
    void dailyBucketsCoverSevenCalendarDaysNewestFirst() {
        List<StatisticsFlow> days = UserServiceImpl.bucketLast7Days(
                List.of(sample(1L, 12, 0, 10L, 1L), sample(1L, 12, 13, 30L, 3L),
                        sample(1L, 6, 23, 50L, 5L), sample(1L, 5, 23, 999L, 999L)),
                1L, NOW, ZONE);

        assertEquals(7, days.size());
        assertEquals("10-12", days.get(0).getTime());
        assertEquals(40L, days.get(0).getInFlow());
        assertEquals(4L, days.get(0).getOutFlow());
        assertEquals("10-06", days.get(6).getTime());
        assertEquals(55L, days.get(6).getFlow());
        // 10-05 is outside the seven days.
        assertEquals(99L, days.stream().mapToLong(StatisticsFlow::getFlow).sum());
        assertEquals(ZonedDateTime.of(2026, 10, 12, 0, 0, 0, 0, ZONE).toInstant().toEpochMilli(),
                days.get(0).getCreatedTime());
    }

    @Test
    void returnsTwentyFourHourlyBucketsNewestFirst() {
        List<StatisticsFlow> buckets = UserServiceImpl.bucketLast24Hours(List.of(), 1L, NOW, ZONE);

        assertEquals(24, buckets.size());
        assertEquals("14:00", buckets.get(0).getTime());
        assertEquals("15:00", buckets.get(23).getTime());
        assertEquals(0L, buckets.stream().mapToLong(StatisticsFlow::getFlow).sum());
    }

    @Test
    void sumsEveryAccountInTheSameHour() {
        List<StatisticsFlow> buckets = UserServiceImpl.bucketLast24Hours(
                List.of(sample(1L, 12, 13, 100L), sample(2L, 12, 13, 250L), sample(1L, 12, 14, 40L)),
                1L, NOW, ZONE);

        assertEquals(40L, buckets.get(0).getFlow());
        assertEquals(350L, buckets.get(1).getFlow());
        assertEquals("13:00", buckets.get(1).getTime());
    }

    @Test
    void hoursWithoutASampleStayEmptyInsteadOfBorrowingOlderOnes() {
        // 14:00 yesterday falls outside the window even though its label matches the newest bucket.
        List<StatisticsFlow> buckets = UserServiceImpl.bucketLast24Hours(
                List.of(sample(1L, 11, 14, 999L), sample(1L, 11, 15, 70L)), 1L, NOW, ZONE);

        assertEquals(0L, buckets.get(0).getFlow());
        assertEquals(70L, buckets.get(23).getFlow());
        assertEquals(70L, buckets.stream().mapToLong(StatisticsFlow::getFlow).sum());
    }

    @Test
    void bucketsCarryTheirStartTimeForClientsInOtherTimeZones() {
        List<StatisticsFlow> buckets = UserServiceImpl.bucketLast24Hours(List.of(), 1L, NOW, ZONE);

        assertEquals(ZonedDateTime.of(2026, 10, 12, 14, 0, 0, 0, ZONE).toInstant().toEpochMilli(),
                buckets.get(0).getCreatedTime());
    }
}
