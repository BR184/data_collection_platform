package com.data.collection.platform.golden;

import static org.assertj.core.api.Assertions.assertThat;

import com.data.collection.platform.domain.form.FormScopeBinding;
import com.data.collection.platform.domain.form.FormTemplateSpec;
import com.data.collection.platform.entity.form.FormActorContext;
import com.data.collection.platform.entity.form.FormCreateRequest;
import com.data.collection.platform.security.PlatformPermissionCodes;
import com.data.collection.platform.service.form.FormRecordService;
import com.data.collection.platform.service.form.FormVersionSchemaService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

/** 黄金链路的固定有值表单输入；通过真实记录服务创建，结构规格冻结于夹具资源。 */
final class GoldenFormFixture {
  private GoldenFormFixture() {}

  static void seed(JdbcTemplate jdbc, ObjectMapper json, FormVersionSchemaService schema,
      FormRecordService records, long configId) throws Exception {
    String url = "http://gitlab.test.local:18080";
    String raw;
    try (var input = new ClassPathResource("golden-baseline/fixtures/form-response-spec.json").getInputStream()) {
      raw = new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }
    FormTemplateSpec spec = json.readValue(raw, FormTemplateSpec.class);
    jdbc.update("update gitlab_sync_configs set web_base_url=? where id=?", url, configId);
    jdbc.update("insert into form_template_scopes(id,template_key,scope_key,resource_type,status) values (1,'issue_response','golden-response','issue','DRAFT')");
    for (String ddl : schema.generate(1, 1, spec, false).statements()) {
      jdbc.execute(ddl);
    }
    jdbc.update("""
        insert into form_template_versions(id,scope_id,version_label,storage_kind,instance_table,
          record_table,field_spec,version_status,published_at)
        values(1,1,'CC2026R3','RECORDS','form_s1_v1_instances','form_s1_v1_records',?::jsonb,
          'PUBLISHED','2026-09-01 12:00:00')
        """, raw);
    String bindingHash = FormScopeBinding.hash(json,
        FormScopeBinding.create(json, "issue_response", configId, "default", url, List.of(9L), 9L, "issue"));
    jdbc.update("""
        update form_template_scopes set source_config_id=?,source_instance='default',
          canonical_gitlab_base_url=?,project_ids='[9]'::jsonb,version_project_id=9,
          current_version_id=1,status='ACTIVE',binding_hash=?,binding_revision=1 where id=1
        """, configId, url, bindingHash);
    jdbc.update("update form_template_scopes set source_set_hash=form_source_set_hash(id) where id=1");
    jdbc.update("""
        insert into form_resource_authorities(source_config_id,source_instance,canonical_gitlab_base_url,
          project_id,resource_type,resource_id,template_key,scope_id,authority,authority_revision,
          effective_at,created_at,updated_at)
        values(?,'default',?,9,'issue',969,'issue_response',1,'FORM',1,
          '2026-09-01 12:00:00','2026-09-01 12:00:00','2026-09-01 12:00:00')
        """, configId, url);
    Map<String, JsonNode> fields = new LinkedHashMap<>();
    try (var input = new ClassPathResource("golden-baseline/fixtures/form-response-fields.json").getInputStream()) {
      json.readTree(input).fields().forEachRemaining(e -> fields.put(e.getKey(), e.getValue()));
    }
    var created = records.create(new FormCreateRequest(url,9L,"issue","969","issue_response","1","1",
        "00000000-0000-4000-8000-000000000001",false,fields),
        new FormActorContext("admin","管理员",Set.of(PlatformPermissionCodes.FORM_RECORD_CREATE)),
        "127.0.0.1","golden-fixture");
    assertThat(created.outcome()).isEqualTo("CREATED");
    assertThat(created.record().id()).isEqualTo("1");
    try (var input = new ClassPathResource("golden-baseline/baseline-manifest.json").getInputStream()) {
      var counts = json.readTree(input).path("form_input").path("table_counts");
      counts.fields().forEachRemaining(entry -> {
        assertThat(entry.getKey()).matches("[a-z0-9_]+");
        assertThat(jdbc.queryForObject("select count(*) from " + entry.getKey(), Long.class))
            .as("冻结输入行数：%s", entry.getKey()).isEqualTo(entry.getValue().asLong());
      });
    }
    // 输入时间固定在消费前；运行期写入时间不作为历史夹具的一部分。
    for (String table : List.of("form_template_scopes","form_template_versions","form_s1_v1_instances",
        "form_s1_v1_records","form_record_locations","form_record_audit_logs","form_resource_authorities")) {
      List<String> columns = jdbc.queryForList("select column_name from information_schema.columns where table_schema=current_schema() and table_name=? and column_name in ('created_at','updated_at')", String.class, table);
      for (String column : columns) {
        jdbc.execute("update " + table + " set " + column + "='2026-09-01 12:00:00'");
      }
    }
    jdbc.execute("select setval('form_template_scopes_id_seq',1,true)");
    jdbc.execute("select setval('form_template_versions_id_seq',1,true)");
  }
}
