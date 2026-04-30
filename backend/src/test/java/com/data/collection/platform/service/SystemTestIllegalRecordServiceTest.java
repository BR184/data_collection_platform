package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.SystemTestIllegalRecordFilterOptionsResponse;
import com.data.collection.platform.entity.SystemTestIllegalRecordListResponse;
import com.data.collection.platform.entity.statistics.StatisticBoardRuleExplanationResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SystemTestIllegalRecordServiceTest {

  @Mock private IssueFactRecordRepository issueFactRecordRepository;
  @Mock private SystemTestScopeProfile systemTestScopeProfile;

  @Test
  void shouldUseSqlPageForPlainIllegalListRequests() {
    SystemTestIllegalRecordService service = service();
    when(issueFactRecordRepository.findPage(any()))
        .thenReturn(
            new PageSlice<>(
                List.of(
                    record(
                        300,
                        "illegal",
                        "draft",
                        "CC2026R1 system test",
                        true,
                        SystemTestIllegalReasonSupport.MISSING_MODULE,
                        false)),
                1,
                1,
                20));

    SystemTestIllegalRecordListResponse response =
        service.listRecords(
            new SystemTestIllegalRecordQueryRequest(
                new IssueFactRecordListRequest(
                    1001L,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    1,
                    20,
                    "updatedAt",
                    "desc"),
                null,
                null,
                null,
                null,
                null));

    assertThat(response.total()).isEqualTo(1);
    verify(issueFactRecordRepository)
        .findPage(
            argThat(
                query ->
                    query.scope() == IssueFactRecordPageQuery.Scope.SYSTEM_TEST
                        && query.illegalOnly()
                        && query.excludeExcluded()
                        && query.supportedSystemIllegalReasonsOnly()));
  }

  @Test
  void shouldApplySystemTestIllegalFiltersThroughSharedPipeline() {
    SystemTestIllegalRecordService service = service();
    when(issueFactRecordRepository.findPage(any()))
        .thenReturn(
            new PageSlice<>(
                List.of(
                    record(
                        301,
                        "draft illegal",
                        "draft",
                        "CC2026R1 phase1 system test",
                        true,
                        SystemTestIllegalReasonSupport.MISSING_MODULE,
                        false)),
                1,
                1,
                20));

    SystemTestIllegalRecordListResponse response =
        service.listRecords(
            new SystemTestIllegalRecordQueryRequest(
                new IssueFactRecordListRequest(
                    1001L,
                    "illegal",
                    null,
                    null,
                    "Rocksdb",
                    "draft",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    1,
                    20,
                    "updatedAt",
                    "desc"),
                "phase1",
                SystemTestIllegalReasonSupport.MISSING_MODULE,
                "alice",
                "bob",
                null));

    assertThat(response.records()).hasSize(1);
    assertThat(response.records().getFirst().issueIid()).isEqualTo(301);
    assertThat(response.records().getFirst().illegalReason())
        .isEqualTo(SystemTestIllegalReasonSupport.MISSING_MODULE);
    assertThat(response.records().getFirst().issueLink())
        .isEqualTo("http://gitlab.example.com/-/issues/301");
    verify(issueFactRecordRepository)
        .findPage(
            argThat(
                query ->
                    query.scope() == IssueFactRecordPageQuery.Scope.SYSTEM_TEST
                        && query.illegalOnly()
                        && query.excludeExcluded()
                        && query.supportedSystemIllegalReasonsOnly()
                        && "phase1".equals(query.testingPhase())
                        && SystemTestIllegalReasonSupport.MISSING_MODULE.equals(query.illegalReason())
                        && "alice".equals(query.authorName())
                        && "bob".equals(query.assigneeName())));
  }

  @Test
  void shouldExposeMissingModuleAsFilterOptionForEmptyModuleRows() {
    SystemTestIllegalRecordService service = service();
    when(issueFactRecordRepository.findByProjectId(null))
        .thenReturn(
            List.of(
                record(
                    401,
                    "missing module",
                    "",
                    "CC2026R1 system test",
                    true,
                    SystemTestIllegalReasonSupport.MISSING_MODULE,
                    false)));
    when(systemTestScopeProfile.matches(any())).thenReturn(true);

    SystemTestIllegalRecordFilterOptionsResponse response = service.getFilterOptions(null);

    assertThat(response.moduleNames())
        .extracting("value")
        .contains(SystemTestIllegalReasonSupport.MISSING_MODULE);
    assertThat(response.illegalReasons())
        .extracting("value")
        .contains(SystemTestIllegalReasonSupport.MISSING_MODULE);
  }

  @Test
  void shouldExplainSystemTestIllegalRules() {
    SystemTestIllegalRecordService service = service();
    when(issueFactRecordRepository.findByProjectId(null))
        .thenReturn(
            List.of(
                record(
                    501,
                    "missing severity",
                    "draft",
                    "CC2026R1 system test",
                    true,
                    SystemTestIllegalReasonSupport.MISSING_SEVERITY,
                    false),
                record(
                    502,
                    "template",
                    "draft",
                    "CC2026R1 system test",
                    true,
                    SystemTestIllegalReasonSupport.TEMPLATE_NOT_FOLLOWED,
                    false)));
    when(systemTestScopeProfile.matches(any())).thenReturn(true);

    StatisticBoardRuleExplanationResponse response = service.getRuleExplanation(null);

    assertThat(response.boardKey()).isEqualTo("system-test-illegal-records");
    assertThat(response.metricDefinitions())
        .extracting("label")
        .contains(
            SystemTestIllegalReasonSupport.MISSING_SEVERITY,
            SystemTestIllegalReasonSupport.MISSING_MODULE,
            SystemTestIllegalReasonSupport.TEMPLATE_NOT_FOLLOWED,
            SystemTestIllegalReasonSupport.NON_UNIQUE_REASON);
    assertThat(response.flowSteps()).extracting("key").contains("reason-normalize");
  }

  private SystemTestIllegalRecordService service() {
    GitlabMirrorProperties gitlabMirrorProperties = new GitlabMirrorProperties();
    gitlabMirrorProperties.setWebBaseUrl("http://gitlab.example.com");
    return new SystemTestIllegalRecordService(
        issueFactRecordRepository,
        systemTestScopeProfile,
        new ObjectMapper(),
        gitlabMirrorProperties);
  }

  private IssueFactRecord record(
      int issueIid,
      String title,
      String moduleName,
      String testingPhase,
      boolean illegal,
      String illegalReason,
      boolean excluded) {
    LocalDateTime now = LocalDateTime.of(2026, 4, 24, 10, 0);
    List<String> modules = moduleName.isBlank() ? List.of() : List.of(moduleName);
    return new IssueFactRecord(
        1001L,
        "Rocksdb",
        9200L + issueIid,
        issueIid,
        title,
        "opened",
        testingPhase,
        testingPhase,
        "LEVEL2",
        "",
        "processing",
        "bug",
        "",
        excluded,
        excluded ? "rejected" : "",
        false,
        false,
        false,
        false,
        false,
        "CC2026R1",
        "alice",
        "bob",
        modules,
        List.of(testingPhase, "system test"),
        false,
        "",
        "",
        false,
        false,
        illegal,
        illegalReason,
        now.minusDays(3),
        now.minusDays(issueIid % 3),
        null);
  }
}
