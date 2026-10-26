package com.data.collection.platform.service.form;

import com.data.collection.platform.domain.form.FormTemplateSpec;
import com.data.collection.platform.domain.form.FormVersionSchema;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 启动时核对本地表单元数据与真实物理结构。
 *
 * <p>只验证本地已发布版本；空平台没有范围时直接通过。列缺失、类型/长度不一致、必填约束数量不足
 * 或 field_spec 与代码资源不一致时拒绝启动，避免带着漂移结构提供写入。
 */
@Component
public class FormSchemaStartupValidator implements ApplicationRunner {

  private final JdbcTemplate jdbcTemplate;
  private final FormVersionSchemaService schemaService;
  private final FormScopeValidationService scopeValidationService;
  private final FormVersionStructureVerifier structureVerifier;
  private final ObjectMapper objectMapper;

  public FormSchemaStartupValidator(
      JdbcTemplate jdbcTemplate,
      FormVersionSchemaService schemaService,
      FormScopeValidationService scopeValidationService,
      FormVersionStructureVerifier structureVerifier,
      ObjectMapper objectMapper) {
    this.jdbcTemplate = jdbcTemplate;
    this.schemaService = schemaService;
    this.scopeValidationService = scopeValidationService;
    this.structureVerifier = structureVerifier;
    this.objectMapper = objectMapper;
  }

  @Override
  public void run(ApplicationArguments args) {
    validate();
  }

  public void validate() {
    requireSequence();
    List<VersionRow> versions =
        jdbcTemplate.query(
            """
            select v.id, v.scope_id, v.version_label, v.storage_kind, v.instance_table,
                   v.record_table, v.field_spec::text as field_spec, v.migration_spec::text as migration_spec,
                   v.version_status, s.template_key
              from form_template_versions v
              join form_template_scopes s on s.id = v.scope_id
             where v.version_status = 'PUBLISHED'
             order by v.scope_id, v.id
            """,
            (rs, rowNum) ->
                new VersionRow(
                    rs.getLong("id"),
                    rs.getLong("scope_id"),
                    rs.getString("template_key"),
                    rs.getString("storage_kind"),
                    rs.getString("instance_table"),
                    rs.getString("record_table"),
                    rs.getString("field_spec"),
                    rs.getString("migration_spec"),
                    rs.getString("version_label")));
    for (VersionRow version : versions) {
      validateVersion(version);
    }
    scopeValidationService.validateActiveScopes();
  }

  private void requireSequence() {
    String sequence =
        jdbcTemplate.queryForObject("select to_regclass(?)", String.class, "form_record_id_seq");
    if (sequence == null) {
      throw new IllegalStateException("缺少共享记录序列 form_record_id_seq");
    }
  }

  private void validateVersion(VersionRow version) {
    FormTemplateSpec spec;
    try {
      JsonNode fieldSpec = objectMapper.readTree(version.fieldSpec());
      if (fieldSpec.path("spec_version").asInt() != 1) {
        throw new IllegalStateException("版本 " + version.id() + " 的 field_spec 版本不受支持");
      }
      spec = objectMapper.treeToValue(fieldSpec, FormTemplateSpec.class);
    } catch (java.io.IOException error) {
      throw new IllegalStateException("版本 " + version.id() + " 的 field_spec 不是合法 JSON", error);
    }
    if (!version.templateKey().equals(spec.templateKey())) {
      throw new IllegalStateException("版本 " + version.id() + " 的 field_spec 模板键不一致");
    }
    if (!"RECORDS".equals(version.storageKind())) {
      if (version.recordTable() != null) {
        throw new IllegalStateException("LEGACY 版本不应有记录表: " + version.id());
      }
      return;
    }
    if (version.versionLabel() == null || version.versionLabel().isBlank()) {
      throw new IllegalStateException("已发布 RECORDS 版本缺少发布标签: " + version.id());
    }
    boolean migrationTarget = version.migrationSpec() != null;
    FormVersionSchema expected =
        schemaService.generate(version.scopeId(), version.id(), spec, migrationTarget);
    if (!expected.instanceTable().equals(version.instanceTable())
        || !expected.recordTable().equals(version.recordTable())) {
      throw new IllegalStateException("版本 " + version.id() + " 的物理表名与元数据不一致");
    }
    structureVerifier.verify(expected);
  }

  private record VersionRow(
      long id,
      long scopeId,
      String templateKey,
      String storageKind,
      String instanceTable,
      String recordTable,
      String fieldSpec,
      String migrationSpec,
      String versionLabel) {}
}
