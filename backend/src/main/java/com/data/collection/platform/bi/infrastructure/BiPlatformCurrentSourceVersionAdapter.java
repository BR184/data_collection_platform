package com.data.collection.platform.bi.infrastructure;

import com.data.collection.platform.bi.domain.port.BiCatTestSourcePort;
import com.data.collection.platform.bi.domain.port.BiCurrentSourceVersionPort;
import com.data.collection.platform.bi.domain.source.BiProductVersionScope;
import com.data.collection.platform.service.PageRecordSnapshotService;

/** 使用平台发布版本服务核对 BI 页面来源身份。 */
public final class BiPlatformCurrentSourceVersionAdapter implements BiCurrentSourceVersionPort {
  private final PageRecordSnapshotService snapshotService;
  private final BiPlatformCodeReviewSourceContextFactory codeReviewSourceContextFactory;
  private final BiCatMirrorRepository catRepository;
  private final com.data.collection.platform.entity.read.ReadSelection fixedRead;

  /** 下载核验当前页面保留版本；最新发布不应使原图的指纹失效。 */
  public BiPlatformCurrentSourceVersionAdapter forRead(com.data.collection.platform.entity.read.ReadSelection selection) {
    return new BiPlatformCurrentSourceVersionAdapter(this, selection);
  }

  private BiPlatformCurrentSourceVersionAdapter(BiPlatformCurrentSourceVersionAdapter factory,
      com.data.collection.platform.entity.read.ReadSelection selection) {
    this.snapshotService = factory.snapshotService;
    this.codeReviewSourceContextFactory = factory.codeReviewSourceContextFactory;
    this.catRepository = factory.catRepository.forRead(selection);
    this.fixedRead = java.util.Objects.requireNonNull(selection);
  }

  public BiPlatformCurrentSourceVersionAdapter(
      PageRecordSnapshotService snapshotService,
      BiPlatformCodeReviewSourceContextFactory codeReviewSourceContextFactory,
      BiCatMirrorRepository catRepository) {
    this.snapshotService = snapshotService;
    this.codeReviewSourceContextFactory = codeReviewSourceContextFactory;
    this.catRepository = catRepository;
    this.fixedRead = null;
  }

  @Override
  public String current(String pageKey, BiProductVersionScope scope) {
    java.util.Objects.requireNonNull(fixedRead, "BI 下载核验必须绑定页面读取身份");
    return switch (pageKey) {
      case "requirements", "design", "coding", "system-test" ->
          fixedRead.sourceVersion() + "|scope:" + scope.fingerprint();
      case "unit-test" -> catRepository.requireCurrentSourceVersion(
          scope, BiCatTestSourcePort.TestStage.UNIT_TEST.name());
      case "integration-test" -> catRepository.requireCurrentSourceVersion(
          scope, BiCatTestSourcePort.TestStage.INTEGRATION_TEST.name());
      default -> throw new IllegalArgumentException("不支持的 BI 页面：" + pageKey);
    };
  }
}
