package com.data.collection.platform.entity;

public record GitlabSyncDiagnosticsResponse(
    Long configId,
    String sourceInstance,
    SourceMode sourceMode,
    boolean connectionOk,
    String connectionMessage,
    boolean whitelistOk,
    String whitelistMessage,
    int whitelistOptionCount,
    boolean metadataOk,
    String metadataMessage,
    int sourceTableCount,
    int primaryKeyTableCount,
    int missingPrimaryKeyTableCount,
    int missingUpdatedAtTableCount,
    java.util.List<GitlabSourceTableDiagnosticsResponse> sourceTables,
    String webhookReceiverUrl,
    boolean webhookAutoRegistrationSupported,
    boolean webhookAutoRegistered,
    String webhookMessage) {
}
