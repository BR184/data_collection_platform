package com.data.collection.platform.entity;

public record CollectFormEditContext(
    String editorId,
    String editorUsername,
    String remoteAddress,
    String userAgent) {
  public String resolvedEditorUsername(String fallbackReviewer) {
    if (editorUsername != null && !editorUsername.isBlank()) {
      return editorUsername.trim();
    }
    if (fallbackReviewer != null && !fallbackReviewer.isBlank()) {
      return fallbackReviewer.trim();
    }
    return "";
  }
}
