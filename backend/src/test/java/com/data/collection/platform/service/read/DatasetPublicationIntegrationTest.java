package com.data.collection.platform.service.read;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.data.collection.platform.common.JsonUtils;
import com.data.collection.platform.config.ReadFallbackProperties;
import com.data.collection.platform.entity.read.DatasetReadSelection;
import com.data.collection.platform.entity.read.DatasetRelationInput;
import com.data.collection.platform.entity.read.PublishedDatasetReadContext;
import com.data.collection.platform.entity.read.ReadPublication;
import com.data.collection.platform.entity.read.ReadView;
import com.data.collection.platform.entity.read.ReadPublicationDomainKey;
import com.data.collection.platform.entity.read.ReadPublicationProducerKind;
import com.data.collection.platform.entity.read.ReadPublicationState;
import com.data.collection.platform.service.FactProjectionExecutionContext;
import com.data.collection.platform.service.FactProjectionPublicationGuardService;
import com.data.collection.platform.service.sync.PostgresIntegrationTestDatabase;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/** 完整迁移链与真实事务验证非事实数据集：旧版稳定、真实类型、重复行、失败及租约回收。 */
class DatasetPublicationIntegrationTest {
  private static final String TABLE = "dataset_source_fixture";
  private static final ReadPublicationDomainKey DOMAIN = new ReadPublicationDomainKey(
      ReadPublicationProducerKind.FORMAL_RECORD, "default", "DATASET", "dataset-fixture");
  private static PostgresIntegrationTestDatabase database;
  private JdbcTemplate jdbc;
  private DataSourceTransactionManager transactions;
  private ReadPublicationRepository repository;
  private ReadPublicationService lifecycle;
  private DatasetPublicationService producer;
  private PublishedReadService reads;
  private ReadFallbackProperties properties;
  private FactProjectionPublicationGuardService guard;
  private VersionedDatasetSql queries;
  private JsonUtils json;

  @BeforeAll
  static void migrate() {
    database = PostgresIntegrationTestDatabase.open("dataset_publication");
    Flyway flyway = Flyway.configure().dataSource(database.dataSource()).locations("classpath:db/migration")
        .cleanDisabled(false).load();
    flyway.clean();
    flyway.migrate();
    new JdbcTemplate(database.dataSource()).execute("""
        create table dataset_source_fixture(id bigint primary key, caption text, amount numeric,
          source_day date, synced_at timestamp not null default current_timestamp);
        create table dataset_unkeyed_fixture(caption text, amount numeric,
          synced_at timestamp not null default current_timestamp);
        create table dataset_second_fixture(id bigint primary key, caption text)
        """);
  }

  @AfterAll
  static void close() { if (database != null) { database.close(); } }

  @BeforeEach
  void reset() {
    org.springframework.security.core.context.SecurityContextHolder.clearContext();
    javax.sql.DataSource dataSource = database.dataSource();
    jdbc = new JdbcTemplate(dataSource);
    jdbc.execute("truncate gitlab_mirror_records, gitlab_sync_configs cascade");
    jdbc.execute("truncate table read_view_contexts, read_publication_domains, read_publications, read_definition_snapshots,"
        + " dataset_source_fixture, dataset_unkeyed_fixture, dataset_second_fixture restart identity cascade");
    transactions = new DataSourceTransactionManager(dataSource);
    properties = new ReadFallbackProperties();
    repository = new ReadPublicationRepository(jdbc);
    lifecycle = proxy(new ReadPublicationService(repository, properties));
    json = new JsonUtils(new ObjectMapper().findAndRegisterModules());
    producer = proxy(new DatasetPublicationService(jdbc, json,
        repository, lifecycle, new ReadDatasetRepository(jdbc)));
    reads = proxy(new PublishedReadService(repository, new ReadViewRepository(jdbc), properties, transactions));
    guard = new FactProjectionPublicationGuardService(jdbc, new FactProjectionExecutionContext(),
        new com.data.collection.platform.service.read.CandidateCacheArtifactStore(jdbc), transactions);
    queries = new VersionedDatasetSql(jdbc);
    jdbc.update("insert into dataset_source_fixture(id, caption, amount, source_day)"
        + " values (1, null, 10, date '2026-10-01'), (2, 'two', 2, date '2026-10-02')");
  }

  @Test
  void mirror_archive_summary_candidates_and_detail_keep_the_original_published_relation() {
    jdbc.update("delete from gitlab_mirror_records");
    jdbc.update("delete from read_dataset_refresh_requests");
    long config = jdbc.queryForObject("insert into gitlab_sync_configs(name, db_name, db_username, db_password)"
        + " values ('archive-fixture', 'fixture', 'fixture', 'fixture') returning id", Long.class);
    jdbc.update("insert into gitlab_mirror_records(config_id, table_name, record_key, row_data)"
        + " values (?, 'issues', 'one', '{\"caption\":\"old\"}'::jsonb)", config);
    Long requestedBefore = jdbc.queryForObject("select requested_revision from read_dataset_refresh_requests"
        + " where producer_kind = 'FORMAL_RECORD'", Long.class);
    new org.springframework.transaction.support.TransactionTemplate(transactions).execute(status -> {
      jdbc.update("delete from gitlab_mirror_records where config_id = ?", config);
      status.setRollbackOnly(); return null;
    });
    assertThat(jdbc.queryForObject("select requested_revision from read_dataset_refresh_requests"
        + " where producer_kind = 'FORMAL_RECORD'", Long.class)).isEqualTo(requestedBefore);
    var request = new DatasetPublicationRequest(DOMAIN, "archive-one",
        List.of(new DatasetRelationInput("archive", "gitlab_mirror_records", List.of("id"), List.of("synced_at"),
            "select * from gitlab_mirror_records", List.of())), new DatasetInputEvidence.FormalCommit());
    ReadPublication captured = producer.prepareCompleteDataset(request);
    ReadPublication first = producer.publishPreparedDataset(captured.id(), request.producerRunId());
    var archiveContext = new PublishedDatasetReadContext("default", DOMAIN.tableKey(), first.epochId(), first.sequenceNo(), first.id(), first.dataAsOf());
    var fixed = new DatasetReadSelection(List.of(archiveContext), null, LocalDate.of(2026, 10, 13));
    var mapper = new com.data.collection.platform.mapper.MirrorTableOverviewMapper(queries).forRead(fixed);
    assertThat(mapper.selectTableNames()).containsExactly("issues");
    assertThat(mapper.countDetails("issues", "totalRecords")).isEqualTo(1L);
    assertThat(mapper.selectSummaryRows(null)).singleElement().satisfies(row -> assertThat(row.get("totalRecords")).isEqualTo(1L));
    jdbc.update("update gitlab_mirror_records set row_data = '{\"caption\":\"new\"}'::jsonb where config_id = ?", config);
    jdbc.update("insert into gitlab_mirror_records(config_id, table_name, record_key, row_data) values (?, 'users', 'two', '{}'::jsonb)", config);
    assertThat(jdbc.queryForObject("select requested_revision from read_dataset_refresh_requests"
        + " where producer_kind = 'FORMAL_RECORD'", Long.class)).isEqualTo(requestedBefore + 2);
    var secondRequest = new DatasetPublicationRequest(DOMAIN, "archive-two", request.relations(), request.evidence());
    ReadPublication next = producer.prepareCompleteDataset(secondRequest);
    producer.publishPreparedDataset(next.id(), secondRequest.producerRunId());
    assertThat(mapper.selectTableNames()).containsExactly("issues");
    assertThat(mapper.countDetails("issues", "totalRecords")).isEqualTo(1L);
    assertThat(mapper.selectDetails("issues", "totalRecords", "id", "asc", 20, 0)).singleElement()
        .satisfies(row -> assertThat(row.get("rowData").toString()).contains("old").doesNotContain("new"));
  }

