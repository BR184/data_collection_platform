package com.data.collection.platform.entity;

import java.util.List;

public record GitlabSourceMetadataDiagnosticsResponse(
    boolean metadataOk,
    String metadataMessage,
    int sourceTableCount,
    int primaryKeyTableCount,
    int missingPrimaryKeyTableCount,
    int missingUpdatedAtTableCount,
    List<GitlabSourceTableDiagnosticsResponse> sourceTables) {

  public static GitlabSourceMetadataDiagnosticsResponse failure(String message) {
    return new GitlabSourceMetadataDiagnosticsResponse(false, message, 0, 0, 0, 0, List.of());
  }
}
