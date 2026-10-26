package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.data.collection.platform.domain.form.FormTemplateSpecCatalog;
import com.data.collection.platform.entity.form.FormActorContext;
import com.data.collection.platform.entity.form.admin.FormAdminViews;
import com.data.collection.platform.entity.form.admin.FormBindingRequest;
import com.data.collection.platform.entity.form.admin.FormDraftUpdateRequest;
import com.data.collection.platform.entity.form.admin.FormPublicationCreateRequest;
import com.data.collection.platform.entity.form.admin.FormScopeCreateRequest;
import com.data.collection.platform.service.form.FormPublicationService;
import com.data.collection.platform.service.form.FormPublicationTxService;
import com.data.collection.platform.service.form.FormTemplateAdminService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
    properties = {
      "platform.background-jobs.enabled=false",
      "platform.gitlab-mirror.scheduler-enabled=false",
      "platform.gitlab-mirror.delete-reconciliation-enabled=false",
      "platform.gitlab-mirror.customer-issue-delay-pre-writeback-sync-enabled=false",
      "platform.gitlab-mirror.customer-issue-delay-writeback-worker-enabled=false",
      "platform.gitlab-mirror.code-review-metric-enrichment-enabled=false",
      "platform.review-data.search-index-backfill-enabled=false",
      "platform.auth.provider=local",
      "platform.auth.secure-config-required=false"
    })
class FormInitialMigrationAdminTest {

  private static final String GITLAB_URL = "http://gitlab.test.local:18080";
  private static final FormActorContext ACTOR =
      new FormActorContext("employee-001", "员工一", Set.of());

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("qaflex")
          .withUsername("qaflex")
          .withPassword("isolated-test");

