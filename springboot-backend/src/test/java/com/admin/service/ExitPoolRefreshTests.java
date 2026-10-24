package com.admin.service;

import com.admin.mapper.NodeMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ExitPoolRefreshTests {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ExitPoolService service = new ExitPoolService(jdbc, mock(NodeMapper.class), mock(IpQualityService.class),
            "https://example.test/list", "https://example.test/lookup");
    private final VpnGateListFetcher fetcher = mock(VpnGateListFetcher.class);

    @AfterEach
    void close() {
        service.shutdown();
    }

    @Test
    void failurePreservesLastGoodTimestampAndRetriesAfterAMinute() throws Exception {
        ReflectionTestUtils.setField(service, "listFetcher", fetcher);
        long lastGood = System.currentTimeMillis() - 120_000L;
        ReflectionTestUtils.setField(service, "lastRefreshAt", lastGood);
        ReflectionTestUtils.setField(service, "lastCleanupAt", System.currentTimeMillis());
        when(fetcher.fetch()).thenThrow(new IOException("HTTP connect timed out"));

        assertTrue(service.refreshCandidatesIfDue(true));
        assertFalse(service.refreshCandidatesIfDue(false));
        ReflectionTestUtils.setField(service, "lastRefreshAttemptAt", System.currentTimeMillis() - 61_000L);
        assertTrue(service.refreshCandidatesIfDue(false));

        verify(fetcher, times(2)).fetch();
        assertEquals(lastGood, ReflectionTestUtils.getField(service, "lastRefreshAt"));
        assertEquals("HTTP connect timed out", ReflectionTestUtils.getField(service, "lastRefreshError"));
        verifyNoInteractions(jdbc);
    }

    @Test
    void successRestoresNormalRefreshIntervalAndClearsTheFailure() throws Exception {
        ReflectionTestUtils.setField(service, "listFetcher", fetcher);
        ReflectionTestUtils.setField(service, "lastRefreshError", "old error");
        when(fetcher.fetch()).thenReturn(com.admin.common.utils.ExitCandidateParser.parseVpnGateList(VpnGateListFetcherTests.validList()));

        long before = System.currentTimeMillis();
        assertTrue(service.refreshCandidatesIfDue(true));
        assertNull(ReflectionTestUtils.getField(service, "lastRefreshError"));
        assertTrue((long) ReflectionTestUtils.getField(service, "lastRefreshAt") >= before);
        ReflectionTestUtils.setField(service, "lastRefreshAttemptAt", System.currentTimeMillis() - 61_000L);
        assertFalse(service.refreshCandidatesIfDue(false));
        verify(fetcher).fetch();
    }

    @Test
    @SuppressWarnings("unchecked")
    void concurrentRefreshIsNotReportedAsCompletedAndLookupErrorsStaySeparate() {
        var refreshing = (AtomicBoolean) ReflectionTestUtils.getField(service, "listRefreshing");
        refreshing.set(true);
        ReflectionTestUtils.setField(service, "lastLookupError", "IP lookup unavailable");

        assertFalse(service.refreshCandidatesIfDue(true));
        assertNotEquals(0, service.refresh().getCode());
        Map<String, Object> data = (Map<String, Object>) service.countries().getData();
        assertNull(data.get("lastRefreshError"));
        assertEquals("IP lookup unavailable", data.get("lastLookupError"));
        assertEquals(true, data.get("refreshing"));
    }
}
