package com.data.collection.platform.service;

import com.data.collection.platform.entity.OptionItemResponse;
import com.data.collection.platform.entity.statistics.StatisticRuleFlowStep;
import com.data.collection.platform.entity.statistics.StatisticRuleFlowStepSample;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import org.springframework.util.StringUtils;

abstract class AbstractIssueFactRecordListService extends AbstractFactQueryService {
  private final IssueFactRecordRepository issueFactRecordRepository;
  private final String defaultGitlabBaseUrl;

  protected AbstractIssueFactRecordListService(
      IssueFactRecordRepository issueFactRecordRepository, String defaultGitlabBaseUrl) {
    this.issueFactRecordRepository = issueFactRecordRepository;
    this.defaultGitlabBaseUrl = defaultGitlabBaseUrl;
  }

  protected List<IssueFactRecord> loadFacts(Long projectId) {
    return issueFactRecordRepository.findByProjectId(projectId);
  }

  protected PageSlice<IssueFactRecord> loadFactPage(IssueFactRecordPageQuery query) {
    return issueFactRecordRepository.findPage(query);
  }

  protected boolean canUseSqlPage(
      IssueFactRecordListRequest request, String filterGroupJson, String safeSortField) {
    return request != null;
  }

  protected List<IssueFactRecord> applyBaseFilters(
      List<IssueFactRecord> rows,
      IssueFactRecordListRequest request,
      Predicate<IssueFactRecord> keywordMatcher) {
    if (request == null) {
      return rows;
    }
    return rows.stream()
        .filter(keywordMatcher)
        .filter(view -> matchesIssueIid(view, request.issueIid()))
        .filter(view -> matchesText(view.title(), request.title()))
        .filter(view -> matchesEquals(view.projectName(), request.projectName()))
        .filter(view -> matchesModule(view, request.moduleName()))
        .filter(view -> matchesEquals(view.severityLevel(), request.severityLevel()))
        .filter(view -> matchesEquals(view.priorityLevel(), request.priorityLevel()))
        .filter(view -> matchesEquals(view.issueState(), request.issueState()))
        .filter(view -> matchesEquals(view.bugStatus(), request.bugStatus()))
        .filter(view -> matchesEquals(view.category(), request.category()))
        .filter(view -> matchesEquals(view.milestoneTitle(), request.milestoneTitle()))
        .filter(view -> matchesDateRange(view.createdAt(), request.createdAtStart(), request.createdAtEnd()))
        .filter(view -> matchesDateRange(view.updatedAt(), request.updatedAtStart(), request.updatedAtEnd()))
        .toList();
  }

  protected int normalizePage(int page) {
    return page <= 0 ? 1 : page;
  }

  protected int normalizeSize(int size) {
    return size <= 0 ? 20 : Math.min(size, 100);
  }

  protected String normalizeSortOrder(String sortOrder) {
    return "asc".equalsIgnoreCase(sortOrder) ? "asc" : "desc";
  }

  protected String normalizeSortField(
      String sortField, String defaultField, Set<String> allowedFields) {
    String normalized = TextQuerySupport.trimToNull(sortField);
    if (normalized == null || allowedFields == null || !allowedFields.contains(normalized)) {
      return defaultField;
    }
    return normalized;
  }

  protected Comparator<IssueFactRecord> applySortDirection(
      Comparator<IssueFactRecord> comparator, String sortOrder) {
    return SortSupport.applyDirection(
        comparator.thenComparing(IssueFactRecord::issueIid),
        "asc".equalsIgnoreCase(sortOrder));
  }

  protected boolean matchesIssueIid(IssueFactRecord view, String issueIid) {
    String normalized = TextQuerySupport.trimToNull(issueIid);
    return normalized == null
        || TextQuerySupport.containsIgnoreCase(String.valueOf(view.issueIid()), normalized);
  }

  protected boolean matchesText(String source, String query) {
    return TextQuerySupport.containsAbstractSearch(source, query);
  }

  protected boolean matchesModule(IssueFactRecord view, String moduleName) {
    String normalized = TextQuerySupport.trimToNull(moduleName);
    return normalized == null
        || view.moduleNames().stream()
            .anyMatch(item -> TextQuerySupport.equalsNormalized(item, normalized));
  }

  protected boolean matchesEquals(String source, String target) {
    return TextQuerySupport.equalsNormalized(source, target);
  }

  protected boolean matchesDateRange(LocalDateTime source, String start, String end) {
    LocalDate startDate = parseDate(start);
    if (startDate != null && (source == null || source.isBefore(startDate.atStartOfDay()))) {
      return false;
    }
    LocalDate endDate = parseDate(end);
    return endDate == null || (source != null && source.isBefore(endDate.plusDays(1).atStartOfDay()));
  }

  protected List<OptionItemResponse> toOptions(
      List<IssueFactRecord> rows, Function<IssueFactRecord, String> extractor) {
    return OptionItemResponseFactory.from(rows, extractor, TextQuerySupport::trimToNull);
  }

  protected List<OptionItemResponse> toOptions(List<String> values) {
    return OptionItemResponseFactory.from(values, TextQuerySupport::trimToNull);
  }

  protected String buildIssueLink(Integer issueIid) {
    if (!StringUtils.hasText(defaultGitlabBaseUrl) || issueIid == null) {
      return null;
    }
    return defaultGitlabBaseUrl.replaceAll("/+$", "") + "/-/issues/" + issueIid;
  }

  protected StatisticRuleFlowStep step(
      String key, String title, String description, List<IssueFactRecord> output, long inputCount) {
    return new StatisticRuleFlowStep(key, title, description, inputCount, output.size(), sample(output));
  }

  protected List<StatisticRuleFlowStepSample> sample(List<IssueFactRecord> rows) {
    return rows.stream()
        .limit(3)
        .map(row -> new StatisticRuleFlowStepSample("#" + row.issueIid() + " " + row.projectName(), row.title()))
        .toList();
  }
}
