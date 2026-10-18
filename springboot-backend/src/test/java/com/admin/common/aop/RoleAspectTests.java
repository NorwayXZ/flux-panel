package com.admin.common.aop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class RoleAspectTests {
  @AfterEach
  void clear() {
    RequestContextHolder.resetRequestAttributes();
  }

  @Test
  void deniesStreamingControllerWithHttp403WithoutInvokingIt() throws Throwable {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(
        "Authorization",
        "x."
            + Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString("{\"role_id\":1}".getBytes(StandardCharsets.UTF_8))
            + ".x");
    MockHttpServletResponse response = new MockHttpServletResponse();
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request, response));
    ProceedingJoinPoint point = mock(ProceedingJoinPoint.class);
    MethodSignature signature = mock(MethodSignature.class);
    when(point.getSignature()).thenReturn(signature);
    when(signature.getReturnType()).thenReturn(SseEmitter.class);
    Object result = new RoleAspect().checkRole(point, null);
    assertNull(result);
    assertEquals(403, response.getStatus());
    assertTrue(response.getContentAsString().contains("权限不足"));
    verify(point, never()).proceed();
  }
}
