package com.data.collection.platform.bi.api;

import com.data.collection.platform.bi.infrastructure.BiCatMirrorManager;
import com.data.collection.platform.bi.infrastructure.BiCatMirrorManager.ConfigView;
import com.data.collection.platform.bi.infrastructure.BiCatMirrorManager.ConnectionView;
import com.data.collection.platform.bi.infrastructure.BiCatMirrorManager.MappingView;
import com.data.collection.platform.bi.infrastructure.BiCatMirrorManager.SaveConfig;
import com.data.collection.platform.bi.infrastructure.BiCatMirrorManager.SaveMapping;
import com.data.collection.platform.bi.infrastructure.BiCatMirrorManager.Settings;
import com.data.collection.platform.bi.infrastructure.BiCatMirrorManager.Submission;
import com.data.collection.platform.common.response.ApiResponse;
import com.data.collection.platform.security.PlatformPermissionCodes;
import com.data.collection.platform.security.RequirePermission;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 系统设置中的 CAT 镜像配置、目录映射和全量同步网关。 */
@RestController
@RequestMapping("/api/bi-cat-mirror")
@RequirePermission(PlatformPermissionCodes.SYSTEM_MIRROR_VIEW)
public class BiCatMirrorController {
  private final BiCatMirrorManager manager;

  public BiCatMirrorController(BiCatMirrorManager manager) {
    this.manager = manager;
  }

  /** 返回 CAT 配置、真实目录、映射和最近运行状态。 */
  @GetMapping("/settings")
  public ApiResponse<Settings> settings() {
    return ApiResponse.success(manager.settings());
  }

  /** 保存 CAT 连接和全量同步策略。 */
  @PutMapping("/config")
  @RequirePermission(PlatformPermissionCodes.SYSTEM_MIRROR_CONFIG)
  public ApiResponse<ConfigView> saveConfig(@RequestBody SaveConfig request) {
    return ApiResponse.success("CAT 镜像配置已保存", manager.saveConfig(request));
  }

  /** 保存 BI 产品版本到 CAT 稳定 ID 的显式映射。 */
  @PutMapping("/mappings")
  @RequirePermission(PlatformPermissionCodes.SYSTEM_MIRROR_CONFIG)
  public ApiResponse<List<MappingView>> saveMappings(
      @RequestBody List<SaveMapping> request) {
    return ApiResponse.success("CAT 产品版本映射已保存", manager.saveMappings(request));
  }

  /** 读取 CAT 完整目录以验证地址和响应契约，不写入镜像。 */
  @PostMapping("/test-connection")
  @RequirePermission(PlatformPermissionCodes.SYSTEM_MIRROR_CONFIG)
  public ApiResponse<ConnectionView> testConnection() {
    return ApiResponse.success(manager.testConnection());
  }

  /** 提交一次手动全量同步。 */
  @PostMapping("/full-sync")
  @RequirePermission(PlatformPermissionCodes.SYSTEM_MIRROR_SYNC)
  public ApiResponse<Submission> fullSync() {
    return ApiResponse.success(manager.startFullSync());
  }

  /** 提交一次手动全量补偿同步。 */
  @PostMapping("/full-compensation-sync")
  @RequirePermission(PlatformPermissionCodes.SYSTEM_MIRROR_SYNC)
  public ApiResponse<Submission> fullCompensationSync() {
    return ApiResponse.success(manager.startFullCompensationSync());
  }
}
