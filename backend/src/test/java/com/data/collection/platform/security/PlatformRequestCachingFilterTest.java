package com.data.collection.platform.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.util.ContentCachingRequestWrapper;

class PlatformRequestCachingFilterTest {

  @Test
  void shouldWrapMutatingApiRequestsForAuditBodyCapture() throws Exception {
    PlatformRequestCachingFilter filter = new PlatformRequestCachingFilter();
    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(
        new MockHttpServletRequest("POST", "/api/gitlab-sync/config"),
        new MockHttpServletResponse(),
        chain);

    assertThat(chain.getRequest()).isInstanceOf(ContentCachingRequestWrapper.class);
  }

  @Test
  void shouldSkipAuthRequests() throws Exception {
    PlatformRequestCachingFilter filter = new PlatformRequestCachingFilter();
    MockFilterChain chain = new MockFilterChain();
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");

    filter.doFilter(request, new MockHttpServletResponse(), chain);

    assertThat(chain.getRequest()).isSameAs(request);
  }
}
