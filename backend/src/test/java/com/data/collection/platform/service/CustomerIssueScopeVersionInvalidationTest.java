package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.data.collection.platform.entity.FactProjectionScope;
import com.data.collection.platform.entity.FactType;
import com.data.collection.platform.entity.ProjectionScopeType;
import com.data.collection.platform.service.statistics.StatisticBoardSnapshotService;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * S04 / T25：来源与范围变化必须精确改变快照来源版本，无关范围不得被波及。
 *
 * <p>统计快照以 {@code FactProjectionVersionService} 的来源版本为键，版本必须对每一个实际读取的来源生效：
 * 非默认来源的事实变化要改变包含该来源的版本、但不改变只读默认来源的版本；目录/成员所属的范围组定义变化
 * 只影响该范围组；无关项目范围的推进不得改变本范围版本。
 */
@SpringBootTest
class CustomerIssueScopeVersionInvalidationTest {
  private static final long CUSTOMER_PROJECT_ID = 325L;
  private static final long UNRELATED_PROJECT_ID = 999_999L;
  private static final long FOREIGN_PROJECT_ID = 987_654L;
  private static final long FOREIGN_GROUP_ID = 987_650L;

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private FactProjectionGenerationService generationService;
  @Autowired private StatisticBoardSnapshotService snapshotService;

  @AfterEach
  void tearDown() {
    jdbcTemplate.update("delete from issue_scope_members where catalog_id = ?", FOREIGN_GROUP_ID);
    jdbcTemplate.update("delete from issue_scope_groups where id = ?", FOREIGN_GROUP_ID);
    jdbcTemplate.update("delete from issue_scope_catalogs where id = ?", FOREIGN_GROUP_ID);
  }

  @Test
  void advancingNonDefaultSourceInvalidatesOnlyVersionsReadingThatSource() {
    Set<FactProjectionScope> defaultOnly = projectScope("default");
    Set<FactProjectionScope> customOnly = projectScope("cc");
    String defaultBefore = versionOf(defaultOnly);
    String customBefore = versionOf(customOnly);
    String combinedBefore = versionOf(union(defaultOnly, customOnly));

    int advanced = generationService.advanceIssueScopeGenerations("cc", customOnly);

    assertThat(advanced).as("非默认来源范围必须被推进").isOne();
    assertThat(versionOf(customOnly)).as("读取 cc 的版本必须失效").isNotEqualTo(customBefore);
    assertThat(versionOf(defaultOnly))
        .as("只读默认来源的版本不得被其他来源的变化改写")
        .isEqualTo(defaultBefore);
    assertThat(versionOf(union(defaultOnly, customOnly)))
        .as("未选择来源时读取多源，版本必须覆盖每个来源")
        .isNotEqualTo(combinedBefore);
  }

  @Test
  void advancingUnrelatedProjectScopeKeepsCurrentVersion() {
    Set<FactProjectionScope> current = projectScope("default");
    String before = versionOf(current);

    int advanced =
        generationService.advanceIssueScopeGenerations(
            "default",
            Set.of(
                new FactProjectionScope(
                    "default",
                    FactType.ISSUE,
                    ProjectionScopeType.PROJECT,
                    FactProjectionScopeKeyCodec.project(UNRELATED_PROJECT_ID))));

    assertThat(advanced).isOne();
    assertThat(versionOf(current))
        .as("无关项目范围推进不得伪造本范围的版本变化")
        .isEqualTo(before);
  }

  @Test
  void changingScopeGroupDefinitionInvalidatesOnlyThatScopeGroupVersion() {
    insertForeignGroup();
    Set<FactProjectionScope> groupScope = groupScope("default");
    Set<FactProjectionScope> projectScope = projectScope("default");
    String groupBefore = versionOf(groupScope);
    String projectBefore = versionOf(projectScope);

    jdbcTemplate.update(
        "update issue_scope_groups set definition_generation = definition_generation + 1 where id = ?",
        FOREIGN_GROUP_ID);

    assertThat(versionOf(groupScope))
        .as("成员所属范围组的定义变化必须使消费该范围组的统计结果失效")
        .isNotEqualTo(groupBefore);
    assertThat(versionOf(projectScope))
        .as("范围组定义变化不得改写项目范围的版本")
        .isEqualTo(projectBefore);
  }

  /** 与真实目录隔离的外来项目目录：只服务于范围组版本断言。 */
  private void insertForeignGroup() {
    jdbcTemplate.update(
        """
        insert into issue_scope_catalogs(id, project_id, project_name, dimension, enabled)
        values (?, ?, '范围组版本样例', 'MILESTONE', true)
        """,
        FOREIGN_GROUP_ID,
        FOREIGN_PROJECT_ID);
    jdbcTemplate.update(
        """
        insert into issue_scope_groups(
          id, catalog_id, business_key, display_name, sort_order, enabled)
        values (?, ?, 'version-sample', '版本样例', 1, true)
        """,
        FOREIGN_GROUP_ID,
        FOREIGN_GROUP_ID);
  }

  private String versionOf(Set<FactProjectionScope> scopes) {
    return snapshotService.issueFactSourceVersion(scopes);
  }

  private Set<FactProjectionScope> projectScope(String sourceInstance) {
    return Set.of(
        new FactProjectionScope(
            sourceInstance,
            FactType.ISSUE,
            ProjectionScopeType.PROJECT,
            FactProjectionScopeKeyCodec.project(CUSTOMER_PROJECT_ID)));
  }

  private Set<FactProjectionScope> groupScope(String sourceInstance) {
    return Set.of(
        new FactProjectionScope(
            sourceInstance,
            FactType.ISSUE,
            ProjectionScopeType.ISSUE_SCOPE_GROUP,
            FactProjectionScopeKeyCodec.issueScopeGroup(
                FOREIGN_PROJECT_ID, IssueScopeDimension.MILESTONE, FOREIGN_GROUP_ID)));
  }

  private Set<FactProjectionScope> union(
      Set<FactProjectionScope> left, Set<FactProjectionScope> right) {
    LinkedHashSet<FactProjectionScope> merged = new LinkedHashSet<>(left);
    merged.addAll(right);
    return merged;
  }
}
