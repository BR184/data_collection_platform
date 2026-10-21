package com.data.collection.platform.service.read;

import com.data.collection.platform.entity.read.ReadPublicationDomainKey;

/**
 * 初始完整版本的可采纳性判定端口。
 *
 * <p>安装阶段必须先在真实生产状态与稳定输入边界核验依赖、标签全量核验、待发布 heads、全量意图与数据关系，
 * 再把已有完整事实登记为初始版本。判定实现单独注入，避免读发布服务直接依赖具体来源实现。
 */
@FunctionalInterface
public interface InitialVersionVerification {

  /**
   * 判定该域当前是否可以作为初始完整版本采纳。
   *
   * @param domainKey 目标一致性域
   * @return 判定结果；{@code admissible=false} 时 {@code reason} 必须说明为什么暂不接管
   */
  VerificationResult verify(ReadPublicationDomainKey domainKey);

  /**
   * 判定结果。
   *
   * @param admissible 是否可采纳
   * @param reason 不可采纳的原因
   */
  record VerificationResult(boolean admissible, String reason) {
    public VerificationResult {
      if (!admissible && (reason == null || reason.isBlank())) {
        throw new IllegalArgumentException("不可采纳的初始版本必须给出原因");
      }
    }

    /** 可采纳。 */
    public static VerificationResult accepted() {
      return new VerificationResult(true, null);
    }

    /** 暂不可采纳。 */
    public static VerificationResult refused(String reason) {
      return new VerificationResult(false, reason);
    }
  }
}