  @Test
  void view_preparation_recovers_a_real_domain_update_conflict_without_leaking_a_lease() {
    ReadPublication baseline = publish("preparation-baseline");
    jdbc.update("update dataset_source_fixture set caption = 'next complete' where id = 1");
    ReadPublication next = producer.prepareCompleteDataset(request("preparation-next"));
    var pages = org.mockito.Mockito.mock(ReadPageCatalog.class);
    var first = new java.util.concurrent.atomic.AtomicBoolean(true);
    var attempts = new java.util.concurrent.atomic.AtomicInteger();
    org.mockito.Mockito.when(pages.domains(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyMap(),
        org.mockito.ArgumentMatchers.any())).thenAnswer(call -> {
          attempts.incrementAndGet();
          jdbc.queryForObject("select count(*) from read_publication_domains", Integer.class);
          if (first.getAndSet(false)) {
            var independentSource = database.dataSource();
            var independentJdbc = new JdbcTemplate(independentSource);
            var independentRepository = new ReadPublicationRepository(independentJdbc);
            var independentLifecycle = new ReadPublicationService(independentRepository, properties);
            var independentProducer = new DatasetPublicationService(independentJdbc, json, independentRepository,
                independentLifecycle, new ReadDatasetRepository(independentJdbc));
            new org.springframework.transaction.support.TransactionTemplate(new DataSourceTransactionManager(independentSource))
                .execute(status -> independentProducer.publishPreparedDataset(next.id(), "preparation-next"));
          }
          return Set.of(DOMAIN);
        });
    var preparation = new ReadViewPreparationService(pages, reads, () -> LocalDate.of(2026, 10, 13), transactions);
    ReadView view = preparation.prepare("user", "session", "fixture-page", "scope", Map.of(), UUID.randomUUID());
    assertThat(view.requireDomain(DOMAIN).publicationId()).isEqualTo(next.id());
    assertThat(jdbc.queryForObject("select count(*) from read_view_contexts", Integer.class)).isEqualTo(1);
    assertThat(jdbc.queryForObject("select count(*) from read_view_publications", Integer.class)).isEqualTo(1);
    assertThat(attempts.get()).as("真实 RR/FK 冲突必须结束旧事务并重建读取组合").isEqualTo(2);
  }

