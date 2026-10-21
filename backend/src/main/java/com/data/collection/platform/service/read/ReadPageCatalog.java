package com.data.collection.platform.service.read;

import com.data.collection.platform.bi.application.BiPagePermissionResolver;
import com.data.collection.platform.entity.FactType;
import com.data.collection.platform.entity.read.ReadPublicationDomainKey;
import com.data.collection.platform.entity.read.ReadPublicationProducerKind;
import com.data.collection.platform.security.PlatformPermissionCodes;
import com.data.collection.platform.service.PagePermissionKeyResolver;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 页面读取闭包与既有权限的服务端目录；客户端只选页面和业务范围，不能选择发布点或任意关系。 */
@Service
public class ReadPageCatalog {
  private static final Set<String> ISSUE_BOARDS = Set.of("system-test-defect-summary", "system-test-delay-analysis",
      "system-test-defect-cause", "system-test-phase-statistics", "customer-issue-defect-summary",
      "customer-issue-customer-statistics", "customer-issue-defect-cause", "customer-issue-delay-issues",
      "customer-issue-response-efficiency", "customer-issue-by-function");
  private final JdbcTemplate jdbcTemplate;
  private final PagePermissionKeyResolver permissions;
  private final BiPagePermissionResolver biPermissions;
  private static final Set<String> SCOPE_KEYS = Set.of("sourceInstance", "source", "projectId", "testingPhase", "milestoneTitle",
      "milestoneBusinessKey", "productVersionId", "tableName", "topic", "repositoryName", "dimensionKey", "candidatePage", "dynamicSources");

  public ReadPageCatalog(JdbcTemplate jdbcTemplate, PagePermissionKeyResolver permissions, BiPagePermissionResolver biPermissions) {
    this.jdbcTemplate = jdbcTemplate;
    this.permissions = permissions;
    this.biPermissions = biPermissions;
  }

  /** 不新增业务权限，读取身份使用原页面查看权；未知页面显式拒绝。 */
  public String viewPermission(String pageKey) {
    if (ISSUE_BOARDS.contains(pageKey)) { return permissions.boardView(pageKey); }
    if (pageKey.startsWith("bi:")) { return biPermissions.viewCode(pageKey.substring(3)); }
    return switch (pageKey) {
      case "customer-issues-cc-product-issues" -> "customer_issue.record.view";
      case "customer-issues-illegal-records" -> "customer_issue.illegal.view";
      case "system-test-issues" -> "system_test.issue.view";
      case "system-test-illegal-records" -> "system_test.illegal.view";
      case "system-test-multi-board" -> "system_test.board.view";
      case "code-review-illegal-records" -> "code_review.illegal.view";
      case "code-review-multi", "quality-rd", "quality-board-other" -> permissions.dashboardView(pageKey);
      case "review-data-records" -> PlatformPermissionCodes.REVIEW_DATA_VIEW;
      case "label-group-values", "label-group-dynamic-values" -> "system.label_group.view";
      case "database-browser", "mirror-table-overview" -> PlatformPermissionCodes.SYSTEM_DATABASE_VIEW;
      default -> throw new IllegalArgumentException("页面未登记读取身份");
    };
  }

