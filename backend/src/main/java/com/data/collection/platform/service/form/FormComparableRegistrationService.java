package com.data.collection.platform.service.form;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.common.exception.FormConflictException;
import com.data.collection.platform.common.response.ResultCode;
import com.data.collection.platform.domain.form.FormFieldSpec;
import com.data.collection.platform.domain.form.FormTemplateSpec;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 稳定语义登记（N0 IV-01 执行位置）：stableId/semanticKey/声明类型的一次性登记，
 * 绑定 scope+version+字段；实际可比类型取该版冻结 {@code field_spec} 的 {@code field.type}。
 *
 * <p>声明与实际类型冲突（{@code TYPE_DECLARATION_CONFLICT}）、未知类型、重复身份或
 * 语义键一律拒绝；同参数重入幂等，不同参数 409。本登记不改写 {@code field_spec}，
 * 也不向管理员暴露类型码以外的技术输入。
 */
@Service
public class FormComparableRegistrationService {

  private static final Set<String> KNOWN_TYPES =
      Set.of("string", "integer", "enum", "enum-array", "date", "boolean");

  private final JdbcTemplate jdbcTemplate;
  private final FormRecordContextService contextService;
  private final ObjectMapper objectMapper;

  public FormComparableRegistrationService(
      JdbcTemplate jdbcTemplate,
      FormRecordContextService contextService,
      ObjectMapper objectMapper) {
    this.jdbcTemplate = jdbcTemplate;
    this.contextService = contextService;
    this.objectMapper = objectMapper;
  }

  /** 一次登记声明；declaredType 必须等于该版冻结字段真实 type。 */
  public record FieldDeclaration(
      String fieldName, String stableId, String semanticKey, String declaredType,
      String unit, String meaning) {}

  public record RegistrationResult(int registered, int unchanged, String status) {}

  /** 读取某版本已登记的可比声明；无登记返回空表。 */
  public Map<String, FieldDeclaration> declarationsForVersion(long versionId) {
    List<Map<String, Object>> rows = jdbcTemplate.queryForList(
        """
        select field_name, stable_id, semantic_key, declared_type, unit, meaning
          from form_field_comparable_registrations where version_id = ? order by id
        """,
        versionId);
    Map<String, FieldDeclaration> result = new LinkedHashMap<>();
    for (Map<String, Object> row : rows) {
      result.put(
          (String) row.get("field_name"),
          new FieldDeclaration(
              (String) row.get("field_name"),
              (String) row.get("stable_id"),
              (String) row.get("semantic_key"),
              (String) row.get("declared_type"),
              (String) row.get("unit"),
              (String) row.get("meaning")));
    }
    return result;
  }

  /**
   * 登记一批字段的可比声明；单事务，任一冲突整体拒绝。
   *
   * @return REGISTERED（有新增）/ UNCHANGED（全部与既有登记一致）
   */
  @Transactional
  public RegistrationResult register(
      long scopeId, long versionId, List<FieldDeclaration> declarations, String operatorId) {
    if (declarations == null || declarations.isEmpty()) {
      throw new BizException(ResultCode.BAD_REQUEST, "可比登记声明不能为空");
    }
    List<Map<String, Object>> versions = jdbcTemplate.queryForList(
        "select v.scope_id, v.field_spec::text as field_spec, s.template_key"
            + " from form_template_versions v join form_template_scopes s on s.id = v.scope_id"
            + " where v.id = ?",
        versionId);
    if (versions.isEmpty()) {
      throw new BizException(ResultCode.BAD_REQUEST, "结构版本不存在");
    }
    long actualScopeId = ((Number) versions.getFirst().get("scope_id")).longValue();
    if (actualScopeId != scopeId) {
      throw new BizException(ResultCode.BAD_REQUEST, "版本不属于指定范围");
    }
    FormTemplateSpec spec = contextService.parseSpec(
        (String) versions.getFirst().get("field_spec"),
        (String) versions.getFirst().get("template_key"));
    Map<String, FormFieldSpec> fieldsByName = new LinkedHashMap<>();
    for (FormFieldSpec field : spec.fields()) {
      if (fieldsByName.put(field.name(), field) != null) {
        throw new FormConflictException("规格内字段名重复：" + field.name());
      }
    }
    int inserted = 0;
    int unchanged = 0;
    Set<String> seenStableIds = new java.util.HashSet<>();
    Set<String> seenSemanticKeys = new java.util.HashSet<>();
    for (FieldDeclaration declaration : declarations) {
      FormFieldSpec field = fieldsByName.get(declaration.fieldName());
      if (field == null) {
        throw new BizException(ResultCode.BAD_REQUEST,
            "声明字段不在冻结规格中：" + declaration.fieldName());
      }
      if (!KNOWN_TYPES.contains(declaration.declaredType())) {
        throw new FormConflictException("未知声明类型：" + declaration.declaredType());
      }
      if (!declaration.declaredType().equals(field.type())) {
        throw new FormConflictException("声明与冻结类型冲突（TYPE_DECLARATION_CONFLICT）：字段="
            + declaration.fieldName() + ", declared=" + declaration.declaredType()
            + ", actual=" + field.type());
      }
      if (!seenStableIds.add(declaration.stableId())
          || !seenSemanticKeys.add(declaration.semanticKey())) {
        throw new FormConflictException("批内重复 stableId 或 semanticKey：" + declaration.fieldName());
      }
      List<Map<String, Object>> existing = jdbcTemplate.queryForList(
          """
          select stable_id, semantic_key, declared_type, unit, meaning
            from form_field_comparable_registrations where version_id = ? and field_name = ?
          """,
          versionId, declaration.fieldName());
      if (!existing.isEmpty()) {
        Map<String, Object> row = existing.getFirst();
        boolean same =
            declaration.stableId().equals(row.get("stable_id"))
                && declaration.semanticKey().equals(row.get("semantic_key"))
                && declaration.declaredType().equals(row.get("declared_type"))
                && java.util.Objects.equals(declaration.unit(), row.get("unit"))
                && declaration.meaning().equals(row.get("meaning"));
        if (!same) {
          throw new FormConflictException("字段已有不同登记，拒绝覆盖：" + declaration.fieldName());
        }
        unchanged++;
        continue;
      }
      int conflicts = jdbcTemplate.queryForObject(
          """
          select (select count(*) from form_field_comparable_registrations
                   where version_id = ? and stable_id = ?)
               + (select count(*) from form_field_comparable_registrations
                   where version_id = ? and semantic_key = ?)
          """,
          Integer.class, versionId, declaration.stableId(), versionId, declaration.semanticKey());
      if (conflicts > 0) {
        throw new FormConflictException("stableId 或 semanticKey 已绑定其它字段："
            + declaration.fieldName());
      }
      jdbcTemplate.update(
          """
          insert into form_field_comparable_registrations(
              scope_id, version_id, field_name, stable_id, semantic_key, declared_type, unit,
              meaning, registered_by)
          values (?, ?, ?, ?, ?, ?, ?, ?, ?)
          """,
          scopeId, versionId, declaration.fieldName(), declaration.stableId(),
          declaration.semanticKey(), declaration.declaredType(), declaration.unit(),
          declaration.meaning(), operatorId);
      inserted++;
    }
    return new RegistrationResult(inserted, unchanged,
        inserted > 0 ? "REGISTERED" : "UNCHANGED");
  }
}
