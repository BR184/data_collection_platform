package com.data.collection.platform.service.read;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.data.collection.platform.config.ReadFallbackProperties;
import com.data.collection.platform.entity.FactType;
import com.data.collection.platform.entity.read.CandidateReadContext;
import com.data.collection.platform.entity.read.FactReadQueryContext;
import com.data.collection.platform.entity.read.FactRootEntry;
import com.data.collection.platform.entity.read.FactRootOperation;
import com.data.collection.platform.entity.read.PublicationFactTable;
import com.data.collection.platform.entity.read.PublishedReadContext;
import com.data.collection.platform.entity.read.ReadPublication;
import com.data.collection.platform.entity.read.ReadPublicationDomainKey;
import com.data.collection.platform.entity.read.ReadView;
import com.data.collection.platform.service.sync.PostgresIntegrationTestDatabase;
import com.data.collection.platform.service.sync.SyncFactPublicationStateService;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 在真实 PostgreSQL 上验证读发布模型：旧版本稳定、失败不切指针、发布 CAS、区间可见性、关系父修订绑定、
 * 区间回收保留、读取期排他保护、首次与全量候选可见性、初始版本正向证据，以及根清单完整业务身份。
 *
 * <p>应用完整迁移链，包括 P2 的修订唯一约束；已发布修订稳定与候选隔离均由真实数据库验证。
 */
class ReadPublicationModelIntegrationTest {
  private static final ReadPublicationDomainKey ISSUE_DOMAIN =
      ReadPublicationDomainKey.gitlabFact("default", FactType.ISSUE);
  private static final ReadPublicationDomainKey MR_DOMAIN =
      ReadPublicationDomainKey.gitlabFact("default", FactType.MERGE_REQUEST);

  private static PostgresIntegrationTestDatabase database;

  private javax.sql.DataSource dataSource;
  private JdbcTemplate jdbcTemplate;
  private TransactionTemplate tx;
  private ReadFallbackProperties properties;
  private ReadPublicationRepository publicationRepository;
  private ReadPublicationService publicationService;
  private ReadViewRepository viewRepository;
  private PublishedReadService publishedReadService;

  @BeforeAll
  static void migrateSchema() {
    database = PostgresIntegrationTestDatabase.open("read_publication_model");
    Flyway flyway =
        Flyway.configure()
            .dataSource(database.dataSource())
            .locations("classpath:db/migration")
            .schemas("public")
            .defaultSchema("public")
            .cleanDisabled(false)
            .load();
    flyway.clean();
    flyway.migrate();
  }

  @AfterAll
  static void closeDatabase() {
    if (database != null) {
      database.close();
    }
  }

  @BeforeEach
  void setUp() {
    dataSource = database.dataSource();
    jdbcTemplate = new JdbcTemplate(dataSource);
    DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
    tx = new TransactionTemplate(transactionManager);
    properties = new ReadFallbackProperties();
    publicationRepository = new ReadPublicationRepository(jdbcTemplate);
    publicationService = new ReadPublicationService(publicationRepository, properties);
    viewRepository = new ReadViewRepository(jdbcTemplate);
    publishedReadService =
        new PublishedReadService(publicationRepository, viewRepository, properties, transactionManager);
    resetTables();
  }

  @Test
  void bootstrap_refuses_existing_unpublished_revisions_and_keeps_candidate_ownership() {
    long root = insertIssueRoot(100L);
    var candidate = tx.execute(status -> publicationService.openCandidate(new CandidateOpenRequest(ISSUE_DOMAIN,
        UUID.randomUUID(), null, "{}", 0, null, "inflight", null, "inflight", Instant.now())));
    insertCandidateIssueRevision(101L, candidate.epochId(), candidate.id());
    ReadPublication result = bootstrap(admitted());
    assertThat(result).isNull();
    assertThat(publicationRepository.findDomain(ISSUE_DOMAIN).orElseThrow().currentPublicationId()).isNull();
    assertThat(publicationRepository.findActiveCandidate(candidate.domainId()).orElseThrow().id()).isEqualTo(candidate.id());
    assertThat(jdbcTemplate.queryForObject("select count(*) from issue_fact where id = ?", Long.class, root)).isOne();
  }

  private void resetTables() {
    jdbcTemplate.execute(
        """
        truncate table read_view_publications, read_view_contexts, read_publication_fact_roots,
            read_publication_scope_generations, read_publications, read_publication_domains,
            read_definition_snapshots, issue_fact_customer_members, issue_fact,
            merge_request_commit_fact, merge_request_fact, integration_test_fact,
            gitlab_sync_configs, fact_change_heads, fact_build_tasks
            restart identity cascade
        """);
  }

  // ---- 发布：指针推进、旧版本稳定、删除与新增根 ----

  @Test
  void test_live_revision_excludes_open_rows_of_a_superseded_full_epoch() {
    insertIssueRoot(100L);
    ReadPublication baseline = bootstrap(admitted());
    ReadPublication candidate = tx.execute(status -> publicationService.openCandidate(new CandidateOpenRequest(
        ISSUE_DOMAIN, UUID.randomUUID(), baseline.id(), "{\"seal\":\"full\"}", 20L, null, "full-current", 1L, "owner", Instant.now())));
    long updated = insertCandidateIssueRevision(100L, candidate.epochId(), candidate.id());
    recordUpsert(ISSUE_DOMAIN, candidate.id(), 100L, null, updated);
    assertThat(jdbcTemplate.queryForList("select f.id from issue_fact f where " + FactReadVisibility.liveRevisionPredicate("f"), Long.class))
        .doesNotContain(updated).hasSize(1);
    publishReady(candidate.id(), "owner");
    assertThat(jdbcTemplate.queryForList("select f.id from issue_fact f where " + FactReadVisibility.liveRevisionPredicate("f"), Long.class))
        .containsExactly(updated);
    assertThat(jdbcTemplate.queryForObject("select count(*) from issue_fact", Integer.class)).isEqualTo(2);
  }

  @Test
  void test_new_revision_preserves_business_order_and_rejects_reordering() {
    long first = insertIssueRoot(100L);
    long second = insertIssueRoot(101L);
    ReadPublication baseline = bootstrap(admitted());
    ReadPublication candidate = openCandidate(ISSUE_DOMAIN, baseline, "{\"seal\":\"order\"}");
    long revised = insertCandidateIssueRevision(100L, candidate.epochId(), candidate.id());
    recordUpsert(ISSUE_DOMAIN, candidate.id(), 100L, first, revised);
    ReadPublication published = publishReady(candidate.id(), "owner");
    assertThat(revised).isGreaterThan(second);
    assertThat(jdbcTemplate.queryForObject("select business_order_id from issue_fact where id = ?", Long.class, revised))
        .isEqualTo(first);
    SqlFilter visible = FactReadVisibility.visibleFacts("fact", new PublishedReadContext("default", PublicationFactTable.ISSUE,
        published.epochId(), published.sequenceNo(), published.id(), published.dataAsOf()));
    assertThat(jdbcTemplate.queryForList("select fact.id from issue_fact fact where " + visible.sql()
        + " order by fact.business_order_id", Long.class, visible.args().toArray())).containsExactly(revised, second);
    assertThatThrownBy(() -> jdbcTemplate.update("update issue_fact set business_order_id = ? where id = ?", second, revised))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void test_publish_advances_pointer_and_keeps_previous_version_readable() {
    long retained = insertIssueRoot(100L);
    long removed = insertIssueRoot(101L);
    ReadPublication v1 = bootstrap(admitted());
    assertThat(v1.sequenceNo()).isEqualTo(1L);

    ReadPublication candidate = openCandidate(ISSUE_DOMAIN, v1, "{\"seal\":\"v2\"}");
    long added = insertCandidateIssueRevision(102L, candidate.epochId(), candidate.id());
    recordUpsert(ISSUE_DOMAIN, candidate.id(), 102L, null, added);
    recordDelete(ISSUE_DOMAIN, candidate.id(), 101L, removed);
    ReadPublication v2 = publishReady(candidate.id(), "owner");

    assertThat(v2.sequenceNo()).isEqualTo(2L);
    assertThat(visibleFrom(retained)).isEqualTo(1L);
    assertThat(visibleTo(retained)).isNull();
    assertThat(visibleTo(removed)).isEqualTo(2L);
    assertThat(visibleFrom(added)).isEqualTo(2L);

    assertThat(visibleIssueIds(v1.epochId(), 1L)).containsExactlyInAnyOrder(retained, removed);
    assertThat(visibleIssueIds(v2.epochId(), 2L)).containsExactlyInAnyOrder(retained, added);
  }

  @Test
  void test_publish_before_ready_does_not_move_pointer_or_open_revisions() {
    long existing = insertIssueRoot(100L);
    ReadPublication v1 = bootstrap(admitted());
    ReadPublication candidate = openCandidate(ISSUE_DOMAIN, v1, "{\"seal\":\"v2\"}");
    long added = insertCandidateIssueRevision(101L, candidate.epochId(), candidate.id());

    assertThatThrownBy(() -> tx.execute(status -> publicationService.publish(candidate.id(), "owner")))
        .isInstanceOf(IllegalStateException.class);

    assertThat(currentPublicationId()).isEqualTo(v1.id());
    assertThat(visibleFrom(added)).isNull();
    assertThat(visibleTo(added)).isNull();
    assertThat(visibleTo(existing)).isNull();
  }

  @Test
  void test_candidate_open_rejects_stale_base_after_newer_publication() {
    insertIssueRoot(100L);
    ReadPublication v1 = bootstrap(admitted());
    ReadPublication candidate = openCandidate(ISSUE_DOMAIN, v1, "{\"seal\":\"v2\"}");
    insertCandidateIssueRevision(101L, candidate.epochId(), candidate.id());
    publishReady(candidate.id(), "owner");

    assertThatThrownBy(
            () ->
                tx.execute(
                    status ->
                        publicationService.openCandidate(
                            new CandidateOpenRequest(
                                ISSUE_DOMAIN, v1.epochId(), v1.id(), "{\"seal\":\"v3\"}", 30L, null,
                                "run", 1L, "owner", Instant.now()))))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void test_open_published_revision_index_is_defined_and_interval_check_is_enforced() {
    insertIssueRoot(100L);
    ReadPublication v1 = bootstrap(admitted());

    // 既有自然键唯一约束与开放修订索引同列，会抢先报错，因此这里只核对索引定义本身；
    // 行为隔离需在自然键约束切换（P2）后再验证。
    List<String> definitions =
        jdbcTemplate.query(
            """
            select indexdef from pg_indexes
             where schemaname = 'public' and indexname = 'uq_issue_fact_open_published_revision'
            """,
            (rs, rowNum) -> rs.getString("indexdef"));
    assertThat(definitions).hasSize(1);
    String definition = definitions.get(0).toLowerCase();
    assertThat(definition)
        .contains("unique index")
        .contains("publication_epoch_id")
        .contains("source_system")
        .contains("source_instance")
        .contains("project_id")
        .contains("issue_id")
        .contains("visible_from is not null")
        .contains("visible_to is null");

    // 区间必须满足 visible_to > visible_from。
    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    """
                    insert into issue_fact(source_system, source_instance, project_id, issue_id, issue_iid,
                        title, publication_epoch_id, created_by_publication_id, visible_from, visible_to)
                    values ('GITLAB', 'default', 1, 999, 999, 'bad', ?, ?, 5, 5)
                    """,
                    v1.epochId(),
                    UUID.randomUUID()))
        .isInstanceOf(DataAccessException.class);
  }

