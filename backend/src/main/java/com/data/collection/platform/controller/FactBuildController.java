package com.data.collection.platform.controller;

import com.data.collection.platform.common.response.ApiResponse;
import com.data.collection.platform.entity.AuthRole;
import com.data.collection.platform.entity.FactBuildResponse;
import com.data.collection.platform.entity.FactBuildTaskResponse;
import com.data.collection.platform.entity.IssueFactDiagnosticsResponse;
import com.data.collection.platform.entity.IssueSourceReadinessResponse;
import com.data.collection.platform.service.FactBuildService;
import com.data.collection.platform.service.FactBuildTaskService;
import com.data.collection.platform.service.FactBuildOperationGuard;
import com.data.collection.platform.service.IssueFactDiagnosticsService;
import com.data.collection.platform.service.IssueSourceReadinessService;
import com.data.collection.platform.security.RequireRole;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/facts")
public class FactBuildController {
  private final FactBuildService factBuildService;
  private final FactBuildOperationGuard factBuildOperationGuard;
  private final FactBuildTaskService factBuildTaskService;
  private final IssueFactDiagnosticsService issueFactDiagnosticsService;
  private final IssueSourceReadinessService issueSourceReadinessService;

  public FactBuildController(
      FactBuildService factBuildService,
      FactBuildOperationGuard factBuildOperationGuard,
      FactBuildTaskService factBuildTaskService,
      IssueFactDiagnosticsService issueFactDiagnosticsService,
      IssueSourceReadinessService issueSourceReadinessService) {
    this.factBuildService = factBuildService;
    this.factBuildOperationGuard = factBuildOperationGuard;
    this.factBuildTaskService = factBuildTaskService;
    this.issueFactDiagnosticsService = issueFactDiagnosticsService;
    this.issueSourceReadinessService = issueSourceReadinessService;
  }

  @PostMapping("/rebuild")
  @RequireRole(AuthRole.ADMIN)
  public ApiResponse<FactBuildResponse> rebuildFacts(
      @RequestParam(defaultValue = "all") String scope,
      @RequestParam(defaultValue = "false") boolean full,
      @RequestParam(required = false) Long configId) {
    FactBuildResponse response =
        factBuildOperationGuard.run(guardScope(scope, configId), () ->
            switch (scope) {
              case "issue" -> configId == null
                  ? factBuildService.rebuildIssueFacts(full)
                  : factBuildService.rebuildIssueFacts(full, configId);
              case "merge-request", "merge_request" -> configId == null
                  ? factBuildService.rebuildMergeRequestFacts(full)
                  : factBuildService.rebuildMergeRequestFacts(full, configId);
              default -> configId == null
                  ? factBuildService.rebuildAllFacts(full)
                  : factBuildService.rebuildAllFacts(full, configId);
            });
    return ApiResponse.success(response.message(), response);
  }

  private String guardScope(String scope, Long configId) {
    return configId == null ? scope : scope + ":config-" + configId;
  }

  @GetMapping("/build-tasks/latest")
  @RequireRole(AuthRole.ADMIN)
  public ApiResponse<FactBuildTaskResponse> getLatestBuildTask(
      @RequestParam(required = false) String scope) {
    FactBuildTaskResponse response = factBuildTaskService.latest(scope);
    return ApiResponse.success("事实构建任务状态已生成", response);
  }

  @GetMapping("/issue-diagnostics")
  @RequireRole(AuthRole.ADMIN)
  public ApiResponse<IssueFactDiagnosticsResponse> getIssueDiagnostics() {
    IssueFactDiagnosticsResponse response = issueFactDiagnosticsService.getDiagnostics();
    return ApiResponse.success("Issue Fact 验收诊断已生成", response);
  }

  @GetMapping("/issue-source-readiness")
  @RequireRole(AuthRole.ADMIN)
  public ApiResponse<IssueSourceReadinessResponse> getIssueSourceReadiness() {
    IssueSourceReadinessResponse response = issueSourceReadinessService.getReadiness();
    return ApiResponse.success("Issue 源数据就绪度诊断已生成", response);
  }
}
