package com.data.collection.platform.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares permission checks whose concrete permission code depends on a route key.
 * The interceptor resolves the stable permission code from the route variable before
 * the controller is invoked, keeping HTTP/session concerns out of business endpoints.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RequirePagePermission {
  Resource resource();

  Action action();

  enum Resource {
    STATISTIC_BOARD,
    ANALYTICS_DASHBOARD
  }

  enum Action {
    VIEW,
    EXPORT,
    ISSUE_EXPORT
  }
}
