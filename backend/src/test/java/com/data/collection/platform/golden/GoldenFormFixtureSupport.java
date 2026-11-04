package com.data.collection.platform.golden;

import static org.assertj.core.api.Assertions.assertThat;

import com.data.collection.platform.domain.form.FormScopeBinding;
import com.data.collection.platform.domain.form.FormTemplateSpec;
import com.data.collection.platform.domain.form.FormTemplateSpecCatalog;
import com.data.collection.platform.domain.form.FormVersionSchema;
import com.data.collection.platform.golden.GoldenBaselineSupport.GoldenHttpSession;
import com.data.collection.platform.service.FormIssueBuildInputProbe;
import com.data.collection.platform.service.form.FormScopeValidationService;
import com.data.collection.platform.service.form.FormVersionSchemaService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 统一表单 v6 黄金成功样本夹具：构造单一范围/版本/权威，用真实新增端点写入基线记录，
 * 等待既有 FACT_REFRESH、事实与投影发布真实收敛后才允许暂停调度。
 *
 * <p>本类不删除 change head、不关闭来源刷新、不新增旁路开关；基线必须是“表单已保存且事实已消费”
 * 的真实状态。命令用例随后保留真实 head 与 AFTER_COMMIT 入队结果，由写用例的 REPLAY 还原。
 */
final class GoldenFormFixtureSupport {

  private static final Logger log = LoggerFactory.getLogger(GoldenFormFixtureSupport.class);

  static final String BASE_URL = "http://gitlab.test.local:18080";
  static final String TEMPLATE_KEY = "issue_response";
  static final String VERSION_LABEL = "CC2026R3";
  static final long SCOPE_ID = 1;
  static final long VERSION_ID = 1;
  static final long PROJECT_ID = 9;
  static final long ISSUE_IID = 969;
  static final long ISSUE_ROOT_ID = 1228;
  static final String BASELINE_REQUEST_KEY = "00000000-0000-4000-8000-000000000001";
  static final String EXPORT_PATH = "/api/collect-forms/exports/records";

  private static final String SOURCE_INSTANCE = "default";
  private static final String SCOPE_KEY = "golden-issue-response";
  private static final String INSTANCE_TABLE = "form_s1_v1_instances";
  private static final String RECORD_TABLE = "form_s1_v1_records";
  private static final LocalDateTime FROZEN_PUBLISHED_AT =
      LocalDateTime.of(2026, 9, 1, 8, 0);
  private static final Duration CONVERGENCE_TIMEOUT = Duration.ofMinutes(8);
  private static final Duration QUEUED_WORK_TIMEOUT = Duration.ofMinutes(3);
  private static final int REQUIRED_STABLE_CHECKS = 3;

  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;
  private final FormTemplateSpecCatalog specCatalog;
  private final FormVersionSchemaService schemaService;
  private final FormScopeValidationService scopeValidation;
  private final ApplicationContext applicationContext;

  private long configId;
  private String failureDumped;

