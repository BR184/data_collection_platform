package com.data.collection.platform.service;

import com.data.collection.platform.common.exception.FormConflictException;
import com.data.collection.platform.domain.form.FormSourceUrlSupport;
import com.data.collection.platform.domain.form.FormTemplateSpec;
import com.data.collection.platform.domain.form.FormTemplateSpecCatalog;
import com.data.collection.platform.service.form.FormRecordContextService;
import com.data.collection.platform.service.form.FormRecordDirectoryReader;
import com.data.collection.platform.service.form.FormRecordDirectoryReader.LocatedRecord;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Array;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 把权威表单目录投影成议题业务输入（N0 §7.2 九项选择器）。
 *
 * <p>读源只由 {@code form_resource_authorities} 的持久权威决定：{@code FORM} 按范围目录读取，
 * {@code COMMENT} 保持评论；启用点 T 只在首次登记时参与，不在每次消费时重算。响应与回复分别核验
 * 来源配置、实例、规范 URL、项目、模板与范围；缺失权威时已登记范围的项目进入“待登记”，身份冲突
 * 显式拒绝，都不静默切换来源。
 */
@Component
class FormIssueInputResolver {
  private static final String RESOURCE_TYPE = "issue";
  private static final String RESPONSE_COLUMN = "planned_resolution_date";
  private static final String BRANCH_COLUMN = "planned_merge_version_branch";
  private static final int RESOURCE_ID_CHUNK = 500;

  private final JdbcTemplate jdbcTemplate;
  private final FormRecordDirectoryReader directoryReader;
  private final FormRecordContextService contextService;
  private final PlatformIdentityService identityService;
  private final ObjectMapper objectMapper;

  FormIssueInputResolver(
      JdbcTemplate jdbcTemplate,
      FormRecordDirectoryReader directoryReader,
      FormRecordContextService contextService,
      PlatformIdentityService identityService,
      ObjectMapper objectMapper) {
    this.jdbcTemplate = jdbcTemplate;
    this.directoryReader = directoryReader;
    this.contextService = contextService;
    this.identityService = identityService;
    this.objectMapper = objectMapper;
  }

  /** 议题完整资源键：项目 + 项目内 IID。 */
  record IssueRef(long projectId, long issueIid) {}

  /**
   * 一次解析结果。
   *
   * @param inputs 已按持久权威确定读源的议题输入；未出现的议题保持评论口径
   * @param pendingRegistration 项目已登记范围但缺少持久权威的议题；本次不读取表单，待登记后重试
   */
  record Resolution(
      Map<IssueRef, IssueBusinessInput> inputs, Set<IssueRef> pendingRegistration) {
    static Resolution empty() {
      return new Resolution(Map.of(), Set.of());
    }
  }

  /**
   * 批量解析议题业务输入。
   *
   * @param sourceInstance 来源实例
   * @param refs 待解析议题
   * @return 表单输入与待登记集合；没有权威的议题不产生输入
   */
  Resolution resolveAll(String sourceInstance, Collection<IssueRef> refs) {
    if (refs == null || refs.isEmpty()) {
      return Resolution.empty();
    }
    List<IssueRef> distinctRefs = refs.stream().filter(Objects::nonNull).distinct().toList();
    List<ScopeRow> scopes = loadScopes(sourceInstance);
    Map<IssueRef, Map<String, AuthorityRow>> authorities =
        loadAuthorities(sourceInstance, distinctRefs, scopes);
    Map<IssueRef, List<LocatedRecord>> responses = new HashMap<>();
    Map<IssueRef, List<LocatedRecord>> replies = new HashMap<>();
    readAdoptedRecords(authorities, responses, replies);
    Map<IssueRef, Map<Long, Map<String, String>>> dictionaries = new HashMap<>();
    Map<IssueRef, IssueBusinessInput> inputs = new LinkedHashMap<>();
    Set<IssueRef> pending = new HashSet<>();
    for (IssueRef ref : distinctRefs) {
      Map<String, AuthorityRow> byTemplate =
          authorities.getOrDefault(ref, Map.of());
      boolean responseAdopted = isForm(byTemplate.get(FormTemplateSpecCatalog.ISSUE_RESPONSE));
      boolean replyAdopted = isForm(byTemplate.get(FormTemplateSpecCatalog.ISSUE_REPLY));
      if (responseAdopted || replyAdopted) {
        inputs.put(
            ref,
            select(
                responseAdopted,
                responses.getOrDefault(ref, List.of()),
                replyAdopted,
                replies.getOrDefault(ref, List.of()),
                this::resolveAccountName,
                record -> dictionaryFor(record, dictionaries.computeIfAbsent(ref, key -> new HashMap<>()))));
        continue;
      }
      if (hasCommentAuthority(byTemplate)) {
        continue;
      }
      if (hasCoveringScope(scopes, ref)) {
        pending.add(ref);
      }
    }
    return new Resolution(Map.copyOf(inputs), Set.copyOf(pending));
  }

