package com.data.collection.platform.service;

import com.data.collection.platform.common.JsonUtils;
import com.data.collection.platform.entity.CodeReviewMatchModeDbSettingsSaveRequest;
import com.data.collection.platform.entity.CodeReviewMatchModeSyncResponse;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

@Service
@Slf4j
public class CodeReviewMatchModeMongoReviewSyncService {
  private static final String RAW_TARGET_TABLE = "legacy_mongo_imported_documents";
  private static final String REVIEW_REPORT_TABLE = "review_data_match_mode_reports";
  private static final String REVIEW_PROBLEM_TABLE = "review_data_match_mode_problem_details";
  private static final DateTimeFormatter LEGACY_DATE_TIME =
      new DateTimeFormatterBuilder()
          .appendPattern("d/M/yyyy H:mm:ss")
          .optionalStart()
          .appendFraction(ChronoField.MILLI_OF_SECOND, 1, 3, true)
          .optionalEnd()
          .toFormatter();
  private static final DateTimeFormatter LEGACY_DATE = DateTimeFormatter.ofPattern("d/M/yyyy");
  private static final DateTimeFormatter STANDARD_DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
  private static final DateTimeFormatter STANDARD_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

  private final CodeReviewMatchModeConfigService configService;
  private final JdbcTemplate jdbcTemplate;
  private final JsonUtils jsonUtils;
  private final TransactionTemplate transactionTemplate;

  public CodeReviewMatchModeMongoReviewSyncService(
      CodeReviewMatchModeConfigService configService,
      JdbcTemplate jdbcTemplate,
      JsonUtils jsonUtils,
      TransactionTemplate transactionTemplate) {
    this.configService = configService;
    this.jdbcTemplate = jdbcTemplate;
    this.jsonUtils = jsonUtils;
    this.transactionTemplate = transactionTemplate;
  }

  //兼容模式-MatchMode
  public CodeReviewMatchModeSyncResponse syncNow(CodeReviewMatchModeDbSettingsSaveRequest request) {
    CodeReviewMatchModeConfig config = configService.loadConfig(request);
    if (!StringUtils.hasText(config.mongoUri()) || !StringUtils.hasText(config.mongoDatabase())) {
      markFailed("兼容模式老平台 MongoDB 配置缺失");
      return currentState(false, "兼容模式老平台 MongoDB 配置缺失");
    }
    markRunning();
    try {
      ImportSummary summary = loadMongo(config);
      replaceSnapshots(config, summary);
      markSuccess(summary);
      return currentState(true, "兼容模式已导入老平台 MongoDB 评审数据");
    } catch (Exception error) {
      log.warn("Code review match mode Mongo review sync failed", error);
      markFailed(rootMessage(error, "兼容模式老平台 MongoDB 评审数据导入失败"));
      return currentState(false, rootMessage(error, "兼容模式老平台 MongoDB 评审数据导入失败"));
    }
  }

  private ImportSummary loadMongo(CodeReviewMatchModeConfig config) {
    ImportSummary summary = new ImportSummary();
    try (MongoClient client = MongoClients.create(config.mongoUri())) {
      MongoDatabase database = client.getDatabase(config.mongoDatabase());
      database.runCommand(new Document("ping", 1));
      for (String collectionName : config.selectedMongoCollectionNames()) {
        MongoCollection<Document> collection = database.getCollection(collectionName);
        List<Document> documents = collection.find().batchSize(Math.max(1, config.mysqlFetchSize())).into(new ArrayList<>());
        summary.add(collectionName, documents);
      }
    }
    return summary;
  }

