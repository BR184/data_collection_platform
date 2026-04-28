package com.data.collection.platform.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.data.collection.platform.service.IssueFactRecordListRequest;
import org.junit.jupiter.api.Test;

class IssueFactRecordListRequestAssemblerTest {

  private final IssueFactRecordListRequestAssembler assembler = new IssueFactRecordListRequestAssembler();

  @Test
  void shouldConvertWebRequestToServiceRequest() {
    IssueFactRecordListWebRequest request = new IssueFactRecordListWebRequest();
    request.setProjectId(325L);
    request.setKeyword("sample");
    request.setIssueIid("101");
    request.setTitle("title");
    request.setProjectName("CC_PRODUCT");
    request.setModuleName("Sketch");
    request.setSeverityLevel("LEVEL2");
    request.setPriorityLevel("P1");
    request.setIssueState("opened");
    request.setBugStatus("Open");
    request.setCategory("Bug");
    request.setMilestoneTitle("R1");
    request.setCreatedAtStart("2026-04-01");
    request.setCreatedAtEnd("2026-04-21");
    request.setUpdatedAtStart("2026-04-10");
    request.setUpdatedAtEnd("2026-04-22");
    request.setPage(2);
    request.setSize(10);
    request.setSortBy("updatedAt");
    request.setSortOrder("desc");

    IssueFactRecordListRequest serviceRequest = assembler.toServiceRequest(request);

    assertThat(serviceRequest.projectId()).isEqualTo(325L);
    assertThat(serviceRequest.keyword()).isEqualTo("sample");
    assertThat(serviceRequest.issueIid()).isEqualTo("101");
    assertThat(serviceRequest.title()).isEqualTo("title");
    assertThat(serviceRequest.projectName()).isEqualTo("CC_PRODUCT");
    assertThat(serviceRequest.moduleName()).isEqualTo("Sketch");
    assertThat(serviceRequest.severityLevel()).isEqualTo("LEVEL2");
    assertThat(serviceRequest.priorityLevel()).isEqualTo("P1");
    assertThat(serviceRequest.issueState()).isEqualTo("opened");
    assertThat(serviceRequest.bugStatus()).isEqualTo("Open");
    assertThat(serviceRequest.category()).isEqualTo("Bug");
    assertThat(serviceRequest.milestoneTitle()).isEqualTo("R1");
    assertThat(serviceRequest.createdAtStart()).isEqualTo("2026-04-01");
    assertThat(serviceRequest.createdAtEnd()).isEqualTo("2026-04-21");
    assertThat(serviceRequest.updatedAtStart()).isEqualTo("2026-04-10");
    assertThat(serviceRequest.updatedAtEnd()).isEqualTo("2026-04-22");
    assertThat(serviceRequest.page()).isEqualTo(2);
    assertThat(serviceRequest.size()).isEqualTo(10);
    assertThat(serviceRequest.sortField()).isEqualTo("updatedAt");
    assertThat(serviceRequest.sortOrder()).isEqualTo("desc");
  }
}
