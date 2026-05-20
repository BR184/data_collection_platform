package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.SourceTableColumn;
import com.data.collection.platform.entity.SourceTableSchema;
import com.data.collection.platform.entity.TableWhitelistOption;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLXML;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PGobject;

class GitlabExternalDbServiceTest {

  private GitlabExternalDbService service;

  @BeforeEach
  void setUp() {
    service = new GitlabExternalDbService(new GitlabMirrorProperties(), new ObjectMapper());
  }

  @Test
  void shouldPreferUpdatedAtVariantsOverCreatedAtVariants() {
    String resolved = service.resolveUpdatedAtColumn(List.of("id", "CreatedAt", "UpdatedAt"));

    assertThat(resolved).isEqualTo("UpdatedAt");
  }

  @Test
  void shouldFallbackToCreatedAtVariantWhenUpdatedAtIsMissing() {
    String resolved = service.resolveUpdatedAtColumn(List.of("id", "createdAt", "name"));

    assertThat(resolved).isEqualTo("createdAt");
  }

  @Test
  void shouldRecognizeCommonSnakeCaseAndCamelCaseVariants() {
    assertThat(service.resolveUpdatedAtColumn(List.of("id", "updated_at"))).isEqualTo("updated_at");
    assertThat(service.resolveUpdatedAtColumn(List.of("id", "updatedAt"))).isEqualTo("updatedAt");
    assertThat(service.resolveUpdatedAtColumn(List.of("id", "gmt_modified"))).isEqualTo("gmt_modified");
    assertThat(service.resolveUpdatedAtColumn(List.of("id", "create_time"))).isEqualTo("create_time");
  }

  @Test
  void shouldReturnNullWhenNoCandidateExists() {
    assertThat(service.resolveUpdatedAtColumn(List.of("id", "name", "description"))).isNull();
  }

  @Test
  void shouldMarkTablesWithoutUpdatedAtAsFullOnly() {
    assertThat(service.resolveRowStrategy("updated_at")).isEqualTo("INCREMENTAL");
    assertThat(service.resolveRowStrategy(null)).isEqualTo("FULL_ONLY");
    assertThat(service.resolveRowStrategy("")).isEqualTo("FULL_ONLY");
  }

  @Test
  void shouldBuildStableSchemaFingerprintForSourceMetadata() {
    SourceTableSchema schema = new SourceTableSchema(
        "issues",
        List.of("id"),
        "updated_at",
        List.of(
            new SourceTableColumn("id", "bigint", false, 1),
            new SourceTableColumn("title", "text", true, 2),
            new SourceTableColumn("updated_at", "timestamp without time zone", false, 3)));

    assertThat(service.buildSchemaFingerprint(schema)).isEqualTo(service.buildSchemaFingerprint(schema));
    assertThat(service.buildSchemaFingerprint(schema)).hasSize(16);
  }

  @Test
  void shouldBuildJdbcUrlWithTimeoutsAndTcpKeepAliveForUnstableNetwork() {
    GitlabMirrorProperties properties = new GitlabMirrorProperties();
    properties.setExternalQueryTimeoutSeconds(45);
    service = new GitlabExternalDbService(properties, new ObjectMapper());
    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setDbHost("10.0.0.8");
    config.setDbPort(5432);
    config.setDbName("gitlabhq_production");

    String url = service.buildJdbcUrl(config);

    assertThat(url)
        .isEqualTo("jdbc:postgresql://10.0.0.8:5432/gitlabhq_production?connectTimeout=45&socketTimeout=45&tcpKeepAlive=true");
  }

  @Test
  void shouldQuoteSourceTableNameForFullScans() {
    TableWhitelistOption option =
        new TableWhitelistOption("Issue Events", "Issue Events", "id", "Updated At", false);

    String sql = service.buildFullTableScanSql(option);

    assertThat(sql).isEqualTo("select * from \"public\".\"Issue Events\"");
  }

  @Test
  void shouldBuildPrimaryKeyCursorSqlForFullScans() {
    TableWhitelistOption option =
        new TableWhitelistOption("label_links", "Label links", "label_id,target_id,target_type", null, true);
    SourceTableSchema schema = new SourceTableSchema(
        "ods_gitlab_label_links",
        List.of("label_id", "target_id", "target_type"),
        null,
        List.of(
            new SourceTableColumn("label_id", "bigint", false, 1),
            new SourceTableColumn("target_id", "bigint", false, 2),
            new SourceTableColumn("target_type", "text", false, 3)));

    String sql = service.buildFullCursorScanSql(option, schema, "1\u001F101\u001FIssue", 100);

    assertThat(sql)
        .contains("select cursor_rows.\"label_id\", cursor_rows.\"target_id\", cursor_rows.\"target_type\"")
        .contains("concat_ws(chr(31), source_rows.\"label_id\"::text, source_rows.\"target_id\"::text, source_rows.\"target_type\"::text) as pk_signature")
        .contains("from \"public\".\"label_links\" source_rows")
        .contains("where cursor_rows.pk_signature > '1\u001F101\u001FIssue'")
        .contains("order by cursor_rows.pk_signature asc")
        .contains("limit 100");
  }

