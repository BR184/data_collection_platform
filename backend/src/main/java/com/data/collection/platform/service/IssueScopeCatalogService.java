package com.data.collection.platform.service;

import com.data.collection.platform.common.exception.BizException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 项目级议题范围目录的唯一读取入口。
 *
 * <p>目录只负责把稳定业务键展开为精确事实值；调用方仍按目录维度查询对应事实字段。
 */
@Service
public class IssueScopeCatalogService {
  public static final long CROWN_CAD_PROJECT_ID = 9L;
  public static final long CC_PRODUCT_PROJECT_ID = 325L;

  private final JdbcTemplate jdbcTemplate;

  public IssueScopeCatalogService(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /**
   * 按管理员顺序返回项目维度下的全部启用范围。
   *
   * @param projectId GitLab 项目 ID
   * @param dimension 事实匹配维度
   * @return 启用范围及其启用成员
   */
  public List<ScopeGroup> listEnabledGroups(long projectId, IssueScopeDimension dimension) {
    return loadEnabledGroups(new CatalogKey(projectId, dimension));
  }

  /** 返回项目维度下按管理员顺序排列的稳定业务键。 */
  public List<String> listEnabledBusinessKeys(long projectId, IssueScopeDimension dimension) {
    return listEnabledGroups(projectId, dimension).stream().map(ScopeGroup::businessKey).toList();
  }

  /** 返回第一条启用范围的稳定业务键；未配置时返回空字符串。 */
  public String defaultBusinessKey(long projectId, IssueScopeDimension dimension) {
    return listEnabledGroups(projectId, dimension).stream()
        .map(ScopeGroup::businessKey)
        .findFirst()
        .orElse("");
  }

  /** 按稳定业务键查找启用范围，匹配不区分大小写。 */
  public Optional<ScopeGroup> findEnabledGroup(
      long projectId, IssueScopeDimension dimension, String businessKey) {
    String normalized = TextQuerySupport.trimToNull(businessKey);
    if (normalized == null) {
      return Optional.empty();
    }
    return listEnabledGroups(projectId, dimension).stream()
        .filter(group -> group.businessKey().equalsIgnoreCase(normalized))
        .findFirst();
  }

  /**
   * 将稳定业务键展开为精确事实值。
   *
   * @throws BizException 范围不存在、已停用或没有启用成员时抛出
   */
  public List<String> requireMemberValues(
      long projectId, IssueScopeDimension dimension, String businessKey) {
    ScopeGroup group =
        findEnabledGroup(projectId, dimension, businessKey)
            .orElseThrow(() -> new BizException("议题范围不存在或已停用：" + businessKey));
    List<String> values = group.members().stream().map(ScopeMember::sourceValue).toList();
    if (values.isEmpty()) {
      throw new BizException("议题范围没有启用的匹配值：" + group.displayName());
    }
    return values;
  }

  /** 判断事实值是否属于指定启用范围。 */
  public boolean matches(
      long projectId,
      IssueScopeDimension dimension,
      String businessKey,
      String sourceValue) {
    String normalizedSource = TextQuerySupport.trimToNull(sourceValue);
    if (normalizedSource == null) {
      return false;
    }
    return findEnabledGroup(projectId, dimension, businessKey).stream()
        .flatMap(group -> group.members().stream())
        .map(ScopeMember::sourceValue)
        .anyMatch(value -> value.equalsIgnoreCase(normalizedSource));
  }

  private List<ScopeGroup> loadEnabledGroups(CatalogKey key) {
    List<ScopeRow> rows =
        jdbcTemplate.query(
            """
            select c.id as catalog_id,
                   c.project_id,
                   c.project_name,
                   c.dimension,
                   g.id as group_id,
                   g.business_key,
                   g.display_name as group_display_name,
                   g.sort_order as group_sort_order,
                   m.id as member_id,
                   m.source_value,
                   m.display_name as member_display_name,
                   m.sort_order as member_sort_order,
                   m.active_from,
                   m.active_until
              from issue_scope_catalogs c
              join issue_scope_groups g
                on g.catalog_id = c.id
               and g.enabled = true
              left join issue_scope_members m
                on m.group_id = g.id
               and m.catalog_id = c.id
               and m.enabled = true
             where c.project_id = ?
               and c.dimension = ?
               and c.enabled = true
             order by g.sort_order asc, g.id asc, m.sort_order asc, m.id asc
            """,
            this::mapRow,
            key.projectId(),
            key.dimension().name());
    Map<Long, MutableScopeGroup> groups = new LinkedHashMap<>();
    for (ScopeRow row : rows) {
      MutableScopeGroup group =
          groups.computeIfAbsent(
              row.groupId(),
              ignored ->
                  new MutableScopeGroup(
                      row.catalogId(),
                      row.projectId(),
                      row.projectName(),
                      IssueScopeDimension.parse(row.dimension()),
                      row.groupId(),
                      row.businessKey(),
                      row.groupDisplayName(),
                      row.groupSortOrder()));
      if (row.memberId() != null) {
        group.addMember(
            new ScopeMember(
                row.memberId(),
                row.sourceValue(),
                row.memberDisplayName(),
                row.memberSortOrder(),
                row.activeFrom(),
                row.activeUntil()));
      }
    }
    return groups.values().stream().map(MutableScopeGroup::toImmutable).toList();
  }

  private ScopeRow mapRow(ResultSet rs, int rowNumber) throws SQLException {
    return new ScopeRow(
        rs.getLong("catalog_id"),
        rs.getLong("project_id"),
        TextQuerySupport.normalizeDisplay(rs.getString("project_name")),
        rs.getString("dimension"),
        rs.getLong("group_id"),
        TextQuerySupport.normalizeDisplay(rs.getString("business_key")),
        TextQuerySupport.normalizeDisplay(rs.getString("group_display_name")),
        rs.getInt("group_sort_order"),
        rs.getObject("member_id", Long.class),
        TextQuerySupport.normalizeDisplay(rs.getString("source_value")),
        TextQuerySupport.normalizeDisplay(rs.getString("member_display_name")),
        rs.getObject("member_sort_order", Integer.class),
        toLocalDateTime(rs, "active_from"),
        toLocalDateTime(rs, "active_until"));
  }

  private LocalDateTime toLocalDateTime(ResultSet rs, String column) throws SQLException {
    return rs.getTimestamp(column) == null ? null : rs.getTimestamp(column).toLocalDateTime();
  }

  public record ScopeGroup(
      long catalogId,
      long projectId,
      String projectName,
      IssueScopeDimension dimension,
      long id,
      String businessKey,
      String displayName,
      int sortOrder,
      List<ScopeMember> members) {
    public ScopeGroup {
      members = members == null ? List.of() : List.copyOf(members);
    }
  }

  public record ScopeMember(
      long id,
      String sourceValue,
      String displayName,
      int sortOrder,
      LocalDateTime activeFrom,
      LocalDateTime activeUntil) {}

  private record CatalogKey(long projectId, IssueScopeDimension dimension) {}

  private record ScopeRow(
      long catalogId,
      long projectId,
      String projectName,
      String dimension,
      long groupId,
      String businessKey,
      String groupDisplayName,
      int groupSortOrder,
      Long memberId,
      String sourceValue,
      String memberDisplayName,
      Integer memberSortOrder,
      LocalDateTime activeFrom,
      LocalDateTime activeUntil) {}

  private static final class MutableScopeGroup {
    private final long catalogId;
    private final long projectId;
    private final String projectName;
    private final IssueScopeDimension dimension;
    private final long groupId;
    private final String businessKey;
    private final String displayName;
    private final int sortOrder;
    private final List<ScopeMember> members = new ArrayList<>();
    private final Set<String> sourceValues = new LinkedHashSet<>();

    private MutableScopeGroup(
        long catalogId,
        long projectId,
        String projectName,
        IssueScopeDimension dimension,
        long groupId,
        String businessKey,
        String displayName,
        int sortOrder) {
      this.catalogId = catalogId;
      this.projectId = projectId;
      this.projectName = projectName;
      this.dimension = dimension;
      this.groupId = groupId;
      this.businessKey = businessKey;
      this.displayName = displayName;
      this.sortOrder = sortOrder;
    }

    private void addMember(ScopeMember member) {
      String key = member.sourceValue().toLowerCase(Locale.ROOT);
      if (sourceValues.add(key)) {
        members.add(member);
      }
    }

    private ScopeGroup toImmutable() {
      return new ScopeGroup(
          catalogId,
          projectId,
          projectName,
          dimension,
          groupId,
          businessKey,
          displayName,
          sortOrder,
          members);
    }
  }
}
