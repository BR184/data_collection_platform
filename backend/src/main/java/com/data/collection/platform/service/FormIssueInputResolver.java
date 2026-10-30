package com.data.collection.platform.service;

import com.data.collection.platform.common.exception.FormConflictException;
import com.data.collection.platform.domain.form.FormTemplateSpecCatalog;
import com.data.collection.platform.service.form.FormRecordDirectoryReader;
import com.data.collection.platform.service.form.FormRecordDirectoryReader.LocatedRecord;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * <p>只读 {@code AUTHORITATIVE} 且已发布/已退役版本的目录记录，作废记录由目录读源排除。响应与回复
 * 各自按 {@code (createdAt, recordId)} 降序取最新整份，首次调研取最早响应；同一议题的记录若同时
 * 存在于多个权威范围，说明登记证据不一致，直接拒绝而不是合并。
 */
@Component
class FormIssueInputResolver {
  private static final String RESOURCE_TYPE = "issue";
  private static final String RESPONSE_COLUMN = "planned_resolution_date";
  private static final String BRANCH_COLUMN = "planned_merge_version_branch";

  private final JdbcTemplate jdbcTemplate;
  private final FormRecordDirectoryReader directoryReader;
  private final PlatformIdentityService identityService;
  private final ObjectMapper objectMapper;

  FormIssueInputResolver(
      JdbcTemplate jdbcTemplate,
      FormRecordDirectoryReader directoryReader,
      PlatformIdentityService identityService,
      ObjectMapper objectMapper) {
    this.jdbcTemplate = jdbcTemplate;
    this.directoryReader = directoryReader;
    this.identityService = identityService;
    this.objectMapper = objectMapper;
  }

  /** 议题完整资源键：项目 + 项目内 IID。 */
  record IssueRef(long projectId, long issueIid) {}

  /**
   * 批量解析议题业务输入。
   *
   * <p>没有登记范围或没有记录的议题返回空输入：新资源以表单为准，空记录表示尚未填写，而不是回退
   * 到评论。调用方必须只为启用点之后的议题调用本方法，历史议题保持原评论路径。
   *
   * @param sourceInstance 来源实例
   * @param refs 待解析议题
   * @return 与 {@code refs} 一一对应的输入；无记录时为空输入
   */
  Map<IssueRef, IssueBusinessInput> resolveAll(
      String sourceInstance, Collection<IssueRef> refs) {
    if (refs == null || refs.isEmpty()) {
      return Map.of();
    }
    List<IssueRef> distinctRefs =
        refs.stream().filter(Objects::nonNull).distinct().toList();
    Map<IssueRef, List<LocatedRecord>> responses = new HashMap<>();
    Map<IssueRef, List<LocatedRecord>> replies = new HashMap<>();
    Map<IssueRef, Set<Long>> responseScopes = new HashMap<>();
    Map<IssueRef, Set<Long>> replyScopes = new HashMap<>();
    Set<IssueRef> scopedRefs = new HashSet<>();
    for (ScopeRow scope : loadScopes(sourceInstance)) {
      Map<Long, List<String>> idsByProject = new LinkedHashMap<>();
      for (IssueRef ref : distinctRefs) {
        if (scope.projectIds().contains(ref.projectId())) {
          scopedRefs.add(ref);
          idsByProject
              .computeIfAbsent(ref.projectId(), project -> new ArrayList<>())
              .add(String.valueOf(ref.issueIid()));
        }
      }
      for (Map.Entry<Long, List<String>> entry : idsByProject.entrySet()) {
        List<LocatedRecord> records =
            directoryReader.listByResources(
                scope.scopeId(), RESOURCE_TYPE, entry.getValue(), entry.getKey());
        boolean responseTemplate =
            FormTemplateSpecCatalog.ISSUE_RESPONSE.equals(scope.templateKey());
        for (LocatedRecord record : records) {
          IssueRef ref = new IssueRef(entry.getKey(), parseIid(record.resourceId()));
          Map<IssueRef, List<LocatedRecord>> target = responseTemplate ? responses : replies;
          Map<IssueRef, Set<Long>> scopes = responseTemplate ? responseScopes : replyScopes;
          target.computeIfAbsent(ref, key -> new ArrayList<>()).add(record);
          scopes.computeIfAbsent(ref, key -> new HashSet<>()).add(scope.scopeId());
        }
      }
    }
    requireSingleScope(responseScopes, "响应");
    requireSingleScope(replyScopes, "回复");
    Map<IssueRef, IssueBusinessInput> inputs = new LinkedHashMap<>();
    for (IssueRef ref : distinctRefs) {
      if (!scopedRefs.contains(ref)) {
        // 没有登记范围的项目保持原评论口径；空表只对已登记范围表示“尚未填写”。
        continue;
      }
      inputs.put(
          ref,
          select(
              responses.getOrDefault(ref, List.of()),
              replies.getOrDefault(ref, List.of()),
              this::resolveAccountName));
    }
    return inputs;
  }

