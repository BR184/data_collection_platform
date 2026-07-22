package com.data.collection.platform.controller;

import com.data.collection.platform.common.response.ApiResponse;
import com.data.collection.platform.security.PlatformPermissionCodes;
import com.data.collection.platform.security.RequirePermission;
import com.data.collection.platform.security.AuthSessionSupport;
import com.data.collection.platform.entity.CodeReviewMatchModeCollectionOptionResponse;
import com.data.collection.platform.entity.CodeReviewMatchModeConnectionTestResponse;
import com.data.collection.platform.entity.CodeReviewMatchModeDbSettingsResponse;
import com.data.collection.platform.entity.CodeReviewMatchModeDbSettingsSaveRequest;
import com.data.collection.platform.entity.CodeReviewMatchModeSyncResponse;
import com.data.collection.platform.entity.CodeReviewMatchModeTableOptionResponse;
import com.data.collection.platform.entity.CodeReviewDgmGitlabProjectOptionResponse;
import com.data.collection.platform.entity.CodeReviewDgmGitlabProjectSourceResponse;
import com.data.collection.platform.entity.CodeReviewDgmGitlabProjectSourceSaveRequest;
import com.data.collection.platform.entity.CodeReviewDgmGitlabProjectSyncResponse;
import com.data.collection.platform.entity.LegacyPlatformFormalImportRequest;
import com.data.collection.platform.entity.LegacyPlatformFormalImportResponse;
import com.data.collection.platform.service.CodeReviewDgmGitlabProjectOptionService;
import com.data.collection.platform.service.CodeReviewMatchModeConfigService;
import com.data.collection.platform.service.CodeReviewMatchModeMongoReviewSyncService;
import com.data.collection.platform.service.CodeReviewMatchModeSyncService;
import com.data.collection.platform.service.LegacyPlatformFormalImportService;
import java.util.List;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/code-review/match-mode-db-settings")
@RequirePermission(PlatformPermissionCodes.SYSTEM_MATCH_MODE_VIEW)
//兼容模式-MatchMode：系统设置/数据库兼容模式临时设置后端入口；老平台交接完成后可整体删除本 Controller 及相关 Service/表。
public class CodeReviewMatchModeDbSettingsController {
  private final CodeReviewMatchModeConfigService configService;
  private final CodeReviewMatchModeSyncService syncService;
  private final CodeReviewMatchModeMongoReviewSyncService mongoReviewSyncService;
  private final LegacyPlatformFormalImportService formalImportService;
  private final CodeReviewDgmGitlabProjectOptionService dgmProjectOptionService;

  public CodeReviewMatchModeDbSettingsController(
      CodeReviewMatchModeConfigService configService,
      CodeReviewMatchModeSyncService syncService,
      CodeReviewMatchModeMongoReviewSyncService mongoReviewSyncService,
      LegacyPlatformFormalImportService formalImportService,
      CodeReviewDgmGitlabProjectOptionService dgmProjectOptionService) {
    this.configService = configService;
    this.syncService = syncService;
    this.mongoReviewSyncService = mongoReviewSyncService;
    this.formalImportService = formalImportService;
    this.dgmProjectOptionService = dgmProjectOptionService;
  }

  //兼容模式-MatchMode
  @GetMapping
  public ApiResponse<CodeReviewMatchModeDbSettingsResponse> getSettings() {
    return ApiResponse.success(configService.getResponse());
  }

  //兼容模式-MatchMode
  @PutMapping
  @RequirePermission(PlatformPermissionCodes.SYSTEM_MATCH_MODE_CONFIG)
  public ApiResponse<CodeReviewMatchModeDbSettingsResponse> saveSettings(
      @RequestBody CodeReviewMatchModeDbSettingsSaveRequest request) {
    return ApiResponse.success("数据库兼容模式临时设置已保存", configService.save(request));
  }

  //兼容模式-MatchMode
  @PostMapping("/test-connection")
  @RequirePermission(PlatformPermissionCodes.SYSTEM_MATCH_MODE_CONFIG)
  public ApiResponse<CodeReviewMatchModeConnectionTestResponse> testConnection(
      @RequestBody(required = false) CodeReviewMatchModeDbSettingsSaveRequest request) {
    CodeReviewMatchModeConnectionTestResponse result = configService.testConnection(request);
    return ApiResponse.success(result.message(), result);
  }

  //兼容模式-MatchMode
  @PostMapping("/table-options")
  @RequirePermission(PlatformPermissionCodes.SYSTEM_MATCH_MODE_CONFIG)
  public ApiResponse<List<CodeReviewMatchModeTableOptionResponse>> tableOptions(
      @RequestBody(required = false) CodeReviewMatchModeDbSettingsSaveRequest request) {
    return ApiResponse.success(configService.discoverTableOptions(request));
  }

  //兼容模式-MatchMode
  @PostMapping("/mongo/test-connection")
  @RequirePermission(PlatformPermissionCodes.SYSTEM_MATCH_MODE_CONFIG)
  public ApiResponse<CodeReviewMatchModeConnectionTestResponse> testMongoConnection(
      @RequestBody(required = false) CodeReviewMatchModeDbSettingsSaveRequest request) {
    CodeReviewMatchModeConnectionTestResponse result = configService.testMongoConnection(request);
    return ApiResponse.success(result.message(), result);
  }

