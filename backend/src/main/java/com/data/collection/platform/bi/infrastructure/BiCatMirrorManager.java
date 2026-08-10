package com.data.collection.platform.bi.infrastructure;

import com.data.collection.platform.bi.domain.model.BiProductVersionOption;
import com.data.collection.platform.bi.infrastructure.BiCatMirrorModels.CatalogSnapshot;
import com.data.collection.platform.bi.infrastructure.BiCatMirrorModels.Config;
import com.data.collection.platform.bi.infrastructure.BiCatMirrorModels.Run;
import com.data.collection.platform.bi.infrastructure.BiCatMirrorModels.ScopeMapping;
import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.service.IssueScopeCatalogService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** CAT 镜像的薄 Spring 生命周期壳，负责设置 API、单任务执行器和定时触发。 */
@Component
public class BiCatMirrorManager {
  private static final Logger log = LoggerFactory.getLogger(BiCatMirrorManager.class);
  private static final ZoneId PLATFORM_ZONE = ZoneId.of("Asia/Shanghai");
  private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

  private final BiCatMirrorRepository repository;
  private final BiCatMirrorSyncService syncService;
  private final BiPlatformProductVersionAdapter productVersions;
  private final BiCatProperties properties;
  private final BiCatScopeMappingRecommender mappingRecommender;
  private final Clock clock;
  private final ExecutorService executor;

