package com.admin.common.utils;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrossEntryTopologyTests {
    @Test
    void groupsSameLargeIpv4RangeTogether() {
        Set<String> first = CrossEntryTopology.keys("8.218.152.71", "", "", "");
        Set<String> second = CrossEntryTopology.keys("8.218.225.255", "", "", "");

        assertTrue(CrossEntryTopology.overlaps(first, second));
    }

    @Test
    void separatesDifferentLargeIpv4Ranges() {
        Set<String> first = CrossEntryTopology.keys("8.218.152.71", "", "", "");
        Set<String> second = CrossEntryTopology.keys("34.150.15.102", "", "", "");

        assertFalse(CrossEntryTopology.overlaps(first, second));
    }

    @Test
    void recognizesProviderFromChineseNodeLabels() {
        Set<String> first = CrossEntryTopology.keys("8.218.152.71", "", "", "阿里云AAQ-NNC");
        Set<String> second = CrossEntryTopology.keys("47.243.1.10", "", "", "aliyun hk");

        assertTrue(CrossEntryTopology.overlaps(first, second));
    }
}
