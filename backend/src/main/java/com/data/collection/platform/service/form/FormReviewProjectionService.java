package com.data.collection.platform.service.form;

import com.data.collection.platform.domain.form.FormFieldSpec;
import com.data.collection.platform.domain.form.FormTemplateSpec;
import com.data.collection.platform.entity.MergeRequestFact;
import com.data.collection.platform.service.form.FormCommonFieldService.FieldContract;
import com.data.collection.platform.service.form.FormCommonFieldService.RecordValue;
import com.data.collection.platform.service.form.FormCommonFieldService.Selection;
import com.data.collection.platform.service.form.FormCommonFieldService.VersionInput;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * MR 逐记录走查投影：按持久 FORM 权威与目录读取有效记录，汇总到 MR 事实并保留逐记录行。
 *
 * <p>只读取 {@code AUTHORITATIVE} 且已发布/退役版本，作废记录由目录排除；同一记录跨版本各一条。
 * 字段参与与否由共同字段选择决定：未声明、冻结类型或声明冲突、域无法解析的字段显式不参与，
 * 不再按固定 apiKey 或可解析数字文本判定可比。人员按稳定身份去重，缺字段/NULL 不补 0，0 保留。
 */
@Service
public class FormReviewProjectionService {
  private static final String TEMPLATE_KEY = "code_review";
  private static final String RESOURCE_TYPE = "merge_request";
  private static final String REVIEWER_SEMANTIC_KEY = "code_review.reviewer_user_id";
  private static final String REVIEW_DURATION_SEMANTIC_KEY =
      "code_review.review_duration_minutes";
  private static final String SPECIFICATION_SEMANTIC_KEY = "code_review.specification_count";
  private static final String LOGIC_SEMANTIC_KEY = "code_review.logic_count";
  private static final String PERFORMANCE_SEMANTIC_KEY = "code_review.performance_count";
  private static final String DESIGN_SEMANTIC_KEY = "code_review.design_count";
  private static final String OTHER_SEMANTIC_KEY = "code_review.other_count";

  private final JdbcTemplate jdbcTemplate;
  private final FormRecordDirectoryReader reader;
  private final FormCommonFieldService commonFieldService;
  private final FormComparableRegistrationService registrationService;
  private final FormRecordContextService contextService;

  public FormReviewProjectionService(
      JdbcTemplate jdbcTemplate,
      FormRecordDirectoryReader reader,
      FormCommonFieldService commonFieldService,
      FormComparableRegistrationService registrationService,
      FormRecordContextService contextService) {
    this.jdbcTemplate = jdbcTemplate;
    this.reader = reader;
    this.commonFieldService = commonFieldService;
    this.registrationService = registrationService;
    this.contextService = contextService;
  }

  /** 单条参与统计的走查记录。 */
  public record RecordRow(
      long projectId,
      long mergeRequestId,
      Long mergeRequestIid,
      long recordId,
      long scopeId,
      long storageVersionId,
      String recordSourceRole,
      String reviewerUserId,
      String reviewerName,
      Integer reviewDurationMinutes,
      Integer specificationCount,
      Integer logicCount,
      Integer performanceCount,
      Integer designCount,
      Integer otherCount,
      LocalDateTime recordCreatedAt) {}

  /** 同一 MR 下有效记录的聚合；即使记录为空也保留条目，表示 FORM 权威下不得回退旧值。 */
  public record Aggregate(
      Integer reviewDurationMinutes,
      Integer specificationCount,
      Integer logicCount,
      Integer performanceCount,
      Integer designCount,
      Integer otherCount,
      Integer defectCount,
      String reviewerNames,
      LocalDateTime codeWalkthroughDate,
      int recordCount) {

    boolean empty() {
      return recordCount == 0;
    }
  }

  /** 一次加载结果：按 MR 根 ID 的聚合与待写入投影行。 */
  public record Projection(Map<Long, Aggregate> aggregates, List<RecordRow> records) {
    public static Projection empty() {
      return new Projection(Map.of(), List.of());
    }
  }

