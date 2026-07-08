package com.data.collection.platform.service;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.entity.TestingPhaseDefinitionResponse;
import com.data.collection.platform.entity.TestingPhaseDefinitionSaveRequest;
import com.data.collection.platform.entity.TestingPhaseGroupResponse;
import com.data.collection.platform.entity.TestingPhaseGroupSaveRequest;
import com.data.collection.platform.entity.TestingPhaseProjectOptionResponse;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TestingPhaseDefinitionService {

  private final JdbcTemplate jdbcTemplate;
  private final SystemTestPhaseCatalogService phaseCatalogService;

  public TestingPhaseDefinitionService(
      JdbcTemplate jdbcTemplate,
      SystemTestPhaseCatalogService phaseCatalogService) {
    this.jdbcTemplate = jdbcTemplate;
    this.phaseCatalogService = phaseCatalogService;
  }

  public List<TestingPhaseDefinitionResponse> list(Long projectId, String keyword, Boolean enabled) {
    List<Object> args = new ArrayList<>();
    StringBuilder sql =
        new StringBuilder(
            """
            select c.id,
                   c.project_id,
                   coalesce(c.legacy_phase_name, p.project_name, '') as project_name,
                   c.legacy_source_id,
                   coalesce(c.legacy_phase_name, g.name) as legacy_phase_name,
                   coalesce(c.legacy_sort_order, g.sort_order) as legacy_sort_order,
                   c.phase_group_id,
                   c.child_sort_order,
                   c.testing_phase,
                   c.phase_start_at,
                   c.phase_end_at,
                   c.enabled,
                   c.remark,
                   coalesce(s.issue_count, 0) as issue_count,
                   c.created_at,
                   c.updated_at
              from testing_phase_calendar c
              left join testing_phase_groups g on g.id = c.phase_group_id
              left join (
                select project_id, max(project_name) as project_name
                  from issue_fact
                 where project_name is not null and btrim(project_name) <> ''
                 group by project_id
              ) p on p.project_id = c.project_id
              left join (
                select project_id, testing_phase, count(*) as issue_count
                  from issue_fact
                 where deleted = false
                 group by project_id, testing_phase
              ) s on s.project_id = c.project_id and s.testing_phase = c.testing_phase
             where 1 = 1
            """);
    if (projectId != null) {
      sql.append(" and c.project_id = ?");
      args.add(projectId);
    }
    String normalizedKeyword = TextQuerySupport.trimToNull(keyword);
    if (normalizedKeyword != null) {
      sql.append(
          """
           and (
             c.testing_phase ilike ?
             or c.remark ilike ?
             or coalesce(c.legacy_phase_name, g.name, '') ilike ?
             or coalesce(p.project_name, '') ilike ?
             or c.project_id::text ilike ?
           )
          """);
      String likeKeyword = "%" + normalizedKeyword + "%";
      args.add(likeKeyword);
      args.add(likeKeyword);
      args.add(likeKeyword);
      args.add(likeKeyword);
      args.add(likeKeyword);
    }
    if (enabled != null) {
      sql.append(" and c.enabled = ?");
      args.add(enabled);
    }
    sql.append(" order by coalesce(g.sort_order, c.legacy_sort_order) asc nulls last, c.project_id asc, c.child_sort_order asc nulls last, c.testing_phase asc");
    return jdbcTemplate.query(sql.toString(), this::mapDefinition, args.toArray());
  }

  public List<TestingPhaseGroupResponse> listGroups(Long projectId, String keyword, Boolean enabled) {
    List<Object> args = new ArrayList<>();
    StringBuilder sql =
        new StringBuilder(
            """
            select g.id,
                   g.project_id,
                   g.name,
                   g.sort_order,
                   g.enabled,
                   g.remark,
                   coalesce(s.issue_count, 0) as issue_count,
                   g.created_at,
                   g.updated_at
              from testing_phase_groups g
              left join (
                select c.phase_group_id, count(f.id) as issue_count
                  from testing_phase_calendar c
                  left join issue_fact f
                    on f.project_id = c.project_id
                   and f.testing_phase = c.testing_phase
                   and f.deleted = false
                 group by c.phase_group_id
              ) s on s.phase_group_id = g.id
             where 1 = 1
            """);
    if (projectId != null) {
      sql.append(" and g.project_id = ?");
      args.add(projectId);
    }
    String normalizedKeyword = TextQuerySupport.trimToNull(keyword);
    if (normalizedKeyword != null) {
      sql.append(
          """
           and (
             g.name ilike ?
             or g.remark ilike ?
             or exists (
               select 1
                 from testing_phase_calendar c
                where c.phase_group_id = g.id
                  and (c.testing_phase ilike ? or c.remark ilike ?)
             )
           )
          """);
      String likeKeyword = "%" + normalizedKeyword + "%";
      args.add(likeKeyword);
      args.add(likeKeyword);
      args.add(likeKeyword);
      args.add(likeKeyword);
    }
    if (enabled != null) {
      sql.append(" and g.enabled = ?");
      args.add(enabled);
    }
    sql.append(" order by g.sort_order asc, g.name asc");
    List<TestingPhaseGroupResponse> groups =
        jdbcTemplate.query(sql.toString(), this::mapGroupWithoutChildren, args.toArray());
    if (groups.isEmpty()) {
      return groups;
    }
    List<TestingPhaseDefinitionResponse> children = listGroupChildren(groups.stream().map(TestingPhaseGroupResponse::id).toList());
    Map<Long, List<TestingPhaseDefinitionResponse>> childrenByGroup = new LinkedHashMap<>();
    for (TestingPhaseDefinitionResponse child : children) {
      if (child.phaseGroupId() == null) {
        continue;
      }
      childrenByGroup.computeIfAbsent(child.phaseGroupId(), ignored -> new ArrayList<>()).add(child);
    }
    return groups.stream()
        .map(group -> withChildren(group, childrenByGroup.getOrDefault(group.id(), List.of())))
        .toList();
  }

  public List<TestingPhaseProjectOptionResponse> listProjectOptions() {
    return jdbcTemplate.query(
        """
        select project_id, max(project_name) as project_name
          from (
            select project_id, project_name
              from issue_fact
             where project_id is not null
            union all
            select project_id, null as project_name
              from testing_phase_calendar
            union all
            select project_id, null as project_name
              from testing_phase_groups
          ) t
         group by project_id
         order by project_id asc
        """,
        (rs, rowNum) ->
            new TestingPhaseProjectOptionResponse(
                rs.getLong("project_id"),
                TextQuerySupport.normalizeDisplay(rs.getString("project_name"))));
  }

  public TestingPhaseDefinitionResponse create(TestingPhaseDefinitionSaveRequest request) {
    NormalizedPhaseDefinition normalized = normalizeRequest(request);
    jdbcTemplate.update(
        """
        insert into testing_phase_calendar(
          project_id, legacy_source_id, legacy_phase_name, legacy_sort_order, phase_group_id, child_sort_order, testing_phase, phase_start_at, phase_end_at, enabled, remark, updated_at
        )
        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, current_timestamp)
        on conflict (project_id, testing_phase) do update
           set legacy_source_id = excluded.legacy_source_id,
               legacy_phase_name = excluded.legacy_phase_name,
               legacy_sort_order = excluded.legacy_sort_order,
               phase_group_id = excluded.phase_group_id,
               child_sort_order = excluded.child_sort_order,
               phase_start_at = excluded.phase_start_at,
               phase_end_at = excluded.phase_end_at,
               enabled = excluded.enabled,
               remark = excluded.remark,
               updated_at = current_timestamp
        """,
        normalized.projectId(),
        normalized.legacySourceId(),
        normalized.legacyPhaseName(),
        normalized.legacySortOrder(),
        normalized.phaseGroupId(),
        normalized.childSortOrder(),
        normalized.testingPhase(),
        toTimestamp(normalized.phaseStartAt()),
        toTimestamp(normalized.phaseEndAt()),
        normalized.enabled(),
        normalized.remark());
    invalidatePhaseCatalogCache();
    return findByProjectAndPhase(normalized.projectId(), normalized.testingPhase());
  }

  public TestingPhaseDefinitionResponse update(Long id, TestingPhaseDefinitionSaveRequest request) {
    ensureExists(id);
    NormalizedPhaseDefinition normalized = normalizeRequest(request);
    jdbcTemplate.update(
        """
        update testing_phase_calendar
           set project_id = ?,
               legacy_source_id = ?,
               legacy_phase_name = ?,
               legacy_sort_order = ?,
               phase_group_id = ?,
               child_sort_order = ?,
               testing_phase = ?,
               phase_start_at = ?,
               phase_end_at = ?,
               enabled = ?,
               remark = ?,
               updated_at = current_timestamp
         where id = ?
        """,
        normalized.projectId(),
        normalized.legacySourceId(),
        normalized.legacyPhaseName(),
        normalized.legacySortOrder(),
        normalized.phaseGroupId(),
        normalized.childSortOrder(),
        normalized.testingPhase(),
        toTimestamp(normalized.phaseStartAt()),
        toTimestamp(normalized.phaseEndAt()),
        normalized.enabled(),
        normalized.remark(),
        id);
    invalidatePhaseCatalogCache();
    return findById(id);
  }

  public TestingPhaseDefinitionResponse setEnabled(Long id, boolean enabled) {
    ensureExists(id);
    jdbcTemplate.update(
        "update testing_phase_calendar set enabled = ?, updated_at = current_timestamp where id = ?",
        enabled,
        id);
    invalidatePhaseCatalogCache();
    return findById(id);
  }

  public void delete(Long id) {
    ensureExists(id);
    jdbcTemplate.update("delete from testing_phase_calendar where id = ?", id);
    invalidatePhaseCatalogCache();
  }

  @Transactional
  public TestingPhaseGroupResponse createGroup(TestingPhaseGroupSaveRequest request) {
    NormalizedGroup normalized = normalizeGroupRequest(request);
    jdbcTemplate.update(
        """
        insert into testing_phase_groups(project_id, name, sort_order, enabled, remark, updated_at)
        values (?, ?, ?, ?, ?, current_timestamp)
        on conflict (project_id, name) do update
           set sort_order = excluded.sort_order,
               enabled = excluded.enabled,
               remark = excluded.remark,
               updated_at = current_timestamp
        """,
        normalized.projectId(),
        normalized.name(),
        normalized.sortOrder(),
        normalized.enabled(),
        normalized.remark());
    invalidatePhaseCatalogCache();
    return findGroupByProjectAndName(normalized.projectId(), normalized.name());
  }

  @Transactional
  public TestingPhaseGroupResponse updateGroup(Long id, TestingPhaseGroupSaveRequest request) {
    ensureGroupExists(id);
    NormalizedGroup normalized = normalizeGroupRequest(request);
    TestingPhaseGroupResponse oldGroup = findGroupById(id);
    jdbcTemplate.update(
        """
        update testing_phase_groups
           set project_id = ?,
               name = ?,
               sort_order = ?,
               enabled = ?,
               remark = ?,
               updated_at = current_timestamp
         where id = ?
        """,
        normalized.projectId(),
        normalized.name(),
        normalized.sortOrder(),
        normalized.enabled(),
        normalized.remark(),
        id);
    jdbcTemplate.update(
        """
        update testing_phase_calendar
           set project_id = ?,
               phase_group_id = ?,
               legacy_phase_name = ?,
               legacy_sort_order = ?,
               updated_at = current_timestamp
         where phase_group_id = ?
            or (project_id = ? and legacy_phase_name = ?)
        """,
        normalized.projectId(),
        id,
        normalized.name(),
        normalized.sortOrder(),
        id,
        oldGroup.projectId(),
        oldGroup.name());
    invalidatePhaseCatalogCache();
    return findGroupById(id);
  }

  @Transactional
  public TestingPhaseGroupResponse setGroupEnabled(Long id, boolean enabled) {
    ensureGroupExists(id);
    jdbcTemplate.update(
        "update testing_phase_groups set enabled = ?, updated_at = current_timestamp where id = ?",
        enabled,
        id);
    invalidatePhaseCatalogCache();
    return findGroupById(id);
  }

  @Transactional
  public void deleteGroup(Long id) {
    ensureGroupExists(id);
    jdbcTemplate.update("delete from testing_phase_calendar where phase_group_id = ?", id);
    jdbcTemplate.update("delete from testing_phase_groups where id = ?", id);
    invalidatePhaseCatalogCache();
  }

  private void invalidatePhaseCatalogCache() {
    phaseCatalogService.clearCache();
  }

  private TestingPhaseDefinitionResponse findByProjectAndPhase(Long projectId, String testingPhase) {
    return jdbcTemplate.queryForObject(
        """
        select c.id,
               c.project_id,
               coalesce(c.legacy_phase_name, p.project_name, '') as project_name,
               c.legacy_source_id,
               coalesce(c.legacy_phase_name, g.name) as legacy_phase_name,
               coalesce(c.legacy_sort_order, g.sort_order) as legacy_sort_order,
               c.phase_group_id,
               c.child_sort_order,
               c.testing_phase,
               c.phase_start_at,
               c.phase_end_at,
               c.enabled,
               c.remark,
               coalesce(s.issue_count, 0) as issue_count,
               c.created_at,
               c.updated_at
          from testing_phase_calendar c
          left join testing_phase_groups g on g.id = c.phase_group_id
          left join (
            select project_id, max(project_name) as project_name
              from issue_fact
             where project_name is not null and btrim(project_name) <> ''
             group by project_id
          ) p on p.project_id = c.project_id
          left join (
            select project_id, testing_phase, count(*) as issue_count
              from issue_fact
             where deleted = false
             group by project_id, testing_phase
          ) s on s.project_id = c.project_id and s.testing_phase = c.testing_phase
         where c.project_id = ? and c.testing_phase = ?
        """,
        this::mapDefinition,
        projectId,
        testingPhase);
  }

  private TestingPhaseDefinitionResponse findById(Long id) {
    try {
      return jdbcTemplate.queryForObject(
          """
          select c.id,
                 c.project_id,
                 coalesce(c.legacy_phase_name, p.project_name, '') as project_name,
                 c.legacy_source_id,
                 coalesce(c.legacy_phase_name, g.name) as legacy_phase_name,
                 coalesce(c.legacy_sort_order, g.sort_order) as legacy_sort_order,
                 c.phase_group_id,
                 c.child_sort_order,
                 c.testing_phase,
                 c.phase_start_at,
                 c.phase_end_at,
                 c.enabled,
                 c.remark,
                 coalesce(s.issue_count, 0) as issue_count,
                 c.created_at,
                 c.updated_at
            from testing_phase_calendar c
            left join testing_phase_groups g on g.id = c.phase_group_id
            left join (
              select project_id, max(project_name) as project_name
                from issue_fact
               where project_name is not null and btrim(project_name) <> ''
               group by project_id
            ) p on p.project_id = c.project_id
            left join (
              select project_id, testing_phase, count(*) as issue_count
                from issue_fact
               where deleted = false
               group by project_id, testing_phase
            ) s on s.project_id = c.project_id and s.testing_phase = c.testing_phase
           where c.id = ?
          """,
          this::mapDefinition,
          id);
    } catch (EmptyResultDataAccessException ex) {
      throw new BizException("测试阶段定义不存在");
    }
  }

  private void ensureExists(Long id) {
    Integer count =
        jdbcTemplate.queryForObject(
            "select count(*) from testing_phase_calendar where id = ?", Integer.class, id);
    if (count == null || count == 0) {
      throw new BizException("测试阶段定义不存在");
    }
  }

  private void ensureGroupExists(Long id) {
    Integer count =
        jdbcTemplate.queryForObject(
            "select count(*) from testing_phase_groups where id = ?", Integer.class, id);
    if (count == null || count == 0) {
      throw new BizException("阶段名称不存在");
    }
  }

  private NormalizedPhaseDefinition normalizeRequest(TestingPhaseDefinitionSaveRequest request) {
    Long projectId = request.projectId();
    if (projectId == null || projectId <= 0) {
      throw new BizException("项目 ID 不能为空");
    }
    String testingPhase = TextQuerySupport.trimToNull(request.testingPhase());
    if (testingPhase == null) {
      throw new BizException("测试阶段不能为空");
    }
    LocalDateTime phaseStartAt = request.phaseStartAt();
    LocalDateTime phaseEndAt = request.phaseEndAt();
    if (phaseStartAt != null && phaseEndAt != null && phaseEndAt.isBefore(phaseStartAt)) {
      throw new BizException("阶段结束时间不能早于开始时间");
    }
    PhaseGroupRef groupRef = resolveGroupRef(request.projectId(), request.phaseGroupId(), request.legacyPhaseName());
    return new NormalizedPhaseDefinition(
        projectId,
        request.legacySourceId(),
        groupRef.name(),
        request.legacySortOrder() == null ? groupRef.sortOrder() : request.legacySortOrder(),
        groupRef.id(),
        request.childSortOrder() == null ? nextChildSortOrder(groupRef.id()) : request.childSortOrder(),
        testingPhase,
        phaseStartAt,
        phaseEndAt,
        request.enabled() == null || request.enabled(),
        TextQuerySupport.trimToNull(request.remark()));
  }

  private NormalizedGroup normalizeGroupRequest(TestingPhaseGroupSaveRequest request) {
    Long projectId = request.projectId();
    if (projectId == null || projectId <= 0) {
      throw new BizException("项目 ID 不能为空");
    }
    String name = TextQuerySupport.trimToNull(request.name());
    if (name == null) {
      throw new BizException("阶段名称不能为空");
    }
    return new NormalizedGroup(
        projectId,
        name,
        request.sortOrder() == null ? nextGroupSortOrder(projectId) : request.sortOrder(),
        request.enabled() == null || request.enabled(),
        TextQuerySupport.trimToNull(request.remark()));
  }

  private PhaseGroupRef resolveGroupRef(Long projectId, Long phaseGroupId, String legacyPhaseName) {
    if (phaseGroupId != null) {
      TestingPhaseGroupResponse group = findGroupById(phaseGroupId);
      if (!group.projectId().equals(projectId)) {
        throw new BizException("测试阶段所属项目与阶段名称不一致");
      }
      return new PhaseGroupRef(group.id(), group.name(), group.sortOrder());
    }
    String name = TextQuerySupport.trimToNull(legacyPhaseName);
    if (name == null) {
      throw new BizException("阶段名称不能为空");
    }
    TestingPhaseGroupResponse group = findGroupByProjectAndNameOrNull(projectId, name);
    if (group != null) {
      return new PhaseGroupRef(group.id(), group.name(), group.sortOrder());
    }
    TestingPhaseGroupResponse created =
        createGroup(new TestingPhaseGroupSaveRequest(projectId, name, requestSortFallback(projectId), true, "由测试阶段定义自动创建"));
    return new PhaseGroupRef(created.id(), created.name(), created.sortOrder());
  }

  private Integer requestSortFallback(Long projectId) {
    return nextGroupSortOrder(projectId);
  }

  private Integer nextGroupSortOrder(Long projectId) {
    Integer next =
        jdbcTemplate.queryForObject(
            "select coalesce(max(sort_order), 0) + 1 from testing_phase_groups where project_id = ?",
            Integer.class,
            projectId);
    return next == null ? 1 : next;
  }

  private Integer nextChildSortOrder(Long phaseGroupId) {
    Integer next =
        jdbcTemplate.queryForObject(
            "select coalesce(max(child_sort_order), 0) + 1 from testing_phase_calendar where phase_group_id = ?",
            Integer.class,
            phaseGroupId);
    return next == null ? 1 : next;
  }

  private TestingPhaseDefinitionResponse mapDefinition(ResultSet rs, int rowNum)
      throws SQLException {
    return new TestingPhaseDefinitionResponse(
        rs.getLong("id"),
        rs.getLong("project_id"),
        TextQuerySupport.normalizeDisplay(rs.getString("project_name")),
        rs.getObject("legacy_source_id", Long.class),
        TextQuerySupport.normalizeDisplay(rs.getString("legacy_phase_name")),
        rs.getObject("legacy_sort_order", Integer.class),
        rs.getObject("phase_group_id", Long.class),
        rs.getObject("child_sort_order", Integer.class),
        TextQuerySupport.normalizeDisplay(rs.getString("testing_phase")),
        toLocalDateTime(rs.getTimestamp("phase_start_at")),
        toLocalDateTime(rs.getTimestamp("phase_end_at")),
        rs.getBoolean("enabled"),
        TextQuerySupport.normalizeDisplay(rs.getString("remark")),
        rs.getLong("issue_count"),
        toLocalDateTime(rs.getTimestamp("created_at")),
        toLocalDateTime(rs.getTimestamp("updated_at")));
  }

  private TestingPhaseGroupResponse mapGroupWithoutChildren(ResultSet rs, int rowNum)
      throws SQLException {
    return new TestingPhaseGroupResponse(
        rs.getLong("id"),
        rs.getLong("project_id"),
        TextQuerySupport.normalizeDisplay(rs.getString("name")),
        rs.getObject("sort_order", Integer.class),
        rs.getBoolean("enabled"),
        TextQuerySupport.normalizeDisplay(rs.getString("remark")),
        rs.getLong("issue_count"),
        List.of(),
        toLocalDateTime(rs.getTimestamp("created_at")),
        toLocalDateTime(rs.getTimestamp("updated_at")));
  }

  private TestingPhaseGroupResponse withChildren(
      TestingPhaseGroupResponse group, List<TestingPhaseDefinitionResponse> children) {
    return new TestingPhaseGroupResponse(
        group.id(),
        group.projectId(),
        group.name(),
        group.sortOrder(),
        group.enabled(),
        group.remark(),
        group.issueCount(),
        children,
        group.createdAt(),
        group.updatedAt());
  }

  private List<TestingPhaseDefinitionResponse> listGroupChildren(List<Long> groupIds) {
    if (groupIds.isEmpty()) {
      return List.of();
    }
    String placeholders = String.join(",", groupIds.stream().map(ignored -> "?").toList());
    String sql =
        """
        select c.id,
               c.project_id,
               coalesce(c.legacy_phase_name, g.name, '') as project_name,
               c.legacy_source_id,
               coalesce(c.legacy_phase_name, g.name) as legacy_phase_name,
               coalesce(c.legacy_sort_order, g.sort_order) as legacy_sort_order,
               c.phase_group_id,
               c.child_sort_order,
               c.testing_phase,
               c.phase_start_at,
               c.phase_end_at,
               c.enabled,
               c.remark,
               coalesce(s.issue_count, 0) as issue_count,
               c.created_at,
               c.updated_at
          from testing_phase_calendar c
          left join testing_phase_groups g on g.id = c.phase_group_id
          left join (
            select project_id, testing_phase, count(*) as issue_count
              from issue_fact
             where deleted = false
             group by project_id, testing_phase
          ) s on s.project_id = c.project_id and s.testing_phase = c.testing_phase
         where c.phase_group_id in (%s)
         order by coalesce(g.sort_order, c.legacy_sort_order) asc nulls last, c.child_sort_order asc nulls last, c.testing_phase asc
        """
            .formatted(placeholders);
    return jdbcTemplate.query(sql, this::mapDefinition, groupIds.toArray());
  }

  private TestingPhaseGroupResponse findGroupByProjectAndName(Long projectId, String name) {
    TestingPhaseGroupResponse group = findGroupByProjectAndNameOrNull(projectId, name);
    if (group == null) {
      throw new BizException("阶段名称不存在");
    }
    return group;
  }

  private TestingPhaseGroupResponse findGroupByProjectAndNameOrNull(Long projectId, String name) {
    try {
      return jdbcTemplate.queryForObject(
          """
          select g.id,
                 g.project_id,
                 g.name,
                 g.sort_order,
                 g.enabled,
                 g.remark,
                 0 as issue_count,
                 g.created_at,
                 g.updated_at
            from testing_phase_groups g
           where g.project_id = ? and g.name = ?
          """,
          this::mapGroupWithoutChildren,
          projectId,
          name);
    } catch (EmptyResultDataAccessException ex) {
      return null;
    }
  }

  private TestingPhaseGroupResponse findGroupById(Long id) {
    try {
      return jdbcTemplate.queryForObject(
          """
          select g.id,
                 g.project_id,
                 g.name,
                 g.sort_order,
                 g.enabled,
                 g.remark,
                 coalesce(s.issue_count, 0) as issue_count,
                 g.created_at,
                 g.updated_at
            from testing_phase_groups g
            left join (
              select c.phase_group_id, count(f.id) as issue_count
                from testing_phase_calendar c
                left join issue_fact f
                  on f.project_id = c.project_id
                 and f.testing_phase = c.testing_phase
                 and f.deleted = false
               group by c.phase_group_id
            ) s on s.phase_group_id = g.id
           where g.id = ?
          """,
          this::mapGroupWithoutChildren,
          id);
    } catch (EmptyResultDataAccessException ex) {
      throw new BizException("阶段名称不存在");
    }
  }

  private Timestamp toTimestamp(LocalDateTime value) {
    return value == null ? null : Timestamp.valueOf(value);
  }

  private LocalDateTime toLocalDateTime(Timestamp value) {
    return value == null ? null : value.toLocalDateTime();
  }

  private record NormalizedPhaseDefinition(
      Long projectId,
      Long legacySourceId,
      String legacyPhaseName,
      Integer legacySortOrder,
      Long phaseGroupId,
      Integer childSortOrder,
      String testingPhase,
      LocalDateTime phaseStartAt,
      LocalDateTime phaseEndAt,
      Boolean enabled,
      String remark) {}

  private record NormalizedGroup(
      Long projectId, String name, Integer sortOrder, Boolean enabled, String remark) {}

  private record PhaseGroupRef(Long id, String name, Integer sortOrder) {}
}
