package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.data.collection.platform.common.JsonUtils;
import com.data.collection.platform.config.ReadFallbackProperties;
import com.data.collection.platform.entity.FactType;
import com.data.collection.platform.entity.read.ReadPublication;
import com.data.collection.platform.entity.read.ReadPublicationDomainKey;
import com.data.collection.platform.entity.read.ReadPublicationProducerKind;
import com.data.collection.platform.service.read.ReadPublicationRepository;
import com.data.collection.platform.service.read.ReadPublicationService;
import com.data.collection.platform.service.sync.PostgresIntegrationTestDatabase;
import com.data.collection.platform.service.sync.SyncFactPublicationStateService;
import com.data.collection.platform.service.sync.SyncRunEventRecorder;
import com.data.collection.platform.service.sync.SyncRunPublicationFenceService;
import com.data.collection.platform.config.GitlabMirrorProperties;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/**
 * 老平台正式导入（兼容评审事实）在真实 PostgreSQL 上的修订模型回归。
 *
 * <p>锁定三件事：导入不再依赖已被替换的自然键 {@code on conflict}（迁移后会报 SQLSTATE 42P10），而是把
 * 业务内容写成候选修订；已发布修订不会被原地改写或原地软删；来源消失只关闭对应完整身份的可见区间。
 */
class LegacyPlatformFormalImportRevisionIntegrationTest {
  private static final long MERGE_REQUEST_ID = 7701L;
  private static final long MERGE_REQUEST_IID = 901L;
  private static PostgresIntegrationTestDatabase database;

  private JdbcTemplate jdbcTemplate;
  private DataSourceTransactionManager transactionManager;
  private ReadPublicationRepository readPublicationRepository;
  private LegacyPlatformFormalImportService service;
  private FactTargetPublicationService targetPublicationService;

  @BeforeAll
  static void setUpDatabase() {
    database = PostgresIntegrationTestDatabase.open("legacy_formal_import_revision");
    Flyway.configure()
        .dataSource(database.dataSource())
        .locations("classpath:db/migration")
        .schemas("public")
        .defaultSchema("public")
        .cleanDisabled(false)
        .load()
        .migrate();
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
    transactionManager = new DataSourceTransactionManager(dataSource);
    truncate();
    jdbcTemplate.update("insert into code_review_match_mode_sync_state(id, status, finished_at) values (1, 'SUCCESS', current_timestamp) on conflict(id) do update set status = 'SUCCESS', finished_at = current_timestamp");
    readPublicationRepository = new ReadPublicationRepository(jdbcTemplate);
    ReadPublicationService readPublicationService =
        transactionalProxy(
            new ReadPublicationService(readPublicationRepository, new ReadFallbackProperties()));
    SyncFactPublicationStateService publicationStateService =
        mock(SyncFactPublicationStateService.class);
    FactTaskExecutionGuard executionGuard =
        new FactTaskExecutionGuard(jdbcTemplate, new FactTaskExecutionContext());
    targetPublicationService =
        transactionalProxy(
            new FactTargetPublicationService(
                jdbcTemplate,
                new FactProjectionScopeResolver(jdbcTemplate, readPublicationService),
                new FactProjectionGenerationService(jdbcTemplate),
                mock(SyncRunPublicationFenceService.class),
                publicationStateService,
                transactionalProxy(new FactPublicationTransaction(executionGuard)),
                executionGuard,
                mock(FactBuildTaskService.class),
                mock(SyncRunEventRecorder.class),
                new GitlabMirrorProperties(),
                readPublicationService,
                readPublicationRepository,
                new com.data.collection.platform.service.CustomerIssueMilestoneCatalogReconciliationService(jdbcTemplate),
                com.data.collection.platform.service.FactRevisionFixture.projectionArtifacts(
                    jdbcTemplate, readPublicationService, readPublicationRepository)));
    CodeReviewMatchModeConfigService configService = mock(CodeReviewMatchModeConfigService.class);
    service =
        new LegacyPlatformFormalImportService(
            mock(CodeReviewMatchModeSyncService.class),
            mock(CodeReviewMatchModeLegacyRefreshService.class),
            configService,
            mock(PageRecordSnapshotService.class),
            targetPublicationService,
            readPublicationService,
            readPublicationRepository,
            jdbcTemplate,
            new JsonUtils(new com.fasterxml.jackson.databind.ObjectMapper()),
            transactionManager);
  }

