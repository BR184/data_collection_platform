package com.data.collection.platform.entity;

import java.util.List;

public record ReviewDataRecordDetailResponse(
    ReviewDataRecordRowResponse record,
    List<String> reviewExperts,
    List<ReviewDataProblemItemResponse> problemItems,
    List<ReviewDataDescriptionResponse> descriptions,
    List<ReviewDataContentResponse> contents) {}
