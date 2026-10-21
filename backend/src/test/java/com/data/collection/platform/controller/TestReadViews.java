package com.data.collection.platform.controller;

import com.data.collection.platform.entity.read.DatasetReadSelection;
import com.data.collection.platform.entity.read.PublishedDatasetReadContext;
import com.data.collection.platform.entity.read.ReadSelection;
import com.data.collection.platform.entity.read.ReadViewMetadata;
import com.data.collection.platform.service.read.ReadViewApplicationService;
import com.data.collection.platform.service.read.TestReadSelections;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.mockito.Mockito;

/** 控制器参数/响应单测的读取边界替身；权限、闭包与历史一致性由真实协议集成测试验证。 */
public final class TestReadViews {
  private TestReadViews() {}

  /** 为 HTTP 外壳单测提供固定来源输入，不能替代真实读取完整性与鉴权验证。 */
  public static ReadViewApplicationService boundary() {
    var facts = TestReadSelections.all();
    var datasets = new DatasetReadSelection(java.util.List.of(new PublishedDatasetReadContext("default", "formal-records",
        UUID.randomUUID(), 1, UUID.randomUUID(), Instant.parse("2026-10-13T00:00:00Z"))), facts, facts.businessDate());
    return Mockito.mock(ReadViewApplicationService.class, invocation -> {
      String name = invocation.getMethod().getName();
      if (name.equals("readFacts") || name.equals("read")) {
        ReadSelection selection = name.equals("readFacts") ? facts : datasets;
        @SuppressWarnings("unchecked")
        java.util.function.Function<ReadSelection, Object> reader = invocation.getArgument(3);
        var metadata = new ReadViewMetadata(UUID.randomUUID(), invocation.getArgument(1), "test-scope", selection.sourceVersion(),
            selection.businessDate(), Instant.parse("2026-10-13T00:05:00Z"), Instant.parse("2026-10-13T00:00:00Z"), 0,
            ReadViewMetadata.SourceState.CURRENT, null, false);
        return new ReadViewApplicationService.ReadOutcome<>(reader.apply(selection), metadata);
      }
      if (name.equals("parameters")) {
        jakarta.servlet.http.HttpServletRequest request = invocation.getArgument(0);
        Map<String, String> parameters = new java.util.LinkedHashMap<>();
        request.getParameterMap().forEach((key, value) -> { if (value.length > 0) { parameters.put(key, value[0]); } });
        return parameters;
      }
      if (name.equals("businessParameters")) {
        Map<String, String> parameters = new java.util.LinkedHashMap<>(invocation.<Map<String, String>>getArgument(0));
        parameters.remove("readContextId");
        return parameters;
      }
      return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
    });
  }

  /** 真控制器调用携带测试账号会话；业务权限仍由真实应用服务重新核验。 */
  public static jakarta.servlet.http.HttpServletRequest authenticatedRequest() {
    var request = new org.springframework.mock.web.MockHttpServletRequest();
    request.getSession().setAttribute(com.data.collection.platform.security.AuthSessionSupport.SESSION_USER_KEY,
        new com.data.collection.platform.entity.AuthUserResponse("admin", "测试管理员", java.util.Set.of("ADMIN"),
            java.util.List.of("管理员"), java.util.Set.of(), true));
    return request;
  }
}
