package com.data.collection.platform.service.read;

import com.data.collection.platform.entity.FactType;
import com.data.collection.platform.entity.read.ReadPublicationDomainKey;
import com.data.collection.platform.entity.read.ReadPublicationProducerKind;
import com.data.collection.platform.service.sync.SyncFactPublicationStateService;
import org.springframework.stereotype.Component;

/**
 * 初始完整版本的默认采纳判定：复用平台既有的来源对外可读资格，并额外要求**存在**可证明的完整基线证据。
 *
 * <p>只有来源当前事实可作完整来源输出（依赖已就绪、无未结算全量意图、无未发布根、标签历史已核验），
 * 且已登记 READY 发布状态 / 各配置完整依赖目录全部就绪 / 变化头收敛 / 无在飞事实构建任务时，才允许初始接管。
 * 此判定本身不锁输入，接管服务须在同一稳定输入栅栏内核验与采纳。
 * 仅库中有事实、却缺少依赖或发布证据的“未知基线”一律拒绝；没有可采纳版本时保持首次产出边界，待合格全量发布。
 */
@Component
public class SyncFactPublicationVerification implements InitialVersionVerification {
  private final SyncFactPublicationStateService publicationStateService;

  public SyncFactPublicationVerification(
      SyncFactPublicationStateService publicationStateService) {
    this.publicationStateService = publicationStateService;
  }

  @Override
  public VerificationResult verify(ReadPublicationDomainKey domainKey) {
    if (domainKey.producerKind() != ReadPublicationProducerKind.GITLAB_FACT) {
      return VerificationResult.refused("暂不支持为以下域建立初始版本：" + domainKey.producerKind());
    }
    FactType factType;
    try {
      factType = FactType.valueOf(domainKey.factType());
    } catch (IllegalArgumentException error) {
      return VerificationResult.refused("未知事实类型，暂不建立初始版本：" + domainKey.factType());
    }
    SyncFactPublicationStateService.SourceQualification qualification =
        publicationStateService.qualification(domainKey.sourceInstance(), factType);
    if (!qualification.currentFactReadable()) {
      return VerificationResult.refused(qualification.reason());
    }
    if (!publicationStateService.hasCompleteInitialVersionEvidence(
        domainKey.sourceInstance(), factType)) {
      return VerificationResult.refused(
          "来源 "
              + domainKey.sourceInstance()
              + " 缺少可证明的完整基线证据（依赖代际 / 发布状态 / 收敛水位 / 在飞任务），暂不建立初始版本");
    }
    return VerificationResult.accepted();
  }
}