  // ---- 关系绑定 ----

  @Test
  void test_customer_member_relation_binds_to_parent_revision() {
    long revision = insertIssueRoot(100L);
    insertCustomerMember(100L, "客户A");

    ReadPublication v1 = bootstrap(admitted());

    assertThat(memberBoundRevision("客户A")).isEqualTo(revision);
    assertThat(memberCountInVersion(v1.epochId(), 1L)).isEqualTo(1L);

    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    """
                    insert into issue_fact_customer_members(source_system, source_instance, project_id,
                        issue_id, customer_name, issue_fact_revision_id)
                    values ('GITLAB', 'default', 1, 200, '客户B', ?)
                    """,
                    9_999_999L))
        .isInstanceOf(DataAccessException.class);
  }

  // ---- 回收：区间保留、在途锁保护、子关系先删 ----

  @Test
  void test_gc_removes_unreferenced_history_and_keeps_current_revision() {
    long removedRevision = insertIssueRoot(101L);
    ReadPublication v1 = bootstrap(admitted());
    ReadPublication candidate = openCandidate(ISSUE_DOMAIN, v1, "{\"seal\":\"v2\"}");
    long keptRevision = insertCandidateIssueRevision(100L, candidate.epochId(), candidate.id());
    recordUpsert(ISSUE_DOMAIN, candidate.id(), 100L, null, keptRevision);
    recordDelete(ISSUE_DOMAIN, candidate.id(), 101L, removedRevision);
    publishReady(candidate.id(), "owner");

    properties.setRetirementGraceMinutes(-1);
    ReadGcResult result = tx.execute(status -> publicationService.collectGarbage(ISSUE_DOMAIN));

    assertThat(result.retiringPublications()).isEqualTo(1);
    assertThat(result.deletedRevisions()).isEqualTo(1);
    assertThat(result.removedPublications()).isEqualTo(1);
    assertThat(countIssueRevision(removedRevision)).isZero();
    assertThat(countIssueRevision(keptRevision)).isEqualTo(1L);
    assertThat(publicationRepository.findPublication(v1.id())).isEmpty();
  }

  @Test
  void test_gc_keeps_history_referenced_by_an_active_view() {
    long removedRevision = insertIssueRoot(101L);
    ReadPublication v1 = bootstrap(admitted());
    ReadView view = tx.execute(status -> publishedReadService.acquireView(issueViewRequest()));
    ReadPublication candidate = openCandidate(ISSUE_DOMAIN, v1, "{\"seal\":\"v2\"}");
    long keptRevision = insertCandidateIssueRevision(100L, candidate.epochId(), candidate.id());
    recordUpsert(ISSUE_DOMAIN, candidate.id(), 100L, null, keptRevision);
    recordDelete(ISSUE_DOMAIN, candidate.id(), 101L, removedRevision);
    publishReady(candidate.id(), "owner");

    properties.setRetirementGraceMinutes(-1);
    ReadGcResult result = tx.execute(status -> publicationService.collectGarbage(ISSUE_DOMAIN));

    assertThat(result.retiringPublications()).isZero();
    assertThat(result.deletedRevisions()).isZero();
    assertThat(result.removedPublications()).isZero();
    assertThat(countIssueRevision(removedRevision)).isEqualTo(1L);
    assertThat(publicationRepository.findPublication(v1.id())).isPresent();
    assertThat(view.requireDomain(ISSUE_DOMAIN).sequence()).isEqualTo(1L);
  }

  @Test
  void test_gc_skips_publication_locked_by_in_flight_read() throws Exception {
    long removedRevision = insertIssueRoot(101L);
    ReadPublication v1 = bootstrap(admitted());
    ReadPublication candidate = openCandidate(ISSUE_DOMAIN, v1, "{\"seal\":\"v2\"}");
    long keptRevision = insertCandidateIssueRevision(100L, candidate.epochId(), candidate.id());
    recordUpsert(ISSUE_DOMAIN, candidate.id(), 100L, null, keptRevision);
    recordDelete(ISSUE_DOMAIN, candidate.id(), 101L, removedRevision);
    publishReady(candidate.id(), "owner");
    properties.setRetirementGraceMinutes(-1);

    try (Connection holder = dataSource.getConnection()) {
      holder.setAutoCommit(false);
      holdKeyShare(holder, v1.id());
      ReadGcResult blocked = tx.execute(status -> publicationService.collectGarbage(ISSUE_DOMAIN));
      assertThat(blocked.retiringPublications()).isZero();
      assertThat(countIssueRevision(removedRevision)).isEqualTo(1L);
      holder.rollback();
    }

    ReadGcResult cleared = tx.execute(status -> publicationService.collectGarbage(ISSUE_DOMAIN));
    assertThat(cleared.retiringPublications()).isEqualTo(1);
    assertThat(countIssueRevision(removedRevision)).isZero();
    assertThat(countIssueRevision(keptRevision)).isEqualTo(1L);
  }

  @Test
  void test_gc_preserves_locked_version_sharing_epoch_with_retiring_version() throws Exception {
    long first = insertIssueRoot(100L);
    ReadPublication v1 = bootstrap(admitted());
    ReadPublication c2 = openCandidate(ISSUE_DOMAIN, v1, "{\"seal\":\"v2\"}");
    long second = insertCandidateIssueRevision(200L, c2.epochId(), c2.id());
    recordUpsert(ISSUE_DOMAIN, c2.id(), 200L, null, second);
    recordDelete(ISSUE_DOMAIN, c2.id(), 100L, first);
    ReadPublication v2 = publishReady(c2.id(), "owner");
    ReadPublication c3 = openCandidate(ISSUE_DOMAIN, v2, "{\"seal\":\"v3\"}");
    long third = insertCandidateIssueRevision(300L, c3.epochId(), c3.id());
    recordUpsert(ISSUE_DOMAIN, c3.id(), 300L, null, third);
    recordDelete(ISSUE_DOMAIN, c3.id(), 200L, second);
    publishReady(c3.id(), "owner");

    jdbcTemplate.update("update read_publications set state = 'RETIRING' where id = ?", v1.id());
    properties.setRetirementGraceMinutes(-1);
    try (Connection holder = dataSource.getConnection()) {
      holder.setAutoCommit(false);
      holdKeyShare(holder, v2.id());
      tx.execute(status -> publicationService.collectGarbage(ISSUE_DOMAIN));
      // V2 被读取持锁、未获回收排他锁，其独占修订即使与正在回收的 V1 同代际也必须保留。
      assertThat(countIssueRevision(second)).as("locked v2 content must remain").isEqualTo(1L);
      holder.rollback();
    }
  }

  @Test
  void test_gc_preserves_cross_domain_projection_input_until_candidate_aborts() {
    long oldRevision = insertIssueRoot(100L);
    ReadPublication v1 = bootstrap(admitted());
    ReadPublication projecting = tx.execute(status -> publicationService.openCandidate(
        new CandidateOpenRequest(MR_DOMAIN, UUID.randomUUID(), null, "{}", 0, null,
            "run", 1L, "owner", Instant.parse("2026-10-02T00:00:00Z"))));
    tx.executeWithoutResult(status -> publicationService.markProjecting(projecting.id(), "owner"));
    jdbcTemplate.update("insert into read_publication_dependencies(publication_id, domain_id, dependency_publication_id) values (?, ?, ?)",
        projecting.id(), v1.domainId(), v1.id());
    ReadPublication update = openCandidate(ISSUE_DOMAIN, v1, "{}");
    long nextRevision = insertCandidateIssueRevision(200L, update.epochId(), update.id());
    recordUpsert(ISSUE_DOMAIN, update.id(), 200L, null, nextRevision);
    recordDelete(ISSUE_DOMAIN, update.id(), 100L, oldRevision);
    publishReady(update.id(), "owner");
    properties.setRetirementGraceMinutes(-1);

    ReadGcResult protectedResult = tx.execute(status -> publicationService.collectGarbage(ISSUE_DOMAIN));
    assertThat(protectedResult.retiringPublications()).isZero();
    assertThat(countIssueRevision(oldRevision)).isOne();
    assertThat(publicationRepository.findPublication(v1.id()).orElseThrow().state())
        .isEqualTo(com.data.collection.platform.entity.read.ReadPublicationState.PUBLISHED);

    tx.executeWithoutResult(status -> publicationService.abort(projecting.id(), "fixture aborted"));
    tx.executeWithoutResult(status -> publicationService.collectGarbage(ISSUE_DOMAIN));
    assertThat(countIssueRevision(oldRevision)).isZero();
  }

  @Test
  void test_versioned_business_sql_keeps_old_rows_after_new_publication() {
    long oldRevision = insertIssueRoot(100L);
    ReadPublication v1 = bootstrap(admitted());
    ReadPublication candidate = openCandidate(ISSUE_DOMAIN, v1, "{}");
    long nextRevision = insertCandidateIssueRevision(200L, candidate.epochId(), candidate.id());
    recordUpsert(ISSUE_DOMAIN, candidate.id(), 200L, null, nextRevision);
    recordDelete(ISSUE_DOMAIN, candidate.id(), 100L, oldRevision);
    publishReady(candidate.id(), "owner");
    com.data.collection.platform.entity.read.FactReadSelection selection =
        new com.data.collection.platform.entity.read.FactReadSelection(List.of(
            new PublishedReadContext("default", PublicationFactTable.ISSUE, v1.epochId(), 1, v1.id(), v1.dataAsOf())), java.time.LocalDate.of(2026, 10, 13));
    VersionedFactSql.Query query = VersionedFactSql.bind(selection,
        "select issue_id from issue_fact where deleted = false order by issue_id", List.of());

    assertThat(jdbcTemplate.queryForList(query.sql(), Long.class, query.arguments().toArray()))
        .containsExactly(100L);
  }

  @Test
  void test_gc_deletes_child_relations_before_parent_revision() {
    long old = insertIssueRoot(100L);
    insertCustomerMember(100L, "review-customer");
    ReadPublication v1 = bootstrap(admitted());
    assertThat(memberBoundRevision("review-customer")).isEqualTo(old);

    ReadPublication candidate = openCandidate(ISSUE_DOMAIN, v1, "{\"seal\":\"v2\"}");
    long added = insertCandidateIssueRevision(200L, candidate.epochId(), candidate.id());
    recordUpsert(ISSUE_DOMAIN, candidate.id(), 200L, null, added);
    recordDelete(ISSUE_DOMAIN, candidate.id(), 100L, old);
    publishReady(candidate.id(), "owner");

    properties.setRetirementGraceMinutes(-1);
    Assertions.assertDoesNotThrow(
        () -> tx.execute(status -> publicationService.collectGarbage(ISSUE_DOMAIN)));
    assertThat(countIssueRevision(old)).isZero();
    assertThat(memberBoundRevision("review-customer")).isNull();
  }

  @Test
  void test_gc_deletes_merge_request_commit_relations_before_parent_revision() {
    long old = insertMergeRequestRoot(100L);
    insertCommitFact(100L, null, "sha-old");
    ReadPublication v1 =
        tx.execute(
            status ->
                publicationService.bootstrapInitialVersion(
                    MR_DOMAIN, "GITLAB", Instant.parse("2026-10-01T00:00:00Z"), "{\"seal\":\"v1\"}",
                    10L, admitted()));
    assertThat(commitBoundRevision("sha-old")).as("adoption binds commit to MR revision").isEqualTo(old);

    ReadPublication candidate = openCandidate(MR_DOMAIN, v1, "{\"seal\":\"v2\"}");
    long added = insertCandidateMergeRequestRevision(200L, candidate.epochId(), candidate.id());
    recordUpsert(MR_DOMAIN, candidate.id(), 200L, null, added);
    recordDelete(MR_DOMAIN, candidate.id(), 100L, old);
    publishReady(candidate.id(), "owner");

    properties.setRetirementGraceMinutes(-1);
    Assertions.assertDoesNotThrow(
        () -> tx.execute(status -> publicationService.collectGarbage(MR_DOMAIN)));
    assertThat(countMergeRequestRevision(old)).isZero();
    assertThat(commitBoundRevision("sha-old")).isNull();
  }

  // ---- 候选中止：子关系先删 ----

  @Test
  void test_abort_commits_without_synchronously_deleting_fact_revisions() {
    insertIssueRoot(100L);
    ReadPublication baseline = bootstrap(admitted());
    ReadPublication candidate = openCandidate(ISSUE_DOMAIN, baseline, "{\"seal\":\"deferred\"}");
    long added = insertCandidateIssueRevision(200L, candidate.epochId(), candidate.id());

    tx.execute(status -> publicationService.abort(candidate.id(), "发布失败"));

    assertThat(publicationRepository.findPublication(candidate.id()).orElseThrow().state())
        .isEqualTo(com.data.collection.platform.entity.read.ReadPublicationState.ABORTED);
    assertThat(publicationRepository.findDomain(ISSUE_DOMAIN).orElseThrow().currentPublicationId())
        .isEqualTo(baseline.id());
    assertThat(countIssueRevision(added)).as("中止提交不得等待物理删除").isOne();
    assertThat(publicationRepository.findActiveCandidate(candidate.domainId())).isEmpty();
  }

  @Test
  void test_abort_cleans_candidate_children_and_releases_candidate() {
    insertIssueRoot(100L);
    ReadPublication v1 = bootstrap(admitted());
    ReadPublication candidate = openCandidate(ISSUE_DOMAIN, v1, "{\"seal\":\"v2\"}");
    long added = insertCandidateIssueRevision(200L, candidate.epochId(), candidate.id());
    recordUpsert(ISSUE_DOMAIN, candidate.id(), 200L, null, added);
    jdbcTemplate.update(
        """
        insert into issue_fact_customer_members(source_system, source_instance, project_id,
            issue_id, customer_name, issue_fact_revision_id)
        values ('GITLAB', 'default', 1, 200, 'candidate-customer', ?)
        """,
        added);

    Assertions.assertDoesNotThrow(
        () -> tx.execute(status -> publicationService.abort(candidate.id(), "review abort")));

    tx.execute(status -> publicationService.cleanupAbortedFactCandidate(candidate.id()));
    assertThat(countIssueRevision(added)).isZero();
    assertThat(publicationRepository.findActiveCandidate(candidate.domainId())).isEmpty();
  }

  @Test
  void test_abort_cleans_candidate_commit_relations() {
    insertMergeRequestRoot(100L);
    ReadPublication v1 =
        tx.execute(
            status ->
                publicationService.bootstrapInitialVersion(
                    MR_DOMAIN, "GITLAB", Instant.parse("2026-10-01T00:00:00Z"), "{\"seal\":\"v1\"}",
                    10L, admitted()));
    ReadPublication candidate = openCandidate(MR_DOMAIN, v1, "{\"seal\":\"v2\"}");
    long added = insertCandidateMergeRequestRevision(200L, candidate.epochId(), candidate.id());
    recordUpsert(MR_DOMAIN, candidate.id(), 200L, null, added);
    insertCommitFact(200L, added, "sha-candidate");

    Assertions.assertDoesNotThrow(
        () -> tx.execute(status -> publicationService.abort(candidate.id(), "review abort")));

    tx.execute(status -> publicationService.cleanupAbortedFactCandidate(candidate.id()));
    assertThat(countMergeRequestRevision(added)).isZero();
    assertThat(commitBoundRevision("sha-candidate")).isNull();
  }

  @Test
  void test_abort_cleanup_is_bounded_and_resumes_after_service_recreation() {
    long currentRevision = insertIssueRoot(100L);
    ReadPublication baseline = bootstrap(admitted());
    ReadPublication candidate = openCandidate(ISSUE_DOMAIN, baseline, "{\"seal\":\"batches\"}");
    for (long root = 200; root < 205; root++) {
      long revision = insertCandidateIssueRevision(root, candidate.epochId(), candidate.id());
      jdbcTemplate.update("insert into issue_fact_customer_members(source_system, source_instance, project_id,"
          + " issue_id, customer_name, issue_fact_revision_id) values ('GITLAB', 'default', 1, ?, '待清理', ?)",
          root, revision);
    }
    properties.setGcBatchSize(2);
    tx.execute(status -> publicationService.abort(candidate.id(), "失败待回收"));
    ReadPublication next = openCandidate(ISSUE_DOMAIN, baseline, "{\"seal\":\"next\"}");
    long nextRevision = insertCandidateIssueRevision(200L, next.epochId(), next.id());

    ReadMaintenanceService maintenance = new ReadMaintenanceService(jdbcTemplate, publicationService, properties);
    tx.executeWithoutResult(status -> maintenance.collectAbortedFactCandidate());
    assertThat(countCandidateRevisions(candidate.id())).isEqualTo(3);
    assertThat(cleanupPending(candidate.id())).isTrue();
    ReadPublicationService resumed = new ReadPublicationService(new ReadPublicationRepository(jdbcTemplate), properties);
    tx.execute(status -> resumed.cleanupAbortedFactCandidate(candidate.id()));
    assertThat(countCandidateRevisions(candidate.id())).isOne();
    tx.execute(status -> resumed.cleanupAbortedFactCandidate(candidate.id()));
    assertThat(countCandidateRevisions(candidate.id())).isZero();
    assertThat(cleanupPending(candidate.id())).isFalse();
    assertThat(jdbcTemplate.queryForObject("select count(*) from issue_fact_customer_members"
        + " where customer_name = '待清理'", Long.class)).isZero();
    assertThat(countIssueRevision(currentRevision)).isOne();
    assertThat(countIssueRevision(nextRevision)).isOne();
    assertThat(publicationRepository.findDomain(ISSUE_DOMAIN).orElseThrow().currentPublicationId()).isEqualTo(baseline.id());
  }

  @Test
  void test_cleanup_timeout_rolls_back_only_current_batch_and_keeps_abort_committed() {
    insertIssueRoot(100L);
    ReadPublication baseline = bootstrap(admitted());
    ReadPublication candidate = openCandidate(ISSUE_DOMAIN, baseline, "{\"seal\":\"timeout\"}");
    long added = insertCandidateIssueRevision(200L, candidate.epochId(), candidate.id());
    jdbcTemplate.update("insert into issue_fact_customer_members(source_system, source_instance, project_id,"
        + " issue_id, customer_name, issue_fact_revision_id) values ('GITLAB', 'default', 1, 200, '保留关系', ?)", added);
    tx.execute(status -> publicationService.abort(candidate.id(), "首个发布错误"));
    JdbcTemplate failingJdbc = new JdbcTemplate(dataSource) {
      @Override
      public int update(String sql, Object... arguments) {
        if (sql.startsWith("delete from issue_fact where id")) {
          throw new org.springframework.dao.QueryTimeoutException("模拟父修订删除超时");
        }
        return super.update(sql, arguments);
      }
    };
    ReadPublicationService failing = new ReadPublicationService(new ReadPublicationRepository(failingJdbc), properties);

    assertThatThrownBy(() -> tx.execute(status -> failing.cleanupAbortedFactCandidate(candidate.id())))
        .isInstanceOf(org.springframework.dao.QueryTimeoutException.class);
    assertThat(countIssueRevision(added)).isOne();
    assertThat(jdbcTemplate.queryForObject("select count(*) from issue_fact_customer_members"
        + " where issue_fact_revision_id = ?", Long.class, added)).isOne();
    assertThat(cleanupPending(candidate.id())).isTrue();
    assertThat(publicationRepository.findPublication(candidate.id()).orElseThrow().state())
        .isEqualTo(com.data.collection.platform.entity.read.ReadPublicationState.ABORTED);
    tx.execute(status -> publicationService.cleanupAbortedFactCandidate(candidate.id()));
    assertThat(countIssueRevision(added)).isZero();
    assertThat(cleanupPending(candidate.id())).isFalse();
  }

  @Test
  void test_cleanup_rejects_active_and_published_versions_and_pending_constraint_is_enforced() {
    long currentRevision = insertIssueRoot(100L);
    ReadPublication baseline = bootstrap(admitted());
    ReadPublication candidate = openCandidate(ISSUE_DOMAIN, baseline, "{\"seal\":\"protected\"}");
    long candidateRevision = insertCandidateIssueRevision(200L, candidate.epochId(), candidate.id());

    int publishedDeleted = tx.execute(status -> publicationService.cleanupAbortedFactCandidate(baseline.id()));
    int activeDeleted = tx.execute(status -> publicationService.cleanupAbortedFactCandidate(candidate.id()));
    assertThat(publishedDeleted).isZero();
    assertThat(activeDeleted).isZero();
    assertThatThrownBy(() -> jdbcTemplate.update("update read_publications set abort_cleanup_pending = true where id = ?",
        baseline.id())).isInstanceOf(DataAccessException.class);
    assertThat(countIssueRevision(currentRevision)).isOne();
    assertThat(countIssueRevision(candidateRevision)).isOne();
  }

  @Test
  void test_cleanup_scheduler_yields_to_busy_domain_and_completes_empty_candidate_idempotently() throws Exception {
    insertIssueRoot(100L);
    ReadPublication baseline = bootstrap(admitted());
    ReadPublication candidate = openCandidate(ISSUE_DOMAIN, baseline, "{\"seal\":\"empty\"}");
    tx.execute(status -> publicationService.abort(candidate.id(), "空候选中止"));
    ReadMaintenanceService maintenance = new ReadMaintenanceService(jdbcTemplate, publicationService, properties);
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try (java.sql.PreparedStatement lock = connection.prepareStatement(
          "select id from read_publication_domains where id = ? for update")) {
        lock.setObject(1, candidate.domainId());
        try (java.sql.ResultSet rows = lock.executeQuery()) { assertThat(rows.next()).isTrue(); }
        tx.executeWithoutResult(status -> {
          jdbcTemplate.execute("set local lock_timeout = '250ms'");
          maintenance.collectAbortedFactCandidate();
        });
        assertThat(cleanupPending(candidate.id())).isTrue();
      } finally {
        connection.rollback();
      }
    }
    tx.executeWithoutResult(status -> maintenance.collectAbortedFactCandidate());
    assertThat(cleanupPending(candidate.id())).isFalse();
    tx.executeWithoutResult(status -> maintenance.collectAbortedFactCandidate());
    assertThat(publicationRepository.findDomain(ISSUE_DOMAIN).orElseThrow().currentPublicationId()).isEqualTo(baseline.id());
  }

  private long countCandidateRevisions(UUID publicationId) {
    return jdbcTemplate.queryForObject("select count(*) from issue_fact where created_by_publication_id = ?",
        Long.class, publicationId);
  }

  private boolean cleanupPending(UUID publicationId) {
    return jdbcTemplate.queryForObject("select abort_cleanup_pending from read_publications where id = ?",
        Boolean.class, publicationId);
  }

  // ---- 候选可见性：首次、全量、全量删除、普通增量 ----

  @Test
  void test_incremental_candidate_visibility_keeps_untouched_baseline_and_new_revisions() {
    long keptRootRevision = insertIssueRoot(100L);
    long deletedRootRevision = insertIssueRoot(101L);
    ReadPublication v1 = bootstrap(admitted());

    ReadPublication candidate = openCandidate(ISSUE_DOMAIN, v1, "{\"seal\":\"v2\"}");
    long newRevision = insertCandidateIssueRevision(102L, candidate.epochId(), candidate.id());
    recordUpsert(ISSUE_DOMAIN, candidate.id(), 102L, null, newRevision);
    recordDelete(ISSUE_DOMAIN, candidate.id(), 101L, deletedRootRevision);

    CandidateReadContext context = publicationService.candidateContext(candidate.id());
    assertThat(context.opensNewEpoch()).isFalse();
    assertThat(visibleIds(context)).containsExactlyInAnyOrder(keptRootRevision, newRevision);
  }

  @Test
  void test_first_candidate_visibility_reads_only_its_own_revisions() {
    ReadPublication candidate =
        tx.execute(
            status ->
                publicationService.openCandidate(
                    new CandidateOpenRequest(
                        ISSUE_DOMAIN, UUID.randomUUID(), null, "{\"seal\":\"first\"}", 20L, null, "run", 1L, "owner",
                        Instant.now())));
    long added = insertCandidateIssueRevision(100L, candidate.epochId(), candidate.id());
    recordUpsert(ISSUE_DOMAIN, candidate.id(), 100L, null, added);

    CandidateReadContext context = publicationService.candidateContext(candidate.id());
    assertThat(context.opensNewEpoch()).isTrue();
    assertThat(visibleIds(context)).containsExactly(added);
  }

  @Test
  void test_full_candidate_visibility_excludes_base_epoch_and_manual_composition_agrees() {
    insertIssueRoot(100L);
    ReadPublication v1 = bootstrap(admitted());
    ReadPublication candidate =
        tx.execute(
            status ->
                publicationService.openCandidate(
                    new CandidateOpenRequest(
                        ISSUE_DOMAIN, UUID.randomUUID(), v1.id(), "{\"seal\":\"full\"}", 20L, null, "run",
                        1L, "owner", Instant.now())));
    long added = insertCandidateIssueRevision(200L, candidate.epochId(), candidate.id());
    recordUpsert(ISSUE_DOMAIN, candidate.id(), 200L, null, added);

    CandidateReadContext context = publicationService.candidateContext(candidate.id());
    assertThat(context.opensNewEpoch()).isTrue();
    assertThat(visibleIds(context)).as("full candidate contains its new epoch only").containsExactly(added);

    // 交付文档的通用合成方式也必须给出同一结果（基线恒不可见 + 候选新修订）。
    SqlFilter base =
        FactReadVisibility.publishedOnBase("f", context)
            .and(FactReadVisibility.candidateBaseExclusion("f", context));
    SqlFilter own = FactReadVisibility.candidateRevisions("f", context);
    List<Long> manual =
        jdbcTemplate.query(
            "select f.id from issue_fact f where (" + base.sql() + ") or (" + own.sql() + ")",
            (rs, num) -> rs.getLong("id"),
            concat(base.args(), own.args()));
    assertThat(manual).containsExactly(added);
  }

  @Test
  void test_full_candidate_with_only_deletions_reads_nothing() {
    insertIssueRoot(100L);
    insertIssueRoot(101L);
    ReadPublication v1 = bootstrap(admitted());
    ReadPublication candidate =
        tx.execute(
            status ->
                publicationService.openCandidate(
                    new CandidateOpenRequest(
                        ISSUE_DOMAIN, UUID.randomUUID(), v1.id(), "{\"seal\":\"full-empty\"}", 20L, null,
                        "run", 1L, "owner", Instant.now())));
    recordDelete(ISSUE_DOMAIN, candidate.id(), 100L, revisionIdOfIssue(100L));
    recordDelete(ISSUE_DOMAIN, candidate.id(), 101L, revisionIdOfIssue(101L));

    CandidateReadContext context = publicationService.candidateContext(candidate.id());
    assertThat(context.opensNewEpoch()).isTrue();
    assertThat(visibleIds(context)).as("empty full candidate exposes no old-epoch roots").isEmpty();
  }

  // ---- 根清单：完整业务身份、幂等、域边界 ----

  @Test
  void test_root_manifest_distinguishes_source_system_and_project() {
    ReadPublication candidate =
        tx.execute(
            status ->
                publicationService.openCandidate(
                    new CandidateOpenRequest(
                        MR_DOMAIN, UUID.randomUUID(), null, "{\"seal\":\"mr\"}", 20L, null, "run", 1L, "owner",
                        Instant.now())));
    tx.execute(
        status ->
            publicationService.recordFactRoot(
                new FactRootEntry(candidate.id(), "GITLAB", "default", "MERGE_REQUEST", 1L, 100L, 10L,
                    null, 11L, FactRootOperation.UPSERT)));
    tx.execute(
        status ->
            publicationService.recordFactRoot(
                new FactRootEntry(candidate.id(), "LEGACY_PLATFORM", "default", "MERGE_REQUEST", 2L, 100L,
                    20L, null, 22L, FactRootOperation.UPSERT)));

    assertThat(publicationRepository.countFactRoots(candidate.id())).isEqualTo(2L);
  }

  @Test
  void test_root_manifest_registration_is_idempotent_and_never_lowers_watermark() {
    ReadPublication candidate =
        tx.execute(
            status ->
                publicationService.openCandidate(
                    new CandidateOpenRequest(
                        ISSUE_DOMAIN, UUID.randomUUID(), null, "{\"seal\":\"first\"}", 30L, null, "run", 1L, "owner",
                        Instant.now())));
    recordUpsert(ISSUE_DOMAIN, candidate.id(), 100L, null, 111L);
    recordUpsert(ISSUE_DOMAIN, candidate.id(), 100L, null, 111L);
    assertThat(publicationRepository.countFactRoots(candidate.id())).isEqualTo(1L);

    recordRootAtWatermark(candidate.id(), 100L, 5L);
    assertThat(rootWatermark(candidate.id(), 100L))
        .as("lower watermark must not overwrite")
        .isEqualTo(20L);

    recordRootAtWatermark(candidate.id(), 100L, 25L);
    assertThat(rootWatermark(candidate.id(), 100L)).isEqualTo(25L);
  }

  @Test
  void test_root_manifest_entry_must_belong_to_candidate_domain() {
    ReadPublication candidate =
        tx.execute(
            status ->
                publicationService.openCandidate(
                    new CandidateOpenRequest(
                        MR_DOMAIN, UUID.randomUUID(), null, "{\"seal\":\"mr\"}", 20L, null, "run", 1L, "owner",
                        Instant.now())));

    assertThatThrownBy(
            () ->
                tx.execute(
                    status ->
                        publicationService.recordFactRoot(
                            new FactRootEntry(candidate.id(), "GITLAB", "default", "ISSUE", 1L, 100L, 10L,
                                null, 11L, FactRootOperation.UPSERT))))
        .isInstanceOf(IllegalStateException.class);
  }

  // ---- 初始版本 ----

  @Test
  void test_bootstrap_registers_existing_facts_once_and_is_idempotent() {
    long first = insertIssueRoot(100L);
    long second = insertIssueRoot(101L);

    ReadPublication v1 = bootstrap(admitted());
    assertThat(v1).isNotNull();
    assertThat(visibleFrom(first)).isEqualTo(1L);
    assertThat(visibleFrom(second)).isEqualTo(1L);
    assertThat(visibleIssueIds(v1.epochId(), 1L)).containsExactlyInAnyOrder(first, second);

    assertThat(bootstrap(admitted())).isNull();
    Long publicationCount =
        jdbcTemplate.queryForObject(
            "select count(*) from read_publications where domain_id = ?", Long.class, v1.domainId());
    assertThat(publicationCount).isEqualTo(1L);
  }

  @Test
  void test_bootstrap_refuses_when_verification_is_not_admissible() {
    long revision = insertIssueRoot(100L);
    ReadPublicationDomainKey otherKey = ReadPublicationDomainKey.gitlabFact("other", FactType.ISSUE);

    ReadPublication result =
        tx.execute(
            status ->
                publicationService.bootstrapInitialVersion(
                    otherKey,
                    "GITLAB",
                    Instant.parse("2026-10-01T00:00:00Z"),
                    "{\"seal\":\"v1\"}",
                    10L,
                    key -> InitialVersionVerification.VerificationResult.refused("存在未发布根")));

    assertThat(result).isNull();
    assertThat(publicationRepository.findDomain(otherKey)).isEmpty();
    assertThat(visibleFrom(revision)).isNull();
  }

  @Test
  void test_default_verifier_refuses_facts_without_completeness_evidence() {
    insertIssueRoot(100L);

    ReadPublication result =
        tx.execute(
            status ->
                publicationService.bootstrapInitialVersion(
                    ISSUE_DOMAIN,
                    "GITLAB",
                    Instant.parse("2026-10-01T00:00:00Z"),
                    "{\"seal\":\"v1\"}",
                    10L,
                    defaultVerifier()));

    assertThat(result).as("no dependency or publication evidence exists").isNull();
    assertThat(publicationRepository.findDomain(ISSUE_DOMAIN)).isEmpty();
  }

  @Test
  void test_default_verifier_accepts_when_completeness_evidence_exists() {
    long revision = insertMergeRequestRoot(100L);
    insertConfigRunAndFamilyEvidence(FactType.MERGE_REQUEST);

    ReadPublication v1 =
        tx.execute(
            status ->
                publicationService.bootstrapInitialVersion(
                    MR_DOMAIN,
                    "GITLAB",
                    Instant.parse("2026-10-01T00:00:00Z"),
                    "{\"seal\":\"v1\"}",
                    10L,
                    defaultVerifier()));

    assertThat(v1).isNotNull();
    assertThat(visibleFromMergeRequest(revision)).isEqualTo(1L);
    assertThat(publicationRepository.findDomain(MR_DOMAIN)).isPresent();
  }

  @Test
  void test_default_verifier_refuses_while_fact_build_task_is_in_flight() {
    insertMergeRequestRoot(100L);
    insertConfigRunAndFamilyEvidence(FactType.MERGE_REQUEST);
    jdbcTemplate.update(
        """
        insert into fact_build_tasks(run_id, scope, status, trigger_type, source_instance, fact_type)
        values ('run-1', 'scope-1', 'RUNNING', 'MANUAL', 'default', 'MERGE_REQUEST')
        """);

    ReadPublication result =
        tx.execute(
            status ->
                publicationService.bootstrapInitialVersion(
                    MR_DOMAIN,
                    "GITLAB",
                    Instant.parse("2026-10-01T00:00:00Z"),
                    "{\"seal\":\"v1\"}",
                    10L,
                    defaultVerifier()));

    assertThat(result).as("in-flight production must block initial version adoption").isNull();
  }

  @Test
  void test_child_view_retains_parent_version_after_publication_and_parent_release() {
    long oldRevision = insertIssueRoot(100L);
    ReadPublication first = bootstrap(admitted());
    ReadView parent = tx.execute(status -> publishedReadService.acquireView(issueViewRequest()));
    ReadPublication candidate = openCandidate(ISSUE_DOMAIN, first, "{}");
    recordDelete(ISSUE_DOMAIN, candidate.id(), 100L, oldRevision);
    publishReady(candidate.id(), "owner");
    UUID clientId = UUID.randomUUID();
    ReadView child = publishedReadService.forkView(parent.viewId(), "tester", "session-1", parent.pageKey(), "detail-scope", clientId);
    assertThat(child.requireDomain(ISSUE_DOMAIN).publicationId()).isEqualTo(first.id());
    assertThat(child.businessDate()).isEqualTo(parent.businessDate());
    assertThat(child.rebindDeadline()).isEqualTo(parent.rebindDeadline());
    assertThat(publishedReadService.forkView(parent.viewId(), "tester", "session-1", parent.pageKey(), "detail-scope", clientId).viewId())
        .isEqualTo(child.viewId());
    tx.executeWithoutResult(status -> publishedReadService.releaseView(parent.viewId(), "tester", "session-1"));
    properties.setRetirementGraceMinutes(-1);
    tx.executeWithoutResult(status -> publicationService.collectGarbage(ISSUE_DOMAIN));
    assertThat(countIssueRevision(oldRevision)).isEqualTo(1L);
    Long count = publishedReadService.withinPublishedRead(child, fixed -> countIssueRevision(oldRevision));
    assertThat(count).isEqualTo(1L);
    tx.executeWithoutResult(status -> publishedReadService.releaseView(child.viewId(), "tester", "session-1"));
    tx.executeWithoutResult(status -> publicationService.collectGarbage(ISSUE_DOMAIN));
    assertThat(countIssueRevision(oldRevision)).isZero();
  }

  @Test
  void test_cross_page_child_keeps_parent_point_and_rejects_replay_to_another_page() {
    insertIssueRoot(100L);
    ReadPublication baseline = bootstrap(admitted());
    ReadView parent = tx.execute(status -> publishedReadService.acquireView(issueViewRequest()));
    UUID client = UUID.randomUUID();
    ReadView child = publishedReadService.forkView(parent.viewId(), "tester", "session-1", "system-test-issues", "scope", client);
    assertThat(child.pageKey()).isEqualTo("system-test-issues");
    assertThat(child.requireDomain(ISSUE_DOMAIN).publicationId()).isEqualTo(baseline.id());
    assertThatThrownBy(() -> publishedReadService.forkView(parent.viewId(), "tester", "session-1", "another-page", "scope", client))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void test_definition_change_without_fact_delta_requests_one_full_run_and_retains_old_point() {
    insertConfigRunAndFamilyEvidence(FactType.ISSUE);
    insertIssueRoot(100L);
    ReadPublication baseline = bootstrap(admitted());
    var configs = org.mockito.Mockito.mock(com.data.collection.platform.service.GitlabConfigService.class);
    var submissions = org.mockito.Mockito.mock(com.data.collection.platform.service.sync.SyncRunSubmissionService.class);
    var config = new com.data.collection.platform.entity.GitlabSyncConfig();
    config.setId(1L);
    org.mockito.Mockito.when(configs.getConfigById(1L)).thenReturn(config);
    var worker = new ReadDefinitionRefreshWorker(jdbcTemplate, publicationRepository, configs, submissions);
    tx.executeWithoutResult(status -> worker.requestChangedDefinitions());
    org.mockito.Mockito.verifyNoInteractions(submissions);
    jdbcTemplate.update("insert into module_dictionary(standard_module_name, alias_name) values ('new-module', 'new-alias')");
    tx.executeWithoutResult(status -> worker.requestChangedDefinitions());
    tx.executeWithoutResult(status -> worker.requestChangedDefinitions());
    org.mockito.Mockito.verify(submissions).submitFactRefresh(config, true, "读取定义已变更，构建完整事实版本");
    assertThat(jdbcTemplate.queryForObject("select full_publication_requested from source_fact_publication_states where config_id = 1", Boolean.class)).isTrue();
    assertThat(publicationRepository.findCurrentPublication(baseline.domainId()).orElseThrow().id()).isEqualTo(baseline.id());
    assertThat(jdbcTemplate.queryForObject("select count(*) from fact_change_heads", Long.class)).isZero();
  }

  @Test
  void test_named_dynamic_queries_exclude_candidates_and_history_and_expand_list_parameters() {
    long original = insertIssueRoot(100L);
    jdbcTemplate.update("update issue_fact set assignee_name = '旧完整成员' where id = ?",original);
    ReadPublication baseline = bootstrap(admitted());
    ReadView oldView = tx.execute(status -> publishedReadService.acquireView(issueViewRequest()));
    ReadPublication candidate = openCandidate(ISSUE_DOMAIN,baseline,"{}");
    long revised = insertCandidateIssueRevision(100L,candidate.epochId(),candidate.id());
    jdbcTemplate.update("update issue_fact set assignee_name = '新完整成员' where id = ?",revised);
    recordUpsert(ISSUE_DOMAIN,candidate.id(),100L,original,revised);
    var queries = new VersionedNamedReadSql(jdbcTemplate);
    var params = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource("members",List.of("旧完整成员","新完整成员"));
    java.util.function.Function<ReadView,List<String>> read = fixed -> queries.query(
        new com.data.collection.platform.entity.read.FactReadSelection(List.of((PublishedReadContext)fixed.requireDomain(ISSUE_DOMAIN)),fixed.businessDate()),
        "select assignee_name from issue_fact where assignee_name in (:members) order by assignee_name",params,(rs,row) -> rs.getString(1));
    assertThat(publishedReadService.withinPublishedRead(oldView,read)).containsExactly("旧完整成员");
    publishReady(candidate.id(),"owner");
    assertThat(publishedReadService.withinPublishedRead(oldView,read)).containsExactly("旧完整成员");
    ReadView current = tx.execute(status -> publishedReadService.acquireView(issueViewRequest()));
    assertThat(publishedReadService.withinPublishedRead(current,read)).containsExactly("新完整成员");
  }

  @Test
  void test_child_view_rejects_wrong_session_scope_replay_and_expired_history_deadline() {
    insertIssueRoot(100L);
    bootstrap(admitted());
    ReadView parent = tx.execute(status -> publishedReadService.acquireView(issueViewRequest()));
    assertThatThrownBy(() -> publishedReadService.forkView(parent.viewId(), "other", "session-1", parent.pageKey(), "detail", UUID.randomUUID()))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    assertThatThrownBy(() -> publishedReadService.forkView(parent.viewId(), "tester", "other-session", parent.pageKey(), "detail", UUID.randomUUID()))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    UUID clientId = UUID.randomUUID();
    publishedReadService.forkView(parent.viewId(), "tester", "session-1", parent.pageKey(), "detail", clientId);
    assertThatThrownBy(() -> publishedReadService.forkView(parent.viewId(), "tester", "session-1", parent.pageKey(), "changed", clientId))
        .isInstanceOf(IllegalArgumentException.class);
    jdbcTemplate.update("update read_view_contexts set rebind_deadline = current_timestamp - interval '1 second' where id = ?", parent.viewId());
    assertThatThrownBy(() -> publishedReadService.forkView(parent.viewId(), "tester", "session-1", parent.pageKey(), "detail", UUID.randomUUID()))
        .isInstanceOf(ReadViewException.class);
  }

  @Test
  void test_acquire_view_binds_current_published_version_and_rejects_unknown_domain() {
    insertIssueRoot(100L);
    ReadPublication v1 = bootstrap(admitted());

    ReadView view = tx.execute(status -> publishedReadService.acquireView(issueViewRequest()));
    assertThat(view.requireDomain(ISSUE_DOMAIN).publicationId()).isEqualTo(v1.id());

    assertThatThrownBy(
            () ->
                tx.execute(
                    status ->
                        publishedReadService.acquireView(
                            new AcquireReadViewRequest(
                                "tester", "s", "page", "scope", LocalDate.of(2026, 10, 1), "rule",
                                Set.of(ReadPublicationDomainKey.gitlabFact("missing", FactType.ISSUE)), java.util.UUID.randomUUID()))))
        .isInstanceOf(ReadViewException.class);
  }

  @Test
  void test_gc_rechecks_reference_after_qualification_before_lock() {
    long oldRevision = insertIssueRoot(100L);
    ReadPublication v1 = bootstrap(admitted());
    ReadView view = tx.execute(status -> publishedReadService.acquireView(issueViewRequest()));
    ReadPublication candidate = openCandidate(ISSUE_DOMAIN, v1, "{}");
    recordDelete(ISSUE_DOMAIN, candidate.id(), 100L, oldRevision);
    publishReady(candidate.id(), "owner");
    properties.setRetirementGraceMinutes(-1);

    // 模拟到期前启动的续约在 GC 初选后、取排他锁前完成。
    // 使用受控时钟，无需等待真实租约到期。
    Instant renewalStartedAt = Instant.now().minusSeconds(60);
    jdbcTemplate.update("update read_view_contexts set created_at = ?, expires_at = ? where id = ?",
        java.sql.Timestamp.from(renewalStartedAt.minusSeconds(60)),
        java.sql.Timestamp.from(renewalStartedAt.plusSeconds(30)), view.viewId());
    ReadPublicationRepository observed = org.mockito.Mockito.spy(publicationRepository);
    java.util.concurrent.atomic.AtomicBoolean renewed = new java.util.concurrent.atomic.AtomicBoolean();
    org.mockito.Mockito.doAnswer(invocation -> {
      if (renewed.compareAndSet(false, true)) {
        try (org.mockito.MockedStatic<Instant> clock =
            org.mockito.Mockito.mockStatic(Instant.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
          clock.when(Instant::now).thenReturn(renewalStartedAt);
          TransactionTemplate renewalTx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
          renewalTx.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
          renewalTx.execute(status -> publishedReadService.renewView(view.viewId(), "tester", "session-1"));
        }
      }
      return invocation.callRealMethod();
    }).when(observed).lockPublicationsForUpdateSkippingLocked(org.mockito.ArgumentMatchers.anyCollection());

    ReadPublicationService observedService = new ReadPublicationService(observed, properties);
    tx.execute(status -> observedService.collectGarbage(ISSUE_DOMAIN));
    assertThat(jdbcTemplate.queryForObject(
        "select expires_at > current_timestamp from read_view_contexts where id = ?",
        Boolean.class, view.viewId())).isTrue();
    assertThat(countIssueRevision(oldRevision)).as("renewed live view must retain its content").isEqualTo(1L);
  }

  @Test
  void test_bootstrap_stable_boundary_prevents_writer_after_verification() {
    insertMergeRequestRoot(100L);
    insertConfigRunAndFamilyEvidence(FactType.MERGE_REQUEST);
    InitialVersionVerification verification = key -> {
      InitialVersionVerification.VerificationResult result = defaultVerifier().verify(key);
      assertThat(result.admissible()).isTrue();
      try (Connection producer = dataSource.getConnection()) {
        producer.setAutoCommit(false);
        try (java.sql.Statement writer = producer.createStatement()) {
          // NOWAIT 确定性验证写入冲突，无延时或重试。
          java.sql.SQLException conflict = Assertions.assertThrows(java.sql.SQLException.class,
              () -> writer.execute("lock table fact_build_tasks, fact_change_heads, merge_request_fact "
                  + "in row exclusive mode nowait"));
          assertThat(conflict.getSQLState()).isEqualTo("55P03");
          producer.rollback();
        }
      } catch (java.sql.SQLException error) {
        throw new IllegalStateException(error);
      }
      return result;
    };
    ReadPublication adopted = tx.execute(status -> publicationService.bootstrapInitialVersion(
        MR_DOMAIN, "GITLAB", Instant.parse("2026-10-01T00:00:00Z"), "{}", 10L, verification));
    assertThat(adopted).isNotNull();
    // 接管事务结束后立即释放栅栏，普通生产写入恢复。
    jdbcTemplate.update("update merge_request_fact set title = 'writer after commit' where merge_request_id = 100");
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"row exclusive", "share update exclusive"})
  void test_bootstrap_busy_input_refuses_without_writes_and_keeps_outer_transaction_usable(String lockMode)
      throws Exception {
    insertMergeRequestRoot(100L);
    insertConfigRunAndFamilyEvidence(FactType.MERGE_REQUEST);
    try (Connection producer = dataSource.getConnection()) {
      producer.setAutoCommit(false);
      try (java.sql.Statement writer = producer.createStatement()) {
        // 后一种是 VACUUM/ANALYZE 的维护锁；安装同样必须让位，不能把它伪装成已接管。
        writer.execute("lock table merge_request_fact in " + lockMode + " mode");
        tx.executeWithoutResult(status -> {
          assertThat(publicationService.bootstrapInitialVersion(MR_DOMAIN, "GITLAB",
              Instant.parse("2026-10-01T00:00:00Z"), "{}", 10L, defaultVerifier())).isNull();
          assertThat(publicationRepository.findDomain(MR_DOMAIN)).isEmpty();
          assertThat(jdbcTemplate.queryForObject("select 1", Integer.class)).isEqualTo(1);
        });
        producer.rollback();
      }
    }
  }
  @Test
  void test_bootstrap_rejects_missing_required_dependency_evidence() {
    insertMergeRequestRoot(100L);
    insertConfigRunAndFamilyEvidence(FactType.MERGE_REQUEST);
    jdbcTemplate.update("delete from source_fact_dependency_states where source_table <> 'merge_requests'");
    // 保留一条 merge_requests 依赖，真实目录还要求项目、人员、指标与标签等。
    assertThat(defaultVerifier().verify(MR_DOMAIN).admissible())
        .as("one READY dependency is not evidence for the complete dependency set").isFalse();
  }

  @Test
  void test_legacy_project_zero_identity_remains_supported() {
    long revision = insertMergeRequestRoot(100L);
    jdbcTemplate.update(
        "update merge_request_fact set source_system = 'LEGACY_PLATFORM', project_id = 0 where id = ?", revision);
    ReadPublication base = tx.execute(status -> publicationService.bootstrapInitialVersion(
        MR_DOMAIN, "LEGACY_PLATFORM", Instant.parse("2026-10-01T00:00:00Z"), "{}", 10L, admitted()));
    ReadPublication candidate = openCandidate(MR_DOMAIN, base, "{}");
    Assertions.assertDoesNotThrow(() -> tx.execute(status -> publicationService.recordFactRoot(
        new FactRootEntry(candidate.id(), "LEGACY_PLATFORM", "default", "MERGE_REQUEST",
            0L, 100L, 20L, revision, null, FactRootOperation.DELETE))));
  }
  // ---- 辅助 ----

  @Test
  void test_issue_customer_alias_change_does_not_require_merge_request_rebuild() {
    insertMergeRequestRoot(100L);
    ReadPublication base = tx.execute(status -> publicationService.bootstrapInitialVersion(
        MR_DOMAIN, "GITLAB", Instant.now(), "{}", 0L, admitted()));
    jdbcTemplate.update("insert into issue_customer_name_aliases(alias_name, canonical_name) values ('p2-test-alias', 'p2-test-customer')");
    try {
      UUID definition = publicationRepository.freezeCurrentFactDefinitions("default");
      Assertions.assertDoesNotThrow(() -> publicationRepository.requireReusableCalculationDefinitions(
          base.id(), definition, MR_DOMAIN));
    } finally {
      jdbcTemplate.update("delete from issue_customer_name_aliases where alias_name = 'p2-test-alias'");
    }
  }

  @Test
  void test_integration_phase_end_change_requires_complete_fact_rebuild() {
    Long catalog = jdbcTemplate.queryForObject("""
        insert into issue_scope_catalogs(project_id, project_name, dimension)
        values (987654, 'p2-calendar', 'TESTING_PHASE') returning id
        """, Long.class);
    try {
      Long group = jdbcTemplate.queryForObject("""
          insert into issue_scope_groups(catalog_id, business_key, display_name)
          values (?, 'p2-calendar', 'p2-calendar') returning id
          """, Long.class, catalog);
      Long member = jdbcTemplate.queryForObject("""
          insert into issue_scope_members(catalog_id, group_id, source_value, display_name, active_from, active_until)
          values (?, ?, '集成测试P2', '集成测试P2', timestamp '2026-10-01', timestamp '2026-10-10') returning id
          """, Long.class, catalog, group);
      jdbcTemplate.update("""
          insert into integration_test_fact(source_system, source_instance, project_id, issue_id, issue_iid, title)
          values ('GITLAB', 'default', 987654, 100, 100, 'p2-calendar-fact')
          """);
      ReadPublicationDomainKey key = ReadPublicationDomainKey.gitlabFact("default", FactType.INTEGRATION_TEST);
      ReadPublication base = tx.execute(status -> publicationService.bootstrapInitialVersion(
          key, "GITLAB", Instant.now(), "{}", 0L, admitted()));
      jdbcTemplate.update("update issue_scope_members set active_until = timestamp '2026-10-05' where id = ?", member);
      UUID definition = publicationRepository.freezeCurrentFactDefinitions("default");
      assertThatThrownBy(() -> publicationRepository.requireReusableCalculationDefinitions(base.id(), definition, key))
          .isInstanceOf(com.data.collection.platform.service.FactCandidateBusyException.class)
          .hasMessageContaining("需要完整重建");
    } finally {
      jdbcTemplate.update("delete from issue_scope_catalogs where id = ?", catalog);
    }
  }

  private InitialVersionVerification admitted() {
    return key -> InitialVersionVerification.VerificationResult.accepted();
  }

  private InitialVersionVerification defaultVerifier() {
    return new SyncFactPublicationVerification(
        new SyncFactPublicationStateService(jdbcTemplate, null));
  }

  private ReadPublication bootstrap(InitialVersionVerification verification) {
    return tx.execute(
        status ->
            publicationService.bootstrapInitialVersion(
                ISSUE_DOMAIN, "GITLAB", Instant.parse("2026-10-01T00:00:00Z"), "{\"seal\":\"v1\"}", 10L,
                verification));
  }

  private ReadPublication openCandidate(
      ReadPublicationDomainKey key, ReadPublication base, String seal) {
    return tx.execute(
        status ->
            publicationService.openCandidate(
                new CandidateOpenRequest(
                    key, base.epochId(), base.id(), seal, 20L, null, "run", 1L, "owner",
                    Instant.parse("2026-10-02T00:00:00Z"))));
  }

  private void recordUpsert(
      ReadPublicationDomainKey key, UUID candidateId, long rootId, Long previousRevision, long nextRevision) {
    tx.execute(
        status ->
            publicationService.recordFactRoot(
                new FactRootEntry(
                    candidateId, "GITLAB", key.sourceInstance(), key.factType(), 1L, rootId, 20L,
                    previousRevision, nextRevision, FactRootOperation.UPSERT)));
  }

  private void recordDelete(
      ReadPublicationDomainKey key, UUID candidateId, long rootId, long previousRevision) {
    tx.execute(
        status ->
            publicationService.recordFactRoot(
                new FactRootEntry(
                    candidateId, "GITLAB", key.sourceInstance(), key.factType(), 1L, rootId, 21L,
                    previousRevision, null, FactRootOperation.DELETE)));
  }

  private void recordRootAtWatermark(UUID candidateId, long rootId, long watermark) {
    tx.execute(
        status ->
            publicationService.recordFactRoot(
                new FactRootEntry(
                    candidateId, "GITLAB", "default", "ISSUE", 1L, rootId, watermark, null, 111L,
                    FactRootOperation.UPSERT)));
  }

  private ReadPublication publishReady(UUID candidateId, String owner) {
    tx.execute(status -> publicationService.markProjecting(candidateId, owner));
    tx.execute(status -> publicationService.markReadyToPublish(candidateId, owner));
    return tx.execute(status -> publicationService.publish(candidateId, owner));
  }

  private AcquireReadViewRequest issueViewRequest() {
    return new AcquireReadViewRequest(
        "tester", "session-1", "customer-issue-records", "scope-1", LocalDate.of(2026, 10, 1), "rule-1",
        Set.of(ISSUE_DOMAIN), java.util.UUID.randomUUID());
  }

  private void insertConfigRunAndFamilyEvidence(FactType factType) {
    jdbcTemplate.update(
        """
        insert into gitlab_sync_configs(id, db_name, db_username, db_password)
        values (1, 'ods', 'ods', 'ods')
        """);
    jdbcTemplate.update(
        """
        insert into sync_runs(id, run_id, config_id, source_instance, run_type, trigger_type, status,
            exclusive_scope)
        values (1, 'run-1', 1, 'default', 'FULL_SYNC', 'MANUAL', 'SUCCESS', 'default')
        """);
    jdbcTemplate.update(
        """
        insert into source_fact_publication_states(config_id, source_instance, fact_type,
            latest_mirror_run_id, readiness_status, full_publication_requested)
        values (1, 'default', ?, 1, 'READY', false)
        """,
        factType.name());
    for (String sourceTable : com.data.collection.platform.service.GitlabFactDependencyCatalog
        .require(factType).requiredTables(new com.data.collection.platform.entity.GitlabSyncConfig())) {
      jdbcTemplate.update(
          """
          insert into source_fact_dependency_states(config_id, source_instance, fact_type, source_table,
              latest_mirror_run_id, readiness_status)
          values (1, 'default', ?, ?, 1, 'READY')
          """, factType.name(), sourceTable);
    }
  }

  private long insertIssueRoot(long issueId) {
    Long id =
        jdbcTemplate.queryForObject(
            """
            insert into issue_fact(source_system, source_instance, project_id, issue_id, issue_iid, title)
            values ('GITLAB', 'default', 1, ?, ?, 'root')
            returning id
            """,
            Long.class,
            issueId,
            issueId);
    return id == null ? 0L : id;
  }

  private long insertMergeRequestRoot(long mergeRequestId) {
    Long id =
        jdbcTemplate.queryForObject(
            """
            insert into merge_request_fact(source_system, source_instance, project_id, merge_request_id,
                merge_request_iid, title)
            values ('GITLAB', 'default', 1, ?, ?, 'mr-root')
            returning id
            """,
            Long.class,
            mergeRequestId,
            mergeRequestId);
    return id == null ? 0L : id;
  }

  private long insertCandidateIssueRevision(long issueId, UUID epochId, UUID publicationId) {
    Long id =
        jdbcTemplate.queryForObject(
            """
            insert into issue_fact(source_system, source_instance, project_id, issue_id, issue_iid, title,
                publication_epoch_id, created_by_publication_id)
            values ('GITLAB', 'default', 1, ?, ?, 'revised', ?, ?)
            returning id
            """,
            Long.class,
            issueId,
            issueId,
            epochId,
            publicationId);
    return id == null ? 0L : id;
  }

  private long insertCandidateMergeRequestRevision(long mergeRequestId, UUID epochId, UUID publicationId) {
    Long id =
        jdbcTemplate.queryForObject(
            """
            insert into merge_request_fact(source_system, source_instance, project_id, merge_request_id,
                merge_request_iid, title, publication_epoch_id, created_by_publication_id)
            values ('GITLAB', 'default', 1, ?, ?, 'mr-revised', ?, ?)
            returning id
            """,
            Long.class,
            mergeRequestId,
            mergeRequestId,
            epochId,
            publicationId);
    return id == null ? 0L : id;
  }

  private void insertCustomerMember(long issueId, String customerName) {
    jdbcTemplate.update(
        """
        insert into issue_fact_customer_members(source_system, source_instance, project_id, issue_id,
            customer_name)
        values ('GITLAB', 'default', 1, ?, ?)
        """,
        issueId,
        customerName);
  }

  private void insertCommitFact(long mergeRequestId, Long parentRevisionId, String commitSha) {
    jdbcTemplate.update(
        """
        insert into merge_request_commit_fact(source_system, source_instance, project_id, merge_request_id,
            commit_sha, committed_at_source, merge_request_revision_id)
        values ('GITLAB', 'default', 1, ?, ?, current_timestamp, ?)
        """,
        mergeRequestId,
        commitSha,
        parentRevisionId);
  }

  private void holdKeyShare(Connection holder, UUID publicationId) throws Exception {
    try (java.sql.PreparedStatement lock =
        holder.prepareStatement("select id from read_publications where id = ? for key share")) {
      lock.setObject(1, publicationId);
      lock.executeQuery().close();
    }
  }

  private List<Long> visibleIds(FactReadQueryContext context) {
    SqlFilter filter = FactReadVisibility.visibleFacts("f", context);
    return jdbcTemplate.query(
        "select f.id from " + context.table().tableName() + " f where " + filter.sql(),
        (rs, rowNum) -> rs.getLong("id"),
        filter.args().toArray());
  }

  private Long visibleFrom(long revisionId) {
    return jdbcTemplate.queryForObject(
        "select visible_from from issue_fact where id = ?", Long.class, revisionId);
  }

  private long revisionIdOfIssue(long issueId) {
    Long id =
        jdbcTemplate.queryForObject(
            "select min(id) from issue_fact where issue_id = ?", Long.class, issueId);
    return id == null ? 0L : id;
  }

  private Long visibleFromMergeRequest(long revisionId) {
    return jdbcTemplate.queryForObject(
        "select visible_from from merge_request_fact where id = ?", Long.class, revisionId);
  }

  private Long visibleTo(long revisionId) {
    return jdbcTemplate.queryForObject(
        "select visible_to from issue_fact where id = ?", Long.class, revisionId);
  }

  private long countIssueRevision(long revisionId) {
    Long count =
        jdbcTemplate.queryForObject("select count(*) from issue_fact where id = ?", Long.class, revisionId);
    return count == null ? 0L : count;
  }

  private long countMergeRequestRevision(long revisionId) {
    Long count =
        jdbcTemplate.queryForObject(
            "select count(*) from merge_request_fact where id = ?", Long.class, revisionId);
    return count == null ? 0L : count;
  }

  private Long memberBoundRevision(String customerName) {
    return jdbcTemplate
        .query(
            """
            select issue_fact_revision_id from issue_fact_customer_members
             where customer_name = ? and issue_fact_revision_id is not null
            """,
            (rs, rowNum) -> rs.getLong("issue_fact_revision_id"),
            customerName)
        .stream()
        .findFirst()
        .orElse(null);
  }

  private Long commitBoundRevision(String commitSha) {
    return jdbcTemplate
        .query(
            """
            select merge_request_revision_id from merge_request_commit_fact
             where commit_sha = ? and merge_request_revision_id is not null
            """,
            (rs, rowNum) -> rs.getLong("merge_request_revision_id"),
            commitSha)
        .stream()
        .findFirst()
        .orElse(null);
  }

  private Long memberCountInVersion(UUID epochId, long sequence) {
    return jdbcTemplate.queryForObject(
        """
        select count(*)
          from issue_fact_customer_members m
          join issue_fact f on f.id = m.issue_fact_revision_id
         where f.publication_epoch_id = ?
           and f.visible_from is not null
           and f.visible_from <= ?
           and (f.visible_to is null or ? < f.visible_to)
           and m.customer_name = '客户A'
        """,
        Long.class,
        epochId,
        sequence,
        sequence);
  }

  private Long rootWatermark(UUID candidateId, long rootId) {
    return jdbcTemplate.queryForObject(
        """
        select captured_change_version from read_publication_fact_roots
         where publication_id = ? and source_system = 'GITLAB' and source_instance = 'default'
           and fact_type = 'ISSUE' and project_id = 1 and root_id = ?
        """,
        Long.class,
        candidateId,
        rootId);
  }

  private UUID currentPublicationId() {
    List<UUID> ids =
        jdbcTemplate.query(
            "select current_publication_id from read_publication_domains",
            (rs, rowNum) -> rs.getObject("current_publication_id", UUID.class));
    return ids.stream().filter(java.util.Objects::nonNull).findFirst().orElse(null);
  }

  private List<Long> visibleIssueIds(UUID epochId, long sequence) {
    PublishedReadContext context =
        new PublishedReadContext(
            "default", PublicationFactTable.ISSUE, epochId, sequence, UUID.randomUUID(), Instant.now());
    return visibleIds(context);
  }

  private static Object[] concat(List<Object> first, List<Object> second) {
    java.util.ArrayList<Object> merged = new java.util.ArrayList<>(first);
    merged.addAll(second);
    return merged.toArray();
  }
}
