package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.data.collection.platform.entity.FactBuildResponse;
import com.data.collection.platform.entity.FactProjectionScope;
import com.data.collection.platform.entity.FactType;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.ProjectionScopeType;
import com.data.collection.platform.service.statistics.StatisticBoardSnapshotService;
import java.time.LocalDateTime;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * S04 / T25：延期三列定向更新必须推进本范围有效版本，且不伪造更新。
 *
 * <p>延期重算复制修订并只替换三列判定；候选构建期间不改变公开 generation，投影完成后统一发布。
 * 本类使用显式 P3 成功边界替身，发布门禁由 FactTargetPublicationServiceIntegrationTest 保护。
 *
 * <p>断言只用项目范围（无里程碑组时的解析结果），避免依赖目录夹具；写入 0 行时必须零推进。
 */
@SpringBootTest
class CustomerIssueDelayRefreshScopeVersionTest {
  private static final long PROJECT_ID = 325L;
  private static final long ISSUE_ID = 91001L;
  private static final long ISSUE_IID = 601L;
  private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 5, 6, 9, 0);

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private FactBuildService factBuildService;
  @Autowired private StatisticBoardSnapshotService snapshotService;
  @Autowired private FactTargetPublicationService candidatePublisher;

  @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
  private com.data.collection.platform.service.sync.SyncFactPublicationStateService sourceInputQualification;

  @BeforeEach
  void setUp() {
    // 事实组件测试用明确的完整输入替身；默认接管/完整依赖证明另有真库反例保护。
    org.mockito.Mockito.doReturn(true).when(sourceInputQualification).canBuildReadCandidate(
        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(FactType.class));
    GitlabIssueMirrorFixture.ensureSchema(jdbcTemplate);
    cleanUp();
    jdbcTemplate.execute("truncate read_publications, read_publication_domains cascade");
  }

  @AfterEach
  void tearDown() {
    cleanUp();
  }

  @Test
  void delayRefreshAdvancesScopeVersionOnlyWhenColumnsActuallyChange() {
    insertOpenCustomerIssueWithStaleDelayFlags();
    FactRevisionFixture.adoptIssueContext(jdbcTemplate);
    String versionBefore = projectScopeVersion();

    FactBuildResponse first =
        factBuildService.refreshCustomerIssueDelayFactsForConfig(new GitlabSyncConfig());

    assertThat(projectScopeVersion()).as("候选构建不能提前改变公开范围版本").isEqualTo(versionBefore);
    FactRevisionFixture.completeRequiredProjections(jdbcTemplate, candidatePublisher,
        com.data.collection.platform.entity.read.ReadPublicationDomainKey.gitlabFact("default", FactType.ISSUE), null);
    assertThat(first.affectedRows()).as("夹具必须制造出真实的三列变化").isPositive();
    assertThat(projectScopeVersion())
        .as("三列确实变化时必须推进范围版本，否则统计快照继续命中旧结果")
        .isNotEqualTo(versionBefore);

    // 第二轮：三列已是重算后的值，无任何写入 → 不得推进版本（不伪造更新）。
    String versionAfterFirstRefresh = projectScopeVersion();
    FactBuildResponse second =
        factBuildService.refreshCustomerIssueDelayFactsForConfig(new GitlabSyncConfig());
    assertThat(second.affectedRows()).isZero();
    FactRevisionFixture.completeRequiredProjections(jdbcTemplate, candidatePublisher,
        com.data.collection.platform.entity.read.ReadPublicationDomainKey.gitlabFact("default", FactType.ISSUE), null);
    assertThat(projectScopeVersion())
        .as("写入 0 行不得推进范围版本")
        .isEqualTo(versionAfterFirstRefresh);
  }

  @Test
  void delayRefreshWithoutAnyCustomerIssueDoesNotAdvanceVersion() {
    FactRevisionFixture.adoptIssueContext(jdbcTemplate);
    String versionBefore = projectScopeVersion();

    factBuildService.refreshCustomerIssueDelayFactsForConfig(new GitlabSyncConfig());

    assertThat(projectScopeVersion())
        .as("没有可重算议题时不得推进任何范围版本")
        .isEqualTo(versionBefore);
  }

  private String projectScopeVersion() {
    return snapshotService.issueFactSourceVersion(
        Set.of(
            new FactProjectionScope(
                GitlabSourceInstanceSupport.DEFAULT_SOURCE_INSTANCE,
                FactType.ISSUE,
                ProjectionScopeType.PROJECT,
                FactProjectionScopeKeyCodec.project(PROJECT_ID))));
  }

  /** 让库中三列与"按当前时间重算"的结果不一致，从而必然产生一次真实写入。 */
  private void insertOpenCustomerIssueWithStaleDelayFlags() {
    jdbcTemplate.update(
        """
        insert into issue_fact(
          source_system, source_instance, project_id, project_name, issue_id, issue_iid, title,
          issue_state, milestone_title, created_at_source, updated_at_source, bug_status,
          label_names, priority_level, is_excluded, is_fixed, delay_issue, response_overdue,
          is_response_delayed, is_resolve_delayed, is_legacy, is_illegal, deleted,
          has_response, resolve_sla_days, is_regression, is_crash, is_level1_other,
          is_customer_requirement
        ) values (
          'GITLAB', 'default', ?, 'CC_PRODUCT', ?, ?, '延期重算版本样例',
          'opened', '里程碑A', ?, ?, '处理中',
          '', null, false, false, false, false,
          false, false, false, false, false,
          false, 0, false, false, false,
          null
        )
        """,
        PROJECT_ID,
        ISSUE_ID,
        ISSUE_IID,
        CREATED_AT,
        CREATED_AT);
  }

  private void cleanUp() {
    jdbcTemplate.update("delete from issue_fact where project_id = ?", PROJECT_ID);
  }
}