  @Test
  void shouldQuoteSourceTableAndTimeColumnForWindowScans() {
    TableWhitelistOption option =
        new TableWhitelistOption("Issue Events", "Issue Events", "id", "Updated At", false);

    String sql = service.buildTimeWindowScanSql(option, LocalDateTime.of(2026, 1, 2, 3, 4, 5));

    assertThat(sql)
        .isEqualTo("select * from \"public\".\"Issue Events\" where \"Updated At\" >= timestamp '2026-01-02 03:04:05'");
  }

  @Test
  void shouldBuildCursorBatchScanSqlWithUpdatedAtAndPrimaryKeyCursor() {
    TableWhitelistOption option =
        new TableWhitelistOption("Issue Events", "Issue Events", "Issue ID", "Updated At", false);

    String sql = service.buildCursorBatchScanSql(
        option,
        LocalDateTime.of(2026, 1, 2, 3, 4, 5),
        LocalDateTime.of(2026, 1, 2, 3, 5, 6),
        "101",
        200);

    assertThat(sql)
        .isEqualTo("select * from \"public\".\"Issue Events\" where \"Updated At\" >= timestamp '2026-01-02 03:04:05' "
            + "and (\"Updated At\" > timestamp '2026-01-02 03:05:06' or (\"Updated At\" = timestamp '2026-01-02 03:05:06' "
            + "and \"Issue ID\" > '101')) order by \"Updated At\" asc, \"Issue ID\" asc limit 200");
  }

  @Test
  void shouldBuildCursorBatchScanSqlWithoutCursorForFirstBatch() {
    TableWhitelistOption option =
        new TableWhitelistOption("issues", "Issues", "id", "updated_at", true);

    String sql = service.buildCursorBatchScanSql(
        option,
        LocalDateTime.of(2026, 1, 2, 3, 4, 5),
        null,
        null,
        0);

    assertThat(sql)
        .isEqualTo("select * from \"public\".\"issues\" where \"updated_at\" >= timestamp '2026-01-02 03:04:05' "
            + "order by \"updated_at\" asc, \"id\" asc limit 1");
  }

  @Test
  void shouldBuildLightweightTableProbeSql() {
    TableWhitelistOption option =
        new TableWhitelistOption("Issue Events", "Issue Events", "Issue ID", "Updated At", false);

    String sql = service.buildTableProbeSql(option);

    assertThat(sql)
        .isEqualTo("select count(*) as row_count,\n"
            + "       max(\"Updated At\") as max_updated_at,\n"
            + "       min(\"Issue ID\")::text as min_pk,\n"
            + "       max(\"Issue ID\")::text as max_pk\n"
            + "  from \"public\".\"Issue Events\"");
  }

  @Test
  void shouldBuildLightweightTableProbeSqlWithoutUpdatedAt() {
    TableWhitelistOption option =
        new TableWhitelistOption("label_links", "Label links", "id", null, true);

    String sql = service.buildTableProbeSql(option);

    assertThat(sql)
        .isEqualTo("select count(*) as row_count,\n"
            + "       null::timestamp as max_updated_at,\n"
            + "       min(\"id\")::text as min_pk,\n"
            + "       max(\"id\")::text as max_pk\n"
            + "  from \"public\".\"label_links\"");
  }

  @Test
  void shouldBuildExistingPrimaryKeysSqlForCompositeKeys() {
    TableWhitelistOption option =
        new TableWhitelistOption("label_links", "Label links", "label_id,target_id,target_type", null, true);

    String sql = service.buildExistingPrimaryKeysSql(
        option,
        List.of("label_id", "target_id", "target_type"),
        List.of(
            Map.of("label_id", "1", "target_id", "101", "target_type", "Issue"),
            Map.of("label_id", "2", "target_id", "102", "target_type", "MergeRequest")));

    assertThat(sql)
        .isEqualTo("select \"label_id\"::text as \"label_id\", \"target_id\"::text as \"target_id\", \"target_type\"::text as \"target_type\"\n"
            + "  from \"public\".\"label_links\"\n"
            + " where (\"label_id\"::text = '1' and \"target_id\"::text = '101' and \"target_type\"::text = 'Issue') "
            + "or (\"label_id\"::text = '2' and \"target_id\"::text = '102' and \"target_type\"::text = 'MergeRequest')");
  }

