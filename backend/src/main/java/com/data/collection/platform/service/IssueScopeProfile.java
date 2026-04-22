package com.data.collection.platform.service;

public interface IssueScopeProfile {
  String key();

  boolean matches(IssueScopeContext context);
}
