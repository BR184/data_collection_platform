package com.data.collection.platform.service.statistics;

import com.data.collection.platform.common.JsonUtils;
import com.data.collection.platform.entity.statistics.StatisticBoardDefinition;
import com.data.collection.platform.entity.statistics.StatisticBoardMeta;
import com.data.collection.platform.entity.statistics.StatisticBoardResponse;
import com.data.collection.platform.entity.statistics.StatisticRowData;
import com.fasterxml.jackson.core.type.TypeReference;
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
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class StatisticBoardSnapshotService {
  private static final TypeReference<List<StatisticRowData>> ROWS_TYPE = new TypeReference<>() {};
  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

  private final JdbcTemplate jdbcTemplate;
  private final JsonUtils jsonUtils;

  public StatisticBoardSnapshotService(JdbcTemplate jdbcTemplate, JsonUtils jsonUtils) {
    this.jdbcTemplate = jdbcTemplate;
    this.jsonUtils = jsonUtils;
  }

  public Optional<Snapshot> findReady(
      String boardKey,
      String scopeKey,
      String ruleVersion,
      String sourceVersion,
      Map<String, ?> filters) {
    String filterHash = filterHash(filters);
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
            boardKey,
            scopeKey,
            ruleVersion,
            sourceVersion,
            filterHash);
    return rows.stream().findFirst();
  }

  public StatisticBoardResponse readOrRefresh(
      SnapshotRequest request,
      Supplier<StatisticBoardResponse> responseSupplier) {
    Map<String, ?> cachePayload = request.cacheFilterPayload();
    Optional<Snapshot> snapshot =
        findReady(
            request.boardKey(),
            request.scopeKey(),
            request.ruleVersion(),
            request.sourceVersion(),
            cachePayload);
    if (snapshot.isPresent()) {
      return request.toResponse(snapshot.get());
    }
    StatisticBoardResponse response = responseSupplier.get();
    save(request, response);
    return response;
  }

  public void save(SnapshotRequest request, StatisticBoardResponse response) {
    Map<String, Object> metaPayload =
        Map.of(
            "generatedAt", response.meta().generatedAt().toString(),
            "queryDurationMs", response.meta().queryDurationMs(),
            "rowCount", response.meta().rowCount(),
            "columnCount", response.meta().columnCount(),
            "drilldownColumnCount", response.meta().drilldownColumnCount());
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
        request.sourceVersion(),
        filterHash(request.cacheFilterPayload()),
        jsonUtils.toJson(request.cacheFilterPayload()),
        jsonUtils.toJson(response.appliedFilters()),
        jsonUtils.toJson(response.definition()),
        jsonUtils.toJson(response.rows()),
        jsonUtils.toJson(metaPayload));
  }

  public void invalidateBoard(String boardKey) {
    jdbcTemplate.update(
        """
        update statistic_board_snapshots
           set status = 'STALE',
               updated_at = current_timestamp
         where board_key = ?
           and status = 'READY'
        """,
        boardKey);
  }

  public String issueFactSourceVersion() {
    return jdbcTemplate.queryForObject(
        """
        select coalesce(
                 (
                   select concat(id, ':', coalesce(to_char(finished_at, 'YYYY-MM-DD"T"HH24:MI:SS.US'), 'running'))
                     from fact_build_tasks
                    where status = 'SUCCESS'
                      and (coalesce(affected_rows, 0) > 0 or full_build = true)
                      and (
                        upper(coalesce(fact_type, '')) = 'ISSUE'
                        or lower(coalesce(scope, '')) = 'issue'
                        or lower(coalesce(scope, '')) like '%:issue'
                        or lower(coalesce(scope, '')) = 'all'
                      )
                    order by finished_at desc nulls last, id desc
                    limit 1
                 ),
                 'bootstrap'
               )
        """,
        String.class);
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

  public record SnapshotRequest(
      String boardKey,
      String scopeKey,
      String ruleVersion,
      String sourceVersion,
      Map<String, ?> filterPayload,
      StatisticBoardDefinition definition,
      com.data.collection.platform.entity.statistics.StatisticFilterGroup appliedFilterGroup) {
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
      return new StatisticBoardResponse(
          definition,
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
