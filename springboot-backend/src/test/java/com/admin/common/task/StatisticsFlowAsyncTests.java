package com.admin.common.task;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StatisticsFlowAsyncTests {

    @Test
    void firstSampleDoesNotCountLifetimeTotalAsOneHour() {
        assertEquals(0L, StatisticsFlowAsync.hourlyIncrement(null, 15_000_000_000L));
    }

    @Test
    void laterSamplesRecordTheDifference() {
        assertEquals(500L, StatisticsFlowAsync.hourlyIncrement(1_000L, 1_500L));
        assertEquals(0L, StatisticsFlowAsync.hourlyIncrement(1_500L, 1_500L));
    }

    @Test
    void counterResetCountsUsageSinceTheReset() {
        assertEquals(300L, StatisticsFlowAsync.hourlyIncrement(9_000L, 300L));
    }
}
