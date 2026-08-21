package com.data.collection.platform.service;

import com.data.collection.platform.service.statistics.CustomerIssueMilestoneOrdering;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 在事实发布事务内维护客户里程碑目录的精确成员集合。 */
@Service
public class CustomerIssueMilestoneCatalogReconciliationService {
  private static final int BUSINESS_KEY_MAX_LENGTH = 128;
  private static final int SOURCE_VALUE_MAX_LENGTH = 255;

  private final JdbcTemplate jdbcTemplate;

  public CustomerIssueMilestoneCatalogReconciliationService(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /**
   * 将当前客户事实值同步到客户里程碑目录。
   *
   * <p>目录完全没有范围组时，视为首次初始化并从已发布事实建立初始组和成员；目录已有
   * 任意范围组后，只向业务键相同的启用组补充缺失成员，保留管理员配置边界。
   *
   * @return 新增的精确成员数量；目录没有事实或被停用时返回 0
   */
  @Transactional
  public int reconcilePublishedFactValues() {
    Optional<CatalogRef> catalog = lockEnabledCatalog();
    if (catalog.isEmpty()) {
      return 0;
    }

    List<String> factValues = loadFactValues();
    if (factValues.isEmpty()) {
      return 0;
    }

    List<GroupRef> groups = loadGroups(catalog.get().id());
    if (groups.isEmpty()) {
      return bootstrapCatalog(catalog.get(), factValues);
    }
    return reconcileExistingGroups(groups, factValues);
  }

  private Optional<CatalogRef> lockEnabledCatalog() {
    return jdbcTemplate.query(
            """
            select id
              from issue_scope_catalogs
             where project_id = ?
               and dimension = 'MILESTONE'
               and enabled = true
             order by id
               for update
            """,
            (rs, rowNumber) -> new CatalogRef(rs.getLong("id")),
            IssueScopeCatalogService.CC_PRODUCT_PROJECT_ID)
        .stream()
        .findFirst();
  }

  private List<GroupRef> loadGroups(long catalogId) {
    return jdbcTemplate.query(
        """
        select id, business_key, enabled
          from issue_scope_groups
         where catalog_id = ?
         order by sort_order, id
        """,
        (rs, rowNumber) ->
            new GroupRef(
                catalogId,
                rs.getLong("id"),
                rs.getString("business_key"),
                rs.getBoolean("enabled")),
        catalogId);
  }

  private int bootstrapCatalog(CatalogRef catalog, List<String> factValues) {
    Map<String, FactGroup> groupsByNormalizedKey = groupFactValues(factValues);
    int insertedMembers = 0;
    int sortOrder = 0;
    List<String> orderedBusinessKeys = CustomerIssueMilestoneOrdering.sortLatestFirst(
        groupsByNormalizedKey.values().stream().map(FactGroup::businessKey).toList());
    for (String businessKey : orderedBusinessKeys) {
      FactGroup group = groupsByNormalizedKey.get(businessKey.toLowerCase(Locale.ROOT));
      if (group == null || !isValidBusinessKey(group.businessKey())) {
        continue;
      }
      long groupId = insertGroup(
          catalog.id(), group.businessKey(), group.sourceValues().getFirst(), sortOrder++);
      int groupMembers = insertMembers(
          new GroupRef(catalog.id(), groupId, group.businessKey(), true),
          group.sourceValues(),
          "由已发布客户事实初始化");
      if (groupMembers > 0) {
        advanceGroupGeneration(groupId);
        insertedMembers += groupMembers;
      }
    }
    return insertedMembers;
  }

  private int reconcileExistingGroups(List<GroupRef> groups, List<String> factValues) {
    Map<String, GroupRef> enabledGroups = new LinkedHashMap<>();
    for (GroupRef group : groups) {
      if (group.enabled()) {
        enabledGroups.putIfAbsent(normalizedKey(group.businessKey()), group);
      }
    }
    int inserted = 0;
    for (String sourceValue : factValues) {
      GroupRef group = enabledGroups.get(normalizedKey(sourceValue));
      if (group == null) {
        continue;
      }
      int insertedMember = insertMembers(group, List.of(sourceValue), "由已发布客户事实自动补齐");
      if (insertedMember > 0) {
        advanceGroupGeneration(group.groupId());
        inserted += insertedMember;
      }
    }
    return inserted;
  }

  private long insertGroup(long catalogId, String businessKey, String displayName, int sortOrder) {
    return jdbcTemplate.queryForObject(
        """
        insert into issue_scope_groups(
          catalog_id, business_key, display_name, sort_order, enabled, remark, updated_at
        ) values (?, ?, ?, ?, true, '由已发布客户事实初始化', current_timestamp)
        returning id
        """,
        Long.class,
        catalogId,
        businessKey,
        displayName,
        sortOrder);
  }

  private int insertMembers(GroupRef group, Collection<String> sourceValues, String remark) {
    int inserted = 0;
    for (String sourceValue : sourceValues) {
      int insertedMember = jdbcTemplate.update(
          """
          insert into issue_scope_members(
            catalog_id, group_id, source_value, display_name, sort_order, enabled, remark
          )
          select ?, ?, ?, ?, ordering.next_sort_order, true, ?
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
          remark,
          group.groupId(),
          group.catalogId(),
          sourceValue);
      inserted += insertedMember;
    }
    return inserted;
  }

  private Map<String, FactGroup> groupFactValues(List<String> factValues) {
    Map<String, String> businessKeys = new LinkedHashMap<>();
    Map<String, LinkedHashMap<String, String>> sourceValuesByKey = new LinkedHashMap<>();
    for (String sourceValue : factValues) {
      String businessKey = CustomerIssueMilestoneIdentity.businessKey(sourceValue);
      if (!isValidBusinessKey(businessKey) || !isValidSourceValue(sourceValue)) {
        continue;
      }
      String normalizedKey = businessKey.toLowerCase(Locale.ROOT);
      businessKeys.putIfAbsent(normalizedKey, businessKey);
      sourceValuesByKey
          .computeIfAbsent(normalizedKey, ignored -> new LinkedHashMap<>())
          .putIfAbsent(sourceValue.toLowerCase(Locale.ROOT), sourceValue);
    }
    Map<String, FactGroup> result = new LinkedHashMap<>();
    businessKeys.forEach(
        (normalizedKey, businessKey) ->
            result.put(
                normalizedKey,
                new FactGroup(
                    businessKey,
                    sourceValuesByKey.get(normalizedKey).values().stream()
                        .sorted(String.CASE_INSENSITIVE_ORDER)
                        .toList())));
    return result;
  }

  private boolean isValidBusinessKey(String businessKey) {
    return !businessKey.isEmpty() && businessKey.length() <= BUSINESS_KEY_MAX_LENGTH;
  }

  private boolean isValidSourceValue(String sourceValue) {
    return sourceValue.length() <= SOURCE_VALUE_MAX_LENGTH;
  }

  private void advanceGroupGeneration(long groupId) {
    jdbcTemplate.update(
        """
        update issue_scope_groups
           set definition_generation = definition_generation + 1,
               updated_at = current_timestamp
         where id = ?
        """,
        groupId);
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

  private record CatalogRef(long id) {}

  private record FactGroup(String businessKey, List<String> sourceValues) {}

  private record GroupRef(long catalogId, long groupId, String businessKey, boolean enabled) {}
}