  /**
   * 读取本批 MR 中已登记 FORM 权威的有效走查记录并计算逐 MR 聚合。
   *
   * @param sourceInstance 来源实例
   * @param facts 本批 MR 事实；无项目或 IID 的行跳过
   * @return 聚合与逐记录行；无 FORM 权威时为空
   */
  public Projection load(String sourceInstance, List<MergeRequestFact> facts) {
    if (facts == null || facts.isEmpty()) {
      return Projection.empty();
    }
    Map<Long, Map<String, MergeRequestFact>> byProject = new LinkedHashMap<>();
    for (MergeRequestFact fact : facts) {
      if (fact.getProjectId() == null || fact.getMergeRequestIid() == null) {
        continue;
      }
      byProject
          .computeIfAbsent(fact.getProjectId(), ignored -> new LinkedHashMap<>())
          .putIfAbsent(String.valueOf(fact.getMergeRequestIid()), fact);
    }
    Map<Long, Aggregate> aggregates = new LinkedHashMap<>();
    List<RecordRow> rows = new ArrayList<>();
    Map<Long, VersionMeta> metaCache = new LinkedHashMap<>();
    for (Map.Entry<Long, Map<String, MergeRequestFact>> project : byProject.entrySet()) {
      long projectId = project.getKey();
      Map<String, MergeRequestFact> factsByIid = project.getValue();
      List<Map<String, Object>> authorities =
          loadAuthorities(sourceInstance, projectId, factsByIid.keySet());
      Map<Long, List<String>> iidsByScope = new LinkedHashMap<>();
      for (Map<String, Object> authority : authorities) {
        iidsByScope
            .computeIfAbsent(((Number) authority.get("scope_id")).longValue(), ignored -> new ArrayList<>())
            .add(String.valueOf(((Number) authority.get("resource_id")).longValue()));
      }
      for (Map.Entry<Long, List<String>> scope : iidsByScope.entrySet()) {
        long scopeId = scope.getKey();
        Map<String, List<FormRecordDirectoryReader.LocatedRecord>> recordsByIid =
            new LinkedHashMap<>();
        for (FormRecordDirectoryReader.LocatedRecord record :
            reader.listByResources(scopeId, RESOURCE_TYPE, scope.getValue(), projectId)) {
          recordsByIid.computeIfAbsent(record.resourceId(), ignored -> new ArrayList<>()).add(record);
        }
        for (String iid : scope.getValue()) {
          MergeRequestFact fact = factsByIid.get(iid);
          if (fact == null) {
            continue;
          }
          List<FormRecordDirectoryReader.LocatedRecord> located =
              recordsByIid.getOrDefault(iid, List.of());
          Selection selection = selectForRecords(scopeId, located, metaCache);
          List<RecordRow> recordRows = new ArrayList<>(located.size());
          for (FormRecordDirectoryReader.LocatedRecord record : located) {
            recordRows.add(toRow(fact, record, selection, metaCache));
          }
          rows.addAll(recordRows);
          aggregates.put(fact.getMergeRequestId(), aggregate(recordRows));
        }
      }
    }
    return new Projection(Map.copyOf(aggregates), List.copyOf(rows));
  }

  /**
   * 把 FORM 权威聚合覆盖到 MR 事实；未登记 FORM 的 MR 保持来源 SQL 结果。
   *
   * <p>五类问题数、时长、人员与走查日期均来自同一批有效记录；密度按本 MR 去重后的新增行计算，
   * 新增行缺失或不为正时显式为空，不造 0。
   */
  public void apply(List<MergeRequestFact> facts, Projection projection) {
    if (facts == null || facts.isEmpty() || projection == null || projection.aggregates().isEmpty()) {
      return;
    }
    for (MergeRequestFact fact : facts) {
      Aggregate aggregate = projection.aggregates().get(fact.getMergeRequestId());
      if (aggregate == null) {
        continue;
      }
      fact.setReviewDurationMinutes(aggregate.reviewDurationMinutes());
      fact.setCodeSpecificationCount(aggregate.specificationCount());
      fact.setCodeLogicSpecificationCount(aggregate.logicCount());
      fact.setPerformanceSpecificationCount(aggregate.performanceCount());
      fact.setDesignSpecificationCount(aggregate.designCount());
      fact.setOtherSpecificationCount(aggregate.otherCount());
      fact.setDefectCount(aggregate.defectCount());
      fact.setDefectCountSource(aggregate.defectCount() == null ? null : "FORM");
      fact.setReviewDefectDensityPerKloc(density(aggregate.defectCount(), fact.getAddedLines()));
      fact.setReviewerNames(aggregate.reviewerNames() == null ? "" : aggregate.reviewerNames());
      fact.setCodeWalkthroughDate(aggregate.codeWalkthroughDate());
      boolean noNeedReview =
          "无需走查".equals(fact.getReviewerNames())
              || "无需走查扫描".equals(fact.getReviewerNames());
      fact.setReviewStatus(
          aggregate.reviewDurationMinutes() == null && !noNeedReview ? "PENDING" : "COMPLETED");
    }
  }

