package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.entity.database.DatabaseTableColumn;
import com.data.collection.platform.entity.database.DatabaseTableRowsResponse;
import com.data.collection.platform.mapper.GitlabMirrorTableRegistryMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * R01 防回归：数据库浏览器只暴露受控表定义声明的公开列，新增物理列不得自动进入响应。
 *
 * <p>{@code buildSql} 决定查询投影，{@code createTableRowMapper} 决定结果行键集合，
 * 二者共同且唯一构成 {@code DatabaseTableRowsResponse.getRows()}，因此本类直接锁定这两处边界。
 */
class DatabaseBrowserPublicColumnsTest {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  @Test
  void buildSqlProjectsDeclaredColumnsInsteadOfSelectStar() {
    DatabaseBrowserTableDefinition definition = DatabaseBrowserTableCatalog.findDefinition("gitlab_sync_configs");

    DatabaseBrowserSqlBundle bundle =
        DatabaseBrowserQuerySupport.buildSql(definition, "gitlab_sync_configs", null, "id", "desc", 1, 20);

    String rowsSql = bundle.rowsSql();
    assertThat(rowsSql).doesNotContain("select *");
    assertThat(rowsSql).startsWith("select \"id\", \"name\", \"source_mode\", \"whitelist_mode\", \"updated_at\", \"created_at\" from");
    // 敏感物理列不得出现在查询投影中。
    assertThat(rowsSql).doesNotContain("db_password").doesNotContain("api_token").doesNotContain("system_hook_secret");
    // 可搜索但不公开的列只用于过滤，不出现在返回列里。
    assertThat(rowsSql).doesNotContain("\"db_username\"");
  }

  @Test
  void buildSqlFailsWhenDefinitionHasNoDisplayColumns() {
    DatabaseBrowserTableDefinition empty =
        new DatabaseBrowserTableDefinition("空表", List.of(), List.of(), "id");

    assertThatThrownBy(
        () -> DatabaseBrowserQuerySupport.buildSql(empty, "empty_table", null, "id", "desc", 1, 20))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("缺少可展示的列定义");
  }

  @Test
  void rowMapperReadsOnlyDeclaredColumnsIgnoringExtraPhysicalColumns() throws Exception {
    List<DatabaseTableColumn> declared =
        List.of(new DatabaseTableColumn("id", "ID", true), new DatabaseTableColumn("name", "名称", true));
    RowMapper<Map<String, Object>> mapper = DatabaseBrowserRowMapperFactory.createTableRowMapper(declared);

    // 模拟物理表比声明列更多：只投影两列，若映射器误读元数据全列会暴露 db_password。
    ResultSet resultSet = mock(ResultSet.class);
    when(resultSet.getObject(1)).thenReturn(7L);
    when(resultSet.getObject(2)).thenReturn("prod-source");
    when(resultSet.getObject(3)).thenReturn("super-secret-password");

    Map<String, Object> row = mapper.mapRow(resultSet, 0);

    assertThat(row).containsOnlyKeys("id", "name");
    assertThat(row).containsEntry("id", 7L).containsEntry("name", "prod-source");
    assertThat(row.values()).doesNotContain("super-secret-password");
  }

  @Test
  void projectedColumnsAndRowMapperAgreeOnDeclaredSet() {
    DatabaseBrowserTableDefinition definition = DatabaseBrowserTableCatalog.findDefinition("gitlab_sync_configs");

    DatabaseBrowserSqlBundle bundle =
        DatabaseBrowserQuerySupport.buildSql(definition, "gitlab_sync_configs", null, "id", "desc", 1, 20);
    RowMapper<Map<String, Object>> mapper =
        DatabaseBrowserRowMapperFactory.createTableRowMapper(definition.columns());

    // 投影列数必须与映射器读取的声明列数一致，保证按位置对齐。
    long projected = bundle.rowsSql()
        .substring(bundle.rowsSql().indexOf(' ') + 1, bundle.rowsSql().indexOf(" from"))
        .chars().filter(c -> c == '"').count() / 2;
    assertThat(projected).isEqualTo(definition.columns().size());
    assertThat(mapper).isNotNull();
  }

