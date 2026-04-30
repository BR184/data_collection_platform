package com.data.collection.platform.service;

import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.OptionItemResponse;
import com.data.collection.platform.entity.SystemTestIssueSearchFilterOptionsResponse;
import com.data.collection.platform.entity.SystemTestIssueSearchListResponse;
import com.data.collection.platform.entity.SystemTestIssueSearchRowResponse;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class SystemTestIssueSearchService extends AbstractIssueFactRecordListService {
  private static final String DEFAULT_SORT_FIELD = "updatedAt";
  private static final Map<String, Comparator<IssueFactRecord>> SORT_COMPARATORS =
      createSortComparators();

  private final SystemTestScopeProfile systemTestScopeProfile;

  public SystemTestIssueSearchService(
      IssueFactRecordRepository issueFactRecordRepository,
      SystemTestScopeProfile systemTestScopeProfile,
      GitlabMirrorProperties gitlabMirrorProperties) {
    super(issueFactRecordRepository, gitlabMirrorProperties.getWebBaseUrl());
    this.systemTestScopeProfile = systemTestScopeProfile;
  }

  public SystemTestIssueSearchListResponse listRecords(SystemTestIssueSearchQueryRequest request) {
    IssueFactRecordListRequest listRequest = request.listRequest();
    int safePage = normalizePage(listRequest.page());
    int safeSize = normalizeSize(listRequest.size());
    String safeSortField =
        normalizeSortField(listRequest.sortField(), DEFAULT_SORT_FIELD, SORT_COMPARATORS.keySet());
    String safeSortOrder = normalizeSortOrder(listRequest.sortOrder());

    if (canUseSqlPage(listRequest, null, safeSortField)) {
      PageSlice<IssueFactRecord> pageSlice =
          loadFactPage(
              new IssueFactRecordPageQuery(
                  IssueFactRecordPageQuery.Scope.SYSTEM_TEST,
                  listRequest,
                  null,
                  null,
                  null,
                  request.testingPhase(),
                  request.authorName(),
                  request.assigneeName(),
                  false,
                  false,
                  false,
                  false,
                  false,
                  safePage,
                  safeSize,
                  safeSortField,
                  safeSortOrder));
      List<SystemTestIssueSearchRowResponse> records =
          pageSlice.records().stream().map(this::toResponse).toList();
      return new SystemTestIssueSearchListResponse(
          records, pageSlice.total(), pageSlice.page(), pageSlice.size(), safeSortField, safeSortOrder);
    }

    List<IssueFactRecord> filtered =
        applyBaseFilters(
                loadScopedViews(listRequest.projectId()),
                listRequest,
                view -> matchesKeyword(view, listRequest.keyword()))
            .stream()
            .filter(view -> matchesTestingPhase(view, request.testingPhase()))
            .filter(view -> matchesEquals(view.authorName(), request.authorName()))
            .filter(view -> matchesEquals(view.assigneeName(), request.assigneeName()))
            .sorted(applySortDirection(SORT_COMPARATORS.get(safeSortField), safeSortOrder))
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
    return loadFacts(projectId).stream()
        .filter(view -> systemTestScopeProfile.matches(view.scopeContext()))
        .toList();
  }

  private SystemTestIssueSearchRowResponse toResponse(IssueFactRecord view) {
    return new SystemTestIssueSearchRowResponse(
        view.issueId(),
        view.issueIid(),
        buildIssueLink(view.issueIid()),
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

  private boolean matchesTestingPhase(IssueFactRecord view, String testingPhase) {
    String normalized = TextQuerySupport.trimToNull(testingPhase);
    return normalized == null || TextQuerySupport.equalsNormalized(view.phaseFilterValue(), normalized);
  }

  private static Map<String, Comparator<IssueFactRecord>> createSortComparators() {
    Map<String, Comparator<IssueFactRecord>> comparators = new LinkedHashMap<>();
    comparators.put("issueIid", SortSupport.nullableComparable(IssueFactRecord::issueIid));
    comparators.put("title", SortSupport.nullableString(IssueFactRecord::title));
    comparators.put("projectName", SortSupport.nullableString(IssueFactRecord::projectName));
    comparators.put(
        "moduleNames", SortSupport.nullableString(view -> String.join("、", view.moduleNames())));
    comparators.put("testingPhase", SortSupport.nullableString(IssueFactRecord::primaryPhaseLabel));
    comparators.put("severityLevel", SortSupport.nullableString(IssueFactRecord::severityLevel));
    comparators.put("bugStatus", SortSupport.nullableString(IssueFactRecord::bugStatus));
    comparators.put("issueState", SortSupport.nullableString(IssueFactRecord::issueState));
    comparators.put("authorName", SortSupport.nullableString(IssueFactRecord::authorName));
    comparators.put("assigneeName", SortSupport.nullableString(IssueFactRecord::assigneeName));
    comparators.put("category", SortSupport.nullableString(IssueFactRecord::category));
    comparators.put("milestoneTitle", SortSupport.nullableString(IssueFactRecord::milestoneTitle));
    comparators.put("createdAt", SortSupport.nullableComparable(IssueFactRecord::createdAt));
    comparators.put("updatedAt", SortSupport.nullableComparable(IssueFactRecord::updatedAt));
    comparators.put("closedAt", SortSupport.nullableComparable(IssueFactRecord::closedAt));
    return Map.copyOf(comparators);
  }
}