  private void replaceSnapshots(CodeReviewMatchModeConfig config, ImportSummary summary) {
    transactionTemplate.executeWithoutResult(status -> {
      for (Map.Entry<String, List<Document>> entry : summary.documentsByCollection().entrySet()) {
        replaceRawDocuments(entry.getKey(), entry.getValue());
      }
      if (summary.documentsByCollection().containsKey(config.reviewReportCollectionName())) {
        replaceReviewReports(summary.documentsByCollection().get(config.reviewReportCollectionName()));
      }
      if (summary.documentsByCollection().containsKey(config.reviewProblemCollectionName())) {
        replaceReviewProblems(summary.documentsByCollection().get(config.reviewProblemCollectionName()));
      }
    });
  }

  private void replaceRawDocuments(String collectionName, List<Document> documents) {
    jdbcTemplate.update("delete from " + RAW_TARGET_TABLE + " where collection_name = ?", collectionName);
    String sql = """
        insert into legacy_mongo_imported_documents (
          collection_name, document_key, raw_payload, synced_at
        ) values (
          ?, ?, ?::jsonb, current_timestamp
        )
        """;
    List<Object[]> batch = new ArrayList<>();
    for (int index = 0; index < documents.size(); index++) {
      Document document = documents.get(index);
      batch.add(new Object[] {collectionName, documentKey(collectionName, document, index + 1), document.toJson()});
      if (batch.size() >= 500) {
        jdbcTemplate.batchUpdate(sql, batch);
        batch.clear();
      }
    }
    if (!batch.isEmpty()) {
      jdbcTemplate.batchUpdate(sql, batch);
    }
    jdbcTemplate.update("""
        insert into legacy_mongo_imported_collections (
          collection_name, record_count, last_synced_at, synced_at
        ) values (
          ?, ?, current_timestamp, current_timestamp
        )
        on conflict (collection_name) do update
           set record_count = excluded.record_count,
               last_synced_at = excluded.last_synced_at,
               synced_at = current_timestamp
        """,
        collectionName,
        documents.size());
  }

  private void replaceReviewReports(List<Document> documents) {
    jdbcTemplate.execute("truncate table " + REVIEW_REPORT_TABLE);
    String sql = """
        insert into review_data_match_mode_reports (
          legacy_id, project_name, title, module_name, source_type, doc_type, review_type_str,
          review_time, review_charger, review_experts, defect_value, defect_count_sum,
          review_defect_density, weighted_defect_density, review_efficiency, review_rate,
          doc_specification, integrity, functionality, feasibility, not_reach_stand_cause,
          problem_detail_ids, description_ids, content_ids, create_time, raw_payload, synced_at
        ) values (
          ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb,
          current_timestamp
        )
        """;
    List<Object[]> batch = new ArrayList<>();
    for (int index = 0; index < documents.size(); index++) {
      Document document = documents.get(index);
      batch.add(new Object[] {
          documentKey("reviewReport", document, index + 1),
          text(document, "projectName"),
          text(document, "title"),
          ReviewDataModuleNameSupport.normalize(text(document, "moduleName")),
          text(document, "sourceType"),
          text(document, "docType"),
          text(document, "reviewTypeStr"),
          localDateTime(document.get("reviewTime")),
          text(document, "reviewCharger"),
          listText(document, "reviewExperts"),
          intValue(document.get("defectValue")),
          intValue(document.get("defectCountSum")),
          decimal(document.get("reviewDefectDensity")),
          decimal(document.get("weightedDefectDensity")),
          decimal(document.get("reviewEfficiency")),
          decimal(document.get("reviewRate")),
          intValue(document.get("docSpecification")),
          intValue(document.get("integrity")),
          intValue(document.get("functionality")),
          intValue(document.get("feasibility")),
          text(document, "notReachStandCause"),
          listText(document, "problemDetailIds"),
          listText(document, "descriptionIds"),
          listText(document, "contentIds"),
          localDateTime(document.get("createTime")),
          document.toJson()
      });
      if (batch.size() >= 500) {
        jdbcTemplate.batchUpdate(sql, batch);
        batch.clear();
      }
    }
    if (!batch.isEmpty()) {
      jdbcTemplate.batchUpdate(sql, batch);
    }
  }

