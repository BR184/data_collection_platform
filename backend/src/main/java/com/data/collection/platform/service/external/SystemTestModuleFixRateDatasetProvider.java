package com.data.collection.platform.service.external;

import com.data.collection.platform.entity.external.ExternalDatasetDescriptor;
import com.data.collection.platform.entity.external.ExternalDatasetField;
import com.data.collection.platform.entity.external.ExternalDatasetParameter;
import com.data.collection.platform.entity.external.SystemTestModuleFixRatePayload;
import com.data.collection.platform.entity.statistics.SystemTestModuleFixRateSnapshot;
import com.data.collection.platform.service.SystemTestPhaseScopeResolver;
import com.data.collection.platform.service.statistics.SystemTestDefectSummaryBoardService;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class SystemTestModuleFixRateDatasetProvider
    implements ExternalDatasetProvider<SystemTestModuleFixRatePayload> {
  public static final String DATASET_KEY = "system-test-module-fix-rates";
  private static final String SCHEMA_VERSION = "1.1";

  private final SystemTestDefectSummaryBoardService summaryBoardService;
  private final SystemTestPhaseScopeResolver phaseScopeResolver;

  public SystemTestModuleFixRateDatasetProvider(
      SystemTestDefectSummaryBoardService summaryBoardService,
      SystemTestPhaseScopeResolver phaseScopeResolver) {
    this.summaryBoardService = summaryBoardService;
    this.phaseScopeResolver = phaseScopeResolver;
  }

  @Override
  public String datasetKey() {
    return DATASET_KEY;
  }

  @Override
  public ExternalDatasetDescriptor descriptor() {
    return new ExternalDatasetDescriptor(
        DATASET_KEY,
        "各模块系统测试修复率达成情况",
        "按产品版本汇总有效、非建议类系统测试缺陷的整体、一级、P1 和 P2 修复情况。",
        SCHEMA_VERSION,
        List.of(new ExternalDatasetParameter(
            "productVersion", "string", true, "产品版本名称，对应测试阶段父分组。", "CC2026R4")),
        List.of(
            new ExternalDatasetField("productVersion", "string", false, "请求使用的产品版本。"),
            new ExternalDatasetField("availableProductVersions[]", "string", false,
                "可选择的启用产品版本。"),
            new ExternalDatasetField("testingPhases[]", "string", false, "产品版本下命中的启用测试阶段。"),
            new ExternalDatasetField("modules[].moduleName", "string", false, "归一化模块名称。"),
            new ExternalDatasetField("modules[].overall.defectCount", "integer", false, "模块有效缺陷数。"),
            new ExternalDatasetField("modules[].overall.fixedCount", "integer", false, "模块事实字段 is_fixed=true 的缺陷数。"),
            new ExternalDatasetField("modules[].overall.fixRatePercent", "number", true, "整体修复率百分比；分母为零时为 null。"),
            new ExternalDatasetField("modules[].level1.defectCount", "integer", false, "模块一级缺陷数。"),
            new ExternalDatasetField("modules[].level1.fixedCount", "integer", false, "模块已修复一级缺陷数。"),
            new ExternalDatasetField("modules[].level1.fixRatePercent", "number", true, "一级缺陷修复率百分比。"),
            new ExternalDatasetField("modules[].p1.defectCount", "integer", false, "模块 P1 缺陷数。"),
            new ExternalDatasetField("modules[].p1.fixedCount", "integer", false, "模块已修复 P1 缺陷数。"),
            new ExternalDatasetField("modules[].p1.fixRatePercent", "number", true, "P1 修复率百分比。"),
            new ExternalDatasetField("modules[].p2.defectCount", "integer", false, "模块 P2 缺陷数。"),
            new ExternalDatasetField("modules[].p2.fixedCount", "integer", false, "模块已修复 P2 缺陷数。"),
            new ExternalDatasetField("modules[].p2.fixRatePercent", "number", true, "P2 修复率百分比。")));
  }

  @Override
  public SystemTestModuleFixRatePayload load(Map<String, String> parameters) {
    String productVersion = parameters == null ? null : parameters.get("productVersion");
    SystemTestModuleFixRateSnapshot snapshot = summaryBoardService.loadExternalModuleFixRates(productVersion);
    return new SystemTestModuleFixRatePayload(
        snapshot.productVersion(),
        phaseScopeResolver.listEnabledLegacyCrownCadParentNames(),
        snapshot.testingPhases(),
        snapshot.modules());
  }
}
