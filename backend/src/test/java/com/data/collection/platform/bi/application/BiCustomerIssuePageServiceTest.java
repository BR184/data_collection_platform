package com.data.collection.platform.bi.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import com.data.collection.platform.bi.domain.BiCustomerIssueCalculator;
import com.data.collection.platform.bi.domain.model.BiCustomerIssuePageData;
import com.data.collection.platform.bi.domain.model.BiDataStatus;
import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.entity.OptionItemResponse;
import com.data.collection.platform.entity.FactProjectionScope;
import com.data.collection.platform.entity.FactType;
import com.data.collection.platform.entity.ProjectionScopeType;
import com.data.collection.platform.service.CustomerIssueFactQueryService;
import com.data.collection.platform.service.CustomerIssueFactQueryService.CustomerIssueFact;
import com.data.collection.platform.service.CustomerIssueFactQueryService.FactScopeRequest;
import com.data.collection.platform.service.CustomerIssueFactQueryService.MemberSelection;
import com.data.collection.platform.service.IssueScopeDimension;
import com.data.collection.platform.service.statistics.CustomerIssueMilestoneCatalogService;
import com.data.collection.platform.service.statistics.StatisticBoardReadScopeResolver;
import com.data.collection.platform.service.statistics.StatisticBoardSnapshotService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class BiCustomerIssuePageServiceTest {
  private static final String MILESTONE = "customer-2026";
  private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 9, 24);

  private CustomerIssueFactQueryService factQueryService;
  private CustomerIssueMilestoneCatalogService milestoneCatalog;
  private StatisticBoardSnapshotService snapshotService;
  private StatisticBoardReadScopeResolver scopeResolver;
  private JdbcTemplate jdbcTemplate;
  private BiCustomerIssuePageService service;
  private List<CustomerIssueFact> facts;

  @BeforeEach
  void setUp() {
    factQueryService = mock(CustomerIssueFactQueryService.class);
    milestoneCatalog = mock(CustomerIssueMilestoneCatalogService.class);
    snapshotService = mock(StatisticBoardSnapshotService.class);
    scopeResolver = mock(StatisticBoardReadScopeResolver.class);
    jdbcTemplate = mock(JdbcTemplate.class);
    service = new BiCustomerIssuePageService(
        factQueryService, milestoneCatalog, snapshotService, scopeResolver, jdbcTemplate,
        new BiCustomerIssueCalculator());
    facts = List.of(
        fact(1L, List.of("missing", "客户乙"), List.of("模块A", "模块B"), "功能甲"),
        fact(2L, List.of(), List.of("模块A"), null));

    when(milestoneCatalog.defaultMilestone()).thenReturn(MILESTONE);
    when(milestoneCatalog.listOptions()).thenReturn(List.of(new OptionItemResponse("客户里程碑 2026", MILESTONE)));
    when(milestoneCatalog.resolveMilestoneValues(MILESTONE)).thenReturn(List.of("客户里程碑 2026"));
    when(factQueryService.load(any(FactScopeRequest.class))).thenReturn(facts);
    when(scopeResolver.resolve(any(), eq(325L), eq(IssueScopeDimension.MILESTONE), eq(MILESTONE)))
        .thenReturn(Set.of(new FactProjectionScope("default", FactType.ISSUE,
            ProjectionScopeType.ISSUE_SCOPE_GROUP, "customer-issue-milestone")));
    when(jdbcTemplate.queryForObject("select current_date", LocalDate.class)).thenReturn(BUSINESS_DATE);
    when(snapshotService.withinConsistentSourceRead(any(), any())).thenAnswer(invocation -> {
      StatisticBoardSnapshotService.SourceReadPlan plan = invocation.getArgument(0);
      plan.resolve();
      @SuppressWarnings("unchecked")
      Function<StatisticBoardSnapshotService.SourceRead, Object> action = invocation.getArgument(1);
      return action.apply(new StatisticBoardSnapshotService.SourceRead(Set.of(), "issue-v17", 0L));
    });
  }

  @Test
  void candidateOptionsComeFromFullRangeAndExposeRealSameNameSeparately() {
    var response = service.load(new BiCustomerIssuePageService.Query(
        MILESTONE,
        MemberSelection.of("missing"),
        MemberSelection.of("模块A"),
        MemberSelection.all()));

    assertEquals(BiDataStatus.READY, response.status());
    assertEquals("issue-v17", response.sourceVersion());
    assertEquals(BUSINESS_DATE, response.data().businessDate());
    assertEquals(1L, metric(response.data(), "defect_total").value());
    assertEquals(List.of("missing", "客户乙"), response.data().customers().stream()
        .filter(option -> option.kind().equals("VALUE"))
        .map(BiCustomerIssuePageData.MemberOption::value)
        .toList());
    assertEquals("missing", response.data().customers().stream()
        .filter(option -> "missing".equals(option.value()))
        .findFirst().orElseThrow().displayName());
    assertEquals("未标注客户", response.data().customers().stream()
        .filter(option -> option.kind().equals("MISSING"))
        .findFirst().orElseThrow().displayName());
    assertTrue(response.data().modules().stream().anyMatch(option -> "模块B".equals(option.value())));
  }

  @Test
  void missingCustomerSelectionDoesNotMatchTheRealSameNameMember() {
    var response = service.load(new BiCustomerIssuePageService.Query(
        MILESTONE,
        MemberSelection.missing(),
        MemberSelection.all(),
        MemberSelection.all()));

    assertEquals(1L, metric(response.data(), "defect_total").value());
    assertEquals(1L, response.data().filteredFactCount());
  }

  @Test
  void unqualifiedSourceReturnsAnIncompletePageInsteadOfAnEmptyPage() {
    doAnswer(invocation -> {
      throw new BizException("ISSUE事实来源尚未完成全量核验");
    }).when(snapshotService).withinConsistentSourceRead(any(), any());

    var response = service.load(BiCustomerIssuePageService.Query.all());

    assertEquals(BiDataStatus.INCOMPLETE, response.status());
    assertEquals("ISSUE事实来源尚未完成全量核验", response.sections().getFirst().message());
    assertNull(response.data());
    assertEquals("", response.sourceVersion());
  }

  @Test
  void unknownCreationTimeMarksDailyDefectTrendIncompleteInsteadOfZeroOrEmpty() {
    when(factQueryService.load(any(FactScopeRequest.class)))
        .thenReturn(List.of(factWithoutCreatedTime(5L, List.of("客户甲"), List.of("模块A"), "功能甲")));

    var response = service.load(BiCustomerIssuePageService.Query.all());

    var trend = response.sections().stream()
        .filter(section -> "daily-defect-trend".equals(section.key()))
        .findFirst().orElseThrow();
    assertEquals(BiDataStatus.INCOMPLETE, trend.status());
    assertTrue(trend.message().contains("缺少"));
    assertEquals(BiDataStatus.INCOMPLETE, response.status());
    // 未知创建时间不得伪装为零：整条序列保持 null，而不是补点成 0。
    assertTrue(response.data().dailyTrend().stream().allMatch(day -> day.createdCount() == null));
  }

  private static CustomerIssueFact factWithoutCreatedTime(
      long issueId, List<String> customers, List<String> modules, String function) {
    CustomerIssueFact base = fact(issueId, customers, modules, function);
    return new CustomerIssueFact(
        base.sourceSystem(), base.sourceInstance(), base.projectId(), base.issueId(), base.issueIid(),
        base.projectName(), base.title(), base.issueState(), base.milestoneTitle(), base.authorName(),
        base.assigneeName(), base.severityLevel(), base.priorityLevel(), base.bugStatus(), base.category(),
        base.reasonCategory(), base.moduleNames(), base.functionName(), base.delayCause(), base.delayReason(),
        base.exclusionReason(), base.labelNames(), base.illegalReason(), base.illegalReasons(),
        base.excluded(), base.delayIssue(), base.responseDelayed(), base.resolveDelayed(),
        base.regression(), base.crash(), base.level1Other(),
        null, base.updatedAtSource(), base.closedAtSource(), base.researchTemplateTime(),
        base.fixedLabelTime(), base.customerRequirement(), base.customerNames());
  }

  private static BiCustomerIssuePageData.Metric metric(BiCustomerIssuePageData data, String key) {
    return data.defectMetrics().stream().filter(item -> item.key().equals(key)).findFirst().orElseThrow();
  }

  private static CustomerIssueFact fact(
      long issueId, List<String> customers, List<String> modules, String function) {
    return new CustomerIssueFact(
        "gitlab", "default", 325L, issueId, issueId + 100, "客户项目", "议题 " + issueId,
        "opened", "客户里程碑 2026", "创建人", "", "LEVEL1", "P1", "待处理", "", "新增理解偏差",
        modules, function, "", "", "", "", "", "", false, false, false, false,
        false, false, false, LocalDateTime.of(2026, 9, 20, 8, 0), null, null, null, null,
        false, customers);
  }
}
