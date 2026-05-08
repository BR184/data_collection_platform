package com.data.collection.platform.security;

import com.data.collection.platform.entity.AuthUserResponse;

public interface PlatformAuthenticationProvider {
  AuthUserResponse authenticate(String username, String password);
}
