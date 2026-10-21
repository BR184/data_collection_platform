package com.data.collection.platform.service.labelgroup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.data.collection.platform.entity.read.FactReadSelection;
import com.data.collection.platform.service.FactRevisionFixture;
import com.data.collection.platform.service.sync.PostgresIntegrationTestDatabase;
import java.time.LocalDate;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** 真实冻结目录及递归引用，目录修改不能改变既有页面的标签筛选。 */
class LabelGroupPublishedReadIntegrationTest {
  private static PostgresIntegrationTestDatabase database;

  @BeforeAll
  static void migrate() {
    database = PostgresIntegrationTestDatabase.open("label_published_read");
    Flyway.configure().dataSource(database.dataSource()).locations("classpath:db/migration").load().migrate();
  }

  @AfterAll
  static void close() { if (database != null) { database.close(); } }

  @Test
  void test_current_members_and_references_change_preserves_published_expansion() {
    JdbcTemplate jdbc = new JdbcTemplate(database.dataSource());
    JdbcLabelGroupRepository repository = new JdbcLabelGroupRepository(new NamedParameterJdbcTemplate(jdbc));
    LabelGroupRecord child = repository.createGroup("冻结子组", "STRING", "STATIC", "SAME_TYPE", null, null, "test");
    repository.replaceMembers(child.id(), List.of(new LabelGroupMemberRecord(null, child.id(), "旧模块", "旧模块", 0)));
    LabelGroupRecord parent = repository.createGroup("冻结组合组", "STRING", "COMPOSITE", "SAME_TYPE", null, null, "test");
    repository.replaceReferences(parent.id(), List.of(child.id()));
    var context = FactRevisionFixture.adoptIssueContext(jdbc);
    FactReadSelection selection = new FactReadSelection(List.of(context), LocalDate.of(2026, 10, 13));
    LabelGroupExpansionService factory = new LabelGroupExpansionService(new LabelGroupService(repository,
        org.mockito.Mockito.mock(LabelGroupDynamicRuleEvaluationService.class)));
    LabelGroupExpansionService oldPage = factory.forRead(selection);
    assertThat(oldPage.expand(parent.id(), "STRING", "moduleName", "system-test-defect-summary", "default").values())
        .containsExactly("旧模块");

    repository.replaceMembers(child.id(), List.of(new LabelGroupMemberRecord(null, child.id(), "新模块", "新模块", 0)));
    repository.replaceReferences(parent.id(), List.of());
    repository.replaceMembers(parent.id(), List.of(new LabelGroupMemberRecord(null, parent.id(), "当前直接成员", "当前直接成员", 0)));
    assertThat(factory.expand(parent.id(), "STRING", "moduleName", "system-test-defect-summary", "default").values())
        .containsExactly("当前直接成员");
    assertThat(oldPage.expand(parent.id(), "STRING", "moduleName", "system-test-defect-summary", "default").values())
        .containsExactly("旧模块");
    LabelGroupRepository frozen = repository.forRead(selection);
    assertThatThrownBy(() -> frozen.replaceMembers(child.id(), List.of()))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("不能执行目录写入");
    assertThat(repository.findById(child.id()).orElseThrow().members()).extracting(LabelGroupMemberRecord::memberValue)
        .containsExactly("新模块");
  }
}
