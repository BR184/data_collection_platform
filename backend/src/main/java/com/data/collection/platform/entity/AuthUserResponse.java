package com.data.collection.platform.entity;

import java.io.Serializable;
import java.util.List;
import java.util.Set;

public record AuthUserResponse(
    String username,
    String displayName,
    Set<String> roleCodes,
    List<String> roleNames,
    Set<String> permissions,
    boolean authenticated
) implements Serializable {
  public static AuthUserResponse guest() {
    return new AuthUserResponse("guest", "游客", Set.of(), List.of(), Set.of(), false);
  }

  public AuthUserResponse {
    roleCodes = roleCodes == null ? Set.of() : Set.copyOf(roleCodes);
    roleNames = roleNames == null ? List.of() : List.copyOf(roleNames);
    permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
  }

  public boolean hasPermission(String permissionCode) {
    return authenticated && permissions.contains(permissionCode);
  }
}
