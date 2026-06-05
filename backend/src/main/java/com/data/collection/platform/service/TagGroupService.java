package com.data.collection.platform.service;

import com.data.collection.platform.entity.TagGroupResponse;
import com.data.collection.platform.entity.TagGroupValueResponse;
import com.data.collection.platform.entity.TagGroupsResponse;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class TagGroupService {
  private static final Duration CACHE_TTL = Duration.ofMinutes(30);

  private final JdbcTemplate jdbcTemplate;
  private volatile CacheSnapshot cache;

  public TagGroupService(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @PostConstruct
  public void loadAtStartup() {
    reload();
  }

  @Scheduled(fixedDelayString = "${platform.tag-groups.cache-ttl-ms:1800000}")
  public void refreshExpiredCache() {
    CacheSnapshot current = cache;
    if (current == null || current.loadedAt().plus(CACHE_TTL).isBefore(Instant.now())) {
      reload();
    }
  }

  public synchronized void reload() {
    List<TagGroupRecord> groups =
        jdbcTemplate.query(
            """
            select id,
                   lower(domain) as domain,
                   group_key,
                   label,
                   selection_mode,
                   sort_order,
                   match_strategy_name
              from tag_group
             where enabled = true
             order by lower(domain), sort_order, group_key
            """,
            this::mapGroup);
    List<TagValueRecord> values =
        jdbcTemplate.query(
            """
            select id,
                   group_id,
                   value_key,
                   label,
                   value_type,
                   sort_order,
                   disabled,
                   unmapped_reason
              from tag_value
             where enabled = true
             order by group_id, sort_order, value_key
            """,
            this::mapValue);
    List<TagValueMappingRecord> mappings =
        jdbcTemplate.query(
            """
            select id,
                   value_id,
                   raw_value,
                   source_instance
              from tag_value_mapping
             where enabled = true
               and lower(coalesce(match_type, 'exact')) <> 'regex'
             order by value_id, source_instance nulls last, raw_value
            """,
            this::mapMapping);
    cache = CacheSnapshot.from(groups, values, mappings, Instant.now());
  }

  public TagGroupsResponse getTagGroups(String domain) {
    CacheSnapshot snapshot = ensureCache();
    String normalizedDomain = normalizeDomain(domain);
    List<TagGroupRecord> groups = snapshot.groupsByDomain().getOrDefault(normalizedDomain, List.of());
    List<TagGroupResponse> responses = new ArrayList<>();
    for (TagGroupRecord group : groups) {
      List<TagGroupValueResponse> valueResponses =
          snapshot.valuesByGroupId().getOrDefault(group.id(), List.of()).stream()
              .map(
                  value ->
                      new TagGroupValueResponse(
                          value.valueKey(),
                          value.label(),
                          value.valueType(),
                          value.sortOrder(),
                          value.disabled(),
                          value.unmappedReason()))
              .toList();
      responses.add(
          new TagGroupResponse(
              group.groupKey(),
              group.label(),
              group.selectionMode(),
              group.sortOrder(),
              group.matchStrategyName(),
              valueResponses));
    }
    return new TagGroupsResponse(normalizedDomain, schemaHash(responses), responses);
  }

  public List<String> resolveMappings(
      String domain, String groupKey, String valueKey, String sourceInstance) {
    CacheSnapshot snapshot = ensureCache();
    String lookupKey =
        mappingLookupKey(normalizeDomain(domain), normalizeKey(groupKey), normalizeKey(valueKey));
    List<TagValueMappingRecord> mappings =
        snapshot.mappingsByDomainGroupAndValue().getOrDefault(lookupKey, List.of());
    String normalizedSource = normalizeSource(sourceInstance);
    List<String> sourceSpecific =
        mappings.stream()
            .filter(mapping -> Objects.equals(normalizeSource(mapping.sourceInstance()), normalizedSource))
            .map(TagValueMappingRecord::rawValue)
            .toList();
    if (!sourceSpecific.isEmpty()) {
      return sourceSpecific;
    }
    return mappings.stream()
        .filter(mapping -> TextQuerySupport.trimToNull(mapping.sourceInstance()) == null)
        .map(TagValueMappingRecord::rawValue)
        .toList();
  }

  private CacheSnapshot ensureCache() {
    CacheSnapshot current = cache;
    if (current == null || current.loadedAt().plus(CACHE_TTL).isBefore(Instant.now())) {
      reload();
      current = cache;
    }
    return current;
  }

  private TagGroupRecord mapGroup(ResultSet rs, int rowNum) throws SQLException {
    return new TagGroupRecord(
        rs.getLong("id"),
        normalizeDomain(rs.getString("domain")),
        normalizeKey(rs.getString("group_key")),
        TextQuerySupport.normalizeDisplay(rs.getString("label")),
        normalizeSelectionMode(rs.getString("selection_mode")),
        rs.getInt("sort_order"),
        TagGroupMatchStrategyRegistry.normalize(rs.getString("match_strategy_name")));
  }

  private TagValueRecord mapValue(ResultSet rs, int rowNum) throws SQLException {
    return new TagValueRecord(
        rs.getLong("id"),
        rs.getLong("group_id"),
        normalizeKey(rs.getString("value_key")),
        TextQuerySupport.normalizeDisplay(rs.getString("label")),
        normalizeValueType(rs.getString("value_type")),
        rs.getInt("sort_order"),
        rs.getBoolean("disabled"),
        TextQuerySupport.trimToNull(rs.getString("unmapped_reason")));
  }

  private TagValueMappingRecord mapMapping(ResultSet rs, int rowNum) throws SQLException {
    return new TagValueMappingRecord(
        rs.getLong("id"),
        rs.getLong("value_id"),
        TextQuerySupport.normalizeDisplay(rs.getString("raw_value")),
        TextQuerySupport.trimToNull(rs.getString("source_instance")));
  }

  private static String normalizeDomain(String value) {
    String normalized = TextQuerySupport.trimToNull(value);
    return normalized == null ? "issue" : normalized.toLowerCase(Locale.ROOT);
  }

  private static String normalizeKey(String value) {
    String normalized = TextQuerySupport.trimToNull(value);
    return normalized == null ? "" : normalized.toLowerCase(Locale.ROOT);
  }

  private static String normalizeSource(String value) {
    String normalized = TextQuerySupport.trimToNull(value);
    return normalized == null ? null : GitlabSourceInstanceSupport.normalizeSourceInstance(normalized);
  }

  private static String normalizeSelectionMode(String value) {
    String normalized = normalizeKey(value);
    return "single".equals(normalized) ? "single" : "multiple";
  }

  private static String normalizeValueType(String value) {
    String normalized = normalizeKey(value);
    return switch (normalized) {
      case "unmapped", "disabled", "raw" -> normalized;
      default -> "standard";
    };
  }

  private static String schemaHash(List<TagGroupResponse> groups) {
    List<String> tokens = new ArrayList<>();
    groups.stream()
        .sorted(Comparator.comparing(TagGroupResponse::groupKey))
        .forEach(
            group ->
                group.values().stream()
                    .sorted(Comparator.comparing(TagGroupValueResponse::valueKey))
                    .forEach(value -> tokens.add(group.groupKey() + ":" + value.valueKey())));
    return sha256(String.join("|", tokens));
  }

  private static String sha256(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
      StringBuilder result = new StringBuilder();
      for (byte item : bytes) {
        result.append(String.format("%02x", item));
      }
      return result.toString();
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is not available", exception);
    }
  }

  private static String mappingLookupKey(String domain, String groupKey, String valueKey) {
    return domain + "\u0000" + groupKey + "\u0000" + valueKey;
  }

  private record TagGroupRecord(
      long id,
      String domain,
      String groupKey,
      String label,
      String selectionMode,
      int sortOrder,
      String matchStrategyName) {}

  private record TagValueRecord(
      long id,
      long groupId,
      String valueKey,
      String label,
      String valueType,
      int sortOrder,
      boolean disabled,
      String unmappedReason) {}

  private record TagValueMappingRecord(
      long id,
      long valueId,
      String rawValue,
      String sourceInstance) {}

  private record CacheSnapshot(
      Map<String, List<TagGroupRecord>> groupsByDomain,
      Map<Long, List<TagValueRecord>> valuesByGroupId,
      Map<String, List<TagValueMappingRecord>> mappingsByDomainGroupAndValue,
      Instant loadedAt) {

    static CacheSnapshot from(
        List<TagGroupRecord> groups,
        List<TagValueRecord> values,
        List<TagValueMappingRecord> mappings,
        Instant loadedAt) {
      Map<Long, TagGroupRecord> groupById = new LinkedHashMap<>();
      Map<String, List<TagGroupRecord>> groupsByDomain = new LinkedHashMap<>();
      for (TagGroupRecord group : groups) {
        groupById.put(group.id(), group);
        groupsByDomain.computeIfAbsent(group.domain(), ignored -> new ArrayList<>()).add(group);
      }

      Map<Long, List<TagValueRecord>> valuesByGroupId = new LinkedHashMap<>();
      Map<Long, TagValueRecord> valueById = new LinkedHashMap<>();
      for (TagValueRecord value : values) {
        valuesByGroupId.computeIfAbsent(value.groupId(), ignored -> new ArrayList<>()).add(value);
        valueById.put(value.id(), value);
      }

      Map<String, List<TagValueMappingRecord>> mappingsByDomainGroupAndValue = new LinkedHashMap<>();
      for (TagValueMappingRecord mapping : mappings) {
        TagValueRecord value = valueById.get(mapping.valueId());
        if (value == null) {
          continue;
        }
        TagGroupRecord group = groupById.get(value.groupId());
        if (group == null) {
          continue;
        }
        mappingsByDomainGroupAndValue
            .computeIfAbsent(
                mappingLookupKey(group.domain(), group.groupKey(), value.valueKey()),
                ignored -> new ArrayList<>())
            .add(mapping);
      }
      return new CacheSnapshot(
          Map.copyOf(groupsByDomain),
          Map.copyOf(valuesByGroupId),
          Map.copyOf(mappingsByDomainGroupAndValue),
          loadedAt);
    }
  }
}
