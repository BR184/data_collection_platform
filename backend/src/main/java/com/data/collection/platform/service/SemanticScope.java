package com.data.collection.platform.service;

public record SemanticScope(
    String scopeKey,
    String scopeType,
    String entityType,
    String description,
    String sqlTemplateKey,
    String ruleDocReference,
    String ruleDocHash,
    boolean enabled) {}
