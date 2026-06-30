package com.data.collection.platform.service;

import com.data.collection.platform.entity.OptionItemResponse;
import com.data.collection.platform.entity.ReviewDataFilterOptionsResponse;
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
          new OptionItemResponse("单元测试用例评审", "单元测试用例评审"),
          new OptionItemResponse("集成测试用例评审", "集成测试用例评审"),
          new OptionItemResponse("系统测试用例评审", "系统测试用例评审"),
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

  public ReviewDataFilterOptionService(
      ReviewDataMirrorOptionRepository mirrorOptionRepository,
      ReviewDataHistoricalOptionRepository historicalOptionRepository) {
    this.mirrorOptionRepository = mirrorOptionRepository;
    this.historicalOptionRepository = historicalOptionRepository;
  }

  public ReviewDataFilterOptionsResponse getFilterOptions() {
    // 按文档 6.8 规则：评审数据管理页"新增评审"候选值必须优先来自 GitLab 镜像库全量数据
    // 项目来自 ods_gitlab_projects，人员来自 ods_gitlab_users
    // 但根据内网老平台实际数据，模块和评审版本是自由文本，应从历史评审数据中提取候选值
    List<String> mirrorUserNames = mirrorOptionRepository.loadUserNames();
    List<String> mirrorProjectNames = mirrorOptionRepository.loadProjectNames();

    // 历史评审数据中的候选值
    List<String> historicalProjectNames = historicalOptionRepository.loadProjectNames();
    List<String> historicalModuleNames = historicalOptionRepository.loadModuleNames();
    List<String> historicalReviewVersions = historicalOptionRepository.loadReviewVersions();

    // 项目名称：镜像库 + 历史数据补充
    List<String> allProjectNames = mergeValues(mirrorProjectNames, historicalProjectNames);

    return new ReviewDataFilterOptionsResponse(
        toOptions(allProjectNames),              // 项目：镜像库 + 历史补充
        toOptions(historicalModuleNames),        // 模块：历史数据（老平台是自由文本）
        toOptions(mirrorUserNames),              // 评审负责人：镜像库
        REVIEW_TYPE_OPTIONS,
        toOptions(mirrorUserNames),              // 评审专家/作者/责任人：镜像库
        toOptions(historicalReviewVersions),     // 评审版本：历史数据（老平台是自由文本）
        PROBLEM_STATUS_OPTIONS,
        REVIEW_CATEGORY_OPTIONS,
        PROBLEM_CATEGORY_OPTIONS);
  }

  private List<String> mergeValues(List<String> first, List<String> second) {
    java.util.LinkedHashSet<String> values = new java.util.LinkedHashSet<>();
    values.addAll(first);
    values.addAll(second);
    return List.copyOf(values);
  }

  private List<OptionItemResponse> toOptions(List<String> values) {
    Set<String> distinct = new LinkedHashSet<>();
    values.stream().map(TextQuerySupport::trimToNull).filter(Objects::nonNull).forEach(distinct::add);
    return distinct.stream().map(value -> new OptionItemResponse(value, value)).toList();
  }
}
