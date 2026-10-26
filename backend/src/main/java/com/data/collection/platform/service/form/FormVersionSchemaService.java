package com.data.collection.platform.service.form;

import com.data.collection.platform.domain.form.FormFieldOption;
import com.data.collection.platform.domain.form.FormFieldSpec;
import com.data.collection.platform.domain.form.FormHashSupport;
import com.data.collection.platform.domain.form.FormTemplateSpec;
import com.data.collection.platform.domain.form.FormVersionColumn;
import com.data.collection.platform.domain.form.FormVersionConstraint;
import com.data.collection.platform.domain.form.FormVersionIndex;
import com.data.collection.platform.domain.form.FormVersionSchema;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 按冻结 field_spec 生成版本实例表/记录表。
 *
 * <p>无历史行时输出即时 NOT NULL/CHECK；迁移目标统一输出 {@code deleted or legacy_incomplete or ...}
 * 豁免，不使用 NOT VALID。所有物理名由服务端生成，业务值只通过列类型与常量约束进入 DDL。
 */
@Service
public class FormVersionSchemaService {

  private final ObjectMapper objectMapper;

  public FormVersionSchemaService(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public FormVersionSchema generate(
      long scopeId, long versionId, FormTemplateSpec spec, boolean migrationTarget) {
    if (scopeId <= 0 || versionId <= 0) {
      throw new IllegalArgumentException("scopeId and versionId must be positive");
    }
    String instanceTable = "form_s" + scopeId + "_v" + versionId + "_instances";
    String recordTable = "form_s" + scopeId + "_v" + versionId + "_records";
    if (instanceTable.length() > 63 || recordTable.length() > 63) {
      throw new IllegalArgumentException("Generated form table name exceeds 63 bytes");
    }

    List<FormVersionColumn> columns = new ArrayList<>();
    List<String> generatedChecks = new ArrayList<>();
    List<String> recordDefinitions = new ArrayList<>();
    appendInstanceColumns(columns, instanceTable);
    appendSystemRecordColumns(recordDefinitions, columns, recordTable);
    appendBusinessColumns(
        recordDefinitions, columns, generatedChecks, recordTable, spec, migrationTarget);
    appendSnapshotColumns(
        recordDefinitions, columns, generatedChecks, recordTable, spec, migrationTarget);
    appendConditionConstraints(generatedChecks, spec, migrationTarget);

    List<FormVersionConstraint> constraints =
        recordConstraints(scopeId, versionId, recordTable, instanceTable, generatedChecks);
    List<FormVersionConstraint> instanceConstraints =
        instanceConstraints(scopeId, versionId, instanceTable);
    List<FormVersionConstraint> allConstraints = new ArrayList<>(constraints);
    allConstraints.addAll(instanceConstraints);

    String instanceDdl = instanceDdl(instanceTable, instanceConstraints);
    String recordDdl = recordDdl(recordTable, recordDefinitions, constraints);
    String indexName = "idx_fr_" + scopeId + "_" + versionId + "_instance";
    String indexDdl =
        "create index "
            + indexName
            + " on "
            + recordTable
            + "(instance_id) where deleted = false";
    FormVersionIndex index = new FormVersionIndex(recordTable, indexName, indexDdl);
    String recordIdDefault = "nextval('form_record_id_seq'::regclass)";

    ArrayNode columnSummary = objectMapper.createArrayNode();
    for (FormVersionColumn column : columns) {
      ObjectNode node = columnSummary.addObject();
      node.put("table", column.tableName());
      node.put("name", column.columnName());
      node.put("type", column.columnType());
      node.put("nullable", column.nullable());
    }
    ObjectNode summary = objectMapper.createObjectNode();
    summary.put("templateKey", spec.templateKey());
    summary.put("migrationTarget", migrationTarget);
    summary.put("instancesTable", instanceTable);
    summary.put("recordsTable", recordTable);
    summary.set("columns", columnSummary);
    ArrayNode constraintSummary = summary.putArray("constraints");
    constraints.forEach(constraint -> constraintSummary.add(constraintJson(constraint)));
    instanceConstraints.forEach(
        constraint -> constraintSummary.add(constraintJson(constraint)));
    ArrayNode indexSummary = summary.putArray("indexes");
    ObjectNode indexNode = indexSummary.addObject();
    indexNode.put("table", index.tableName());
    indexNode.put("name", index.indexName());
    indexNode.put("definition", index.definition());
    summary.put("recordIdDefault", recordIdDefault);
    String ddlHash = FormHashSupport.sha256Hex(writeJson(summary));

    return new FormVersionSchema(
        scopeId,
        versionId,
        spec.templateKey(),
        instanceTable,
        recordTable,
        List.of(instanceDdl, recordDdl, indexDdl),
        List.copyOf(columns),
        List.copyOf(allConstraints),
        List.of(index),
        recordIdDefault,
        summary,
        ddlHash);
  }

  private List<FormVersionConstraint> recordConstraints(
      long scopeId,
      long versionId,
      String recordTable,
      String instanceTable,
      List<String> generatedChecks) {
    String prefix = "fr_" + scopeId + "_" + versionId;
    List<FormVersionConstraint> constraints = new ArrayList<>();
    constraints.add(FormVersionConstraint.primaryKey(recordTable, "pk_" + prefix, List.of("id")));
    constraints.add(
        FormVersionConstraint.foreignKey(
            recordTable,
            "fk_" + prefix + "_instance",
            List.of("instance_id"),
            instanceTable,
            List.of("id")));
    constraints.add(
        FormVersionConstraint.foreignKey(
            recordTable,
            "fk_" + prefix + "_origin",
            List.of("origin_version_id"),
            "form_template_versions",
            List.of("id")));
    constraints.add(
        FormVersionConstraint.unique(
            recordTable, "uq_" + prefix + "_request_key", List.of("request_key")));
    int checkIndex = 1;
    constraints.add(
        FormVersionConstraint.check(
            recordTable, "ck_" + prefix + "_" + checkIndex++, "record_version > 0"));
    constraints.add(
        FormVersionConstraint.check(
            recordTable,
            "ck_" + prefix + "_" + checkIndex++,
            "created_by is null or (request_key is not null "
                + "and create_payload_hash is not null and template_version is not null)"));
    constraints.add(
        FormVersionConstraint.check(
            recordTable,
            "ck_" + prefix + "_" + checkIndex++,
            "not legacy_incomplete or cardinality(legacy_missing_fields) > 0"));
    for (String generated : generatedChecks) {
      constraints.add(
          FormVersionConstraint.check(
              recordTable, "ck_" + prefix + "_" + checkIndex++, stripCheckWrapper(generated)));
    }
    return constraints;
  }

  private List<FormVersionConstraint> instanceConstraints(
      long scopeId, long versionId, String instanceTable) {
    String prefix = "fi_" + scopeId + "_" + versionId;
    List<FormVersionConstraint> constraints = new ArrayList<>();
    constraints.add(
        FormVersionConstraint.primaryKey(instanceTable, "pk_" + prefix, List.of("id")));
    constraints.add(
        FormVersionConstraint.foreignKey(
            instanceTable,
            "fk_" + prefix + "_scope",
            List.of("scope_id"),
            "form_template_scopes",
            List.of("id")));
    constraints.add(
        FormVersionConstraint.foreignKey(
            instanceTable,
            "fk_" + prefix + "_template",
            List.of("template_key"),
            "form_template_definitions",
            List.of("template_key")));
    constraints.add(
        FormVersionConstraint.unique(
            instanceTable,
            "uq_" + prefix + "_natural",
            List.of("scope_id", "source_config_id", "project_id", "resource_type", "resource_id")));
    constraints.add(
        FormVersionConstraint.check(
            instanceTable, "ck_" + prefix + "_project", "project_id > 0"));
    constraints.add(
        FormVersionConstraint.check(
            instanceTable, "ck_" + prefix + "_resource", "resource_id > 0"));
    return constraints;
  }

  private String stripCheckWrapper(String raw) {
    if (raw.startsWith("check (") && raw.endsWith(")")) {
      return raw.substring("check (".length(), raw.length() - 1);
    }
    return raw;
  }

  private ObjectNode constraintJson(FormVersionConstraint constraint) {
    ObjectNode node = objectMapper.createObjectNode();
    node.put("table", constraint.tableName());
    node.put("name", constraint.constraintName());
    node.put("kind", constraint.kind().name());
    ArrayNode columns = node.putArray("columns");
    constraint.columns().forEach(columns::add);
    if (constraint.referenceTable() == null) {
      node.putNull("referenceTable");
    } else {
      node.put("referenceTable", constraint.referenceTable());
    }
    ArrayNode referenceColumns = node.putArray("referenceColumns");
    constraint.referenceColumns().forEach(referenceColumns::add);
    if (constraint.expression() == null) {
      node.putNull("expression");
    } else {
      node.put("expression", constraint.expression());
    }
    putNullable(node, "onDelete", constraint.onDelete());
    putNullable(node, "onUpdate", constraint.onUpdate());
    putNullable(node, "matchType", constraint.matchType());
    node.put("deferrable", constraint.deferrable());
    node.put("initiallyDeferred", constraint.initiallyDeferred());
    node.put("validated", constraint.validated());
    return node;
  }

  private void putNullable(ObjectNode node, String field, String value) {
    if (value == null) {
      node.putNull(field);
    } else {
      node.put(field, value);
    }
  }

  private void appendInstanceColumns(List<FormVersionColumn> columns, String tableName) {
    columns.add(new FormVersionColumn(tableName, "id", "bigint", false));
    columns.add(new FormVersionColumn(tableName, "scope_id", "bigint", false));
    columns.add(new FormVersionColumn(tableName, "source_config_id", "bigint", false));
    columns.add(new FormVersionColumn(tableName, "source_instance", "varchar(128)", false));
    columns.add(new FormVersionColumn(tableName, "gitlab_base_url", "varchar(255)", false));
    columns.add(new FormVersionColumn(tableName, "project_id", "bigint", false));
    columns.add(new FormVersionColumn(tableName, "resource_type", "varchar(64)", false));
    columns.add(new FormVersionColumn(tableName, "resource_id", "bigint", false));
    columns.add(new FormVersionColumn(tableName, "template_key", "varchar(64)", false));
    columns.add(new FormVersionColumn(tableName, "created_at", "timestamp", false));
    columns.add(new FormVersionColumn(tableName, "updated_at", "timestamp", false));
  }

  private void appendSystemRecordColumns(
      List<String> definitions, List<FormVersionColumn> columns, String tableName) {
    add(definitions, columns, tableName, "id", "bigint", false);
    definitions.set(
        definitions.size() - 1,
        "id bigint not null default nextval('form_record_id_seq')");
    add(definitions, columns, tableName, "instance_id", "bigint", false);
    definitions.set(definitions.size() - 1, "instance_id bigint not null");
    add(definitions, columns, tableName, "origin_version_id", "bigint", false);
    definitions.set(definitions.size() - 1, "origin_version_id bigint not null");
    add(definitions, columns, tableName, "template_version", "varchar(64)", true);
    add(definitions, columns, tableName, "created_by", "varchar(128)", true);
    add(definitions, columns, tableName, "request_key", "varchar(64)", true);
    add(definitions, columns, tableName, "create_payload_hash", "char(64)", true);
    add(definitions, columns, tableName, "record_version", "integer", false);
    definitions.set(
        definitions.size() - 1, "record_version integer not null default 1");
    add(definitions, columns, tableName, "deleted", "boolean", false);
    definitions.set(definitions.size() - 1, "deleted boolean not null default false");
    add(definitions, columns, tableName, "legacy_incomplete", "boolean", false);
    definitions.set(
        definitions.size() - 1, "legacy_incomplete boolean not null default false");
    add(definitions, columns, tableName, "legacy_missing_fields", "text[]", false);
    definitions.set(
        definitions.size() - 1,
        "legacy_missing_fields text[] not null default '{}'");
    add(definitions, columns, tableName, "created_at", "timestamp", false);
    definitions.set(definitions.size() - 1, "created_at timestamp not null");
    add(definitions, columns, tableName, "updated_at", "timestamp", false);
    definitions.set(definitions.size() - 1, "updated_at timestamp not null");
  }

  private void appendBusinessColumns(
      List<String> definitions,
      List<FormVersionColumn> columns,
      List<String> constraints,
      String tableName,
      FormTemplateSpec spec,
      boolean migrationTarget) {
    for (FormFieldSpec field : spec.fields()) {
      String column = field.storage().resolvedColumn();
      String columnType = field.storage().resolvedColumnType();
      boolean requiredInDatabase = field.required();
      addColumn(
          definitions,
          columns,
          constraints,
          tableName,
          column,
          columnType,
          requiredInDatabase,
          "string".equals(field.type()) && isTextual(columnType),
          migrationTarget);
      if (field.storage().nameColumn() != null) {
        addColumn(
            definitions,
            columns,
            constraints,
            tableName,
            field.storage().nameColumn(),
            field.storage().resolvedNameColumnType(),
            requiredInDatabase,
            true,
            migrationTarget);
      }
      appendFieldValueConstraints(constraints, field, column, migrationTarget);
    }
  }

  private void appendSnapshotColumns(
      List<String> definitions,
      List<FormVersionColumn> columns,
      List<String> constraints,
      String tableName,
      FormTemplateSpec spec,
      boolean migrationTarget) {
    for (String snapshotColumn : spec.snapshotColumns()) {
      addColumn(
          definitions,
          columns,
          constraints,
          tableName,
          snapshotColumn,
          "boolean",
          true,
          false,
          migrationTarget);
    }
  }

  private void addColumn(
      List<String> definitions,
      List<FormVersionColumn> columns,
      List<String> constraints,
      String tableName,
      String column,
      String columnType,
      boolean required,
      boolean textual,
      boolean migrationTarget) {
    boolean nullable = !required || migrationTarget;
    String definition = column + " " + columnType + (nullable ? "" : " not null");
    definitions.add(definition);
    columns.add(new FormVersionColumn(tableName, column, columnType, nullable));
    if (!required) {
      return;
    }
    if (!textual) {
      if (migrationTarget) {
        constraints.add(wrapLegacy(column + " is not null", true));
      }
      return;
    }
    if (migrationTarget) {
      constraints.add(
          wrapLegacy(column + " is not null and btrim(" + column + ") <> ''", true));
    } else {
      constraints.add("check (btrim(" + column + ") <> '')");
    }
  }

  private void appendFieldValueConstraints(
      List<String> constraints, FormFieldSpec field, String column, boolean migrationTarget) {
    if (field.hasOptions()) {
      String values =
          field.options().stream()
              .map(FormFieldOption::value)
              .map(this::literal)
              .reduce((left, right) -> left + ", " + right)
              .orElseThrow();
      if ("enum-array".equals(field.type())) {
        constraints.add(
            wrapLegacy(
                "cardinality(" + column + ") > 0 and " + column + " <@ array[" + values + "]::text[]",
                migrationTarget));
      } else {
        constraints.add(wrapLegacy(column + " in (" + values + ")", migrationTarget));
      }
    }
    if (field.dictionary() != null && field.maxItems() != null) {
      constraints.add(
          wrapLegacy(
              "cardinality(" + column + ") >= 1 and cardinality(" + column + ") <= "
                  + field.maxItems(),
              migrationTarget));
    }
    if ("integer".equals(field.type()) && field.min() != null && field.max() != null) {
      constraints.add(
          wrapLegacy(
              column + " >= " + field.min() + " and " + column + " <= " + field.max(),
              migrationTarget));
    }
  }

  private void appendConditionConstraints(
      List<String> constraints, FormTemplateSpec spec, boolean migrationTarget) {
    for (JsonNode condition : spec.conditions()) {
      String type = condition.path("type").asText();
      if ("mutuallyExclusive".equals(type)) {
        String field = condition.path("field").asText();
        String whenContains = condition.path("whenContains").asText();
        constraints.add(
            wrapLegacy(
                "not ("
                    + literal(whenContains)
                    + " = any("
                    + field
                    + ") and cardinality("
                    + field
                    + ") > 1)",
                migrationTarget));
        continue;
      }
      if (!"conditionalRequired".equals(type)) {
        continue;
      }
      String target = condition.path("target").asText();
      String snapshotColumn = condition.path("snapshotColumn").asText(null);
      if (snapshotColumn != null) {
        constraints.add(
            wrapLegacy(
                snapshotColumn
                    + " is not null and (("
                    + snapshotColumn
                    + " and "
                    + target
                    + " is not null and btrim("
                    + target
                    + ") <> '') or (not "
                    + snapshotColumn
                    + " and "
                    + target
                    + " is null))",
                migrationTarget));
        continue;
      }
      JsonNode requiredWhen = condition.path("requiredWhen");
      if (!requiredWhen.isMissingNode()) {
        String requiredExpression = conditionExpression(requiredWhen);
        constraints.add(
            wrapLegacy(
                "not (" + requiredExpression + ") or ("
                    + target
                    + " is not null and btrim("
                    + target
                    + ") <> '')",
                migrationTarget));
      }
      JsonNode clearWhen = condition.path("clearWhen");
      if (!clearWhen.isMissingNode()) {
        String clearExpression = negatedConditionExpression(clearWhen);
        constraints.add(
            wrapLegacy("(" + clearExpression + ") or " + target + " is null", migrationTarget));
      }
    }
  }

  private String conditionExpression(JsonNode condition) {
    if (condition.hasNonNull("resourceSeverityLevel")) {
      throw new IllegalArgumentException("resourceSeverityLevel requires snapshotColumn");
    }
    String field = condition.path("field").asText();
    if (condition.hasNonNull("contains")) {
      return literal(condition.path("contains").asText()) + " = any(" + field + ")";
    }
    if (condition.hasNonNull("notContains")) {
      return "not (" + literal(condition.path("notContains").asText()) + " = any(" + field + "))";
    }
    if (condition.has("equals")) {
      JsonNode value = condition.path("equals");
      if (value.isBoolean()) {
        return value.asBoolean() ? field : "not " + field;
      }
      return field + " = " + literal(value.asText());
    }
    throw new IllegalArgumentException("Unsupported form condition: " + condition);
  }

  private String negatedConditionExpression(JsonNode condition) {
    String field = condition.path("field").asText();
    if (condition.hasNonNull("notContains")) {
      return literal(condition.path("notContains").asText()) + " = any(" + field + ")";
    }
    if (condition.has("equals")) {
      JsonNode value = condition.path("equals");
      if (value.isBoolean()) {
        return value.asBoolean() ? "not " + field : field;
      }
      return "not (" + field + " = " + literal(value.asText()) + ")";
    }
    if (condition.hasNonNull("contains")) {
      return "not (" + literal(condition.path("contains").asText()) + " = any(" + field + "))";
    }
    throw new IllegalArgumentException("Unsupported negated form condition: " + condition);
  }

  private String wrapLegacy(String expression, boolean migrationTarget) {
    if (!migrationTarget) {
      return "check (" + expression + ")";
    }
    return "check (deleted or legacy_incomplete or (" + expression + "))";
  }

  private boolean isTextual(String columnType) {
    return columnType.startsWith("varchar") || "text".equals(columnType);
  }

  private String literal(String value) {
    return "'" + value.replace("'", "''") + "'";
  }

  private void add(
      List<String> definitions,
      List<FormVersionColumn> columns,
      String tableName,
      String column,
      String columnType,
      boolean nullable) {
    definitions.add(column + " " + columnType);
    columns.add(new FormVersionColumn(tableName, column, columnType, nullable));
  }

  private String instanceDdl(
      String instanceTable, List<FormVersionConstraint> constraints) {
    List<String> definitions = new ArrayList<>();
    definitions.add("id bigserial not null");
    definitions.add("scope_id bigint not null");
    definitions.add("source_config_id bigint not null");
    definitions.add("source_instance varchar(128) not null");
    definitions.add("gitlab_base_url varchar(255) not null");
    definitions.add("project_id bigint not null");
    definitions.add("resource_type varchar(64) not null");
    definitions.add("resource_id bigint not null");
    definitions.add("template_key varchar(64) not null");
    definitions.add("created_at timestamp not null");
    definitions.add("updated_at timestamp not null");
    constraints.forEach(constraint -> definitions.add(constraintDefinition(constraint)));
    return "create table "
        + instanceTable
        + " (\n    "
        + String.join(",\n    ", definitions)
        + "\n)";
  }

  private String recordDdl(
      String recordTable,
      List<String> definitions,
      List<FormVersionConstraint> constraints) {
    List<String> all = new ArrayList<>(definitions);
    constraints.forEach(constraint -> all.add(constraintDefinition(constraint)));
    return "create table " + recordTable + " (\n    " + String.join(",\n    ", all) + "\n)";
  }

  private String constraintDefinition(FormVersionConstraint constraint) {
    String prefix = "constraint " + constraint.constraintName() + " ";
    return switch (constraint.kind()) {
      case PRIMARY_KEY ->
          prefix + "primary key (" + String.join(", ", constraint.columns()) + ")";
      case UNIQUE -> prefix + "unique (" + String.join(", ", constraint.columns()) + ")";
      case FOREIGN_KEY ->
          prefix
              + "foreign key ("
              + String.join(", ", constraint.columns())
              + ") references "
              + constraint.referenceTable()
              + " ("
              + String.join(", ", constraint.referenceColumns())
              + ")";
      case CHECK -> prefix + "check (" + constraint.expression() + ")";
    };
  }

  private String writeJson(JsonNode node) {
    try {
      return objectMapper.writeValueAsString(node);
    } catch (Exception error) {
      throw new IllegalStateException("Failed to serialize form DDL summary", error);
    }
  }
}
