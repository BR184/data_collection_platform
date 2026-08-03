package com.data.collection.platform.service;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 在事实发布事务内维护客户里程碑目录的精确成员集合。 */
@Service
public class CustomerIssueMilestoneCatalogReconciliationService {
  private final JdbcTemplate jdbcTemplate;

  public CustomerIssueMilestoneCatalogReconciliationService(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /**
   * 将当前客户事实值补入同业务键的已启用范围；不创建范围、不启用或移动既有配置。
   *
   * @return 新增的精确成员数量
   */
  @Transactional
  public int reconcilePublishedFactValues() {
    Map<String, GroupRef> groups = loadEnabledGroups().stream()
        .collect(Collectors.toMap(
            group -> normalizedKey(group.businessKey()),
            Function.identity(),
            (first, ignored) -> first));
    if (groups.isEmpty()) {
      return 0;
    }
    int inserted = 0;
    for (String sourceValue : loadFactValues()) {
      GroupRef group = groups.get(normalizedKey(sourceValue));
      if (group == null) {
        continue;
      }
      int insertedMember = jdbcTemplate.update(
          """
          insert into issue_scope_members(
            catalog_id, group_id, source_value, display_name, sort_order, enabled, remark
          )
          select ?, ?, ?, ?, ordering.next_sort_order, true, '由已发布客户事实自动补齐'
            from (
              select coalesce(max(sort_order), 0) + 1 as next_sort_order
                from issue_scope_members
               where group_id = ?
            ) ordering
           where not exists (
             select 1
               from issue_scope_members existing
              where existing.catalog_id = ?
                and lower(existing.source_value) = lower(?)
           )
          on conflict (catalog_id, source_value) do nothing
          """,
          group.catalogId(),
          group.groupId(),
          sourceValue,
          sourceValue,
          group.groupId(),
          group.catalogId(),
          sourceValue);
      if (insertedMember > 0) {
        jdbcTemplate.update(
            """
            update issue_scope_groups
               set definition_generation = definition_generation + 1,
                   updated_at = current_timestamp
             where id = ?
            """,
            group.groupId());
        inserted += insertedMember;
      }
    }
    return inserted;
  }

  private List<GroupRef> loadEnabledGroups() {
    return jdbcTemplate.query(
        """
        select c.id as catalog_id, g.id as group_id, g.business_key
          from issue_scope_catalogs c
          join issue_scope_groups g on g.catalog_id = c.id and g.enabled = true
         where c.project_id = ? and c.dimension = 'MILESTONE' and c.enabled = true
         order by g.sort_order, g.id
        """,
        (rs, rowNumber) ->
            new GroupRef(
                rs.getLong("catalog_id"),
                rs.getLong("group_id"),
                rs.getString("business_key")),
        IssueScopeCatalogService.CC_PRODUCT_PROJECT_ID);
  }

  private List<String> loadFactValues() {
    return jdbcTemplate.queryForList(
        """
        select distinct btrim(milestone_title)
          from issue_fact
         where project_id = ?
           and deleted = false
           and nullif(btrim(milestone_title), '') is not null
         order by btrim(milestone_title)
        """,
        String.class,
        IssueScopeCatalogService.CC_PRODUCT_PROJECT_ID);
  }

  private String normalizedKey(String value) {
    return CustomerIssueMilestoneIdentity.businessKey(value).toLowerCase(Locale.ROOT);
  }

  private record GroupRef(long catalogId, long groupId, String businessKey) {}
}