  @Test
  void shouldBuildShardProbeSqlFromPrimaryKeyAndSourceColumns() {
    TableWhitelistOption option =
        new TableWhitelistOption("issues", "Issues", "id", "updated_at", true);
    SourceTableSchema schema = new SourceTableSchema(
        "ods_gitlab_issues",
        List.of("id"),
        "updated_at",
        List.of(
            new SourceTableColumn("id", "bigint", false, 1),
            new SourceTableColumn("title", "text", true, 2),
            new SourceTableColumn("updated_at", "timestamp without time zone", false, 3)));

    String sql = service.buildTableShardProbeSql(option, schema, 2);

    assertThat(sql)
        .contains("substring(md5(concat_ws(chr(31), \"id\"::text)), 1, 2) as shard_key")
        .contains("md5(jsonb_build_array(to_jsonb(\"id\"), to_jsonb(\"title\"), to_jsonb(\"updated_at\"))::text) as row_hash")
        .contains("md5(coalesce(string_agg(row_hash, ',' order by pk_signature), '')) as checksum")
        .contains("from \"public\".\"issues\"");
  }

  @Test
  void shouldBuildShardCursorScanSqlWithCompositePrimaryKey() {
    TableWhitelistOption option =
        new TableWhitelistOption("label_links", "Label links", "label_id,target_id,target_type", null, true);
    SourceTableSchema schema = new SourceTableSchema(
        "ods_gitlab_label_links",
        List.of("label_id", "target_id", "target_type"),
        null,
        List.of(
            new SourceTableColumn("label_id", "bigint", false, 1),
            new SourceTableColumn("target_id", "bigint", false, 2),
            new SourceTableColumn("target_type", "text", false, 3)));

    String sql = service.buildShardCursorScanSql(option, schema, "0a", "1\u001F101\u001FIssue", 50);

    assertThat(sql)
        .contains("concat_ws(chr(31), source_rows.\"label_id\"::text, source_rows.\"target_id\"::text, source_rows.\"target_type\"::text) as pk_signature")
        .contains("from \"public\".\"label_links\" source_rows")
        .contains("where substring(md5(pk_signature), 1, 2) = '0a'")
        .contains("and pk_signature > '1\u001F101\u001FIssue'")
        .contains("order by pk_signature asc")
        .contains("limit 50");
  }

  @Test
  void shouldQuoteSourceTableAndLookupColumnForPreciseScans() {
    TableWhitelistOption option =
        new TableWhitelistOption("Issue Events", "Issue Events", "id", "Updated At", false);

    String sql = service.buildPreciseScanSql(option, "Issue ID", 101L);

    assertThat(sql).isEqualTo("select * from \"public\".\"Issue Events\" where \"Issue ID\" = 101");
  }

  @Test
  void shouldEscapeQuotesInSourceIdentifiers() {
    TableWhitelistOption option =
        new TableWhitelistOption("issue\"events", "issue\"events", "id", "updated_at", false);

    String sql = service.buildFullTableScanSql(option);

    assertThat(sql).isEqualTo("select * from \"public\".\"issue\"\"events\"");
  }

  @Test
  void shouldRetryTransientExternalQueryFailures() {
    GitlabMirrorProperties properties = new GitlabMirrorProperties();
    properties.setExternalQueryRetryAttempts(3);
    properties.setExternalQueryRetryDelayMs(0);
    service = new GitlabExternalDbService(properties, new ObjectMapper());
    AtomicInteger attempts = new AtomicInteger();

    String result = service.executeExternalQueryWithRetry("test query", () -> {
      if (attempts.incrementAndGet() < 3) {
        throw new BizException("Connection reset by peer");
      }
      return "ok";
    });

    assertThat(result).isEqualTo("ok");
    assertThat(attempts).hasValue(3);
  }

  @Test
  void shouldUseExponentialBackoffWithJitterForExternalQueryRetries() {
    GitlabMirrorProperties properties = new GitlabMirrorProperties();
    properties.setExternalQueryRetryDelayMs(1000);
    properties.setExternalQueryRetryMaxDelayMs(2500);
    service = new GitlabExternalDbService(properties, new ObjectMapper());

    long firstDelay = service.computeExternalQueryRetryDelayMs(1);
    long secondDelay = service.computeExternalQueryRetryDelayMs(2);
    long cappedDelay = service.computeExternalQueryRetryDelayMs(4);

    assertThat(firstDelay).isBetween(1000L, 1500L);
    assertThat(secondDelay).isBetween(2000L, 2500L);
    assertThat(cappedDelay).isBetween(2500L, 2500L);
  }

  @Test
  void shouldNotRetrySqlErrorsFromSourceDatabase() {
    GitlabMirrorProperties properties = new GitlabMirrorProperties();
    properties.setExternalQueryRetryAttempts(3);
    properties.setExternalQueryRetryDelayMs(0);
    service = new GitlabExternalDbService(properties, new ObjectMapper());
    AtomicInteger attempts = new AtomicInteger();

    assertThatThrownBy(() -> service.executeExternalQueryWithRetry("test query", () -> {
      attempts.incrementAndGet();
      throw new BizException("ERROR: relation \"missing_table\" does not exist");
    })).isInstanceOf(BizException.class);

    assertThat(attempts).hasValue(1);
  }

