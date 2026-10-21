package com.data.collection.platform.bi.infrastructure;

import com.data.collection.platform.service.PageRecordSnapshotService;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** 在 BI 请求边界冻结平台代码走查读源及其来源版本。 */
public final class BiPlatformCodeReviewSourceContextFactory {
  private final BooleanSupplier compatibilityModeEnabled;
  private final PageRecordSnapshotService snapshotService;
  private final Supplier<String> compatibilityReviewVersion;
  private final BooleanSupplier commitDetailsAvailable;
  private final Supplier<String> commitVersion;
  private final com.data.collection.platform.entity.read.DatasetReadSelection fixedRead;

  /** 模式、提交能力与来源指纹由同一个完整页面组合冻结，捕获后不再检查最新指针。 */
  public BiPlatformCodeReviewSourceContextFactory forRead(
      com.data.collection.platform.entity.read.DatasetReadSelection selection, boolean compatibility, boolean commitsAvailable) {
    return new BiPlatformCodeReviewSourceContextFactory(this, selection, compatibility, commitsAvailable);
  }

  private BiPlatformCodeReviewSourceContextFactory(BiPlatformCodeReviewSourceContextFactory factory,
      com.data.collection.platform.entity.read.DatasetReadSelection selection, boolean compatibility, boolean commitsAvailable) {
    this.compatibilityModeEnabled = () -> compatibility;
    this.snapshotService = factory.snapshotService;
    this.compatibilityReviewVersion = factory.compatibilityReviewVersion;
    this.commitDetailsAvailable = () -> commitsAvailable;
    this.commitVersion = factory.commitVersion;
    this.fixedRead = Objects.requireNonNull(selection);
  }

  /**
   * 创建请求级读源上下文工厂。
   *
   * @param compatibilityModeEnabled 平台唯一兼容模式读取函数
   * @param snapshotService 平台已发布代码走查快照版本服务
   */
  public BiPlatformCodeReviewSourceContextFactory(
      BooleanSupplier compatibilityModeEnabled,
      PageRecordSnapshotService snapshotService,
      Supplier<String> compatibilityReviewVersion,
      BooleanSupplier commitDetailsAvailable,
      Supplier<String> commitVersion) {
    this.compatibilityModeEnabled = Objects.requireNonNull(
        compatibilityModeEnabled, "compatibilityModeEnabled");
    this.snapshotService = Objects.requireNonNull(snapshotService, "snapshotService");
    this.compatibilityReviewVersion =
        Objects.requireNonNull(compatibilityReviewVersion, "compatibilityReviewVersion");
    this.commitDetailsAvailable =
        Objects.requireNonNull(commitDetailsAvailable, "commitDetailsAvailable");
    this.commitVersion = Objects.requireNonNull(commitVersion, "commitVersion");
    this.fixedRead = null;
  }

  /**
   * 读取一次平台开关并冻结本次请求的唯一读源。
   *
   * @return 同时包含物理读源、基础快照版本和对外来源版本的不可变上下文
   */
  public Context capture() {
    ReadMode readMode = compatibilityModeEnabled.getAsBoolean()
        ? ReadMode.COMPATIBILITY
        : ReadMode.FORMAL;
    boolean commitsAvailable = commitDetailsAvailable.getAsBoolean();
    String dataVersion = Objects.requireNonNull(fixedRead, "BI 编码读取必须绑定完整页面身份").sourceVersion();
    return new Context(
        readMode,
        dataVersion,
        dataVersion,
        commitsAvailable);
  }

  /** BI 编码页允许选择的两个互斥平台读源。 */
  public enum ReadMode {
    FORMAL("formal"),
    COMPATIBILITY("compatibility");

    private final String versionPrefix;

    ReadMode(String versionPrefix) {
      this.versionPrefix = versionPrefix;
    }

    private String versionPrefix() {
      return versionPrefix;
    }
  }

  /** 单次请求冻结后的代码走查读源身份。 */
  public record Context(
      ReadMode readMode,
      String dataVersion,
      String sourceVersion,
      boolean commitDetailsAvailable) {
    public Context {
      Objects.requireNonNull(readMode, "readMode");
      Objects.requireNonNull(dataVersion, "dataVersion");
      Objects.requireNonNull(sourceVersion, "sourceVersion");
    }
  }
}
