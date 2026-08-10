package com.data.collection.platform.bi.infrastructure;

import com.data.collection.platform.bi.domain.port.BiCatContractUnavailableException;
import com.data.collection.platform.bi.domain.source.BiProductVersionScope;
import com.data.collection.platform.bi.infrastructure.BiCatMirrorModels.CatalogNode;
import com.data.collection.platform.bi.infrastructure.BiCatMirrorModels.CatalogProject;
import com.data.collection.platform.bi.infrastructure.BiCatMirrorModels.CatalogSnapshot;
import com.data.collection.platform.bi.infrastructure.BiCatMirrorModels.Config;
import com.data.collection.platform.bi.infrastructure.BiCatMirrorModels.FunctionSnapshot;
import com.data.collection.platform.bi.infrastructure.BiCatMirrorModels.ModuleSnapshot;
import com.data.collection.platform.bi.infrastructure.BiCatMirrorModels.PublishedTestSnapshot;
import com.data.collection.platform.bi.infrastructure.BiCatMirrorModels.RawResponse;
import com.data.collection.platform.bi.infrastructure.BiCatMirrorModels.Run;
import com.data.collection.platform.bi.infrastructure.BiCatMirrorModels.ScopeMapping;
import com.data.collection.platform.bi.infrastructure.BiCatMirrorModels.TestSnapshot;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** CAT 镜像专属 JDBC 边界；所有表均为 bi_cat_*，不访问 GitLab 或兼容模式表。 */
final class BiCatMirrorRepository {
  private final JdbcTemplate jdbcTemplate;
  private final TransactionTemplate transactionTemplate;

  BiCatMirrorRepository(JdbcTemplate jdbcTemplate, TransactionTemplate transactionTemplate) {
    this.jdbcTemplate = jdbcTemplate;
    this.transactionTemplate = transactionTemplate;
  }

  Config loadConfig() {
    return jdbcTemplate.queryForObject(
        """
        select enabled, base_url, auto_sync_enabled, sync_interval_minutes,
               full_compensation_enabled, full_compensation_time
          from bi_cat_mirror_configs
         where id = 1
        """,
        this::mapConfig);
  }

  void saveConfig(Config config, Instant now) {
    Timestamp savedAt = Timestamp.from(now);
    Timestamp nextScheduledAt = config.enabled() && config.autoSyncEnabled() ? savedAt : null;
    transactionTemplate.executeWithoutResult(status -> {
      jdbcTemplate.update(
          """
          update bi_cat_mirror_configs
             set enabled = ?,
                 base_url = ?,
                 auto_sync_enabled = ?,
                 sync_interval_minutes = ?,
                 full_compensation_enabled = ?,
                 full_compensation_time = ?,
                 updated_at = ?
           where id = 1
          """,
          config.enabled(),
          config.baseUrl(),
          config.autoSyncEnabled(),
          config.syncIntervalMinutes(),
          config.fullCompensationEnabled(),
          Time.valueOf(config.fullCompensationTime()),
          savedAt);
      jdbcTemplate.update(
          """
          update bi_cat_sync_state
             set next_scheduled_at = ?,
                 updated_at = ?
           where id = 1
          """,
          nextScheduledAt,
          savedAt);
    });
  }

  List<ScopeMapping> loadMappings() {
    return jdbcTemplate.query(
        """
        select product_version_id, product_version_key, cat_project_id, cat_version_id,
               unit_testing_phase_id, integration_testing_phase_id
          from bi_cat_scope_mappings
         order by product_version_id
        """,
        this::mapScopeMapping);
  }

  void replaceMappings(List<ScopeMapping> mappings, Instant now) {
    transactionTemplate.executeWithoutResult(status -> {
      jdbcTemplate.update("delete from bi_cat_scope_mappings");
      for (ScopeMapping mapping : mappings) {
        jdbcTemplate.update(
            """
            insert into bi_cat_scope_mappings(
                product_version_id, product_version_key, cat_project_id, cat_version_id,
                unit_testing_phase_id, integration_testing_phase_id, updated_at)
            values (?, ?, ?, ?, ?, ?, ?)
            """,
            mapping.productVersionId(),
            mapping.productVersionKey(),
            mapping.catProjectId(),
            mapping.catVersionId(),
            mapping.unitTestingPhaseId(),
            mapping.integrationTestingPhaseId(),
            Timestamp.from(now));
      }
    });
  }