  /**
   * 按九项选择器从已读出的响应/回复记录构造输入。
   *
   * @param responses 有效响应记录（可为空）
   * @param replies 有效回复记录（可为空）
   * @param nameResolver 账号到姓名快照的解析
   * @return 来源无关业务输入
   */
  static IssueBusinessInput select(
      List<LocatedRecord> responses,
      List<LocatedRecord> replies,
      Function<String, String> nameResolver) {
    if (responses == null || responses.isEmpty()) {
      return new IssueBusinessInput(
          false, null, null, "", null, false, replies != null && !replies.isEmpty(),
          latestCreator(replies, nameResolver));
    }
    List<LocatedRecord> orderedResponses = new ArrayList<>(responses);
    orderedResponses.sort(RECORD_ORDER);
    LocatedRecord first = orderedResponses.getFirst();
    LocatedRecord latest = orderedResponses.getLast();
    LocalDateTime plannedAt = plannedResolutionAt(latest);
    boolean planExempt = orderedResponses.stream().anyMatch(record -> plannedResolutionAt(record) != null);
    List<LocatedRecord> orderedReplies =
        replies == null ? List.of() : new ArrayList<>(replies);
    orderedReplies.sort(RECORD_ORDER);
    return new IssueBusinessInput(
        true,
        first.createdAt(),
        plannedAt,
        plannedAt == null ? "" : plannedResolutionText(latest),
        plannedMergeVersionBranch(latest),
        planExempt,
        !orderedReplies.isEmpty(),
        latestCreator(orderedReplies, nameResolver));
  }

  /** 记录顺序：创建时间升序，同一时刻按记录 ID 升序；编辑时间不参与重排。 */
  private static final Comparator<LocatedRecord> RECORD_ORDER =
      Comparator.comparing(LocatedRecord::createdAt, Comparator.nullsFirst(Comparator.naturalOrder()))
          .thenComparingLong(LocatedRecord::recordId);

  private List<ScopeRow> loadScopes(String sourceInstance) {
    return jdbcTemplate.query(
        """
        select id, template_key, project_ids::text as project_ids
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
                parseProjectIds(rs.getString("project_ids"))),
        FormTemplateSpecCatalog.ISSUE_RESPONSE,
        FormTemplateSpecCatalog.ISSUE_REPLY,
        sourceInstance,
        RESOURCE_TYPE);
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

  private static void requireSingleScope(Map<IssueRef, Set<Long>> scopes, String kind) {
    for (Map.Entry<IssueRef, Set<Long>> entry : scopes.entrySet()) {
      if (entry.getValue().size() > 1) {
        throw new FormConflictException(
            "议题 " + entry.getKey().issueIid() + " 的" + kind + "记录存在于多个权威范围："
                + entry.getValue());
      }
    }
  }

  private String resolveAccountName(String account) {
    if (!StringUtils.hasText(account)) {
      return null;
    }
    return identityService.displayNameForUser(account, account);
  }

  private static String latestCreator(
      List<LocatedRecord> replies, Function<String, String> nameResolver) {
    if (replies == null || replies.isEmpty()) {
      return null;
    }
    List<LocatedRecord> ordered = new ArrayList<>(replies);
    ordered.sort(RECORD_ORDER);
    String createdBy = ordered.getLast().createdBy();
    return createdBy == null ? null : nameResolver.apply(createdBy);
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

  private record ScopeRow(long scopeId, String templateKey, Set<Long> projectIds) {}
}
