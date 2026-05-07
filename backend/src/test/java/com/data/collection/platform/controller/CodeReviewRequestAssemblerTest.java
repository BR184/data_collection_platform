package com.data.collection.platform.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.data.collection.platform.entity.CodeReviewRuleConfig;
import com.data.collection.platform.service.CodeReviewIllegalRecordFilterOptionsRequest;
import com.data.collection.platform.service.CodeReviewIllegalRecordQueryRequest;
import com.data.collection.platform.service.CodeReviewMultiBoardOverviewRequest;
import com.data.collection.platform.service.CodeReviewRulePreviewRequest;
import java.util.List;
import org.junit.jupiter.api.Test;

class CodeReviewRequestAssemblerTest {

  @Test
  void shouldAssembleIllegalRecordListWebRequest() {
    CodeReviewIllegalRecordListWebRequest request = new CodeReviewIllegalRecordListWebRequest();
    request.setProjectId(325L);
    request.setRepositoryName("repo-a");
    request.setMergedAtStart("2026-04-01");
    request.setMergedAtEnd("2026-04-21");
    request.setKeyword("sample");
    request.setProjectName("Project X");
    request.setRequestType("merge_request");
    request.setTargetBranch("master");
    request.setMergedBy("Alice");
    request.setModuleName("payment");
    request.setIllegalType("missing-module");
    request.setMergeRequestIid("101");
    request.setOwner("owner-a");
    request.setSource("dgm");
    request.setFilterGroup("{\"logic\":\"AND\"}");
    request.setPage(2);
    request.setSize(10);
    request.setSortBy("mergedAt");
    request.setSortOrder("desc");
    request.setRuleConfig("{\"enabled\":true}");

    CodeReviewIllegalRecordQueryRequest queryRequest =
        new CodeReviewRequestAssembler().toIllegalRecordQueryRequest(request);

    assertThat(queryRequest)
        .isEqualTo(
            new CodeReviewIllegalRecordQueryRequest(
                325L,
                "repo-a",
                "2026-04-01",
                "2026-04-21",
                "sample",
                "Project X",
                "merge_request",
                "master",
                "Alice",
                "payment",
                "missing-module",
                "101",
                "owner-a",
                "dgm",
                "{\"logic\":\"AND\"}",
                2,
                10,
                "mergedAt",
                "desc",
                "{\"enabled\":true}"));
  }

  @Test
  void shouldKeepDefaultPaginationValues() {
    CodeReviewIllegalRecordListWebRequest request = new CodeReviewIllegalRecordListWebRequest();

    CodeReviewIllegalRecordQueryRequest queryRequest =
        new CodeReviewRequestAssembler().toIllegalRecordQueryRequest(request);

    assertThat(queryRequest.page()).isEqualTo(1);
    assertThat(queryRequest.size()).isEqualTo(20);
  }

  @Test
  void shouldAssembleMultiBoardOverviewWebRequest() {
    CodeReviewMultiBoardOverviewWebRequest request = new CodeReviewMultiBoardOverviewWebRequest();
    request.setSource("dgm");

    CodeReviewMultiBoardOverviewRequest queryRequest =
        new CodeReviewRequestAssembler().toMultiBoardOverviewRequest(request);

    assertThat(queryRequest).isEqualTo(new CodeReviewMultiBoardOverviewRequest("dgm"));
  }

  @Test
  void shouldAssembleIllegalRecordFilterOptionsWebRequest() {
    CodeReviewIllegalRecordFilterOptionsWebRequest request =
        new CodeReviewIllegalRecordFilterOptionsWebRequest();
    request.setProjectId(325L);
    request.setSource("cc");

    CodeReviewIllegalRecordFilterOptionsRequest queryRequest =
        new CodeReviewRequestAssembler().toIllegalRecordFilterOptionsRequest(request);

    assertThat(queryRequest).isEqualTo(new CodeReviewIllegalRecordFilterOptionsRequest(325L, "cc"));
  }

  @Test
  void shouldAssembleRulePreviewWebRequest() {
    CodeReviewRuleConfig ruleConfig = new CodeReviewRuleConfig(true, List.of(), null);
    CodeReviewRulePreviewWebRequest request = new CodeReviewRulePreviewWebRequest();
    request.setProjectId(325L);
    request.setRepositoryName("repo-a");
    request.setMergedAtStart("2026-04-01");
    request.setMergedAtEnd("2026-04-21");
    request.setKeyword("sample");
    request.setProjectName("Project X");
    request.setRequestType("merge_request");
    request.setTargetBranch("master");
    request.setMergedBy("Alice");
    request.setModuleName("payment");
    request.setIllegalType("missing-module");
    request.setMergeRequestIid("101");
    request.setOwner("owner-a");
    request.setSource("dgm");
    request.setRuleConfig(ruleConfig);

    CodeReviewRulePreviewRequest previewRequest =
        new CodeReviewRequestAssembler().toRulePreviewRequest(request);

    assertThat(previewRequest)
        .isEqualTo(
            new CodeReviewRulePreviewRequest(
                325L,
                "repo-a",
                "2026-04-01",
                "2026-04-21",
                "sample",
                "Project X",
                "merge_request",
                "master",
                "Alice",
                "payment",
                "missing-module",
                "101",
                "owner-a",
                "dgm",
                ruleConfig));
  }
}
