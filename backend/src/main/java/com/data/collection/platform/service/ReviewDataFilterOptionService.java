package com.data.collection.platform.service;

import com.data.collection.platform.entity.OptionItemResponse;
import com.data.collection.platform.entity.ReviewDataFilterOptionsResponse;
import com.data.collection.platform.entity.ReviewDataRecordRowResponse;
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
          new OptionItemResponse("新提出", "新提出"),
          new OptionItemResponse("已修复", "已修复"),
          new OptionItemResponse("已关闭", "已关闭"),
          new OptionItemResponse("已拒绝", "已拒绝"),
          new OptionItemResponse("无问题", "无问题"),
          new OptionItemResponse("未评审", "未评审"));

  private final ReviewDataRecordPersistenceSupport persistenceSupport;

  public ReviewDataFilterOptionService(ReviewDataRecordPersistenceSupport persistenceSupport) {
    this.persistenceSupport = persistenceSupport;
  }

  public ReviewDataFilterOptionsResponse getFilterOptions() {
    List<ReviewDataRecordRowResponse> records =
        persistenceSupport.loadRecords(null, null, null, null, null, null, null);

    return new ReviewDataFilterOptionsResponse(
        toOptions(records.stream().map(ReviewDataRecordRowResponse::projectName).toList()),
        toOptions(records.stream().map(ReviewDataRecordRowResponse::moduleName).toList()),
        toOptions(records.stream().map(ReviewDataRecordRowResponse::reviewOwner).toList()),
        REVIEW_TYPE_OPTIONS,
        toOptions(persistenceSupport.loadExpertOptions()),
        PROBLEM_STATUS_OPTIONS,
        REVIEW_CATEGORY_OPTIONS,
        PROBLEM_CATEGORY_OPTIONS);
  }

  private List<OptionItemResponse> toOptions(List<String> values) {
    Set<String> distinct = new LinkedHashSet<>();
    values.stream().map(TextQuerySupport::trimToNull).filter(Objects::nonNull).forEach(distinct::add);
    return distinct.stream().map(value -> new OptionItemResponse(value, value)).toList();
  }
}
