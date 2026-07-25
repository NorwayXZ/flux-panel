package com.admin.common.utils;

import java.math.BigDecimal;

public final class TrafficBillingUtil {

    private TrafficBillingUtil() {
    }

    public static long[] calculate(long download, long upload, int flowType, BigDecimal ratio) {
        BigDecimal multiplier = ratio == null ? BigDecimal.ONE : ratio;
        long billedDownload = flowType == 1 ? 0L
                : BigDecimal.valueOf(Math.max(0L, download)).multiply(multiplier).longValue();
        long billedUpload = BigDecimal.valueOf(Math.max(0L, upload)).multiply(multiplier).longValue();
        return new long[]{billedDownload, billedUpload};
    }
}