  @Test
  void shouldNormalizeSqlArrayValuesToDetachedJavaList() throws Exception {
    StubSqlArray sqlArray = new StubSqlArray(new Object[] {"a", 1L, new Object[] {"x", "y"}});

    Object normalized = service.normalizeJdbcValue(sqlArray);

    assertThat(normalized).isInstanceOf(List.class);
    List<?> items = (List<?>) normalized;
    assertThat(items).hasSize(3);
    assertThat(items.get(0)).isEqualTo("a");
    assertThat(items.get(1)).isEqualTo(1L);
    assertThat(items.get(2)).isInstanceOf(List.class);
    @SuppressWarnings("unchecked")
    List<Object> nested = (List<Object>) items.get(2);
    assertThat(nested).containsExactly("x", "y");
    assertThat(sqlArray.freed).isTrue();
  }

  @Test
  void shouldNormalizePostgresSpecificObjectsToRawValues() throws Exception {
    PGobject inet = new PGobject();
    inet.setType("inet");
    inet.setValue("172.18.0.1");
    PGobject searchVector = new PGobject();
    searchVector.setType("tsvector");
    searchVector.setValue("'sample':1 'vector':2");

    assertThat(service.normalizeJdbcValue(inet)).isEqualTo("172.18.0.1");
    assertThat(service.normalizeJdbcValue(searchVector)).isEqualTo("'sample':1 'vector':2");
  }

  @Test
  void shouldNormalizeSqlXmlValuesToDetachedString() throws Exception {
    StubSqlXml xml = new StubSqlXml("<root><value>ok</value></root>");

    Object normalized = service.normalizeJdbcValue(xml);

    assertThat(normalized).isEqualTo("<root><value>ok</value></root>");
    assertThat(xml.freed).isTrue();
  }

  private static final class StubSqlArray implements java.sql.Array {
    private final Object value;
    private boolean freed;

    private StubSqlArray(Object value) {
      this.value = value;
    }

    @Override
    public String getBaseTypeName() throws SQLException {
      throw new UnsupportedOperationException();
    }

    @Override
    public int getBaseType() throws SQLException {
      throw new UnsupportedOperationException();
    }

    @Override
    public Object getArray() throws SQLException {
      return value;
    }

    @Override
    public Object getArray(Map<String, Class<?>> map) throws SQLException {
      throw new UnsupportedOperationException();
    }

    @Override
    public Object getArray(long index, int count) throws SQLException {
      throw new UnsupportedOperationException();
    }

    @Override
    public Object getArray(long index, int count, Map<String, Class<?>> map) throws SQLException {
      throw new UnsupportedOperationException();
    }

    @Override
    public ResultSet getResultSet() throws SQLException {
      throw new UnsupportedOperationException();
    }

    @Override
    public ResultSet getResultSet(Map<String, Class<?>> map) throws SQLException {
      throw new UnsupportedOperationException();
    }

    @Override
    public ResultSet getResultSet(long index, int count) throws SQLException {
      throw new UnsupportedOperationException();
    }

    @Override
    public ResultSet getResultSet(long index, int count, Map<String, Class<?>> map) throws SQLException {
      throw new UnsupportedOperationException();
    }

    @Override
    public void free() throws SQLException {
      freed = true;
    }
  }

  private static final class StubSqlXml implements SQLXML {
    private final String value;
    private boolean freed;

    private StubSqlXml(String value) {
      this.value = value;
    }

    @Override
    public void free() throws SQLException {
      freed = true;
    }

    @Override
    public String getString() throws SQLException {
      return value;
    }

    @Override
    public void setString(String value) throws SQLException {
      throw new UnsupportedOperationException();
    }

    @Override
    public java.io.InputStream getBinaryStream() throws SQLException {
      throw new UnsupportedOperationException();
    }

    @Override
    public java.io.OutputStream setBinaryStream() throws SQLException {
      throw new UnsupportedOperationException();
    }

    @Override
    public java.io.Reader getCharacterStream() throws SQLException {
      throw new UnsupportedOperationException();
    }

    @Override
    public java.io.Writer setCharacterStream() throws SQLException {
      throw new UnsupportedOperationException();
    }

    @Override
    public <T extends javax.xml.transform.Source> T getSource(Class<T> sourceClass) throws SQLException {
      throw new UnsupportedOperationException();
    }

    @Override
    public <T extends javax.xml.transform.Result> T setResult(Class<T> resultClass) throws SQLException {
      throw new UnsupportedOperationException();
    }
  }
}
