package com.data.collection.platform.entity.form;

import java.util.Set;

/** 一次表单请求的会话账号与已生效权限。 */
public record FormActorContext(String userId, String displayName, Set<String> permissions) {

  public FormActorContext {
    permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
  }

  public boolean hasPermission(String permissionCode) {
    return permissionCode != null && permissions.contains(permissionCode);
  }
}