  @DynamicPropertySource
  static void registerIsolatedDatabase(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl() + "?currentSchema=public");
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.flyway.schemas", () -> "public");
    registry.add("spring.flyway.default-schema", () -> "public");
  }

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private FormTemplateSpecCatalog specCatalog;
  @Autowired private FormTemplateAdminService adminService;
  @Autowired private FormPublicationService publicationService;
  @Autowired private FormPublicationTxService publicationTxService;

  @BeforeEach
  void resetState() {
    jdbcTemplate.execute(
        """
        do $$
        declare t text;
        begin
          for t in select tablename from pg_tables
                    where schemaname = current_schema()
                      and tablename like 'form_s%'
                      and (tablename like '%_instances' or tablename like '%_records')
          loop
            execute 'drop table if exists ' || t || ' cascade';
          end loop;
        end $$;
        """);
    jdbcTemplate.execute("truncate table form_template_scopes restart identity cascade");
    jdbcTemplate.execute("truncate table collect_form_records restart identity cascade");
    jdbcTemplate.execute("truncate table form_authority_cutover_manifests restart identity cascade");
    jdbcTemplate.update("delete from gitlab_sync_configs where name like 'form-r02-test%'");
    jdbcTemplate.execute("select setval('form_record_id_seq', 1, false)");
  }

  @Test
  void shouldExecuteAdminFrozenDraftInsteadOfSeedSpec() {
    long configId = insertSourceConfig();
    insertLegacyRow(101, 9, 12L, "12", "Alice", 30, 1, 2, 3, 4, 5, "note-101", false);

    JsonNode spec = seedSpecWithReviewerLabel("REVIEW_CONFIRMED_LABEL");
    Published published = publishBoundScope(configId, 9L, spec, "CC2027R1", "scope-r02-a");

    assertThat(published.publication().publicationKind()).isEqualTo("INITIAL_MIGRATION");
    assertThat(published.publication().stage()).isEqualTo("PUBLISHED");
    assertThat(published.previewJson().path("publicationKind").asText())
        .isEqualTo("INITIAL_MIGRATION");
    assertThat(published.previewJson().path("impact").path("records").asInt()).isEqualTo(1);
    assertThat(
            jdbcTemplate.queryForObject(
                "select field_spec->'fields'->0->>'label' from form_template_versions where id = ?",
                String.class,
                published.publication().toVersionId()))
        .isEqualTo("REVIEW_CONFIRMED_LABEL");
    assertThat(
            jdbcTemplate.queryForObject(
                "select storage_kind from form_template_versions where id = ?",
                String.class,
                published.publication().fromVersionId()))
        .isEqualTo("LEGACY");
    String recordTable = recordTable(published.publication().toVersionId());
    assertThat(
            jdbcTemplate.queryForObject(
                "select reviewer_name from " + recordTable + " where id = 101", String.class))
        .isEqualTo("Alice");
    assertThat(
            jdbcTemplate.queryForObject(
                "select migrated_rows from form_template_migration_logs where scope_id = ?",
                Long.class,
                published.publication().scopeId()))
        .isEqualTo(1);
    assertThat(count("form_authority_cutover_items")).isEqualTo(1);
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from form_resource_authorities where cutover_manifest_id is not null",
                Integer.class))
        .isEqualTo(1);
    assertThat(count("form_change_heads")).isEqualTo(1);
  }

  @Test
  void shouldTakeInitialEmptyWhenOnlyOtherScopeHasLegacySelection() {
    long configId = insertSourceConfig();
    insertLegacyRow(201, 9, 21L, "21", "Alice", 10, 0, 0, 0, 0, 0, null, false);

    JsonNode spec = seedSpecWithReviewerLabel("走查人");
    Published other = publishBoundScope(configId, 172L, spec, "CC2027R1", "scope-r02-b");

    assertThat(other.publication().publicationKind()).isEqualTo("INITIAL_EMPTY");
    assertThat(other.previewJson().path("impact").path("records").asInt()).isZero();
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from " + recordTable(other.publication().toVersionId()),
                Integer.class))
        .isZero();
    assertThat(count("form_authority_cutover_manifests")).isZero();
  }

  @Test
  void shouldCopyFrozenRenamedAddedAndDeletedFields() {
    long configId = insertSourceConfig();
    insertLegacyRow(301, 9, 31L, "31", "Bob", 7, 1, 2, 3, 4, 5, "should-not-copy", false);

    ObjectNode spec = (ObjectNode) seedSpecWithReviewerLabel("修复状态");
    var fields = spec.withArray("fields");
    for (int index = fields.size() - 1; index >= 0; index--) {
      if ("supplement_note".equals(fields.get(index).path("name").asText())) {
        fields.remove(index);
      }
    }
    ObjectNode added = fields.addObject();
    added.put("name", "fix_status");
    added.put("apiKey", "fixStatus");
    added.put("label", "修复状态");
    added.put("type", "string");
    added.put("required", true);
    added.put("maxLength", 64);
    added.put("control", "input");
    added.putObject("storage").put("column", "fix_status").put("columnType", "varchar(64)");

    Published published = publishBoundScope(configId, 9L, spec, "CC2027R2", "scope-r02-c");
    String recordTable = recordTable(published.publication().toVersionId());
    assertThat(
            jdbcTemplate.queryForObject(
                "select reviewer_name from " + recordTable + " where id = 301", String.class))
        .isEqualTo("Bob");
    assertThat(
            jdbcTemplate.queryForObject(
                "select legacy_incomplete from " + recordTable + " where id = 301",
                Boolean.class))
        .isTrue();
    assertThat(
            jdbcTemplate.queryForObject(
                "select legacy_missing_fields::text from " + recordTable + " where id = 301",
                String.class))
        .contains("fix_status")
        .contains("reviewer_user_id");
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from information_schema.columns"
                    + " where table_name = ? and column_name = 'supplement_note'",
                Integer.class,
                recordTable))
        .isZero();
    // 删除字段的旧值不得串入新增字段；新增字段历史值为 NULL。
    assertThat(
            jdbcTemplate.queryForObject(
                "select fix_status from " + recordTable + " where id = 301", String.class))
        .isNull();
  }

  @Test
  void shouldWaitOnSequenceLockBeforeScopeDuringInitialSwitch() throws Exception {
    long configId = insertSourceConfig();
    insertLegacyRow(401, 9, 41L, "41", "Alice", 10, 1, 1, 1, 1, 1, "lock", false);
    PreparedPublication prepared =
        prepareBoundPublication(
            configId, 9L, seedSpecWithReviewerLabel("走查人"), "CC2027R1", "scope-r01-lock");

    DataSourceTransactionManager transactions =
        new DataSourceTransactionManager(jdbcTemplate.getDataSource());
    TransactionTemplate transaction = new TransactionTemplate(transactions);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Holder holder = startAdvisoryHolder(pool, transaction);
      Future<Boolean> runner =
          pool.submit(() -> publicationService.executeOnce(prepared.publication().id()));
      long waiterPid = awaitAdvisoryWait(holder.pid(), runner);
      assertNoScopeRowLock(waiterPid);
      holder.release().countDown();
      assertThat(runner.get(30, TimeUnit.SECONDS)).isTrue();
    } finally {
      pool.shutdownNow();
    }

    assertThat(publicationService.publicationView(prepared.publication().id()).stage())
        .isEqualTo("PUBLISHED");
    assertThat(
            jdbcTemplate.queryForObject(
                "select write_fenced from form_template_scopes where id = ?",
                Boolean.class,
                prepared.publication().scopeId()))
        .isFalse();
  }


  @Test
  void shouldCopyInitialMigrationWithoutDeadlockAgainstScopeThenTaskClaimPrefix() throws Exception {
    long configId = insertSourceConfig();
    insertLegacyRow(402, 9, 42L, "42", "Alice", 11, 2, 2, 2, 2, 2, "copy-lock", false);
    PreparedPublication prepared =
        prepareBoundPublication(
            configId, 9L, seedSpecWithReviewerLabel("走查人"), "CC2027R1", "scope-r01-copy-lock");
    FormPublicationTxService.Claim claim =
        publicationTxService.claim(prepared.publication().id(), "r01-copy-lock");
    assertThat(claim).isNotNull();
    long scopeId = claim.scopeId();
    String recordTable =
        jdbcTemplate.queryForObject(
            "select record_table from form_template_versions where id = ?",
            String.class,
            claim.toVersionId());

    DataSourceTransactionManager transactions =
        new DataSourceTransactionManager(jdbcTemplate.getDataSource());
    TransactionTemplate transaction = new TransactionTemplate(transactions);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    java.util.concurrent.atomic.AtomicReference<Future<?>> copyFuture =
        new java.util.concurrent.atomic.AtomicReference<>();
    try {
      Future<?> claimPrefix =
          pool.submit(
              () ->
                  transaction.executeWithoutResult(
                      status -> {
                        jdbcTemplate.queryForObject(
                            "select id from form_template_scopes where id = ? for update",
                            Long.class,
                            scopeId);
                        copyFuture.set(
                            pool.submit(() -> publicationTxService.copy(claim)));
                        try {
                          Thread.sleep(800);
                        } catch (InterruptedException error) {
                          Thread.currentThread().interrupt();
                          throw new IllegalStateException(error);
                        }
                        jdbcTemplate.queryForObject(
                            "select id from form_template_publications where id = ? for update",
                            Long.class,
                            claim.publicationId());
                      }));
      claimPrefix.get(30, TimeUnit.SECONDS);
      copyFuture.get().get(60, TimeUnit.SECONDS);
    } finally {
      pool.shutdownNow();
    }

    assertThat(publicationService.publicationView(prepared.publication().id()).stage())
        .isEqualTo("VERIFIED");
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from " + recordTable, Integer.class))
        .isEqualTo(1);
  }
  private Published publishBoundScope(
      long configId, long projectId, JsonNode fieldSpec, String label, String scopeKey) {
    PreparedPublication prepared =
        prepareBoundPublication(configId, projectId, fieldSpec, label, scopeKey);
    boolean executed = publicationService.executeOnce(prepared.publication().id());
    assertThat(executed).isTrue();
    FormAdminViews.PublicationView finished =
        publicationService.publicationView(prepared.publication().id());
    return new Published(finished, prepared.previewJson());
  }

  private PreparedPublication prepareBoundPublication(
      long configId, long projectId, JsonNode fieldSpec, String label, String scopeKey) {
    FormAdminViews.ScopeDetail created =
        adminService.createScope(new FormScopeCreateRequest("code_review", scopeKey), ACTOR);
    long scopeId = created.scope().id();
    ObjectNode evidence = objectMapper.createObjectNode();
    evidence.put("sourceConfigId", configId);
    evidence.put("projectId", projectId);
    evidence.put("milestoneId", 1);
    evidence.put("milestoneTitle", label);
    evidence.putObject("mapping").put(label, label);
    evidence.put("verifiedAt", "2026-10-22T00:00:00Z");
    FormAdminViews.DraftView draft =
        adminService.updateDraft(
            scopeId,
            new FormDraftUpdateRequest(
                created.draft().revision(),
                fieldSpec,
                new FormBindingRequest(configId, List.of(projectId), projectId),
                label,
                evidence,
                null),
            ACTOR);
    FormAdminViews.PreviewView preview = adminService.preview(scopeId, draft.revision(), ACTOR);
    assertThat(preview.blockingErrors()).isEmpty();
    FormAdminViews.PublicationView publication =
        adminService.createPublication(
            scopeId,
            new FormPublicationCreateRequest(
                draft.revision(), preview.id(), null, java.util.UUID.randomUUID().toString()),
            ACTOR);
    String previewJson =
        jdbcTemplate.queryForObject(
            "select preview_json::text from form_template_preview_evidence where id = ?",
            String.class,
            preview.id());
    try {
      return new PreparedPublication(publication, objectMapper.readTree(previewJson));
    } catch (Exception error) {
      throw new IllegalStateException(error);
    }
  }

  private Holder startAdvisoryHolder(
      ExecutorService pool, TransactionTemplate transaction) throws Exception {
    java.util.concurrent.atomic.AtomicLong pid =
        new java.util.concurrent.atomic.AtomicLong();
    CountDownLatch ready = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    pool.submit(
        () ->
            transaction.executeWithoutResult(
                status -> {
                  jdbcTemplate.execute(
                      "select pg_advisory_xact_lock(hashtext('form_record_id_seq')::bigint)");
                  pid.set(jdbcTemplate.queryForObject("select pg_backend_pid()", Long.class));
                  ready.countDown();
                  try {
                    if (!release.await(20, TimeUnit.SECONDS)) {
                      throw new IllegalStateException("等待释放序列锁超时");
                    }
                  } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(error);
                  }
                }));
    assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
    return new Holder(pid.get(), release);
  }

  private long awaitAdvisoryWait(long holderPid, Future<?> worker) throws Exception {
    for (int attempt = 0; attempt < 400; attempt++) {
      List<Long> waiters =
          jdbcTemplate.queryForList(
              """
              select pid from pg_stat_activity
               where datname = current_database() and pid <> ?
                 and ? = any(pg_blocking_pids(pid))
                 and query like '%pg_advisory_xact_lock%'
              """,
              Long.class,
              holderPid,
              holderPid);
      if (!waiters.isEmpty()) {
        return waiters.getFirst();
      }
      if (worker.isDone()) {
        worker.get();
        throw new AssertionError("工作线程未等待序列锁即完成");
      }
      Thread.sleep(20);
    }
    throw new AssertionError("工作线程未在超时内等待序列锁");
  }

  private void assertNoScopeRowLock(long pid) {
    Integer held =
        jdbcTemplate.queryForObject(
            """
            select count(*) from pg_locks l
              join pg_class c on c.oid = l.relation
             where l.pid = ? and l.granted and c.relname = 'form_template_scopes'
            """,
            Integer.class,
            pid);
    assertThat(held).isZero();
  }

  private record Holder(long pid, CountDownLatch release) {}

  private record PreparedPublication(
      FormAdminViews.PublicationView publication, JsonNode previewJson) {}

  private JsonNode seedSpecWithReviewerLabel(String label) {
    try {
      ObjectNode spec =
          (ObjectNode) objectMapper.readTree(specCatalog.rawJson("code_review"));
      for (JsonNode field : spec.withArray("fields")) {
        if ("reviewer_user_id".equals(field.path("name").asText())) {
          ((ObjectNode) field).put("label", label);
        }
      }
      return spec;
    } catch (Exception error) {
      throw new IllegalStateException(error);
    }
  }

  private String recordTable(long versionId) {
    return jdbcTemplate.queryForObject(
        "select record_table from form_template_versions where id = ?", String.class, versionId);
  }

  private long insertSourceConfig() {
    return jdbcTemplate.queryForObject(
        """
        insert into gitlab_sync_configs(name, db_name, db_username, db_password, web_base_url)
        values ('form-r02-test', 'qaflex', 'qaflex', 'secret', ?)
        returning id
        """,
        Long.class,
        GITLAB_URL);
  }

  private void insertLegacyRow(
      long id,
      long projectId,
      Long requestIid,
      String resourceId,
      String reviewer,
      int duration,
      int specification,
      int logic,
      int performance,
      int design,
      int other,
      String remark,
      boolean deleted) {
    jdbcTemplate.update(
        """
        insert into collect_form_records(
            id, gitlab_base_url, project_id, request_iid, resource_type, resource_id,
            template_code, reviewer, review_duration_minutes, specification_score, logic_score,
            performance_score, design_score, other_score, remark, deleted, created_at, updated_at)
        values (?, ?, ?, ?, 'merge_request', ?, 'code_review', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        id,
        GITLAB_URL,
        projectId,
        requestIid,
        resourceId,
        reviewer,
        duration,
        specification,
        logic,
        performance,
        design,
        other,
        remark,
        deleted,
        Timestamp.valueOf(LocalDateTime.of(2026, 1, 1, 9, 0)),
        Timestamp.valueOf(LocalDateTime.of(2026, 1, 1, 10, 0)));
  }

  private int count(String table) {
    return jdbcTemplate.queryForObject("select count(*) from " + table, Integer.class);
  }

  private record Published(FormAdminViews.PublicationView publication, JsonNode previewJson) {}
}
