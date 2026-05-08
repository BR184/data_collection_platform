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
    String webhookReceiverUrl,
    boolean webhookAutoRegistrationSupported,
    boolean webhookAutoRegistered,
    String webhookMessage) {
}
