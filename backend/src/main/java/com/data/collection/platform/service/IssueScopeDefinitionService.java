package com.data.collection.platform.service;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.entity.IssueScopeCatalogResponse;
import com.data.collection.platform.entity.IssueScopeCatalogSaveRequest;
import com.data.collection.platform.entity.IssueScopeDiscoveredValueResponse;
import com.data.collection.platform.entity.IssueScopeGroupResponse;
import com.data.collection.platform.entity.IssueScopeGroupSaveRequest;
import com.data.collection.platform.entity.IssueScopeMemberResponse;
import com.data.collection.platform.entity.IssueScopeMemberSaveRequest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 议题范围目录的管理服务。 */
@Service
public class IssueScopeDefinitionService {
  private final JdbcTemplate jdbcTemplate;

  public IssueScopeDefinitionService(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /** 返回全部已登记目录及未纳入目录的事实值数量。 */
  public List<IssueScopeCatalogResponse> listCatalogs() {
    return jdbcTemplate.query(
        """
        select c.id,
               c.project_id,
               c.project_name,
               c.dimension,
               c.enabled,
               c.remark,
               (select count(*) from issue_scope_groups g where g.catalog_id = c.id) as group_count,
               c.created_at,
               c.updated_at
          from issue_scope_catalogs c
         order by c.project_id asc, c.dimension asc
        """,
        (rs, rowNumber) -> {
          IssueScopeDimension dimension = IssueScopeDimension.parse(rs.getString("dimension"));
          long catalogId = rs.getLong("id");
          long projectId = rs.getLong("project_id");
          return new IssueScopeCatalogResponse(
              catalogId,
              projectId,
              TextQuerySupport.normalizeDisplay(rs.getString("project_name")),
              dimension.name(),
              dimension.displayName(),
              rs.getBoolean("enabled"),
              TextQuerySupport.normalizeDisplay(rs.getString("remark")),
              rs.getLong("group_count"),
              countUnassignedValues(catalogId, projectId, dimension),
              toLocalDateTime(rs.getTimestamp("created_at")),
              toLocalDateTime(rs.getTimestamp("updated_at")));
        });
  }

  /** 返回目录下的范围组和匹配成员。 */
  public List<IssueScopeGroupResponse> listGroups(
      Long catalogId, String keyword, Boolean enabled) {
    CatalogRef catalog = requireCatalog(catalogId);
    List<Object> args = new ArrayList<>();
    args.add(catalog.id());
    StringBuilder sql =
        new StringBuilder(
            """
            select g.id,
                   g.catalog_id,
                   g.business_key,
                   g.display_name,
                   g.sort_order,
                   g.enabled,
                   g.remark,
                   g.created_at,
                   g.updated_at
              from issue_scope_groups g
             where g.catalog_id = ?
            """);
    String normalizedKeyword = TextQuerySupport.trimToNull(keyword);
    if (normalizedKeyword != null) {
      sql.append(
          """
           and (
             g.business_key ilike ?
             or g.display_name ilike ?
             or coalesce(g.remark, '') ilike ?
             or exists (
               select 1
                 from issue_scope_members m
                where m.group_id = g.id
                  and (m.source_value ilike ? or m.display_name ilike ?)
             )
           )
          """);
      String like = "%" + normalizedKeyword + "%";
      for (int index = 0; index < 5; index++) {
        args.add(like);
      }
    }
    if (enabled != null) {
      sql.append(" and g.enabled = ?");
      args.add(enabled);
    }
    sql.append(" order by g.sort_order asc, g.id asc");
    List<GroupRow> groupRows =
        jdbcTemplate.query(sql.toString(), this::mapGroupRow, args.toArray());
    if (groupRows.isEmpty()) {
      return List.of();
    }
    Map<Long, List<IssueScopeMemberResponse>> membersByGroup =
        loadMembers(catalog, groupRows.stream().map(GroupRow::id).toList());
    return groupRows.stream()
        .map(
            row -> {
              List<IssueScopeMemberResponse> members =
                  membersByGroup.getOrDefault(row.id(), List.of());
              long issueCount = members.stream().mapToLong(IssueScopeMemberResponse::issueCount).sum();
              return new IssueScopeGroupResponse(
                  row.id(),
                  catalog.id(),
                  catalog.projectId(),
                  catalog.projectName(),
                  catalog.dimension().name(),
                  row.businessKey(),
                  row.displayName(),
                  row.sortOrder(),
                  row.enabled(),
                  row.remark(),
                  issueCount,
                  members,
                  row.createdAt(),
                  row.updatedAt());
            })
        .toList();
  }

  /** 创建项目范围目录。 */
  @Transactional
  public IssueScopeCatalogResponse createCatalog(IssueScopeCatalogSaveRequest request) {
    NormalizedCatalog normalized = normalizeCatalog(request);
    try {
      jdbcTemplate.update(
          """
          insert into issue_scope_catalogs(
            project_id, project_name, dimension, enabled, remark, updated_at
          ) values (?, ?, ?, ?, ?, current_timestamp)
          """,
          normalized.projectId(),
          normalized.projectName(),
          normalized.dimension().name(),
          normalized.enabled(),
          normalized.remark());
    } catch (DuplicateKeyException error) {
      throw new BizException("该项目和匹配维度已存在议题范围目录");
    }
    return findCatalogByProjectAndDimension(normalized.projectId(), normalized.dimension());
  }

  /** 更新目录名称、启停和备注；项目与匹配维度创建后不可变。 */
  @Transactional
  public IssueScopeCatalogResponse updateCatalog(
      Long id, IssueScopeCatalogSaveRequest request) {
    CatalogRef current = requireCatalog(id);
    NormalizedCatalog normalized = normalizeCatalog(request);
    if (current.projectId() != normalized.projectId()
        || current.dimension() != normalized.dimension()) {
      throw new BizException("目录的项目和匹配维度不可修改，请新建目录");
    }
    jdbcTemplate.update(
        """
        update issue_scope_catalogs
           set project_name = ?, enabled = ?, remark = ?, updated_at = current_timestamp
         where id = ?
        """,
        normalized.projectName(),
        normalized.enabled(),
        normalized.remark(),
        id);
    return findCatalog(id);
  }

  /** 创建范围组，业务键用于查询，显示名称用于界面。 */
  @Transactional
  public IssueScopeGroupResponse createGroup(IssueScopeGroupSaveRequest request) {
    NormalizedGroup normalized = normalizeGroup(request);
    try {
      jdbcTemplate.update(
          """
          insert into issue_scope_groups(
            catalog_id, business_key, display_name, sort_order, enabled, remark, updated_at
          ) values (?, ?, ?, ?, ?, ?, current_timestamp)
          """,
          normalized.catalogId(),
          normalized.businessKey(),
          normalized.displayName(),
          normalized.sortOrder(),
          normalized.enabled(),
          normalized.remark());
    } catch (DuplicateKeyException error) {
      throw new BizException("当前目录中已存在相同业务键");
    }
    return findGroupByBusinessKey(normalized.catalogId(), normalized.businessKey());
  }

  /** 更新范围组；不允许把范围组移动到另一目录。 */
  @Transactional
  public IssueScopeGroupResponse updateGroup(Long id, IssueScopeGroupSaveRequest request) {
    GroupRef current = requireGroup(id);
    NormalizedGroup normalized = normalizeGroup(request);
    if (!Objects.equals(current.catalogId(), normalized.catalogId())) {
      throw new BizException("范围组不可移动到其他目录");
    }
    if (!current.businessKey().equals(normalized.businessKey())) {
      throw new BizException("范围业务键创建后不可修改");
    }
    try {
      jdbcTemplate.update(
          """
          update issue_scope_groups
             set business_key = ?, display_name = ?, sort_order = ?, enabled = ?, remark = ?,
                 updated_at = current_timestamp
           where id = ?
          """,
          normalized.businessKey(),
          normalized.displayName(),
          normalized.sortOrder(),
          normalized.enabled(),
          normalized.remark(),
          id);
    } catch (DuplicateKeyException error) {
      throw new BizException("当前目录中已存在相同业务键");
    }
    return findGroup(id);
  }

  /** 创建范围匹配成员。 */
  @Transactional
  public IssueScopeMemberResponse createMember(IssueScopeMemberSaveRequest request) {
    NormalizedMember normalized = normalizeMember(request);
    try {
      jdbcTemplate.update(
          """
          insert into issue_scope_members(
            catalog_id, group_id, source_value, display_name, sort_order,
            active_from, active_until, enabled, source_reference_id, remark, updated_at
          ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, current_timestamp)
          """,
          normalized.catalogId(),
          normalized.groupId(),
          normalized.sourceValue(),
          normalized.displayName(),
          normalized.sortOrder(),
          toTimestamp(normalized.activeFrom()),
          toTimestamp(normalized.activeUntil()),
          normalized.enabled(),
          normalized.sourceReferenceId(),
          normalized.remark());
    } catch (DuplicateKeyException error) {
      throw new BizException("该事实值已经归属于当前目录中的其他范围");
    }
    return findMemberBySourceValue(normalized.catalogId(), normalized.sourceValue());
  }

  /** 更新匹配成员；不允许跨目录或跨范围组移动。 */
  @Transactional
  public IssueScopeMemberResponse updateMember(Long id, IssueScopeMemberSaveRequest request) {
    MemberRef current = requireMember(id);
    NormalizedMember normalized = normalizeMember(request);
    if (!Objects.equals(current.catalogId(), normalized.catalogId())
        || !Objects.equals(current.groupId(), normalized.groupId())) {
      throw new BizException("匹配成员不可移动到其他目录或范围组");
    }
    try {
      jdbcTemplate.update(
          """
          update issue_scope_members
             set source_value = ?, display_name = ?, sort_order = ?,
                 active_from = ?, active_until = ?, enabled = ?, source_reference_id = ?,
                 remark = ?, updated_at = current_timestamp
           where id = ?
          """,
          normalized.sourceValue(),
          normalized.displayName(),
          normalized.sortOrder(),
          toTimestamp(normalized.activeFrom()),
          toTimestamp(normalized.activeUntil()),
          normalized.enabled(),
          normalized.sourceReferenceId(),
          normalized.remark(),
          id);
    } catch (DuplicateKeyException error) {
      throw new BizException("该事实值已经归属于当前目录中的其他范围");
    }
    return findMember(id);
  }

  /** 设置目录启用状态。 */
  public IssueScopeCatalogResponse setCatalogEnabled(Long id, boolean enabled) {
    requireCatalog(id);
    jdbcTemplate.update(
        "update issue_scope_catalogs set enabled = ?, updated_at = current_timestamp where id = ?",
        enabled,
        id);
    return findCatalog(id);
  }

  /** 设置范围组启用状态。 */
  public IssueScopeGroupResponse setGroupEnabled(Long id, boolean enabled) {
    requireGroup(id);
    jdbcTemplate.update(
        "update issue_scope_groups set enabled = ?, updated_at = current_timestamp where id = ?",
        enabled,
        id);
    return findGroup(id);
  }

  /** 设置匹配成员启用状态。 */
  public IssueScopeMemberResponse setMemberEnabled(Long id, boolean enabled) {
    requireMember(id);
    jdbcTemplate.update(
        "update issue_scope_members set enabled = ?, updated_at = current_timestamp where id = ?",
        enabled,
        id);
    return findMember(id);
  }

  /** 删除范围组及其成员，不修改事实数据。 */
  public void deleteGroup(Long id) {
    requireGroup(id);
    jdbcTemplate.update("delete from issue_scope_groups where id = ?", id);
  }

  /** 删除匹配成员，不修改事实数据。 */
  public void deleteMember(Long id) {
    requireMember(id);
    jdbcTemplate.update("delete from issue_scope_members where id = ?", id);
  }

  /**
   * 在单一事务内重排目录下的全部范围组。
   *
   * @param catalogId 目录 ID
   * @param ids 按目标顺序排列的全部范围组 ID
   */
  @Transactional
  public void reorderGroups(Long catalogId, List<Long> ids) {
    lockCatalog(catalogId);
    List<Long> current =
        jdbcTemplate.queryForList(
            "select id from issue_scope_groups where catalog_id = ? order by sort_order, id",
            Long.class,
            catalogId);
    validateCompleteOrder(current, ids, "范围组");
    updateOrder("issue_scope_groups", ids);
  }

  /** 在单一事务内重排范围组下的全部匹配成员。 */
  @Transactional
  public void reorderMembers(Long groupId, List<Long> ids) {
    requireGroupForUpdate(groupId);
    List<Long> current =
        jdbcTemplate.queryForList(
            "select id from issue_scope_members where group_id = ? order by sort_order, id",
            Long.class,
            groupId);
    validateCompleteOrder(current, ids, "匹配成员");
    updateOrder("issue_scope_members", ids);
  }

  /** 返回事实层已经存在但尚未归入任何范围组的值。 */
  public List<IssueScopeDiscoveredValueResponse> listUnassignedValues(Long catalogId) {
    CatalogRef catalog = requireCatalog(catalogId);
    String field = factField(catalog.dimension());
    String sql =
        """
        select btrim(f.%s) as source_value, count(*) as issue_count
          from issue_fact f
         where f.project_id = ?
           and f.deleted = false
           and nullif(btrim(f.%s), '') is not null
           and not exists (
             select 1
               from issue_scope_members m
              where m.catalog_id = ?
                and lower(m.source_value) = lower(btrim(f.%s))
           )
         group by btrim(f.%s)
         order by issue_count desc, source_value asc
        """.formatted(field, field, field, field);
    return jdbcTemplate.query(
        sql,
        (rs, rowNumber) ->
            new IssueScopeDiscoveredValueResponse(
                TextQuerySupport.normalizeDisplay(rs.getString("source_value")),
                rs.getLong("issue_count")),
        catalog.projectId(),
        catalog.id());
  }

  private Map<Long, List<IssueScopeMemberResponse>> loadMembers(
      CatalogRef catalog, List<Long> groupIds) {
    if (groupIds.isEmpty()) {
      return Map.of();
    }
    String placeholders = String.join(",", java.util.Collections.nCopies(groupIds.size(), "?"));
    String field = factField(catalog.dimension());
    String sql =
        """
        select m.id,
               m.catalog_id,
               m.group_id,
               m.source_value,
               m.display_name,
               m.sort_order,
               m.active_from,
               m.active_until,
               m.enabled,
               m.source_reference_id,
               m.remark,
               m.created_at,
               m.updated_at,
               (
                 select count(*)
                   from issue_fact f
                  where f.project_id = ?
                    and f.deleted = false
                    and lower(f.%s) = lower(m.source_value)
               ) as issue_count
          from issue_scope_members m
         where m.group_id in (%s)
         order by m.sort_order asc, m.id asc
        """.formatted(field, placeholders);
    List<Object> args = new ArrayList<>();
    args.add(catalog.projectId());
    args.addAll(groupIds);
    Map<Long, List<IssueScopeMemberResponse>> result = new LinkedHashMap<>();
    jdbcTemplate.query(
        sql,
        rs -> {
          IssueScopeMemberResponse member = mapMember(rs);
          result.computeIfAbsent(member.groupId(), ignored -> new ArrayList<>()).add(member);
        },
        args.toArray());
    return result;
  }

  private IssueScopeMemberResponse mapMember(ResultSet rs) throws SQLException {
    return new IssueScopeMemberResponse(
        rs.getLong("id"),
        rs.getLong("catalog_id"),
        rs.getLong("group_id"),
        TextQuerySupport.normalizeDisplay(rs.getString("source_value")),
        TextQuerySupport.normalizeDisplay(rs.getString("display_name")),
        rs.getInt("sort_order"),
        toLocalDateTime(rs.getTimestamp("active_from")),
        toLocalDateTime(rs.getTimestamp("active_until")),
        rs.getBoolean("enabled"),
        rs.getObject("source_reference_id", Long.class),
        TextQuerySupport.normalizeDisplay(rs.getString("remark")),
        rs.getLong("issue_count"),
        toLocalDateTime(rs.getTimestamp("created_at")),
        toLocalDateTime(rs.getTimestamp("updated_at")));
  }

  private GroupRow mapGroupRow(ResultSet rs, int rowNumber) throws SQLException {
    return new GroupRow(
        rs.getLong("id"),
        TextQuerySupport.normalizeDisplay(rs.getString("business_key")),
        TextQuerySupport.normalizeDisplay(rs.getString("display_name")),
        rs.getInt("sort_order"),
        rs.getBoolean("enabled"),
        TextQuerySupport.normalizeDisplay(rs.getString("remark")),
        toLocalDateTime(rs.getTimestamp("created_at")),
        toLocalDateTime(rs.getTimestamp("updated_at")));
  }

  private IssueScopeCatalogResponse findCatalog(Long id) {
    return listCatalogs().stream()
        .filter(catalog -> Objects.equals(catalog.id(), id))
        .findFirst()
        .orElseThrow(() -> new BizException("议题范围目录不存在"));
  }

  private IssueScopeCatalogResponse findCatalogByProjectAndDimension(
      long projectId, IssueScopeDimension dimension) {
    return listCatalogs().stream()
        .filter(catalog -> catalog.projectId() == projectId && catalog.dimension().equals(dimension.name()))
        .findFirst()
        .orElseThrow(() -> new BizException("议题范围目录保存失败"));
  }

  private IssueScopeGroupResponse findGroup(Long id) {
    GroupRef ref = requireGroup(id);
    return listGroups(ref.catalogId(), null, null).stream()
        .filter(group -> Objects.equals(group.id(), id))
        .findFirst()
        .orElseThrow(() -> new BizException("议题范围组不存在"));
  }

  private IssueScopeGroupResponse findGroupByBusinessKey(Long catalogId, String businessKey) {
    return listGroups(catalogId, null, null).stream()
        .filter(group -> group.businessKey().equalsIgnoreCase(businessKey))
        .findFirst()
        .orElseThrow(() -> new BizException("议题范围组保存失败"));
  }

  private IssueScopeMemberResponse findMember(Long id) {
    MemberRef ref = requireMember(id);
    return listGroups(ref.catalogId(), null, null).stream()
        .flatMap(group -> group.members().stream())
        .filter(member -> Objects.equals(member.id(), id))
        .findFirst()
        .orElseThrow(() -> new BizException("匹配成员不存在"));
  }

  private IssueScopeMemberResponse findMemberBySourceValue(Long catalogId, String sourceValue) {
    return listGroups(catalogId, null, null).stream()
        .flatMap(group -> group.members().stream())
        .filter(member -> member.sourceValue().equalsIgnoreCase(sourceValue))
        .findFirst()
        .orElseThrow(() -> new BizException("匹配成员保存失败"));
  }

  private CatalogRef requireCatalog(Long id) {
    if (id == null) {
      throw new BizException("议题范围目录不能为空");
    }
    List<CatalogRef> rows =
        jdbcTemplate.query(
            """
            select id, project_id, project_name, dimension
              from issue_scope_catalogs
             where id = ?
            """,
            (rs, rowNumber) ->
                new CatalogRef(
                    rs.getLong("id"),
                    rs.getLong("project_id"),
                    TextQuerySupport.normalizeDisplay(rs.getString("project_name")),
                    IssueScopeDimension.parse(rs.getString("dimension"))),
            id);
    if (rows.isEmpty()) {
      throw new BizException("议题范围目录不存在");
    }
    return rows.getFirst();
  }

  private GroupRef requireGroup(Long id) {
    if (id == null) {
      throw new BizException("议题范围组不能为空");
    }
    List<GroupRef> rows =
        jdbcTemplate.query(
            "select id, catalog_id, business_key from issue_scope_groups where id = ?",
            (rs, rowNumber) ->
                new GroupRef(
                    rs.getLong("id"),
                    rs.getLong("catalog_id"),
                    TextQuerySupport.normalizeDisplay(rs.getString("business_key"))),
            id);
    if (rows.isEmpty()) {
      throw new BizException("议题范围组不存在");
    }
    return rows.getFirst();
  }

  private MemberRef requireMember(Long id) {
    if (id == null) {
      throw new BizException("匹配成员不能为空");
    }
    List<MemberRef> rows =
        jdbcTemplate.query(
            "select id, catalog_id, group_id from issue_scope_members where id = ?",
            (rs, rowNumber) ->
                new MemberRef(
                    rs.getLong("id"), rs.getLong("catalog_id"), rs.getLong("group_id")),
            id);
    if (rows.isEmpty()) {
      throw new BizException("匹配成员不存在");
    }
    return rows.getFirst();
  }

  private void lockCatalog(Long id) {
    requireCatalog(id);
    jdbcTemplate.queryForObject(
        "select id from issue_scope_catalogs where id = ? for update", Long.class, id);
  }

  private void requireGroupForUpdate(Long id) {
    requireGroup(id);
    jdbcTemplate.queryForObject(
        "select id from issue_scope_groups where id = ? for update", Long.class, id);
  }

  private NormalizedCatalog normalizeCatalog(IssueScopeCatalogSaveRequest request) {
    if (request == null || request.projectId() == null || request.projectId() <= 0) {
      throw new BizException("项目 ID 必须大于 0");
    }
    String projectName = requireText(request.projectName(), "项目名称不能为空", 255);
    return new NormalizedCatalog(
        request.projectId(),
        projectName,
        IssueScopeDimension.parse(request.dimension()),
        request.enabled() == null || request.enabled(),
        normalizeOptional(request.remark(), 255, "备注长度不能超过 255 个字符"));
  }

  private NormalizedGroup normalizeGroup(IssueScopeGroupSaveRequest request) {
    if (request == null || request.catalogId() == null) {
      throw new BizException("议题范围目录不能为空");
    }
    requireCatalog(request.catalogId());
    return new NormalizedGroup(
        request.catalogId(),
        requireText(request.businessKey(), "业务键不能为空", 128),
        requireText(request.displayName(), "显示名称不能为空", 128),
        request.sortOrder() == null ? nextGroupOrder(request.catalogId()) : request.sortOrder(),
        request.enabled() == null || request.enabled(),
        normalizeOptional(request.remark(), 255, "备注长度不能超过 255 个字符"));
  }

  private NormalizedMember normalizeMember(IssueScopeMemberSaveRequest request) {
    if (request == null || request.catalogId() == null || request.groupId() == null) {
      throw new BizException("议题范围目录和范围组不能为空");
    }
    CatalogRef catalog = requireCatalog(request.catalogId());
    GroupRef group = requireGroup(request.groupId());
    if (!Objects.equals(group.catalogId(), catalog.id())) {
      throw new BizException("匹配成员所属目录与范围组不一致");
    }
    if (request.activeFrom() != null
        && request.activeUntil() != null
        && request.activeUntil().isBefore(request.activeFrom())) {
      throw new BizException("结束时间不能早于开始时间");
    }
    return new NormalizedMember(
        catalog.id(),
        group.id(),
        requireText(request.sourceValue(), "事实匹配值不能为空", 255),
        requireText(request.displayName(), "显示名称不能为空", 255),
        request.sortOrder() == null ? nextMemberOrder(group.id()) : request.sortOrder(),
        request.activeFrom(),
        request.activeUntil(),
        request.enabled() == null || request.enabled(),
        request.sourceReferenceId(),
        normalizeOptional(request.remark(), 255, "备注长度不能超过 255 个字符"));
  }

  private String requireText(String value, String emptyMessage, int maxLength) {
    String normalized = TextQuerySupport.trimToNull(value);
    if (normalized == null) {
      throw new BizException(emptyMessage);
    }
    if (normalized.length() > maxLength) {
      throw new BizException(emptyMessage.replace("不能为空", "长度不能超过 " + maxLength + " 个字符"));
    }
    return normalized;
  }

  private String normalizeOptional(String value, int maxLength, String errorMessage) {
    String normalized = TextQuerySupport.trimToNull(value);
    if (normalized != null && normalized.length() > maxLength) {
      throw new BizException(errorMessage);
    }
    return normalized;
  }

  private int nextGroupOrder(Long catalogId) {
    Integer value =
        jdbcTemplate.queryForObject(
            "select coalesce(max(sort_order), 0) + 1 from issue_scope_groups where catalog_id = ?",
            Integer.class,
            catalogId);
    return value == null ? 1 : value;
  }

  private int nextMemberOrder(Long groupId) {
    Integer value =
        jdbcTemplate.queryForObject(
            "select coalesce(max(sort_order), 0) + 1 from issue_scope_members where group_id = ?",
            Integer.class,
            groupId);
    return value == null ? 1 : value;
  }

  private void validateCompleteOrder(List<Long> current, List<Long> requested, String subject) {
    if (requested == null || requested.isEmpty()) {
      throw new BizException(subject + "排序不能为空");
    }
    Set<Long> requestedSet = new LinkedHashSet<>(requested);
    if (requestedSet.size() != requested.size() || !requestedSet.equals(new LinkedHashSet<>(current))) {
      throw new BizException(subject + "排序必须包含当前全部记录且不能重复");
    }
  }

  private void updateOrder(String tableName, List<Long> ids) {
    for (int index = 0; index < ids.size(); index++) {
      jdbcTemplate.update(
          "update " + tableName + " set sort_order = ?, updated_at = current_timestamp where id = ?",
          index + 1,
          ids.get(index));
    }
  }

  private long countUnassignedValues(
      long catalogId, long projectId, IssueScopeDimension dimension) {
    String field = factField(dimension);
    Long value =
        jdbcTemplate.queryForObject(
            """
            select count(*)
              from (
                select distinct btrim(f.%s) as source_value
                  from issue_fact f
                 where f.project_id = ?
                   and f.deleted = false
                   and nullif(btrim(f.%s), '') is not null
              ) values_in_fact
             where not exists (
               select 1
                 from issue_scope_members m
                where m.catalog_id = ?
                  and lower(m.source_value) = lower(values_in_fact.source_value)
             )
            """.formatted(field, field),
            Long.class,
            projectId,
            catalogId);
    return value == null ? 0L : value;
  }

  private String factField(IssueScopeDimension dimension) {
    return dimension == IssueScopeDimension.TESTING_PHASE ? "testing_phase" : "milestone_title";
  }

  private LocalDateTime toLocalDateTime(Timestamp timestamp) {
    return timestamp == null ? null : timestamp.toLocalDateTime();
  }

  private Timestamp toTimestamp(LocalDateTime value) {
    return value == null ? null : Timestamp.valueOf(value);
  }

  private record CatalogRef(
      Long id, long projectId, String projectName, IssueScopeDimension dimension) {}

  private record GroupRef(Long id, Long catalogId, String businessKey) {}

  private record MemberRef(Long id, Long catalogId, Long groupId) {}

  private record GroupRow(
      Long id,
      String businessKey,
      String displayName,
      Integer sortOrder,
      Boolean enabled,
      String remark,
      LocalDateTime createdAt,
      LocalDateTime updatedAt) {}

  private record NormalizedCatalog(
      long projectId,
      String projectName,
      IssueScopeDimension dimension,
      boolean enabled,
      String remark) {}

  private record NormalizedGroup(
      Long catalogId,
      String businessKey,
      String displayName,
      int sortOrder,
      boolean enabled,
      String remark) {}

  private record NormalizedMember(
      Long catalogId,
      Long groupId,
      String sourceValue,
      String displayName,
      int sortOrder,
      LocalDateTime activeFrom,
      LocalDateTime activeUntil,
      boolean enabled,
      Long sourceReferenceId,
      String remark) {}
}
