package com.data.collection.platform.controller;

import com.data.collection.platform.common.response.ApiResponse;
import com.data.collection.platform.entity.AuthRole;
import com.data.collection.platform.entity.CodeReviewMatchModeConnectionTestResponse;
import com.data.collection.platform.entity.CodeReviewMatchModeDbSettingsResponse;
import com.data.collection.platform.entity.CodeReviewMatchModeDbSettingsSaveRequest;
import com.data.collection.platform.entity.CodeReviewMatchModeSyncResponse;
import com.data.collection.platform.security.RequireRole;
import com.data.collection.platform.service.CodeReviewMatchModeConfigService;
import com.data.collection.platform.service.CodeReviewMatchModeSyncService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/code-review/match-mode-db-settings")
@RequireRole(AuthRole.ADMIN)
public class CodeReviewMatchModeDbSettingsController {
  private final CodeReviewMatchModeConfigService configService;
  private final CodeReviewMatchModeSyncService syncService;

  public CodeReviewMatchModeDbSettingsController(
      CodeReviewMatchModeConfigService configService,
      CodeReviewMatchModeSyncService syncService) {
    this.configService = configService;
    this.syncService = syncService;
  }

  //兼容模式-MatchMode
  @GetMapping
  public ApiResponse<CodeReviewMatchModeDbSettingsResponse> getSettings() {
    return ApiResponse.success(configService.getResponse());
  }

  //兼容模式-MatchMode
  @PutMapping
  public ApiResponse<CodeReviewMatchModeDbSettingsResponse> saveSettings(
      @RequestBody CodeReviewMatchModeDbSettingsSaveRequest request) {
    return ApiResponse.success("兼容模式数据库设置已保存", configService.save(request));
  }

  //兼容模式-MatchMode
  @PostMapping("/test-connection")
  public ApiResponse<CodeReviewMatchModeConnectionTestResponse> testConnection(
      @RequestBody(required = false) CodeReviewMatchModeDbSettingsSaveRequest request) {
    CodeReviewMatchModeConnectionTestResponse result = configService.testConnection(request);
    return ApiResponse.success(result.message(), result);
  }

  //兼容模式-MatchMode
  @PostMapping("/sync-now")
  public ApiResponse<CodeReviewMatchModeSyncResponse> syncNow() {
    CodeReviewMatchModeSyncResponse result = syncService.syncNow();
    return ApiResponse.success(result.message(), result);
  }
}