  private boolean isForm(AuthorityRow authority) {
    return authority != null && "FORM".equals(authority.authority());
  }

  private boolean hasCommentAuthority(Map<String, AuthorityRow> byTemplate) {
    return byTemplate.values().stream().anyMatch(row -> "COMMENT".equals(row.authority()));
  }

  boolean hasCoveringScope(List<ScopeRow> scopes, IssueRef ref) {
    return scopes.stream()
        .anyMatch(
            scope ->
                scope.projectIds().contains(ref.projectId())
                    && (FormTemplateSpecCatalog.ISSUE_RESPONSE.equals(scope.templateKey())
                        || FormTemplateSpecCatalog.ISSUE_REPLY.equals(scope.templateKey())));
  }

  /**
   * 按九项选择器从已读出的响应/回复记录构造输入。
   *
   * @param responseAdopted 响应侧是否按表单权威读取
   * @param responses 有效响应记录（可为空）
   * @param replyAdopted 回复侧是否按表单权威读取
   * @param replies 有效回复记录（可为空）
   * @param nameResolver 账号到姓名快照的解析
   * @param dictionaryResolver 记录承载版本的稳定原因 key→规范标签
   * @return 来源无关业务输入
   */
  static IssueBusinessInput select(
      boolean responseAdopted,
      List<LocatedRecord> responses,
      boolean replyAdopted,
      List<LocatedRecord> replies,
      Function<String, String> nameResolver,
      Function<LocatedRecord, Map<String, String>> dictionaryResolver) {
    IssueBusinessInput.ResponseSnapshot response =
        responseAdopted ? responseSnapshot(responses) : IssueBusinessInput.ResponseSnapshot.absent();
    IssueBusinessInput.ReplySnapshot reply =
        replyAdopted
            ? replySnapshot(replies, nameResolver, dictionaryResolver)
            : IssueBusinessInput.ReplySnapshot.absent();
    return new IssueBusinessInput(responseAdopted, response, replyAdopted, reply);
  }

  private static IssueBusinessInput.ResponseSnapshot responseSnapshot(
      List<LocatedRecord> responses) {
    if (responses == null || responses.isEmpty()) {
      return IssueBusinessInput.ResponseSnapshot.absent();
    }
    List<LocatedRecord> ordered = new ArrayList<>(responses);
    ordered.sort(RECORD_ORDER);
    LocatedRecord first = ordered.getFirst();
    LocatedRecord latest = ordered.getLast();
    LocalDateTime plannedAt = plannedResolutionAt(latest);
    boolean planExempt =
        ordered.stream().anyMatch(record -> plannedResolutionAt(record) != null);
    return new IssueBusinessInput.ResponseSnapshot(
        true,
        latest.recordId(),
        first.createdAt(),
        plannedAt,
        plannedAt == null ? "" : plannedResolutionText(latest),
        plannedMergeVersionBranch(latest),
        planExempt);
  }

