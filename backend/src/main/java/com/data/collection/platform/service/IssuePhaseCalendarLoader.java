package com.data.collection.platform.service;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
class IssuePhaseCalendarLoader {
  private static final String PHASE_CALENDAR_SQL =
      """
      select c.project_id,
             m.source_value as testing_phase,
             m.active_from as phase_start_at,
             m.active_until as phase_end_at,
             m.enabled
        from issue_scope_catalogs c
        join issue_scope_groups g
          on g.catalog_id = c.id
         and g.enabled = true
        join issue_scope_members m
          on m.catalog_id = c.id
         and m.group_id = g.id
         and m.enabled = true
       where c.dimension = 'TESTING_PHASE'
         and c.enabled = true
      """;

  private final JdbcTemplate jdbcTemplate;

  IssuePhaseCalendarLoader(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  Map<PhaseCalendarKey, PhaseCalendarEntry> loadFactProjectionCalendar() {
    return selectForFactProjection(loadEntries());
  }

  Map<PhaseCalendarKey, PhaseCalendarEntry> loadIntegrationTestCalendar() {
    return selectForIntegrationTest(loadEntries());
  }

  static Map<PhaseCalendarKey, PhaseCalendarEntry> selectForFactProjection(
      List<PhaseCalendarEntry> entries) {
    Map<PhaseCalendarKey, PhaseCalendarEntry> result = new LinkedHashMap<>();
    entries.stream()
        // 保留原事实构建语义：先反转 nullsLast 比较器，因此空起始时间优先于有值记录。
        .sorted(
            Comparator.comparing(
                    PhaseCalendarEntry::phaseStartAt,
                    Comparator.nullsLast(LocalDateTime::compareTo))
                .reversed())
        .forEach(entry -> result.putIfAbsent(keyOf(entry), entry));
    return result;
  }

  static Map<PhaseCalendarKey, PhaseCalendarEntry> selectForIntegrationTest(
      List<PhaseCalendarEntry> entries) {
    Map<PhaseCalendarKey, PhaseCalendarEntry> result = new LinkedHashMap<>();
    for (PhaseCalendarEntry entry : entries) {
      result.putIfAbsent(keyOf(entry), entry);
    }
    return result;
  }

  private List<PhaseCalendarEntry> loadEntries() {
    return jdbcTemplate.query(
        PHASE_CALENDAR_SQL,
        (resultSet, rowNumber) ->
            new PhaseCalendarEntry(
                resultSet.getLong("project_id"),
                TextQuerySupport.trimToNull(resultSet.getString("testing_phase")),
                toLocalDateTime(resultSet.getTimestamp("phase_start_at")),
                toLocalDateTime(resultSet.getTimestamp("phase_end_at")),
                resultSet.getBoolean("enabled")));
  }

  private static PhaseCalendarKey keyOf(PhaseCalendarEntry entry) {
    return new PhaseCalendarKey(entry.projectId(), normalizeKey(entry.testingPhase()));
  }

  static String normalizeKey(String value) {
    return StringUtils.hasText(value) ? value.trim().toLowerCase(Locale.ROOT) : null;
  }

  private static LocalDateTime toLocalDateTime(java.sql.Timestamp timestamp) {
    return timestamp == null ? null : timestamp.toLocalDateTime();
  }

  record PhaseCalendarKey(Long projectId, String testingPhase) {}

  record PhaseCalendarEntry(
      Long projectId,
      String testingPhase,
      LocalDateTime phaseStartAt,
      LocalDateTime phaseEndAt,
      boolean enabled) {
    boolean matches(LocalDateTime target) {
      return enabled
          && target != null
          && phaseStartAt != null
          && !target.isBefore(phaseStartAt)
          && (phaseEndAt == null || !target.isAfter(phaseEndAt));
    }
  }
}
