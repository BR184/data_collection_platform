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
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class RealtimeWorkspaceSyncMetadataService {
  private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");
  private static final List<String> SCHEDULED_TIME_COLLECTIONS =
      List.of("scheduledTimeRecord", "ScheduledTimeRecord");

  private final GitlabConfigService configService;
  private final CodeReviewMatchModeConfigService matchModeConfigService;
  private final JsonUtils jsonUtils;

  public RealtimeWorkspaceSyncMetadataService(
      GitlabConfigService configService,
      CodeReviewMatchModeConfigService matchModeConfigService,
      JsonUtils jsonUtils) {
    this.configService = configService;
    this.matchModeConfigService = matchModeConfigService;
    this.jsonUtils = jsonUtils;
  }

  public RealtimeWorkspaceSyncMetadata resolve(String workspaceKey, Map<String, String> filters) {
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
    if (isCustomerIssueWorkspace(workspaceKey)) {
      return new ScheduledTimeLookup("issue", "CCProduct", "325");
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
    try (MongoClient client = MongoClients.create(config.mongoUri())) {
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
