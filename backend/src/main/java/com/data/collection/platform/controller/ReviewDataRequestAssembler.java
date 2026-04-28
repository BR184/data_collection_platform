package com.data.collection.platform.controller;

import com.data.collection.platform.service.ReviewDataRecordQueryRequest;
import org.springframework.stereotype.Component;

@Component
public class ReviewDataRequestAssembler {

  public ReviewDataRecordQueryRequest toQueryRequest(ReviewDataRecordListRequest request) {
    return new ReviewDataRecordQueryRequest(
        request.getKeyword(),
        request.getTitle(),
        request.getProjectName(),
        request.getModuleName(),
        request.getReviewOwner(),
        request.getReviewType(),
        request.getProblemStatus(),
        request.getReviewExpert(),
        request.getFilterGroup(),
        request.getPage(),
        request.getSize(),
        request.getSortBy(),
        request.getSortOrder());
  }
}
