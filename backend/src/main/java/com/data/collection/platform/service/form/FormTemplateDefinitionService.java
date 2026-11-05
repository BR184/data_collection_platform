package com.data.collection.platform.service.form;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.common.response.ResultCode;
import com.data.collection.platform.domain.form.FormSpecValidator;
import com.data.collection.platform.entity.form.admin.FormTemplateCreateRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 持久模板定义；与范围的可编辑草稿、已发布冻结版本分别管理。 */
@Service
public class FormTemplateDefinitionService {
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;
  private final FormSpecValidator validator;

  public FormTemplateDefinitionService(JdbcTemplate jdbc, ObjectMapper mapper, FormSpecValidator validator) {
    this.jdbc = jdbc;
    this.mapper = mapper;
    this.validator = validator;
  }

  /** 模板身份、填写对象及新范围使用的初始字段配置。 */
  public record Definition(String key, String displayName, String resourceType,
      String fillPolicy, String kind, String initialSpec) {}

  /** 读取有效定义，未知或停用模板返回业务错误。 */
  public Definition require(String key) {
    List<Definition> rows = jdbc.query("""
        select template_key, display_name, resource_type, fill_policy, definition_kind,
               initial_spec::text as initial_spec
          from form_template_definitions where template_key = ? and status = 'ACTIVE'
        """, (rs, n) -> new Definition(rs.getString("template_key"), rs.getString("display_name"),
            rs.getString("resource_type"), rs.getString("fill_policy"),
            rs.getString("definition_kind"), rs.getString("initial_spec")), key);
    if (rows.isEmpty()) {
      throw new BizException(ResultCode.BAD_REQUEST, "表单不存在或已停用");
    }
    return rows.getFirst();
  }

  /** 按持久填写对象规则核验当前账号；创建人来自资源的账号ID关联，不以显示姓名匹配。 */
  public boolean canFill(FormVersionContext context, String account) {
    Definition definition = require(context.spec().templateKey());
    if ("AUTHORIZED_USER".equals(definition.fillPolicy())) {
      return true;
    }
    if (context.rootId() == null || !"default".equals(context.sourceInstance())) {
      return false;
    }
    String table = "merge_request".equals(context.resourceType())
        ? "ods_gitlab_merge_requests" : "ods_gitlab_issues";
    String projectColumn = "merge_request".equals(context.resourceType()) ? "target_project_id" : "project_id";
    Boolean exists = jdbc.queryForObject("select exists(select 1 from " + table
        + " r join ods_gitlab_users u on u.id = r.author_id where r.id = ? and r."
        + projectColumn + " = ? and r.iid = ? and u.username = ?)", Boolean.class,
        context.rootId(), context.projectId(), context.resourceId(), account);
    return Boolean.TRUE.equals(exists);
  }

  /** 创建独立身份；从已发布版本复制时重建字段与条件引用，不复制记录、范围或权威。 */
  @Transactional
  public Definition create(FormTemplateCreateRequest request) {
    String key = "form_" + UUID.randomUUID().toString().replace("-", "");
    ObjectNode spec = mapper.createObjectNode();
    spec.put("spec_version", 1);
    spec.put("template_key", key);
    spec.putArray("fields");
    spec.putArray("conditions");
    spec.putArray("dictionaries");
    if (request.copyFromVersionId() != null) {
      List<String> sources = jdbc.queryForList("""
          select v.field_spec::text from form_template_versions v
          join form_template_scopes s on s.id = v.scope_id
          where v.id = ? and v.version_status in ('PUBLISHED', 'RETIRED')
            and s.resource_type = ? and v.record_source_role = 'AUTHORITATIVE'
          """, String.class, request.copyFromVersionId(), request.resourceType());
      if (sources.isEmpty()) {
        throw new BizException(ResultCode.BAD_REQUEST, "请选择资源类型一致的已发布表单版本");
      }
      spec = copySpec(sources.getFirst(), key);
      validator.validate(validator.parse(spec.toString()));
    }
    jdbc.update("""
        insert into form_template_definitions(template_key, display_name, status, resource_type,
            fill_policy, definition_kind, initial_spec)
        values (?, ?, 'ACTIVE', ?, ?, 'CUSTOM', ?::jsonb)
        """, key, request.displayName().trim(), request.resourceType(), request.fillPolicy(), spec.toString());
    return require(key);
  }

  private ObjectNode copySpec(String raw, String key) {
    try {
      ObjectNode copy = (ObjectNode) mapper.readTree(raw);
      Map<String, String> names = new HashMap<>();
      for (JsonNode field : copy.path("fields")) {
        String identity = "f_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        names.put(field.path("name").asText(), identity);
        names.put(field.path("apiKey").asText(), identity.replace("_", ""));
        for (String column : List.of("column", "idColumn", "nameColumn")) {
          String old = field.path("storage").path(column).asText();
          if (!old.isBlank() && !names.containsKey(old)) {
            names.put(old, identity + ("nameColumn".equals(column) ? "_name" : "_value"));
          }
        }
      }
      for (JsonNode condition : copy.path("conditions")) {
        String snapshot = condition.path("snapshotColumn").asText();
        if (!snapshot.isBlank()) {
          names.put(snapshot, "s_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        }
      }
      // 引用名仅在结构属性中替换，选项值、显示文字和默认业务值保持。
      replaceReferences(copy, names);
      copy.put("template_key", key);
      return copy;
    } catch (java.io.IOException error) {
      throw new BizException(ResultCode.BAD_REQUEST, "复制来源字段配置无法解析");
    }
  }

  private static final Set<String> REFERENCES = Set.of(
      "name", "apiKey", "field", "target", "column", "idColumn", "nameColumn", "snapshotColumn");

  private void replaceReferences(JsonNode node, Map<String, String> names) {
    if (node.isObject()) {
      ObjectNode object = (ObjectNode) node;
      object.properties().forEach(entry -> {
        JsonNode value = entry.getValue();
        if (REFERENCES.contains(entry.getKey()) && value.isTextual() && names.containsKey(value.asText())) {
          object.put(entry.getKey(), names.get(value.asText()));
        } else {
          replaceReferences(value, names);
        }
      });
    } else if (node.isArray()) {
      node.forEach(value -> replaceReferences(value, names));
    }
  }
}