  private static IssueBusinessInput.ReplySnapshot replySnapshot(
      List<LocatedRecord> replies,
      Function<String, String> nameResolver,
      Function<LocatedRecord, Map<String, String>> dictionaryResolver) {
    if (replies == null || replies.isEmpty()) {
      return IssueBusinessInput.ReplySnapshot.absent();
    }
    List<LocatedRecord> ordered = new ArrayList<>(replies);
    ordered.sort(RECORD_ORDER);
    LocatedRecord latest = ordered.getLast();
    Map<String, String> dictionary =
        dictionaryResolver == null ? Map.of() : dictionaryResolver.apply(latest);
    List<String> causeKeys = readStringList(latest.fields().get("cause_keys"));
    List<String> causeLabels = new ArrayList<>();
    for (String key : causeKeys) {
      String label = dictionary.get(key);
      if (!StringUtils.hasText(label)) {
        throw new FormConflictException("回复原因键不在冻结词典：" + key + "；记录=" + latest.recordId());
      }
      causeLabels.add(label);
    }
    String createdBy = latest.createdBy();
    String fixUser = createdBy == null ? null : nameResolver.apply(createdBy);
    return new IssueBusinessInput.ReplySnapshot(
        true,
        latest.recordId(),
        latest.createdAt(),
        createdBy,
        fixUser,
        text(latest, "fix_status"),
        causeKeys,
        causeLabels,
        text(latest, "specific_reason"),
        text(latest, "modification_plan"),
        bool(latest, "caused_by_other_defect"),
        readStringList(latest.fields().get("affected_kinds")),
        text(latest, "known_affected_functions"),
        text(latest, "new_affected_functions"),
        text(latest, "impact_test_status"),
        bool(latest, "has_residual_impact"),
        text(latest, "residual_impact_detail"),
        text(latest, "relation_table_updated"),
        text(latest, "supplement_note"));
  }

  /** 记录顺序：创建时间升序，同一时刻按记录 ID 升序；编辑时间不参与重排。 */
  private static final Comparator<LocatedRecord> RECORD_ORDER =
      Comparator.comparing(LocatedRecord::createdAt, Comparator.nullsFirst(Comparator.naturalOrder()))
          .thenComparingLong(LocatedRecord::recordId);

  List<ScopeRow> loadScopes(String sourceInstance) {
    return jdbcTemplate.query(
        """
        select id, template_key, source_config_id, source_instance,
               canonical_gitlab_base_url, project_ids::text as project_ids, status,
               source_set_revision, source_set_hash
          from form_template_scopes
         where template_key in (?, ?)
           and source_instance = ?
           and resource_type = ?
           and status in ('ACTIVE', 'RETIRED')
         order by case status when 'ACTIVE' then 0 else 1 end, id
        """,
        (rs, rowNum) ->
            new ScopeRow(
                rs.getLong("id"),
                rs.getString("template_key"),
                rs.getLong("source_config_id"),
                rs.getString("source_instance"),
                rs.getString("canonical_gitlab_base_url"),
                parseProjectIds(rs.getString("project_ids")),
                rs.getString("status"),
                nullableLong(rs, "source_set_revision"),
                rs.getString("source_set_hash")),
        FormTemplateSpecCatalog.ISSUE_RESPONSE,
        FormTemplateSpecCatalog.ISSUE_REPLY,
        sourceInstance,
        RESOURCE_TYPE);
  }

  /**
   * 按完整资源身份加载持久权威并逐项核验；同一议题同一模板出现多份权威或范围身份不一致时拒绝。
   */
  Map<IssueRef, Map<String, AuthorityRow>> loadAuthorities(
      String sourceInstance, List<IssueRef> refs, List<ScopeRow> scopes) {
    Map<Long, List<IssueRef>> byProject = new LinkedHashMap<>();
    for (IssueRef ref : refs) {
      byProject.computeIfAbsent(ref.projectId(), key -> new ArrayList<>()).add(ref);
    }
    Map<Long, ScopeRow> scopesById = new HashMap<>();
    for (ScopeRow scope : scopes) {
      scopesById.put(scope.scopeId(), scope);
    }
    Map<IssueRef, Map<String, AuthorityRow>> result = new HashMap<>();
    for (Map.Entry<Long, List<IssueRef>> entry : byProject.entrySet()) {
      List<Long> ids =
          entry.getValue().stream().map(FormIssueInputResolver.IssueRef::issueIid).distinct().toList();
      for (int offset = 0; offset < ids.size(); offset += RESOURCE_ID_CHUNK) {
        List<Long> chunk =
            ids.subList(offset, Math.min(offset + RESOURCE_ID_CHUNK, ids.size()));
        List<AuthorityRow> rows =
            jdbcTemplate.query(
                """
                select a.source_config_id, a.source_instance, a.canonical_gitlab_base_url,
                       a.project_id, a.resource_type, a.resource_id, a.template_key, a.scope_id,
                       a.authority, a.authority_revision,
                       s.template_key as scope_template_key, s.source_instance as scope_source_instance,
                       s.canonical_gitlab_base_url as scope_canonical_url,
                       s.project_ids::text as scope_project_ids, s.status as scope_status
                  from form_resource_authorities a
                  join form_template_scopes s on s.id = a.scope_id
                 where a.source_instance = ? and a.resource_type = ? and a.project_id = ?
                   and a.template_key in (?, ?)
                   and a.resource_id in (%s)
                """
                    .formatted(placeholders(chunk.size())),
                (rs, rowNum) ->
                    new AuthorityRow(
                        rs.getLong("source_config_id"),
                        rs.getString("source_instance"),
                        rs.getString("canonical_gitlab_base_url"),
                        rs.getLong("project_id"),
                        rs.getString("resource_type"),
                        rs.getLong("resource_id"),
                        rs.getString("template_key"),
                        rs.getLong("scope_id"),
                        rs.getString("authority"),
                        rs.getLong("authority_revision"),
                        rs.getString("scope_template_key"),
                        rs.getString("scope_source_instance"),
                        rs.getString("scope_canonical_url"),
                        rs.getString("scope_project_ids"),
                        rs.getString("scope_status")),
                concatArgs(sourceInstance, entry.getKey(), chunk));
        for (AuthorityRow row : rows) {
          IssueRef ref = new IssueRef(row.projectId(), row.resourceId());
          validateAuthority(row, ref, sourceInstance, scopesById);
          Map<String, AuthorityRow> byTemplate =
              result.computeIfAbsent(ref, key -> new HashMap<>());
          AuthorityRow previous = byTemplate.putIfAbsent(row.templateKey(), row);
          if (previous != null) {
            throw new FormConflictException(
                "议题 " + ref.issueIid() + " 的 " + row.templateKey() + " 存在多份持久权威");
          }
        }
      }
    }
    return result;
  }

