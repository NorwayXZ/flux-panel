package com.admin.common.exception;

import com.admin.common.lang.R;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlobalExceptionHandlerTests {
    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void unexpectedExceptionWithBlankMessageReturnsUsefulReasonAndRequestId() {
        MDC.put("requestId", "req-123");
        R result = new GlobalExceptionHandler().Exception(new NullPointerException());

        assertEquals(-2, result.getCode());
        assertTrue(result.getMsg().contains("服务器内部异常：NullPointerException"));
        assertTrue(result.getMsg().contains("请求ID：req-123"));
    }
}
