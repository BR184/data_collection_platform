package com.data.collection.platform.bi.application;

import com.data.collection.platform.bi.domain.port.BiCatContractUnavailableException;
import com.data.collection.platform.bi.domain.port.BiCurrentSourceVersionPort;
import com.data.collection.platform.bi.domain.port.BiProductVersionPort;
import com.data.collection.platform.common.exception.BizException;
import java.util.Map;
import java.util.Set;

/** 校验浏览器端完整数据 PNG 导出是否仍对应当前授权页面版本。 */
public final class BiDownloadAuthorizationService {
  private static final Map<String, Set<String>> PAGE_TEMPLATES = Map.of(
      "requirements", Set.of("distribution-donut", "review-quality-dual-panel", "review-quality-scatter"),
      "design", Set.of("distribution-donut", "review-quality-dual-panel", "review-quality-scatter"),
      "coding", Set.of(
          "vertical-category-bar", "coding-trend-combo", "submission-trend-combo",
          "distribution-donut", "stacked-category-bar",
          "review-quality-dual-panel", "review-quality-scatter", "quality-trend-small-multiples"),
      "unit-test", Set.of("test-quality-attainment"),
      "integration-test", Set.of("test-quality-attainment"),
      "system-test", Set.of(
          "distribution-donut", "quality-round-track", "stacked-category-bar",
          "overlay-category-bar", "module-repair-matrix", "vertical-category-bar",
          "delay-heatmap", "developer-workload"));

  private final BiProductVersionPort versionPort;
  private final BiCurrentSourceVersionPort sourceVersionPort;

  public BiDownloadAuthorizationService(
      BiProductVersionPort versionPort,
      BiCurrentSourceVersionPort sourceVersionPort) {
    this.versionPort = versionPort;
    this.sourceVersionPort = sourceVersionPort;
  }

  /** 校验页面、图表模板和来源版本，成功时只回传原请求身份。 */
  public Authorization authorize(Request request) {
    Set<String> templates = PAGE_TEMPLATES.get(request.pageKey());
    if (templates == null || !templates.contains(request.chartTemplateId())) {
      throw new BizException("当前页面不支持该 BI 图表下载");
    }
    if (request.sourceVersion() == null || request.sourceVersion().isBlank()) {
      throw new BizException("下载前必须提供当前页面来源版本");
    }
    var scope = versionPort.requireScope(request.productVersionId());
    try {
      String current = sourceVersionPort.current(request.pageKey(), scope);
      if (!request.sourceVersion().equals(current)) {
        throw new BizException("页面已有新数据，请刷新整页后再下载");
      }
    } catch (BiCatContractUnavailableException unavailable) {
      throw new BizException(unavailable.getMessage() + "，当前页面不能下载");
    }
    return new Authorization(
        true,
        request.productVersionId(),
        request.pageKey(),
        request.chartTemplateId(),
        request.sourceVersion());
  }

  /** 下载授权请求，只包含稳定业务身份，不上传图表像素。 */
  public record Request(
      long productVersionId,
      String pageKey,
      String chartTemplateId,
      String sourceVersion) {}

  /** 浏览器可据此继续执行本地 PNG 生成。 */
  public record Authorization(
      boolean authorized,
      long productVersionId,
      String pageKey,
      String chartTemplateId,
      String sourceVersion) {}
}
