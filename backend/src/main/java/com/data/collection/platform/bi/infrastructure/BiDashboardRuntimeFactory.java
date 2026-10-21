package com.data.collection.platform.bi.infrastructure;

import com.data.collection.platform.bi.application.BiCatTestPageService;
import com.data.collection.platform.bi.application.BiCustomerIssuePageService;
import com.data.collection.platform.bi.application.BiCodingPageService;
import com.data.collection.platform.bi.application.BiDashboardRuntime;
import com.data.collection.platform.bi.application.BiDownloadAuthorizationService;
import com.data.collection.platform.bi.application.BiExcelExportService;
import com.data.collection.platform.bi.application.BiReviewPageService;
import com.data.collection.platform.bi.application.BiSystemTestPageService;
import com.data.collection.platform.bi.application.BiVersionService;
import com.data.collection.platform.bi.domain.BiCodingCalculator;
import com.data.collection.platform.bi.domain.BiCustomerIssueCalculator;
import com.data.collection.platform.bi.domain.BiProductVersionMatcher;
import com.data.collection.platform.bi.domain.BiReviewCalculator;
import com.data.collection.platform.bi.domain.BiSystemTestCalculator;
import com.data.collection.platform.bi.domain.BiSystemTestCauseClassifier;
import com.data.collection.platform.bi.domain.BiSystemTestDelayCauseClassifier;
import com.data.collection.platform.bi.domain.port.BiCatTestSourcePort;
import com.data.collection.platform.bi.domain.port.BiReviewSourcePort;
import com.data.collection.platform.service.BusinessDateProvider;
import com.data.collection.platform.service.CodeReviewMatchModeSwitchService;
import com.data.collection.platform.service.GitlabConfigService;
import com.data.collection.platform.service.GitlabMergeRequestCommitFactCapability;
import com.data.collection.platform.service.IssueScopeCatalogService;
import com.data.collection.platform.service.PageRecordSnapshotService;
import com.data.collection.platform.service.CustomerIssueFactQueryService;
import com.data.collection.platform.service.statistics.CustomerIssueMilestoneCatalogService;
import com.data.collection.platform.service.statistics.StatisticBoardReadScopeResolver;
import com.data.collection.platform.service.statistics.StatisticBoardSnapshotService;
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
  private final CustomerIssueFactQueryService customerIssueFactQueryService;
  private final CustomerIssueMilestoneCatalogService customerIssueMilestoneCatalogService;
  private final StatisticBoardSnapshotService statisticBoardSnapshotService;
  private final StatisticBoardReadScopeResolver statisticBoardReadScopeResolver;
  private final BusinessDateProvider businessDateProvider;
  private final com.data.collection.platform.entity.read.ReadSelection fixedRead;

  /** 每个页面请求创建持有固定组合的 Runtime，不把请求身份存入延迟共享 Runtime。 */
  public BiDashboardRuntimeFactory forRead(com.data.collection.platform.entity.read.ReadSelection selection) {
    return new BiDashboardRuntimeFactory(this, selection);
  }

  private BiDashboardRuntimeFactory(BiDashboardRuntimeFactory factory,
      com.data.collection.platform.entity.read.ReadSelection selection) {
    this.jdbcTemplate = factory.jdbcTemplate;
    this.catalogService = factory.catalogService.forRead(selection);
    this.snapshotService = factory.snapshotService;
    this.matchModeSwitchService = selection instanceof com.data.collection.platform.entity.read.DatasetReadSelection dataset
        && dataset.datasets().stream().anyMatch(input -> input.datasetKey().equals("formal-records"))
        ? factory.matchModeSwitchService.forRead(dataset) : factory.matchModeSwitchService;
    this.gitlabConfigService = factory.gitlabConfigService;
    this.catMirrorManager = factory.catMirrorManager;
    this.customerIssueFactQueryService = factory.customerIssueFactQueryService;
    this.customerIssueMilestoneCatalogService = factory.customerIssueMilestoneCatalogService;
    this.statisticBoardSnapshotService = factory.statisticBoardSnapshotService;
    this.statisticBoardReadScopeResolver = factory.statisticBoardReadScopeResolver;
    this.businessDateProvider = selection::businessDate;
    this.fixedRead = java.util.Objects.requireNonNull(selection);
  }

  @org.springframework.beans.factory.annotation.Autowired
  public BiDashboardRuntimeFactory(
      JdbcTemplate jdbcTemplate,
      IssueScopeCatalogService catalogService,
      PageRecordSnapshotService snapshotService,
      CodeReviewMatchModeSwitchService matchModeSwitchService,
      GitlabConfigService gitlabConfigService,
      BiCatMirrorManager catMirrorManager,
      CustomerIssueFactQueryService customerIssueFactQueryService,
      CustomerIssueMilestoneCatalogService customerIssueMilestoneCatalogService,
      StatisticBoardSnapshotService statisticBoardSnapshotService,
      StatisticBoardReadScopeResolver statisticBoardReadScopeResolver,
      BusinessDateProvider businessDateProvider) {
    this.jdbcTemplate = jdbcTemplate;
    this.catalogService = catalogService;
    this.snapshotService = snapshotService;
    this.matchModeSwitchService = matchModeSwitchService;
    this.gitlabConfigService = gitlabConfigService;
    this.catMirrorManager = catMirrorManager;
    this.customerIssueFactQueryService = customerIssueFactQueryService;
    this.customerIssueMilestoneCatalogService = customerIssueMilestoneCatalogService;
    this.statisticBoardSnapshotService = statisticBoardSnapshotService;
    this.statisticBoardReadScopeResolver = statisticBoardReadScopeResolver;
    this.businessDateProvider = businessDateProvider;
    this.fixedRead = null;
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
    if (fixedRead instanceof com.data.collection.platform.entity.read.DatasetReadSelection dataset
        && dataset.datasets().stream().anyMatch(input -> input.datasetKey().equals("formal-records"))) {
      reviews = reviews.forRead(dataset);
      codeReviewSourceContextFactory = codeReviewSourceContextFactory.forRead(dataset,
          matchModeSwitchService.isCodeReviewCompatibilityReadEnabled(), frozenCommitCapability(dataset));
      coding = coding.forRead(dataset, codeReviewSourceContextFactory);
    }
    // 系统测试的原因分类器属于 BI 规则，不把显示分类反向写入平台事实表。
    var systemTest = new BiPlatformSystemTestSourceAdapter(
        jdbcTemplate,
        snapshotService,
        new BiSystemTestCauseClassifier(),
        new BiSystemTestDelayCauseClassifier());
    com.data.collection.platform.entity.read.FactReadSelection facts = fixedRead instanceof com.data.collection.platform.entity.read.FactReadSelection fact
        ? fact : fixedRead instanceof com.data.collection.platform.entity.read.DatasetReadSelection dataset ? dataset.facts() : null;
    if (facts != null) { systemTest = systemTest.forRead(facts); }
    var catRepository = catMirrorManager.repository();
    // CAT 页面只读取已发布镜像，避免请求链路直接依赖 CAT 网络状态或半成品响应。
    var cat = new BiCatTestSourceAdapter(catRepository);
    if (fixedRead != null) { cat = cat.forRead(fixedRead); }
    var currentVersions = new BiPlatformCurrentSourceVersionAdapter(
        snapshotService, codeReviewSourceContextFactory, catRepository);
    if (fixedRead != null) { currentVersions = currentVersions.forRead(fixedRead); }
    var customerIssues = new BiCustomerIssuePageService(
        customerIssueFactQueryService,
        customerIssueMilestoneCatalogService,
        statisticBoardSnapshotService,
        statisticBoardReadScopeResolver,
        businessDateProvider,
        new BiCustomerIssueCalculator());
    if (facts != null) { customerIssues = customerIssues.forRead(facts); }
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
        customerIssues,
        new BiDownloadAuthorizationService(versions, currentVersions, customerIssues),
        new BiExcelExportService());
  }

  private boolean frozenCommitCapability(com.data.collection.platform.entity.read.DatasetReadSelection selection) {
    if (selection.facts() == null) { return false; }
    for (var context : selection.facts().contexts()) {
      if (context.table() != com.data.collection.platform.entity.read.PublicationFactTable.MERGE_REQUEST) { continue; }
      String configs = jdbcTemplate.queryForObject("select d.payload->'configs' from read_publications p"
          + " join read_definition_snapshots d on d.id = p.definition_snapshot_id where p.id = ?", String.class, context.publicationId());
      try {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var entries = mapper.readTree(configs);
        if (!entries.isArray() || entries.isEmpty()) { return false; }
        for (var config : entries) {
          if (!"CUSTOM".equals(config.path("whitelistMode").asText())) { continue; }
          var tables = config.path("whitelistTables");
          if (tables.isTextual()) { tables = mapper.readTree(tables.asText()); }
          java.util.Set<String> names = new java.util.HashSet<>();
          tables.forEach(table -> names.add(com.data.collection.platform.service.GitlabSourceInstanceSupport.normalizeSourceTableName(table.asText())));
          if (!names.containsAll(java.util.Set.of("merge_request_diffs", "merge_request_diff_commits"))) { return false; }
        }
      } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
        throw new IllegalStateException("已发布的提交来源能力定义无法解析", error);
      }
    }
    return true;
  }
}
