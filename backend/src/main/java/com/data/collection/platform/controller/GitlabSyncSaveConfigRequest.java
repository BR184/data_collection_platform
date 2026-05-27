package com.data.collection.platform.controller;

import com.data.collection.platform.entity.SourceMode;
import com.data.collection.platform.entity.WhitelistMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;

public record GitlabSyncSaveConfigRequest(
    Long id,
    @NotBlank String name,
    boolean enabled,
    Boolean sourceEnabled,
    boolean autoSyncEnabled,
    Boolean systemHookEnabled,
    String sourceInstance,
    @NotNull SourceMode sourceMode,
    @NotNull WhitelistMode whitelistMode,
    List<String> whitelistTables,
    String dbHost,
    Integer dbPort,
    String dbName,
    String dbUsername,
    String dbPassword,
    String dockerContainerName,
    String systemHookSecret,
    Long systemHookProjectId,
    @NotNull Integer compensationIntervalMinutes,
    Boolean fullCompensationEnabled,
    String fullCompensationTime,
    @NotBlank String syncThreadMode,
    @NotNull BigDecimal syncThreadValue,
    Integer maxSyncThreads) {}
