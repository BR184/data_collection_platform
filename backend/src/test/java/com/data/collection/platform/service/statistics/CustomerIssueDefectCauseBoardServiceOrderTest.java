package com.data.collection.platform.service.statistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.data.collection.platform.common.JsonUtils;
import com.data.collection.platform.entity.read.FactReadSelection;
import com.data.collection.platform.service.CustomerIssueScopeProfile;
import com.data.collection.platform.service.IssueFactQueryService;
import com.data.collection.platform.service.read.TestReadSelections;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.RowMapper;

/**
 * 客户问题缺陷原因板的事实读取稳定行序契约。
 *
 * <p>该板的人员候选按“数据源首次出现顺序”生成，候选数组又随看板定义进入候选预热产物的内容校验和；缺少
 * 稳定次序时同一份代码从零重建库会得到不同校验和，发布前取证因此间歇失败。次序口径与
 * {@code IssueFactRecordRepository}、{@code CustomerIssueFactQueryService} 的同一列保持一致。
 */
@ExtendWith(MockitoExtension.class)
class CustomerIssueDefectCauseBoardServiceOrderTest {
  @Mock private IssueFactQueryService issueFactQueryService;
  @Mock private CustomerIssueMilestoneCatalogService milestoneCatalogService;
  @Mock private StatisticBoardSnapshotService snapshotService;
  @Mock private StatisticBoardSnapshotRequestFactory snapshotRequestFactory;

  @Test
  void factQueryAppendsStableOrderSoPersonCandidatesAreReproducible() {
    when(milestoneCatalogService.forRead(any(FactReadSelection.class))).thenReturn(milestoneCatalogService);
    when(snapshotRequestFactory.forRead(any(FactReadSelection.class))).thenReturn(snapshotRequestFactory);
    when(milestoneCatalogService.listMilestones()).thenReturn(List.of());
    when(snapshotService.readOrRefresh(any(FactReadSelection.class), any(), any()))
        .thenAnswer(invocation -> ((Function<Object, Object>) invocation.getArgument(2)).apply(null));
    when(issueFactQueryService.query(any(FactReadSelection.class), anyString(), any(Map.class), anyString(),
        anyList(), anyString(), any(RowMapper.class))).thenReturn(List.of());

    CustomerIssueDefectCauseBoardService board = new CustomerIssueDefectCauseBoardService(
        new JsonUtils(new ObjectMapper()),
        org.mockito.Mockito.mock(com.data.collection.platform.service.RealtimeWorkspaceService.class),
        org.mockito.Mockito.mock(com.data.collection.platform.service.RealtimeIncrementalRefreshService.class),
        issueFactQueryService,
        org.mockito.Mockito.mock(CustomerIssueScopeProfile.class),
        org.mockito.Mockito.mock(StatisticIssueLinkSupport.class),
        milestoneCatalogService,
        snapshotService,
        snapshotRequestFactory).forRead(TestReadSelections.all());

    board.loadBoard(Map.of());

    ArgumentCaptor<String> order = ArgumentCaptor.forClass(String.class);
    verify(issueFactQueryService).query(any(FactReadSelection.class), anyString(), any(Map.class), anyString(),
        anyList(), order.capture(), any(RowMapper.class));
    assertThat(order.getValue()).isEqualTo("order by business_order_id, id");
  }
}