  @Test
  void test_promotion_writes_candidate_revisions_without_rewriting_published_rows() {
    insertMatchModeRecord("首轮走查");
    service.refreshAndPromoteCodeReviewRecord("cc", MERGE_REQUEST_IID);
    completeCandidate();

    ReadPublication first = currentPublication();
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from merge_request_fact where created_by_publication_id = ?",
                Long.class,
                first.id()))
        .as("导入内容必须归属本次发布的候选修订")
        .isOne();
    assertThat(visibleTitles(first.sequenceNo())).containsExactly("首轮走查");
    assertThat(jdbcTemplate.queryForObject(
            "select deleted from merge_request_fact where merge_request_id = ?",
            Boolean.class,
            MERGE_REQUEST_ID))
        .isFalse();

    updateMatchModeRecordTitle("第二轮走查");
    service.refreshAndPromoteCodeReviewRecord("cc", MERGE_REQUEST_IID);
    completeCandidate();

    ReadPublication second = currentPublication();
    assertThat(second.sequenceNo()).isEqualTo(first.sequenceNo() + 1L);
    assertThat(visibleTitles(second.sequenceNo())).containsExactly("第二轮走查");
    assertThat(visibleTitles(first.sequenceNo()))
        .as("已发布修订必须继续按原内容可读")
        .containsExactly("首轮走查");
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from merge_request_fact"
                    + " where visible_from is not null and visible_to is null",
                Long.class))
        .as("同一完整业务根只能有一个开放修订")
        .isOne();
  }

  @Test
  void test_vanished_source_record_disappears_only_in_new_version() {
    insertMatchModeRecord("将被撤回");
    service.refreshAndPromoteCodeReviewRecord("cc", MERGE_REQUEST_IID);
    completeCandidate();
    ReadPublication published = currentPublication();

    jdbcTemplate.update("delete from code_review_match_mode_records where merge_request_id = ?",
        MERGE_REQUEST_ID);
    service.refreshAndPromoteCodeReviewRecord("cc", MERGE_REQUEST_IID);
    completeCandidate();

    ReadPublication afterDelete = currentPublication();
    assertThat(afterDelete.sequenceNo()).isEqualTo(published.sequenceNo() + 1L);
    assertThat(visibleTitles(afterDelete.sequenceNo())).isEmpty();
    assertThat(visibleTitles(published.sequenceNo()))
        .as("删除只对新发布序号生效，旧版本仍可核对")
        .containsExactly("将被撤回");
    assertThat(
            jdbcTemplate.queryForObject(
                "select deleted from merge_request_fact where created_by_publication_id = ?",
                Boolean.class,
                published.id()))
        .as("不得用原地软删改写历史修订")
        .isFalse();
    assertThat(
            jdbcTemplate.queryForObject(
                "select operation from read_publication_fact_roots where publication_id = ?",
                String.class,
                afterDelete.id()))
        .isEqualTo("DELETE");
  }

  @Test
  void test_mature_unversioned_rows_are_taken_over_before_incremental_promotion() {
    // 模拟升级到修订模型之前就已存在的成熟导入结果：事实未版本化，域也还没有任何发布身份。
    insertMatchModeRecord("历史首轮");
    jdbcTemplate.update(
        """
        insert into merge_request_fact(
            source_system, source_instance, project_id, merge_request_id, merge_request_iid, title,
            deleted)
        values ('LEGACY_PLATFORM', 'cc', 0, ?, ?, '升级前已导入', false)
        """,
        MERGE_REQUEST_ID,
        MERGE_REQUEST_IID);
    assertThat(readPublicationRepository.findDomain(compatDomain())).isEmpty();

    jdbcTemplate.update("insert into legacy_platform_formal_import_runs(import_type, confirmation_text, code_review_requested, status) values ('CODE_REVIEW', 'test', true, 'SUCCESS')");
    updateMatchModeRecordTitle("接管后的新一轮");
    service.refreshAndPromoteCodeReviewRecord("cc", MERGE_REQUEST_IID);
    completeCandidate();

    ReadPublication current = currentPublication();
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from merge_request_fact where created_by_publication_id is null",
                Long.class))
        .as("成熟数据必须先被接管为初始版本，不能被局部集合冒充首个完整版本")
        .isZero();
    assertThat(visibleTitles(current.sequenceNo())).containsExactly("接管后的新一轮");
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from merge_request_fact where title = '升级前已导入'"
                    + " and created_by_publication_id is not null",
                Long.class))
        .as("被接管的旧修订必须仍然存在并可按旧序号读取")
        .isOne();
  }

  private void completeCandidate() {
    com.data.collection.platform.service.FactRevisionFixture.completeRequiredProjections(
        jdbcTemplate, targetPublicationService, compatDomain(), null);
  }

  @Test
  void test_first_targeted_refresh_prepares_entire_source_without_publishing_empty_baseline() {
    insertMatchModeRecord("first");
    jdbcTemplate.update("""
        insert into code_review_match_mode_records(source_instance, project_id, merge_request_id,
            merge_request_iid, title, legacy_source_id, synced_at)
        values ('cc', 0, 7702, 902, 'second', '7702', current_timestamp)
        """);
    service.refreshAndPromoteCodeReviewRecord("cc", MERGE_REQUEST_IID);
    assertThat(readPublicationRepository.findDomain(compatDomain()).orElseThrow().currentPublicationId()).isNull();
    ReadPublication candidate = readPublicationRepository.findActiveCandidate(
        readPublicationRepository.findDomain(compatDomain()).orElseThrow().id()).orElseThrow();
    assertThat(jdbcTemplate.queryForObject("select count(*) from merge_request_fact where created_by_publication_id = ?",
        Long.class, candidate.id())).isEqualTo(2L);
    completeCandidate();
    assertThat(visibleTitles(currentPublication().sequenceNo())).containsExactly("first", "second");
  }

  @Test
  void test_compatibility_domain_does_not_advance_gitlab_generation_namespace() {
    jdbcTemplate.update("""
        insert into fact_projection_generations(source_instance, fact_type, scope_type, scope_key, generation)
        values ('cc', 'MERGE_REQUEST', 'GLOBAL_VIEW', '*', 7)
        """);
    insertMatchModeRecord("legacy");
    service.refreshAndPromoteCodeReviewRecord("cc", MERGE_REQUEST_IID);
    completeCandidate();
    assertThat(jdbcTemplate.queryForObject("select generation from fact_projection_generations"
        + " where source_instance = 'cc' and fact_type = 'MERGE_REQUEST'", Long.class)).isEqualTo(7L);
    assertThat(jdbcTemplate.queryForObject("select committed_generation from read_publication_scope_generations"
        + " where publication_id = ? and scope_type = 'GLOBAL_VIEW'", Long.class, currentPublication().id())).isOne();
  }

  private <T> T transactionalProxy(T target) {
    ProxyFactory proxy = new ProxyFactory(target);
    proxy.setProxyTargetClass(true);
    proxy.addAdvice(
        new TransactionInterceptor(
            transactionManager, new AnnotationTransactionAttributeSource()));
    @SuppressWarnings("unchecked")
    T casted = (T) proxy.getProxy();
    return casted;
  }

  private ReadPublicationDomainKey compatDomain() {
    return new ReadPublicationDomainKey(
        ReadPublicationProducerKind.COMPAT_REVIEW, "cc", FactType.MERGE_REQUEST.name(), "");
  }

  private ReadPublication currentPublication() {
    return readPublicationRepository
        .findCurrentPublication(
            readPublicationRepository.findDomain(compatDomain()).orElseThrow().id())
        .orElseThrow();
  }

  private List<String> visibleTitles(long sequenceNo) {
    UUID epoch = currentPublication().epochId();
    return jdbcTemplate.queryForList(
        """
        select title
          from merge_request_fact
         where source_system = 'LEGACY_PLATFORM'
           and publication_epoch_id = ?
           and visible_from is not null
           and visible_from <= ?
           and (visible_to is null or ? < visible_to)
         order by title
        """,
        String.class,
        epoch,
        sequenceNo,
        sequenceNo);
  }

  private void insertMatchModeRecord(String title) {
    jdbcTemplate.update(
        """
        insert into code_review_match_mode_records(
            source_instance, project_id, merge_request_id, merge_request_iid, title, legacy_source_id, synced_at)
        values ('cc', 0, ?, ?, ?, '7701', current_timestamp)
        """,
        MERGE_REQUEST_ID,
        MERGE_REQUEST_IID,
        title);
  }

  private void updateMatchModeRecordTitle(String title) {
    jdbcTemplate.update(
        "update code_review_match_mode_records set title = ? where merge_request_id = ?",
        title,
        MERGE_REQUEST_ID);
  }

  private void truncate() {
    jdbcTemplate.execute(
        """
        truncate read_view_publications, read_view_contexts, read_publication_fact_roots,
            read_publication_scope_generations, read_publications, read_publication_domains,
            read_definition_snapshots, merge_request_commit_fact, merge_request_fact,
            issue_fact_customer_members, issue_fact, integration_test_fact,
            code_review_match_mode_records, fact_change_heads, fact_build_task_roots,
            fact_build_tasks, fact_projection_generations, fact_projection_refresh_tasks,
            source_fact_publication_states, source_fact_dependency_states,
            legacy_platform_formal_import_runs, gitlab_sync_configs restart identity cascade
        """);
  }
}
