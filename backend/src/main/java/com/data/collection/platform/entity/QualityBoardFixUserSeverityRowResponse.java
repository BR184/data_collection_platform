package com.data.collection.platform.entity;

public record QualityBoardFixUserSeverityRowResponse(
    String name,
    Integer level1,
    Integer level2,
    Integer level3,
    Integer suggestion,
    Integer total) {}