  private Selection selectForRecords(
      long scopeId,
      List<FormRecordDirectoryReader.LocatedRecord> located,
      Map<Long, VersionMeta> metaCache) {
    if (located.isEmpty()) {
      return new Selection(
          "NO_COMMON_FIELDS", List.of(), List.of(), List.of(), List.of(), List.of(), Map.of());
    }
    Map<Long, List<RecordValue>> recordsByVersion = new LinkedHashMap<>();
    for (FormRecordDirectoryReader.LocatedRecord record : located) {
      recordsByVersion
          .computeIfAbsent(record.storageVersionId(), ignored -> new ArrayList<>())
          .add(
              new RecordValue(
                  record.recordId(), record.storageVersionId(), record.deleted(), record.fields()));
    }
    List<VersionInput> inputs = new ArrayList<>(recordsByVersion.size());
    for (Map.Entry<Long, List<RecordValue>> version : recordsByVersion.entrySet()) {
      VersionMeta meta = metaCache.computeIfAbsent(version.getKey(), this::loadMeta);
      inputs.add(
          new VersionInput(
              version.getKey(),
              scopeId,
              TEMPLATE_KEY,
              meta.spec(),
              meta.declarations(),
              List.copyOf(version.getValue())));
    }
    return commonFieldService.select(inputs);
  }

  private VersionMeta loadMeta(long versionId) {
    List<Map<String, Object>> rows =
        jdbcTemplate.queryForList(
            "select v.field_spec::text as field_spec, s.template_key"
                + " from form_template_versions v join form_template_scopes s on s.id = v.scope_id"
                + " where v.id = ?",
            versionId);
    if (rows.isEmpty() || rows.getFirst().get("field_spec") == null) {
      throw new IllegalStateException("表单版本缺少冻结字段规格：" + versionId);
    }
    Map<String, Object> row = rows.getFirst();
    FormTemplateSpec spec =
        contextService.parseSpec(
            (String) row.get("field_spec"), (String) row.get("template_key"));
    Map<String, FormFieldSpec> fields = new LinkedHashMap<>();
    for (FormFieldSpec field : spec.fields()) {
      fields.put(field.name(), field);
    }
    return new VersionMeta(
        spec, Map.copyOf(fields), registrationService.declarationsForVersion(versionId));
  }

  private RecordRow toRow(
      MergeRequestFact fact,
      FormRecordDirectoryReader.LocatedRecord record,
      Selection selection,
      Map<Long, VersionMeta> metaCache) {
    VersionMeta meta = metaCache.computeIfAbsent(record.storageVersionId(), this::loadMeta);
    Map<String, FieldContract> contracts = contractsOf(selection, record.storageVersionId());
    FormFieldSpec reviewer = fieldOf(meta, contracts.get(REVIEWER_SEMANTIC_KEY));
    String reviewerId = text(record.fields(), reviewer == null ? null : column(reviewer, true));
    String reviewerName = text(record.fields(), reviewer == null ? null : column(reviewer, false));
    if (reviewerId == null) {
      reviewerId = reviewerName;
    }
    if (reviewerName == null) {
      reviewerName = reviewerId;
    }
    return new RecordRow(
        fact.getProjectId(),
        fact.getMergeRequestId(),
        fact.getMergeRequestIid(),
        record.recordId(),
        record.scopeId(),
        record.storageVersionId(),
        record.recordSourceRole(),
        reviewerId,
        reviewerName,
        integer(record.fields(), fieldOf(meta, contracts.get(REVIEW_DURATION_SEMANTIC_KEY)),
            contracts.get(REVIEW_DURATION_SEMANTIC_KEY)),
        integer(record.fields(), fieldOf(meta, contracts.get(SPECIFICATION_SEMANTIC_KEY)),
            contracts.get(SPECIFICATION_SEMANTIC_KEY)),
        integer(record.fields(), fieldOf(meta, contracts.get(LOGIC_SEMANTIC_KEY)),
            contracts.get(LOGIC_SEMANTIC_KEY)),
        integer(record.fields(), fieldOf(meta, contracts.get(PERFORMANCE_SEMANTIC_KEY)),
            contracts.get(PERFORMANCE_SEMANTIC_KEY)),
        integer(record.fields(), fieldOf(meta, contracts.get(DESIGN_SEMANTIC_KEY)),
            contracts.get(DESIGN_SEMANTIC_KEY)),
        integer(record.fields(), fieldOf(meta, contracts.get(OTHER_SEMANTIC_KEY)),
            contracts.get(OTHER_SEMANTIC_KEY)),
        record.createdAt());
  }

  private Map<String, FieldContract> contractsOf(Selection selection, long versionId) {
    Map<String, FieldContract> bySemanticKey = new LinkedHashMap<>();
    List<FieldContract> contracts = selection.perVersion().get(versionId);
    if (contracts == null) {
      return bySemanticKey;
    }
    for (FieldContract contract : contracts) {
      bySemanticKey.putIfAbsent(contract.semanticKey(), contract);
    }
    return bySemanticKey;
  }

  private FormFieldSpec fieldOf(VersionMeta meta, FieldContract contract) {
    return contract == null ? null : meta.fields().get(contract.name());
  }

