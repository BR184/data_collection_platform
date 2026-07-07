package com.data.collection.platform.controller;

import com.data.collection.platform.common.response.ApiResponse;
import com.data.collection.platform.entity.AuthRole;
import com.data.collection.platform.entity.CodeReviewMatchModeCollectionOptionResponse;
import com.data.collection.platform.entity.CodeReviewMatchModeConnectionTestResponse;
import com.data.collection.platform.entity.CodeReviewMatchModeDbSettingsResponse;
import com.data.collection.platform.entity.CodeReviewMatchModeDbSettingsSaveRequest;
import com.data.collection.platform.entity.CodeReviewMatchModeSyncResponse;
import com.data.collection.platform.entity.CodeReviewMatchModeTableOptionResponse;
import com.data.collection.platform.entity.LegacyPlatformFormalImportRequest;
import com.data.collection.platform.entity.LegacyPlatformFormalImportResponse;
import com.data.collection.platform.security.RequireRole;
import com.data.collection.platform.service.CodeReviewMatchModeConfigService;
import com.data.collection.platform.service.CodeReviewMatchModeMongoReviewSyncService;
import com.data.collection.platform.service.CodeReviewMatchModeSyncService;
import com.data.collection.platform.service.LegacyPlatformFormalImportService;
import java.util.List;
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
  private final CodeReviewMatchModeMongoReviewSyncService mongoReviewSyncService;
  private final LegacyPlatformFormalImportService formalImportService;

  public CodeReviewMatchModeDbSettingsController(
      CodeReviewMatchModeConfigService configService,
      CodeReviewMatchModeSyncService syncService,
      CodeReviewMatchModeMongoReviewSyncService mongoReviewSyncService,
      LegacyPlatformFormalImportService formalImportService) {
    this.configService = configService;
    this.syncService = syncService;
    this.mongoReviewSyncService = mongoReviewSyncService;
    this.formalImportService = formalImportService;
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
  @PostMapping("/table-options")
  public ApiResponse<List<CodeReviewMatchModeTableOptionResponse>> tableOptions(
      @RequestBody(required = false) CodeReviewMatchModeDbSettingsSaveRequest request) {
    return ApiResponse.success(configService.discoverTableOptions(request));
  }

  //兼容模式-MatchMode
  @PostMapping("/mongo/test-connection")
  public ApiResponse<CodeReviewMatchModeConnectionTestResponse> testMongoConnection(
      @RequestBody(required = false) CodeReviewMatchModeDbSettingsSaveRequest request) {
    CodeReviewMatchModeConnectionTestResponse result = configService.testMongoConnection(request);
    return ApiResponse.success(result.message(), result);
  }

  //兼容模式-MatchMode
  @PostMapping("/mongo/collection-options")
  public ApiResponse<List<CodeReviewMatchModeCollectionOptionResponse>> mongoCollectionOptions(
      @RequestBody(required = false) CodeReviewMatchModeDbSettingsSaveRequest request) {
    return ApiResponse.success(configService.discoverMongoCollectionOptions(request));
  }

  //兼容模式-MatchMode
  @PostMapping("/mongo/sync-now")
  public ApiResponse<CodeReviewMatchModeSyncResponse> syncMongoReviewNow(
      @RequestBody(required = false) CodeReviewMatchModeDbSettingsSaveRequest request) {
    CodeReviewMatchModeSyncResponse result = mongoReviewSyncService.syncNow(request);
    return ApiResponse.success(result.message(), result);
  }

  //兼容模式-MatchMode
  @PostMapping("/sync-now")
  public ApiResponse<CodeReviewMatchModeSyncResponse> syncNow() {
    CodeReviewMatchModeSyncResponse result = syncService.syncNow();
    return ApiResponse.success(result.message(), result);
  }

  //兼容模式-MatchMode
  @PostMapping("/formal-import")
  public ApiResponse<LegacyPlatformFormalImportResponse> importToFormal(
      @RequestBody LegacyPlatformFormalImportRequest request) {
    LegacyPlatformFormalImportResponse result = formalImportService.importToFormal(request);
    return ApiResponse.success(result.message(), result);
  }
}
