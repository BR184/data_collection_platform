package com.data.collection.platform.bi.infrastructure;

import com.data.collection.platform.bi.domain.port.BiCatTestSourcePort;
import com.data.collection.platform.bi.domain.port.BiCurrentSourceVersionPort;
import com.data.collection.platform.bi.domain.source.BiProductVersionScope;
import com.data.collection.platform.service.GitlabSourceInstanceSupport;
import com.data.collection.platform.service.IssueScopeDimension;
import com.data.collection.platform.service.PageRecordSnapshotService;

/** 使用平台发布版本服务核对 BI 页面来源身份。 */
public final class BiPlatformCurrentSourceVersionAdapter implements BiCurrentSourceVersionPort {
  private final PageRecordSnapshotService snapshotService;
  private final BiPlatformCodeReviewSourceContextFactory codeReviewSourceContextFactory;
  private final BiCatMirrorRepository catRepository;

  public BiPlatformCurrentSourceVersionAdapter(
      PageRecordSnapshotService snapshotService,
      BiPlatformCodeReviewSourceContextFactory codeReviewSourceContextFactory,
      BiCatMirrorRepository catRepository) {
    this.snapshotService = snapshotService;
    this.codeReviewSourceContextFactory = codeReviewSourceContextFactory;
    this.catRepository = catRepository;
  }

  @Override
  public String current(String pageKey, BiProductVersionScope scope) {
    return switch (pageKey) {
      case "requirements", "design" -> snapshotService.reviewDataSourceVersion();
      case "coding" -> codeReviewSourceContextFactory.capture().sourceVersion();
      case "system-test" -> snapshotService.issueFactSourceVersion(
          GitlabSourceInstanceSupport.DEFAULT_SOURCE_INSTANCE,
          scope.projectId(),
          IssueScopeDimension.TESTING_PHASE,
          scope.businessKey());
      case "unit-test" -> catRepository.requireCurrentSourceVersion(
          scope, BiCatTestSourcePort.TestStage.UNIT_TEST.name());
      case "integration-test" -> catRepository.requireCurrentSourceVersion(
          scope, BiCatTestSourcePort.TestStage.INTEGRATION_TEST.name());
      default -> throw new IllegalArgumentException("不支持的 BI 页面：" + pageKey);
    };
  }
}
