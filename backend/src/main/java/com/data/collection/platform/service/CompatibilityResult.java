package com.data.collection.platform.service;

public record CompatibilityResult(boolean compatible, String message) {
  public static CompatibilityResult ok() {
    return new CompatibilityResult(true, "compatible");
  }

  public static CompatibilityResult incompatible(String message) {
    return new CompatibilityResult(false, message);
  }
}
