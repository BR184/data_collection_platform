package com.data.collection.platform.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.data.collection.platform.entity.FactChangeIdentity;
import com.data.collection.platform.entity.FactType;
import com.data.collection.platform.entity.MirrorRowChange;
import com.data.collection.platform.entity.VersionedFactChangeTarget;
import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.service.FactChangeTargetService;
import com.data.collection.platform.service.GitlabFactChangeResolver;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class FactChangeTargetServiceIntegrationTest {
  private static PostgresIntegrationTestDatabase database;

  private JdbcTemplate jdbcTemplate;
  private TransactionTemplate transactionTemplate;
  private GitlabFactChangeResolver changeResolver;
  private FactChangeTargetService service;

  @BeforeAll
  static void setUpDatabase() {
    database = PostgresIntegrationTestDatabase.open("fact_change_target_test");
  }

  @AfterAll
  static void closeDatabase() {
    if (database != null) {
      database.close();
    }
  }

  @BeforeEach
  void setUp() {
    DataSource dataSource = database.dataSource();
    jdbcTemplate = new JdbcTemplate(dataSource);
    dropSchema();
    createSchema();
    transactionTemplate =
        new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    changeResolver = mock(GitlabFactChangeResolver.class);
    GitlabMirrorProperties properties = new GitlabMirrorProperties();
    properties.setFactTargetBatchSize(2);
    service = new FactChangeTargetService(jdbcTemplate, changeResolver, properties);
  }

  @Test
  void test_multiple_targets_are_batched_without_losing_stable_order_or_versions() {
    List<FactChangeIdentity> identities =
        List.of(
            new FactChangeIdentity("alpha", FactType.MERGE_REQUEST, 601L, 42L, 9L),
            new FactChangeIdentity("alpha", FactType.ISSUE, 502L, 42L, 8L),
            new FactChangeIdentity("alpha", FactType.ISSUE, 501L, 42L, 7L));
    List<MirrorRowChange> changes =
        List.of(new MirrorRowChange(Map.of("id", 1L), Map.of("id", 1L)));
    when(changeResolver.resolve("alpha", "users", changes)).thenReturn(identities);

    List<VersionedFactChangeTarget> targets =
        transactionTemplate.execute(
            status -> service.registerChanges(10L, 20L, "alpha", "users", changes));

    assertThat(targets)
        .extracting(target -> target.identity().factType() + ":" + target.identity().rootId())
        .containsExactly("ISSUE:501", "ISSUE:502", "MERGE_REQUEST:601");
    List<Long> versions =
        targets.stream().map(VersionedFactChangeTarget::changeVersion).toList();
    assertThat(versions.get(0)).isPositive();
    assertThat(versions.get(1)).isGreaterThan(versions.get(0));
    assertThat(versions.get(2)).isGreaterThan(versions.get(1));
    assertThat(
            jdbcTemplate.queryForObject("select count(*) from fact_change_heads", Integer.class))
        .isEqualTo(3);
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from sync_run_fact_targets where mirror_run_id = 10",
                Integer.class))
        .isEqualTo(3);
  }

  @Test
  void test_allocated_version_older_than_existing_head_keeps_head_and_target_monotonic() {
    FactChangeIdentity identity =
        new FactChangeIdentity("alpha", FactType.ISSUE, 501L, 42L, 7L);
    List<MirrorRowChange> changes =
        List.of(new MirrorRowChange(Map.of("id", 501L), Map.of()));
    when(changeResolver.resolve("alpha", "issues", changes)).thenReturn(List.of(identity));
    jdbcTemplate.update(
        """
        insert into fact_change_heads(
            source_instance, fact_type, root_id,
            latest_change_version, published_version)
        values ('alpha', 'ISSUE', 501, 100, 0)
        """);

    List<VersionedFactChangeTarget> targets =
        transactionTemplate.execute(
            status -> service.registerChanges(10L, 20L, "alpha", "issues", changes));

    assertThat(targets).isNotNull().hasSize(1);
    assertThat(targets.getFirst().changeVersion()).isEqualTo(100L);
    assertThat(
            jdbcTemplate.queryForObject(
                "select latest_change_version from fact_change_heads where root_id = 501",
                Long.class))
        .isEqualTo(100L);
    assertThat(
            jdbcTemplate.queryForObject(
                "select change_version from sync_run_fact_targets where root_id = 501",
                Long.class))
        .isEqualTo(100L);
  }

  private void dropSchema() {
    jdbcTemplate.execute("drop table if exists sync_run_fact_targets cascade");
    jdbcTemplate.execute("drop table if exists fact_change_heads cascade");
    jdbcTemplate.execute("drop sequence if exists fact_change_version_seq cascade");
  }

  private void createSchema() {
    jdbcTemplate.execute("create sequence fact_change_version_seq start with 1");
    jdbcTemplate.execute(
        """
        create table fact_change_heads (
          source_instance varchar(128) not null,
          fact_type varchar(64) not null,
          root_id bigint not null,
          latest_change_version bigint not null,
          published_version bigint not null default 0,
          updated_at timestamp not null default current_timestamp,
          primary key (source_instance, fact_type, root_id)
        )
        """);
    jdbcTemplate.execute(
        """
        create table sync_run_fact_targets (
          mirror_run_id bigint not null,
          source_instance varchar(128) not null,
          fact_type varchar(64) not null,
          root_id bigint not null,
          change_version bigint not null,
          project_id bigint,
          iid bigint,
          first_task_id bigint,
          last_task_id bigint,
          publication_status varchar(32) not null,
          assigned_fact_run_id bigint,
          assigned_fact_build_task_id bigint,
          published_version bigint,
          published_by_fact_build_task_id bigint,
          published_at timestamp,
          created_at timestamp not null default current_timestamp,
          updated_at timestamp not null default current_timestamp,
          primary key (mirror_run_id, source_instance, fact_type, root_id)
        )
        """);
  }
}
