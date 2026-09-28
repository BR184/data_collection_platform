package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.common.JsonUtils;
import com.data.collection.platform.entity.FactType;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * S07：客户问题统计板（boardKey {@code customer-issue-customer-statistics}）的页面级登记。
 *
 * <p>看板本身由注册表按 boardKey 收集，但页面能否打开还取决于三处显式登记：权限映射（否则
 * 授权拦截器找不到权限键）、工作区事实依赖（否则实时刷新拒绝该工作区）与同步元数据分类
 * （否则展示的时间退回 GitLab 全局同步时间）。本测试锁定这三处登记，避免漏登记只在运行期暴露。
 */
@ExtendWith(MockitoExtension.class)
class CustomerIssueCustomerStatisticsWiringTest {
  private static final String BOARD_KEY = "customer-issue-customer-statistics";

  @Mock private GitlabConfigService configService;
  @Mock private CodeReviewMatchModeConfigService matchModeConfigService;
  @Mock private JdbcTemplate jdbcTemplate;

  @Test
  void pagePermissionKeyResolverMapsViewAndExportForTheNewBoard() {
    PagePermissionKeyResolver resolver = new PagePermissionKeyResolver();

    assertThat(resolver.boardView(BOARD_KEY)).isEqualTo("customer_issue.customer.view");
    assertThat(resolver.boardExport(BOARD_KEY)).isEqualTo("customer_issue.customer.export");
    assertThatThrownBy(() -> resolver.boardView("customer-issue-unknown"))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("统计页面权限未定义");
  }

  @Test
  void workspaceDependencyCatalogRequiresIssueFactForTheNewBoard() {
    RealtimeWorkspaceDependencyCatalog.WorkspaceDependency dependency =
        RealtimeWorkspaceDependencyCatalog.require(BOARD_KEY);

    assertThat(dependency.workspaceKey()).isEqualTo(BOARD_KEY);
    assertThat(dependency.factTypes())
        .as("新板只消费 ISSUE 事实，实时刷新不得要求其他事实类型")
        .containsExactly(FactType.ISSUE);
    assertThat(dependency.sourceTables()).isNotEmpty();
  }

  @Test
  void syncMetadataUsesCustomerIssueFactBuildInsteadOfGlobalGitlabTime() {
    LocalDateTime factFinishedAt = LocalDateTime.of(2026, 9, 22, 9, 30);
    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setLastIncrementalSyncAt(LocalDateTime.of(2026, 8, 1, 0, 0));
    lenient().when(configService.getConfig()).thenReturn(config);
    lenient()
        .doReturn(List.of(new RealtimeWorkspaceSyncMetadata(factFinishedAt, factFinishedAt.minusHours(1), factFinishedAt)))
        .when(jdbcTemplate)
        .query(anyString(), any(RowMapper.class), any());

    RealtimeWorkspaceSyncMetadataService service =
        new RealtimeWorkspaceSyncMetadataService(
            configService, matchModeConfigService, new JsonUtils(new ObjectMapper()), jdbcTemplate);

    RealtimeWorkspaceSyncMetadata metadata = service.resolve(BOARD_KEY, Map.of());

    assertThat(metadata.taskFinishedAt())
        .as("新板与既有客户问题板一样以本地 ISSUE 事实构建时间为准")
        .isEqualTo(factFinishedAt);
    assertThat(metadata.lastSyncedAt()).isNotEqualTo(config.getLastIncrementalSyncAt());
  }
}
