package com.data.collection.platform.service.statistics;

import com.data.collection.platform.common.JsonUtils;
import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.entity.FactProjectionScope;
import com.data.collection.platform.entity.FactType;
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
    this.readConsistencyTemplate.setReadOnly(true);
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

  /**
   * 命中快照或刷新统计结果。
   *
   * <p>范围解析、发布资格校验、来源版本读取、快照命中判定与事实读取在同一个只读 REPEATABLE READ
   * 事务内完成：目录与来源集合、版本与事实看到同一数据库视图，不会出现“事务前取旧版本、事务内读到新事实
   * 却写入旧缓存键”，也不会出现版本检查之后发生发布而明细读回另一代际的数据。
   * 命中缓存同样要经过发布资格校验，来源重建未结算或依赖未就绪时不返回新的 READY 结果。
   * 快照写入在只读事务之外执行，写入用的是事务内读到的版本，因此并发发布只会让它成为可被取代的旧版本条目。
   *
   * @param request 声明范围解析计划与缓存身份的快照请求
   * @param responseSupplier 未命中时重建统计结果的读取动作，入参为本次一致性边界解析出的范围与版本
   * @return 命中快照或新读取的结果
   */
  public StatisticBoardResponse readOrRefresh(
      SnapshotRequest request,
      Function<SourceRead, StatisticBoardResponse> responseSupplier) {
    ConsistentRead read =
        withinConsistentSourceRead(
            request.readScopes(),
            sourceRead -> {
              Optional<Snapshot> snapshot = findReady(request, sourceRead.sourceVersion());
              if (snapshot.isPresent()) {
                return new ConsistentRead(sourceRead, snapshot, null);
              }
              return new ConsistentRead(
                  sourceRead, Optional.empty(), responseSupplier.apply(sourceRead));
            });
    if (read.snapshot().isPresent()) {
      return request.toResponse(read.snapshot().get());
    }
    save(request, read.sourceRead().sourceVersion(), read.response());
    return read.response();
  }

  /**
   * 在与统计快照相同的只读一致性边界内执行读取动作。
   *
   * <p>供不走缓存的链路（下钻明细、分子分母与样本集合）复用同一边界：范围解析、发布资格、来源版本与
   * 消费方的事实读取都在同一个 REPEATABLE READ 事务内，因此并发发布要么整体落在本次视图之前，
   * 要么整体落在之后，不会把两代数据混进同一份结果。
   *
   * @param readScopes 实际读取范围的解析计划
   * @param action 读取动作，入参为边界内解析出的范围与来源版本
   * @param <T> 读取结果类型
   * @return 读取动作的结果
   */
  public <T> T withinConsistentSourceRead(
      SourceReadPlan readScopes, Function<SourceRead, T> action) {
    T result =
        readConsistencyTemplate.execute(
            status -> {
              Set<FactProjectionScope> scopes = readScopes.resolve();
              requireReadableSources(scopes);
              return action.apply(
                  new SourceRead(
                      scopes,
                      projectionVersionService.combinedSourceVersion(
                          factTypeOf(scopes), scopes)));
            });
    if (result == null) {
      throw new IllegalStateException("统计只读一致性事务未返回结果");
    }
    return result;
  }

  private static FactType factTypeOf(Set<FactProjectionScope> scopes) {
    return scopes.iterator().next().factType();
  }

  /** 校验请求实际读取的每个来源实例都允许对外输出。 */
  private void requireReadableSources(Set<FactProjectionScope> scopes) {
    FactType factType = factTypeOf(scopes);
    for (String sourceInstance : sourceInstancesOf(scopes)) {
      SyncFactPublicationStateService.SourceQualification qualification =
          publicationStateService.qualification(sourceInstance, factType);
      if (!qualification.readable()) {
        throw new BizException(qualification.reason());
      }
    }
  }

  private static Set<String> sourceInstancesOf(Set<FactProjectionScope> scopes) {
    TreeSet<String> sources = new TreeSet<>();
    for (FactProjectionScope scope : scopes) {
      sources.add(scope.sourceInstance());
    }
    return sources;
  }

  public void save(SnapshotRequest request, StatisticBoardResponse response) {
    Set<FactProjectionScope> scopes = request.readScopes().resolve();
    save(
        request,
        projectionVersionService.combinedSourceVersion(factTypeOf(scopes), scopes),
        response);
  }

  /**
   * 以一致性边界内解析出的来源版本写入快照。
   *
   * @param request 缓存身份与载荷
   * @param sourceVersion 与本次读取同一边界解析出的来源版本
   * @param response 待缓存的统计结果
   */
  public void save(
      SnapshotRequest request, String sourceVersion, StatisticBoardResponse response) {
    Map<String, Object> metaPayload =
        Map.of(
            "generatedAt", response.meta().generatedAt().toString(),
            "queryDurationMs", response.meta().queryDurationMs(),
            "rowCount", response.meta().rowCount(),
            "columnCount", response.meta().columnCount(),
            "drilldownColumnCount", response.meta().drilldownColumnCount());
    publicationGuardService.writeSnapshot(
        () ->
            jdbcTemplate.update(
                """
                insert into statistic_board_snapshots(
                  board_key, scope_key, rule_version, source_version, filter_hash, filter_payload,
                  applied_filter_payload, definition_payload, row_payload, meta_payload, status, error_message,
                  generated_at, refreshed_at, created_at, updated_at
                ) values (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb, 'READY', null,
                          current_timestamp, current_timestamp, current_timestamp, current_timestamp)
                on conflict (board_key, scope_key, rule_version, filter_hash)
                do update set
                  source_version = excluded.source_version,
                  filter_payload = excluded.filter_payload,
                  applied_filter_payload = excluded.applied_filter_payload,
                  definition_payload = excluded.definition_payload,
                  row_payload = excluded.row_payload,
                  meta_payload = excluded.meta_payload,
                  status = 'READY',
                  error_message = null,
                  generated_at = current_timestamp,
                  refreshed_at = current_timestamp,
                  updated_at = current_timestamp
                """,
                request.boardKey(),
                request.scopeKey(),
                request.ruleVersion(),
                sourceVersion,
                filterHash(request.cacheFilterPayload()),
                jsonUtils.toJson(request.cacheFilterPayload()),
                jsonUtils.toJson(response.appliedFilters()),
                jsonUtils.toJson(response.definition()),
                jsonUtils.toJson(response.rows()),
                jsonUtils.toJson(metaPayload)));
  }

  /** 只读一致性事务内的结果：命中快照时为快照，否则为本次读取结果。 */
  private record ConsistentRead(
      SourceRead sourceRead, Optional<Snapshot> snapshot, StatisticBoardResponse response) {
  }

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

  /** 一致性边界内解析出的实际读取范围与该视图对应的来源版本。 */
  public record SourceRead(Set<FactProjectionScope> readScopes, String sourceVersion) {}

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
          snapshot.meta());
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
