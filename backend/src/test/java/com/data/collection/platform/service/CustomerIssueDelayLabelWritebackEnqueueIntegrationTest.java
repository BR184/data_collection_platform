package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.data.collection.platform.domain.form.FormScopeBinding;
import com.data.collection.platform.domain.form.FormTemplateSpec;
import com.data.collection.platform.domain.form.FormTemplateSpecCatalog;
import com.data.collection.platform.domain.form.FormVersionSchema;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.service.form.FormVersionSchemaService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 写回候选分页、真实标签读取与处理中修订（真实 PostgreSQL）。
 *
 * <p>标签必须来自当前 ODS；ODS 议题缺失时视为未知而不是空集合。RUNNING 期间到达的新差异要推进
 * 任务修订并保留新内容，旧 worker 的结算必须失败。
 */
@SpringBootTest
class CustomerIssueDelayLabelWritebackEnqueueIntegrationTest {
  private static final long PROJECT_ID = 325L;
  private static final long FIRST_IID = 81001L;
  private static final long SECOND_IID = 81002L;
  private static final long THIRD_IID = 81003L;
  private static final String SOURCE = "default";
  private static final long AUTH_REVISION = 7L;
  private static final long FACT_VERSION = 42L;
  private static final long MIRROR_RUN_ID = 77L;

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private CustomerIssueDelayLabelWritebackQueueService queueService;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private FormTemplateSpecCatalog catalog;
  @Autowired private FormVersionSchemaService schemaService;

  private long configId;
  private GitlabSyncConfig config;

  @BeforeEach
  void setUp() {
    GitlabIssueMirrorFixture.ensureSchema(jdbcTemplate);
    cleanUp();
    configId =
        jdbcTemplate.queryForObject(
            """
            insert into gitlab_sync_configs(
                name, source_instance, source_enabled, auto_sync_enabled, db_name,
                db_username, db_password, delay_label_writeback_enabled,
                web_base_url, api_token)
            values ('delay-enqueue-it', ?, true, false, 'mirror', 'reader', 'secret',
                    true, 'http://127.0.0.1', 'token')
            returning id
            """,
            Long.class,
            SOURCE);
    config = new GitlabSyncConfig();
    config.setId(configId);
    config.setSourceInstance(SOURCE);
    config.setSourceEnabled(true);
    config.setDelayLabelWritebackEnabled(true);
    config.setWebBaseUrl("http://127.0.0.1");
    config.setApiToken("token");
  }

  @AfterEach
  void tearDown() {
    cleanUp();
  }

  @Test
  void enqueuePageReadsCurrentOdsLabelsAndAdvancesCursor() {
    insertIssue(FIRST_IID, 981001L, "stale-label", true, false);
    insertIssue(SECOND_IID, 981002L, "stale-label", true, false);
    insertIssue(THIRD_IID, 981003L, "stale-label", false, false);

    CustomerIssueDelayLabelWritebackQueueService.PageResult first =
        queueService.enqueuePage(config, AUTH_REVISION, FACT_VERSION, MIRROR_RUN_ID, null, 2);

    assertThat(first.enqueued()).isEqualTo(2);
    assertThat(first.lastIssueId()).isEqualTo(981002L);
    assertThat(first.hasMore()).isTrue();
    Map<String, Object> job = job(981001L);
    assertThat(job.get("current_label_names"))
        .as("标签必须来自当前 ODS，而不是事实中的旧标签")
        .isEqualTo("");
    assertThat(job.get("add_labels")).isEqualTo("响应已延期");
    assertThat(job.get("config_id")).isEqualTo(configId);
    assertThat(job.get("authorization_revision")).isEqualTo(AUTH_REVISION);
    assertThat(job.get("required_fact_version")).isEqualTo(FACT_VERSION);
    assertThat(job.get("round_mirror_run_id")).isEqualTo(MIRROR_RUN_ID);
    assertThat(((Number) job.get("task_revision")).longValue()).isEqualTo(1L);

    CustomerIssueDelayLabelWritebackQueueService.PageResult second =
        queueService.enqueuePage(
            config, AUTH_REVISION, FACT_VERSION, MIRROR_RUN_ID, first.lastIssueId(), 2);

    assertThat(second.lastIssueId()).isEqualTo(981003L);
    assertThat(second.hasMore()).isFalse();
  }

