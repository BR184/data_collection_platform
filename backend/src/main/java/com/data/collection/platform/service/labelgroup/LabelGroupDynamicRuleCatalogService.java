package com.data.collection.platform.service.labelgroup;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.entity.OptionItemResponse;
import com.data.collection.platform.entity.labelgroup.LabelGroupDynamicRuleRelationResponse;
import com.data.collection.platform.entity.labelgroup.LabelGroupDynamicRuleSourceFieldResponse;
import com.data.collection.platform.entity.labelgroup.LabelGroupDynamicRuleSourceResponse;
import com.data.collection.platform.entity.labelgroup.LabelGroupRuleRelationRequest;
import com.data.collection.platform.service.IssueDisplayValueSupport;
import com.data.collection.platform.service.TextQuerySupport;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class LabelGroupDynamicRuleCatalogService {
  static final String VALUE_STRING = "STRING";
  static final String VALUE_NUMBER = "NUMBER";
  static final String VALUE_DATE = "DATE";
  static final String VALUE_BOOLEAN = "BOOLEAN";
  static final String CANDIDATE_NONE = "NONE";
  static final String CANDIDATE_STATIC = "STATIC";
  static final String CANDIDATE_DISTINCT = "DISTINCT";
  private static final String FACT_ACTIVE_COLUMN = "deleted";
  private static final String MIRROR_ACTIVE_COLUMN = "mirror_deleted";

  private static final List<String> TEXT_OPERATORS =
      List.of("eq", "ne", "contains", "notContains", "startsWith", "endsWith", "isEmpty", "isNotEmpty", "in");
  private static final List<String> NUMBER_OPERATORS =
      List.of("eq", "ne", "gt", "gte", "lt", "lte", "between", "isEmpty", "isNotEmpty", "in");
  private static final List<String> DATE_OPERATORS =
      List.of("eq", "ne", "gt", "gte", "lt", "lte", "between", "lastDays", "isEmpty", "isNotEmpty");
  private static final List<String> BOOLEAN_OPERATORS = List.of("eq", "ne", "isEmpty", "isNotEmpty");
  private static final Set<String> MATCH_OPERATORS = Set.of("eq");
  private static final Set<String> NORMALIZERS = Set.of("NONE", "TRIM", "LOWER", "TRIM_LOWER");

  private final Map<String, DynamicRuleSourceDefinition> sourceByKey;
  private final List<DynamicRuleRelationDefinition> registeredRelations;

  public LabelGroupDynamicRuleCatalogService() {
    this.sourceByKey = buildSources();
    this.registeredRelations = buildRelations();
  }

  public List<LabelGroupDynamicRuleSourceResponse> listSources() {
    return sourceByKey.values().stream()
        .map(source -> new LabelGroupDynamicRuleSourceResponse(
            source.key(),
            source.name(),
            source.description(),
            source.fields().values().stream()
                .map(field -> new LabelGroupDynamicRuleSourceFieldResponse(
                    field.key(),
                    field.name(),
                    field.valueType(),
                    field.outputSupported(),
                    field.filterSupported(),
                    field.groupSupported(),
                    field.aggregateSupported(),
                    operatorsFor(field.valueType()),
                    field.candidateMode(),
                    field.staticCandidates()))
                .toList()))
        .toList();
  }

  public List<LabelGroupDynamicRuleRelationResponse> listRelations() {
    return registeredRelations.stream()
        .map(relation -> new LabelGroupDynamicRuleRelationResponse(
            relation.name(),
            relation.leftSourceKey(),
            relation.leftFieldKey(),
            relation.rightSourceKey(),
            relation.rightFieldKey(),
            relation.matchOperator(),
            relation.normalizer()))
        .toList();
  }

  public DynamicRuleSourceDefinition requireSource(String sourceKey) {
    String key = requireKey(sourceKey, "数据源不能为空");
    DynamicRuleSourceDefinition source = sourceByKey.get(key);
    if (source == null) {
      throw new BizException("动态规则数据源不存在：" + sourceKey);
    }
    return source;
  }

  public DynamicRuleFieldDefinition requireField(String sourceKey, String fieldKey) {
    DynamicRuleSourceDefinition source = requireSource(sourceKey);
    String key = requireKey(fieldKey, "字段不能为空");
    DynamicRuleFieldDefinition field = source.fields().get(key);
    if (field == null) {
      throw new BizException("动态规则字段不存在：" + source.name() + "." + fieldKey);
    }
    return field;
  }

  DynamicRuleRelationDefinition normalizeRelation(LabelGroupRuleRelationRequest request) {
    if (request == null) {
      throw new BizException("逻辑关联不能为空");
    }
    DynamicRuleFieldDefinition left = requireField(request.leftSourceKey(), request.leftFieldKey());
    DynamicRuleFieldDefinition right = requireField(request.rightSourceKey(), request.rightFieldKey());
    if (!left.valueType().equals(right.valueType())) {
      throw new BizException("逻辑关联两侧字段值类型不一致");
    }
    String operator = requireKey(request.matchOperator() == null ? "eq" : request.matchOperator(), "逻辑关联关系不能为空");
    if (!MATCH_OPERATORS.contains(operator)) {
      throw new BizException("逻辑关联关系不支持：" + request.matchOperator());
    }
    String normalizer = normalizeNormalizer(request.normalizer());
    return new DynamicRuleRelationDefinition(
        relationName(request.leftSourceKey(), request.leftFieldKey(), request.rightSourceKey(), request.rightFieldKey()),
        requireKey(request.leftSourceKey(), "逻辑关联左侧数据源不能为空"),
        requireKey(request.leftFieldKey(), "逻辑关联左侧字段不能为空"),
        requireKey(request.rightSourceKey(), "逻辑关联右侧数据源不能为空"),
        requireKey(request.rightFieldKey(), "逻辑关联右侧字段不能为空"),
        operator,
        normalizer);
  }

  boolean isRegisteredRelation(DynamicRuleRelationDefinition relation) {
    return registeredRelations.stream().anyMatch(item -> item.sameEndpoint(relation));
  }

  List<String> operatorsFor(String valueType) {
    return switch (valueType) {
      case VALUE_NUMBER -> NUMBER_OPERATORS;
      case VALUE_DATE -> DATE_OPERATORS;
      case VALUE_BOOLEAN -> BOOLEAN_OPERATORS;
      default -> TEXT_OPERATORS;
    };
  }

  void validateOperator(DynamicRuleFieldDefinition field, String operator) {
    String normalized = requireKey(operator, "过滤关系不能为空");
    if (!operatorsFor(field.valueType()).contains(normalized)) {
      throw new BizException("字段不支持该过滤关系：" + field.name() + " / " + operator);
    }
  }

  private Map<String, DynamicRuleSourceDefinition> buildSources() {
    Map<String, DynamicRuleSourceDefinition> sources = new LinkedHashMap<>();
    put(sources, source(
        "review_records",
        "评审记录",
        "评审数据管理统一可见记录（正式记录与未转正式历史快照）",
        "review_visible_records",
        FACT_ACTIVE_COLUMN,
        List.of(
            field("id", "记录ID", "id", VALUE_NUMBER, false, true, true, true),
            candidateField("projectName", "项目", "project_name", VALUE_STRING, true, true, true, false),
            field("title", "标题", "title", VALUE_STRING, true, true, true, false),
            candidateField("moduleName", "模块", "module_name", VALUE_STRING, true, true, true, false),
            candidateField("reviewType", "评审类型", "review_type", VALUE_STRING, true, true, true, false),
            field("reviewDate", "评审日期", "review_date", VALUE_DATE, true, true, true, false),
            candidateField("reviewOwner", "评审负责人", "review_owner", VALUE_STRING, true, true, true, false),
            field("reviewScalePages", "评审规模", "review_scale_pages", VALUE_NUMBER, true, true, true, true),
            candidateField("authorName", "作者", "author_name", VALUE_STRING, true, true, true, false),
            candidateField("reviewVersion", "评审版本", "review_version", VALUE_STRING, true, true, true, false),
            field("updatedAt", "更新时间", "updated_at", VALUE_DATE, false, true, false, false))));
    put(sources, source(
        "review_problem_items",
        "评审问题项",
        "评审数据管理统一可见问题项（正式问题项与未转正式历史快照）",
        "review_visible_problem_items",
        FACT_ACTIVE_COLUMN,
        List.of(
            field("id", "问题项ID", "id", VALUE_NUMBER, false, true, true, true),
            field("reviewRecordId", "评审记录ID", "review_record_id", VALUE_NUMBER, false, true, true, true),
            candidateField("reviewerName", "评审人", "reviewer_name", VALUE_STRING, true, true, true, false),
            field("workloadHours", "工作量", "workload_hours", VALUE_NUMBER, true, true, true, true),
            candidateField("reviewCategory", "评审类别", "review_category", VALUE_STRING, true, true, true, false),
            candidateField("problemCategory", "问题类别", "problem_category", VALUE_STRING, true, true, true, false),
            candidateField("ownerName", "责任人", "owner_name", VALUE_STRING, true, true, true, false),
            candidateField("problemStatus", "问题状态", "problem_status", VALUE_STRING, true, true, true, false),
            field("updatedAt", "更新时间", "updated_at", VALUE_DATE, false, true, false, false))));
    put(sources, source(
        "issue_fact",
        "议题事实",
        "系统测试和客户问题共用议题事实表",
        "issue_fact",
        FACT_ACTIVE_COLUMN,
        List.of(
            field("projectId", "项目ID", "project_id", VALUE_NUMBER, true, true, true, true),
            candidateField("projectName", "项目", "project_name", VALUE_STRING, true, true, true, false),
            field("issueIid", "议题IID", "issue_iid", VALUE_NUMBER, true, true, true, true),
            field("title", "标题", "title", VALUE_STRING, true, true, true, false),
            candidateField("issueState", "状态", "issue_state", VALUE_STRING, true, true, true, false),
            candidateField("milestoneTitle", "里程碑", "milestone_title", VALUE_STRING, true, true, true, false),
            candidateField("authorName", "作者", "author_name", VALUE_STRING, true, true, true, false),
            candidateField("assigneeName", "处理人", "assignee_name", VALUE_STRING, true, true, true, false),
            field("createdAt", "创建时间", "created_at_source", VALUE_DATE, true, true, true, false),
            field("updatedAt", "更新时间", "updated_at_source", VALUE_DATE, true, true, true, false),
            candidateField("moduleName", "模块", "module_name", VALUE_STRING, true, true, true, false),
            staticSeverityField("severityLevel", "严重程度", "severity_level", true, true, true, false),
            staticField("priorityLevel", "优先级", "priority_level", VALUE_STRING, true, true, true, false,
                "P1", "P2", "P3"),
            candidateField("testingPhase", "测试阶段", "testing_phase", VALUE_STRING, true, true, true, false),
            staticField("isIllegal", "是否非法", "is_illegal", VALUE_BOOLEAN, true, true, true, false,
                "true", "false"),
            staticField("delayIssue", "是否延期", "delay_issue", VALUE_BOOLEAN, true, true, true, false,
                "true", "false"))));
    put(sources, source(
        "merge_request_fact",
        "代码走查 MR",
        "代码走查合并请求事实表",
        "merge_request_fact",
        FACT_ACTIVE_COLUMN,
        List.of(
            field("projectId", "项目ID", "project_id", VALUE_NUMBER, true, true, true, true),
            candidateField("projectName", "项目", "project_name", VALUE_STRING, true, true, true, false),
            candidateField("repositoryName", "仓库", "repository_name", VALUE_STRING, true, true, true, false),
            field("mergeRequestIid", "MR IID", "merge_request_iid", VALUE_NUMBER, true, true, true, true),
            field("title", "标题", "title", VALUE_STRING, true, true, true, false),
            candidateField("state", "MR 状态", "merge_request_state", VALUE_STRING, true, true, true, false),
            candidateField("targetBranch", "目标分支", "target_branch", VALUE_STRING, true, true, true, false),
            candidateField("authorName", "作者", "author_name", VALUE_STRING, true, true, true, false),
            candidateField("mergeUserName", "合并人", "merge_user_name", VALUE_STRING, true, true, true, false),
            candidateField("ownerName", "负责人", "owner_name", VALUE_STRING, true, true, true, false),
            candidateField("reviewerNames", "审查人", "reviewer_names", VALUE_STRING, true, true, true, false),
            candidateField("assigneeNames", "指派人", "assignee_names", VALUE_STRING, true, true, true, false),
            candidateField("moduleName", "模块", "module_name", VALUE_STRING, true, true, true, false),
            field("createdAt", "创建时间", "created_at_source", VALUE_DATE, true, true, true, false),
            field("updatedAt", "更新时间", "updated_at_source", VALUE_DATE, true, true, true, false),
            field("mergedAt", "合并时间", "merged_at_source", VALUE_DATE, true, true, true, false),
            field("defectCount", "代码走查缺陷数", "defect_count", VALUE_NUMBER, true, true, true, true),
            field("scanBugCount", "扫描缺陷数", "scan_bug_count", VALUE_NUMBER, true, true, true, true),
            field("addedLines", "新增行数", "added_lines", VALUE_NUMBER, true, true, true, true))));
    put(sources, source(
        "ods_gitlab_issues",
        "GitLab议题镜像",
        "GitLab 原始 issues 镜像表，适合按原始议题字段生成成员",
        "ods_gitlab_issues",
        MIRROR_ACTIVE_COLUMN,
        List.of(
            field("id", "议题ID", "id", VALUE_NUMBER, true, true, true, true),
            field("iid", "议题IID", "iid", VALUE_NUMBER, true, true, true, true),
            field("projectId", "项目ID", "project_id", VALUE_NUMBER, true, true, true, true),
            field("title", "标题", "title", VALUE_STRING, true, true, true, false),
            field("authorId", "作者ID", "author_id", VALUE_NUMBER, true, true, true, true),
            field("stateId", "状态ID", "state_id", VALUE_NUMBER, true, true, true, true),
            field("createdAt", "创建时间", "created_at", VALUE_DATE, true, true, true, false),
            field("updatedAt", "更新时间", "updated_at", VALUE_DATE, true, true, true, false),
            field("closedAt", "关闭时间", "closed_at", VALUE_DATE, true, true, true, false))));
    put(sources, source(
        "ods_gitlab_labels",
        "GitLab标签镜像",
        "GitLab 原始 labels 镜像表，适合生成标签文本成员",
        "ods_gitlab_labels",
        MIRROR_ACTIVE_COLUMN,
        List.of(
            field("id", "标签ID", "id", VALUE_NUMBER, true, true, true, true),
            candidateField("title", "标签名称", "title", VALUE_STRING, true, true, true, false))));
    put(sources, source(
        "ods_gitlab_label_links",
        "GitLab标签关联镜像",
        "GitLab 原始 label_links 镜像表，适合按目标类型和标签关联生成成员",
        "ods_gitlab_label_links",
        MIRROR_ACTIVE_COLUMN,
        List.of(
            field("id", "关联ID", "id", VALUE_NUMBER, true, true, true, true),
            field("labelId", "标签ID", "label_id", VALUE_NUMBER, true, true, true, true),
            field("targetId", "目标ID", "target_id", VALUE_NUMBER, true, true, true, true),
            staticField("targetType", "目标类型", "target_type", VALUE_STRING, true, true, true, false,
                "Issue", "MergeRequest"),
            field("createdAt", "创建时间", "created_at", VALUE_DATE, true, true, true, false),
            field("updatedAt", "更新时间", "updated_at", VALUE_DATE, true, true, true, false))));
    put(sources, source(
        "ods_gitlab_merge_requests",
        "GitLab MR 镜像",
        "GitLab 原始 merge_requests 镜像表，适合按 MR 原始字段生成成员",
        "ods_gitlab_merge_requests",
        MIRROR_ACTIVE_COLUMN,
        List.of(
            field("id", "MR ID", "id", VALUE_NUMBER, true, true, true, true),
            field("iid", "MR IID", "iid", VALUE_NUMBER, true, true, true, true),
            field("targetProjectId", "目标项目ID", "target_project_id", VALUE_NUMBER, true, true, true, true),
            field("title", "标题", "title", VALUE_STRING, true, true, true, false),
            field("authorId", "作者ID", "author_id", VALUE_NUMBER, true, true, true, true),
            field("mergeUserId", "合并人ID", "merge_user_id", VALUE_NUMBER, true, true, true, true),
            candidateField("targetBranch", "目标分支", "target_branch", VALUE_STRING, true, true, true, false),
            candidateField("sourceBranch", "源分支", "source_branch", VALUE_STRING, true, true, true, false),
            field("createdAt", "创建时间", "created_at", VALUE_DATE, true, true, true, false),
            field("updatedAt", "更新时间", "updated_at", VALUE_DATE, true, true, true, false))));
    // 动态规则源顺序是前端 DSL 的稳定展示契约，不能交给 Map.copyOf 的未定义迭代顺序。
    return Collections.unmodifiableMap(sources);
  }

  private List<DynamicRuleRelationDefinition> buildRelations() {
    return List.of(
        relation("评审记录-问题项记录ID", "review_records", "id", "review_problem_items", "reviewRecordId", "eq", "NONE"),
        relation("议题处理人与 MR 负责人", "issue_fact", "assigneeName", "merge_request_fact", "ownerName", "eq", "TRIM_LOWER"),
        relation("议题处理人与 MR 作者", "issue_fact", "assigneeName", "merge_request_fact", "authorName", "eq", "TRIM_LOWER"),
        relation("评审负责人与 MR 负责人", "review_records", "reviewOwner", "merge_request_fact", "ownerName", "eq", "TRIM_LOWER"),
        relation("评审问题责任人与 MR 负责人", "review_problem_items", "ownerName", "merge_request_fact", "ownerName", "eq", "TRIM_LOWER"));
  }

  private DynamicRuleSourceDefinition source(
      String key,
      String name,
      String description,
      String tableName,
      String activeColumnName,
      List<DynamicRuleFieldDefinition> fields) {
    Map<String, DynamicRuleFieldDefinition> fieldByKey = new LinkedHashMap<>();
    for (DynamicRuleFieldDefinition field : fields) {
      fieldByKey.put(field.key(), field);
    }
    return new DynamicRuleSourceDefinition(
        key,
        name,
        description,
        tableName,
        activeColumnName,
        Collections.unmodifiableMap(fieldByKey));
  }

  private DynamicRuleFieldDefinition field(
      String key,
      String name,
      String columnName,
      String valueType,
      boolean outputSupported,
      boolean filterSupported,
      boolean groupSupported,
      boolean aggregateSupported) {
    return new DynamicRuleFieldDefinition(
        key,
        name,
        columnName,
        valueType,
        outputSupported,
        filterSupported,
        groupSupported,
        aggregateSupported,
        CANDIDATE_NONE,
        List.of());
  }

  private DynamicRuleFieldDefinition candidateField(
      String key,
      String name,
      String columnName,
      String valueType,
      boolean outputSupported,
      boolean filterSupported,
      boolean groupSupported,
      boolean aggregateSupported) {
    return new DynamicRuleFieldDefinition(
        key,
        name,
        columnName,
        valueType,
        outputSupported,
        filterSupported,
        groupSupported,
        aggregateSupported,
        CANDIDATE_DISTINCT,
        List.of());
  }

  private DynamicRuleFieldDefinition staticField(
      String key,
      String name,
      String columnName,
      String valueType,
      boolean outputSupported,
      boolean filterSupported,
      boolean groupSupported,
      boolean aggregateSupported,
      String... values) {
    return new DynamicRuleFieldDefinition(
        key,
        name,
        columnName,
        valueType,
        outputSupported,
        filterSupported,
        groupSupported,
        aggregateSupported,
        CANDIDATE_STATIC,
        java.util.Arrays.stream(values).map(value -> new OptionItemResponse(value, value)).toList());
  }

  private DynamicRuleFieldDefinition staticSeverityField(
      String key,
      String name,
      String columnName,
      boolean outputSupported,
      boolean filterSupported,
      boolean groupSupported,
      boolean aggregateSupported) {
    return new DynamicRuleFieldDefinition(
        key,
        name,
        columnName,
        VALUE_STRING,
        outputSupported,
        filterSupported,
        groupSupported,
        aggregateSupported,
        CANDIDATE_STATIC,
        IssueDisplayValueSupport.severityLevelOptions(true));
  }

  private DynamicRuleRelationDefinition relation(
      String name,
      String leftSourceKey,
      String leftFieldKey,
      String rightSourceKey,
      String rightFieldKey,
      String matchOperator,
      String normalizer) {
    return new DynamicRuleRelationDefinition(
        name, leftSourceKey, leftFieldKey, rightSourceKey, rightFieldKey, matchOperator, normalizer);
  }

  private void put(Map<String, DynamicRuleSourceDefinition> sources, DynamicRuleSourceDefinition source) {
    sources.put(source.key(), source);
  }

  private String normalizeNormalizer(String value) {
    String normalizer = TextQuerySupport.trimToNull(value);
    normalizer = normalizer == null ? "NONE" : normalizer.toUpperCase(Locale.ROOT);
    if (!NORMALIZERS.contains(normalizer)) {
      throw new BizException("逻辑关联规范化方式不支持：" + value);
    }
    return normalizer;
  }

  private String requireKey(String value, String message) {
    String key = TextQuerySupport.trimToNull(value);
    if (key == null) {
      throw new BizException(message);
    }
    return key;
  }

  private String relationName(String leftSourceKey, String leftFieldKey, String rightSourceKey, String rightFieldKey) {
    return leftSourceKey + "." + leftFieldKey + " = " + rightSourceKey + "." + rightFieldKey;
  }

  public record DynamicRuleSourceDefinition(
      String key,
      String name,
      String description,
      String tableName,
      String activeColumnName,
      Map<String, DynamicRuleFieldDefinition> fields) {}

  public record DynamicRuleFieldDefinition(
      String key,
      String name,
      String columnName,
      String valueType,
      boolean outputSupported,
      boolean filterSupported,
      boolean groupSupported,
      boolean aggregateSupported,
      String candidateMode,
      List<OptionItemResponse> staticCandidates) {}

  record DynamicRuleRelationDefinition(
      String name,
      String leftSourceKey,
      String leftFieldKey,
      String rightSourceKey,
      String rightFieldKey,
      String matchOperator,
      String normalizer) {
    boolean sameEndpoint(DynamicRuleRelationDefinition other) {
      return matchOperator.equals(other.matchOperator)
          && normalizer.equals(other.normalizer)
          && ((leftSourceKey.equals(other.leftSourceKey)
                  && leftFieldKey.equals(other.leftFieldKey)
                  && rightSourceKey.equals(other.rightSourceKey)
                  && rightFieldKey.equals(other.rightFieldKey))
              || (leftSourceKey.equals(other.rightSourceKey)
                  && leftFieldKey.equals(other.rightFieldKey)
                  && rightSourceKey.equals(other.leftSourceKey)
                  && rightFieldKey.equals(other.leftFieldKey)));
    }
  }
}
