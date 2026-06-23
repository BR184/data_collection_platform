package com.data.collection.platform.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class SystemTestPhaseCatalogService {
  public static final long LEGACY_CROWN_CAD_PROJECT_ID = 9L;

  private static final Pattern TURN_LABEL_PATTERN =
      Pattern.compile("(第[一二三四五六七八九十0-9]+轮系统测试|回归测试|系统测试)");
  private static final List<String> SYSTEM_TEST_TOKENS = List.of("系统测试", "回归测试");

  private final JdbcTemplate jdbcTemplate;

  public SystemTestPhaseCatalogService(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public List<PhaseGroup> listGroups(Long projectId) {
    return groupEntries(loadConfiguredEntries(projectId));
  }

  public List<String> listParentNames(Long projectId) {
    return listGroups(projectId).stream().map(PhaseGroup::name).toList();
  }

  public List<String> listTestingPhases(Long projectId) {
    return listGroups(projectId).stream().flatMap(group -> group.testingPhases().stream()).toList();
  }

  public List<String> listTestingPhasesByParent(Long projectId, String parentName) {
    String normalizedParent = TextQuerySupport.normalizeDisplay(parentName);
    return listGroups(projectId).stream()
        .filter(group -> group.name().equalsIgnoreCase(normalizedParent))
        .flatMap(group -> group.testingPhases().stream())
        .toList();
  }

  public String parentName(String phaseLabel) {
    String normalized = TextQuerySupport.trimToNull(phaseLabel);
    if (normalized == null) {
      return "";
    }
    Matcher matcher = TURN_LABEL_PATTERN.matcher(normalized);
    if (matcher.find()) {
      String parent = TextQuerySupport.trimToNull(normalized.replace(matcher.group(1), ""));
      if (parent != null) {
        return parent;
      }
    }
    return normalized;
  }

  public boolean isSystemTestPhase(String value) {
    return StringUtils.hasText(value) && IssueRuleSupport.containsToken(value, SYSTEM_TEST_TOKENS);
  }

  private List<PhaseEntry> loadConfiguredEntries(Long projectId) {
    List<Object> args = new ArrayList<>();
    StringBuilder sql =
        new StringBuilder(
            """
            select c.project_id,
                   coalesce(g.name, c.legacy_phase_name) as legacy_phase_name,
                   coalesce(g.sort_order, c.legacy_sort_order) as legacy_sort_order,
                   c.child_sort_order,
                   c.testing_phase,
                   c.phase_start_at,
                   coalesce(s.issue_count, 0) as issue_count
              from testing_phase_calendar c
              left join testing_phase_groups g
                on g.id = c.phase_group_id
              left join (
                select project_id, testing_phase, count(*) as issue_count
                  from issue_fact
                 where deleted = false
                 group by project_id, testing_phase
              ) s on s.project_id = c.project_id and s.testing_phase = c.testing_phase
             where c.enabled = true
               and coalesce(g.enabled, true) = true
            """);
    if (projectId != null) {
      sql.append(" and c.project_id = ?");
      args.add(projectId);
    }
    sql.append(" order by coalesce(g.sort_order, c.legacy_sort_order) asc nulls last, c.child_sort_order asc nulls last, c.phase_start_at desc nulls last, c.testing_phase asc");
    try {
      return jdbcTemplate.query(sql.toString(), this::mapConfiguredEntry, args.toArray());
    } catch (DataAccessException error) {
      return List.of();
    }
  }

  private PhaseEntry mapConfiguredEntry(ResultSet rs, int rowNum) throws SQLException {
    String testingPhase = TextQuerySupport.normalizeDisplay(rs.getString("testing_phase"));
    String legacyPhaseName = TextQuerySupport.normalizeDisplay(rs.getString("legacy_phase_name"));
    return new PhaseEntry(
        rs.getLong("project_id"),
        StringUtils.hasText(legacyPhaseName) ? legacyPhaseName : parentName(testingPhase),
        testingPhase,
        rs.getTimestamp("phase_start_at") == null ? null : rs.getTimestamp("phase_start_at").toLocalDateTime(),
        rs.getObject("legacy_sort_order", Integer.class),
        rs.getLong("issue_count"));
  }

  private List<PhaseGroup> groupEntries(List<PhaseEntry> entries) {
    Map<String, MutablePhaseGroup> groups = new LinkedHashMap<>();
    for (PhaseEntry entry : entries) {
      if (!StringUtils.hasText(entry.name()) || !StringUtils.hasText(entry.testingPhase())) {
        continue;
      }
      MutablePhaseGroup group =
          groups.computeIfAbsent(entry.name(), name -> new MutablePhaseGroup(entry.projectId(), name));
      group.add(entry);
    }
    return groups.values().stream().map(MutablePhaseGroup::toPhaseGroup).toList();
  }

  public record PhaseGroup(Long projectId, String name, List<String> testingPhases, long issueCount) {}

  private record PhaseEntry(
      Long projectId, String name, String testingPhase, LocalDateTime startAt, Integer sortOrder, long issueCount) {}

  private static final class MutablePhaseGroup {
    private final Long projectId;
    private final String name;
    private final Set<String> testingPhases = new LinkedHashSet<>();
    private long issueCount;

    private MutablePhaseGroup(Long projectId, String name) {
      this.projectId = projectId;
      this.name = name;
    }

    private void add(PhaseEntry entry) {
      testingPhases.add(entry.testingPhase());
      issueCount += Math.max(0, entry.issueCount());
    }

    private PhaseGroup toPhaseGroup() {
      return new PhaseGroup(projectId, name, List.copyOf(testingPhases), issueCount);
    }
  }
}
