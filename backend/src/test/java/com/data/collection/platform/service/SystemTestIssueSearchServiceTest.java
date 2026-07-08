package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import com.data.collection.platform.entity.labelgroup.LabelGroupExpansionResponse;
import com.data.collection.platform.entity.SystemTestIssueSearchListResponse;
import com.data.collection.platform.service.labelgroup.LabelGroupExpansionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SystemTestIssueSearchServiceTest {

  @Mock private IssueFactRecordRepository issueFactRecordRepository;
  @Mock private GitlabResourceLinkService issueLinkService;
  @Mock private LabelGroupExpansionService labelGroupExpansionService;
  @Mock private SystemTestPhaseScopeResolver phaseScopeResolver;
  @Mock private SystemTestPhaseCatalogService phaseCatalogService;
  @Mock private PageRecordSnapshotService pageRecordSnapshotService;

  @Test
  void shouldUseSqlPageForPlainSearchRequests() {
    SystemTestIssueSearchService service = service();
    when(issueFactRecordRepository.findByProjectId(1001L))
        .thenReturn(
            List.of(record(300, "draft", "draft", "phase1 system test", "alice", "bob")));

    SystemTestIssueSearchListResponse response =
        service.listRecords(
            new SystemTestIssueSearchQueryRequest(
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
                null));

    assertThat(response.total()).isEqualTo(1);
    verify(issueFactRecordRepository).findByProjectId(1001L);
  }

  @Test
  void shouldApplySystemTestSpecificFiltersThroughRequestObject() {
    SystemTestIssueSearchService service = service();
    when(issueLinkService.issueUrl("default", 1001L, 301))
        .thenReturn("http://gitlab.example.com/group/project/-/issues/301");
    when(issueFactRecordRepository.findByProjectId(1001L))
        .thenReturn(
            List.of(record(301, "draft crash", "draft", "phase1 system test", "alice", "bob")));

    SystemTestIssueSearchListResponse response =
        service.listRecords(
            new SystemTestIssueSearchQueryRequest(
                new IssueFactRecordListRequest(
                    1001L,
                    "draft",
                    null,
                    null,
                    "Rocksdb",
                    "draft",
                    "LEVEL2",
                    null,
                    null,
                    "processing",
                    "bug",
                    "CC2026R1",
                    null,
                    null,
                    null,
                    null,
                    1,
                    20,
                    "updatedAt",
                    "desc"),
                "phase1",
                "alice",
                "bob",
                null));

    assertThat(response.records()).hasSize(1);
    assertThat(response.records().getFirst().issueIid()).isEqualTo(301);
    assertThat(response.records().getFirst().issueLink())
        .isEqualTo("http://gitlab.example.com/group/project/-/issues/301");
    verify(issueFactRecordRepository).findByProjectId(1001L);
  }

  @Test
  void shouldFilterDirtyHistoricalModuleValuesFromFilterOptions() {
    SystemTestIssueSearchService service = service();
    when(issueFactRecordRepository.findForFilterOptions(any()))
        .thenReturn(
            List.of(
                recordWithModules(
                    302,
                    "mixed module values",
                    List.of("草图", "9007", "前端", "分支：发布", "CC2023R3客户"),
                    "phase1 system test",
                    "alice",
                    "bob"),
                recordWithModules(
                    303,
                    "another module",
                    List.of("曲线"),
                    "phase1 system test",
                    "alice",
                    "bob")));

    List<String> moduleOptions =
        service.getFilterOptions(null).moduleNames().stream().map(option -> option.value()).toList();

    assertThat(moduleOptions).containsExactly("曲线", "草图");
  }

  @Test
  void shouldBuildIssueSearchFilterOptionsFromAllIssueFacts() {
    SystemTestIssueSearchService service = service();
    when(issueFactRecordRepository.findForFilterOptions(any()))
        .thenReturn(
            List.of(
                recordWithModules(
                    304,
                    "general issue",
                    List.of("通用模块"),
                    "需求阶段",
                    "carol",
                    "dave")));

    List<String> moduleOptions =
        service.getFilterOptions(null).moduleNames().stream().map(option -> option.value()).toList();

    assertThat(moduleOptions).containsExactly("通用模块");
  }

  @Test
  void shouldBuildIssueSearchFilterOptionsWithinSourceInstance() {
    SystemTestIssueSearchService service = service();
    when(issueFactRecordRepository.findForFilterOptions(any()))
        .thenReturn(
            List.of(
                recordWithModules(
                    305,
                    "cc issue",
                    List.of("CC模块"),
                    "需求阶段",
                    "carol",
                    "dave")));

    List<String> moduleOptions =
        service.getFilterOptions(null, "cc").moduleNames().stream().map(option -> option.value()).toList();

    assertThat(moduleOptions).containsExactly("CC模块");
    verify(issueFactRecordRepository)
        .findForFilterOptions(argThat(request -> "cc".equals(request.sourceInstance())));
    verify(issueFactRecordRepository, never()).findByProjectId(null);
  }

  @Test
  void shouldApplyLabelGroupFilterOnAssigneeNameThroughFilterGroup() {
    SystemTestIssueSearchService service = service();
    when(labelGroupExpansionService.expand(1L, "STRING", "assigneeName", "question-metrics-issue-search", null))
        .thenReturn(new LabelGroupExpansionResponse(1L, "核心人员", "STRING", List.of("bob"), List.of()));
    when(issueFactRecordRepository.findByProjectId(1001L))
        .thenReturn(
            List.of(
                record(306, "assigned to bob", "草图", "phase1 system test", "alice", "bob"),
                record(307, "assigned to alice", "草图", "phase1 system test", "alice", "alice")));

    String filterGroupJson =
        "{\"logic\":\"AND\",\"conditions\":[{\"fieldKey\":\"assigneeName\",\"operator\":\"eq\","
            + "\"valueType\":\"LABEL_GROUP\",\"labelGroupId\":1,\"labelGroupName\":\"核心人员\"}]}";

    SystemTestIssueSearchListResponse response =
        service.listRecords(
            new SystemTestIssueSearchQueryRequest(
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
                filterGroupJson));

    assertThat(response.records()).extracting(record -> record.issueIid()).containsExactly(306);
    verify(issueFactRecordRepository, never()).findPage(any());
  }

  @Test
  void shouldWriteExpandedLabelGroupSnapshotWhenExportingWorkbook() {
    SystemTestIssueSearchService service = service();
    when(labelGroupExpansionService.expand(1L, "STRING", "assigneeName", "question-metrics-issue-search", null))
        .thenReturn(new LabelGroupExpansionResponse(1L, "核心人员", "STRING", List.of("bob"), List.of()));
    when(issueFactRecordRepository.findByProjectId(1001L))
        .thenReturn(List.of(record(308, "assigned to bob", "草图", "phase1 system test", "alice", "bob")));

    String filterGroupJson =
        "{\"logic\":\"AND\",\"conditions\":[{\"fieldKey\":\"assigneeName\",\"operator\":\"eq\","
            + "\"valueType\":\"LABEL_GROUP\",\"labelGroupId\":1,\"labelGroupName\":\"核心人员\"}]}";

    byte[] workbook =
        service.exportRecordsWorkbook(
            new SystemTestIssueSearchQueryRequest(
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
                filterGroupJson));

    assertThat(workbook).isNotEmpty();
  }

  @Test
  void shouldKeepSourceInstanceScopeWhenApplyingLabelGroupFilterInJavaPath() {
    SystemTestIssueSearchService service = service();
    when(labelGroupExpansionService.expand(1L, "STRING", "assigneeName", "question-metrics-issue-search", "cc"))
        .thenReturn(new LabelGroupExpansionResponse(1L, "核心人员", "STRING", List.of("bob"), List.of()));
    when(issueFactRecordRepository.findByProjectId(1001L))
        .thenReturn(
            List.of(
                recordWithSource("cc", 309, "cc source", List.of("草图"), "phase1 system test", "alice", "bob"),
                recordWithSource("default", 310, "default source", List.of("草图"), "phase1 system test", "alice", "bob")));

    String filterGroupJson =
        "{\"logic\":\"AND\",\"conditions\":[{\"fieldKey\":\"assigneeName\",\"operator\":\"eq\","
            + "\"valueType\":\"LABEL_GROUP\",\"labelGroupId\":1,\"labelGroupName\":\"核心人员\"}]}";

    SystemTestIssueSearchListResponse response =
        service.listRecords(
            new SystemTestIssueSearchQueryRequest(
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
                    null,
                    null,
                    "cc",
                    1,
                    20,
                    "updatedAt",
                    "desc"),
                null,
                null,
                null,
                filterGroupJson));

    assertThat(response.records()).extracting(record -> record.issueIid()).containsExactly(309);
  }

  private SystemTestIssueSearchService service() {
    org.mockito.Mockito.lenient().when(phaseCatalogService.listParentNames(9L)).thenReturn(List.of("CC2026R1"));
    org.mockito.Mockito.lenient()
        .when(phaseScopeResolver.resolveLegacyCrownCadPhases(anyString()))
        .thenAnswer(invocation -> List.of(invocation.getArgument(0, String.class)));
    org.mockito.Mockito.lenient()
        .when(phaseScopeResolver.resolveLegacyCrownCadPhases(anyList()))
        .thenAnswer(invocation -> invocation.getArgument(0, List.class));
    org.mockito.Mockito.lenient()
        .when(phaseScopeResolver.matchesLegacyCrownCadPhase(anyString(), anyString()))
        .thenReturn(true);
    org.mockito.Mockito.lenient()
        .when(phaseScopeResolver.matchesLegacyCrownCadPhases(anyString(), anyList()))
        .thenReturn(true);
    org.mockito.Mockito.lenient()
        .when(pageRecordSnapshotService.issueFactSourceVersion())
        .thenReturn("test-issue-version");
    org.mockito.Mockito.lenient()
        .when(pageRecordSnapshotService.readOrRefresh(any(), any(), any()))
        .thenAnswer(invocation -> ((java.util.function.Supplier<?>) invocation.getArgument(2)).get());
    return new SystemTestIssueSearchService(
        issueFactRecordRepository,
        issueLinkService,
        new ObjectMapper(),
        labelGroupExpansionService,
        phaseScopeResolver,
        phaseCatalogService,
        pageRecordSnapshotService);
  }

  private IssueFactRecord record(
      int issueIid,
      String title,
      String moduleName,
      String testingPhase,
      String authorName,
      String assigneeName) {
    return recordWithModules(issueIid, title, List.of(moduleName), testingPhase, authorName, assigneeName);
  }

  private IssueFactRecord recordWithModules(
      int issueIid,
      String title,
      List<String> moduleNames,
      String testingPhase,
      String authorName,
      String assigneeName) {
    LocalDateTime now = LocalDateTime.of(2026, 4, 24, 10, 0);
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
        false,
        "",
        false,
        false,
        false,
        false,
        false,
        "CC2026R1",
        authorName,
        assigneeName,
        moduleNames,
        "",
        List.of(testingPhase, "system test"),
        false,
        "",
        "",
        false,
        false,
        false,
        "",
        List.of(),
        now.minusDays(3),
        now.minusDays(1),
        null);
  }

  private IssueFactRecord recordWithSource(
      String sourceInstance,
      int issueIid,
      String title,
      List<String> moduleNames,
      String testingPhase,
      String authorName,
      String assigneeName) {
    LocalDateTime now = LocalDateTime.of(2026, 4, 24, 10, 0);
    return new IssueFactRecord(
        1001L,
        sourceInstance,
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
        false,
        "",
        false,
        false,
        false,
        false,
        false,
        "CC2026R1",
        authorName,
        assigneeName,
        moduleNames,
        "",
        List.of(testingPhase, "system test"),
        false,
        "",
        "",
        false,
        false,
        false,
        "",
        List.of(),
        now.minusDays(3),
        now.minusDays(1),
        null);
  }
}