  @Test
  void collectFormEditorFieldsAreAllDeclaredAndProjected() {
    // 声明/SQL 契约层：数据库浏览器内联编辑器（frontend DatabaseBrowserView.vue openCollectFormEditor /
    // saveCollectFormEdit）从行数据回填并回写这些物理列，后端 CollectFormService.updateRecord 对空
    // remark 会写空串。本用例仅断言表定义与 buildSql 投影都覆盖编辑器消费的字段；运行期 JSON 往返由
    // collectFormRemarkRoundTripsThroughSerializedResponse 覆盖。
    List<String> editorConsumedColumns = List.of(
        "id", "form_title", "reviewer", "review_duration_minutes",
        "specification_score", "logic_score", "performance_score", "design_score", "other_score",
        "remark", "deleted", "gitlab_base_url", "project_id", "request_iid",
        "resource_type", "resource_id", "template_code", "created_at", "updated_at");

    DatabaseBrowserTableDefinition definition = DatabaseBrowserTableCatalog.findDefinition("collect_form_records");
    List<String> declared = definition.columns().stream().map(DatabaseTableColumn::getKey).toList();
    assertThat(declared).containsAll(editorConsumedColumns);

    DatabaseBrowserSqlBundle bundle =
        DatabaseBrowserQuerySupport.buildSql(definition, "collect_form_records", null, "id", "desc", 1, 20);
    // 显式投影必须逐列覆盖编辑器消费的字段，尤其是历史上仅靠 select * 隐式带出的 remark。
    assertThat(bundle.rowsSql()).doesNotContain("select *");
    for (String column : editorConsumedColumns) {
      assertThat(bundle.rowsSql()).contains("\"" + column + "\"");
    }
  }

  @Test
  void collectFormRemarkRoundTripsThroughSerializedResponse() {
    // 运行期跨层回归：走真实 getTableRows→buildSql→createTableRowMapper，再用默认 ObjectMapper
    // 序列化 response.getRows() 后解析，对 remark 非空/空串/null 断言 JSON 键与值。仅覆盖字符串/
    // 数字/null 的 Map 字段序列化，不代表完整 HTTP、日期时间或项目 Spring 注入 ObjectMapper 的行为；
    // JDBC 替身只按生产实际生成的投影列逐位置喂值，不预先返回裁剪好的 Map。
    Map<String, Object> physical = collectFormFixture();
    physical.put("remark", "必须保留的原备注");
    JsonNode keepRow = serializeFirstRow(queryRowsThroughRealService("collect_form_records", physical));
    assertThat(keepRow.has("remark")).as("remark 必须进入序列化响应（否则编辑器读到空并回写清空库值）").isTrue();
    assertThat(keepRow.get("remark").asText()).isEqualTo("必须保留的原备注");

    physical.put("remark", "");
    JsonNode emptyRow = serializeFirstRow(queryRowsThroughRealService("collect_form_records", physical));
    assertThat(emptyRow.has("remark")).isTrue();
    assertThat(emptyRow.get("remark").asText()).isEmpty();

    physical.put("remark", null);
    JsonNode nullRow = serializeFirstRow(queryRowsThroughRealService("collect_form_records", physical));
    assertThat(nullRow.has("remark")).isTrue();
    assertThat(nullRow.get("remark").isNull()).isTrue();
  }