  Optional<CatalogSnapshot> loadPublishedCatalog() {
    List<CatalogHeader> headers = jdbcTemplate.query(
        """
        select snapshot.snapshot_id, snapshot.collected_at
          from bi_cat_catalog_publication publication
          join bi_cat_catalog_snapshots snapshot
            on snapshot.snapshot_id = publication.snapshot_id
         where publication.id = 1
        """,
        (rs, rowNumber) -> new CatalogHeader(
            rs.getObject("snapshot_id", UUID.class),
            rs.getTimestamp("collected_at").toInstant()));
    if (headers.isEmpty()) {
      return Optional.empty();
    }
    CatalogHeader header = headers.getFirst();
    List<CatalogProject> projects = jdbcTemplate.query(
        """
        select cat_project_id, name, note, create_time, create_user_id, default_project
          from bi_cat_catalog_projects
         where snapshot_id = ?
         order by name, cat_project_id
        """,
        this::mapCatalogProject,
        header.snapshotId());
    List<CatalogNode> nodes = jdbcTemplate.query(
        """
        select cat_project_id, node_type, cat_node_id, parent_node_id, name,
               create_time, note, end_time, current_version, disabled, default_project,
               group_id, version_id
          from bi_cat_catalog_nodes
         where snapshot_id = ?
         order by cat_project_id,
                  case node_type when 'VERSION' then 0 else 1 end,
                  name, cat_node_id
        """,
        this::mapCatalogNode,
        header.snapshotId());
    return Optional.of(new CatalogSnapshot(
        header.snapshotId(), header.collectedAt(), projects, nodes, List.of()));
  }

  boolean tryStartRun(
      UUID runId,
      String runType,
      String triggerType,
      Instant now,
      Duration leaseDuration) {
    Boolean started = transactionTemplate.execute(status -> {
      int acquired = jdbcTemplate.update(
          """
          update bi_cat_sync_state
             set active_run_id = ?,
                 lease_expires_at = ?,
                 last_run_id = ?,
                 last_status = 'RUNNING',
                 last_message = 'CAT 全量采集已开始',
                 updated_at = ?
           where id = 1
             and (active_run_id is null or lease_expires_at < ?)
          """,
          runId,
          Timestamp.from(now.plus(leaseDuration)),
          runId,
          Timestamp.from(now),
          Timestamp.from(now));
      if (acquired != 1) {
        return false;
      }
      jdbcTemplate.update(
          """
          insert into bi_cat_sync_runs(
              run_id, run_type, trigger_type, status, started_at, message)
          values (?, ?, ?, 'RUNNING', ?, 'CAT 全量采集已开始')
          """,
          runId,
          runType,
          triggerType,
          Timestamp.from(now));
      return true;
    });
    return Boolean.TRUE.equals(started);
  }

  void heartbeat(UUID runId, Instant now, Duration leaseDuration) {
    int updated = jdbcTemplate.update(
        """
        update bi_cat_sync_state
           set lease_expires_at = ?, updated_at = ?
         where id = 1 and active_run_id = ?
        """,
        Timestamp.from(now.plus(leaseDuration)),
        Timestamp.from(now),
        runId);
    if (updated != 1) {
      throw new IllegalStateException("CAT 同步运行租约已丢失");
    }
  }

