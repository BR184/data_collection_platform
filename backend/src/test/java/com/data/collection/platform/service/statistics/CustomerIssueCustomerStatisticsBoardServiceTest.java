package com.data.collection.platform.service.statistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import com.data.collection.platform.common.JsonUtils;
import com.data.collection.platform.entity.FactProjectionScope;
import com.data.collection.platform.entity.FactType;
import com.data.collection.platform.entity.ProjectionScopeType;
import com.data.collection.platform.entity.statistics.StatisticBoardResponse;
import com.data.collection.platform.entity.statistics.StatisticRowData;
import com.data.collection.platform.service.CustomerIssueFactQueryService;
import com.data.collection.platform.service.FactProjectionScopeKeyCodec;
import com.data.collection.platform.service.FactProjectionVersionService;
import com.data.collection.platform.service.IssueProjectionScopeResolver;
import com.data.collection.platform.service.IssueScopeDimension;
import com.data.collection.platform.service.SortSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * S04 / S05：客户统计板的快照请求必须显式声明实际读取来源，且业务日进入快照键。
 *
 * <p>业务日只进入键是不够的：本测试同时固定“键随业务日变化”和“键随来源选择变化”，
 * 避免跨日或换来源后继续命中同一份 READY 结果。
 */
@ExtendWith(MockitoExtension.class)
class CustomerIssueCustomerStatisticsBoardServiceTest {
  private static final long CUSTOMER_PROJECT_ID = 325L;

  @Mock private CustomerIssueFactQueryService factQueryService;
  @Mock private CustomerIssueMilestoneCatalogService milestoneCatalogService;
  @Mock private StatisticBoardSnapshotService snapshotService;
  @Mock private FactProjectionVersionService projectionVersionService;
  @Mock private IssueProjectionScopeResolver scopeResolver;
  @Mock private IssueFactBoardRuntimeSupport runtimeSupport;
  @Mock private StatisticIssueLinkSupport issueLinkSupport;

  private CustomerIssueCustomerStatisticsBoardService service;

  @BeforeEach
  void setUp() {
    org.mockito.Mockito.lenient()
        .when(milestoneCatalogService.defaultMilestone())
        .thenReturn("CC2026R3");
    org.mockito.Mockito.lenient()
        .when(milestoneCatalogService.resolveMilestoneValues("CC2026R3"))
        .thenReturn(List.of("CC2026 R3"));
    // 范围解析改为在一致性边界内进行，只有显式解析范围的用例才会消费它。
    org.mockito.Mockito.lenient()
        .when(scopeResolver.resolve(anyString(), any(), anyLong(), any(), any()))
        .thenAnswer(
            invocation -> {
              String source = invocation.getArgument(0);
              long projectId = invocation.getArgument(2);
              return Set.of(
                  new FactProjectionScope(
                      source,
                      FactType.ISSUE,
                      ProjectionScopeType.PROJECT,
                      FactProjectionScopeKeyCodec.project(projectId)));
            });
    service =
        new CustomerIssueCustomerStatisticsBoardService(
            new JsonUtils(new ObjectMapper()),
            factQueryService,
            milestoneCatalogService,
            snapshotService,
            new StatisticBoardSnapshotRequestFactory(
                new StatisticBoardReadScopeResolver(projectionVersionService, scopeResolver)),
            new StatisticBoardReadScopeResolver(projectionVersionService, scopeResolver),
            runtimeSupport,
            issueLinkSupport);
  }