  @Test
  void syncConfigSecretColumnsNeverReachSerializedResponse() {
    // 敏感物理列不进 SQL 投影，也不进序列化后的完整响应 JSON（键与值都不出现）。
    Map<String, Object> physical = new LinkedHashMap<>();
    physical.put("id", 1L);
    physical.put("name", "prod-source");
    physical.put("source_mode", "DIRECT");
    physical.put("whitelist_mode", "W");
    physical.put("updated_at", null);
    physical.put("created_at", null);
    // 合成敏感值排在六个公开列之后：若回归到 select *，parseProjectionColumns 会因投影为 "*"
    // 明确拒绝（见该辅助方法的 doesNotContain("select *") 断言），而不是把整表物理列展开后泄露。
    physical.put("db_password", "hunter2");
    physical.put("api_token", "tok-SECRET");
    physical.put("system_hook_secret", "hook-SECRET");

    DatabaseTableRowsResponse response = queryRowsThroughRealService("gitlab_sync_configs", physical);
    String fullJson = writeJson(response);

    assertThat(fullJson)
        .doesNotContain("db_password", "api_token", "system_hook_secret")
        .doesNotContain("hunter2", "tok-SECRET", "hook-SECRET");

    JsonNode row = serializeFirstRow(response);
    assertThat(fieldNames(row))
        .containsExactly("id", "name", "source_mode", "whitelist_mode", "updated_at", "created_at");
  }

  private String writeJson(Object value) {
    try {
      return OBJECT_MAPPER.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  /** 序列化响应中的 rows 列表、解析回 JSON 数组并取第一行，断言的是真实 JSON 键/值而非内存 Map。 */
  private JsonNode serializeFirstRow(DatabaseTableRowsResponse response) {
    try {
      JsonNode rows = OBJECT_MAPPER.readTree(writeJson(response.getRows()));
      assertThat(rows.isArray()).isTrue();
      return rows.get(0);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  private List<String> fieldNames(JsonNode node) {
    List<String> names = new ArrayList<>();
    node.fieldNames().forEachRemaining(names::add);
    return names;
  }

  private Map<String, Object> collectFormFixture() {
    Map<String, Object> physical = new LinkedHashMap<>();
    physical.put("id", 42L);
    physical.put("form_title", "评审记录");
    physical.put("reviewer", "张三");
    physical.put("review_duration_minutes", 60);
    physical.put("specification_score", 3);
    physical.put("logic_score", 4);
    physical.put("performance_score", 5);
    physical.put("design_score", 2);
    physical.put("other_score", 1);
    physical.put("deleted", false);
    physical.put("gitlab_base_url", "https://gitlab.example");
    physical.put("project_id", 7L);
    physical.put("request_iid", 9L);
    physical.put("resource_type", "merge_request");
    physical.put("resource_id", "101");
    physical.put("template_code", "code-review");
    physical.put("created_at", null);
    physical.put("updated_at", null);
    return physical;
  }

  @SuppressWarnings("unchecked")
  private DatabaseTableRowsResponse queryRowsThroughRealService(String tableName, Map<String, Object> physicalValues) {
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(1L);
    when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class))).thenAnswer(invocation -> {
      String rowsSql = invocation.getArgument(0);
      RowMapper<Map<String, Object>> mapper = invocation.getArgument(1);
      List<String> projection = parseProjectionColumns(rowsSql);
      ResultSet resultSet = mock(ResultSet.class);
      for (int index = 0; index < projection.size(); index++) {
        when(resultSet.getObject(index + 1)).thenReturn(physicalValues.get(projection.get(index)));
      }
      return List.of(mapper.mapRow(resultSet, 0));
    });
    DatabaseBrowserService service = new DatabaseBrowserService(
        jdbcTemplate,
        mock(GitlabMirrorTableRegistryMapper.class),
        mock(DatabaseBrowserMirrorTableDefinitionFactory.class),
        mock(GitlabMirrorSyncService.class),
        mock(GitlabConfigService.class),
        mock(SourceMetadataInspector.class),
        mock(GitlabExternalDbService.class));

    return service.getTableRows(tableName, 1, 20, null, null, null);
  }

  /** 从生产生成的 {@code select "a", "b" ... from ...} 中按顺序解析出被投影的列名，逐位置对应映射器读取。 */
  private List<String> parseProjectionColumns(String rowsSql) {
    int fromIndex = rowsSql.indexOf(" from ");
    assertThat(rowsSql).as("行查询必须使用显式投影").startsWith("select ").doesNotContain("select *");
    String projection = rowsSql.substring("select ".length(), fromIndex);
    List<String> columns = new ArrayList<>();
    for (String part : projection.split(",")) {
      columns.add(part.trim().replace("\"", ""));
    }
    return columns;
  }
}
