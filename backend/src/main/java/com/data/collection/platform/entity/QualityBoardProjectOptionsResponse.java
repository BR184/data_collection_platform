package com.data.collection.platform.entity;

import java.util.List;

public record QualityBoardProjectOptionsResponse(
    String defaultProjectName,
    List<OptionItemResponse> options) {}
