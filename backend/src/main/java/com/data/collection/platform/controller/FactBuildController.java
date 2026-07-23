package com.data.collection.platform.controller;

import com.data.collection.platform.common.response.ApiResponse;
import com.data.collection.platform.security.PlatformPermissionCodes;
import com.data.collection.platform.security.RequirePermission;
import com.data.collection.platform.entity.FactBuildTaskResponse;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.IssueFactDiagnosticsResponse;
import com.data.collection.platform.entity.IssueSourceReadinessResponse;
import com.data.collection.platform.service.FactBuildTaskService;
import com.data.collection.platform.service.GitlabConfigService;
import com.data.collection.platform.service.IssueFactDiagnosticsService;
import com.data.collection.platform.service.IssueSourceReadinessService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/facts")
public class FactBuildController {
  private final FactBuildTaskService factBuildTaskService;
  private final GitlabConfigService configService;
  private final GitlabSyncCommandFacade syncCommandFacade;
  private final IssueFactDiagnosticsService issueFactDiagnosticsService;
  private final IssueSourceReadinessService issueSourceReadinessService;

  public FactBuildController(
      FactBuildTaskService factBuildTaskService,
      GitlabConfigService configService,
      GitlabSyncCommandFacade syncCommandFacade,
      IssueFactDiagnosticsService issueFactDiagnosticsService,
      IssueSourceReadinessService issueSourceReadinessService) {
    this.factBuildTaskService = factBuildTaskService;
    this.configService = configService;
    this.syncCommandFacade = syncCommandFacade;
    this.issueFactDiagnosticsService = issueFactDiagnosticsService;
    this.issueSourceReadinessService = issueSourceReadinessService;
  }

  @PostMapping("/rebuild")
  @RequirePermission(PlatformPermissionCodes.SYSTEM_FACT_REBUILD)
  public ApiResponse<Map<String, Object>> rebuildFacts(@RequestParam Long configId) {
    GitlabSyncConfig config = configService.getConfigById(configId);
    return syncCommandFacade.manualFullFactRebuild(config);
  }

  @GetMapping("/build-tasks/latest")
  @RequirePermission(PlatformPermissionCodes.SYSTEM_FACT_REBUILD)
  public ApiResponse<FactBuildTaskResponse> getLatestBuildTask(
      @RequestParam(required = false) String scope) {
    FactBuildTaskResponse response = factBuildTaskService.latest(scope);
    return ApiResponse.success("事实构建任务状态已生成", response);
  }

  @GetMapping("/issue-diagnostics")
  @RequirePermission(PlatformPermissionCodes.SYSTEM_FACT_REBUILD)
  public ApiResponse<IssueFactDiagnosticsResponse> getIssueDiagnostics() {
    IssueFactDiagnosticsResponse response = issueFactDiagnosticsService.getDiagnostics();
    return ApiResponse.success("Issue Fact 验收诊断已生成", response);
  }

  @GetMapping("/issue-source-readiness")
  @RequirePermission(PlatformPermissionCodes.SYSTEM_FACT_REBUILD)
  public ApiResponse<IssueSourceReadinessResponse> getIssueSourceReadiness() {
    IssueSourceReadinessResponse response = issueSourceReadinessService.getReadiness();
    return ApiResponse.success("Issue 源数据就绪度诊断已生成", response);
  }
}
