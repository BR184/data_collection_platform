package com.data.collection.platform.entity;

import java.io.Serializable;

public record AuthUserResponse(
    String username,
    String displayName,
    AuthRole role,
    boolean authenticated
) implements Serializable {
  public static AuthUserResponse guest() {
    return new AuthUserResponse("guest", "游客", AuthRole.GUEST, false);
  }
}
