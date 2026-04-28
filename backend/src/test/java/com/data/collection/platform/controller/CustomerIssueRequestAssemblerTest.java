package com.data.collection.platform.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.data.collection.platform.service.CustomerIssueIllegalRecordQueryRequest;
import com.data.collection.platform.service.CustomerIssueRecordQueryRequest;
import org.junit.jupiter.api.Test;

class CustomerIssueRequestAssemblerTest {

  private final CustomerIssueRequestAssembler assembler =
      new CustomerIssueRequestAssembler(new IssueFactRecordListRequestAssembler());

  @Test
  void shouldConvertCustomerIssueRecordRequest() {
    CustomerIssueRecordListWebRequest request = new CustomerIssueRecordListWebRequest();
    request.setTopic("delay");
    request.setProjectId(325L);
    request.setKeyword("sample");
    request.setIssueIid("201");
    request.setTitle("title");
    request.setProjectName("CC_PRODUCT");
    request.setModuleName("Sketch");
    request.setSeverityLevel("S2");
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
    request.setReasonCategory("Design");
    request.setFilterGroup("{\"logic\":\"AND\",\"conditions\":[]}");

    CustomerIssueRecordQueryRequest queryRequest = assembler.toRecordQueryRequest(request);

    assertThat(queryRequest.topic()).isEqualTo("delay");
    assertThat(queryRequest.reasonCategory()).isEqualTo("Design");
    assertThat(queryRequest.filterGroupJson()).isEqualTo("{\"logic\":\"AND\",\"conditions\":[]}");
    assertThat(queryRequest.listRequest().projectId()).isEqualTo(325L);
    assertThat(queryRequest.listRequest().priorityLevel()).isEqualTo("P1");
  }

  @Test
  void shouldConvertCustomerIssueIllegalRecordRequest() {
    CustomerIssueIllegalRecordListWebRequest request = new CustomerIssueIllegalRecordListWebRequest();
    request.setProjectId(325L);
    request.setKeyword("sample");
    request.setIllegalReason("Module mismatch");
    request.setFilterGroup("{\"logic\":\"AND\",\"conditions\":[]}");

    CustomerIssueIllegalRecordQueryRequest queryRequest = assembler.toIllegalRecordQueryRequest(request);

    assertThat(queryRequest.illegalReason()).isEqualTo("Module mismatch");
    assertThat(queryRequest.filterGroupJson()).isEqualTo("{\"logic\":\"AND\",\"conditions\":[]}");
    assertThat(queryRequest.listRequest().projectId()).isEqualTo(325L);
    assertThat(queryRequest.listRequest().keyword()).isEqualTo("sample");
  }
}