  @Autowired
  public BiCatMirrorManager(
      JdbcTemplate jdbcTemplate,
      TransactionTemplate transactionTemplate,
      IssueScopeCatalogService catalogService,
      BiCatProperties properties,
      ObjectMapper objectMapper) {
    this(
        new BiCatMirrorRepository(jdbcTemplate, transactionTemplate),
        new BiPlatformProductVersionAdapter(catalogService),
        properties,
        objectMapper,
        Clock.systemUTC(),
        Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("bi-cat-mirror-", 0).factory()));
  }

  BiCatMirrorManager(
      BiCatMirrorRepository repository,
      BiPlatformProductVersionAdapter productVersions,
      BiCatProperties properties,
      ObjectMapper objectMapper,
      Clock clock,
      ExecutorService executor) {
    this.repository = repository;
    this.productVersions = productVersions;
    this.properties = properties;
    this.mappingRecommender = new BiCatScopeMappingRecommender();
    this.clock = clock;
    this.executor = executor;
    this.syncService = new BiCatMirrorSyncService(repository, properties, objectMapper, clock);
  }

  /** 返回 CAT 配置、真实目录、产品版本映射和最近运行，供系统设置页一次加载。 */
  public Settings settings() {
    Config config = repository.loadConfig();
    var productVersionCatalog = productVersions.catalog();
    List<MappingView> mappings = repository.loadMappings().stream()
        .map(this::mappingView)
        .toList();
    CatalogSnapshot catalogSnapshot = repository.loadPublishedCatalog().orElse(null);
    CatalogView catalog = catalogSnapshot == null ? null : catalogView(catalogSnapshot);
    List<MappingSuggestionView> mappingSuggestions = catalogSnapshot == null
        ? List.of()
        : mappingRecommender.recommend(productVersionCatalog, catalogSnapshot).stream()
            .map(this::mappingSuggestionView)
            .toList();
    List<RunView> runs = repository.recentRuns(10).stream().map(this::runView).toList();
    boolean synchronizing = runs.stream().findFirst()
        .map(run -> "RUNNING".equals(run.status()))
        .orElse(false);
    return new Settings(
        configView(config),
        productVersionCatalog.versions(),
        mappings,
        mappingSuggestions,
        catalog,
        runs,
        synchronizing);
  }

  /** 保存 CAT 连接和全量同步策略；路径仍由部署配置控制。 */
  public ConfigView saveConfig(SaveConfig command) {
    Config config = validatedConfig(command);
    repository.saveConfig(config, clock.instant());
    return configView(config);
  }

  /** 以平台稳定产品版本 ID 为权威，保存其到 CAT 项目、版本和两个阶段的映射。 */
  public List<MappingView> saveMappings(List<SaveMapping> commands) {
    CatalogSnapshot catalog = repository.loadPublishedCatalog()
        .orElseThrow(() -> new BizException("请先执行一次 CAT 全量同步以获取项目和阶段目录"));
    List<ScopeMapping> mappings = new ArrayList<>();
    Set<Long> productVersionIds = new HashSet<>();
    for (SaveMapping command : commands == null ? List.<SaveMapping>of() : commands) {
      if (!productVersionIds.add(command.productVersionId())) {
        throw new BizException("同一 BI 产品版本只能配置一条 CAT 映射");
      }
      var scope = productVersions.requireScope(command.productVersionId());
      ScopeMapping mapping = new ScopeMapping(
          scope.id(),
          scope.businessKey(),
          requireText(command.catProjectId(), "CAT 项目 ID"),
          requireText(command.catVersionId(), "CAT 版本 ID"),
          requireText(command.unitTestingPhaseId(), "CAT 单元测试阶段 ID"),
          requireText(command.integrationTestingPhaseId(), "CAT 集成测试阶段 ID"));
      validateCatalogMapping(catalog, mapping);
      mappings.add(mapping);
    }
    repository.replaceMappings(mappings, clock.instant());
    return mappings.stream().map(this::mappingView).toList();
  }

  /** 使用当前已保存配置读取完整 CAT 目录，但不发布或修改镜像。 */
  public ConnectionView testConnection() {
    try {
      var result = syncService.testConnection();
      return new ConnectionView(
          true,
          "CAT 连接成功，共读取 " + result.projectCount() + " 个项目、"
              + result.versionCount() + " 个版本、" + result.phaseCount() + " 个测试阶段",
          result.projectCount(),
          result.versionCount(),
          result.phaseCount());
    } catch (RuntimeException failure) {
      throw new BizException("CAT 连接失败：" + safeMessage(failure));
    }
  }

  /** 提交手动全量同步，返回后由唯一后台任务继续执行。 */
  public Submission startFullSync() {
    return submit("FULL", "MANUAL");
  }

  /** 提交手动全量补偿；采集算法与全量同步相同，运行身份独立可审计。 */
  public Submission startFullCompensationSync() {
    return submit("FULL_COMPENSATION", "MANUAL");
  }

  /** 每分钟检查全量或每日补偿是否到期，不在调度线程中执行网络采集。 */
  @Scheduled(
      fixedDelayString = "${platform.bi.cat.scheduler-delay-ms:60000}",
      initialDelayString = "${platform.bi.cat.initial-delay-ms:30000}")
  public void scheduleIfDue() {
    try {
      repository.scheduledRunType(clock.instant(), PLATFORM_ZONE)
          .ifPresent(runType -> submit(runType, "SCHEDULED"));
    } catch (RuntimeException failure) {
      log.warn("bi_cat_schedule_check_failed message={}", failure.getMessage());
    }
  }

  BiCatMirrorRepository repository() {
    return repository;
  }

  @PreDestroy
  public void close() {
    executor.shutdown();
  }

  private Submission submit(String runType, String triggerType) {
    Config config = repository.loadConfig();
    if (!config.enabled()) {
      throw new BizException("请先启用 CAT 数据镜像");
    }
    BiCatMirrorSyncService.requireBaseUri(config.baseUrl());
    UUID runId = UUID.randomUUID();
    Duration lease = Duration.ofMinutes(properties.getLeaseMinutes());
    Instant startedAt = clock.instant();
    if (!repository.tryStartRun(runId, runType, triggerType, startedAt, lease)) {
      return new Submission(false, null, "RUNNING", "已有 CAT 同步正在运行");
    }
    try {
      executor.execute(() -> syncService.execute(runId, runType));
      return new Submission(true, runId, "RUNNING", "CAT 全量同步已提交");
    } catch (RejectedExecutionException rejected) {
      repository.finishRun(
          runId,
          runType,
          "FAILED",
          0,
          0,
          "CAT 同步执行器不可用",
          clock.instant(),
          config.syncIntervalMinutes());
      throw new BizException("CAT 同步执行器不可用");
    }
  }

  private Config validatedConfig(SaveConfig command) {
    if (command == null) {
      throw new BizException("CAT 镜像配置不能为空");
    }
    String baseUrl = BiCatMirrorSyncService.requireBaseUri(command.baseUrl()).toString();
    requireRange(command.syncIntervalMinutes(), 5, 10080, "同步间隔");
    LocalTime compensationTime;
    try {
      compensationTime = LocalTime.parse(requireText(
          command.fullCompensationTime(), "全量补偿时间"));
    } catch (java.time.format.DateTimeParseException invalid) {
      throw new BizException("全量补偿时间必须使用 HH:mm 或 HH:mm:ss 格式");
    }
    return new Config(
        command.enabled(),
        baseUrl,
        command.autoSyncEnabled(),
        command.syncIntervalMinutes(),
        command.fullCompensationEnabled(),
        compensationTime);
  }

  private void validateCatalogMapping(CatalogSnapshot catalog, ScopeMapping mapping) {
    boolean projectExists = catalog.projects().stream()
        .anyMatch(project -> mapping.catProjectId().equals(project.id()));
    boolean versionExists = catalog.nodes().stream()
        .anyMatch(node -> "VERSION".equals(node.nodeType())
            && mapping.catProjectId().equals(node.projectId())
            && mapping.catVersionId().equals(node.id()));
    boolean unitExists = phaseExists(catalog, mapping, mapping.unitTestingPhaseId());
    boolean integrationExists = phaseExists(
        catalog, mapping, mapping.integrationTestingPhaseId());
    if (!projectExists || !versionExists || !unitExists || !integrationExists) {
      throw new BizException(
          "BI 产品版本 " + mapping.productVersionKey() + " 的 CAT 映射与已发布目录不一致");
    }
  }

  private boolean phaseExists(
      CatalogSnapshot catalog,
      ScopeMapping mapping,
      String phaseId) {
    return catalog.nodes().stream().anyMatch(node ->
        "TEST_PHASE".equals(node.nodeType())
            && mapping.catProjectId().equals(node.projectId())
            && mapping.catVersionId().equals(node.versionId())
            && phaseId.equals(node.id()));
  }

  private ConfigView configView(Config config) {
    return new ConfigView(
        config.enabled(),
        config.baseUrl(),
        config.autoSyncEnabled(),
        config.syncIntervalMinutes(),
        config.fullCompensationEnabled(),
        config.fullCompensationTime().format(TIME_FORMAT));
  }

  private MappingView mappingView(ScopeMapping mapping) {
    return new MappingView(
        mapping.productVersionId(),
        mapping.productVersionKey(),
        mapping.catProjectId(),
        mapping.catVersionId(),
        mapping.unitTestingPhaseId(),
        mapping.integrationTestingPhaseId());
  }

  private MappingSuggestionView mappingSuggestionView(
      BiCatScopeMappingRecommender.Suggestion suggestion) {
    return new MappingSuggestionView(
        suggestion.productVersionId(),
        suggestion.catProjectId(),
        suggestion.catVersionId(),
        suggestion.unitTestingPhaseId(),
        suggestion.integrationTestingPhaseId());
  }

  private CatalogView catalogView(CatalogSnapshot catalog) {
    return new CatalogView(
        catalog.snapshotId(),
        catalog.collectedAt(),
        catalog.projects().stream()
            .map(project -> new CatalogProjectView(
                project.id(),
                project.name(),
                project.note(),
                project.createTime(),
                project.createUserId(),
                project.defaultProject()))
            .toList(),
        catalog.nodes().stream()
            .map(node -> new CatalogNodeView(
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
                node.versionId()))
            .toList());
  }

  private RunView runView(Run run) {
    return new RunView(
        run.runId(),
        run.runType(),
        run.triggerType(),
        run.status(),
        run.startedAt(),
        run.finishedAt(),
        run.catalogSnapshotId(),
        run.publishedStageCount(),
        run.failedStageCount(),
        run.message());
  }

  private String requireText(String value, String label) {
    if (value == null || value.isBlank()) {
      throw new BizException(label + "不能为空");
    }
    return value.trim();
  }

  private void requireRange(int value, int minimum, int maximum, String label) {
    if (value < minimum || value > maximum) {
      throw new BizException(label + "必须在 " + minimum + " 至 " + maximum + " 之间");
    }
  }

  private String safeMessage(Throwable failure) {
    String message = failure.getMessage();
    return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
  }

  public record SaveConfig(
      boolean enabled,
      String baseUrl,
      boolean autoSyncEnabled,
      int syncIntervalMinutes,
      boolean fullCompensationEnabled,
      String fullCompensationTime) {}

  public record SaveMapping(
      long productVersionId,
      String catProjectId,
      String catVersionId,
      String unitTestingPhaseId,
      String integrationTestingPhaseId) {}

  public record ConfigView(
      boolean enabled,
      String baseUrl,
      boolean autoSyncEnabled,
      int syncIntervalMinutes,
      boolean fullCompensationEnabled,
      String fullCompensationTime) {}

  public record MappingView(
      long productVersionId,
      String productVersionKey,
      String catProjectId,
      String catVersionId,
      String unitTestingPhaseId,
      String integrationTestingPhaseId) {}

  /** CAT 真实目录生成的可编辑建议；空字段表示目录无法形成唯一结论。 */
  public record MappingSuggestionView(
      long productVersionId,
      String catProjectId,
      String catVersionId,
      String unitTestingPhaseId,
      String integrationTestingPhaseId) {}

  public record CatalogProjectView(
      String id,
      String name,
      String note,
      String createTime,
      String createUserId,
      Boolean defaultProject) {}

  public record CatalogNodeView(
      String projectId,
      String nodeType,
      String id,
      String parentId,
      String name,
      String createTime,
      String note,
      String endTime,
      Boolean currentVersion,
      Boolean disabled,
      Boolean defaultProject,
      String groupId,
      String versionId) {}

  public record CatalogView(
      UUID snapshotId,
      Instant collectedAt,
      List<CatalogProjectView> projects,
      List<CatalogNodeView> nodes) {}

  public record RunView(
      UUID runId,
      String runType,
      String triggerType,
      String status,
      Instant startedAt,
      Instant finishedAt,
      UUID catalogSnapshotId,
      int publishedStageCount,
      int failedStageCount,
      String message) {}

  public record Settings(
      ConfigView config,
      List<BiProductVersionOption> productVersions,
      List<MappingView> mappings,
      List<MappingSuggestionView> mappingSuggestions,
      CatalogView catalog,
      List<RunView> recentRuns,
      boolean synchronizing) {}

  public record ConnectionView(
      boolean success,
      String message,
      int projectCount,
      int versionCount,
      int phaseCount) {}

  public record Submission(
      boolean accepted,
      UUID runId,
      String status,
      String message) {}
}
