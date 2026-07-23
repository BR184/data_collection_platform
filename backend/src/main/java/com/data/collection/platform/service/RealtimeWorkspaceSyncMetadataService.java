package com.data.collection.platform.service;

import com.data.collection.platform.common.JsonUtils;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.statistics.StatisticFilterCondition;
import com.data.collection.platform.entity.statistics.StatisticFilterGroup;
import com.fasterxml.jackson.core.type.TypeReference;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class RealtimeWorkspaceSyncMetadataService {
  private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");
  private static final List<String> SCHEDULED_TIME_COLLECTIONS =
      List.of("scheduledTimeRecord", "ScheduledTimeRecord");
  private static final int SCHEDULED_TIME_MONGO_TIMEOUT_MS = 1200;

  private final GitlabConfigService configService;
  private final CodeReviewMatchModeConfigService matchModeConfigService;
  private final JsonUtils jsonUtils;
  private final JdbcTemplate jdbcTemplate;

  public RealtimeWorkspaceSyncMetadataService(
      GitlabConfigService configService,
      CodeReviewMatchModeConfigService matchModeConfigService,
      JsonUtils jsonUtils,
      JdbcTemplate jdbcTemplate) {
    this.configService = configService;
    this.matchModeConfigService = matchModeConfigService;
    this.jsonUtils = jsonUtils;
    this.jdbcTemplate = jdbcTemplate;
  }

  /**
   * 解析工作区当前可展示事实版本对应的同步时间。
   *
   * <p>客户问题直接以本地成功 ISSUE 事实构建为准，其他仍需老平台对齐的工作区按各自历史来源回退。
   *
   * @param workspaceKey 页面工作区稳定标识
   * @param filters 页面当前筛选，用于解析阶段等历史任务维度
   * @return 可展示的同步元数据；没有已完成记录时三个时间均为空
   */
  public RealtimeWorkspaceSyncMetadata resolve(String workspaceKey, Map<String, String> filters) {
    if (isCustomerIssueWorkspace(workspaceKey)) {
      RealtimeWorkspaceSyncMetadata metadata = loadCustomerIssueFactMetadata();
      if (metadata != null) {
        return metadata;
      }
      return new RealtimeWorkspaceSyncMetadata(null, null, null);
    }
    ScheduledTimeLookup lookup = scheduledTimeLookup(workspaceKey, filters == null ? Map.of() : filters);
    if (lookup != null) {
      RealtimeWorkspaceSyncMetadata metadata = loadScheduledTimeMetadata(lookup);
      if (metadata != null) {
        return metadata;
      }
    }
    LocalDateTime lastSyncedAt = resolveGitlabLastSyncedAt();
    return new RealtimeWorkspaceSyncMetadata(lastSyncedAt, null, null);
  }

  private ScheduledTimeLookup scheduledTimeLookup(String workspaceKey, Map<String, String> filters) {
    if ("code-review-illegal-records".equals(workspaceKey)) {
      String source = text(filters.get("source"));
      if ("dgm".equalsIgnoreCase(source)) {
        return new ScheduledTimeLookup("mergeRequest", "DGM", "79");
      }
      return new ScheduledTimeLookup("mergeRequest", "CrownCAD", "9");
    }
    if (isSystemTestWorkspace(workspaceKey)) {
      String testingPhase = firstText(filters.get("testingPhase"), filterGroupValue(filters.get("filterGroup"), "testingPhase"));
      if (!StringUtils.hasText(testingPhase)) {
        return null;
      }
      if (testingPhase.contains(",")) {
        return null;
      }
      return new ScheduledTimeLookup("issue", testingPhase, "9");
    }
    return null;
  }

  private RealtimeWorkspaceSyncMetadata loadCustomerIssueFactMetadata() {
    GitlabSyncConfig config = configService.getConfig();
    if (config == null) {
      return null;
    }
    String sourceInstance = GitlabSourceInstanceSupport.sourceInstanceOf(config);
    List<RealtimeWorkspaceSyncMetadata> rows = queryCustomerIssueFactMetadata(sourceInstance);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  private List<RealtimeWorkspaceSyncMetadata> queryCustomerIssueFactMetadata(String sourceInstance) {
    return jdbcTemplate.query(
        """
        select task.started_at, task.finished_at
          from fact_build_tasks task
         where task.status = 'SUCCESS'
           and upper(coalesce(task.fact_type, '')) = 'ISSUE'
           and task.source_instance = ?
         order by task.finished_at desc nulls last, task.id desc
         limit 1
        """,
        (resultSet, rowNum) -> {
          LocalDateTime startedAt = resultSet.getTimestamp("started_at") == null
              ? null
              : resultSet.getTimestamp("started_at").toLocalDateTime();
          LocalDateTime finishedAt = resultSet.getTimestamp("finished_at") == null
              ? null
              : resultSet.getTimestamp("finished_at").toLocalDateTime();
          return new RealtimeWorkspaceSyncMetadata(
              finishedAt == null ? startedAt : finishedAt,
              startedAt,
              finishedAt);
        },
        sourceInstance);
  }

  private boolean isSystemTestWorkspace(String workspaceKey) {
    return "system-test-defect-summary".equals(workspaceKey)
        || "system-test-defect-cause".equals(workspaceKey)
        || "system-test-delay-analysis".equals(workspaceKey)
        || "system-test-phase-statistics".equals(workspaceKey)
        || "system-test-illegal-records".equals(workspaceKey)
        || "system-test-issues".equals(workspaceKey);
  }

  private boolean isCustomerIssueWorkspace(String workspaceKey) {
    return "customer-issue-defect-summary".equals(workspaceKey)
        || "customer-issue-defect-cause".equals(workspaceKey)
        || "customer-issue-delay-issues".equals(workspaceKey)
        || "customer-issue-response-efficiency".equals(workspaceKey)
        || "customer-issue-by-function".equals(workspaceKey)
        || "customer-issue-illegal-records".equals(workspaceKey)
        || "customer-issue-delay-records".equals(workspaceKey)
        || "customer-issue-cc-product-records".equals(workspaceKey);
  }

  private RealtimeWorkspaceSyncMetadata loadScheduledTimeMetadata(ScheduledTimeLookup lookup) {
    CodeReviewMatchModeConfig config = matchModeConfigService.loadConfig();
    if (!StringUtils.hasText(config.mongoUri()) || !StringUtils.hasText(config.mongoDatabase())) {
      return null;
    }
    try (MongoClient client = MongoClients.create(scheduledTimeMongoSettings(config.mongoUri()))) {
      MongoDatabase database = client.getDatabase(config.mongoDatabase());
      for (String collectionName : SCHEDULED_TIME_COLLECTIONS) {
        MongoCollection<Document> collection = database.getCollection(collectionName);
        Document record = collection.find(scheduledTimeQuery(lookup))
            .sort(Sorts.descending("startTime"))
            .limit(1)
            .first();
        if (record != null) {
          LocalDateTime startedAt = localDateTime(record.get("startTime"));
          LocalDateTime finishedAt = localDateTime(record.get("endTime"));
          Long usedMinutes = usedMinutes(record.get("usedTime"));
          if (startedAt != null
              && usedMinutes != null
              && usedMinutes > 0
              && (finishedAt == null || !finishedAt.isAfter(startedAt))) {
            finishedAt = startedAt.plusMinutes(usedMinutes);
          }
          return new RealtimeWorkspaceSyncMetadata(finishedAt != null ? finishedAt : startedAt, startedAt, finishedAt);
        }
      }
    } catch (RuntimeException ignored) {
      return null;
    }
    return null;
  }

  private MongoClientSettings scheduledTimeMongoSettings(String mongoUri) {
    return MongoClientSettings.builder()
        .applyConnectionString(new ConnectionString(mongoUri))
        .applyToClusterSettings(builder ->
            builder.serverSelectionTimeout(SCHEDULED_TIME_MONGO_TIMEOUT_MS, TimeUnit.MILLISECONDS))
        .applyToSocketSettings(builder -> builder
            .connectTimeout(SCHEDULED_TIME_MONGO_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(SCHEDULED_TIME_MONGO_TIMEOUT_MS, TimeUnit.MILLISECONDS))
        .build();
  }

  private Long usedMinutes(Object value) {
    if (value instanceof Number number) {
      return number.longValue();
    }
    if (value instanceof String raw && StringUtils.hasText(raw)) {
      try {
        return Long.parseLong(raw.trim());
      } catch (NumberFormatException ignored) {
        return null;
      }
    }
    return null;
  }

  private Bson scheduledTimeQuery(ScheduledTimeLookup lookup) {
    Bson typeName = Filters.eq("typeName", lookup.typeName());
    Bson typeValue = lookup.typeValue() == null ? Filters.eq("typeValue", null) : Filters.eq("typeValue", lookup.typeValue());
    if (!StringUtils.hasText(lookup.projectId())) {
      return Filters.and(typeName, typeValue);
    }
    return Filters.and(typeName, typeValue, Filters.eq("projectId", lookup.projectId()));
  }

  private String filterGroupValue(String filterGroupJson, String fieldKey) {
    if (!StringUtils.hasText(filterGroupJson)) {
      return "";
    }
    try {
      StatisticFilterGroup group = jsonUtils.fromJson(filterGroupJson, new TypeReference<>() {});
      if (group == null || group.conditions() == null) {
        return "";
      }
      for (StatisticFilterCondition condition : group.conditions()) {
        if (fieldKey.equals(condition.fieldKey()) && condition.value() != null) {
          return text(String.valueOf(condition.value()));
        }
      }
    } catch (RuntimeException ignored) {
      return "";
    }
    return "";
  }

  private LocalDateTime resolveGitlabLastSyncedAt() {
    GitlabSyncConfig config = configService.getConfig();
    if (config == null) {
      return null;
    }
    LocalDateTime incremental = config.getLastIncrementalSyncAt();
    LocalDateTime full = config.getLastFullSyncAt();
    if (incremental == null) {
      return full;
    }
    if (full == null) {
      return incremental;
    }
    return incremental.isAfter(full) ? incremental : full;
  }

  private LocalDateTime localDateTime(Object value) {
    if (value instanceof Date date) {
      return LocalDateTime.ofInstant(date.toInstant(), BEIJING);
    }
    if (value instanceof Instant instant) {
      return LocalDateTime.ofInstant(instant, BEIJING);
    }
    if (value instanceof String raw && StringUtils.hasText(raw)) {
      try {
        return LocalDateTime.ofInstant(Instant.parse(raw), BEIJING);
      } catch (RuntimeException ignored) {
        return null;
      }
    }
    return null;
  }

  private String firstText(String first, String second) {
    String normalized = text(first);
    return StringUtils.hasText(normalized) ? normalized : text(second);
  }

  private String text(String value) {
    return value == null ? "" : value.trim();
  }

  private record ScheduledTimeLookup(String typeName, String typeValue, String projectId) {
  }
}