  private void validateAuthority(
      AuthorityRow row, IssueRef ref, String sourceInstance, Map<Long, ScopeRow> scopesById) {
    if (!sourceInstance.equals(row.sourceInstance())
        || !sourceInstance.equals(row.scopeSourceInstance())) {
      throw new FormConflictException(
          "议题 " + ref.issueIid() + " 的权威来源实例与消费来源不一致");
    }
    if (row.projectId() != ref.projectId() || !RESOURCE_TYPE.equals(row.resourceType())) {
      throw new FormConflictException("议题 " + ref.issueIid() + " 的权威资源身份不一致");
    }
    if (!row.templateKey().equals(row.scopeTemplateKey())
        || !List.of(FormTemplateSpecCatalog.ISSUE_RESPONSE, FormTemplateSpecCatalog.ISSUE_REPLY)
            .contains(row.templateKey())) {
      throw new FormConflictException("表单权威模板与范围模板不一致：" + row.templateKey());
    }
    if (!canonical(row.canonicalUrl()).equals(canonical(row.scopeCanonicalUrl()))) {
      throw new FormConflictException("表单权威与范围规范地址不一致：scope=" + row.scopeId());
    }
    if (!parseProjectIds(row.scopeProjectIds()).contains(ref.projectId())) {
      throw new FormConflictException("表单权威范围不包含议题项目：scope=" + row.scopeId());
    }
    ScopeRow scope = scopesById.get(row.scopeId());
    if (scope == null || !row.templateKey().equals(scope.templateKey())) {
      throw new FormConflictException("表单权威范围不存在或模板不一致：scope=" + row.scopeId());
    }
    if (!"ACTIVE".equals(row.scopeStatus()) && !"RETIRED".equals(row.scopeStatus())) {
      throw new FormConflictException("表单权威范围状态不可读：scope=" + row.scopeId());
    }
  }

