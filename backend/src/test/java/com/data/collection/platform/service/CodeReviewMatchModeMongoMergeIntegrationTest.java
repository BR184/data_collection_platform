package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.data.collection.platform.common.JsonUtils;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 兼容模式 Mongo 评审同步的增量合并语义验证：TRUNCATE 全量重填已废除，
 * 每轮同步必须做到——payload 未变的行 id 与 synced_at 完全稳定（快照指纹不漂移）、
 * 变更行原位更新（自增 id 不漂移）、消失行点删、新增行插入。
 */
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CodeReviewMatchModeMongoMergeIntegrationTest {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("qaflex")
          .withUsername("qaflex")
          .withPassword("secret");

  private JdbcTemplate jdbcTemplate;
  private CodeReviewMatchModeMongoReviewSyncService service;

  @BeforeAll
  void setUp() {
    DriverManagerDataSource dataSource =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
    jdbcTemplate = new JdbcTemplate(dataSource);
    JsonUtils jsonUtils = mock(JsonUtils.class);
    when(jsonUtils.toJson(any())).thenReturn("[]");
    service =
        new CodeReviewMatchModeMongoReviewSyncService(
            mock(CodeReviewMatchModeConfigService.class),
            jdbcTemplate,
            jsonUtils,
            mock(TransactionTemplate.class));
  }

  @Test
  void reportMergeKeepsIdAndSyncedAtStableForUnchangedDocuments() throws InterruptedException {
    service.replaceReviewReports(List.of(report("aaa", "评审一"), report("bbb", "评审二")));
    Map<String, Object> firstRoundAaa = reportRow(storageKey("aaa"));
    Map<String, Object> firstRoundBbb = reportRow(storageKey("bbb"));

    Thread.sleep(20);
    service.replaceReviewReports(
        List.of(report("aaa", "评审一"), report("bbb", "评审二-改"), report("ccc", "评审三")));

    Map<String, Object> secondRoundAaa = reportRow(storageKey("aaa"));
    assertThat(secondRoundAaa.get("id")).isEqualTo(firstRoundAaa.get("id"));
    assertThat(secondRoundAaa.get("synced_at")).isEqualTo(firstRoundAaa.get("synced_at"));
    Map<String, Object> secondRoundBbb = reportRow(storageKey("bbb"));
    assertThat(secondRoundBbb.get("id")).isEqualTo(firstRoundBbb.get("id"));
    assertThat((String) secondRoundBbb.get("title")).isEqualTo("评审二-改");
    assertThat((Timestamp) secondRoundBbb.get("synced_at"))
        .isAfter((Timestamp) firstRoundBbb.get("synced_at"));
    assertThat(reportRow(storageKey("ccc"))).isNotNull();
  }

  @Test
  void reportMergeDeletesRowsMissingFromCurrentRound() {
    service.replaceReviewReports(List.of(report("dad", "评审四"), report("eba", "评审五")));

    service.replaceReviewReports(List.of(report("dad", "评审四")));

    assertThat(reportRow(storageKey("dad"))).isNotNull();
    assertThat(reportRow(storageKey("eba"))).isNull();
  }

  @Test
  void contentMergeKeepsCompositeKeyRowsInPlaceAndRemovesVanishedOrders() throws InterruptedException {
    service.replaceReviewContents(
        List.of(reportWithContents("aca", List.of("内容甲", "内容乙", "内容丁"))));
    Map<String, Object> firstRoundOrderZero = contentRow(storageKey("aca"), 0);
    assertThat(firstRoundOrderZero).isNotNull();
    assertThat(contentRow(storageKey("aca"), 1)).isNotNull();
    assertThat(contentRow(storageKey("aca"), 2)).isNotNull();

    Thread.sleep(20);
    service.replaceReviewContents(
        List.of(reportWithContents("aca", List.of("内容甲-改", "内容丙"))));

    Map<String, Object> orderZero = contentRow(storageKey("aca"), 0);
    assertThat((String) orderZero.get("assignment_content")).isEqualTo("内容甲-改");
    assertThat(orderZero.get("id")).isEqualTo(firstRoundOrderZero.get("id"));
    assertThat((String) contentRow(storageKey("aca"), 1).get("assignment_content"))
        .isEqualTo("内容丙");
    assertThat(contentRow(storageKey("aca"), 2)).isNull();
  }

  @Test
  void rawDocumentMergeKeepsUnchangedPayloadsUntouched() throws InterruptedException {
    service.replaceRawDocuments("reviewReport", List.of(report("ada", "评审七")));
    Map<String, Object> firstRound = rawDocumentRow(storageKey("ada"));

    Thread.sleep(20);
    service.replaceRawDocuments(
        "reviewReport", List.of(report("ada", "评审七"), report("add", "评审八")));

    assertThat(rawDocumentRow(storageKey("ada")).get("synced_at"))
        .isEqualTo(firstRound.get("synced_at"));
    assertThat(rawDocumentRow(storageKey("add"))).isNotNull();

    service.replaceRawDocuments("reviewReport", List.of(report("add", "评审八")));

    assertThat(rawDocumentRow(storageKey("ada"))).isNull();
    assertThat(rawDocumentRow(storageKey("add"))).isNotNull();
  }

  /** 与同步服务的 documentKey 对齐：无 Mongo _id 语义差异，直接用可复现的十六进制键。 */
  private String storageKey(String hexPrefix) {
    return hexPrefix + "0".repeat(21);
  }

  private Document report(String hexPrefix, String title) {
    Document document = new Document();
    document.put("_id", new ObjectId(storageKey(hexPrefix)));
    document.put("projectName", "示例项目");
    document.put("title", title);
    document.put("moduleName", "示例模块");
    document.put("reviewTime", "2026/9/1 10:00:00");
    document.put("reviewCharger", "张三");
    document.put("createTime", "2026/9/1 09:00:00");
    return document;
  }

  private Document reportWithContents(String hexPrefix, List<String> contents) {
    Document document = report(hexPrefix, "带内容评审");
    List<Document> contentDocuments =
        contents.stream()
            .map(name -> new Document(Map.of("name", name, "content", name)))
            .toList();
    document.put("contents", contentDocuments);
    return document;
  }

  private Map<String, Object> reportRow(String legacyId) {
    return queryRow(
        "select id, title, synced_at from review_data_match_mode_reports where legacy_id = ?",
        legacyId);
  }

  private Map<String, Object> contentRow(String reportLegacyId, int contentOrder) {
    return queryRow(
        """
        select id, assignment_content
          from review_data_match_mode_contents
         where match_mode_report_legacy_id = ? and content_order = ?
        """,
        reportLegacyId,
        contentOrder);
  }

  private Map<String, Object> rawDocumentRow(String documentKey) {
    return queryRow(
        """
        select synced_at
          from legacy_mongo_imported_documents
         where collection_name = 'reviewReport' and document_key = ?
        """,
        documentKey);
  }

  private Map<String, Object> queryRow(String sql, Object... args) {
    List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, args);
    return rows.isEmpty() ? null : rows.getFirst();
  }
}
