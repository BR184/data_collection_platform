package com.data.collection.platform.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.data.collection.platform.entity.backup.BackupConnectionTestRequest;
import com.data.collection.platform.entity.backup.BackupSettingsSaveRequest;
import com.data.collection.platform.security.PlatformPermissionCodes;
import com.data.collection.platform.security.RequirePermission;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

class DatabaseBackupControllerSecurityContractTest {
  @Test
  void test_readEndpoints_requireBackupViewPermission() throws Exception {
    assertPermission("settings", new Class<?>[0], PlatformPermissionCodes.SYSTEM_BACKUP_VIEW);
    assertPermission("status", new Class<?>[0], PlatformPermissionCodes.SYSTEM_BACKUP_VIEW);
    assertPermission("history", new Class<?>[] {int.class, int.class}, PlatformPermissionCodes.SYSTEM_BACKUP_VIEW);
  }

  @Test
  void test_manageEndpoints_requireBackupManagePermission() throws Exception {
    assertPermission(
        "saveSettings",
        new Class<?>[] {BackupSettingsSaveRequest.class, jakarta.servlet.http.HttpServletRequest.class},
        PlatformPermissionCodes.SYSTEM_BACKUP_MANAGE);
    assertPermission(
        "testConnection",
        new Class<?>[] {BackupConnectionTestRequest.class},
        PlatformPermissionCodes.SYSTEM_BACKUP_MANAGE);
    assertPermission("trigger", new Class<?>[0], PlatformPermissionCodes.SYSTEM_BACKUP_MANAGE);
  }

  private void assertPermission(String methodName, Class<?>[] parameterTypes, String permission)
      throws Exception {
    Method method = DatabaseBackupController.class.getMethod(methodName, parameterTypes);
    RequirePermission annotation = method.getAnnotation(RequirePermission.class);
    assertThat(annotation).as("端点 %s 缺少 @RequirePermission", methodName).isNotNull();
    assertThat(annotation.value()).containsExactly(permission);
  }
}
