package com.data.collection.platform.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.data.collection.platform.service.ReviewDataRecordQueryRequest;
import org.junit.jupiter.api.Test;

class ReviewDataRequestAssemblerTest {

  private final ReviewDataRequestAssembler assembler = new ReviewDataRequestAssembler();

  @Test
  void shouldConvertListRequestToQueryRequest() {
    ReviewDataRecordListRequest request = new ReviewDataRecordListRequest();
    request.setKeyword("review");
    request.setTitle("design");
    request.setProjectName("CrownCAD");
    request.setModuleName("Sketch");
    request.setReviewOwner("Alice");
    request.setReviewType("Document Review");
    request.setProblemStatus("Resolved");
    request.setReviewExpert("Bob");
    request.setFilterGroup("{\"logic\":\"AND\",\"conditions\":[]}");
    request.setPage(2);
    request.setSize(10);
    request.setSortBy("updatedAt");
    request.setSortOrder("desc");

    ReviewDataRecordQueryRequest queryRequest = assembler.toQueryRequest(request);

    assertThat(queryRequest.keyword()).isEqualTo("review");
    assertThat(queryRequest.title()).isEqualTo("design");
    assertThat(queryRequest.projectName()).isEqualTo("CrownCAD");
    assertThat(queryRequest.moduleName()).isEqualTo("Sketch");
    assertThat(queryRequest.reviewOwner()).isEqualTo("Alice");
    assertThat(queryRequest.reviewType()).isEqualTo("Document Review");
    assertThat(queryRequest.problemStatus()).isEqualTo("Resolved");
    assertThat(queryRequest.reviewExpert()).isEqualTo("Bob");
    assertThat(queryRequest.filterGroupJson()).isEqualTo("{\"logic\":\"AND\",\"conditions\":[]}");
    assertThat(queryRequest.page()).isEqualTo(2);
    assertThat(queryRequest.size()).isEqualTo(10);
    assertThat(queryRequest.sortField()).isEqualTo("updatedAt");
    assertThat(queryRequest.sortOrder()).isEqualTo("desc");
  }
}
