package com.data.collection.platform.service;

import com.data.collection.platform.entity.OptionItemResponse;
import com.data.collection.platform.entity.SystemTestIssueSearchFilterOptionsResponse;
import com.data.collection.platform.entity.SystemTestIssueSearchListResponse;
import com.data.collection.platform.entity.SystemTestIssueSearchRowResponse;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class SystemTestIssueSearchService {
  private final IssueFactRecordRepository issueFactRecordRepository;
  private final SystemTestScopeProfile systemTestScopeProfile;

  public SystemTestIssueSearchService(
      IssueFactRecordRepository issueFactRecordRepository,
      SystemTestScopeProfile systemTestScopeProfile) {
    this.issueFactRecordRepository = issueFactRecordRepository;
    this.systemTestScopeProfile = systemTestScopeProfile;
  }

  public SystemTestIssueSearchListResponse listRecords(
      Long projectId,
      String keyword,
      String issueIid,
      String title,
      String projectName,
      String moduleName,
      String testingPhase,
      String authorName,
      String assigneeName,
      String issueState,
      String severityLevel,
      String bugStatus,
      String category,
      String milestoneTitle,
      String createdAtStart,
      String createdAtEnd,
      String updatedAtStart,
      String updatedAtEnd,
      int page,
      int size,
      String sortField,
      String sortOrder) {
    int safePage = page <= 0 ? 1 : page;
    int safeSize = size <= 0 ? 20 : Math.min(size, 100);
    String safeSortField = normalizeSortField(sortField);
    String safeSortOrder = normalizeSortOrder(sortOrder);

    List<IssueFactRecord> filtered =
        loadScopedViews(projectId).stream()
            .filter(view -> matchesKeyword(view, keyword))
            .filter(view -> matchesIssueIid(view, issueIid))
            .filter(view -> matchesText(view.title(), title))
            .filter(view -> matchesEquals(view.projectName(), projectName))
            .filter(view -> matchesModule(view, moduleName))
            .filter(view -> matchesTestingPhase(view, testingPhase))
            .filter(view -> matchesEquals(view.authorName(), authorName))
            .filter(view -> matchesEquals(view.assigneeName(), assigneeName))
            .filter(view -> matchesEquals(view.issueState(), issueState))
            .filter(view -> matchesEquals(view.severityLevel(), severityLevel))
            .filter(view -> matchesEquals(view.bugStatus(), bugStatus))
            .filter(view -> matchesEquals(view.category(), category))
            .filter(view -> matchesEquals(view.milestoneTitle(), milestoneTitle))
            .filter(view -> matchesDateRange(view.createdAt(), createdAtStart, createdAtEnd))
            .filter(view -> matchesDateRange(view.updatedAt(), updatedAtStart, updatedAtEnd))
            .sorted(buildComparator(safeSortField, safeSortOrder))
            .toList();

    PageSlice<IssueFactRecord> pageSlice = PageSliceSupport.slice(filtered, safePage, safeSize);
    List<SystemTestIssueSearchRowResponse> records =
        pageSlice.records().stream().map(this::toResponse).toList();
    return new SystemTestIssueSearchListResponse(
        records, pageSlice.total(), pageSlice.page(), pageSlice.size(), safeSortField, safeSortOrder);
  }

  public SystemTestIssueSearchFilterOptionsResponse getFilterOptions(Long projectId) {
    List<IssueFactRecord> scopedViews = loadScopedViews(projectId);
    return new SystemTestIssueSearchFilterOptionsResponse(
        toOptions(scopedViews, IssueFactRecord::projectName),
        toOptions(scopedViews.stream().flatMap(view -> view.moduleNames().stream()).toList()),
        toOptions(
            scopedViews.stream()
                .map(IssueFactRecord::phaseFilterValue)
                .filter(StringUtils::hasText)
                .toList()),
        toOptions(scopedViews, IssueFactRecord::authorName),
        toOptions(scopedViews, IssueFactRecord::assigneeName),
        toOptions(scopedViews, IssueFactRecord::issueState),
        toOptions(scopedViews, IssueFactRecord::severityLevel),
        toOptions(scopedViews, IssueFactRecord::bugStatus),
        toOptions(scopedViews, IssueFactRecord::category),
        toOptions(scopedViews, IssueFactRecord::milestoneTitle));
  }

  private List<IssueFactRecord> loadScopedViews(Long projectId) {
    return issueFactRecordRepository.findByProjectId(projectId).stream()
        .filter(view -> systemTestScopeProfile.matches(view.scopeContext()))
        .toList();
  }

  private SystemTestIssueSearchRowResponse toResponse(IssueFactRecord view) {
    return new SystemTestIssueSearchRowResponse(
        view.issueId(),
        view.issueIid(),
        view.projectId(),
        view.projectName(),
        view.title(),
        view.issueState(),
        view.primaryPhaseLabel(),
        view.severityLevel(),
        view.bugStatus(),
        view.category(),
        view.milestoneTitle(),
        view.authorName(),
        view.assigneeName(),
        String.join("、", view.moduleNames()),
        view.createdAt(),
        view.updatedAt(),
        view.closedAt(),
        view.labels());
  }

  private boolean matchesKeyword(IssueFactRecord view, String keyword) {
    String normalizedKeyword = TextQuerySupport.trimToNull(keyword);
    if (normalizedKeyword == null) {
      return true;
    }
    return TextQuerySupport.containsAbstractSearch(String.valueOf(view.issueIid()), normalizedKeyword)
        || TextQuerySupport.containsAbstractSearch(view.title(), normalizedKeyword)
        || TextQuerySupport.containsAbstractSearch(view.projectName(), normalizedKeyword)
        || TextQuerySupport.containsAbstractSearch(String.join(" ", view.moduleNames()), normalizedKeyword)
        || TextQuerySupport.containsAbstractSearch(view.primaryPhaseLabel(), normalizedKeyword)
        || TextQuerySupport.containsAbstractSearch(view.authorName(), normalizedKeyword)
        || TextQuerySupport.containsAbstractSearch(view.assigneeName(), normalizedKeyword)
        || TextQuerySupport.containsAbstractSearch(view.bugStatus(), normalizedKeyword)
        || TextQuerySupport.containsAbstractSearch(view.category(), normalizedKeyword)
        || TextQuerySupport.containsAbstractSearch(view.milestoneTitle(), normalizedKeyword);
  }

  private boolean matchesIssueIid(IssueFactRecord view, String issueIid) {
    String normalized = TextQuerySupport.trimToNull(issueIid);
    return normalized == null
        || TextQuerySupport.containsIgnoreCase(String.valueOf(view.issueIid()), normalized);
  }

  private boolean matchesText(String source, String query) {
    return TextQuerySupport.containsAbstractSearch(source, query);
  }

  private boolean matchesModule(IssueFactRecord view, String moduleName) {
    String normalized = TextQuerySupport.trimToNull(moduleName);
    if (normalized == null) {
      return true;
    }
    return view.moduleNames().stream()
        .anyMatch(item -> TextQuerySupport.equalsNormalized(item, normalized));
  }

  private boolean matchesTestingPhase(IssueFactRecord view, String testingPhase) {
    String normalized = TextQuerySupport.trimToNull(testingPhase);
    return normalized == null || TextQuerySupport.equalsNormalized(view.phaseFilterValue(), normalized);
  }

  private boolean matchesEquals(String source, String target) {
    return TextQuerySupport.equalsNormalized(source, target);
  }

  private boolean matchesDateRange(LocalDateTime source, String start, String end) {
    LocalDate startDate = parseDate(start);
    if (startDate != null && (source == null || source.isBefore(startDate.atStartOfDay()))) {
      return false;
    }
    LocalDate endDate = parseDate(end);
    return endDate == null || (source != null && source.isBefore(endDate.plusDays(1).atStartOfDay()));
  }

  private Comparator<IssueFactRecord> buildComparator(String sortField, String sortOrder) {
    Comparator<IssueFactRecord> comparator =
        switch (sortField) {
          case "issueIid" -> SortSupport.nullableComparable(IssueFactRecord::issueIid);
          case "title" -> SortSupport.nullableString(IssueFactRecord::title);
          case "projectName" -> SortSupport.nullableString(IssueFactRecord::projectName);
          case "moduleNames" -> SortSupport.nullableString(view -> String.join("、", view.moduleNames()));
          case "testingPhase" -> SortSupport.nullableString(IssueFactRecord::primaryPhaseLabel);
          case "severityLevel" -> SortSupport.nullableString(IssueFactRecord::severityLevel);
          case "bugStatus" -> SortSupport.nullableString(IssueFactRecord::bugStatus);
          case "issueState" -> SortSupport.nullableString(IssueFactRecord::issueState);
          case "authorName" -> SortSupport.nullableString(IssueFactRecord::authorName);
          case "assigneeName" -> SortSupport.nullableString(IssueFactRecord::assigneeName);
          case "category" -> SortSupport.nullableString(IssueFactRecord::category);
          case "milestoneTitle" -> SortSupport.nullableString(IssueFactRecord::milestoneTitle);
          case "createdAt" -> SortSupport.nullableComparable(IssueFactRecord::createdAt);
          case "closedAt" -> SortSupport.nullableComparable(IssueFactRecord::closedAt);
          default -> SortSupport.nullableComparable(IssueFactRecord::updatedAt);
        };
    comparator = comparator.thenComparing(IssueFactRecord::issueIid);
    return SortSupport.applyDirection(comparator, "asc".equalsIgnoreCase(sortOrder));
  }

  private String normalizeSortField(String sortField) {
    String normalized = TextQuerySupport.trimToNull(sortField);
    if (normalized == null) {
      return "updatedAt";
    }
    return switch (normalized) {
      case "issueIid",
          "title",
          "projectName",
          "moduleNames",
          "testingPhase",
          "severityLevel",
          "bugStatus",
          "issueState",
          "authorName",
          "assigneeName",
          "category",
          "milestoneTitle",
          "createdAt",
          "updatedAt",
          "closedAt" -> normalized;
      default -> "updatedAt";
    };
  }

  private String normalizeSortOrder(String sortOrder) {
    return "asc".equalsIgnoreCase(sortOrder) ? "asc" : "desc";
  }

  private List<OptionItemResponse> toOptions(
      List<IssueFactRecord> rows, java.util.function.Function<IssueFactRecord, String> extractor) {
    return OptionItemResponseFactory.from(rows, extractor, TextQuerySupport::trimToNull);
  }

  private List<OptionItemResponse> toOptions(List<String> values) {
    return OptionItemResponseFactory.from(values, TextQuerySupport::trimToNull);
  }

  private LocalDate parseDate(String value) {
    String normalized = TextQuerySupport.trimToNull(value);
    return normalized == null ? null : LocalDate.parse(normalized);
  }
}