  private void replaceReviewProblems(List<Document> documents) {
    jdbcTemplate.execute("truncate table " + REVIEW_PROBLEM_TABLE);
    String sql = """
        insert into review_data_match_mode_problem_details (
          legacy_id, reviewer, workload, review_type, position, problem_type, description, suggestion,
          liable_person, reason_for_not_accepting, problem_status, close_time, create_time, update_time,
          raw_payload, synced_at
        ) values (
          ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, current_timestamp
        )
        """;
    List<Object[]> batch = new ArrayList<>();
    for (int index = 0; index < documents.size(); index++) {
      Document document = documents.get(index);
      batch.add(new Object[] {
          documentKey("problemDetail", document, index + 1),
          text(document, "reviewer"),
          decimal(document.get("workload")),
          text(document, "reviewType"),
          text(document, "position"),
          text(document, "problemType"),
          text(document, "description"),
          text(document, "suggestion"),
          text(document, "liablePerson"),
          text(document, "reasonForNotAccepting"),
          text(document, "problemStatus"),
          localDate(document.get("closeTime")),
          localDateTime(document.get("createTime")),
          localDate(document.get("updateTime")),
          document.toJson()
      });
      if (batch.size() >= 500) {
        jdbcTemplate.batchUpdate(sql, batch);
        batch.clear();
      }
    }
    if (!batch.isEmpty()) {
      jdbcTemplate.batchUpdate(sql, batch);
    }
  }

  private String listText(Document document, String fieldName) {
    Object value = document.get(fieldName);
    if (value instanceof Iterable<?> iterable) {
      List<String> values = new ArrayList<>();
      for (Object item : iterable) {
        String normalized = objectIdText(item);
        if (normalized != null) {
          values.add(normalized);
        }
      }
      return values.isEmpty() ? null : jsonUtils.toJson(values);
    }
    return textValue(value);
  }

  private String text(Document document, String fieldName) {
    return textValue(document.get(fieldName));
  }

  private String textValue(Object value) {
    String normalized = objectIdText(value);
    if (normalized == null) {
      return null;
    }
    return "null".equalsIgnoreCase(normalized) ? null : normalized;
  }

  private String documentKey(String collectionName, Document document, int rowIndex) {
    String id = objectIdText(document.get("_id"));
    if (id != null) {
      return id;
    }
    return collectionName + "-row-" + rowIndex + "-" + Integer.toHexString(document.toJson().hashCode());
  }

  private String objectIdText(Object value) {
    if (value == null) {
      return null;
    }
    String text;
    if (value instanceof ObjectId objectId) {
      text = objectId.toHexString();
    } else {
      text = String.valueOf(value);
    }
    text = TextQuerySupport.trimToNull(text);
    return text == null || "null".equalsIgnoreCase(text) ? null : text;
  }