  GoldenFormFixtureSupport(
      JdbcTemplate jdbc,
      ObjectMapper objectMapper,
      FormTemplateSpecCatalog specCatalog,
      FormVersionSchemaService schemaService,
      FormScopeValidationService scopeValidation,
      ApplicationContext applicationContext) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
    this.specCatalog = specCatalog;
    this.schemaService = schemaService;
    this.scopeValidation = scopeValidation;
    this.applicationContext = applicationContext;
  }

  /**
   * 构造基线并等待真实消费收敛；失败立即抛出，不进入快照阶段。
   *
   * @param configId 已种子的来源配置 id
   * @param session 已登录的黄金会话
   */
  void bootstrapBaseline(long configId, GoldenHttpSession session) throws Exception {
    this.configId = configId;
    seedStructure(configId);
    createBaselineRecord(session);
    awaitFactConsumption();
    assertFactSignature();
    assertBaselineRowCounts();
  }

  /** 按 DRAFT → 版本 → ACTIVE → 物理表 → 权威的顺序构造唯一范围。 */
  private void seedStructure(long configId) {
    ObjectNode binding =
        FormScopeBinding.create(
            objectMapper,
            TEMPLATE_KEY,
            configId,
            SOURCE_INSTANCE,
            BASE_URL,
            List.of(PROJECT_ID),
            PROJECT_ID,
            "issue");
    String bindingHash = FormScopeBinding.hash(objectMapper, binding);
    Timestamp publishedAt = Timestamp.valueOf(FROZEN_PUBLISHED_AT);

    jdbc.update(
        """
        insert into form_template_scopes(
            id, template_key, scope_key, source_config_id, source_instance,
            canonical_gitlab_base_url, project_ids, version_project_id, resource_type,
            status, updated_at)
        values (?, ?, ?, ?, ?, ?, ?::jsonb, ?, 'issue', 'DRAFT', ?)
        """,
        SCOPE_ID,
        TEMPLATE_KEY,
        SCOPE_KEY,
        configId,
        SOURCE_INSTANCE,
        BASE_URL,
        "[" + PROJECT_ID + "]",
        PROJECT_ID,
        publishedAt);

    FormTemplateSpec spec = specCatalog.require(TEMPLATE_KEY);
    jdbc.update(
        """
        insert into form_template_versions(
            id, scope_id, version_label, storage_kind, instance_table, record_table,
            field_spec, version_status, published_at, record_source_role, instance_mode)
        values (?, ?, ?, 'RECORDS', ?, ?, ?::jsonb, 'PUBLISHED', ?, 'AUTHORITATIVE', 'MATERIALIZED')
        """,
        VERSION_ID,
        SCOPE_ID,
        VERSION_LABEL,
        INSTANCE_TABLE,
        RECORD_TABLE,
        specCatalog.rawJson(TEMPLATE_KEY),
        publishedAt);

    jdbc.update(
        """
        update form_template_scopes
           set status = 'ACTIVE',
               current_version_id = ?,
               source_set_revision = 1,
               binding_revision = 1,
               binding_hash = ?,
               updated_at = ?
         where id = ?
        """,
        VERSION_ID,
        bindingHash,
        publishedAt,
        SCOPE_ID);

    FormVersionSchema schema = schemaService.generate(SCOPE_ID, VERSION_ID, spec, false);
    schema.statements().forEach(jdbc::execute);

    jdbc.update(
        "update form_template_scopes set source_set_hash = form_source_set_hash(?) where id = ?",
        SCOPE_ID,
        SCOPE_ID);

    jdbc.update(
        """
        insert into form_resource_authorities(
            source_config_id, source_instance, canonical_gitlab_base_url, project_id,
            resource_type, resource_id, template_key, scope_id, authority,
            authority_revision, cutover_manifest_id, effective_at, created_at, updated_at)
        values (?, ?, ?, ?, 'issue', ?, ?, ?, 'FORM', 1, null, ?, ?, ?)
        """,
        configId,
        SOURCE_INSTANCE,
        BASE_URL,
        PROJECT_ID,
        ISSUE_IID,
        TEMPLATE_KEY,
        SCOPE_ID,
        publishedAt,
        publishedAt,
        publishedAt);

    scopeValidation.validateActiveScopes();

    assertThat(count("select count(*) from form_template_scopes where id = ?", SCOPE_ID))
        .as("基线范围必须唯一")
        .isEqualTo(1);
    assertThat(count("select count(*) from form_template_versions where id = ?", VERSION_ID))
        .as("基线版本必须唯一")
        .isEqualTo(1);
    assertThat(
            count(
                "select count(*) from form_resource_authorities where scope_id = ? and resource_id = ?",
                SCOPE_ID,
                ISSUE_IID))
        .as("基线 FORM 权威必须唯一")
        .isEqualTo(1);
    assertThat(scalar("select source_set_hash from form_template_scopes where id = ?", SCOPE_ID))
        .as("范围来源集合哈希必须来自版本集合")
        .isEqualTo(scalar("select form_source_set_hash(?)", SCOPE_ID));
  }

  /** 通过真实新增端点写入基线记录；不关闭来源、不抑制事实刷新。 */
  private void createBaselineRecord(GoldenHttpSession session) throws Exception {
    JsonNode data =
        session.postJson(
            "/api/collect-forms/records",
            """
            {
              "scopeId": "1",
              "storageVersionId": "1",
              "templateKey": "issue_response",
              "gitlabBaseUrl": "http://gitlab.test.local:18080",
              "projectId": 9,
              "resourceType": "issue",
              "resourceId": "969",
              "requestKey": "%s",
              "fields": {
                "problemType": "DEFECT",
                "problemCause": "黄金基线固定问题原因",
                "modificationPlan": "黄金基线固定修改方案",
                "plannedResolutionDate": "2026-09-30",
                "plannedMergeVersionBranch": "CC2026R3",
                "supplementNote": "黄金基线样本"
              }
            }
            """
                .formatted(BASELINE_REQUEST_KEY));
    assertThat(data.path("outcome").asText()).as("基线记录必须真实新增").isEqualTo("CREATED");
    assertThat(data.path("record").path("id").asText()).as("基线记录 id").isEqualTo("1");
    assertThat(data.path("record").path("recordVersion").asInt()).isEqualTo(1);
  }

  /** 等待既有调度器把 FORM 输入消费到事实与发布点；未收敛即停止。 */
  private void awaitFactConsumption() throws InterruptedException {
    long deadline = System.nanoTime() + CONVERGENCE_TIMEOUT.toNanos();
    int stable = 0;
    while (System.nanoTime() < deadline) {
      if (converged()) {
        stable++;
        if (stable >= REQUIRED_STABLE_CHECKS) {
          log.info("Golden form baseline consumed to facts/publication");
          return;
        }
      } else {
        stable = 0;
      }
      Thread.sleep(2000);
    }
    throw new AssertionError("表单基线消费未收敛：\n" + diagnostics());
  }

  private boolean converged() {
    return count("select count(*) from sync_runs where status in ('QUEUED','RUNNING','PAUSED','RETRYING')") == 0
        && count(
                """
                select count(*) from form_change_heads
                 where source_config_id = ? and project_id = 9 and resource_type = 'issue'
                   and resource_id = 969 and template_key = 'issue_response'
                   and status = 'CONSUMED' and latest_change_version > 0
                   and consumed_change_version = latest_change_version
                """,
                configId)
            == 1
        && count(
                """
                select count(*) from form_head_captures c
                  join form_change_heads h on h.id = c.head_id
                 where h.source_config_id = ? and h.project_id = 9
                   and h.resource_type = 'issue' and h.resource_id = 969
                   and h.template_key = 'issue_response' and c.status = 'CONSUMED'
                """,
                configId)
            == 1
        && issueFactConsumed()
        && count(
                """
                select count(*) from read_publication_domains d
                  join read_publications p on p.id = d.current_publication_id
                 where d.producer_kind = 'GITLAB_FACT' and d.source_instance = 'default'
                   and d.fact_type = 'ISSUE' and p.state = 'PUBLISHED'
                """)
            >= 1
        && count(
                """
                select count(*) from read_publications p
                  join read_publication_domains d on d.id = p.domain_id
                 where d.producer_kind = 'GITLAB_FACT' and d.fact_type = 'ISSUE'
                   and p.state in ('BUILDING','PROJECTING','READY_TO_PUBLISH')
                """)
            == 0
        && count(
                "select count(*) from customer_issue_delay_closure_requests where stage <> 'IDLE'")
            == 0
        && count(
                """
                select count(*) from customer_issue_delay_label_writeback_jobs
                 where status in ('PENDING','RUNNING')
                """)
            == 0;
  }

  /**
   * 暂停全部生产者后固定读状态：没有在途运行/候选，且数据集刷新请求全部结算。
   *
   * <p>暂停后仍可能有暂停前已入队的请求；用同一生产方法（每轮一个拥有者）结算，不新增旁路。
   * 状态必须连续稳定，避免在最后一次检查与暂停之间新请求把页面留在 UPDATING。
   */
  void settleReadState(
      com.data.collection.platform.service.read.DatasetRefreshWorker datasets,
      com.data.collection.platform.service.read.MirrorDatasetPublicationWorker mirrors)
      throws InterruptedException {
    long deadline = System.nanoTime() + CONVERGENCE_TIMEOUT.toNanos();
    int stable = 0;
    while (System.nanoTime() < deadline) {
      datasets.publishOneRequestedDataset();
      mirrors.publishOneCompleteTable();
      if (readStateSettled()) {
        stable++;
        // 发布事件与延期闭包可能异步晚到；留足静默窗口，避免下一次检查后才出现新候选。
        if (stable >= 15) {
          log.info("Golden read state settled after pausing producers");
          dumpReadState("after-settle");
          return;
        }
      } else {
        stable = 0;
      }
      Thread.sleep(2000);
    }
    dumpReadState("after-settle-failed");
    throw new AssertionError("表单基线读状态未收敛：\n" + diagnostics());
  }

  /**
   * 写用例还原表之后，用同一生产入口把真实入队的 FACT_REFRESH 跑到终态，避免后续读用例把
   * 在途候选解释为 UPDATING。调度器已被暂停，这里只手动调用其公开执行入口，不新增旁路。
   */
  void settleQueuedWork(
      com.data.collection.platform.service.read.DatasetRefreshWorker datasets,
      com.data.collection.platform.service.read.MirrorDatasetPublicationWorker mirrors)
      throws InterruptedException {
    var dispatcher =
        applicationContext.getBean(com.data.collection.platform.service.sync.SyncRunDispatcherService.class);
    var publicationState =
        applicationContext.getBean(
            com.data.collection.platform.service.sync.SyncFactPublicationStateService.class);
    var projections =
        applicationContext.getBean(com.data.collection.platform.service.FactProjectionTaskRecoveryScheduler.class);
    if (readStateSettled() && factSourcesReadable(publicationState)) {
      return;
    }
    long deadline = System.nanoTime() + QUEUED_WORK_TIMEOUT.toNanos();
    int stable = 0;
    while (System.nanoTime() < deadline) {
      dispatcher.runOnce();
      // 独立投影（refresh-one 等无父运行的候选）只能由生产恢复入口推进，不能停在 PROJECTING。
      projections.recoverExpiredTasks();
      datasets.publishOneRequestedDataset();
      mirrors.publishOneCompleteTable();
      if (readStateSettled() && factSourcesReadable(publicationState)) {
        stable++;
        if (stable >= 3) {
          return;
        }
      } else {
        stable = 0;
      }
      Thread.sleep(1000);
    }
    dumpReadState("queued-work-failed");
    throw new AssertionError("写用例真实派生产出未结算：\n" + diagnostics());
  }

  /** 事实来源资格必须真实可读；只统计真实生产判定，不用“无在途候选”替代。 */
  private boolean factSourcesReadable(
      com.data.collection.platform.service.sync.SyncFactPublicationStateService publicationState) {
    return jdbc.queryForList(
            """
            select distinct source_instance || '|' || fact_type from read_publication_domains
             where producer_kind = 'GITLAB_FACT' order by 1
            """,
            String.class)
        .stream()
        .allMatch(key -> {
          String[] parts = key.split("\\|", 2);
          return publicationState
              .qualification(parts[0], com.data.collection.platform.entity.FactType.valueOf(parts[1]))
              .currentFactReadable();
        });
  }

  /** 首个失败只落盘一次，避免每个失败用例重复写同一份证据文件。 */
  synchronized boolean markFailureDumped(String tag) {
    if (failureDumped != null) {
      return false;
    }
    failureDumped = tag;
    return true;
  }

  /** 把读发布域、候选、数据集请求与镜像任务状态落盘，作为 UPDATING/ATTENTION 的判定证据。 */
  void dumpReadState(String tag) {
    StringBuilder report = new StringBuilder();
    report.append("=== domains ===\n");
    jdbc.queryForList(
            """
            select d.producer_kind || '|' || d.source_instance || '|' || d.fact_type || '|'
              || d.table_key
              || '|pub=' || coalesce(p.state, '<none>') || '@'
              || coalesce(p.published_at::text, '-')
              || '|verified=' || coalesce(d.last_successful_verification_at::text, '-')
              || '|retry=' || coalesce(d.producer_retry_after::text, '-')
              || '|unsettled=' || coalesce(d.unsettled_since::text, '-')
              || '|active=' || (select count(*) from read_publications c
                   where c.domain_id = d.id
                     and c.state in ('BUILDING','PROJECTING','READY_TO_PUBLISH'))
              || '|abortedAfter=' || (select count(*) from read_publications c
                   where c.domain_id = d.id and c.state = 'ABORTED'
                     and c.created_at > greatest(p.published_at,
                           d.last_successful_verification_at))
              from read_publication_domains d
              left join read_publications p on p.id = d.current_publication_id
             order by 1
            """,
            String.class)
        .forEach(line -> report.append(line).append('\n'));
    report.append("=== qualification inputs ===\n");
    jdbc.queryForList(
            """
            select d.producer_kind || '|' || d.fact_type || '|' || d.table_key
              || '|notReady=' || (select count(*) from source_fact_publication_states s
                   where s.source_instance = d.source_instance and s.fact_type = d.fact_type
                     and s.readiness_status <> 'READY')
              || '|fullPending=' || (select coalesce(bool_or(s.full_publication_requested), false)
                   from source_fact_publication_states s
                   where s.source_instance = d.source_instance and s.fact_type = d.fact_type)
              || '|unpublishedHeads=' || (select count(*) from fact_change_heads h
                   where h.source_instance = d.source_instance and h.fact_type = d.fact_type
                     and h.published_version < h.latest_change_version)
              || '|labelVerified=' || (d.fact_type <> 'ISSUE' or exists(
                   select 1 from sync_run_table_states s2
                    where s2.source_instance = d.source_instance
                      and s2.source_table = 'resource_label_events'
                      and s2.sync_enabled and not s2.dirty_flag
                      and s2.last_full_verified_at is not null))
              from read_publication_domains d order by 1
            """,
            String.class)
        .forEach(line -> report.append(line).append('\n'));
    report.append("=== production qualification ===\n");
    var publicationState =
        applicationContext.getBean(
            com.data.collection.platform.service.sync.SyncFactPublicationStateService.class);
    jdbc.queryForList(
            """
            select distinct source_instance || '|' || fact_type
              from read_publication_domains where producer_kind = 'GITLAB_FACT' order by 1
            """,
            String.class)
        .forEach(key -> {
          String[] parts = key.split("\\|", 2);
          var qualification =
              publicationState.qualification(
                  parts[0], com.data.collection.platform.entity.FactType.valueOf(parts[1]));
          report.append(key)
              .append("|current=").append(qualification.currentFactReadable())
              .append("|saved=").append(qualification.savedSnapshotReadable())
              .append("|pending=").append(qualification.pendingUpdates())
              .append("|reason=").append(qualification.reason())
              .append('\n');
        });
    report.append("=== publication states ===\n");
    jdbc.queryForList(
            """
            select source_instance || '|' || fact_type || '|' || readiness_status
              || '|full=' || full_publication_requested || '|revision=' || full_request_revision
              || '|error=' || coalesce(error_message, '-')
              from source_fact_publication_states order by 1
            """,
            String.class)
        .forEach(line -> report.append(line).append('\n'));
    report.append("=== mirror freshness progress ===\n");
    jdbc.queryForList(
            """
            select d.source_instance || '|' || d.table_key
              || '|publishedAt=' || greatest(p.published_at, d.last_successful_verification_at)::text
              || '|progress=' || coalesce((select run.id || ':' || run.status || ':'
                   || coalesce(task.finished_at, run.finished_at, run.created_at)::text
                   from sync_run_table_states state
                   join sync_run_table_tasks task on task.state_id = state.id
                   join sync_runs run on run.id = task.run_id
                  where state.source_instance = d.source_instance
                    and state.mirror_table = d.table_key
                    and (run.status in ('SUBMITTED','QUEUED','RUNNING','RETRYING','PAUSED','CANCELLING')
                         or coalesce(task.finished_at, run.finished_at, run.created_at)
                            > greatest(p.published_at, d.last_successful_verification_at))
                  order by run.id desc, task.id desc limit 1), '-')
              from read_publication_domains d
              join read_publications p on p.id = d.current_publication_id
             where d.producer_kind = 'MIRROR_TABLE' and d.fact_type = 'DATASET'
             order by 1
            """,
            String.class)
        .forEach(line -> report.append(line).append('\n'));
    report.append("=== dataset requests ===\n");
    jdbc.queryForList(
            """
            select producer_kind || '|' || source_instance || '|' || table_key
              || '|req=' || requested_revision || '|done=' || completed_revision
              || '|retryAfter=' || coalesce(retry_after::text, '-')
              || '|error=' || coalesce(last_error, '-')
              from read_dataset_refresh_requests order by 1
            """,
            String.class)
        .forEach(line -> report.append(line).append('\n'));
    report.append("=== recent sync runs ===\n");
    jdbc.queryForList(
            """
            select id || '|' || run_type || '|' || status || '|'
              || coalesce(finished_at::text, started_at::text, created_at::text)
              from sync_runs order by id desc limit 20
            """,
            String.class)
        .forEach(line -> report.append(line).append('\n'));
    report.append("=== mirror states ===\n");
    jdbc.queryForList(
            """
            select s.source_instance || '|' || s.mirror_table
              || '|published=' || coalesce((select p.published_at::text
                   from read_publication_domains d
                   join read_publications p on p.id = d.current_publication_id
                   where d.producer_kind = 'MIRROR_TABLE' and d.fact_type = 'DATASET'
                     and d.source_instance = s.source_instance
                     and d.table_key = s.mirror_table), '-')
              || '|lastTask=' || coalesce((select max(t.finished_at)::text
                   from sync_run_table_tasks t where t.state_id = s.id), '-')
              || '|verified=' || coalesce(s.last_full_verified_at::text, '-')
              || '|dirty=' || s.dirty_flag
              from sync_run_table_states s order by 1
            """,
            String.class)
        .forEach(line -> report.append(line).append('\n'));
    report.append("=== form directory events ===\n");
    jdbc.queryForList(
            "select id || '|' || event_type || '|' || status || '|' || scope_id "
                + "from form_directory_events order by id",
            String.class)
        .forEach(line -> report.append(line).append('\n'));
    try {
      java.nio.file.Path target =
          java.nio.file.Path.of("..", ".tmp", "t2-golden-20261104", "read-state-" + tag + ".txt");
      java.nio.file.Files.createDirectories(target.getParent());
      java.nio.file.Files.writeString(target, report.toString());
      log.info("Golden read-state diagnostics written to {}", target.toAbsolutePath());
    } catch (java.io.IOException error) {
      log.warn("Golden read-state diagnostics write failed", error);
    }
  }

  private boolean readStateSettled() {
    return count("select count(*) from sync_runs where status in ('QUEUED','RUNNING','PAUSED','RETRYING')") == 0
        && count(
                """
                select count(*) from read_publications
                 where state in ('BUILDING','PROJECTING','READY_TO_PUBLISH')
                """)
            == 0
        && count(
                """
                select coalesce(sum(requested_revision - completed_revision), 0)
                  from read_dataset_refresh_requests
                 where table_key = ''
                """)
            == 0;
  }

  private boolean issueFactConsumed() {
    return count(
            """
            select count(*) from issue_fact
             where source_system = 'GITLAB' and source_instance = 'default'
               and project_id = 9 and issue_id = 1228
               and visible_from is not null and visible_to is null and deleted = false
               and planned_resolution_at is not null
               and to_char(planned_resolution_at, 'YYYY-MM-DD') = '2026-09-30'
               and planned_resolution_text = '2026-09-30'
               and planned_merge_version_branch = 'CC2026R3'
               and form_input_hash is not null and length(form_input_hash) = 64
            """)
        == 1;
  }

  /** 用生产同一份冻结输入服务核验事实绑定签名，不手写哈希。 */
  private void assertFactSignature() {
    Object bean = applicationContext.getBean(FormIssueBuildInputProbe.beanType());
    String[] snapshot =
        FormIssueBuildInputProbe.statusAndHash(bean, SOURCE_INSTANCE, PROJECT_ID, ISSUE_IID);
    assertThat(snapshot[0]).as("议题当前输入签名状态").isEqualTo("OK");
    String factHash =
        scalar(
            """
            select form_input_hash from issue_fact
             where source_system = 'GITLAB' and source_instance = 'default'
               and project_id = 9 and issue_id = 1228
               and visible_from is not null and visible_to is null
            """);
    assertThat(snapshot[1]).as("事实必须绑定当前 FORM 输入签名").isEqualTo(factHash);
  }

  private void assertBaselineRowCounts() {
    assertThat(count("select count(*) from form_s1_v1_records")).as("基线记录").isEqualTo(1);
    assertThat(count("select count(*) from form_s1_v1_instances")).as("基线实例").isEqualTo(1);
    assertThat(count("select count(*) from form_record_locations")).as("基线目录").isEqualTo(1);
    assertThat(count("select count(*) from form_record_audit_logs")).as("基线审计").isEqualTo(1);
    assertThat(count("select count(*) from form_change_heads")).as("基线表单 head").isEqualTo(1);
    assertThat(
            count(
                """
                select count(*) from fact_change_heads
                 where source_instance = 'default' and fact_type = 'ISSUE' and root_id = 1228
                """))
        .as("基线事实 head")
        .isEqualTo(1);
    assertThat(
            count(
                """
                select count(*) from form_head_captures c
                  join form_change_heads h on h.id = c.head_id
                 where h.source_config_id = ? and h.project_id = 9
                   and h.resource_type = 'issue' and h.resource_id = 969
                   and h.template_key = 'issue_response'
                """,
                configId))
        .as("基线 head 捕获")
        .isEqualTo(1);
    assertThat(
            count(
                """
                select count(*) from sync_runs
                 where config_id = ? and run_type = 'FACT_REFRESH' and status = 'SUCCESS'
                """,
                configId))
        .as("基线事实刷新必须由真实桥接产生")
        .isGreaterThanOrEqualTo(1);
  }

  /**
   * 断言黄金成功读用例的业务结果；与快照是否已生成无关，缺失快照不得掩盖业务失败。
   *
   * @param path 端点路径
   * @param caseId 用例 id
   * @param responseBody 完整 ApiResponse JSON
   */
  void assertReadExpectation(String path, String caseId, String responseBody) throws Exception {
    if (!"/api/collect-forms/detail".equals(path) || !"golden-record".equals(caseId)) {
      return;
    }
    JsonNode data = objectMapper.readTree(responseBody).path("data");
    assertThat(data.path("templateKey").asText()).isEqualTo(TEMPLATE_KEY);
    assertThat(data.path("scopeId").asText()).isEqualTo("1");
    assertThat(data.path("currentVersionId").asText()).isEqualTo("1");
    assertThat(data.path("storageVersionId").asText()).isEqualTo("1");
    assertThat(data.path("versionLabel").asText()).isEqualTo(VERSION_LABEL);
    assertThat(data.path("writable").asBoolean()).isTrue();
    assertThat(data.path("records").size()).isEqualTo(1);
    JsonNode record = data.path("records").get(0);
    assertThat(record.path("id").asText()).isEqualTo("1");
    assertThat(record.path("recordVersion").asInt()).isEqualTo(1);
    assertThat(record.path("deleted").asBoolean()).isFalse();
    assertThat(record.path("fields").path("problemType").asText()).isEqualTo("DEFECT");
    assertThat(record.path("fields").path("plannedResolutionDate").asText())
        .isEqualTo("2026-09-30");
    assertThat(record.path("fields").path("plannedMergeVersionBranch").asText())
        .isEqualTo(VERSION_LABEL);
    assertThat(record.path("createdAt").asText()).isNotBlank();
  }

  /** 断言按版导出工作簿包含基线有效记录与冻结业务值。 */
  void assertExportExpectation(byte[] content) throws Exception {
    JsonNode root = objectMapper.readTree(GoldenSnapshotSupport.normalizeExcel(content));
    JsonNode sheet = root.path("sheets").get(0);
    assertThat(sheet.path("name").asText()).isEqualTo(VERSION_LABEL);
    JsonNode row = sheet.path("rows").path("2");
    assertThat(row.path("A").asText()).isEqualTo("1");
    assertThat(row.path("B").asText()).isEqualTo(BASE_URL);
    assertThat(row.path("C").asText()).isEqualTo("9");
    assertThat(row.path("D").asText()).isEqualTo("issue");
    assertThat(row.path("E").asText()).isEqualTo("969");
    assertThat(row.path("G").asText())
        .isEqualTo(
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .format(LocalDateTime.now(GoldenBaselineSupport.frozenClock())));
    assertThat(row.path("H").asText()).isEqualTo("1");
    assertThat(row.path("I").asText()).isEqualTo(VERSION_LABEL);
    assertThat(row.path("J").asText()).isEqualTo("有效");
    assertThat(row.path("K").asText()).isEqualTo("DEFECT");
    assertThat(row.path("L").asText()).isEqualTo("黄金基线固定问题原因");
    assertThat(row.path("M").asText()).isEqualTo("黄金基线固定修改方案");
    assertThat(row.path("N").asText()).isEmpty();
    assertThat(row.path("O").asText()).isEqualTo("2026-09-30");
    assertThat(row.path("P").asText()).isEqualTo(VERSION_LABEL);
    assertThat(row.path("Q").asText()).isEqualTo("黄金基线样本");
  }

  /** 断言命令写响应与真实副作用；head 与 AFTER_COMMIT 入队不得被抑制。 */
  void assertWriteExpectation(String path, String caseId, String responseBody) throws Exception {
    JsonNode root = objectMapper.readTree(responseBody);
    if ("/api/collect-forms/records".equals(path) && "golden-create".equals(caseId)) {
      assertThat(root.path("httpStatus").asInt()).isEqualTo(200);
      JsonNode data = root.path("body").path("data");
      assertThat(data.path("outcome").asText()).isEqualTo("CREATED");
      assertThat(data.path("record").path("id").asText()).isEqualTo("2");
      assertThat(data.path("record").path("recordVersion").asInt()).isEqualTo(1);
      assertThat(data.path("record").path("fields").path("problemType").asText())
          .isEqualTo("REQUIREMENT");
      assertThat(count("select count(*) from form_s1_v1_records")).isEqualTo(2);
      assertThat(
              count(
                  """
                  select count(*) from form_change_heads
                   where source_config_id = ? and project_id = 9 and resource_type = 'issue'
                     and resource_id = 969 and template_key = 'issue_response'
                     and latest_change_version = 2 and status = 'PENDING'
                  """,
                  configId))
          .isEqualTo(1);
      assertThat(
              count(
                  """
                  select count(*) from fact_change_heads
                   where source_instance = 'default' and fact_type = 'ISSUE' and root_id = 1228
                     and latest_change_version = 2
                  """))
          .isEqualTo(1);
      assertThat(
              count(
                  """
                  select count(*) from sync_runs
                   where config_id = ? and run_type = 'FACT_REFRESH' and status = 'QUEUED'
                  """,
                  configId))
          .isEqualTo(1);
      return;
    }
    if ("/api/collect-forms/records/{id}".equals(path) && "golden-update".equals(caseId)) {
      assertThat(root.path("httpStatus").asInt()).isEqualTo(200);
      JsonNode body = root.path("body");
      assertThat(body.path("message").asText()).isEqualTo("表单记录已更新");
      assertThat(body.path("data").path("id").asText()).isEqualTo("1");
      assertThat(body.path("data").path("recordVersion").asInt()).isEqualTo(2);
      assertThat(body.path("data").path("fields").path("problemCause").asText())
          .isEqualTo("更新后的问题原因");
      assertThat(
              count(
                  """
                  select count(*) from form_change_heads
                   where source_config_id = ? and project_id = 9 and resource_type = 'issue'
                     and resource_id = 969 and template_key = 'issue_response'
                     and latest_change_version = 2
                  """,
                  configId))
          .isEqualTo(1);
      assertThat(
              count(
                  """
                  select count(*) from sync_runs
                   where config_id = ? and run_type = 'FACT_REFRESH' and status = 'QUEUED'
                  """,
                  configId))
          .isEqualTo(1);
      return;
    }
    if ("/api/collect-forms/records/{id}/void".equals(path) && "golden-void".equals(caseId)) {
      assertThat(root.path("httpStatus").asInt()).isEqualTo(200);
      JsonNode body = root.path("body");
      assertThat(body.path("message").asText()).isEqualTo("表单记录已作废");
      assertThat(body.path("data").path("id").asText()).isEqualTo("1");
      assertThat(body.path("data").path("recordVersion").asInt()).isEqualTo(2);
      assertThat(body.path("data").path("deleted").asBoolean()).isTrue();
      assertThat(
              count(
                  """
                  select count(*) from sync_runs
                   where config_id = ? and run_type = 'FACT_REFRESH' and status = 'QUEUED'
                  """,
                  configId))
          .isEqualTo(1);
    }
  }

  /** 每个 collect-forms 写用例执行后必须回到同一基线：一条有效记录、head 已消费、无残留入队。 */
  void assertBaselineRestored() {
    assertThat(count("select count(*) from form_s1_v1_records")).isEqualTo(1);
    assertThat(count("select count(*) from form_s1_v1_records where deleted = false")).isEqualTo(1);
    assertThat(count("select count(*) from form_record_locations")).isEqualTo(1);
    assertThat(count("select count(*) from form_record_audit_logs")).isEqualTo(1);
    assertThat(
            count(
                """
                select count(*) from form_change_heads
                 where source_config_id = ? and project_id = 9 and resource_type = 'issue'
                   and resource_id = 969 and template_key = 'issue_response'
                   and latest_change_version = 1 and consumed_change_version = 1
                   and status = 'CONSUMED'
                """,
                configId))
        .isEqualTo(1);
    assertThat(
            count(
                """
                select count(*) from sync_runs
                 where config_id = ? and run_type = 'FACT_REFRESH' and status = 'QUEUED'
                """,
                configId))
        .isZero();
  }

  /** 导出用例所需权限：不存在时临时授予，返回是否由本次插入。 */
  static boolean grantExportPermission(JdbcTemplate jdbc) {
    int inserted =
        jdbc.update(
            """
            insert into platform_role_permissions(role_code, permission_code)
            select 'SUPER_ADMIN', 'form.record.export'
             where not exists (
               select 1 from platform_role_permissions
                where role_code = 'SUPER_ADMIN' and permission_code = 'form.record.export')
            """);
    return inserted > 0;
  }

  /** 清除导出用例的全部临时副作用：审计行与本次新增授权。 */
  static void clearExportEvidence(JdbcTemplate jdbc, boolean permissionGranted) {
    jdbc.update("delete from form_record_export_logs");
    if (permissionGranted) {
      jdbc.update(
          "delete from platform_role_permissions where role_code = 'SUPER_ADMIN' and permission_code = 'form.record.export'");
    }
  }

  private String diagnostics() {
    return """
        sync_runs(active)=%d
        form_change_heads=%s
        form_head_captures=%d
        issue_fact=%s
        issue_publication=%s
        issue_inflight=%d
        delay_closure=%s
        writeback_jobs=%d
        """
        .formatted(
            count("select count(*) from sync_runs where status in ('QUEUED','RUNNING','PAUSED','RETRYING')"),
            scalar(
                """
                select coalesce(string_agg(status || ':' || latest_change_version || '/'
                       || consumed_change_version, ','), '<none>')
                  from form_change_heads
                 where source_config_id = ? and project_id = 9 and resource_type = 'issue'
                   and resource_id = 969 and template_key = 'issue_response'
                """,
                configId),
            count(
                """
                select count(*) from form_head_captures c
                  join form_change_heads h on h.id = c.head_id
                 where h.project_id = 9 and h.resource_id = 969
                """),
            scalar(
                """
                select coalesce(string_agg(planned_resolution_at || '/' || planned_resolution_text
                       || '/' || planned_merge_version_branch || '/'
                       || coalesce(form_input_hash, '<null>'), ','), '<none>')
                  from issue_fact
                 where source_instance = 'default' and project_id = 9 and issue_id = 1228
                   and visible_from is not null and visible_to is null
                """),
            scalar(
                """
                select coalesce(string_agg(p.state, ','), '<none>')
                  from read_publication_domains d
                  join read_publications p on p.id = d.current_publication_id
                 where d.producer_kind = 'GITLAB_FACT' and d.source_instance = 'default'
                   and d.fact_type = 'ISSUE'
                """),
            count(
                """
                select count(*) from read_publications p
                  join read_publication_domains d on d.id = p.domain_id
                 where d.producer_kind = 'GITLAB_FACT' and d.fact_type = 'ISSUE'
                   and p.state in ('BUILDING','PROJECTING','READY_TO_PUBLISH')
                """),
            scalar(
                """
                select coalesce(string_agg(stage, ','), '<none>')
                  from customer_issue_delay_closure_requests
                """),
            count(
                """
                select count(*) from customer_issue_delay_label_writeback_jobs
                 where status in ('PENDING','RUNNING')
                """));
  }

  private int count(String sql, Object... args) {
    Integer value = jdbc.queryForObject(sql, Integer.class, args);
    return value == null ? 0 : value;
  }

  private String scalar(String sql, Object... args) {
    List<String> values = jdbc.queryForList(sql, String.class, args);
    return values.isEmpty() ? null : values.getFirst();
  }
}