  private Aggregate aggregate(List<RecordRow> rows) {
    Integer duration = sum(rows.stream().map(RecordRow::reviewDurationMinutes).toList());
    Integer specification = sum(rows.stream().map(RecordRow::specificationCount).toList());
    Integer logic = sum(rows.stream().map(RecordRow::logicCount).toList());
    Integer performance = sum(rows.stream().map(RecordRow::performanceCount).toList());
    Integer design = sum(rows.stream().map(RecordRow::designCount).toList());
    Integer other = sum(rows.stream().map(RecordRow::otherCount).toList());
    Integer defect = sum(rows.stream().map(FormReviewProjectionService::defectOf).toList());
    LinkedHashMap<String, String> reviewers = new LinkedHashMap<>();
    LocalDateTime walkthroughDate = null;
    for (RecordRow row : rows) {
      String key = row.reviewerUserId() == null ? row.reviewerName() : row.reviewerUserId();
      if (key != null) {
        reviewers.putIfAbsent(key, row.reviewerName() == null ? key : row.reviewerName());
      }
      if (row.recordCreatedAt() != null
          && (walkthroughDate == null || row.recordCreatedAt().isAfter(walkthroughDate))) {
        walkthroughDate = row.recordCreatedAt();
      }
    }
    return new Aggregate(
        duration,
        specification,
        logic,
        performance,
        design,
        other,
        defect,
        reviewers.isEmpty() ? "" : String.join(", ", reviewers.values()),
        walkthroughDate,
        rows.size());
  }

  /** 单条记录缺陷数：只累加实际存在且参与共同字段的类别；全部缺失时为空，0 保留。 */
  private static Integer defectOf(RecordRow row) {
    List<Integer> values = new ArrayList<>(5);
    addIfPresent(values, row.specificationCount());
    addIfPresent(values, row.logicCount());
    addIfPresent(values, row.performanceCount());
    addIfPresent(values, row.designCount());
    addIfPresent(values, row.otherCount());
    if (values.isEmpty()) {
      return null;
    }
    int total = 0;
    for (Integer value : values) {
      total += value;
    }
    return total;
  }

  private static void addIfPresent(List<Integer> values, Integer value) {
    if (value != null) {
      values.add(value);
    }
  }

  private List<Map<String, Object>> loadAuthorities(
      String sourceInstance, long projectId, java.util.Collection<String> iids) {
    List<String> ids = iids.stream().filter(Objects::nonNull).distinct().toList();
    if (ids.isEmpty()) {
      return List.of();
    }
    String placeholders = String.join(", ", java.util.Collections.nCopies(ids.size(), "?"));
    List<Object> args = new ArrayList<>(ids.size() + 3);
    args.add(sourceInstance);
    args.add(projectId);
    args.addAll(ids.stream().map(Long::valueOf).toList());
    return jdbcTemplate.queryForList(
        """
        select scope_id, resource_id
          from form_resource_authorities
         where source_instance = ? and resource_type = 'merge_request'
           and template_key = 'code_review' and authority = 'FORM'
           and project_id = ? and resource_id in (%s)
         order by resource_id
        """
            .formatted(placeholders),
        args.toArray());
  }

  private static String column(FormFieldSpec field, boolean idColumn) {
    if (field.storage() == null) {
      return null;
    }
    return idColumn ? field.storage().resolvedColumn() : field.storage().nameColumn();
  }

  private static String text(Map<String, Object> fields, String column) {
    if (column == null) {
      return null;
    }
    Object raw = fields.get(column);
    if (raw == null) {
      return null;
    }
    String value = String.valueOf(raw).trim();
    return value.isEmpty() ? null : value;
  }

  /** 只接受共同字段确认的整数合同与真实数值，不把可解析文本当作可比整数。 */
  private static Integer integer(
      Map<String, Object> fields, FormFieldSpec field, FieldContract contract) {
    if (field == null
        || contract == null
        || !"integer".equals(contract.valueType())
        || field.storage() == null) {
      return null;
    }
    Object raw = fields.get(field.storage().resolvedColumn());
    if (raw instanceof Number number) {
      return number.intValue();
    }
    return null;
  }

  private static Integer sum(List<Integer> values) {
    Integer total = null;
    for (Integer value : values) {
      if (value != null) {
        total = (total == null ? 0 : total) + value;
      }
    }
    return total;
  }

  private static BigDecimal density(Integer defectCount, Integer addedLines) {
    if (defectCount == null || addedLines == null || addedLines <= 0) {
      return null;
    }
    return BigDecimal.valueOf(defectCount.longValue())
        .multiply(BigDecimal.valueOf(1000L))
        .divide(BigDecimal.valueOf(addedLines), 2, RoundingMode.HALF_UP);
  }

  private record VersionMeta(
      FormTemplateSpec spec,
      Map<String, FormFieldSpec> fields,
      Map<String, FormComparableRegistrationService.FieldDeclaration> declarations) {}
}