  //兼容模式-MatchMode
  @PostMapping("/mongo/collection-options")
  @RequirePermission(PlatformPermissionCodes.SYSTEM_MATCH_MODE_CONFIG)
  public ApiResponse<List<CodeReviewMatchModeCollectionOptionResponse>> mongoCollectionOptions(
      @RequestBody(required = false) CodeReviewMatchModeDbSettingsSaveRequest request) {
    return ApiResponse.success(configService.discoverMongoCollectionOptions(request));
  }

  //兼容模式-MatchMode
  @PostMapping("/mongo/sync-now")
  @RequirePermission(PlatformPermissionCodes.SYSTEM_MATCH_MODE_SYNC)
  public ApiResponse<CodeReviewMatchModeSyncResponse> syncMongoReviewNow(
      @RequestBody(required = false) CodeReviewMatchModeDbSettingsSaveRequest request) {
    CodeReviewMatchModeSyncResponse result = mongoReviewSyncService.syncNow(request);
    return ApiResponse.success(result.message(), result);
  }

  //兼容模式-MatchMode
  @PostMapping("/sync-now")
  @RequirePermission(PlatformPermissionCodes.SYSTEM_MATCH_MODE_SYNC)
  public ApiResponse<CodeReviewMatchModeSyncResponse> syncNow() {
    CodeReviewMatchModeSyncResponse result = syncService.syncNow();
    return ApiResponse.success(result.message(), result);
  }

  //兼容模式-MatchMode
  @PostMapping("/formal-import")
  @RequirePermission(PlatformPermissionCodes.SYSTEM_MATCH_MODE_FORMAL_IMPORT)
  public ApiResponse<LegacyPlatformFormalImportResponse> importToFormal(
      @RequestBody LegacyPlatformFormalImportRequest request,
      HttpServletRequest httpRequest) {
    var user = AuthSessionSupport.currentUser(httpRequest);
    LegacyPlatformFormalImportResponse result = formalImportService.importToFormal(
        request,
        user == null ? "system" : user.username());
    return ApiResponse.success(result.message(), result);
  }

  //兼容模式-MatchMode：DGM 项目下拉候选服务放在交接期设置页维护，但只缓存项目候选，不改变兼容表/正式事实表读源。
  @GetMapping("/dgm-gitlab-project-source")
  public ApiResponse<CodeReviewDgmGitlabProjectSourceResponse> getDgmGitlabProjectSource() {
    return ApiResponse.success(dgmProjectOptionService.getSettings());
  }

  //兼容模式-MatchMode：同上，便于后续彻底删除兼容设置页时快速定位交接期入口。
  @PutMapping("/dgm-gitlab-project-source")
  @RequirePermission(PlatformPermissionCodes.SYSTEM_MATCH_MODE_CONFIG)
  public ApiResponse<CodeReviewDgmGitlabProjectSourceResponse> saveDgmGitlabProjectSource(
      @RequestBody CodeReviewDgmGitlabProjectSourceSaveRequest request) {
    return ApiResponse.success("DGM GitLab 项目下拉数据源已保存", dgmProjectOptionService.saveSettings(request));
  }

  //兼容模式-MatchMode：仅测试 DGM GitLab 项目候选 API，不触发 MR/代码走查数据同步。
  @PostMapping("/dgm-gitlab-project-source/test-connection")
  @RequirePermission(PlatformPermissionCodes.SYSTEM_MATCH_MODE_CONFIG)
  public ApiResponse<CodeReviewMatchModeConnectionTestResponse> testDgmGitlabProjectSource(
      @RequestBody(required = false) CodeReviewDgmGitlabProjectSourceSaveRequest request) {
    CodeReviewMatchModeConnectionTestResponse result = dgmProjectOptionService.testConnection(request);
    return ApiResponse.success(result.message(), result);
  }

  //兼容模式-MatchMode：只刷新 DGM 项目下拉本地缓存，不改变老平台 MySQL 兼容表和正式事实表。
  @PostMapping("/dgm-gitlab-project-options/sync-now")
  @RequirePermission(PlatformPermissionCodes.SYSTEM_MATCH_MODE_SYNC)
  public ApiResponse<CodeReviewDgmGitlabProjectSyncResponse> syncDgmGitlabProjectOptions() {
    CodeReviewDgmGitlabProjectSyncResponse result = dgmProjectOptionService.syncNow();
    return ApiResponse.success(result.message(), result);
  }

  //兼容模式-MatchMode：供代码走查非法数据页在兼容/非兼容读源下复用同一套 DGM 项目候选。
  @GetMapping("/dgm-gitlab-project-options")
  public ApiResponse<List<CodeReviewDgmGitlabProjectOptionResponse>> getDgmGitlabProjectOptions() {
    return ApiResponse.success(dgmProjectOptionService.listOptions());
  }
}
