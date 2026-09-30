package com.data.collection.platform.bi.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.data.collection.platform.bi.application.BiDownloadAuthorizationService;
import com.data.collection.platform.bi.application.BiPagePermissionResolver;
import com.data.collection.platform.bi.domain.model.BiDownloadScope;
import com.data.collection.platform.bi.infrastructure.BiDashboardRuntimeManager;
import com.data.collection.platform.common.exception.GlobalExceptionHandler;
import com.data.collection.platform.entity.AuthUserResponse;
import com.data.collection.platform.security.AuthSessionSupport;
import com.data.collection.platform.security.PlatformAuthorizationInterceptor;
import com.data.collection.platform.service.PagePermissionKeyResolver;
import com.data.collection.platform.service.PlatformPermissionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 下载端点的页面级鉴权行为。
 *
 * <p>注解只能表达「持有任一页面的下载权限」，请求体里的 pageKey 由控制器再解析成该页的查看与下载码精确校验；
 * 本测试锁定这条链路的状态码语义：无权限一律 403（不得落到 500），未登记的 pageKey 为 400，
 * 且 A 页权限不能下载 B 页图表。
 */
class BiDownloadPagePermissionTest {
  private static final String SYSTEM_TEST_DOWNLOAD = "bi.dashboard.system_test.download";
  private static final String SYSTEM_TEST_VIEW = "bi.dashboard.system_test.view";
  private static final String CUSTOMER_ISSUE_DOWNLOAD = "bi.dashboard.customer_issues.download";
  private static final String CUSTOMER_ISSUE_VIEW = "bi.dashboard.customer_issues.view";

  private MockMvc mockMvc;
  private BiDownloadAuthorizationService downloads;
  /** 当前请求下发给登录用户的权限码；逐用例设置，模拟权限设置页的授予结果。 */
  private Set<String> grantedPermissions = Set.of();

  @BeforeEach
  void setUp() {
    PlatformPermissionService permissionService =
        mock(PlatformPermissionService.class, org.mockito.Mockito.CALLS_REAL_METHODS);
    // 只替换权限来源，保留 requirePermission/hasPermission 的真实判定逻辑。
    doAnswer(invocation -> grantedPermissions).when(permissionService).permissionsForRoles(any());

    downloads = mock(BiDownloadAuthorizationService.class);
    when(downloads.authorize(any())).thenReturn(new BiDownloadAuthorizationService.Authorization(
        true, BiDownloadScope.productVersion(10L), "system-test", "system-test-assignee-workload",
        "developer-workload", "issue-version-3", "产品版本：CC2026R4"));
    var runtime = mock(com.data.collection.platform.bi.application.BiDashboardRuntime.class);
    when(runtime.downloads()).thenReturn(downloads);
    BiDashboardRuntimeManager manager = mock(BiDashboardRuntimeManager.class);
    when(manager.runtime()).thenReturn(runtime);

    mockMvc = MockMvcBuilders
        .standaloneSetup(new BiDashboardController(
            manager, new BiPagePermissionResolver(), permissionService))
        .addInterceptors(new PlatformAuthorizationInterceptor(
            new ObjectMapper(), permissionService, mock(PagePermissionKeyResolver.class)))
        .setControllerAdvice(new GlobalExceptionHandler())
        .build();
  }

  @Test
  void rejectsAnonymousDownloadBeforeAnyPermissionResolution() throws Exception {
    mockMvc.perform(post("/api/bi/download/authorize")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body("system-test")))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("A0301"));
  }

  @Test
  void rejectsUserWithoutAnyDownloadPermission() throws Exception {
    grantedPermissions = Set.of(SYSTEM_TEST_VIEW);

    mockMvc.perform(post("/api/bi/download/authorize")
            .session(session())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body("system-test")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("A0303"));
  }

  @Test
  void rejectsDownloadPermissionWithoutTheSamePageViewPermission() throws Exception {
    grantedPermissions = Set.of(CUSTOMER_ISSUE_VIEW, SYSTEM_TEST_DOWNLOAD);

    mockMvc.perform(post("/api/bi/download/authorize")
            .session(session())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body("system-test")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("A0303"));
  }

  @Test
  void rejectsCrossPageDownloadEvenWithAnotherPageFullAccess() throws Exception {
    grantedPermissions = Set.of(SYSTEM_TEST_VIEW, SYSTEM_TEST_DOWNLOAD);

    mockMvc.perform(post("/api/bi/download/authorize")
            .session(session())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body("customer-issues")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("A0303"));
  }

  @Test
  void rejectsUnregisteredPageKeyAsBadRequest() throws Exception {
    grantedPermissions = Set.of(SYSTEM_TEST_VIEW, SYSTEM_TEST_DOWNLOAD);

    mockMvc.perform(post("/api/bi/download/authorize")
            .session(session())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body("mirror-table-overview")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("B0001"))
        .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("BI 页面权限未定义")));
  }

  @Test
  void authorizesDownloadWhenPageViewAndDownloadAreBothGranted() throws Exception {
    grantedPermissions = Set.of(CUSTOMER_ISSUE_VIEW, CUSTOMER_ISSUE_DOWNLOAD);

    mockMvc.perform(post("/api/bi/download/authorize")
            .session(session())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body("customer-issues")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.authorized").value(true));

    verify(downloads).authorize(new BiDownloadAuthorizationService.Request(
        BiDownloadScope.productVersion(10L), "customer-issues", "system-test-assignee-workload",
        "developer-workload", "issue-version-3"));
  }

  private MockHttpSession session() {
    MockHttpSession session = new MockHttpSession();
    session.setAttribute(
        AuthSessionSupport.SESSION_USER_KEY,
        new AuthUserResponse(
            "tester", "测试用户", Set.of("NORMAL_USER"), List.of("普通用户"), Set.of(), true));
    return session;
  }

  private String body(String pageKey) {
    return """
        {"scope":{"rangeType":"PRODUCT_VERSION","productVersionId":10},\
        "pageKey":"%s","chartInstanceId":"system-test-assignee-workload",\
        "chartTemplateId":"developer-workload","sourceVersion":"issue-version-3"}"""
        .formatted(pageKey);
  }
}
