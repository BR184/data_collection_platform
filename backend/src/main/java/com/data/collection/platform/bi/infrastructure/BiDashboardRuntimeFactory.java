package com.data.collection.platform.bi.infrastructure;

import com.data.collection.platform.bi.application.BiCatTestPageService;
import com.data.collection.platform.bi.application.BiCodingPageService;
import com.data.collection.platform.bi.application.BiDashboardRuntime;
import com.data.collection.platform.bi.application.BiDownloadAuthorizationService;
import com.data.collection.platform.bi.application.BiReviewPageService;
import com.data.collection.platform.bi.application.BiSystemTestPageService;
import com.data.collection.platform.bi.application.BiVersionService;
import com.data.collection.platform.bi.domain.BiCodingCalculator;
import com.data.collection.platform.bi.domain.BiProductVersionMatcher;
import com.data.collection.platform.bi.domain.BiReviewCalculator;
import com.data.collection.platform.bi.domain.BiSystemTestCalculator;
import com.data.collection.platform.bi.domain.BiSystemTestCauseClassifier;
import com.data.collection.platform.bi.domain.BiSystemTestDelayCauseClassifier;
import com.data.collection.platform.bi.domain.port.BiCatTestSourcePort;
import com.data.collection.platform.bi.domain.port.BiReviewSourcePort;
import com.data.collection.platform.service.CodeReviewMatchModeSwitchService;
import com.data.collection.platform.service.GitlabConfigService;
import com.data.collection.platform.service.GitlabMergeRequestCommitFactCapability;
import com.data.collection.platform.service.IssueScopeCatalogService;
import com.data.collection.platform.service.PageRecordSnapshotService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 第一次已授权 BI 请求到达时显式装配普通 Java 运行对象。 */
@Component
public class BiDashboardRuntimeFactory {
  private final JdbcTemplate jdbcTemplate;
  private final IssueScopeCatalogService catalogService;
  private final PageRecordSnapshotService snapshotService;
  private final CodeReviewMatchModeSwitchService matchModeSwitchService;
  private final GitlabConfigService gitlabConfigService;
  private final BiCatMirrorManager catMirrorManager;

  public BiDashboardRuntimeFactory(
      JdbcTemplate jdbcTemplate,
      IssueScopeCatalogService catalogService,
      PageRecordSnapshotService snapshotService,
      CodeReviewMatchModeSwitchService matchModeSwitchService,
      GitlabConfigService gitlabConfigService,
      BiCatMirrorManager catMirrorManager) {
    this.jdbcTemplate = jdbcTemplate;
    this.catalogService = catalogService;
    this.snapshotService = snapshotService;
    this.matchModeSwitchService = matchModeSwitchService;
    this.gitlabConfigService = gitlabConfigService;
    this.catMirrorManager = catMirrorManager;
  }

  /** 创建一个没有启动期副作用、没有后台线程和没有网络调用的 BI Runtime。 */
  public BiDashboardRuntime create() {
    // 本层流程：先创建版本目录和数据源，再为每个页面绑定计算器与页面服务，
    // 最后将完整运行时交给 RuntimeManager；Controller 不感知具体依赖。
    // 先组装稳定的产品版本目录和平台事实入口；这些入口只读平台已发布数据。
    var matcher = new BiProductVersionMatcher();
    var versions = new BiPlatformProductVersionAdapter(catalogService);
    var reviews = new BiPlatformReviewSourceAdapter(jdbcTemplate, snapshotService, matcher);
    var commits = new BiCodingCommitFactRepository(jdbcTemplate);
    var codeReviewSourceContextFactory = new BiPlatformCodeReviewSourceContextFactory(
        matchModeSwitchService::isCodeReviewCompatibilityReadEnabled,
        snapshotService,
        () -> "compatibility",
        () ->
            GitlabMergeRequestCommitFactCapability.isEnabled(
                gitlabConfigService.getConfig()),
        commits::sourceVersion);
    var coding = new BiPlatformCodingSourceAdapter(
        jdbcTemplate, codeReviewSourceContextFactory, matcher, commits);
    // 系统测试的原因分类器属于 BI 规则，不把显示分类反向写入平台事实表。
    var systemTest = new BiPlatformSystemTestSourceAdapter(
        jdbcTemplate,
        snapshotService,
        new BiSystemTestCauseClassifier(),
        new BiSystemTestDelayCauseClassifier());
    var catRepository = catMirrorManager.repository();
    // CAT 页面只读取已发布镜像，避免请求链路直接依赖 CAT 网络状态或半成品响应。
    var cat = new BiCatTestSourceAdapter(catRepository);
    var currentVersions = new BiPlatformCurrentSourceVersionAdapter(
        snapshotService, codeReviewSourceContextFactory, catRepository);
    // 最后把“版本范围 -> 数据源 -> 计算器 -> 页面服务”逐页绑定，Controller 不感知具体实现。
    return new BiDashboardRuntime(
        new BiVersionService(versions),
        new BiReviewPageService(
            BiReviewSourcePort.ReviewStage.REQUIREMENTS, versions, reviews, new BiReviewCalculator()),
        new BiReviewPageService(
            BiReviewSourcePort.ReviewStage.DESIGN, versions, reviews, new BiReviewCalculator()),
        new BiCodingPageService(versions, coding, new BiCodingCalculator()),
        new BiCatTestPageService(BiCatTestSourcePort.TestStage.UNIT_TEST, versions, cat),
        new BiCatTestPageService(BiCatTestSourcePort.TestStage.INTEGRATION_TEST, versions, cat),
        new BiSystemTestPageService(versions, systemTest, new BiSystemTestCalculator()),
        new BiDownloadAuthorizationService(versions, currentVersions));
  }
}
