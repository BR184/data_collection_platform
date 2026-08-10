package com.data.collection.platform.bi.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.data.collection.platform.bi.infrastructure.BiCatMirrorManager.SaveConfig;
import com.data.collection.platform.security.PlatformPermissionCodes;
import com.data.collection.platform.security.RequirePermission;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;

class BiCatMirrorSecurityContractTest {
  @Test
  void catMirrorEndpoints_requirePlatformMirrorPermissions() throws Exception {
    RequirePermission controllerPermission =
        BiCatMirrorController.class.getAnnotation(RequirePermission.class);
    assertThat(controllerPermission.value())
        .containsExactly(PlatformPermissionCodes.SYSTEM_MIRROR_VIEW);

    assertMethodPermission(
        "saveConfig", new Class<?>[] {SaveConfig.class},
        PlatformPermissionCodes.SYSTEM_MIRROR_CONFIG);
    assertMethodPermission(
        "saveMappings", new Class<?>[] {List.class},
        PlatformPermissionCodes.SYSTEM_MIRROR_CONFIG);
    assertMethodPermission(
        "testConnection", new Class<?>[0],
        PlatformPermissionCodes.SYSTEM_MIRROR_CONFIG);
    assertMethodPermission(
        "fullSync", new Class<?>[0],
        PlatformPermissionCodes.SYSTEM_MIRROR_SYNC);
    assertMethodPermission(
        "fullCompensationSync", new Class<?>[0],
        PlatformPermissionCodes.SYSTEM_MIRROR_SYNC);
  }

  private void assertMethodPermission(
      String methodName,
      Class<?>[] parameterTypes,
      String permission) throws Exception {
    Method method = BiCatMirrorController.class.getMethod(methodName, parameterTypes);
    RequirePermission annotation = method.getAnnotation(RequirePermission.class);
    assertThat(annotation.value()).containsExactly(permission);
  }
}