  /** 读取 FORM 权威范围的记录，并核验记录完整来源身份与持久权威一致。 */
  private void readAdoptedRecords(
      Map<IssueRef, Map<String, AuthorityRow>> authorities,
      Map<IssueRef, List<LocatedRecord>> responses,
      Map<IssueRef, List<LocatedRecord>> replies) {
    Map<Long, Map<Long, List<Long>>> idsByScopeProject = new LinkedHashMap<>();
    Map<String, AuthorityRow> authorityByScopeResource = new HashMap<>();
    for (Map.Entry<IssueRef, Map<String, AuthorityRow>> entry : authorities.entrySet()) {
      for (AuthorityRow authority : entry.getValue().values()) {
        if (!"FORM".equals(authority.authority())) {
          continue;
        }
        idsByScopeProject
            .computeIfAbsent(authority.scopeId(), key -> new LinkedHashMap<>())
            .computeIfAbsent(entry.getKey().projectId(), key -> new ArrayList<>())
            .add(authority.resourceId());
        authorityByScopeResource.put(
            authority.scopeId() + ":" + entry.getKey().projectId() + ":" + authority.resourceId(),
            authority);
      }
    }
    for (Map.Entry<Long, Map<Long, List<Long>>> scopeEntry : idsByScopeProject.entrySet()) {
      for (Map.Entry<Long, List<Long>> projectEntry : scopeEntry.getValue().entrySet()) {
        List<LocatedRecord> records =
            directoryReader.listByResources(
                scopeEntry.getKey(),
                RESOURCE_TYPE,
                projectEntry.getValue().stream().map(String::valueOf).toList(),
                projectEntry.getKey());
        for (LocatedRecord record : records) {
          AuthorityRow authority =
              authorityByScopeResource.get(
                  record.scopeId() + ":" + record.projectId() + ":" + parseIid(record.resourceId()));
          if (authority == null) {
            throw new FormConflictException("表单记录缺少对应持久权威：record=" + record.recordId());
          }
          requireSameSourceIdentity(record, authority);
          IssueRef ref = new IssueRef(record.projectId(), parseIid(record.resourceId()));
          Map<IssueRef, List<LocatedRecord>> target =
              FormTemplateSpecCatalog.ISSUE_RESPONSE.equals(authority.templateKey())
                  ? responses
                  : replies;
          target.computeIfAbsent(ref, key -> new ArrayList<>()).add(record);
        }
      }
    }
  }

  private void requireSameSourceIdentity(LocatedRecord record, AuthorityRow authority) {
    if (record.scopeId() != authority.scopeId()
        || record.sourceConfigId() == null
        || record.sourceConfigId() != authority.sourceConfigId()
        || !Objects.equals(record.sourceInstance(), authority.sourceInstance())
        || record.projectId() != authority.projectId()
        || !RESOURCE_TYPE.equals(record.resourceType())
        || authority.resourceId() != parseIid(record.resourceId())
        || !canonical(record.gitlabBaseUrl()).equals(canonical(authority.canonicalUrl()))) {
      throw new FormConflictException(
          "表单记录完整来源身份与持久权威不一致：record=" + record.recordId());
    }
  }

  private Map<String, String> dictionaryFor(
      LocatedRecord record, Map<Long, Map<String, String>> cache) {
    return cache.computeIfAbsent(
        record.storageVersionId(),
        versionId -> {
          String raw =
              jdbcTemplate.queryForObject(
                  "select field_spec::text from form_template_versions where id = ?",
                  String.class,
                  versionId);
          FormTemplateSpec spec =
              contextService.parseSpec(raw, FormTemplateSpecCatalog.ISSUE_REPLY);
          return spec.dictionary("DEFECT_CAUSE")
              .map(
                  dictionary -> {
                    Map<String, String> labels = new HashMap<>();
                    for (var item : dictionary.items()) {
                      labels.put(item.key(), item.label());
                    }
                    return labels;
                  })
              .orElseThrow(
                  () -> new FormConflictException("回复版本缺少缺陷原因词典：version=" + versionId));
        });
  }

  private Set<Long> parseProjectIds(String raw) {
    Set<Long> ids = new HashSet<>();
    if (!StringUtils.hasText(raw)) {
      return ids;
    }
    try {
      JsonNode node = objectMapper.readTree(raw);
      if (node.isArray()) {
        for (JsonNode item : node) {
          if (item.canConvertToLong()) {
            ids.add(item.asLong());
          }
        }
      }
    } catch (Exception error) {
      throw new FormConflictException("表单范围项目集合无法解析");
    }
    return ids;
  }

  private String resolveAccountName(String account) {
    if (!StringUtils.hasText(account)) {
      return null;
    }
    return identityService.displayNameForUser(account, account);
  }

  private static LocalDateTime plannedResolutionAt(LocatedRecord record) {
    LocalDate date = toLocalDate(record.fields().get(RESPONSE_COLUMN));
    return date == null ? null : date.atStartOfDay();
  }

  private static String plannedResolutionText(LocatedRecord record) {
    LocalDate date = toLocalDate(record.fields().get(RESPONSE_COLUMN));
    return date == null ? "" : date.toString();
  }

