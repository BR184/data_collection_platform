package com.data.collection.platform.service;

import com.data.collection.platform.common.JsonUtils;
import com.data.collection.platform.entity.ReviewDataProblemItemResponse;
import com.data.collection.platform.entity.ReviewDataRecordDetailResponse;
import com.data.collection.platform.entity.ReviewDataRecordListResponse;
import com.data.collection.platform.entity.ReviewDataRecordRowResponse;
import com.data.collection.platform.entity.statistics.StatisticFilterCondition;
import com.data.collection.platform.entity.statistics.StatisticFilterGroup;
import com.fasterxml.jackson.core.type.TypeReference;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class ReviewDataRecordQueryService {
  private static final Map<String, List<String>> FILTER_OPERATORS =
      Map.ofEntries(
          Map.entry("title", List.of("contains", "eq", "ne", "isEmpty", "isNotEmpty")),
          Map.entry("projectName", List.of("eq", "ne", "isEmpty", "isNotEmpty")),
          Map.entry("moduleName", List.of("eq", "ne", "isEmpty", "isNotEmpty")),
          Map.entry("reviewOwner", List.of("eq", "ne", "isEmpty", "isNotEmpty")),
          Map.entry("reviewType", List.of("eq", "ne", "isEmpty", "isNotEmpty")),
          Map.entry("reviewExpert", List.of("eq", "ne", "isEmpty", "isNotEmpty")),
          Map.entry("problemStatus", List.of("eq", "ne", "isEmpty", "isNotEmpty")),
          Map.entry("reviewScalePages", List.of("eq", "gt", "gte", "lt", "lte", "between")),
          Map.entry("problemCount", List.of("eq", "gt", "gte", "lt", "lte", "between")),
          Map.entry("problemDensity", List.of("eq", "gt", "gte", "lt", "lte", "between")),
          Map.entry("reviewDate", List.of("day", "before", "after", "between")));

  private final ReviewDataRecordPersistenceSupport persistenceSupport;
  private final ReviewDataSummaryService summaryService;
  private final JsonUtils jsonUtils;

  public ReviewDataRecordQueryService(
      ReviewDataRecordPersistenceSupport persistenceSupport,
      ReviewDataSummaryService summaryService,
      JsonUtils jsonUtils) {
    this.persistenceSupport = persistenceSupport;
    this.summaryService = summaryService;
    this.jsonUtils = jsonUtils;
  }

  public ReviewDataRecordListResponse listRecords(
      String keyword,
      String title,
      String projectName,
      String moduleName,
      String reviewOwner,
      String reviewType,
      String problemStatus,
      String reviewExpert,
      String filterGroupJson,
      int page,
      int size,
      String sortField,
      String sortOrder) {
    int safePage = page <= 0 ? 1 : page;
    int safeSize = size <= 0 ? 20 : Math.min(size, 100);
    String safeSortField = normalizeSortField(sortField);
    String safeSortOrder = normalizeSortOrder(sortOrder);

    List<ReviewDataRecordRowResponse> legacyFiltered =
        persistenceSupport.loadRecords(title, projectName, moduleName, reviewOwner, reviewType, problemStatus, reviewExpert);
    List<ReviewDataRecordRowResponse> keywordFiltered =
        legacyFiltered.stream()
            .filter(row -> ReviewDataSearchSupport.matchesKeyword(row, keyword))
            .toList();
    StatisticFilterGroup filterGroup = parseFilterGroup(filterGroupJson);
    Map<Long, List<String>> problemStatusesByRecordId =
        needsField(filterGroup, "problemStatus")
            ? persistenceSupport.loadProblemStatusesByRecordIds(keywordFiltered)
            : Map.of();
    List<ReviewDataRecordRowResponse> filtered =
        keywordFiltered.stream()
            .filter(row -> matchesFilterGroup(row, filterGroup, problemStatusesByRecordId))
            .sorted(buildComparator(safeSortField, safeSortOrder))
            .toList();

    PageSlice<ReviewDataRecordRowResponse> pageSlice =
        PageSliceSupport.slice(filtered, safePage, safeSize);

    return new ReviewDataRecordListResponse(
        pageSlice.records(),
        pageSlice.total(),
        pageSlice.page(),
        pageSlice.size(),
        safeSortField,
        safeSortOrder,
        summaryService.buildSummary(filtered));
  }

  public ReviewDataRecordDetailResponse getRecordDetail(Long recordId) {
    ReviewDataRecordRowResponse record = persistenceSupport.getRecordOrThrow(recordId);
    return new ReviewDataRecordDetailResponse(
        record,
        persistenceSupport.listRecordExperts(recordId),
        persistenceSupport.listProblemItems(recordId));
  }

  public List<ReviewDataProblemItemResponse> listProblemItems(Long recordId) {
    persistenceSupport.assertRecordExists(recordId);
    return persistenceSupport.listProblemItems(recordId);
  }

  private StatisticFilterGroup parseFilterGroup(String filterGroupJson) {
    String normalized = TextQuerySupport.trimToNull(filterGroupJson);
    if (normalized == null) {
      return new StatisticFilterGroup("AND", List.of());
    }
    StatisticFilterGroup parsed = jsonUtils.fromJson(normalized, new TypeReference<>() {});
    if (parsed == null || parsed.conditions() == null || parsed.conditions().isEmpty()) {
      return new StatisticFilterGroup("AND", List.of());
    }
    List<StatisticFilterCondition> conditions =
        parsed.conditions().stream()
            .map(this::normalizeFilterCondition)
            .filter(Objects::nonNull)
            .toList();
    return new StatisticFilterGroup("OR".equalsIgnoreCase(parsed.logic()) ? "OR" : "AND", conditions);
  }

  private StatisticFilterCondition normalizeFilterCondition(StatisticFilterCondition condition) {
    if (condition == null) {
      return null;
    }
    String fieldKey = TextQuerySupport.trimToNull(condition.fieldKey());
    String operator = TextQuerySupport.trimToNull(condition.operator());
    if (fieldKey == null || operator == null || !FILTER_OPERATORS.getOrDefault(fieldKey, List.of()).contains(operator)) {
      return null;
    }
    String value = TextQuerySupport.trimToNull(condition.value());
    String secondaryValue = TextQuerySupport.trimToNull(condition.secondaryValue());
    if (requiresPrimaryValue(operator) && value == null) {
      return null;
    }
    if ("between".equals(operator) && secondaryValue == null) {
      return null;
    }
    return new StatisticFilterCondition(fieldKey, operator, value, secondaryValue);
  }

  private boolean needsField(StatisticFilterGroup filterGroup, String fieldKey) {
    return filterGroup != null
        && filterGroup.conditions() != null
        && filterGroup.conditions().stream().anyMatch(condition -> fieldKey.equals(condition.fieldKey()));
  }

  private boolean matchesFilterGroup(
      ReviewDataRecordRowResponse row,
      StatisticFilterGroup filterGroup,
      Map<Long, List<String>> problemStatusesByRecordId) {
    if (filterGroup == null || filterGroup.conditions() == null || filterGroup.conditions().isEmpty()) {
      return true;
    }
    boolean isOr = "OR".equalsIgnoreCase(filterGroup.logic());
    for (StatisticFilterCondition condition : filterGroup.conditions()) {
      boolean matched = matchesCondition(row, condition, problemStatusesByRecordId);
      if (isOr && matched) {
        return true;
      }
      if (!isOr && !matched) {
        return false;
      }
    }
    return !isOr;
  }

  private boolean matchesCondition(
      ReviewDataRecordRowResponse row,
      StatisticFilterCondition condition,
      Map<Long, List<String>> problemStatusesByRecordId) {
    List<String> values = valuesForField(row, condition.fieldKey(), problemStatusesByRecordId);
    return switch (condition.operator()) {
      case "isEmpty" -> values.stream().allMatch(value -> TextQuerySupport.trimToNull(value) == null);
      case "isNotEmpty" -> values.stream().anyMatch(value -> TextQuerySupport.trimToNull(value) != null);
      case "ne" -> values.stream().noneMatch(value -> equalsIgnoreCase(value, condition.value()));
      case "contains" -> values.stream().anyMatch(value -> ReviewDataSearchSupport.matchesContains(value, condition.value()));
      case "notContains" -> values.stream().noneMatch(value -> ReviewDataSearchSupport.matchesContains(value, condition.value()));
      case "gt", "gte", "lt", "lte", "between" -> values.stream().anyMatch(value -> matchesNumber(value, condition));
      case "day" -> values.stream().anyMatch(value -> Objects.equals(firstDatePart(value), condition.value()));
      case "before" -> values.stream().anyMatch(value -> compareText(firstDatePart(value), firstDatePart(condition.value())) < 0);
      case "after" -> values.stream().anyMatch(value -> compareText(firstDatePart(value), firstDatePart(condition.value())) > 0);
      default -> values.stream().anyMatch(value -> equalsIgnoreCase(value, condition.value()));
    };
  }

  private List<String> valuesForField(
      ReviewDataRecordRowResponse row,
      String fieldKey,
      Map<Long, List<String>> problemStatusesByRecordId) {
    return switch (fieldKey) {
      case "title" -> List.of(Objects.toString(row.title(), ""));
      case "projectName" -> List.of(Objects.toString(row.projectName(), ""));
      case "moduleName" -> List.of(Objects.toString(row.moduleName(), ""));
      case "reviewOwner" -> List.of(Objects.toString(row.reviewOwner(), ""));
      case "reviewType" -> List.of(Objects.toString(row.reviewType(), ""));
      case "reviewExpert" -> splitMultiValue(row.reviewExpertsSummary());
      case "problemStatus" -> problemStatusesByRecordId.getOrDefault(row.id(), List.of());
      case "reviewScalePages" -> List.of(Objects.toString(row.reviewScalePages(), ""));
      case "problemCount" -> List.of(Objects.toString(row.problemCount(), ""));
      case "problemDensity" -> List.of(Objects.toString(row.problemDensity(), ""));
      case "reviewDate" -> List.of(row.reviewDate() == null ? "" : row.reviewDate().toString());
      default -> List.of();
    };
  }

  private boolean matchesNumber(String value, StatisticFilterCondition condition) {
    Double left = parseDouble(value);
    Double right = parseDouble(condition.value());
    Double secondary = parseDouble(condition.secondaryValue());
    if (left == null || right == null) {
      return false;
    }
    return switch (condition.operator()) {
      case "gt" -> left > right;
      case "gte" -> left >= right;
      case "lt" -> left < right;
      case "lte" -> left <= right;
      case "between" -> secondary != null && left >= Math.min(right, secondary) && left <= Math.max(right, secondary);
      default -> Double.compare(left, right) == 0;
    };
  }

  private List<String> splitMultiValue(String value) {
    String normalized = TextQuerySupport.trimToNull(value);
    if (normalized == null) {
      return List.of();
    }
    return java.util.Arrays.stream(normalized.split("[、，]"))
        .map(TextQuerySupport::trimToNull)
        .filter(Objects::nonNull)
        .toList();
  }

  private boolean equalsIgnoreCase(String left, String right) {
    String safeLeft = TextQuerySupport.trimToNull(left);
    String safeRight = TextQuerySupport.trimToNull(right);
    return safeLeft != null && safeRight != null && safeLeft.equalsIgnoreCase(safeRight);
  }

  private int compareText(String left, String right) {
    if (left == null || right == null) {
      return 0;
    }
    return left.compareTo(right);
  }

  private String firstDatePart(String value) {
    String normalized = TextQuerySupport.trimToNull(value);
    return normalized == null ? null : normalized.substring(0, Math.min(normalized.length(), 10));
  }

  private Double parseDouble(String value) {
    String normalized = TextQuerySupport.trimToNull(value);
    if (normalized == null) {
      return null;
    }
    try {
      return Double.parseDouble(normalized);
    } catch (NumberFormatException exception) {
      return null;
    }
  }

  private boolean requiresPrimaryValue(String operator) {
    return !"isEmpty".equals(operator) && !"isNotEmpty".equals(operator);
  }

  private Comparator<ReviewDataRecordRowResponse> buildComparator(String sortField, String sortOrder) {
    Comparator<ReviewDataRecordRowResponse> comparator =
        switch (sortField) {
          case "projectName" ->
              Comparator.comparing(
                  ReviewDataRecordRowResponse::projectName,
                  Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
          case "moduleName" ->
              Comparator.comparing(
                  ReviewDataRecordRowResponse::moduleName,
                  Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
          case "reviewType" ->
              Comparator.comparing(
                  ReviewDataRecordRowResponse::reviewType,
                  Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
          case "reviewDate" ->
              Comparator.comparing(
                  ReviewDataRecordRowResponse::reviewDate, Comparator.nullsLast(LocalDate::compareTo));
          case "reviewOwner" ->
              Comparator.comparing(
                  ReviewDataRecordRowResponse::reviewOwner,
                  Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
          case "reviewScalePages" ->
              Comparator.comparing(
                  ReviewDataRecordRowResponse::reviewScalePages, Comparator.nullsLast(Integer::compareTo));
          case "problemCount" ->
              Comparator.comparing(
                  ReviewDataRecordRowResponse::problemCount, Comparator.nullsLast(Integer::compareTo));
          case "problemDensity" ->
              Comparator.comparing(
                  ReviewDataRecordRowResponse::problemDensity, Comparator.nullsLast(Double::compareTo));
          case "updatedAt" ->
              Comparator.comparing(
                  ReviewDataRecordRowResponse::updatedAt,
                  Comparator.nullsLast(LocalDateTime::compareTo));
          default ->
              Comparator.comparing(
                  ReviewDataRecordRowResponse::title,
                  Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
        };
    if ("desc".equalsIgnoreCase(sortOrder)) {
      comparator = comparator.reversed();
    }
    return comparator.thenComparing(ReviewDataRecordRowResponse::id, Comparator.nullsLast(Long::compareTo));
  }

  private String normalizeSortField(String sortField) {
    String normalized = TextQuerySupport.trimToNull(sortField);
    if (normalized == null) {
      return "updatedAt";
    }
    return switch (normalized) {
      case "title",
          "projectName",
          "moduleName",
          "reviewType",
          "reviewDate",
          "reviewOwner",
          "reviewScalePages",
          "problemCount",
          "problemDensity",
          "updatedAt" -> normalized;
      default -> "updatedAt";
    };
  }

  private String normalizeSortOrder(String sortOrder) {
    String normalized = TextQuerySupport.trimToNull(sortOrder);
    if ("asc".equalsIgnoreCase(normalized)) {
      return "asc";
    }
    return "desc";
  }
}