  void publishCatalog(UUID runId, CatalogSnapshot snapshot, Instant publishedAt) {
    transactionTemplate.executeWithoutResult(status -> {
      jdbcTemplate.update(
          """
          insert into bi_cat_catalog_snapshots(
              snapshot_id, run_id, collected_at, published_at,
              project_count, version_count, phase_count)
          values (?, ?, ?, ?, ?, ?, ?)
          """,
          snapshot.snapshotId(),
          runId,
          Timestamp.from(snapshot.collectedAt()),
          Timestamp.from(publishedAt),
          snapshot.projects().size(),
          countNodes(snapshot.nodes(), "VERSION"),
          countNodes(snapshot.nodes(), "TEST_PHASE"));
      for (CatalogProject project : snapshot.projects()) {
        jdbcTemplate.update(
            """
            insert into bi_cat_catalog_projects(
                snapshot_id, cat_project_id, name, note, create_time,
                create_user_id, default_project)
            values (?, ?, ?, ?, ?, ?, ?)
            """,
            snapshot.snapshotId(),
            project.id(),
            project.name(),
            project.note(),
            project.createTime(),
            project.createUserId(),
            project.defaultProject());
      }
      for (CatalogNode node : snapshot.nodes()) {
        jdbcTemplate.update(
            """
            insert into bi_cat_catalog_nodes(
                snapshot_id, cat_project_id, node_type, cat_node_id, parent_node_id,
                name, create_time, note, end_time, current_version, disabled,
                default_project, group_id, version_id)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            snapshot.snapshotId(),
            node.projectId(),
            node.nodeType(),
            node.id(),
            node.parentId(),
            node.name(),
            node.createTime(),
            node.note(),
            node.endTime(),
            node.currentVersion(),
            node.disabled(),
            node.defaultProject(),
            node.groupId(),
            node.versionId());
      }
      insertRawResponses(runId, snapshot.snapshotId(), snapshot.rawResponses());
      jdbcTemplate.update(
          """
          insert into bi_cat_catalog_publication(id, snapshot_id, published_version, published_at)
          values (1, ?, 1, ?)
          on conflict (id) do update
             set snapshot_id = excluded.snapshot_id,
                 published_version = bi_cat_catalog_publication.published_version + 1,
                 published_at = excluded.published_at
          """,
          snapshot.snapshotId(),
          Timestamp.from(publishedAt));
      jdbcTemplate.update(
          "update bi_cat_sync_runs set catalog_snapshot_id = ? where run_id = ?",
          snapshot.snapshotId(),
          runId);
    });
  }

  void publishTestSnapshot(UUID runId, TestSnapshot snapshot, Instant publishedAt) {
    transactionTemplate.executeWithoutResult(status -> {
      ScopeMapping mapping = snapshot.mapping();
      jdbcTemplate.update(
          """
          insert into bi_cat_test_snapshots(
              snapshot_id, run_id, product_version_id, product_version_key, test_stage,
              cat_project_id, cat_version_id, cat_testing_phase_id, data_status,
              overall_pass_rate, attained_function_count, total_function_count,
              collection_started_at, collection_finished_at, published_at)
          values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
          """,
          snapshot.snapshotId(),
          runId,
          mapping.productVersionId(),
          mapping.productVersionKey(),
          snapshot.testStage(),
          mapping.catProjectId(),
          mapping.catVersionId(),
          snapshot.testingPhaseId(),
          snapshot.dataStatus(),
          snapshot.overallPassRate(),
          snapshot.attainedFunctionCount(),
          snapshot.totalFunctionCount(),
          Timestamp.from(snapshot.collectionStartedAt()),
          Timestamp.from(snapshot.collectionFinishedAt()),
          Timestamp.from(publishedAt));
      for (ModuleSnapshot module : snapshot.modules()) {
        jdbcTemplate.update(
            """
            insert into bi_cat_test_modules(
                snapshot_id, cat_module_id, module_name, attained_function_count,
                total_function_count, test_pass_rate, display_order)
            values (?, ?, ?, ?, ?, ?, ?)
            """,
            snapshot.snapshotId(),
            module.id(),
            module.name(),
            module.attainedFunctionCount(),
            module.totalFunctionCount(),
            module.passRate(),
            module.displayOrder());
      }
      for (FunctionSnapshot function : snapshot.functions()) {
        jdbcTemplate.update(
            """
            insert into bi_cat_test_functions(
                snapshot_id, cat_module_id, cat_function_id, function_name,
                function_label, test_pass_rate, display_order)
            values (?, ?, ?, ?, ?, ?, ?)
            """,
            snapshot.snapshotId(),
            function.moduleId(),
            function.id(),
            function.name(),
            function.label(),
            function.passRate(),
            function.displayOrder());
      }
      insertRawResponses(runId, snapshot.snapshotId(), snapshot.rawResponses());
      jdbcTemplate.update(
          """
          insert into bi_cat_test_publications(
              product_version_id, test_stage, snapshot_id, published_version, published_at)
          values (?, ?, ?, 1, ?)
          on conflict (product_version_id, test_stage) do update
             set snapshot_id = excluded.snapshot_id,
                 published_version = bi_cat_test_publications.published_version + 1,
                 published_at = excluded.published_at
          """,
          mapping.productVersionId(),
          snapshot.testStage(),
          snapshot.snapshotId(),
          Timestamp.from(publishedAt));
    });
  }

  Optional<PublishedTestSnapshot> loadPublishedTestSnapshot(
      BiProductVersionScope scope,
      String testStage) {
    List<PublishedHeader> headers = jdbcTemplate.query(
        """
        select snapshot.snapshot_id, publication.published_version, snapshot.data_status,
               snapshot.overall_pass_rate, snapshot.attained_function_count,
               snapshot.total_function_count
          from bi_cat_scope_mappings mapping
          join bi_cat_test_publications publication
            on publication.product_version_id = mapping.product_version_id
           and publication.test_stage = ?
          join bi_cat_test_snapshots snapshot
            on snapshot.snapshot_id = publication.snapshot_id
         where mapping.product_version_id = ?
           and upper(mapping.product_version_key) = upper(?)
        """,
        this::mapPublishedHeader,
        testStage,
        scope.id(),
        scope.businessKey());
    if (headers.isEmpty()) {
      return Optional.empty();
    }
    PublishedHeader header = headers.getFirst();
    List<ModuleSnapshot> modules = jdbcTemplate.query(
        """
        select cat_module_id, module_name, attained_function_count,
               total_function_count, test_pass_rate, display_order
          from bi_cat_test_modules
         where snapshot_id = ?
         order by display_order, cat_module_id
        """,
        this::mapModule,
        header.snapshotId());
    List<FunctionSnapshot> functions = jdbcTemplate.query(
        """
        select cat_module_id, cat_function_id, function_name, function_label,
               test_pass_rate, display_order
          from bi_cat_test_functions
         where snapshot_id = ?
         order by cat_module_id, display_order, cat_function_id
        """,
        this::mapFunction,
        header.snapshotId());
    return Optional.of(new PublishedTestSnapshot(
        header.snapshotId(),
        header.publishedVersion(),
        header.dataStatus(),
        header.overallPassRate(),
        header.attainedFunctionCount(),
        header.totalFunctionCount(),
        modules,
        functions));
  }

  String requireCurrentSourceVersion(BiProductVersionScope scope, String testStage) {
    PublishedTestSnapshot snapshot = loadPublishedTestSnapshot(scope, testStage)
        .orElseThrow(() -> new BiCatContractUnavailableException(
            "当前产品版本尚未发布 CAT " + stageLabel(testStage) + "镜像"));
    return sourceVersion(scope.id(), testStage, snapshot.publishedVersion());
  }

  void finishRun(
      UUID runId,
      String runType,
      String status,
      int publishedStageCount,
      int failedStageCount,
      String message,
      Instant now,
      int syncIntervalMinutes) {
    transactionTemplate.executeWithoutResult(transaction -> {
      jdbcTemplate.update(
          """
          update bi_cat_sync_runs
             set status = ?, finished_at = ?, published_stage_count = ?,
                 failed_stage_count = ?, message = ?
           where run_id = ?
          """,
          status,
          Timestamp.from(now),
          publishedStageCount,
          failedStageCount,
          compactMessage(message),
          runId);
      jdbcTemplate.update(
          """
          update bi_cat_sync_state
             set active_run_id = null,
                 lease_expires_at = null,
                 next_scheduled_at = ?,
                 last_compensation_at = case when ? = 'FULL_COMPENSATION' then ?
                                             else last_compensation_at end,
                 last_success_at = case when ? in ('SUCCEEDED', 'PARTIAL_SUCCESS') then ?
                                        else last_success_at end,
                 last_status = ?,
                 last_message = ?,
                 updated_at = ?
           where id = 1 and active_run_id = ?
          """,
          Timestamp.from(now.plus(Duration.ofMinutes(syncIntervalMinutes))),
          runType,
          Timestamp.from(now),
          status,
          Timestamp.from(now),
          status,
          compactMessage(message),
          Timestamp.from(now),
          runId);
    });
  }

  Optional<String> scheduledRunType(Instant now, ZoneId zoneId) {
    Config config = loadConfig();
    if (!config.enabled() || !config.autoSyncEnabled()) {
      return Optional.empty();
    }
    ScheduleState state = jdbcTemplate.queryForObject(
        """
        select active_run_id, lease_expires_at, next_scheduled_at, last_compensation_at
          from bi_cat_sync_state where id = 1
        """,
        this::mapScheduleState);
    if (state == null
        || (state.activeRunId() != null
            && state.leaseExpiresAt() != null
            && !state.leaseExpiresAt().isBefore(now))) {
      return Optional.empty();
    }
    LocalDate today = now.atZone(zoneId).toLocalDate();
    boolean compensationDue = config.fullCompensationEnabled()
        && !now.atZone(zoneId).toLocalTime().isBefore(config.fullCompensationTime())
        && (state.lastCompensationAt() == null
            || state.lastCompensationAt().atZone(zoneId).toLocalDate().isBefore(today));
    if (compensationDue) {
      return Optional.of("FULL_COMPENSATION");
    }
    return state.nextScheduledAt() == null || !state.nextScheduledAt().isAfter(now)
        ? Optional.of("FULL")
        : Optional.empty();
  }

  List<Run> recentRuns(int limit) {
    return jdbcTemplate.query(
        """
        select run_id, run_type, trigger_type, status, started_at, finished_at,
               catalog_snapshot_id, published_stage_count, failed_stage_count, message
          from bi_cat_sync_runs
         order by started_at desc
         limit ?
        """,
        this::mapRun,
        limit);
  }

  void pruneSnapshots(int retainedSnapshotCount) {
    transactionTemplate.executeWithoutResult(status -> {
      List<UUID> staleTestSnapshots = jdbcTemplate.queryForList(
          """
          select snapshot_id
            from (
              select snapshot_id,
                     row_number() over (
                       partition by product_version_id, test_stage
                       order by published_at desc, snapshot_id desc) as position
                from bi_cat_test_snapshots
            ) ranked
           where position > ?
          """,
          UUID.class,
          retainedSnapshotCount);
      deleteSnapshots("bi_cat_test_snapshots", staleTestSnapshots);
      List<UUID> staleCatalogSnapshots = jdbcTemplate.queryForList(
          """
          select snapshot_id
            from bi_cat_catalog_snapshots
           order by published_at desc, snapshot_id desc
          offset ?
          """,
          UUID.class,
          retainedSnapshotCount);
      deleteSnapshots("bi_cat_catalog_snapshots", staleCatalogSnapshots);
    });
  }

  static String sourceVersion(long productVersionId, String testStage, long publishedVersion) {
    return "cat-mirror:" + productVersionId + ":" + testStage + ":" + publishedVersion;
  }

  private void deleteSnapshots(String tableName, List<UUID> snapshotIds) {
    for (UUID snapshotId : snapshotIds) {
      jdbcTemplate.update(
          "delete from bi_cat_raw_responses where snapshot_id = ?", snapshotId);
      jdbcTemplate.update("delete from " + tableName + " where snapshot_id = ?", snapshotId);
    }
  }

  private void insertRawResponses(
      UUID runId,
      UUID snapshotId,
      List<RawResponse> rawResponses) {
    for (RawResponse response : rawResponses) {
      jdbcTemplate.update(
          """
          insert into bi_cat_raw_responses(
              run_id, snapshot_id, operation, request_key, request_payload, response_payload)
          values (?, ?, ?, ?, cast(? as jsonb), cast(? as jsonb))
          """,
          runId,
          snapshotId,
          response.operation(),
          response.requestKey(),
          response.requestPayload(),
          response.responsePayload());
    }
  }

  private Config mapConfig(ResultSet rs, int rowNumber) throws SQLException {
    return new Config(
        rs.getBoolean("enabled"),
        rs.getString("base_url"),
        rs.getBoolean("auto_sync_enabled"),
        rs.getInt("sync_interval_minutes"),
        rs.getBoolean("full_compensation_enabled"),
        rs.getTime("full_compensation_time").toLocalTime());
  }

  private ScopeMapping mapScopeMapping(ResultSet rs, int rowNumber) throws SQLException {
    return new ScopeMapping(
        rs.getLong("product_version_id"),
        rs.getString("product_version_key"),
        rs.getString("cat_project_id"),
        rs.getString("cat_version_id"),
        rs.getString("unit_testing_phase_id"),
        rs.getString("integration_testing_phase_id"));
  }

  private CatalogProject mapCatalogProject(ResultSet rs, int rowNumber) throws SQLException {
    return new CatalogProject(
        rs.getString("cat_project_id"),
        rs.getString("name"),
        rs.getString("note"),
        rs.getString("create_time"),
        rs.getString("create_user_id"),
        rs.getObject("default_project", Boolean.class));
  }

  private CatalogNode mapCatalogNode(ResultSet rs, int rowNumber) throws SQLException {
    return new CatalogNode(
        rs.getString("cat_project_id"),
        rs.getString("node_type"),
        rs.getString("cat_node_id"),
        rs.getString("parent_node_id"),
        rs.getString("name"),
        rs.getString("create_time"),
        rs.getString("note"),
        rs.getString("end_time"),
        rs.getObject("current_version", Boolean.class),
        rs.getObject("disabled", Boolean.class),
        rs.getObject("default_project", Boolean.class),
        rs.getString("group_id"),
        rs.getString("version_id"));
  }

  private PublishedHeader mapPublishedHeader(ResultSet rs, int rowNumber) throws SQLException {
    return new PublishedHeader(
        rs.getObject("snapshot_id", UUID.class),
        rs.getLong("published_version"),
        rs.getString("data_status"),
        rs.getBigDecimal("overall_pass_rate"),
        rs.getObject("attained_function_count", Long.class),
        rs.getObject("total_function_count", Long.class));
  }

  private ModuleSnapshot mapModule(ResultSet rs, int rowNumber) throws SQLException {
    return new ModuleSnapshot(
        rs.getString("cat_module_id"),
        rs.getString("module_name"),
        rs.getLong("attained_function_count"),
        rs.getLong("total_function_count"),
        rs.getBigDecimal("test_pass_rate"),
        rs.getInt("display_order"));
  }

  private FunctionSnapshot mapFunction(ResultSet rs, int rowNumber) throws SQLException {
    return new FunctionSnapshot(
        rs.getString("cat_module_id"),
        rs.getString("cat_function_id"),
        rs.getString("function_name"),
        rs.getString("function_label"),
        rs.getBigDecimal("test_pass_rate"),
        rs.getInt("display_order"));
  }

  private ScheduleState mapScheduleState(ResultSet rs, int rowNumber) throws SQLException {
    return new ScheduleState(
        rs.getObject("active_run_id", UUID.class),
        instant(rs, "lease_expires_at"),
        instant(rs, "next_scheduled_at"),
        instant(rs, "last_compensation_at"));
  }

  private Run mapRun(ResultSet rs, int rowNumber) throws SQLException {
    return new Run(
        rs.getObject("run_id", UUID.class),
        rs.getString("run_type"),
        rs.getString("trigger_type"),
        rs.getString("status"),
        rs.getTimestamp("started_at").toInstant(),
        instant(rs, "finished_at"),
        rs.getObject("catalog_snapshot_id", UUID.class),
        rs.getInt("published_stage_count"),
        rs.getInt("failed_stage_count"),
        rs.getString("message"));
  }

  private Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp value = rs.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }

  private int countNodes(List<CatalogNode> nodes, String nodeType) {
    return Math.toIntExact(nodes.stream().filter(node -> nodeType.equals(node.nodeType())).count());
  }

  private String stageLabel(String testStage) {
    return "UNIT_TEST".equals(testStage) ? "单元测试" : "集成测试";
  }

  private String compactMessage(String message) {
    String normalized = message == null ? "" : message.strip();
    return normalized.length() <= 2000 ? normalized : normalized.substring(0, 2000);
  }

  private record CatalogHeader(UUID snapshotId, Instant collectedAt) {}

  private record PublishedHeader(
      UUID snapshotId,
      long publishedVersion,
      String dataStatus,
      java.math.BigDecimal overallPassRate,
      Long attainedFunctionCount,
      Long totalFunctionCount) {}

  private record ScheduleState(
      UUID activeRunId,
      Instant leaseExpiresAt,
      Instant nextScheduledAt,
      Instant lastCompensationAt) {}
}
