package com.data.collection.platform.service.statistics;

import com.data.collection.platform.common.JsonUtils;
import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.entity.FactProjectionScope;
import com.data.collection.platform.entity.FactType;
import com.data.collection.platform.service.read.CandidateCacheArtifactStore;
import com.data.collection.platform.entity.statistics.StatisticBoardDefinition;
import com.data.collection.platform.entity.statistics.StatisticBoardMeta;
import com.data.collection.platform.entity.statistics.StatisticBoardResponse;
import com.data.collection.platform.entity.statistics.StatisticRowData;
import com.fasterxml.jackson.core.type.TypeReference;
import com.data.collection.platform.service.FactProjectionVersionService;
import com.data.collection.platform.service.FactProjectionPublicationGuardService;
import com.data.collection.platform.service.GitlabSourceInstanceSupport;
import com.data.collection.platform.service.sync.SyncFactPublicationStateService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class StatisticBoardSnapshotService {
  private static final TypeReference<List<StatisticRowData>> ROWS_TYPE = new TypeReference<>() {};
  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

  private final JdbcTemplate jdbcTemplate;
  private final JsonUtils jsonUtils;
  private final FactProjectionVersionService projectionVersionService;
  private final FactProjectionPublicationGuardService publicationGuardService;
  private final SyncFactPublicationStateService publicationStateService;
  private final TransactionTemplate readConsistencyTemplate;

  public StatisticBoardSnapshotService(
      JdbcTemplate jdbcTemplate,
      JsonUtils jsonUtils,
      FactProjectionVersionService projectionVersionService,
      SyncFactPublicationStateService publicationStateService,
      FactProjectionPublicationGuardService publicationGuardService,
      PlatformTransactionManager transactionManager) {
    this.jdbcTemplate = jdbcTemplate;
    this.jsonUtils = jsonUtils;
    this.projectionVersionService = projectionVersionService;
    this.publicationStateService = publicationStateService;
    this.publicationGuardService = publicationGuardService;
    this.readConsistencyTemplate = new TransactionTemplate(transactionManager);
    this.readConsistencyTemplate.setIsolationLevel(
        TransactionDefinition.ISOLATION_REPEATABLE_READ);
    this.readConsistencyTemplate.setReadOnly(false);
    this.readConsistencyTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
  }

  private Optional<Snapshot> findReady(SnapshotRequest request, String sourceVersion) {
    String filterHash = filterHash(request.cacheFilterPayload());
    List<Snapshot> rows =
        jdbcTemplate.query(
            """
            select *
              from statistic_board_snapshots
             where board_key = ?
               and scope_key = ?
               and rule_version = ?
               and source_version = ?
               and filter_hash = ?
               and status = 'READY'
             order by refreshed_at desc, id desc
             limit 1
            """,
            this::mapSnapshot,
            request.boardKey(),
            request.scopeKey(),
            request.ruleVersion(),
            sourceVersion,
            filterHash);
    return rows.stream().findFirst();
  }

  /** 在固定完整版本中命中或计算统计；缓存缺失不改变完整事实的可读性。 */
  public StatisticBoardResponse readOrRefresh(
      com.data.collection.platform.entity.read.FactReadSelection selection,
      SnapshotRequest request, Function<SourceRead, StatisticBoardResponse> responseSupplier) {
    return withinVersionedRead(selection, request.readScopes(), sourceRead -> {
      Optional<Snapshot> cached = findReady(request, selection.sourceVersion());
      StatisticBoardResponse response = cached.isPresent()
          ? request.toResponse(cached.get()) : responseSupplier.apply(sourceRead);
      if (cached.isEmpty()) { save(selection, request, response); }
      return sourceRead.attachFreshness(response);
    });
  }

  /** 短事务保护显式版本；生产未结算不拒绝公开身份，候选只由内部生产边界提供。 */
  public <T> T withinVersionedRead(
      com.data.collection.platform.entity.read.FactReadSelection selection,
      SourceReadPlan readScopes, Function<SourceRead, T> action) {
    T result = readConsistencyTemplate.execute(status -> {
      publicationGuardService.protectRead(selection);
      Set<FactProjectionScope> scopes = readScopes.resolve();
      long pending = sourceInstancesOf(scopes).stream()
          .mapToLong(source -> publicationStateService.countUnpublishedTargets(source, factTypeOf(scopes))).sum();
      return action.apply(new SourceRead(scopes, selection.sourceVersion(), pending, selection));
    });
    return java.util.Objects.requireNonNull(result, "版本读取事务未返回结果");
  }

  private static FactType factTypeOf(Set<FactProjectionScope> scopes) {
    return scopes.iterator().next().factType();
  }

  private static Set<String> sourceInstancesOf(Set<FactProjectionScope> scopes) {
    TreeSet<String> sources = new TreeSet<>();
    for (FactProjectionScope scope : scopes) {
      sources.add(scope.sourceInstance());
    }
    return sources;
  }

  /** 固定发布组合缓存；候选仅写 PREPARED，统一发布后才能公开。 */
  public void save(com.data.collection.platform.entity.read.FactReadSelection selection,
      SnapshotRequest request, StatisticBoardResponse response) {
    String sourceVersion = selection.sourceVersion();
    boolean candidate = selection.contexts().stream().anyMatch(context -> context.candidate());
    Map<String, Object> metaPayload =
        Map.of(
            "generatedAt", response.meta().generatedAt().toString(),
            "queryDurationMs", response.meta().queryDurationMs(),
            "rowCount", response.meta().rowCount(),
            "columnCount", response.meta().columnCount(),
            "drilldownColumnCount", response.meta().drilldownColumnCount());
    Object[] args = new Object[] {
        request.boardKey(),
        request.scopeKey(),
        request.ruleVersion(),
        sourceVersion,
        filterHash(request.cacheFilterPayload()),
        jsonUtils.toJson(request.cacheFilterPayload()),
        selection.primaryPublicationId(),
        java.sql.Date.valueOf(selection.businessDate()),
        jsonUtils.toJson(response.appliedFilters()),
        jsonUtils.toJson(response.definition()),
        jsonUtils.toJson(response.rows()),
        jsonUtils.toJson(metaPayload),
        candidate ? "PREPARED" : "READY"};
    if (candidate) {
      publicationGuardService.writePreparedArtifact(CandidateCacheArtifactStore.KIND_STATISTIC,
          () -> java.util.Objects.requireNonNull(
              jdbcTemplate.queryForObject(SNAPSHOT_UPSERT_SQL + " returning id", Long.class, args),
              "候选统计缓存写入未返回行主键"));
    } else {
      publicationGuardService.writeSnapshot(() -> jdbcTemplate.update(SNAPSHOT_UPSERT_SQL, args));
    }
  }

  /** 统计缓存行的唯一写入语句；候选分支追加 returning id 以登记产物身份。 */
  private static final String SNAPSHOT_UPSERT_SQL = """
      insert into statistic_board_snapshots(
        board_key, scope_key, rule_version, source_version, filter_hash, filter_payload, publication_id, business_date,
        applied_filter_payload, definition_payload, row_payload, meta_payload, status, error_message,
        generated_at, refreshed_at, created_at, updated_at
      ) values (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb, ?, null,
                current_timestamp, current_timestamp, current_timestamp, current_timestamp)
      on conflict (board_key, scope_key, rule_version, filter_hash, source_version)
      do update set
        source_version = excluded.source_version,
        filter_payload = excluded.filter_payload,
        applied_filter_payload = excluded.applied_filter_payload,
        definition_payload = excluded.definition_payload,
        row_payload = excluded.row_payload,
        meta_payload = excluded.meta_payload,
        status = excluded.status,
        publication_id = excluded.publication_id,
        business_date = excluded.business_date,
        error_message = null,
        generated_at = current_timestamp,
        refreshed_at = current_timestamp,
        updated_at = current_timestamp
      """;

  /**
   * 实际读取范围的解析计划。
   *
   * <p>范围来自议题范围目录与来源集合，必须在一致性边界内解析；提前解析会让目录变化落在版本与
   * 事实读取之外，产生“按旧目录解析范围、按新事实读数据”的混合视图。
   */
  public record SourceReadPlan(Supplier<Set<FactProjectionScope>> readScopes) {
    /** 以解析动作构造计划。 */
    public static SourceReadPlan of(Supplier<Set<FactProjectionScope>> readScopes) {
      return new SourceReadPlan(readScopes);
    }

    /**
     * 在当前一致性视图内解析实际读取的来源与稳定范围。
     *
     * @return 排序后的不可变范围集合；为空时抛出，调用方不得以默认来源替代
     */
    public Set<FactProjectionScope> resolve() {
      Set<FactProjectionScope> scopes = readScopes == null ? null : readScopes.get();
      if (scopes == null || scopes.isEmpty()) {
        throw new IllegalArgumentException("统计快照请求必须声明实际读取的来源与范围");
      }
      return java.util.Collections.unmodifiableSet(new TreeSet<>(scopes));
    }
  }

  /** 固定版本读取结果及实时待更新数；新鲜度不改变事实可读性。 */
  public record SourceRead(Set<FactProjectionScope> readScopes, String sourceVersion,
      long pendingUpdates, com.data.collection.platform.entity.read.FactReadSelection selection) {
    StatisticBoardResponse attachFreshness(StatisticBoardResponse response) {
      return response == null ? null : response.withPendingUpdates(pendingUpdates);
    }
  }

  public void invalidateBoard(String boardKey) {
    publicationGuardService.writeSnapshot(
        () ->
            jdbcTemplate.update(
                """
                update statistic_board_snapshots
                   set status = 'STALE',
                       updated_at = current_timestamp
                 where board_key = ?
                   and status = 'READY'
                """,
                boardKey));
  }

  public String issueFactSourceVersion() {
    return projectionVersionService.globalSourceVersion(
        GitlabSourceInstanceSupport.DEFAULT_SOURCE_INSTANCE, FactType.ISSUE);
  }

  /**
   * 为实际读取的 Issue 稳定范围集合生成来源版本。
   *
   * <p>每个范围自带来源实例：只读单源时版本只锁定该源，读多源时版本覆盖每个来源，
   * 不再由本方法隐式替换为默认来源。
   */
  public String issueFactSourceVersion(Set<FactProjectionScope> scopes) {
    return projectionVersionService.combinedSourceVersion(FactType.ISSUE, scopes);
  }

  public String filterHash(Map<String, ?> filters) {
    String json = jsonUtils.toJson(filters == null ? Map.of() : new java.util.TreeMap<>(filters));
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(json.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }

  private Snapshot mapSnapshot(ResultSet rs, int rowNum) throws SQLException {
    Map<String, Object> metaPayload = jsonUtils.fromJson(rs.getString("meta_payload"), MAP_TYPE);
    return new Snapshot(
        rs.getString("board_key"),
        rs.getString("scope_key"),
        rs.getString("rule_version"),
        rs.getString("source_version"),
        jsonUtils.fromJson(rs.getString("filter_payload"), MAP_TYPE),
        jsonUtils.fromJson(rs.getString("applied_filter_payload"), MAP_TYPE),
        definitionPayload(rs),
        jsonUtils.fromJson(rs.getString("row_payload"), ROWS_TYPE),
        new StatisticBoardMeta(
            parseTime(metaPayload.get("generatedAt")),
            number(metaPayload.get("queryDurationMs")),
            (int) number(metaPayload.get("rowCount")),
            (int) number(metaPayload.get("columnCount")),
            (int) number(metaPayload.get("drilldownColumnCount"))),
        rs.getTimestamp("refreshed_at").toLocalDateTime());
  }

  private StatisticBoardDefinition definitionPayload(ResultSet rs) throws SQLException {
    String value = rs.getString("definition_payload");
    if (value == null || value.isBlank()) {
      return null;
    }
    return jsonUtils.fromJson(value, StatisticBoardDefinition.class);
  }

  private LocalDateTime parseTime(Object value) {
    if (value instanceof String text && !text.isBlank()) {
      return LocalDateTime.parse(text);
    }
    return LocalDateTime.now();
  }

  private long number(Object value) {
    if (value instanceof Number number) {
      return number.longValue();
    }
    if (value instanceof String text && !text.isBlank()) {
      return Long.parseLong(text);
    }
    return 0L;
  }

  /**
   * 统计快照请求。
   *
   * <p>{@code readScopes} 声明本次读取实际消费的来源实例与稳定范围的解析动作，来源版本与发布资格校验
   * 都在一致性边界内由它派生；不存在“未声明来源即视为默认来源”的回退，避免用默认来源的版本替代其他
   * 来源的变化。
   */
  public record SnapshotRequest(
      String boardKey,
      String scopeKey,
      String ruleVersion,
      SourceReadPlan readScopes,
      Map<String, ?> filterPayload,
      StatisticBoardDefinition definition,
      com.data.collection.platform.entity.statistics.StatisticFilterGroup appliedFilterGroup) {

    public SnapshotRequest {
      if (readScopes == null) {
        throw new IllegalArgumentException("统计快照请求必须声明实际读取的来源与范围");
      }
    }

    Map<String, ?> cacheFilterPayload() {
      Map<String, Object> payload = new java.util.LinkedHashMap<>(filterPayload == null ? Map.of() : filterPayload);
      // 主表快照缓存必须区分高级筛选/快速筛选展开后的真实条件，否则同一阶段会复用默认快照，
      // 导致主表数字和下钻明细在筛选后显示不一致。
      payload.put("appliedFilterGroup", appliedFilterGroup);
      return payload;
    }

    StatisticBoardResponse toResponse(Snapshot snapshot) {
      @SuppressWarnings("unchecked")
      Map<String, String> appliedFilters = (Map<String, String>) (Map<?, ?>) snapshot.appliedFilterPayload();
      StatisticBoardDefinition responseDefinition =
          snapshot.definition() == null ? definition : snapshot.definition();
      return new StatisticBoardResponse(
          responseDefinition,
          appliedFilters,
          appliedFilterGroup,
          snapshot.rows(),
          snapshot.meta(),
          null,
        null);
    }
  }

  public record Snapshot(
      String boardKey,
      String scopeKey,
      String ruleVersion,
      String sourceVersion,
      Map<String, Object> filterPayload,
      Map<String, Object> appliedFilterPayload,
      StatisticBoardDefinition definition,
      List<StatisticRowData> rows,
      StatisticBoardMeta meta,
      LocalDateTime refreshedAt) {}
}
