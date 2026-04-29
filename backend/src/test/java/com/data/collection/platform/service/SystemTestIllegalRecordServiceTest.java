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
                List.of(record(300, "非法", "草图", "CC2026R1系统测试", true, "缺失模块", false)),
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
    when(issueFactRecordRepository.findByProjectId(1001L))
        .thenReturn(
            List.of(
                record(301, "草图非法", "草图", "CC2026R1第一轮系统测试", true, "缺失模块", false),
                record(302, "草图合法", "草图", "CC2026R1第一轮系统测试", false, "", false),
                record(303, "流程问题", "草图", "CC2026R1第一轮系统测试", true, "流程越位", false),
                record(304, "排除样本", "草图", "CC2026R1第一轮系统测试", true, "缺失模块", true),
                record(305, "其他轮次", "草图", "CC2026R2第一轮系统测试", true, "缺失模块", false)));
    when(systemTestScopeProfile.matches(any())).thenReturn(true);

    SystemTestIllegalRecordListResponse response =
        service.listRecords(
            new SystemTestIllegalRecordQueryRequest(
                new IssueFactRecordListRequest(
                    1001L,
                    "非法",
                    null,
                    null,
                    "Rocksdb",
                    "草图",
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
                "CC2026R1第一轮",
                "未设定模块",
                "alice",
                "bob",
                null));

    assertThat(response.records()).hasSize(1);
    assertThat(response.records().getFirst().issueIid()).isEqualTo(301);
    assertThat(response.records().getFirst().illegalReason()).isEqualTo("未设定模块");
    assertThat(response.records().getFirst().issueLink())
        .isEqualTo("http://gitlab.example.com/-/issues/301");
  }

  @Test
  void shouldExposeMissingModuleAsFilterOptionForEmptyModuleRows() {
    SystemTestIllegalRecordService service = service();
    when(issueFactRecordRepository.findByProjectId(null))
        .thenReturn(
            List.of(
                record(401, "缺模块", "", "CC2026R1第一轮系统测试", true, "缺失模块", false)));
    when(systemTestScopeProfile.matches(any())).thenReturn(true);

    SystemTestIllegalRecordFilterOptionsResponse response = service.getFilterOptions(null);

    assertThat(response.moduleNames()).extracting("value").contains("未设定模块");
    assertThat(response.illegalReasons()).extracting("value").contains("未设定模块");
  }

  @Test
  void shouldExplainSystemTestIllegalRules() {
    SystemTestIllegalRecordService service = service();
    when(issueFactRecordRepository.findByProjectId(null))
        .thenReturn(
            List.of(
                record(501, "缺严重程度", "草图", "CC2026R1第一轮系统测试", true, "缺失严重程度", false),
                record(502, "模板错误", "草图", "CC2026R1第一轮系统测试", true, "未按照模板回复", false)));
    when(systemTestScopeProfile.matches(any())).thenReturn(true);

    StatisticBoardRuleExplanationResponse response = service.getRuleExplanation(null);

    assertThat(response.title()).isEqualTo("系统测试非法数据规则说明");
    assertThat(response.metricDefinitions())
        .extracting("label")
        .contains("未设定严重程度", "未设定模块", "未按照模板回复", "缺陷原因不唯一");
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
        "处理中",
        "功能缺陷",
        "",
        excluded,
        excluded ? "已拒绝" : "",
        false,
        false,
        false,
        false,
        false,
        "CC2026R1",
        "alice",
        "bob",
        modules,
        List.of(testingPhase, "系统测试"),
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
