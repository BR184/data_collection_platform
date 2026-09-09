package com.data.collection.platform.controller;

import com.data.collection.platform.common.response.ApiResponse;
import com.data.collection.platform.entity.AuthUserResponse;
import com.data.collection.platform.entity.backup.BackupConnectionTestRequest;
import com.data.collection.platform.entity.backup.BackupConnectionTestResponse;
import com.data.collection.platform.entity.backup.BackupRunResponse;
import com.data.collection.platform.entity.backup.BackupRunsPageResponse;
import com.data.collection.platform.entity.backup.BackupSettingsResponse;
import com.data.collection.platform.entity.backup.BackupSettingsSaveRequest;
import com.data.collection.platform.entity.backup.BackupStatusResponse;
import com.data.collection.platform.entity.backup.BackupTriggerResponse;
import com.data.collection.platform.security.AuthSessionSupport;
import com.data.collection.platform.security.PlatformPermissionCodes;
import com.data.collection.platform.security.RequirePermission;
import com.data.collection.platform.service.backup.BackupMonitorService;
import com.data.collection.platform.service.backup.BackupOrchestrationService;
import com.data.collection.platform.service.backup.BackupSettingsService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 数据库备份管理 API：配置读写、远程连接测试、手动触发、执行状态与历史分页。
 * 读取要求管理员查看权限，保存、测试连接与触发要求管理员维护权限
 * （权限码仅授予 SUPER_ADMIN/ADMIN，见迁移 V20260909_01）。
 */
@RestController
@RequestMapping("/api/database-backup")
public class DatabaseBackupController {
  private final BackupSettingsService settingsService;
  private final BackupMonitorService monitorService;
  private final BackupOrchestrationService orchestrationService;

  public DatabaseBackupController(
      BackupSettingsService settingsService,
      BackupMonitorService monitorService,
      BackupOrchestrationService orchestrationService) {
    this.settingsService = settingsService;
    this.monitorService = monitorService;
    this.orchestrationService = orchestrationService;
  }

  /** 备份配置视图：密码永不回显，仅返回是否已保存与主密钥可用性。 */
  @GetMapping("/settings")
  @RequirePermission(PlatformPermissionCodes.SYSTEM_BACKUP_VIEW)
  public ApiResponse<BackupSettingsResponse> settings() {
    return ApiResponse.success(settingsService.settingsView());
  }

  /** 保存备份配置：整体替换 + 乐观锁；远程密码留空表示不修改。版本冲突返回 CONFLICT 语义。 */
  @PutMapping("/settings")
  @RequirePermission(PlatformPermissionCodes.SYSTEM_BACKUP_MANAGE)
  public ApiResponse<BackupSettingsResponse> saveSettings(
      @RequestBody BackupSettingsSaveRequest request, HttpServletRequest servletRequest) {
    AuthUserResponse user = AuthSessionSupport.currentUser(servletRequest);
    return ApiResponse.success("备份配置已保存", settingsService.save(request, user.username()));
  }

  /** 远程连接三查（只读）：SSH 登录与指纹、目录写探针、磁盘余量；不触发备份。 */
  @PostMapping("/test-connection")
  @RequirePermission(PlatformPermissionCodes.SYSTEM_BACKUP_MANAGE)
  public ApiResponse<BackupConnectionTestResponse> testConnection(
      @RequestBody BackupConnectionTestRequest request) {
    return ApiResponse.success(settingsService.testConnection(request));
  }

  /** 手动触发一次备份；已有运行在执行时返回 accepted=false 的业务级拒绝。 */
  @PostMapping("/runs")
  @RequirePermission(PlatformPermissionCodes.SYSTEM_BACKUP_MANAGE)
  public ApiResponse<BackupTriggerResponse> trigger() {
    return ApiResponse.success(orchestrationService.triggerManual());
  }

  /** 当前执行状态：运行中的运行、最近一次结果与下次计划时刻。 */
  @GetMapping("/status")
  @RequirePermission(PlatformPermissionCodes.SYSTEM_BACKUP_VIEW)
  public ApiResponse<BackupStatusResponse> status() {
    return ApiResponse.success(monitorService.status());
  }

  /** 备份历史分页。 */
  @GetMapping("/runs")
  @RequirePermission(PlatformPermissionCodes.SYSTEM_BACKUP_VIEW)
  public ApiResponse<BackupRunsPageResponse> history(
      @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "10") int size) {
    return ApiResponse.success(monitorService.history(page, size));
  }
}
