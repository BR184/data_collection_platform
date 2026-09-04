package com.data.collection.platform.service;

import com.data.collection.platform.entity.OptionItemResponse;
import com.data.collection.platform.entity.ReviewDataFilterOptionsResponse;
import com.data.collection.platform.entity.ReviewDataRecordRowResponse;
import com.data.collection.platform.service.dropdown.DropdownOptionFieldRegistry;
import com.data.collection.platform.service.dropdown.DropdownOptionFilterService;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class ReviewDataFilterOptionService {
  private static final List<OptionItemResponse> REVIEW_TYPE_OPTIONS =
      List.of(
          new OptionItemResponse("需求说明书评审", "需求说明书评审"),
          new OptionItemResponse("设计说明书评审", "设计说明书评审"),
          new OptionItemResponse("产品用户手册", "产品用户手册"),
          new OptionItemResponse("项目计划评审", "项目计划评审"),
          new OptionItemResponse("其他", "其他"));

  private static final List<OptionItemResponse> REVIEW_CATEGORY_OPTIONS =
      List.of(
          new OptionItemResponse("走查", "走查"),
          new OptionItemResponse("独立评审", "独立评审"),
          new OptionItemResponse("会议评审", "会议评审"));

  private static final List<OptionItemResponse> PROBLEM_CATEGORY_OPTIONS =
      List.of(
          new OptionItemResponse("文档规范", "文档规范"),
          new OptionItemResponse("完整性", "完整性"),
          new OptionItemResponse("功能性", "功能性"),
          new OptionItemResponse("可行性", "可行性"),
          new OptionItemResponse("无问题", "无问题"));

  private static final List<OptionItemResponse> PROBLEM_STATUS_OPTIONS =
      List.of(
          new OptionItemResponse("新提交", "新提交"),
          new OptionItemResponse("已修复", "已修复"),
          new OptionItemResponse("已关闭", "已关闭"),
          new OptionItemResponse("已拒绝", "已拒绝"),
          new OptionItemResponse("无问题", "无问题"),
          new OptionItemResponse("未评审", "未评审"));

  private final ReviewDataMirrorOptionRepository mirrorOptionRepository;
  private final ReviewDataHistoricalOptionRepository historicalOptionRepository;
  private final CodeReviewMatchModeSwitchService matchModeSwitchService;
  private final ReviewDataMatchModeRecordRepository matchModeRecordRepository;
  private final DropdownOptionFilterService dropdownOptionFilterService;

  public ReviewDataFilterOptionService(
      ReviewDataMirrorOptionRepository mirrorOptionRepository,
      ReviewDataHistoricalOptionRepository historicalOptionRepository,
      CodeReviewMatchModeSwitchService matchModeSwitchService,
      ReviewDataMatchModeRecordRepository matchModeRecordRepository,
      DropdownOptionFilterService dropdownOptionFilterService) {
    this.mirrorOptionRepository = mirrorOptionRepository;
    this.historicalOptionRepository = historicalOptionRepository;
    this.matchModeSwitchService = matchModeSwitchService;
    this.matchModeRecordRepository = matchModeRecordRepository;
    this.dropdownOptionFilterService = dropdownOptionFilterService;
  }

  public ReviewDataFilterOptionsResponse getFilterOptions() {
    // 列表快速筛选候选来自当前可查询的评审记录；新增/编辑表单候选优先来自 GitLab 镜像。
    // 两类候选不能混用，否则 GitLab 仓库项目会成为无法命中任何评审记录的筛选项。

    // GitLab 镜像库数据
    List<String> mirrorUserNames = mirrorOptionRepository.loadUserNames();
    List<String> mirrorModuleNames = mirrorOptionRepository.loadModuleNames();
    List<String> mirrorReviewVersions = mirrorOptionRepository.loadMilestoneTitles();

    // 历史评审数据
    List<String> historicalProjectNames = historicalOptionRepository.loadProjectNames();
    List<String> historicalModuleNames = historicalOptionRepository.loadModuleNames();
    List<String> historicalReviewVersions = historicalOptionRepository.loadReviewVersions();
    List<String> historicalReviewOwners = historicalOptionRepository.loadReviewOwners();
    List<String> historicalReviewExperts = historicalOptionRepository.loadReviewExperts();
    List<String> historicalAuthors = historicalOptionRepository.loadAuthors();
    List<ReviewDataRecordRowResponse> matchRecords = List.of();
    if (matchModeSwitchService.isReviewDataCompatibilityReadEnabled()) {
      // 与列表同源，避免将已由用户接管、当前不可查询的旧快照字段作为筛选候选返回。
      matchRecords = matchModeRecordRepository.loadRecords();
    }
    List<String> matchProjectNames = matchRecords.stream().map(ReviewDataRecordRowResponse::projectName).toList();
    List<String> matchModuleNames = matchRecords.stream().map(ReviewDataRecordRowResponse::moduleName).toList();
    List<String> matchReviewOwners = matchRecords.stream().map(ReviewDataRecordRowResponse::reviewOwner).toList();
    List<String> matchReviewExperts = matchModeReviewExperts(matchRecords);

    // 快速筛选只展示当前记录查询能够命中的项目，不能混入没有评审记录的 GitLab 仓库项目。
    List<String> filterProjectNames = mergeValues(historicalProjectNames, matchProjectNames);
    List<String> filterModuleNames = mergeValues(historicalModuleNames, matchModuleNames);
    List<String> reviewOwnerNames =
        mergeValues(
            mirrorUserNames,
            historicalReviewOwners,
            matchReviewOwners);
    List<String> allUserNames =
        mergeValues(
            mirrorUserNames,
            historicalReviewOwners,
            historicalReviewExperts,
            historicalAuthors,
            matchReviewOwners,
            matchReviewExperts);
    List<String> allReviewVersions = mergeValues(mirrorReviewVersions, historicalReviewVersions);

    return new ReviewDataFilterOptionsResponse(
        toOptions(filterProjectNames),           // 快速筛选项目：当前读模式下实际存在的评审项目
        toOptions(filterModuleNames),            // 快速筛选模块：当前读模式下实际存在的评审模块
        toOptions(reviewOwnerNames),             // 评审负责人：镜像库 + 历史负责人补充；不把专家/作者混入负责人筛选项。
        REVIEW_TYPE_OPTIONS,
        toOptions(allUserNames),                 // 评审专家/作者/责任人：镜像库 + 历史补充
        toOptions(allReviewVersions),            // 评审版本：镜像里程碑 + 历史补充
        PROBLEM_STATUS_OPTIONS,
        REVIEW_CATEGORY_OPTIONS,
        PROBLEM_CATEGORY_OPTIONS,
        toOptions(dropdownOptionFilterService.resolveOptions(
            DropdownOptionFieldRegistry.REVIEW_FORM_PROJECT_NAME_FIELD)), // 新增/编辑评审项目：GitLab 项目标签经下拉框选项配置判定，不影响快速筛选范围
        toOptions(mirrorModuleNames));            // 新增/编辑评审模块：项目 9、79 的全角“模块：”标签
  }

  @SafeVarargs
  private List<String> mergeValues(List<String>... sources) {
    java.util.LinkedHashSet<String> values = new java.util.LinkedHashSet<>();
    for (List<String> source : sources) {
      values.addAll(source);
    }
    return List.copyOf(values);
  }

  private List<String> matchModeReviewExperts(List<ReviewDataRecordRowResponse> records) {
    return records.stream()
        .flatMap(
            record ->
                java.util.Arrays.stream(
                    TextQuerySupport.normalizeDisplay(record.reviewExpertsSummary()).split("、")))
        .toList();
  }

  private List<OptionItemResponse> toOptions(List<String> values) {
    Set<String> distinct = new LinkedHashSet<>();
    values.stream().map(TextQuerySupport::trimToNull).filter(Objects::nonNull).forEach(distinct::add);
    return distinct.stream().map(value -> new OptionItemResponse(value, value)).toList();
  }
}