  private LocalDateTime localDateTime(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof LocalDateTime dateTime) {
      return dateTime;
    }
    if (value instanceof Date date) {
      return LocalDateTime.ofInstant(date.toInstant(), ZoneId.systemDefault());
    }
    String text = textValue(value);
    if (text == null) {
      return null;
    }
    String normalized = text.replace('T', ' ');
    for (DateTimeFormatter formatter : List.of(LEGACY_DATE_TIME, STANDARD_DATE_TIME)) {
      try {
        return LocalDateTime.parse(trimToFormatterLength(normalized, formatter), formatter);
      } catch (DateTimeParseException ignored) {
        // Try the next supported legacy format.
      }
    }
    for (DateTimeFormatter formatter : List.of(LEGACY_DATE, STANDARD_DATE)) {
      try {
        return LocalDate.parse(normalized, formatter).atStartOfDay();
      } catch (DateTimeParseException ignored) {
        // Try the next date-only format.
      }
    }
    try {
      return LocalDate.parse(normalized.substring(0, Math.min(10, normalized.length()))).atStartOfDay();
    } catch (RuntimeException ignored) {
      return null;
    }
  }

  private LocalDate localDate(Object value) {
    LocalDateTime dateTime = localDateTime(value);
    return dateTime == null ? null : dateTime.toLocalDate();
  }

  private String trimToFormatterLength(String value, DateTimeFormatter formatter) {
    if (formatter == STANDARD_DATE_TIME && value.length() > 19) {
      return value.substring(0, 19);
    }
    return value;
  }

  private Integer intValue(Object value) {
    BigDecimal decimal = decimal(value);
    return decimal == null ? null : decimal.intValue();
  }

  private BigDecimal decimal(Object value) {
    String text = textValue(value);
    if (text == null) {
      return null;
    }
    try {
      return new BigDecimal(text);
    } catch (NumberFormatException ignored) {
      return null;
    }
  }

  private void markRunning() {
    jdbcTemplate.update("""
        update code_review_match_mode_sync_state
           set status = 'RUNNING', message = '兼容模式正在导入老平台 MongoDB 评审数据',
               started_at = current_timestamp, finished_at = null, updated_at = current_timestamp
         where id = 1
        """);
  }

  private void markSuccess(ImportSummary summary) {
    jdbcTemplate.update("""
        update code_review_match_mode_sync_state
           set status = 'SUCCESS', message = ?, record_count = ?, finished_at = current_timestamp,
               updated_at = current_timestamp
         where id = 1
        """, "兼容模式已导入老平台 MongoDB 评审数据：" + summary.describe(), summary.totalCount());
  }

  private void markFailed(String message) {
    jdbcTemplate.update("""
        update code_review_match_mode_sync_state
           set status = 'FAILED', message = ?, finished_at = current_timestamp, updated_at = current_timestamp
         where id = 1
        """, message == null ? "兼容模式老平台 MongoDB 评审数据导入失败" : message);
  }

  private CodeReviewMatchModeSyncResponse currentState(boolean accepted, String fallbackMessage) {
    return jdbcTemplate.queryForObject("""
        select status, message, record_count, started_at, finished_at
          from code_review_match_mode_sync_state
         where id = 1
        """, (rs, rowNum) ->
        new CodeReviewMatchModeSyncResponse(
            accepted,
            rs.getString("status"),
            rs.getString("message") == null ? fallbackMessage : rs.getString("message"),
            rs.getLong("record_count"),
            rs.getTimestamp("started_at") == null ? null : rs.getTimestamp("started_at").toLocalDateTime(),
            rs.getTimestamp("finished_at") == null ? null : rs.getTimestamp("finished_at").toLocalDateTime()));
  }

  private String rootMessage(Throwable error, String fallbackMessage) {
    Throwable cursor = error;
    while (cursor.getCause() != null) {
      cursor = cursor.getCause();
    }
    String message = cursor.getMessage();
    return message == null || message.isBlank()
        ? fallbackMessage
        : message.trim().replace('\n', ' ').replace('\r', ' ');
  }

  private static final class ImportSummary {
    private final Map<String, List<Document>> documentsByCollection = new LinkedHashMap<>();

    void add(String collectionName, List<Document> documents) {
      documentsByCollection.put(collectionName, List.copyOf(documents));
    }

    Map<String, List<Document>> documentsByCollection() {
      return documentsByCollection;
    }

    long totalCount() {
      long total = 0L;
      for (List<Document> documents : documentsByCollection.values()) {
        total += documents.size();
      }
      return total;
    }

    String describe() {
      if (documentsByCollection.isEmpty()) {
        return "未选择 MongoDB 集合";
      }
      List<String> parts = new ArrayList<>();
      for (Map.Entry<String, List<Document>> entry : documentsByCollection.entrySet()) {
        parts.add(entry.getKey() + " " + entry.getValue().size() + " 条");
      }
      return String.join("，", parts);
    }
  }
}