  @Test
  void actualOdsLabelQueryFailureMustNotReturnEmptyLabels() {
    jdbcTemplate.execute("alter table ods_gitlab_labels rename to delay_labels_unavailable");
    try {
      assertThatThrownBy(() -> queueService.loadCandidate(SOURCE, PROJECT_ID, FIRST_IID))
          .isInstanceOf(org.springframework.dao.DataAccessException.class);
    } finally {
      jdbcTemplate.execute("alter table delay_labels_unavailable rename to ods_gitlab_labels");
    }
  }

  @Test
  void simultaneousJobClaimsMustHaveOnlyOneOwner() throws Exception {
    insertIssue(FIRST_IID, 981001L, "stale-label", true, false);
    queueService.enqueuePage(config, AUTH_REVISION, FACT_VERSION, MIRROR_RUN_ID, null, 10);
    var start = new java.util.concurrent.CyclicBarrier(2);
    try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var first = executor.submit(() -> { start.await(); return queueService.claimNext("worker-a", 120); });
      var second = executor.submit(() -> { start.await(); return queueService.claimNext("worker-b", 120); });
      var a = first.get(10, java.util.concurrent.TimeUnit.SECONDS);
      var b = second.get(10, java.util.concurrent.TimeUnit.SECONDS);
      assertThat((a == null ? 0 : 1) + (b == null ? 0 : 1)).isEqualTo(1);
      var owner = a != null ? a : b;
      assertThat(queueService.markSucceeded(owner)).isTrue();
      assertThat(job(981001L).get("status")).isEqualTo("SUCCEEDED");
    }
  }

  @Test
  void newDiffArrivingWhileRunningIncrementsRevisionAndOldSettlementFails() {
    insertIssue(FIRST_IID, 981001L, "stale-label", true, false);
    CustomerIssueDelayLabelWritebackQueueService.PageResult page =
        queueService.enqueuePage(config, AUTH_REVISION, FACT_VERSION, MIRROR_RUN_ID, null, 10);
    assertThat(page.enqueued()).isEqualTo(1);

    CustomerIssueDelayLabelWritebackJob claimed = queueService.claimNext("worker-a", 120);
    assertThat(claimed).isNotNull();
    assertThat(claimed.taskRevision()).isEqualTo(1L);

    // 处理期间来源出现两个延期标签；事实仍判定解决未延期，本轮应新增"摘除解决已延期"。
    insertLabel(1L, "响应已延期");
    linkLabel(1L, 981001L);
    insertLabel(2L, "解决已延期");
    linkLabel(2L, 981001L);

    CustomerIssueDelayLabelWritebackQueueService.PageResult newPage =
        queueService.enqueuePage(
            config, AUTH_REVISION + 1, FACT_VERSION + 1, MIRROR_RUN_ID, null, 10);

    assertThat(newPage.enqueued()).isEqualTo(1);
    Map<String, Object> updated = job(981001L);
    assertThat(updated.get("remove_labels")).isEqualTo("解决已延期");
    assertThat(updated.get("status")).isEqualTo("RUNNING");
    assertThat(((Number) updated.get("task_revision")).longValue()).isEqualTo(2L);
    assertThat(updated.get("authorization_revision")).isEqualTo(AUTH_REVISION + 1);
    assertThat(updated.get("required_fact_version")).isEqualTo(FACT_VERSION + 1);

    assertThat(queueService.markSucceeded(claimed))
        .as("旧任务修订不能结算处理中到达的新差异")
        .isFalse();
    assertThat(job(981001L).get("status")).isEqualTo("RUNNING");
  }

  @Test
  void missingOdsIssueIsUnknownRatherThanEmptyLabels() {
    insertIssue(FIRST_IID, 981001L, "stale-label", true, false);
    jdbcTemplate.update(
        "update ods_gitlab_issues set mirror_deleted = true where id = ?", 981001L);

    CustomerIssueDelayLabelWritebackQueueService.PageResult page =
        queueService.enqueuePage(config, AUTH_REVISION, FACT_VERSION, MIRROR_RUN_ID, null, 10);

    assertThat(page.enqueued()).isZero();
    assertThat(jdbcTemplate.queryForObject(
            "select count(*) from customer_issue_delay_label_writeback_jobs where issue_id = ?",
            Long.class,
            981001L))
        .isZero();
  }

  /**
   * 延期原因从技术卡点改为资源卡点时，输入签名推进事实、作业目标改为只摘旧原因并加新原因；
   * 旧修订在发送前即因输入签名不一致而失去资格。
   */
  @Test
  void deferralReasonChangeUpdatesJobTargetAndRejectsOldRevision() {
    insertIssue(FIRST_IID, 981001L, "", false, false);
    long scopeId = createReplyScope();
    insertReplyAuthority(FIRST_IID, scopeId);
    long versionId = currentVersionId(scopeId);
    long instanceId = ensureReplyInstance(scopeId, versionId);
    long recordId = insertReplyRecord(scopeId, versionId, instanceId, "TECHNICAL_BLOCKER");
    updateFormInputHash(981001L, "a".repeat(64));

    CustomerIssueDelayLabelWritebackQueueService.PageResult first =
        queueService.enqueuePage(config, AUTH_REVISION, FACT_VERSION, null, null, 10);
    assertThat(first.enqueued()).isEqualTo(1);
    Map<String, Object> firstJob = job(981001L);
    assertThat(firstJob.get("add_labels")).isEqualTo("状态：申请延期, 技术卡点");
    assertThat(firstJob.get("remove_labels")).isEqualTo("");
    assertThat(firstJob.get("form_input_hash")).isEqualTo("a".repeat(64));

    CustomerIssueDelayLabelWritebackJob claimed = queueService.claimNext("worker-a", 120);
    assertThat(claimed).isNotNull();
    assertThat(claimed.addLabels()).containsExactly("状态：申请延期", "技术卡点");
    assertThat(claimed.formInputHash()).isEqualTo("a".repeat(64));

    updateReplyReason(scopeId, versionId, recordId, "RESOURCE_BLOCKER");
    updateFormInputHash(981001L, "b".repeat(64));
    // 第一次写回已生效：ODS 当前标签为申请延期＋技术卡点
    insertLabel(3L, "状态：申请延期");
    linkLabel(3L, 981001L);
    insertLabel(4L, "技术卡点");
    linkLabel(4L, 981001L);

    CustomerIssueDelayLabelWritebackQueueService.PageResult second =
        queueService.enqueuePage(config, AUTH_REVISION + 1, FACT_VERSION + 1, null, null, 10);
    assertThat(second.enqueued()).isEqualTo(1);
    Map<String, Object> updated = job(981001L);
    assertThat(updated.get("add_labels")).isEqualTo("资源卡点");
    assertThat(updated.get("remove_labels")).isEqualTo("技术卡点");
    assertThat(((Number) updated.get("task_revision")).longValue()).isEqualTo(2L);
    assertThat(updated.get("form_input_hash")).isEqualTo("b".repeat(64));

    var current = queueService.loadCandidate(SOURCE, PROJECT_ID, FIRST_IID).orElseThrow();
    assertThat(claimed.formInputHash())
        .as("旧作业携带的输入签名与新事实不一致，worker 发送前会作废")
        .isNotEqualTo(current.getFormInputHash());
    assertThat(queueService.markSucceeded(claimed)).isFalse();
    assertThat(job(981001L).get("status")).isEqualTo("RUNNING");
  }

  private long createReplyScope() {
    String rawSpec = catalog.rawJson(FormTemplateSpecCatalog.ISSUE_REPLY);
    FormTemplateSpec spec;
    try {
      spec = objectMapper.readValue(rawSpec, FormTemplateSpec.class);
    } catch (Exception error) {
      throw new IllegalStateException("测试回复规格无法解析", error);
    }
    String bindingHash =
        FormScopeBinding.hash(
            objectMapper,
            FormScopeBinding.create(
                objectMapper,
                FormTemplateSpecCatalog.ISSUE_REPLY,
                configId,
                SOURCE,
                "http://127.0.0.1",
                List.of(PROJECT_ID),
                PROJECT_ID,
                "issue"));
    long scopeId =
        jdbcTemplate.queryForObject(
            """
            insert into form_template_scopes(
                template_key, scope_key, source_config_id, source_instance,
                canonical_gitlab_base_url, project_ids, version_project_id, resource_type,
                status, binding_hash)
            values (?, 'delay-reply-scope', ?, ?, 'http://127.0.0.1', ?::jsonb, ?, 'issue',
                    'DRAFT', ?)
            returning id
            """,
            Long.class,
            FormTemplateSpecCatalog.ISSUE_REPLY,
            configId,
            SOURCE,
            "[" + PROJECT_ID + "]",
            PROJECT_ID,
            bindingHash);
    long versionId =
        jdbcTemplate.queryForObject(
            "select nextval(pg_get_serial_sequence('form_template_versions', 'id'))", Long.class);
    FormVersionSchema schema = schemaService.generate(scopeId, versionId, spec, false);
    jdbcTemplate.update(
        """
        insert into form_template_versions(
            id, scope_id, version_label, storage_kind, instance_table, record_table, field_spec,
            version_status, published_at)
        values (?, ?, 'v1', 'RECORDS', ?, ?, ?::jsonb, 'PUBLISHED', current_timestamp)
        """,
        versionId,
        scopeId,
        schema.instanceTable(),
        schema.recordTable(),
        rawSpec);
    schema.statements().forEach(jdbcTemplate::execute);
    jdbcTemplate.update(
        "update form_template_scopes set status = 'ACTIVE', current_version_id = ?,"
            + " source_set_revision = 1, source_set_hash = form_source_set_hash(id)"
            + " where id = ?",
        versionId,
        scopeId);
    return scopeId;
  }

  private void insertReplyAuthority(long iid, long scopeId) {
    jdbcTemplate.update(
        """
        insert into form_resource_authorities(
            source_config_id, source_instance, canonical_gitlab_base_url, project_id,
            resource_type, resource_id, template_key, scope_id, authority,
            authority_revision, effective_at, created_at, updated_at)
        values (?, ?, 'http://127.0.0.1', ?, 'issue', ?, ?, ?, 'FORM', 1,
                current_timestamp, current_timestamp, current_timestamp)
        """,
        configId,
        SOURCE,
        PROJECT_ID,
        iid,
        FormTemplateSpecCatalog.ISSUE_REPLY,
        scopeId);
  }

  private long currentVersionId(long scopeId) {
    return jdbcTemplate.queryForObject(
        "select current_version_id from form_template_scopes where id = ?", Long.class, scopeId);
  }

  private long ensureReplyInstance(long scopeId, long versionId) {
    String instanceTable =
        jdbcTemplate.queryForObject(
            "select instance_table from form_template_versions where id = ?",
            String.class,
            versionId);
    return jdbcTemplate.queryForObject(
        """
        insert into %s(
            scope_id, source_config_id, source_instance, gitlab_base_url, project_id,
            resource_type, resource_id, template_key, created_at, updated_at)
        values (?, ?, ?, 'http://127.0.0.1', ?, 'issue', ?, ?, current_timestamp,
                current_timestamp)
        returning id
        """
            .formatted(instanceTable),
        Long.class,
        scopeId,
        configId,
        SOURCE,
        PROJECT_ID,
        FIRST_IID,
        FormTemplateSpecCatalog.ISSUE_REPLY);
  }

  private long insertReplyRecord(
      long scopeId, long versionId, long instanceId, String deferralReason) {
    String recordTable =
        jdbcTemplate.queryForObject(
            "select record_table from form_template_versions where id = ?",
            String.class,
            versionId);
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("fix_status", "DEFERRED");
    fields.put("deferral_reason", deferralReason);
    fields.put("cause_keys", "{standard_error}");
    fields.put("specific_reason", "原因");
    fields.put("modification_plan", "方案");
    fields.put("caused_by_other_defect", false);
    fields.put("affected_kinds", "{NONE}");
    fields.put("impact_test_status", "NOT_APPLICABLE");
    fields.put("has_residual_impact", false);
    fields.put("relation_table_updated", "NOT_APPLICABLE");
    List<String> columns =
        new java.util.ArrayList<>(
            List.of(
                "instance_id",
                "origin_version_id",
                "template_version",
                "created_by",
                "request_key",
                "create_payload_hash",
                "record_version",
                "deleted",
                "created_at",
                "updated_at"));
    List<Object> args =
        new java.util.ArrayList<>(
            List.of(
                instanceId,
                versionId,
                "v1",
                "u-jia",
                "req-" + java.util.UUID.randomUUID(),
                "0".repeat(64),
                1,
                false,
                Timestamp.valueOf(LocalDateTime.of(2026, 11, 2, 9, 0)),
                Timestamp.valueOf(LocalDateTime.of(2026, 11, 2, 9, 0))));
    fields.forEach(
        (column, value) -> {
          columns.add(column);
          args.add(value);
        });
    String placeholders =
        String.join(
            ", ",
            columns.stream()
                .map(
                    column ->
                        "cause_keys".equals(column) || "affected_kinds".equals(column)
                            ? "?::text[]"
                            : "?")
                .toList());
    long recordId =
        jdbcTemplate.queryForObject(
            "insert into "
                + recordTable
                + " ("
                + String.join(", ", columns)
                + ") values ("
                + placeholders
                + ") returning id",
            Long.class,
            args.toArray());
    String originSpecHash =
        jdbcTemplate.queryForObject(
            "select form_spec_hash(?::jsonb)",
            String.class,
            jdbcTemplate.queryForObject(
                "select field_spec::text from form_template_versions where id = ?",
                String.class,
                versionId));
    jdbcTemplate.update(
        """
        insert into form_record_locations(
            record_id, scope_id, storage_version_id, origin_version_id, instance_mode, instance_id,
            source_config_id, source_instance, gitlab_base_url, project_id, resource_type,
            resource_id, request_key, created_by, origin_spec_hash, create_payload_hash, created_at)
        values (?, ?, ?, ?, 'MATERIALIZED', ?, ?, ?, 'http://127.0.0.1', ?, 'issue', ?, null,
                'u-jia', ?, null, ?)
        """,
        recordId,
        scopeId,
        versionId,
        versionId,
        instanceId,
        configId,
        SOURCE,
        PROJECT_ID,
        String.valueOf(FIRST_IID),
        originSpecHash,
        Timestamp.valueOf(LocalDateTime.of(2026, 11, 2, 9, 0)));
    return recordId;
  }

  private void updateReplyReason(
      long scopeId, long versionId, long recordId, String deferralReason) {
    String recordTable =
        jdbcTemplate.queryForObject(
            "select record_table from form_template_versions where id = ?",
            String.class,
            versionId);
    jdbcTemplate.update(
        "update " + recordTable
            + " set deferral_reason = ?, record_version = record_version + 1,"
            + " updated_at = current_timestamp where id = ?",
        deferralReason,
        recordId);
  }

  private void updateFormInputHash(long issueId, String hash) {
    jdbcTemplate.update(
        "update issue_fact set form_input_hash = ? where issue_id = ?", hash, issueId);
  }

  private void cleanFormFixture() {
    List<Map<String, Object>> versions =
        jdbcTemplate.queryForList(
            """
            select v.instance_table, v.record_table
              from form_template_versions v
              join form_template_scopes s on s.id = v.scope_id
             where s.scope_key = 'delay-reply-scope'
            """);
    for (Map<String, Object> version : versions) {
      jdbcTemplate.execute(
          "drop table if exists " + version.get("record_table") + " cascade");
      jdbcTemplate.execute(
          "drop table if exists " + version.get("instance_table") + " cascade");
    }
    jdbcTemplate.update(
        "delete from form_record_locations where scope_id in"
            + " (select id from form_template_scopes where scope_key = 'delay-reply-scope')");
    jdbcTemplate.update(
        "delete from form_resource_authorities where scope_id in"
            + " (select id from form_template_scopes where scope_key = 'delay-reply-scope')");
    jdbcTemplate.update(
        "update form_template_scopes set status = 'DRAFT', current_version_id = null"
            + " where scope_key = 'delay-reply-scope'");
    jdbcTemplate.update(
        "delete from form_template_versions where scope_id in"
            + " (select id from form_template_scopes where scope_key = 'delay-reply-scope')");
    jdbcTemplate.update(
        "delete from form_template_scopes where scope_key = 'delay-reply-scope'");
  }

  private void insertIssue(
      long iid, long issueId, String staleLabels, boolean responseDelayed, boolean resolveDelayed) {
    jdbcTemplate.update(
        """
        insert into ods_gitlab_issues(id, iid, project_id, title, mirror_deleted)
        values (?, ?, ?, 'issue', false)
        """,
        issueId,
        iid,
        PROJECT_ID);
    jdbcTemplate.update(
        """
        insert into issue_fact(
            source_system, source_instance, project_id, issue_id, issue_iid, title,
            issue_state, created_at_source, label_names, is_response_delayed,
            is_resolve_delayed, response_overdue, deleted)
        values ('GITLAB', ?, ?, ?, ?, 'issue', 'opened', '2026-05-01', ?, ?, ?, ?,
                false)
        """,
        SOURCE,
        PROJECT_ID,
        issueId,
        iid,
        staleLabels,
        responseDelayed,
        resolveDelayed,
        responseDelayed);
  }

  private void insertLabel(long id, String title) {
    jdbcTemplate.update(
        "insert into ods_gitlab_labels(id, title, mirror_deleted) values (?, ?, false)",
        id,
        title);
  }

  private void linkLabel(long labelId, long targetId) {
    jdbcTemplate.update(
        """
        insert into ods_gitlab_label_links(
            id, label_id, target_id, target_type, mirror_deleted)
        values (?, ?, ?, 'Issue', false)
        """,
        1000 + labelId,
        labelId,
        targetId);
  }

  private Map<String, Object> job(long issueId) {
    return jdbcTemplate.queryForMap(
        """
        select config_id, current_label_names, add_labels, remove_labels, status,
               authorization_revision, required_fact_version, round_mirror_run_id,
               task_revision, form_input_hash
          from customer_issue_delay_label_writeback_jobs
         where project_id = ? and issue_id = ?
        """,
        PROJECT_ID,
        issueId);
  }

  private void cleanUp() {
    cleanFormFixture();
    jdbcTemplate.update(
        "delete from customer_issue_delay_label_writeback_jobs where project_id = ? and issue_iid in (?, ?, ?)",
        PROJECT_ID,
        FIRST_IID,
        SECOND_IID,
        THIRD_IID);
    jdbcTemplate.update(
        "delete from issue_fact where project_id = ? and issue_iid in (?, ?, ?)",
        PROJECT_ID,
        FIRST_IID,
        SECOND_IID,
        THIRD_IID);
    jdbcTemplate.update(
        "delete from ods_gitlab_label_links where target_id in (981001, 981002, 981003)");
    jdbcTemplate.update(
        "delete from ods_gitlab_labels where id in (1, 2, 3, 4)");
    jdbcTemplate.update(
        "delete from ods_gitlab_issues where id in (981001, 981002, 981003)");
    jdbcTemplate.update(
        "delete from customer_issue_delay_closure_requests where source_instance = ?", SOURCE);
    jdbcTemplate.update(
        "delete from gitlab_sync_configs where source_instance = ?", SOURCE);
  }
}
