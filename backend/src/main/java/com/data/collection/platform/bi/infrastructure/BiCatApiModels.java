package com.data.collection.platform.bi.infrastructure;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.List;

/** CAT 传输 DTO；这些类型不得穿透到 BI 应用层。 */
final class BiCatApiModels {
  private BiCatApiModels() {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record Envelope<T>(Integer code, String message, T data) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record Project(
      String id,
      String name,
      String note,
      String createTime,
      String createUserId,
      Boolean defaultProject) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record PhaseNode(
      String id,
      String name,
      List<PhaseNode> children,
      String createTime,
      String note,
      @JsonProperty("group_id") String groupId,
      String endTime,
      Boolean curVersion,
      String projectId,
      String versionId,
      Boolean disabled,
      Boolean defaultProject) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record StatisticsPayload(List<ModuleStatistic> result, BigDecimal passRate) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record ModuleStatistic(
      String id,
      String moduleName,
      String name,
      Long passFeatureCount,
      Long notPassFeatureCount,
      BigDecimal testPassRate) {}

  record FeatureQuery(String moduleId, String testingPhaseId) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record FeaturePage(
      List<FeatureStatistic> statisticsInfoList,
      Integer page,
      Integer pageSize,
      Long totalCount,
      Integer sumPageCount) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record FeatureStatistic(
      String id,
      String name,
      String featureUniqueId,
      String featureLabel,
      BigDecimal testPassRate) {}
}
