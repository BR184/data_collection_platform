package com.data.collection.platform.entity;

public record IntegrationTestPhaseOptionResponse(
    Long projectId,
    String projectName,
    String testingPhase,
    long recordCount) {}