  /** 返回规范化业务范围；分页/排序/成员筛选不扩大来源闭包，不进入租约身份。 */
  public Map<String, String> normalizeScope(Map<String, String> scope) {
    if (scope == null) { return Map.of(); }
    Map<String, String> normalized = new java.util.TreeMap<>();
    for (var entry : scope.entrySet()) {
      if (!SCOPE_KEYS.contains(entry.getKey())) {
        throw new IllegalArgumentException("页面范围参数未登记：" + entry.getKey());
      }
      if (entry.getValue() != null && !entry.getValue().isBlank()) {
        String value = entry.getValue().strip();
        if (entry.getKey().equals("sourceInstance")) {
          value = com.data.collection.platform.service.GitlabSourceInstanceSupport.normalizeSourceInstance(value);
        } else if (entry.getKey().equals("source") || entry.getKey().equals("topic")) {
          value = value.toLowerCase(java.util.Locale.ROOT);
        } else if (entry.getKey().equals("projectId") || entry.getKey().equals("productVersionId")) {
          try {
            long id = Long.parseLong(value);
            if (id < 0) { throw new NumberFormatException(); }
            value = Long.toString(id);
          } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("页面范围 ID 必须是非负整数：" + entry.getKey());
          }
        }
        normalized.put(entry.getKey(), value);
      }
    }
    return java.util.Collections.unmodifiableMap(normalized);
  }

  /** 从完整查询参数提取来源范围，其余成员筛选/分页不被当成租约范围或业务缓存身份。 */
  public Map<String, String> scopeFromParameters(Map<String, String> parameters) {
    Map<String, String> scope = new java.util.TreeMap<>();
    if (parameters != null) {
      parameters.forEach((key, value) -> { if (SCOPE_KEYS.contains(key)) { scope.put(key, value); } });
    }
    return normalizeScope(scope);
  }

  /** 事实页面只选择已有完整来源；新来源尚未产出不使其它来源的既有完整视图失效。 */
  public Set<ReadPublicationDomainKey> factDomains(FactType factType, String selectedSource) {
    List<ReadPublicationDomainKey> available = jdbcTemplate.query("""
        select domain.producer_kind, domain.source_instance, domain.fact_type, domain.table_key
          from read_publication_domains domain join read_publications publication on publication.id = domain.current_publication_id
         where domain.fact_type = ? and publication.state = 'PUBLISHED'
           and (?::text is null or domain.source_instance = ?) order by publication.id
        """, (rs, row) -> new ReadPublicationDomainKey(ReadPublicationProducerKind.valueOf(rs.getString(1)),
            rs.getString(2), rs.getString(3), rs.getString(4)), factType.name(), selectedSource, selectedSource);
    if (available.isEmpty()) { throw ReadViewException.unavailable("该事实来源尚无完整产出"); }
    return java.util.Collections.unmodifiableSet(new LinkedHashSet<>(available));
  }

  /** 页面基础来源的类型契约；跨来源页面闭包由专属生产者目录补齐，不能按零值或当前原表顶替。 */
  public FactType primaryFactType(String pageKey) {
    if (ISSUE_BOARDS.contains(pageKey) || Set.of("customer-issues-cc-product-issues", "customer-issues-illegal-records",
        "system-test-issues", "system-test-illegal-records", "system-test-multi-board", "bi:customer-issues", "bi:system-test").contains(pageKey)) {
      return FactType.ISSUE;
    }
    if (Set.of("code-review-illegal-records", "code-review-multi", "bi:coding").contains(pageKey)) { return FactType.MERGE_REQUEST; }
    return null;
  }

  /** 根据固定模式确定实际来源闭包，设置版本与身份指针由准备事务统一冻结。 */
  public Set<ReadPublicationDomainKey> domains(String page, Map<String, String> scope, java.time.LocalDate businessDate) {
    return resolveDomains(page,scope,businessDate,null);
  }

  /** 下钻按父视图当时的来源集合和冻结模式确定目标依赖，不引入随后新增来源或新模式。 */
  public Set<ReadPublicationDomainKey> domainsForView(String page, Map<String,String> scope,
      com.data.collection.platform.entity.read.ReadView ancestor) {
    return resolveDomains(page,scope,ancestor.businessDate(),ancestor);
  }

  private Set<ReadPublicationDomainKey> resolveDomains(String page, Map<String,String> scope,
      java.time.LocalDate businessDate, com.data.collection.platform.entity.read.ReadView ancestor) {
    Set<ReadPublicationDomainKey> domains = new LinkedHashSet<>();
    if (page.equals("label-group-dynamic-values")) {
      var catalog = new com.data.collection.platform.service.labelgroup.LabelGroupDynamicRuleCatalogService();
      for (String sourceKey : scope.getOrDefault("dynamicSources", "").split(",")) {
        String table = catalog.requireSource(sourceKey).tableName();
        if (table.equals("issue_fact")) domains.addAll(readFactDomains(ancestor,FactType.ISSUE, null));
        else if (table.equals("merge_request_fact")) domains.addAll(readFactDomains(ancestor,FactType.MERGE_REQUEST, null));
        else if (Set.of("review_visible_records", "review_visible_problem_items").contains(table)) {
          domains.addAll(resolveDomains("review-data-records", Map.of(), businessDate, ancestor));
        } else {
          domains.add(new ReadPublicationDomainKey(ReadPublicationProducerKind.MIRROR_TABLE,"default","DATASET",table));
        }
      }
      return java.util.Collections.unmodifiableSet(domains);
    }
    if (page.equals("label-group-values")) {
      String relation = switch (scope.getOrDefault("dimensionKey", "")) {
        case "project" -> "labels"; case "person" -> "users"; case "milestone" -> "milestones"; default -> null;
      };
      if (relation != null) {
        return Set.of(new ReadPublicationDomainKey(ReadPublicationProducerKind.MIRROR_TABLE,"default","DATASET",
            com.data.collection.platform.service.GitlabSourceInstanceSupport.buildMirrorTableName(relation)));
      }
      String candidatePage = scope.get("candidatePage");
      if (candidatePage == null || candidatePage.equals("review-data-home")) {
        domains.addAll(resolveDomains("review-data-records", Map.of(), businessDate, ancestor));
      }
      if (candidatePage == null || Set.of("question-metrics-issue-search", "customer-issues-cc-product-issues").contains(candidatePage)) {
        domains.addAll(readFactDomains(ancestor,FactType.ISSUE, scope.get("sourceInstance")));
      }
      return java.util.Collections.unmodifiableSet(domains);
    }
    if (page.equals("system-test-defect-summary")) {
      Set<ReadPublicationDomainKey> primary = readFactDomains(ancestor,FactType.ISSUE, scope.get("sourceInstance"));
      // 主表的完整 ISSUE 产出独立存在。横向导出扩展来源只有具备完整闭包才一同冻结，
      // 尚未首次产出的扩展功能不能阻断已经完整的主表；也不能由原表补齐缺失来源。
      try {
        Set<ReadPublicationDomainKey> expanded = resolveDomains("system-test-horizontal-comparison", scope, businessDate, ancestor);
        for (var key : expanded) {
          Boolean ready = jdbcTemplate.queryForObject("""
              select exists(select 1 from read_publication_domains d join read_publications p on p.id = d.current_publication_id
                where d.producer_kind = ? and d.source_instance = ? and d.fact_type = ? and d.table_key = ? and p.state = 'PUBLISHED')
              """, Boolean.class, key.producerKind().name(), key.sourceInstance(), key.factType(), key.tableKey());
          if (!Boolean.TRUE.equals(ready)) return primary;
        }
        return expanded;
      } catch (ReadViewException unavailable) {
        if (unavailable.code() != ReadViewException.Code.READ_VIEW_UNAVAILABLE) throw unavailable;
        return primary;
      }
    }
    if (page.equals("mirror-table-overview")) {
      return Set.of(new ReadPublicationDomainKey(ReadPublicationProducerKind.FORMAL_RECORD, "default", "DATASET", "formal-records"));
    }
    if (page.equals("bi:unit-test") || page.equals("bi:integration-test")) {
      String version = scope.get("productVersionId");
      if (version == null || Long.parseLong(version) <= 0) { throw new IllegalArgumentException("CAT 读取必须选择产品版本"); }
      return Set.of(new ReadPublicationDomainKey(ReadPublicationProducerKind.CAT_MIRROR, "default", "DATASET",
          "cat:" + version + ":" + (page.equals("bi:unit-test") ? "UNIT_TEST" : "INTEGRATION_TEST")));
    }
    if (page.equals("database-browser")) {
      String table = scope.get("tableName");
      if (table == null) { throw new IllegalArgumentException("数据库内容读取必须选择表"); }
      var fact = switch (table) {
        case "issue_fact" -> FactType.ISSUE;
        case "merge_request_fact" -> FactType.MERGE_REQUEST;
        case "integration_test_fact" -> FactType.INTEGRATION_TEST;
        default -> null;
      };
      if (fact != null) { return readFactDomains(ancestor,fact, scope.get("sourceInstance")); }
      var nativeDomain = com.data.collection.platform.service.DatasetProducerCatalog.domainForRelation(table);
      if (nativeDomain.isPresent()) { return Set.of(nativeDomain.get()); }
      var mirror = jdbcTemplate.query("select d.source_instance from read_publication_domains d"
          + " join read_publications p on p.id = d.current_publication_id"
          + " where d.producer_kind = 'MIRROR_TABLE' and d.table_key = ? and p.state = 'PUBLISHED'",
          (rs, row) -> new ReadPublicationDomainKey(ReadPublicationProducerKind.MIRROR_TABLE,
              rs.getString(1), "DATASET", table), table);
      if (mirror.size() != 1) { throw ReadViewException.unavailable("镜像表尚无完整产出"); }
      return Set.copyOf(mirror);
    }
    if (Set.of("review-data-records", "code-review-illegal-records", "code-review-multi", "quality-rd", "quality-board-other",
        "bi:requirements", "bi:design", "bi:coding", "system-test-horizontal-comparison").contains(page)) {
      ReadPublicationDomainKey formal = new ReadPublicationDomainKey(ReadPublicationProducerKind.FORMAL_RECORD, "default", "DATASET", "formal-records");
      var contexts = ancestor == null ? jdbcTemplate.query("""
          select p.epoch_id, p.sequence_no, p.id, p.data_as_of from read_publication_domains d
          join read_publications p on p.id = d.current_publication_id
           where d.producer_kind = 'FORMAL_RECORD' and d.fact_type = 'DATASET' and d.source_instance = 'default'
             and d.table_key = 'formal-records' and p.state = 'PUBLISHED'
          """, (rs, row) -> new com.data.collection.platform.entity.read.PublishedDatasetReadContext("default", "formal-records",
              rs.getObject(1, java.util.UUID.class), rs.getLong(2), rs.getObject(3, java.util.UUID.class), rs.getTimestamp(4).toInstant()))
          : ancestor.domains().entrySet().stream().filter(entry -> entry.getKey().equals(formal))
              .map(entry -> (com.data.collection.platform.entity.read.PublishedDatasetReadContext)entry.getValue()).toList();
      if (contexts.size() != 1) { throw ReadViewException.unavailable("页面正式数据尚无完整产出"); }
      var selection = new com.data.collection.platform.entity.read.DatasetReadSelection(contexts, null, businessDate);
      var modes = new VersionedDatasetSql(jdbcTemplate).query(selection,
          "select enabled, code_review_read_mode, review_data_read_mode from code_review_match_mode_db_settings where id = 1",
          (rs, row) -> new ReadModes(rs.getBoolean(1) && "compatibility".equals(rs.getString(2)),
              rs.getBoolean(1) && "compatibility".equals(rs.getString(3))));
      if (modes.size() != 1) { throw ReadViewException.unavailable("页面来源模式尚无完整产出"); }
      domains.add(formal);
      boolean review = page.equals("review-data-records") || page.equals("system-test-horizontal-comparison") || page.startsWith("quality-")
          || page.equals("bi:requirements") || page.equals("bi:design");
      boolean code = !page.equals("review-data-records") && !page.equals("bi:requirements") && !page.equals("bi:design");
      if (review && modes.getFirst().review()) {
        domains.add(new ReadPublicationDomainKey(ReadPublicationProducerKind.COMPAT_REVIEW, "default", "DATASET", "compat-review"));
      }
      if (code) {
        if (modes.getFirst().code()) {
          domains.add(new ReadPublicationDomainKey(ReadPublicationProducerKind.COMPAT_WALKTHROUGH, "default", "DATASET", "compat-walkthrough"));
        } else {
          // 兼容 cc/dgm 与 GitLab 来源命名不同；业务 source 过滤在固定完整集合上执行。
          domains.addAll(readFactDomains(ancestor,FactType.MERGE_REQUEST, null));
        }
      }
      if (page.startsWith("quality-") || page.equals("system-test-horizontal-comparison")) { domains.addAll(readFactDomains(ancestor,FactType.ISSUE, scope.get("sourceInstance"))); }
      if (page.startsWith("quality-")) { domains.addAll(readFactDomains(ancestor,FactType.INTEGRATION_TEST, null)); }
      // 长期代码规模分母读取正式 MR，独立于当前走查兼容模式。
      if (page.equals("quality-board-other")) { domains.addAll(readFactDomains(ancestor,FactType.MERGE_REQUEST, null)); }
      if (page.equals("bi:coding")) { domains.addAll(readFactDomains(ancestor,FactType.MERGE_REQUEST, null)); }
      if (review) {
        domains.addAll(ancestor == null ? reviewDirectoryDomains() : ancestor.domains().keySet().stream()
            .filter(key -> key.producerKind() == ReadPublicationProducerKind.MIRROR_TABLE
                && List.of("labels","users","milestones").stream().map(com.data.collection.platform.service.GitlabSourceInstanceSupport::buildMirrorTableName)
                    .anyMatch(table -> table.equals(key.tableKey()))).collect(java.util.stream.Collectors.toSet()));
      }
      return java.util.Collections.unmodifiableSet(domains);
    }
    FactType type = primaryFactType(page);
    if (type == null) { throw new IllegalArgumentException("该页面的完整来源闭包尚未接入：" + page); }
    return readFactDomains(ancestor,type, scope.get("sourceInstance"));
  }

  private Set<ReadPublicationDomainKey> readFactDomains(com.data.collection.platform.entity.read.ReadView ancestor,
      FactType type, String selectedSource) {
    if (ancestor == null) return factDomains(type,selectedSource);
    Set<ReadPublicationDomainKey> selected = ancestor.domains().keySet().stream()
        .filter(key -> key.factType().equals(type.name()) && (selectedSource == null || selectedSource.equals(key.sourceInstance())))
        .collect(java.util.stream.Collectors.toSet());
    if (selected.isEmpty()) throw ReadViewException.unavailable("父完整视图不包含目标事实来源");
    return Set.copyOf(selected);
  }

  /** 现有评审候选来自默认镜像的标签/用户/里程碑；已配置目录必须绑定其完整表发布点。 */
  private Set<ReadPublicationDomainKey> reviewDirectoryDomains() {
    Set<ReadPublicationDomainKey> domains = new LinkedHashSet<>();
    for (String logical : List.of("labels", "users", "milestones")) {
      String table = com.data.collection.platform.service.GitlabSourceInstanceSupport.buildMirrorTableName(logical);
      if (!Boolean.TRUE.equals(jdbcTemplate.queryForObject("select to_regclass(?) is not null", Boolean.class, table))) { continue; }
      var available = jdbcTemplate.query("""
          select d.source_instance from read_publication_domains d
          join read_publications p on p.id = d.current_publication_id
          where d.producer_kind = 'MIRROR_TABLE' and d.table_key = ? and p.state = 'PUBLISHED'
          """, (rs, row) -> new ReadPublicationDomainKey(ReadPublicationProducerKind.MIRROR_TABLE,
              rs.getString(1), "DATASET", table), table);
      if (available.size() != 1) { throw ReadViewException.unavailable("评审候选目录尚无完整产出：" + logical); }
      domains.add(available.getFirst());
    }
    return Set.copyOf(domains);
  }

  private record ReadModes(boolean code, boolean review) {}
}
