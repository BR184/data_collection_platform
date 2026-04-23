package com.data.collection.platform.service;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.entity.TestingPhaseDefinitionResponse;
import com.data.collection.platform.entity.TestingPhaseDefinitionSaveRequest;
import com.data.collection.platform.entity.TestingPhaseProjectOptionResponse;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class TestingPhaseDefinitionService {

  private final JdbcTemplate jdbcTemplate;

  public TestingPhaseDefinitionService(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public List<TestingPhaseDefinitionResponse> list(Long projectId, String keyword, Boolean enabled) {
    List<Object> args = new ArrayList<>();
    StringBuilder sql =
        new StringBuilder(
            """
            select c.id,
                   c.project_id,
                   coalesce(p.project_name, '') as project_name,
                   c.testing_phase,
                   c.phase_start_at,
                   c.phase_end_at,
                   c.enabled,
                   c.remark,
                   coalesce(s.issue_count, 0) as issue_count,
                   c.created_at,
                   c.updated_at
              from testing_phase_calendar c
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
             or coalesce(p.project_name, '') ilike ?
             or c.project_id::text ilike ?
           )
          """);
      String likeKeyword = "%" + normalizedKeyword + "%";
      args.add(likeKeyword);
      args.add(likeKeyword);
      args.add(likeKeyword);
      args.add(likeKeyword);
    }
    if (enabled != null) {
      sql.append(" and c.enabled = ?");
      args.add(enabled);
    }
    sql.append(" order by c.project_id asc, c.phase_start_at desc, c.testing_phase asc");
    return jdbcTemplate.query(sql.toString(), this::mapDefinition, args.toArray());
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
          project_id, testing_phase, phase_start_at, phase_end_at, enabled, remark, updated_at
        )
        values (?, ?, ?, ?, ?, ?, current_timestamp)
        on conflict (project_id, testing_phase) do update
           set phase_start_at = excluded.phase_start_at,
               phase_end_at = excluded.phase_end_at,
               enabled = excluded.enabled,
               remark = excluded.remark,
               updated_at = current_timestamp
        """,
        normalized.projectId(),
        normalized.testingPhase(),
        Timestamp.valueOf(normalized.phaseStartAt()),
        toTimestamp(normalized.phaseEndAt()),
        normalized.enabled(),
        normalized.remark());
    return findByProjectAndPhase(normalized.projectId(), normalized.testingPhase());
  }

  public TestingPhaseDefinitionResponse update(Long id, TestingPhaseDefinitionSaveRequest request) {
    ensureExists(id);
    NormalizedPhaseDefinition normalized = normalizeRequest(request);
    jdbcTemplate.update(
        """
        update testing_phase_calendar
           set project_id = ?,
               testing_phase = ?,
               phase_start_at = ?,
               phase_end_at = ?,
               enabled = ?,
               remark = ?,
               updated_at = current_timestamp
         where id = ?
        """,
        normalized.projectId(),
        normalized.testingPhase(),
        Timestamp.valueOf(normalized.phaseStartAt()),
        toTimestamp(normalized.phaseEndAt()),
        normalized.enabled(),
        normalized.remark(),
        id);
    return findById(id);
  }

  public TestingPhaseDefinitionResponse setEnabled(Long id, boolean enabled) {
    ensureExists(id);
    jdbcTemplate.update(
        "update testing_phase_calendar set enabled = ?, updated_at = current_timestamp where id = ?",
        enabled,
        id);
    return findById(id);
  }

  public void delete(Long id) {
    ensureExists(id);
    jdbcTemplate.update("delete from testing_phase_calendar where id = ?", id);
  }

  private TestingPhaseDefinitionResponse findByProjectAndPhase(Long projectId, String testingPhase) {
    return jdbcTemplate.queryForObject(
        """
        select c.id,
               c.project_id,
               coalesce(p.project_name, '') as project_name,
               c.testing_phase,
               c.phase_start_at,
               c.phase_end_at,
               c.enabled,
               c.remark,
               coalesce(s.issue_count, 0) as issue_count,
               c.created_at,
               c.updated_at
          from testing_phase_calendar c
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
                 coalesce(p.project_name, '') as project_name,
                 c.testing_phase,
                 c.phase_start_at,
                 c.phase_end_at,
                 c.enabled,
                 c.remark,
                 coalesce(s.issue_count, 0) as issue_count,
                 c.created_at,
                 c.updated_at
            from testing_phase_calendar c
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
    if (phaseStartAt == null) {
      throw new BizException("阶段开始时间不能为空");
    }
    LocalDateTime phaseEndAt = request.phaseEndAt();
    if (phaseEndAt != null && phaseEndAt.isBefore(phaseStartAt)) {
      throw new BizException("阶段结束时间不能早于开始时间");
    }
    return new NormalizedPhaseDefinition(
        projectId,
        testingPhase,
        phaseStartAt,
        phaseEndAt,
        request.enabled() == null || request.enabled(),
        TextQuerySupport.trimToNull(request.remark()));
  }

  private TestingPhaseDefinitionResponse mapDefinition(ResultSet rs, int rowNum)
      throws SQLException {
    return new TestingPhaseDefinitionResponse(
        rs.getLong("id"),
        rs.getLong("project_id"),
        TextQuerySupport.normalizeDisplay(rs.getString("project_name")),
        TextQuerySupport.normalizeDisplay(rs.getString("testing_phase")),
        toLocalDateTime(rs.getTimestamp("phase_start_at")),
        toLocalDateTime(rs.getTimestamp("phase_end_at")),
        rs.getBoolean("enabled"),
        TextQuerySupport.normalizeDisplay(rs.getString("remark")),
        rs.getLong("issue_count"),
        toLocalDateTime(rs.getTimestamp("created_at")),
        toLocalDateTime(rs.getTimestamp("updated_at")));
  }

  private Timestamp toTimestamp(LocalDateTime value) {
    return value == null ? null : Timestamp.valueOf(value);
  }

  private LocalDateTime toLocalDateTime(Timestamp value) {
    return value == null ? null : value.toLocalDateTime();
  }

  private record NormalizedPhaseDefinition(
      Long projectId,
      String testingPhase,
      LocalDateTime phaseStartAt,
      LocalDateTime phaseEndAt,
      Boolean enabled,
      String remark) {}
}