  @Test
  void snapshotRequestCarriesBusinessDateAndActuallyReadSources() {
    when(projectionVersionService.knownSourceInstances(FactType.ISSUE))
        .thenReturn(Set.of("cc", "default"));

    StatisticBoardSnapshotService.SnapshotRequest withoutSource =
        captureRequest(Map.of("businessDate", "2026-09-22"));
    StatisticBoardSnapshotService.SnapshotRequest nextDay =
        captureRequest(Map.of("businessDate", "2026-09-23"));
    StatisticBoardSnapshotService.SnapshotRequest selectedSource =
        captureRequest(Map.of("businessDate", "2026-09-22", "sourceInstance", "cc"));

    assertThat(withoutSource.cacheFilterPayload().get("businessDate")).isEqualTo("2026-09-22");
    assertThat(sourceInstancesOf(withoutSource.readScopes().resolve()))
        .as("未选择来源时查询读取全部来源，版本必须覆盖每个已存在代际的来源")
        .containsExactly("cc", "default");
    assertThat(nextDay.cacheFilterPayload())
        .as("业务日进入快照键，跨日不得复用同一条 READY 结果")
        .isNotEqualTo(withoutSource.cacheFilterPayload());
    assertThat(selectedSource.readScopes().resolve())
        .as("显式选择来源时只声明该来源")
        .allSatisfy(scope -> assertThat(scope.sourceInstance()).isEqualTo("cc"));
    assertThat(selectedSource.cacheFilterPayload()).isNotEqualTo(withoutSource.cacheFilterPayload());
  }

  @Test
  void boardLoadReadsThroughOwnSnapshotRequestAndReturnsTotalRowForEmptyScope() {
    doAnswer(invocation -> ((Function<StatisticBoardSnapshotService.SourceRead, StatisticBoardResponse>)
                invocation.getArgument(1))
            .apply(StatisticBoardTestSnapshotScopes.testSourceRead()))
        .when(snapshotService)
        .readOrRefresh(any(), any());

    StatisticBoardResponse response =
        service.loadBoard(Map.of("businessDate", "2026-09-22", "groupBy", "CUSTOMER"));

    ArgumentCaptor<StatisticBoardSnapshotService.SnapshotRequest> captor =
        ArgumentCaptor.forClass(StatisticBoardSnapshotService.SnapshotRequest.class);
    org.mockito.Mockito.verify(snapshotService).readOrRefresh(captor.capture(), any());
    assertThat(captor.getValue().cacheFilterPayload().get("groupBy")).isEqualTo("CUSTOMER");
    assertThat(captor.getValue().cacheFilterPayload().get("businessDate")).isEqualTo("2026-09-22");
    assertThat(response.rows()).hasSize(1);
    StatisticRowData totalRow = response.rows().get(0);
    assertThat(totalRow.rowLabel()).isEqualTo("总计");
    assertThat(
            totalRow.cells().stream()
                .map(cell -> cell.numericValue())
                .filter(java.util.Objects::nonNull))
        .as("空范围的总计行数量为 0，比率保持 null")
        .allMatch(value -> value == 0L);
  }

  @Test
  void rejectsUnknownAndIncompleteMemberSelectionsForCustomerModuleAndFunction() {
    for (String member : List.of("customer", "module", "function")) {
      String kind = member + "Kind";

      assertThatThrownBy(() -> service.parseControlParams(Map.of(kind, "FUTURE")))
          .as("%s 不得把未知 kind 扩大成不限", member)
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> service.parseControlParams(Map.of(kind, "VALUE")))
          .as("%s 的 VALUE 必须同时有真实成员名", member)
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> service.parseControlParams(Map.of(kind, "MISSING", member, "甲")))
          .as("%s 的 MISSING 不能带成员值", member)
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> service.parseControlParams(Map.of(kind, "ALL", member, "甲")))
          .as("%s 的 ALL 不能带成员值", member)
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> service.parseControlParams(Map.of(member, "甲")))
          .as("%s 的成员值不能缺 kind", member)
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  private static List<String> sourceInstancesOf(Set<FactProjectionScope> scopes) {
    return scopes.stream().map(FactProjectionScope::sourceInstance).sorted().toList();
  }

  private StatisticBoardSnapshotService.SnapshotRequest captureRequest(Map<String, String> filters) {
    ArgumentCaptor<StatisticBoardSnapshotService.SnapshotRequest> captor =
        ArgumentCaptor.forClass(StatisticBoardSnapshotService.SnapshotRequest.class);
    doAnswer(invocation -> null).when(snapshotService).readOrRefresh(captor.capture(), any());
    service.loadBoard(filters);
    return captor.getValue();
  }
}
