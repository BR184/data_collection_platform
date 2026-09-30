package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.data.collection.platform.service.CustomerIssueFactQueryService.FactScopeRequest;
import com.data.collection.platform.service.CustomerIssueFactQueryService.MemberSelection;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.RowMapper;

/**
 * 客户问题窄事实读取的稳定行序契约。
 *
 * <p>下游存在"取结果前 N 条"的消费（客户问题统计板规则说明的 {@code samples} 取前 5 条），
 * 没有确定顺序时同一份代码从零重建库会产出不同样本，快照门禁因此间歇性失败且无法通过重建消除。
 */
@ExtendWith(MockitoExtension.class)
class CustomerIssueFactQueryServiceTest {
  @Mock private IssueFactQueryService issueFactQueryService;

  @Test
  void loadPlacesStableOrderAfterEveryFilterCondition() {
    when(issueFactQueryService.query(anyString(), anyList(), any())).thenReturn(List.of());
    CustomerIssueFactQueryService service = new CustomerIssueFactQueryService(issueFactQueryService);

    service.load(
        new FactScopeRequest(
            "cc", List.of("CC2026 R3"), MemberSelection.all(), MemberSelection.all(), MemberSelection.all()));

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(issueFactQueryService).query(sql.capture(), anyList(), any(RowMapper.class));
    assertThat(sql.getValue())
        .contains(" and issue_fact.project_id = ?")
        .endsWith(" order by issue_fact.id");
  }
}
