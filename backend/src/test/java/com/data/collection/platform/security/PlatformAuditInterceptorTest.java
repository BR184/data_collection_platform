package com.data.collection.platform.security;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.data.collection.platform.service.OperationAuditService;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.util.ContentCachingRequestWrapper;

class PlatformAuditInterceptorTest {

  @Test
  void shouldRecordSanitizedRequestSummaryForMutatingApiCalls() throws Exception {
    OperationAuditService operationAuditService = mock(OperationAuditService.class);
    PlatformAuditInterceptor interceptor = new PlatformAuditInterceptor(operationAuditService);
    MockHttpServletRequest rawRequest = new MockHttpServletRequest("POST", "/api/gitlab-sync/config");
    rawRequest.setRemoteAddr("127.0.0.1");
    rawRequest.setCharacterEncoding(StandardCharsets.UTF_8.name());
    rawRequest.setQueryString("configId=1&token=raw-token");
    rawRequest.setContentType("application/json");
    rawRequest.setContent(
        """
        {"name":"CC","dbPassword":"plain-password","systemHookSecret":"plain-secret"}
        """
            .getBytes(StandardCharsets.UTF_8));
    ContentCachingRequestWrapper request = new ContentCachingRequestWrapper(rawRequest);
    request.getInputStream().readAllBytes();

    interceptor.afterCompletion(request, new MockHttpServletResponse(), null, null);

    verify(operationAuditService)
        .record(
            argThat(user -> user != null && "guest".equals(user.username())),
            eq("POST"),
            eq("/api/gitlab-sync/config"),
            eq("127.0.0.1"),
            eq(200),
            eq(""),
            argThat(
                summary ->
                    summary.contains("configId=1")
                        && summary.contains("token=***")
                        && summary.contains("\"dbPassword\":\"***\"")
                        && summary.contains("\"systemHookSecret\":\"***\"")
                        && !summary.contains("plain-password")
                        && !summary.contains("plain-secret")
                        && !summary.contains("raw-token")));
  }

  @Test
  void shouldSanitizeExceptionMessagesBeforeAudit() throws Exception {
    OperationAuditService operationAuditService = mock(OperationAuditService.class);
    PlatformAuditInterceptor interceptor = new PlatformAuditInterceptor(operationAuditService);
    MockHttpServletRequest rawRequest = new MockHttpServletRequest("POST", "/api/gitlab-sync/config");
    rawRequest.setRemoteAddr("127.0.0.1");
    ContentCachingRequestWrapper request = new ContentCachingRequestWrapper(rawRequest);

    interceptor.afterCompletion(
        request,
        new MockHttpServletResponse(),
        null,
        new IllegalStateException("dbPassword=plain-password token=raw-token"));

    verify(operationAuditService)
        .record(
            argThat(user -> user != null && "guest".equals(user.username())),
            eq("POST"),
            eq("/api/gitlab-sync/config"),
            eq("127.0.0.1"),
            eq(200),
            argThat(message ->
                message.contains("dbPassword=***")
                    && message.contains("token=***")
                    && !message.contains("plain-password")
                    && !message.contains("raw-token")),
            eq(""));
  }
}