  @Test
  void published_dataset_idempotency_still_checks_the_producer_identity() {
    ReadPublication baseline = publish("owned-dataset");
    assertThat(producer.publishPreparedDataset(baseline.id(), "owned-dataset").id()).isEqualTo(baseline.id());
    assertThatThrownBy(() -> producer.publishPreparedDataset(baseline.id(), "another-producer"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(repository.findCurrentPublication(baseline.domainId()).orElseThrow().id()).isEqualTo(baseline.id());
  }

  @Test
  void prepared_dataset_rechecks_state_after_another_publisher_wins() {
    var captured = producer.prepareCompleteDataset(request("publication-race"));
    var observing = org.mockito.Mockito.spy(repository);
    var first = new java.util.concurrent.atomic.AtomicBoolean(true);
    org.mockito.Mockito.doAnswer(call -> {
      var observed = repository.findPublication(captured.id());
      if (first.getAndSet(false)) { producer.publishPreparedDataset(captured.id(), "publication-race"); }
      return observed;
    }).when(observing).findPublication(captured.id());
    var competitor = proxy(new DatasetPublicationService(jdbc, json, observing, lifecycle, new ReadDatasetRepository(jdbc)));
    ReadPublication result = competitor.publishPreparedDataset(captured.id(), "publication-race");
    assertThat(result.state()).isEqualTo(ReadPublicationState.PUBLISHED);
    assertThat(repository.findCurrentPublication(result.domainId()).orElseThrow().id()).isEqualTo(result.id());
    assertThat(rows(result)).hasSize(2);
  }

  @Test
  void bi_review_old_records_and_problem_formula_stay_on_complete_dataset_after_new_publication() {
    resetFormalInputs();
    long id = jdbc.queryForObject("insert into review_records(title, project_name, review_type, review_scale_pages, review_date)"
        + " values ('requirements', 'CC2026R4', '需求说明书评审', 20, date '2026-10-01') returning id", Long.class);
    jdbc.update("insert into review_problem_items(review_record_id, problem_status, problem_category, review_category, workload_hours)"
        + " values (?, '已修改', '完整性', '独立评审', 2)", id);
    var worker = formalWorker();
    worker.publishOneRequestedDataset();
    var domain = new ReadPublicationDomainKey(ReadPublicationProducerKind.FORMAL_RECORD, "default", "DATASET", "formal-records");
    ReadPublication old = repository.findCurrentPublication(repository.findDomain(domain).orElseThrow().id()).orElseThrow();
    var scope = new com.data.collection.platform.bi.domain.source.BiProductVersionScope(1, 9, "CC2026R4", "CC2026R4", 0, List.of());
    var adapter = new com.data.collection.platform.bi.infrastructure.BiPlatformReviewSourceAdapter(jdbc, null,
        new com.data.collection.platform.bi.domain.BiProductVersionMatcher()).forRead(selection(old));
    var stage = com.data.collection.platform.bi.domain.port.BiReviewSourcePort.ReviewStage.REQUIREMENTS;
    var before = guard.withinRead(selection(old), () -> adapter.load(scope, stage));
    assertThat(before.records()).hasSize(1);
    assertThat(before.records().getFirst().effectiveProblemCount()).isOne();
    assertThat(before.records().getFirst().integrityCount()).isOne();
    assertThat(before.records().getFirst().workloadHours()).isEqualByComparingTo("2");
    jdbc.update("update review_records set review_scale_pages = 40 where id = ?", id);
    jdbc.update("update review_problem_items set workload_hours = 5, problem_category = '功能性' where review_record_id = ?", id);
    worker.publishOneRequestedDataset();
    var retained = guard.withinRead(selection(old), () -> adapter.load(scope, stage));
    assertThat(retained).isEqualTo(before);
    ReadPublication current = repository.findCurrentPublication(old.domainId()).orElseThrow();
    var newAdapter = new com.data.collection.platform.bi.infrastructure.BiPlatformReviewSourceAdapter(jdbc, null,
        new com.data.collection.platform.bi.domain.BiProductVersionMatcher()).forRead(selection(current));
    var updated = guard.withinRead(selection(current), () -> newAdapter.load(scope, stage));
    assertThat(updated.records().getFirst().reviewedPages()).isEqualTo(40L);
    assertThat(updated.records().getFirst().functionalityCount()).isOne();
    assertThat(updated.records().getFirst().workloadHours()).isEqualByComparingTo("5");
  }

  @Test
  void business_day_change_requests_new_frame_without_escalating_a_healthy_old_version() {
    resetFormalInputs();
    formalWorker().publishOneRequestedDataset();
    var caller = readerRequest("admin", "ADMIN");
    var view = application().acquire(caller, "review-data-records", java.util.Map.of(), UUID.randomUUID());
    jdbc.update("update read_publication_domains set unsettled_since = current_timestamp - interval '2 days'");
    var freshness = new ReadViewFreshnessService(jdbc,
        org.mockito.Mockito.mock(com.data.collection.platform.service.sync.SyncFactPublicationStateService.class),
        new com.data.collection.platform.service.PlatformPermissionService(jdbc), properties, () -> LocalDate.of(2026, 10, 14));
    var metadata = freshness.metadata(view, com.data.collection.platform.security.AuthSessionSupport.currentUser(caller));
    assertThat(metadata.sourceState()).isEqualTo(com.data.collection.platform.entity.read.ReadViewMetadata.SourceState.NEWER_AVAILABLE);
    assertThat(metadata.businessDate()).isEqualTo(LocalDate.of(2026, 10, 13));
    assertThat(metadata.escalated()).isFalse();
    assertThat(jdbc.queryForObject("select count(*) from read_publication_domains where unsettled_since is not null", Long.class)).isZero();
  }

  @Test
  void review_directory_candidates_read_the_bound_mirror_version_after_update_and_failure() {
    String table = com.data.collection.platform.service.GitlabSourceInstanceSupport.buildMirrorTableName("users");
    jdbc.execute("create table " + table + "(id bigint primary key, name text, mirror_deleted boolean default false,"
        + " last_activity_on timestamp, created_at timestamp, updated_at timestamp)");
    try {
      jdbc.update("insert into " + table + "(id,name,created_at) values (1,'旧完整成员',current_timestamp)");
      long config = jdbc.queryForObject("insert into gitlab_sync_configs(db_name,db_username,db_password) values ('directory','fixture','fixture') returning id", Long.class);
      long state = jdbc.queryForObject("insert into sync_run_table_states(config_id,source_instance,source_table,mirror_table,primary_key_columns,last_full_verified_at)"
          + " values (?,'default','users',?,'id',current_timestamp) returning id", Long.class, config, table);
      long run = mirrorRun(config, "SUCCESS");
      mirrorTask(config, run, state, "SUCCESS");
      var worker = new MirrorDatasetPublicationWorker(jdbc, producer, json, new ReadProducerRetryService(jdbc));
      worker.publishOneCompleteTable();
      var key = new ReadPublicationDomainKey(ReadPublicationProducerKind.MIRROR_TABLE,"default","DATASET",table);
      ReadPublication old = repository.findCurrentPublication(repository.findDomain(key).orElseThrow().id()).orElseThrow();
      var fixed = new com.data.collection.platform.service.ReviewDataMirrorOptionRepository(jdbc).forRead(selection(old));
      assertThat(guard.withinRead(selection(old), fixed::loadUserNames)).containsExactly("旧完整成员");
      // 质量看板可以跳转评审记录，父闭包必须保留该记录页依赖的目录点。
      resetFormalInputs();
      formalWorker().publishOneRequestedDataset();
      for (var type : com.data.collection.platform.entity.read.PublicationFactTable.values()) {
        com.data.collection.platform.service.FactRevisionFixture.adoptSeededFacts(jdbc,type);
      }
      var permissions = new com.data.collection.platform.service.PlatformPermissionService(jdbc);
      com.data.collection.platform.service.BusinessDateProvider dates = () -> LocalDate.of(2026,10,13);
      var pages = new ReadPageCatalog(jdbc,new com.data.collection.platform.service.PagePermissionKeyResolver(),
          org.mockito.Mockito.mock(com.data.collection.platform.bi.application.BiPagePermissionResolver.class));
      var freshness = new ReadViewFreshnessService(jdbc,org.mockito.Mockito.mock(com.data.collection.platform.service.sync.SyncFactPublicationStateService.class),permissions,properties,dates);
      var controlReader = new com.data.collection.platform.service.labelgroup.LabelValuePublishedReader(reads,proxy(new ReadViewPreparationService(pages,reads,dates,transactions)),freshness,transactions);
      var controls = new com.data.collection.platform.service.labelgroup.LabelValueQueryService(
          new com.data.collection.platform.service.labelgroup.LabelDimensionCatalogService(),
          new com.data.collection.platform.service.ReviewDataMirrorOptionRepository(jdbc),
          org.mockito.Mockito.mock(com.data.collection.platform.service.ReviewDataRecordService.class),
          org.mockito.Mockito.mock(com.data.collection.platform.service.SystemTestIssueSearchService.class),
          org.mockito.Mockito.mock(com.data.collection.platform.service.CustomerIssueRecordService.class),controlReader);
      assertThat(pages.domains("quality-rd",Map.of(),LocalDate.of(2026,10,13)))
          .containsAll(pages.domains("review-data-records",Map.of(),LocalDate.of(2026,10,13)));
      var app = application();
      var caller = readerRequest("quality-parent","ADMIN");
      ReadView parent = app.acquire(caller,"quality-rd",Map.of(),UUID.randomUUID());
      jdbc.update("insert into issue_fact(source_system,source_instance,project_id,issue_id,issue_iid,title)"
          + " values ('GITLAB','beta',9,201,201,'later-source')");
      com.data.collection.platform.service.FactRevisionFixture.adoptSeededFacts(jdbc,
          com.data.collection.platform.entity.read.PublicationFactTable.ISSUE);
      ReadView child = app.fork(caller,parent.viewId(),"system-test-issues",Map.of("projectId","9"),UUID.randomUUID());
      assertThat(child.domains()).isEqualTo(parent.domains());
      assertThat(child.domains().keySet()).noneMatch(domain -> domain.sourceInstance().equals("beta"));
      jdbc.update("update " + table + " set name = '分页半成品成员' where id = 1");
      long failed = mirrorRun(config, "FAILED");
      mirrorTask(config, failed, state, "FAILED");
      worker.publishOneCompleteTable();
      assertThat(guard.withinRead(selection(old), fixed::loadUserNames)).containsExactly("旧完整成员");
      var outer = new org.springframework.transaction.support.TransactionTemplate(transactions);
      outer.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
      outer.executeWithoutResult(status -> {
        jdbc.queryForObject("select count(*) from read_view_contexts",Long.class);
        assertThat(controls.listValues("person",null,null,null,1,20).items())
            .extracting(com.data.collection.platform.entity.labelgroup.LabelValueResponse::value).containsExactly("旧完整成员");
      });
      assertThat(jdbc.queryForObject("select count(*) from read_view_contexts where subject = 'label-group-control'",Long.class)).isZero();
      long success = mirrorRun(config, "SUCCESS");
      mirrorTask(config, success, state, "SUCCESS");
      worker.publishOneCompleteTable();
      ReadPublication current = repository.findCurrentPublication(old.domainId()).orElseThrow();
      var updated = new com.data.collection.platform.service.ReviewDataMirrorOptionRepository(jdbc).forRead(selection(current));
      assertThat(guard.withinRead(selection(current), updated::loadUserNames)).containsExactly("分页半成品成员");
      assertThat(guard.withinRead(selection(old), fixed::loadUserNames)).containsExactly("旧完整成员");
    } finally {
      jdbc.execute("drop table " + table);
    }
  }

  @Test
  void native_dataset_definition_change_without_record_delta_republishes_and_keeps_old_definition() {
    resetFormalInputs();
    var worker = formalWorker();
    worker.publishOneRequestedDataset();
    var key = new ReadPublicationDomainKey(ReadPublicationProducerKind.FORMAL_RECORD,"default","DATASET","formal-records");
    ReadPublication old = repository.findCurrentPublication(repository.findDomain(key).orElseThrow().id()).orElseThrow();
    String oldDefinition = jdbc.queryForObject("select payload::text from read_definition_snapshots where id = ?", String.class, old.definitionSnapshotId());
    jdbc.update("insert into module_dictionary(standard_module_name,alias_name) values ('native-definition-update','native-definition-alias')");
    var definitions = new ReadDefinitionRefreshWorker(jdbc, repository,
        org.mockito.Mockito.mock(com.data.collection.platform.service.GitlabConfigService.class),
        org.mockito.Mockito.mock(com.data.collection.platform.service.sync.SyncRunSubmissionService.class));
    var transaction = new org.springframework.transaction.support.TransactionTemplate(transactions);
    transaction.executeWithoutResult(status -> definitions.requestChangedDefinitions());
    transaction.executeWithoutResult(status -> definitions.requestChangedDefinitions());
    worker.publishOneRequestedDataset();
    ReadPublication current = repository.findCurrentPublication(old.domainId()).orElseThrow();
    assertThat(current.id()).isNotEqualTo(old.id());
    assertThat(jdbc.queryForObject("select payload::text from read_definition_snapshots where id = ?", String.class, old.definitionSnapshotId())).isEqualTo(oldDefinition);
    assertThat(jdbc.queryForObject("select payload::text from read_definition_snapshots where id = ?", String.class, current.definitionSnapshotId())).contains("native-definition-update");
  }

  @Test
  void failed_dataset_over_twenty_four_hours_alerts_only_admin_and_verified_recovery_clears_age() {
    resetFormalInputs();
    var worker = formalWorker();
    worker.publishOneRequestedDataset();
    var normal = readerRequest("normal", "NORMAL_USER");
    var application = application();
    var normalView = application.acquire(normal, "review-data-records", java.util.Map.of(), UUID.randomUUID());
    jdbc.update("update read_dataset_refresh_requests set requested_revision = requested_revision + 1, last_error = 'private failure'"
        + " where producer_kind = 'FORMAL_RECORD'");
    jdbc.update("update read_publication_domains set unsettled_since = current_timestamp - interval '25 hours'");
    assertThat(application.metadata(normal, normalView).escalated()).isFalse();
    assertThat(application.metadata(normal, normalView).sourceStateDetail()).isNull();
    var admin = readerRequest("admin", "ADMIN");
    var adminView = application.acquire(admin, "review-data-records", java.util.Map.of(), UUID.randomUUID());
    assertThat(application.metadata(admin, adminView).escalated()).isTrue();
    assertThat(application.metadata(admin, adminView).sourceStateDetail()).contains("private failure");
    worker.publishOneRequestedDataset();
    assertThat(application.metadata(admin, adminView).escalated()).isFalse();
    assertThat(application.metadata(admin, adminView).sourceState()).isEqualTo(com.data.collection.platform.entity.read.ReadViewMetadata.SourceState.CURRENT);
  }

  @Test
  void mirror_table_publication_rejects_failed_later_pages_and_keeps_complete_old_rows() {
    long config = jdbc.queryForObject("insert into gitlab_sync_configs(db_name, db_username, db_password)"
        + " values ('mirror-fixture', 'fixture', 'fixture') returning id", Long.class);
    try {
      long run = mirrorRun(config, "SUCCESS");
      long state = jdbc.queryForObject("insert into sync_run_table_states(config_id, source_instance, source_table,"
          + " mirror_table, primary_key_columns, last_full_verified_at) values (?, 'default', 'fixture', ?, 'id',"
          + " current_timestamp) returning id", Long.class, config, TABLE);
      mirrorTask(config, run, state, "SUCCESS");
      MirrorDatasetPublicationWorker worker = new MirrorDatasetPublicationWorker(jdbc, producer, json, new ReadProducerRetryService(jdbc));
      worker.publishOneCompleteTable();
      var domain = new ReadPublicationDomainKey(ReadPublicationProducerKind.MIRROR_TABLE, "default", "DATASET", TABLE);
      ReadPublication old = repository.findCurrentPublication(repository.findDomain(domain).orElseThrow().id()).orElseThrow();
      assertThat(rows(old)).extracting(Row::amount).containsExactly(new BigDecimal("2"), new BigDecimal("10"));
      var app = application();
      var caller = readerRequest("mirror-admin", "ADMIN");
      ReadView oldView = app.acquire(caller, "database-browser", Map.of("tableName", TABLE), UUID.randomUUID());
      long partial = mirrorRun(config, "FAILED");
      mirrorTask(config, partial, state, "FAILED");
      jdbc.update("update dataset_source_fixture set amount = 99 where id = 1");
      // 运行开始早于旧读点提交、稍后失败，仍应披露更新问题并保留旧点。
      jdbc.update("update sync_runs set created_at = ? where id = ?", java.sql.Timestamp.from(old.publishedAt().minusSeconds(60)), partial);
      assertThat(app.metadata(caller, oldView).sourceState())
          .isEqualTo(com.data.collection.platform.entity.read.ReadViewMetadata.SourceState.ATTENTION);
      worker.publishOneCompleteTable();
      assertThat(repository.findCurrentPublication(old.domainId()).orElseThrow().id()).isEqualTo(old.id());
      assertThat(rows(old).getLast().amount()).isEqualTo(new BigDecimal("10"));
      assertThatThrownBy(() -> producer.prepareCompleteDataset(new DatasetPublicationRequest(domain, "forged-old-proof",
          request("ignored").relations(), new DatasetInputEvidence.MirrorTableRun(run, state))))
          .isInstanceOf(IllegalStateException.class);
      jdbc.update("update sync_runs set status = 'SUCCESS' where id = ?", partial);
      jdbc.update("update sync_run_table_tasks set status = 'SUCCESS', finished_at = current_timestamp where run_id = ?", partial);
      assertThat(app.metadata(caller, oldView).sourceState())
          .as("镜像成功但读发布尚未完成，不能披露为 CURRENT")
          .isEqualTo(com.data.collection.platform.entity.read.ReadViewMetadata.SourceState.UPDATING);
      worker.publishOneCompleteTable();
      ReadPublication current = repository.findCurrentPublication(old.domainId()).orElseThrow();
      ReadView newView = app.acquire(caller, "database-browser", Map.of("tableName", TABLE), UUID.randomUUID());
      assertThat(app.metadata(caller, newView).sourceState())
          .isEqualTo(com.data.collection.platform.entity.read.ReadViewMetadata.SourceState.CURRENT);
      assertThat(current.id()).isNotEqualTo(old.id());
      assertThat(rows(current).getLast().amount()).isEqualTo(new BigDecimal("99"));
      assertThat(rows(old).getLast().amount()).isEqualTo(new BigDecimal("10"));
      long independent = mirrorRun(config, "FAILED");
      mirrorTask(config, independent, state, "SUCCESS");
      long otherState = jdbc.queryForObject("insert into sync_run_table_states(config_id, source_instance, source_table,"
          + " mirror_table, primary_key_columns) values (?, 'default', 'other', 'dataset_other_fixture', 'id') returning id",
          Long.class, config);
      jdbc.update("insert into sync_run_table_tasks(config_id, run_id, state_id, source_instance, source_table, mirror_table,"
          + " task_type, status, row_strategy, finished_at) values (?, ?, ?, 'default', 'other', 'dataset_other_fixture',"
          + " 'SCAN', 'FAILED', 'INCREMENTAL', current_timestamp)", config, independent, otherState);
      jdbc.update("update dataset_source_fixture set amount = 75 where id = 1");
      worker.publishOneCompleteTable();
      ReadPublication independentlyComplete = repository.findCurrentPublication(old.domainId()).orElseThrow();
      assertThat(independentlyComplete.id()).isNotEqualTo(current.id());
      assertThat(rows(independentlyComplete).getLast().amount()).isEqualTo(new BigDecimal("75"));
      assertThat(rows(current).getLast().amount()).isEqualTo(new BigDecimal("99"));
      assertThat(repository.findDomain(new ReadPublicationDomainKey(ReadPublicationProducerKind.MIRROR_TABLE,
          "default", "DATASET", "dataset_other_fixture"))).isEmpty();
    } finally {
      jdbc.update("delete from gitlab_sync_configs where id = ?", config);
    }
  }

  private long mirrorRun(long config, String status) {
    return jdbc.queryForObject("insert into sync_runs(run_id, config_id, source_instance, run_type, trigger_type, status,"
        + " exclusive_scope, started_at) values (?, ?, 'default', 'INCREMENTAL_SYNC', 'MANUAL', ?, 'default',"
        + " current_timestamp) returning id", Long.class, UUID.randomUUID().toString(), config, status);
  }

  private void mirrorTask(long config, long run, long state, String status) {
    jdbc.update("insert into sync_run_table_tasks(config_id, run_id, state_id, source_instance, source_table, mirror_table,"
        + " task_type, status, row_strategy, finished_at) values (?, ?, ?, 'default', 'fixture', ?, 'SCAN', ?,"
        + " 'INCREMENTAL', current_timestamp)", config, run, state, TABLE, status);
  }

  @Test
  void versioned_dataset_cannot_bypass_frozen_relation_by_schema_qualification() {
    var selected = selection(publish("schema-guard"));
    assertThatThrownBy(() -> queries.bind(selected, "select * from public.dataset_source_fixture", List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> queries.bind(selected, "select * from \"public\".\"dataset_source_fixture\"", List.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void schema_changes_start_new_epoch_and_old_view_keeps_original_columns_and_numeric_semantics() {
    ReadPublication old = publish("schema-before");
    List<Row> before = rows(old);
    try {
      jdbc.execute("alter table dataset_source_fixture drop column caption");
      jdbc.execute("alter table dataset_source_fixture alter column amount type text using amount::text");
      ReadPublication current = publish("schema-after");
      assertThat(current.epochId()).isNotEqualTo(old.epochId());
      assertThat(rows(old)).isEqualTo(before);
      DatasetReadSelection selected = selection(current);
      assertThat(guard.withinRead(selected, () -> queries.queryForList(selected,
          "select amount from dataset_source_fixture order by amount", String.class))).containsExactly("10", "2");
    } finally {
      jdbc.execute("alter table dataset_source_fixture alter column amount type numeric using amount::numeric");
      jdbc.execute("alter table dataset_source_fixture add column caption text");
    }
  }

  @Test
  void lease_maintenance_releases_only_expired_views_and_keeps_active_read_identity() {
    ReadPublication published = publish("lease-maintenance");
    var expired = reads.acquireView(viewRequest());
    var active = reads.acquireView(viewRequest());
    jdbc.update("update read_view_contexts set created_at = current_timestamp - interval '1 minute',"
        + " expires_at = current_timestamp - interval '1 second' where id = ?", expired.viewId());
    ReadMaintenanceService maintenance = proxy(new ReadMaintenanceService(jdbc, lifecycle, properties));
    assertThat(maintenance.expireViews()).isEqualTo(1);
    assertThat(new ReadViewRepository(jdbc).findBindings(expired.viewId())).isEmpty();
    assertThat(reads.loadOwnedView(active.viewId(), "tester", "session").viewId()).isEqualTo(active.viewId());
    assertThat(rows(published)).hasSize(2);
  }

  @Test
  void domain_maintenance_preserves_current_reused_rows_after_expired_old_view_is_released() {
    properties.setRetirementGraceMinutes(0);
    ReadPublication old = publish("maintenance-old");
    var expired = reads.acquireView(viewRequest());
    jdbc.update("update dataset_source_fixture set amount = 15 where id = 2");
    ReadPublication current = publish("maintenance-current");
    jdbc.update("update read_view_contexts set created_at = current_timestamp - interval '1 minute',"
        + " expires_at = current_timestamp - interval '1 second' where id = ?", expired.viewId());
    ReadMaintenanceService maintenance = proxy(new ReadMaintenanceService(jdbc, lifecycle, properties));
    maintenance.expireViews();
    maintenance.collectOneDomain();
    assertThat(rows(current)).extracting(Row::amount).containsExactly(new BigDecimal("10"), new BigDecimal("15"));
    assertThat(repository.findDomain(current.domainId()).orElseThrow().currentPublicationId()).isEqualTo(current.id());
    assertThat(repository.findPublication(old.id()).orElseThrow().state()).isEqualTo(ReadPublicationState.RETIRING);
  }

  @Test
  void committed_formal_changes_publish_a_complete_dataset_and_old_page_stays_readable() {
    resetFormalInputs();
    jdbc.update("insert into review_records(title) values ('before')");
    DatasetRefreshWorker worker = formalWorker();
    worker.publishOneRequestedDataset();
    ReadViewApplicationService application = application();
    var request = readerRequest("normal", "NORMAL_USER");
    var view = application.acquire(request, "review-data-records", java.util.Map.of(), UUID.randomUUID());
    request.addHeader("X-Read-Context-Id", view.viewId().toString());
    assertThat(readTitles(application, request)).containsExactly("before");
    jdbc.update("update review_records set title = 'after'");
    jdbc.update("insert into review_records(title) values ('new')");
    worker.publishOneRequestedDataset();
    assertThat(readTitles(application, request)).containsExactly("before");
    var fresh = readerRequest("normal", "NORMAL_USER");
    assertThat(readTitles(application, fresh)).containsExactly("after", "new");
  }

  @Test
  void rolled_back_input_does_not_queue_or_publish_a_revision() {
    resetFormalInputs();
    formalWorker().publishOneRequestedDataset();
    long requested = jdbc.queryForObject("select requested_revision from read_dataset_refresh_requests where producer_kind = 'FORMAL_RECORD'", Long.class);
    new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(status -> {
      jdbc.update("insert into review_records(title) values ('rolled-back')");
      status.setRollbackOnly();
    });
    assertThat(jdbc.queryForObject("select requested_revision from read_dataset_refresh_requests where producer_kind = 'FORMAL_RECORD'", Long.class))
        .isEqualTo(requested);
    assertThat(jdbc.queryForObject("select count(*) from review_records where title = 'rolled-back'", Long.class)).isZero();
  }

  @Test
  void expired_request_lease_cannot_publish_its_prepared_dataset() {
    resetFormalInputs();
    var owner = ReadPublicationProducerKind.FORMAL_RECORD;
    String run = "lost-lease";
    ReadPublication prepared = producer.prepareCompleteDataset(new com.data.collection.platform.service.DatasetProducerCatalog(
        new com.data.collection.platform.service.CodeReviewMatchModeConfigService(jdbc)).request(owner, run));
    UUID lease = UUID.randomUUID();
    jdbc.update("update read_dataset_refresh_requests set lease_owner = ?, lease_until = current_timestamp - interval '1 second'"
        + " where producer_kind = 'FORMAL_RECORD'", lease);
    assertThatThrownBy(() -> producer.publishRequestedDataset(prepared.id(), run, owner.name(), lease)).isInstanceOf(IllegalStateException.class);
    assertThat(repository.findDomain(prepared.domainId()).orElseThrow().currentPublicationId()).isNull();
    assertThat(repository.findPublication(prepared.id()).orElseThrow().state()).isEqualTo(ReadPublicationState.PROJECTING);
  }

  @Test
  void read_identity_is_session_owned_and_current_permission_revocation_is_immediate() {
    resetFormalInputs();
    jdbc.update("insert into review_records(title) values ('protected')");
    formalWorker().publishOneRequestedDataset();
    ReadViewApplicationService application = application();
    var owner = readerRequest("normal", "NORMAL_USER");
    var view = application.acquire(owner, "review-data-records", java.util.Map.of(), UUID.randomUUID());
    owner.addHeader("X-Read-Context-Id", view.viewId().toString());
    var otherSession = readerRequest("normal", "NORMAL_USER");
    otherSession.addHeader("X-Read-Context-Id", view.viewId().toString());
    assertThatThrownBy(() -> readTitles(application, otherSession)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    assertThat(readTitles(application, owner)).containsExactly("protected");
    jdbc.update("delete from platform_role_permissions where role_code = 'NORMAL_USER' and permission_code = ?",
        com.data.collection.platform.security.PlatformPermissionCodes.REVIEW_DATA_VIEW);
    assertThatThrownBy(() -> readTitles(application, owner)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
  }

  @Test
  void frozen_read_mode_and_administrator_disclosure_do_not_leak_into_shared_business_data() {
    resetFormalInputs();
    formalWorker().publishOneRequestedDataset();
    var application = application();
    var normal = readerRequest("normal", "NORMAL_USER");
    var view = application.acquire(normal, "review-data-records", java.util.Map.of(), UUID.randomUUID());
    normal.addHeader("X-Read-Context-Id", view.viewId().toString());
    jdbc.update("update code_review_match_mode_db_settings set enabled = true, review_data_read_mode = 'compatibility' where id = 1");
    var oldSelection = (DatasetReadSelection) new ReadViewFreshnessService(jdbc,
        org.mockito.Mockito.mock(com.data.collection.platform.service.sync.SyncFactPublicationStateService.class),
        new com.data.collection.platform.service.PlatformPermissionService(jdbc), properties, () -> LocalDate.of(2026, 10, 13)).selection(view);
    assertThat(new com.data.collection.platform.service.CodeReviewMatchModeConfigService(jdbc).compatibilityReadEnabled(oldSelection, true)).isFalse();
    jdbc.update("update read_dataset_refresh_requests set last_error = 'private source failure' where producer_kind = 'FORMAL_RECORD'");
    assertThat(application.metadata(normal, view).sourceState()).isEqualTo(com.data.collection.platform.entity.read.ReadViewMetadata.SourceState.ATTENTION);
    assertThat(application.metadata(normal, view).sourceStateDetail()).isNull();
    var admin = readerRequest("admin", "ADMIN");
    assertThat(application.metadata(admin, view).sourceStateDetail()).contains("private source failure");
  }

  private void resetFormalInputs() {
    jdbc.execute("truncate review_records, review_data_match_mode_edit_links, review_data_match_mode_problem_edit_links,"
        + " code_review_external_metrics, read_dataset_refresh_requests restart identity cascade");
    jdbc.update("update code_review_match_mode_db_settings set enabled = false, code_review_read_mode = 'formal', review_data_read_mode = 'formal' where id = 1");
    jdbc.update("insert into platform_role_permissions(role_code, permission_code) values ('NORMAL_USER', ?) on conflict do nothing",
        com.data.collection.platform.security.PlatformPermissionCodes.REVIEW_DATA_VIEW);
  }

  private DatasetRefreshWorker formalWorker() {
    return new DatasetRefreshWorker(jdbc, new com.data.collection.platform.service.DatasetProducerCatalog(
        new com.data.collection.platform.service.CodeReviewMatchModeConfigService(jdbc)), producer);
  }

  private ReadViewApplicationService application() {
    var permissions = new com.data.collection.platform.service.PlatformPermissionService(jdbc);
    com.data.collection.platform.service.BusinessDateProvider dates = () -> LocalDate.of(2026, 10, 13);
    var pages = new ReadPageCatalog(jdbc, new com.data.collection.platform.service.PagePermissionKeyResolver(),
        org.mockito.Mockito.mock(com.data.collection.platform.bi.application.BiPagePermissionResolver.class));
    var freshness = new ReadViewFreshnessService(jdbc,
        org.mockito.Mockito.mock(com.data.collection.platform.service.sync.SyncFactPublicationStateService.class), permissions, properties, dates);
    return new ReadViewApplicationService(pages, reads, new FactReadSelectionService(jdbc, repository, lifecycle, dates),
        permissions, dates, json, freshness, proxy(new ReadViewPreparationService(pages, reads, dates, transactions)));
  }

  private org.springframework.mock.web.MockHttpServletRequest readerRequest(String subject, String role) {
    var request = new org.springframework.mock.web.MockHttpServletRequest();
    request.getSession().setAttribute(com.data.collection.platform.security.AuthSessionSupport.SESSION_USER_KEY,
        new com.data.collection.platform.entity.AuthUserResponse(subject, subject, Set.of(role), List.of(role), Set.of(), true));
    return request;
  }

  private List<String> readTitles(ReadViewApplicationService application, jakarta.servlet.http.HttpServletRequest request) {
    return application.read(request, "review-data-records", java.util.Map.of(), selection -> queries.queryForList((DatasetReadSelection) selection,
        "select title from review_records order by id", String.class)).data();
  }

  @Test
  void capture_is_private_until_published_and_queries_preserve_null_numeric_and_date_types() {
    ReadPublication candidate = producer.prepareCompleteDataset(request("first"));
    assertThat(candidate.state()).isEqualTo(ReadPublicationState.PROJECTING);
    assertThat(repository.findCurrentPublication(candidate.domainId())).isEmpty();
    assertThatThrownBy(() -> reads.acquireView(viewRequest())).isInstanceOf(ReadViewException.class);

    ReadPublication published = producer.publishPreparedDataset(candidate.id(), "first");
    List<Row> rows = rows(published);
    assertThat(rows).extracting(Row::id).containsExactly(2L, 1L);
    assertThat(rows.getLast().caption()).isNull();
    assertThat(rows.getLast().amount()).isEqualByComparingTo("10");
    assertThat(rows.getLast().day()).isEqualTo(LocalDate.of(2026, 10, 1));
  }

  @Test
  void incremental_update_addition_and_deletion_keep_old_view_complete_and_reuse_unchanged_rows() {
    ReadPublication old = publish("first");
    jdbc.update("insert into dataset_source_fixture(id, caption, amount, source_day) values (4, 'retained', 4, date '2026-10-04')");
    old = publish("add-retained");
    ReadPublication oldView = old;
    jdbc.update("update dataset_source_fixture set caption = 'changed' where id = 1");
    jdbc.update("delete from dataset_source_fixture where id = 2");
    jdbc.update("insert into dataset_source_fixture(id, caption, amount, source_day) values (3, 'new', 3, date '2026-10-03')");
    ReadPublication candidate = producer.prepareCompleteDataset(request("second"));
    assertThat(rows(oldView)).extracting(Row::id).containsExactly(2L, 4L, 1L);
    assertThat(jdbc.queryForObject("select count(*) from read_dataset_rows where created_by_publication_id = ?",
        Long.class, candidate.id())).isEqualTo(2);

    ReadPublication current = producer.publishPreparedDataset(candidate.id(), "second");
    assertThat(rows(current)).extracting(Row::id).containsExactly(3L, 4L, 1L);
    assertThat(rows(current).getLast().caption()).isEqualTo("changed");
    assertThat(rows(oldView)).extracting(Row::id).containsExactly(2L, 4L, 1L);
    assertThat(rows(oldView).getLast().caption()).isNull();
  }

  @Test
  void failed_or_aborted_capture_never_moves_pointer_or_deletes_old_rows() {
    ReadPublication old = publish("first");
    jdbc.update("update dataset_source_fixture set caption = 'unpublished' where id = 1");
    ReadPublication candidate = producer.prepareCompleteDataset(request("failed"));
    assertThat(lifecycle.abort(candidate.id(), "capture interrupted")).isTrue();

    assertThat(repository.findCurrentPublication(old.domainId()).orElseThrow().id()).isEqualTo(old.id());
    assertThat(rows(old).getLast().caption()).isNull();
    assertThatThrownBy(() -> jdbc.update("update read_dataset_tables set schema_payload = '[]'::jsonb where publication_id = ?", old.id()))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() -> jdbc.update("delete from read_dataset_capture_proofs where publication_id = ?", old.id()))
        .isInstanceOf(DataAccessException.class);
    assertThat(jdbc.queryForObject("select count(*) from read_dataset_rows where created_by_publication_id = ?",
        Long.class, candidate.id())).isZero();
    assertThatThrownBy(() -> producer.publishPreparedDataset(candidate.id(), "failed"))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void mirror_restore_timeout_persists_backoff_allows_other_tables_and_resumes_same_candidate() {
    long config = jdbc.queryForObject("insert into gitlab_sync_configs(db_name,db_username,db_password)"
        + " values ('retry-fixture','fixture','fixture') returning id", Long.class);
    long state = jdbc.queryForObject("insert into sync_run_table_states(config_id,source_instance,source_table,mirror_table,"
        + " primary_key_columns,last_full_verified_at) values (?,'default','fixture',?,'id',current_timestamp) returning id",
        Long.class, config, TABLE);
    long run = mirrorRun(config, "SUCCESS");
    mirrorTask(config, run, state, "SUCCESS");
    var key = new ReadPublicationDomainKey(ReadPublicationProducerKind.MIRROR_TABLE, "default", "DATASET", TABLE);
    String owner = "mirror-dataset:" + run + ":" + state;
    var request = new DatasetPublicationRequest(key, owner,
        List.of(new DatasetRelationInput(TABLE, TABLE, List.of("id"), List.of(), "select * from " + TABLE, List.of())),
        new DatasetInputEvidence.MirrorTableRun(run, state));
    ReadPublication prepared = producer.prepareCompleteDataset(request);
    DatasetPublicationService failing = org.mockito.Mockito.mock(DatasetPublicationService.class);
    org.mockito.Mockito.when(failing.publishPreparedDataset(prepared.id(), owner))
        .thenThrow(new org.springframework.dao.QueryTimeoutException("发布超时"));
    new MirrorDatasetPublicationWorker(jdbc, failing, json, new ReadProducerRetryService(jdbc)).publishOneCompleteTable();
    assertThat(jdbc.queryForObject("select producer_retry_after > current_timestamp from read_publication_domains where id = ?",
        Boolean.class, prepared.domainId())).isTrue();
    assertThat(repository.findPublication(prepared.id()).orElseThrow().state())
        .isEqualTo(com.data.collection.platform.entity.read.ReadPublicationState.PROJECTING);

    jdbc.update("insert into dataset_second_fixture values (1,'其它表可继续')");
    long nextState = jdbc.queryForObject("insert into sync_run_table_states(config_id,source_instance,source_table,mirror_table,"
        + " primary_key_columns,last_full_verified_at) values (?,'default','other','dataset_second_fixture','id',current_timestamp) returning id",
        Long.class, config);
    long nextRun = mirrorRun(config, "SUCCESS");
    jdbc.update("insert into sync_run_table_tasks(config_id,run_id,state_id,source_instance,source_table,mirror_table,task_type,"
        + "status,row_strategy,finished_at) values (?,?,?,'default','other','dataset_second_fixture','SCAN','SUCCESS','FULL_RECONCILE',current_timestamp)",
        config, nextRun, nextState);
    jdbc.update("update sync_run_table_states set last_full_verified_at = current_timestamp where id = ?", nextState);
    var resumed = new MirrorDatasetPublicationWorker(jdbc, producer, json, new ReadProducerRetryService(jdbc));
    resumed.publishOneCompleteTable();
    var nextKey = new ReadPublicationDomainKey(ReadPublicationProducerKind.MIRROR_TABLE, "default", "DATASET", "dataset_second_fixture");
    assertThat(repository.findDomain(nextKey).orElseThrow().currentPublicationId()).isNotNull();
    assertThat(repository.findDomain(key).orElseThrow().currentPublicationId()).isNull();

    jdbc.update("update read_publication_domains set producer_retry_after = current_timestamp - interval '1 second' where id = ?",
        prepared.domainId());
    resumed.publishOneCompleteTable();
    assertThat(repository.findDomain(key).orElseThrow().currentPublicationId()).isEqualTo(prepared.id());
    assertThat(jdbc.queryForObject("select producer_last_error from read_publication_domains where id = ?",
        String.class, prepared.domainId())).isNull();
    // 新完成镜像与原数据一致时复用旧点，不用新运行号再发布旧候选。
    long unchangedRun = mirrorRun(config, "SUCCESS");
    mirrorTask(config, unchangedRun, state, "SUCCESS");
    resumed.publishOneCompleteTable();
    assertThat(repository.findDomain(key).orElseThrow().currentPublicationId()).isEqualTo(prepared.id());
    assertThat(jdbc.queryForObject("select producer_last_error from read_publication_domains where id = ?",
        String.class, prepared.domainId())).isNull();
  }

  @Test
  void initial_fact_installation_is_bounded_and_rejected_domains_do_not_starve_later_sources() {
    long config = jdbc.queryForObject("insert into gitlab_sync_configs(db_name,db_username,db_password)"
        + " values ('install-fixture','fixture','fixture') returning id", Long.class);
    long run = mirrorRun(config, "SUCCESS");
    for (String type : List.of("ISSUE", "MERGE_REQUEST")) {
      jdbc.update("insert into source_fact_publication_states(config_id,source_instance,fact_type,latest_mirror_run_id,readiness_status)"
          + " values (?,'default',?,?,'READY')", config, type, run);
    }
    var scheduler = new ReadModelInstallationScheduler(jdbc, org.mockito.Mockito.mock(ReadModelInitialInstallation.class),
        org.mockito.Mockito.mock(com.data.collection.platform.bi.infrastructure.BiCatReadPublicationService.class),
        new org.springframework.transaction.support.TransactionTemplate(transactions), new ReadProducerRetryService(jdbc));
    scheduler.installOneFactSource();
    assertThat(jdbc.queryForObject("select count(*) from read_publication_domains where producer_kind = 'GITLAB_FACT'"
        + " and producer_retry_after > current_timestamp and producer_last_error is not null", Long.class)).isOne();
    scheduler.installOneFactSource();
    assertThat(jdbc.queryForObject("select count(*) from read_publication_domains where producer_kind = 'GITLAB_FACT'"
        + " and producer_retry_after > current_timestamp and producer_last_error is not null", Long.class)).isEqualTo(2);
    scheduler.installOneFactSource();
    scheduler.installOneCatSnapshot();
    assertThat(jdbc.queryForObject("select count(*) from read_publications", Long.class)).isZero();
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({
      "false, compatibility, compatibility, FORMAL_RECORD",
      "true, formal, compatibility, FORMAL_RECORD|COMPAT_REVIEW",
      "true, compatibility, formal, FORMAL_RECORD|COMPAT_WALKTHROUGH"
  })
  void initial_local_requests_only_include_enabled_read_sources(boolean enabled, String codeMode,
      String reviewMode, String expectedOwners) {
    resetFormalInputs();
    jdbc.update("update code_review_match_mode_db_settings set enabled = ?, code_review_read_mode = ?,"
        + " review_data_read_mode = ? where id = 1", enabled, codeMode, reviewMode);
    var rejecting = org.mockito.Mockito.mock(DatasetPublicationService.class);
    org.mockito.Mockito.when(rejecting.prepareCompleteDataset(org.mockito.ArgumentMatchers.any()))
        .thenThrow(new IllegalStateException("本测试只登记初始来源"));
    new DatasetRefreshWorker(jdbc, new com.data.collection.platform.service.DatasetProducerCatalog(
        new com.data.collection.platform.service.CodeReviewMatchModeConfigService(jdbc)), rejecting).publishOneRequestedDataset();
    assertThat(jdbc.queryForList("select producer_kind from read_dataset_refresh_requests", String.class))
        .containsExactlyInAnyOrder(expectedOwners.split("\\|"));
  }

  @Test
  void local_dataset_cleanup_failure_keeps_original_error_and_persistent_retry() {
    jdbc.update("delete from read_dataset_refresh_requests");
    jdbc.update("insert into read_dataset_refresh_requests(producer_kind) values ('FORMAL_RECORD')");
    var catalog = org.mockito.Mockito.mock(com.data.collection.platform.service.DatasetProducerCatalog.class);
    var failing = org.mockito.Mockito.mock(DatasetPublicationService.class);
    org.mockito.Mockito.when(catalog.request(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(request("failing-local"));
    org.mockito.Mockito.when(failing.prepareCompleteDataset(org.mockito.ArgumentMatchers.any()))
        .thenThrow(new IllegalStateException("原始捕获失败"));
    org.mockito.Mockito.doThrow(new org.springframework.dao.QueryTimeoutException("清理失败"))
        .when(failing).abortRequestedDataset(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
    new DatasetRefreshWorker(jdbc, catalog, failing).publishOneRequestedDataset();
    assertThat(jdbc.queryForObject("select count(*) from read_dataset_refresh_requests where last_error = '原始捕获失败'"
        + " and lease_owner is null and retry_after > current_timestamp", Long.class)).isOne();
  }

  @Test
  void complete_keyed_reconciliation_reuses_unchanged_rows_and_preserves_old_view() {
    ReadPublication old = publish("reconcile-old");
    jdbc.update("update dataset_source_fixture set caption = 'reconciled' where id = 1");
    ReadPublication candidate = producer.prepareCompleteDataset(request("reconcile-new"));
    assertThat(candidate.epochId()).isEqualTo(old.epochId());
    assertThat(jdbc.queryForObject("select count(*) from read_dataset_rows where created_by_publication_id = ?",
        Long.class, candidate.id())).isOne();
    ReadPublication current = producer.publishPreparedDataset(candidate.id(), "reconcile-new");
    assertThat(rows(old).getLast().caption()).isNull();
    assertThat(rows(current).getLast().caption()).isEqualTo("reconciled");
  }

  @Test
  void ingestion_timestamp_only_change_reuses_version_without_empty_candidate() {
    ReadPublication old = publish("first");
    jdbc.update("update dataset_source_fixture set synced_at = synced_at + interval '1 day'");
    ReadPublication unchanged = producer.prepareCompleteDataset(request("unchanged"));

    assertThat(unchanged.id()).isEqualTo(old.id());
    assertThat(jdbc.queryForObject("select count(*) from read_publications", Long.class)).isOne();
    assertThat(rows(unchanged)).extracting(Row::id).containsExactly(2L, 1L);
  }

  @Test
  void mode_switch_without_complete_target_keeps_previous_formal_read_publication() {
    jdbc.update("update code_review_match_mode_db_settings set enabled = false, code_review_read_mode = 'formal',"
        + " review_data_read_mode = 'formal' where id = 1");
    var formal = new com.data.collection.platform.service.DatasetProducerCatalog(
        org.mockito.Mockito.mock(com.data.collection.platform.service.CodeReviewMatchModeConfigService.class));
    var key = new ReadPublicationDomainKey(ReadPublicationProducerKind.FORMAL_RECORD, "default", "DATASET", "formal-records");
    ReadPublication previous = producer.prepareCompleteDataset(formal.request(ReadPublicationProducerKind.FORMAL_RECORD, "mode-before"));
    previous = producer.publishPreparedDataset(previous.id(), "mode-before");
    var app = application();
    var caller = readerRequest("mode-parent","ADMIN");
    ReadView parent = app.acquire(caller,"mirror-table-overview",Map.of(),UUID.randomUUID());
    var oldInput = new DatasetReadSelection(List.of(new PublishedDatasetReadContext("default", "formal-records",
        previous.epochId(), previous.sequenceNo(), previous.id(), previous.dataAsOf())), null, LocalDate.of(2026, 10, 13));

    jdbc.update("update code_review_match_mode_db_settings set enabled = true, code_review_read_mode = 'compatibility',"
        + " review_data_read_mode = 'compatibility' where id = 1");
    ReadPublication preparing = producer.prepareCompleteDataset(formal.request(ReadPublicationProducerKind.FORMAL_RECORD, "mode-after"));
    assertThatThrownBy(() -> producer.publishPreparedDataset(preparing.id(), "mode-after"))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("目标来源尚无完整产出");
    assertThat(repository.findCurrentPublication(repository.findDomain(key).orElseThrow().id()).orElseThrow().id())
        .isEqualTo(previous.id());
    assertThat(guard.withinRead(oldInput, () -> queries.queryForObject(oldInput,
        "select enabled from code_review_match_mode_db_settings where id = 1", Boolean.class))).isFalse();
    var pages = new ReadPageCatalog(jdbc, org.mockito.Mockito.mock(com.data.collection.platform.service.PagePermissionKeyResolver.class),
        org.mockito.Mockito.mock(com.data.collection.platform.bi.application.BiPagePermissionResolver.class));
    assertThat(pages.domains("review-data-records", java.util.Map.of(), LocalDate.of(2026, 10, 13))).containsExactly(key);

    // 当前设置再变动不能替换候选已捕获的模式；仍需认证候选自己的目标。
    jdbc.update("update code_review_match_mode_db_settings set enabled = false where id = 1");
    assertThatThrownBy(() -> producer.publishPreparedDataset(preparing.id(), "mode-after"))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("目标来源尚无完整产出");
    var targets = new com.data.collection.platform.service.DatasetProducerCatalog(
        new com.data.collection.platform.service.CodeReviewMatchModeConfigService(jdbc));
    for (var owner : List.of(ReadPublicationProducerKind.COMPAT_REVIEW, ReadPublicationProducerKind.COMPAT_WALKTHROUGH)) {
      var request = targets.request(owner, "mode-target:" + owner);
      var inventory = (DatasetInputEvidence.CompatibilityInventory) request.evidence();
      String metadata = owner == ReadPublicationProducerKind.COMPAT_REVIEW
          ? "legacy_mongo_imported_collections" : "legacy_mysql_imported_tables";
      String raw = owner == ReadPublicationProducerKind.COMPAT_REVIEW
          ? "legacy_mongo_imported_documents" : "legacy_mysql_imported_rows";
      String name = owner == ReadPublicationProducerKind.COMPAT_REVIEW ? "collection_name" : "table_name";
      // 明确构造实际完成的空来源；缺装载时刻的“从未采集”不能作为此证明。
      for (String input : inventory.keys()) {
        jdbc.update("delete from " + raw + " where " + name + " = ?", input);
        jdbc.update("insert into " + metadata + "(" + name + ", record_count, last_synced_at)"
            + " values (?, 0, current_timestamp) on conflict(" + name + ") do update"
            + " set record_count = 0, last_synced_at = current_timestamp", input);
      }
      List<String> physicalTables = request.relations().stream().map(DatasetRelationInput::relationName)
          .filter(table -> Boolean.TRUE.equals(jdbc.queryForObject(
              "select relkind in ('r', 'p') from pg_class where oid = ?::regclass", Boolean.class, table))).toList();
      jdbc.execute("truncate " + String.join(", ", physicalTables) + " cascade");
      ReadPublication target = producer.prepareCompleteDataset(request);
      producer.publishPreparedDataset(target.id(), request.producerRunId());
    }
    ReadPublication accepted = producer.publishPreparedDataset(preparing.id(), "mode-after");
    assertThat(repository.findCurrentPublication(accepted.domainId()).orElseThrow().id()).isEqualTo(accepted.id());
    assertThat(pages.domains("review-data-records", java.util.Map.of(), LocalDate.of(2026, 10, 13)))
        .containsExactlyInAnyOrder(key,
            new ReadPublicationDomainKey(ReadPublicationProducerKind.COMPAT_REVIEW, "default", "DATASET", "compat-review"));
    assertThat(repository.findPublication(previous.id()).orElseThrow().publiclyReadable()).isTrue();
    ReadView child = app.fork(caller,parent.viewId(),"review-data-records",Map.of(),UUID.randomUUID());
    assertThat(child.domains()).isEqualTo(parent.domains());
    assertThat(child.requireDomain(key).publicationId()).isEqualTo(previous.id());
  }

  @Test
  void published_payload_and_historical_intervals_cannot_be_rewritten_or_deleted() {
    ReadPublication old = publish("first");
    assertThatThrownBy(() -> jdbc.update("update read_dataset_rows set row_payload = jsonb_set(row_payload, '{caption}', '\"forged\"')"
        + " where created_by_publication_id = ?", old.id())).isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() -> jdbc.update("delete from read_dataset_rows where created_by_publication_id = ?", old.id()))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() -> jdbc.update("update read_dataset_rows set visible_from = 9 where created_by_publication_id = ?", old.id()))
        .isInstanceOf(DataAccessException.class);
    assertThat(rows(old).getLast().caption()).isNull();
  }

  @Test
  void active_view_blocks_dataset_gc_and_releasing_it_allows_history_collection() {
    ReadPublication old = publish("first");
    var view = reads.acquireView(viewRequest());
    jdbc.update("update dataset_source_fixture set caption = 'new' where id = 1");
    jdbc.update("delete from dataset_source_fixture where id = 2");
    publish("second");
    properties.setRetirementGraceMinutes(-1);
    assertThat(lifecycle.collectGarbage(DOMAIN).retiringPublications()).isZero();
    List<Row> protectedRows = reads.withinPublishedRead(view, actual -> rows(old));
    assertThat(protectedRows).hasSize(2);

    reads.releaseView(view.viewId(), "tester", "session");
    assertThat(lifecycle.collectGarbage(DOMAIN).deletedRevisions()).isEqualTo(2);
    lifecycle.collectGarbage(DOMAIN);
    assertThat(repository.findPublication(old.id())).isEmpty();
  }

  @Test
  void unkeyed_full_dataset_keeps_duplicate_rows_and_previous_epoch() {
    jdbc.update("insert into dataset_unkeyed_fixture(caption, amount) values ('same', 10), ('same', 10)");
    DatasetRelationInput input = new DatasetRelationInput("unkeyed", "dataset_unkeyed_fixture", List.of(),
        List.of("synced_at"), "select * from dataset_unkeyed_fixture", List.of());
    var request = new DatasetPublicationRequest(DOMAIN, "unkeyed-first", List.of(input), new DatasetInputEvidence.FormalCommit());
    ReadPublication old = producer.prepareCompleteDataset(request);
    old = producer.publishPreparedDataset(old.id(), "unkeyed-first");
    jdbc.update("delete from dataset_unkeyed_fixture where ctid in (select ctid from dataset_unkeyed_fixture limit 1)");
    ReadPublication current = producer.prepareCompleteDataset(new DatasetPublicationRequest(DOMAIN, "unkeyed-second",
        List.of(input), new DatasetInputEvidence.FormalCommit()));
    current = producer.publishPreparedDataset(current.id(), "unkeyed-second");
    assertThat(current.epochId()).isNotEqualTo(old.epochId());
    assertThat(countUnkeyed(old)).isEqualTo(2);
    assertThat(countUnkeyed(current)).isOne();
  }

  private long countUnkeyed(ReadPublication publication) {
    var selected = selection(publication);
    return guard.withinRead(selected, () -> queries.queryForObject(selected, "select count(*) from dataset_unkeyed_fixture", Long.class));
  }

  private List<Row> rows(ReadPublication publication) {
    DatasetReadSelection selection = selection(publication);
    return guard.withinRead(selection, () -> queries.query(selection,
        "select id, caption, amount, source_day from " + TABLE + " order by amount",
        (rs, row) -> new Row(rs.getLong(1), rs.getString(2), rs.getBigDecimal(3), rs.getObject(4, LocalDate.class))));
  }

  private DatasetReadSelection selection(ReadPublication publication) {
    var domain = repository.findDomain(publication.domainId()).orElseThrow().key();
    return new DatasetReadSelection(List.of(new PublishedDatasetReadContext(domain.sourceInstance(), domain.tableKey(),
        publication.epochId(), publication.sequenceNo(), publication.id(), publication.dataAsOf())), null, java.time.LocalDate.of(2026, 10, 13));
  }

  private DatasetPublicationRequest request(String runId) {
    return new DatasetPublicationRequest(DOMAIN, runId,
        List.of(new DatasetRelationInput(TABLE, TABLE, List.of("id"), List.of("synced_at"), "select * from " + TABLE, List.of())),
        new DatasetInputEvidence.FormalCommit());
  }

  private ReadPublication publish(String runId) {
    ReadPublication prepared = producer.prepareCompleteDataset(request(runId));
    return producer.publishPreparedDataset(prepared.id(), runId);
  }

  private AcquireReadViewRequest viewRequest() {
    return new AcquireReadViewRequest("tester", "session", "dataset-fixture", "all", LocalDate.of(2026, 10, 13), "rule", Set.of(DOMAIN), java.util.UUID.randomUUID());
  }

  private <T> T proxy(T target) {
    ProxyFactory factory = new ProxyFactory(target);
    factory.setProxyTargetClass(true);
    factory.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
    @SuppressWarnings("unchecked") T result = (T) factory.getProxy();
    return result;
  }

  private record Row(long id, String caption, BigDecimal amount, LocalDate day) {}
}
