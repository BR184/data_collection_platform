package com.data.collection.platform.service.labelgroup;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.entity.CustomerIssueRecordFilterOptionsResponse;
import com.data.collection.platform.entity.OptionItemResponse;
import com.data.collection.platform.entity.ReviewDataFilterOptionsResponse;
import com.data.collection.platform.entity.SystemTestIssueSearchFilterOptionsResponse;
import com.data.collection.platform.entity.labelgroup.LabelGroupCompatiblePageResponse;
import com.data.collection.platform.entity.labelgroup.LabelValuePageResponse;
import com.data.collection.platform.entity.labelgroup.LabelValueResponse;
import com.data.collection.platform.service.CustomerIssueRecordService;
import com.data.collection.platform.service.ReviewDataMirrorOptionRepository;
import com.data.collection.platform.service.ReviewDataRecordService;
import com.data.collection.platform.service.SystemTestIssueSearchService;
import com.data.collection.platform.service.TextQuerySupport;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

@Service
public class LabelValueQueryService {
  private static final String SOURCE_FACT = "FACT";
  private static final String REVIEW_PAGE = "review-data-home";
  private static final String ISSUE_PAGE = "question-metrics-issue-search";
  private static final String CUSTOMER_PAGE = "customer-issues-cc-product-issues";

  private final LabelDimensionCatalogService dimensionCatalogService;
  private final ReviewDataMirrorOptionRepository mirrorOptionRepository;
  private final ReviewDataRecordService reviewDataRecordService;
  private final SystemTestIssueSearchService systemTestIssueSearchService;
  private final CustomerIssueRecordService customerIssueRecordService;
  private final Map<String, Supplier<List<String>>> mirrorValueLoaders;

  public LabelValueQueryService(
      LabelDimensionCatalogService dimensionCatalogService,
      ReviewDataMirrorOptionRepository mirrorOptionRepository,
      ReviewDataRecordService reviewDataRecordService,
      SystemTestIssueSearchService systemTestIssueSearchService,
      CustomerIssueRecordService customerIssueRecordService) {
    this.dimensionCatalogService = dimensionCatalogService;
    this.mirrorOptionRepository = mirrorOptionRepository;
    this.reviewDataRecordService = reviewDataRecordService;
    this.systemTestIssueSearchService = systemTestIssueSearchService;
    this.customerIssueRecordService = customerIssueRecordService;
    this.mirrorValueLoaders =
        Map.of(
            "project", mirrorOptionRepository::loadLabelProjectNames,
            "person", mirrorOptionRepository::loadUserNames,
            "milestone", mirrorOptionRepository::loadMilestoneTitles);
  }

  public LabelValuePageResponse listValues(
      String dimensionKey,
      String pageKey,
      String sourceInstanceId,
      String keyword,
      int page,
      int size) {
    LabelDimensionDefinition dimension = dimensionCatalogService.getDimension(dimensionKey);
    if (!dimensionCatalogService.pageSupportsDimension(dimensionKey, pageKey)) {
      throw new BizException("当前页面不支持该标签维度：" + dimension.name());
    }

    List<OptionItemResponse> options = loadOptions(dimensionKey, TextQuerySupport.trimToNull(pageKey), sourceInstanceId);
    List<LabelValueResponse> values =
        options.stream()
            .filter(option -> TextQuerySupport.containsAbstractSearch(option.label(), keyword)
                || TextQuerySupport.containsAbstractSearch(option.value(), keyword))
            .map(option -> new LabelValueResponse(
                option.value(), option.label(), dimension.valueKind(), SOURCE_FACT, 0L))
            .sorted(Comparator.comparing(LabelValueResponse::label, String::compareToIgnoreCase))
            .toList();

    int safePage = Math.max(1, page);
    int safeSize = Math.min(200, Math.max(1, size));
    int from = Math.min((safePage - 1) * safeSize, values.size());
    int to = Math.min(from + safeSize, values.size());
    return new LabelValuePageResponse(values.subList(from, to), values.size(), safePage, safeSize);
  }

  private List<OptionItemResponse> loadOptions(String dimensionKey, String pageKey, String sourceInstanceId) {
    if ("closure_status".equals(dimensionKey)) {
      return List.of(new OptionItemResponse("需求如此", "需求如此"));
    }
    Supplier<List<String>> mirrorLoader = mirrorValueLoaders.get(dimensionKey);
    if (mirrorLoader != null) {
      // 镜像直取维度：候选值来自镜像库全量，不随页面数据过滤；页面只是使用位置。
      return toOptions(mirrorLoader.get());
    }
    if (pageKey != null) {
      return loadPageOptions(dimensionKey, pageKey, sourceInstanceId);
    }
    Map<String, OptionItemResponse> merged = new LinkedHashMap<>();
    for (LabelGroupCompatiblePageResponse page : dimensionCatalogService.listCompatiblePages(dimensionKey)) {
      loadPageOptions(dimensionKey, page.pageKey(), sourceInstanceId)
          .forEach(option -> merged.putIfAbsent(option.value(), option));
    }
    return List.copyOf(merged.values());
  }

  private static List<OptionItemResponse> toOptions(List<String> values) {
    return values.stream().map(value -> new OptionItemResponse(value, value)).toList();
  }

  private List<OptionItemResponse> loadPageOptions(String dimensionKey, String pageKey, String sourceInstanceId) {
    return switch (pageKey) {
      case REVIEW_PAGE -> reviewOptions(dimensionKey);
      case ISSUE_PAGE -> issueOptions(dimensionKey, sourceInstanceId);
      case CUSTOMER_PAGE -> customerOptions(dimensionKey, sourceInstanceId);
      default -> throw new BizException("标签组候选值暂不支持页面：" + pageKey);
    };
  }

  private List<OptionItemResponse> reviewOptions(String dimensionKey) {
    ReviewDataFilterOptionsResponse options = reviewDataRecordService.getFilterOptions();
    return switch (dimensionKey) {
      case "module" -> options.moduleNames();
      default -> List.of();
    };
  }

  private List<OptionItemResponse> issueOptions(String dimensionKey, String sourceInstanceId) {
    SystemTestIssueSearchFilterOptionsResponse options =
        systemTestIssueSearchService.getFilterOptions(null, TextQuerySupport.trimToNull(sourceInstanceId));
    return switch (dimensionKey) {
      case "module" -> options.moduleNames();
      case "test_stage" -> options.testingPhases();
      case "severity_level" -> options.severityLevels();
      case "priority_level" -> List.of(
          new OptionItemResponse("P1", "P1"),
          new OptionItemResponse("P2", "P2"),
          new OptionItemResponse("P3", "P3"));
      default -> List.of();
    };
  }

  private List<OptionItemResponse> customerOptions(String dimensionKey, String sourceInstanceId) {
    CustomerIssueRecordFilterOptionsResponse options =
        customerIssueRecordService.getFilterOptions(
            "cc-product", null, TextQuerySupport.trimToNull(sourceInstanceId));
    return switch (dimensionKey) {
      case "module" -> options.moduleNames();
      case "priority_level" -> options.priorityLevels();
      default -> List.of();
    };
  }
}
