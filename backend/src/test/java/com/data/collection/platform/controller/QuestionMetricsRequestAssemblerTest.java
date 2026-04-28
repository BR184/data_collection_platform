package com.data.collection.platform.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.data.collection.platform.service.SystemTestIllegalRecordQueryRequest;
import com.data.collection.platform.service.SystemTestIssueSearchQueryRequest;
import org.junit.jupiter.api.Test;

class QuestionMetricsRequestAssemblerTest {

  private final QuestionMetricsRequestAssembler assembler =
      new QuestionMetricsRequestAssembler(new IssueFactRecordListRequestAssembler());

  @Test
  void shouldConvertIssueSearchRequest() {
    SystemTestIssueSearchListWebRequest request = new SystemTestIssueSearchListWebRequest();
    request.setProjectId(1001L);
    request.setKeyword("草图");
    request.setIssueIid("809");
    request.setTitle("崩溃");
    request.setProjectName("Rocksdb");
    request.setModuleName("草图");
    request.setTestingPhase("CC2026R1");
    request.setAuthorName("alice");
    request.setAssigneeName("bob");
    request.setSeverityLevel("LEVEL2");
    request.setIssueState("opened");
    request.setBugStatus("处理中");
    request.setCategory("功能缺陷");
    request.setMilestoneTitle("CC2026R1");
    request.setCreatedAtStart("2026-04-01");
    request.setCreatedAtEnd("2026-04-21");
    request.setUpdatedAtStart("2026-04-10");
    request.setUpdatedAtEnd("2026-04-22");
    request.setPage(2);
    request.setSize(10);
    request.setSortBy("updatedAt");
    request.setSortOrder("desc");

    SystemTestIssueSearchQueryRequest queryRequest = assembler.toIssueSearchQueryRequest(request);

    assertThat(queryRequest.testingPhase()).isEqualTo("CC2026R1");
    assertThat(queryRequest.authorName()).isEqualTo("alice");
    assertThat(queryRequest.assigneeName()).isEqualTo("bob");
    assertThat(queryRequest.listRequest().projectId()).isEqualTo(1001L);
    assertThat(queryRequest.listRequest().moduleName()).isEqualTo("草图");
  }

  @Test
  void shouldConvertIllegalRecordRequest() {
    SystemTestIllegalRecordListWebRequest request = new SystemTestIllegalRecordListWebRequest();
    request.setProjectId(1001L);
    request.setKeyword("草图");
    request.setTestingPhase("CC2026R1");
    request.setIllegalReason("未设定模块");
    request.setAuthorName("alice");
    request.setAssigneeName("bob");
    request.setFilterGroup("{\"logic\":\"AND\",\"conditions\":[]}");

    SystemTestIllegalRecordQueryRequest queryRequest = assembler.toIllegalRecordQueryRequest(request);

    assertThat(queryRequest.testingPhase()).isEqualTo("CC2026R1");
    assertThat(queryRequest.illegalReason()).isEqualTo("未设定模块");
    assertThat(queryRequest.authorName()).isEqualTo("alice");
    assertThat(queryRequest.assigneeName()).isEqualTo("bob");
    assertThat(queryRequest.filterGroupJson()).isEqualTo("{\"logic\":\"AND\",\"conditions\":[]}");
    assertThat(queryRequest.listRequest().projectId()).isEqualTo(1001L);
  }
}