  private static String plannedMergeVersionBranch(LocatedRecord record) {
    Object raw = record.fields().get(BRANCH_COLUMN);
    if (raw == null) {
      return null;
    }
    String text = raw.toString().trim();
    return text.isEmpty() ? null : text;
  }

  private static String text(LocatedRecord record, String column) {
    Object raw = record.fields().get(column);
    if (raw == null) {
      return null;
    }
    String text = raw.toString().trim();
    return text.isEmpty() ? null : text;
  }

  private static Boolean bool(LocatedRecord record, String column) {
    Object raw = record.fields().get(column);
    if (raw == null) {
      return null;
    }
    if (raw instanceof Boolean value) {
      return value;
    }
    return Boolean.parseBoolean(raw.toString());
  }

  @SuppressWarnings("unchecked")
  private static List<String> readStringList(Object raw) {
    List<String> values = new ArrayList<>();
    if (raw == null) {
      return values;
    }
    if (raw instanceof Array array) {
      try {
        raw = array.getArray();
      } catch (Exception error) {
        throw new FormConflictException("表单数组字段无法读取");
      }
      if (raw == null) {
        return values;
      }
    }
    if (raw instanceof String[] items) {
      for (String item : items) {
        addIfText(values, item);
      }
      return values;
    }
    if (raw instanceof Collection<?> items) {
      for (Object item : items) {
        addIfText(values, item == null ? null : String.valueOf(item));
      }
      return values;
    }
    if (raw instanceof String text) {
      for (String item : text.split(",")) {
        addIfText(values, item);
      }
      return values;
    }
    addIfText(values, raw.toString());
    return values;
  }

  private static void addIfText(List<String> values, String raw) {
    if (raw == null) {
      return;
    }
    String text = raw.trim();
    if (!text.isEmpty()) {
      values.add(text);
    }
  }

  private static LocalDate toLocalDate(Object raw) {
    if (raw == null) {
      return null;
    }
    if (raw instanceof LocalDate date) {
      return date;
    }
    if (raw instanceof java.sql.Date date) {
      return date.toLocalDate();
    }
    if (raw instanceof Timestamp timestamp) {
      return timestamp.toLocalDateTime().toLocalDate();
    }
    if (raw instanceof LocalDateTime dateTime) {
      return dateTime.toLocalDate();
    }
    String text = raw.toString().trim();
    return text.isEmpty() ? null : LocalDate.parse(text);
  }

  private static long parseIid(String resourceId) {
    try {
      return Long.parseLong(resourceId);
    } catch (NumberFormatException error) {
      throw new FormConflictException("议题资源编号不是数字：" + resourceId);
    }
  }

  private static String canonical(String raw) {
    try {
      return FormSourceUrlSupport.canonicalize(raw);
    } catch (IllegalArgumentException error) {
      throw new FormConflictException("表单来源地址非法：" + raw);
    }
  }

  private static String placeholders(int count) {
    return String.join(", ", java.util.Collections.nCopies(count, "?"));
  }

  private static Object[] concatArgs(
      String sourceInstance, long projectId, List<Long> resourceIds) {
    Object[] args = new Object[resourceIds.size() + 5];
    args[0] = sourceInstance;
    args[1] = RESOURCE_TYPE;
    args[2] = projectId;
    args[3] = FormTemplateSpecCatalog.ISSUE_RESPONSE;
    args[4] = FormTemplateSpecCatalog.ISSUE_REPLY;
    int index = 5;
    for (Long id : resourceIds) {
      args[index++] = id;
    }
    return java.util.Arrays.copyOf(args, index);
  }

  record ScopeRow(
      long scopeId,
      String templateKey,
      long sourceConfigId,
      String sourceInstance,
      String canonicalUrl,
      Set<Long> projectIds,
      String status,
      Long sourceSetRevision,
      String sourceSetHash) {}

  record AuthorityRow(
      long sourceConfigId,
      String sourceInstance,
      String canonicalUrl,
      long projectId,
      String resourceType,
      long resourceId,
      String templateKey,
      long scopeId,
      String authority,
      long authorityRevision,
      String scopeTemplateKey,
      String scopeSourceInstance,
      String scopeCanonicalUrl,
      String scopeProjectIds,
      String scopeStatus) {}

  private static Long nullableLong(java.sql.ResultSet rs, String column)
      throws java.sql.SQLException {
    long value = rs.getLong(column);
    return rs.wasNull() ? null : value;
  }
}
