package com.data.collection.platform.service.labelgroup;

import com.data.collection.platform.entity.read.ReadSelection;
import com.data.collection.platform.service.read.PublishedReadService;
import com.data.collection.platform.service.read.ReadViewPreparationService;
import com.data.collection.platform.service.read.ReadViewFreshnessService;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Service;

/** 设置页候选值的完整读取边界；准备事务提交租约后再读取，不把内部发布身份交给调用者。 */
@Service
public class LabelValuePublishedReader {
  private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(LabelValuePublishedReader.class);
  private final PublishedReadService reads;
  private final ReadViewPreparationService preparation;
  private final ReadViewFreshnessService selections;
  private final org.springframework.transaction.support.TransactionTemplate cleanup;

  public LabelValuePublishedReader(PublishedReadService reads, ReadViewPreparationService preparation,
      ReadViewFreshnessService selections, org.springframework.transaction.PlatformTransactionManager transactions) {
    this.reads = reads; this.preparation = preparation; this.selections = selections;
    this.cleanup = new org.springframework.transaction.support.TransactionTemplate(transactions);
    this.cleanup.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  /** 每次候选值查询固定完整来源组合；与原设置页授权一致，内部租约读取结束立即释放。 */
  public <T> T read(String dimension, String page, String source, Function<ReadSelection, T> reader) {
    java.util.Map<String,String> scope = new java.util.TreeMap<>();
    scope.put("dimensionKey", dimension);
    if (page != null && !page.isBlank()) scope.put("candidatePage",page);
    if (source != null && !source.isBlank()) scope.put("sourceInstance",
        com.data.collection.platform.service.GitlabSourceInstanceSupport.normalizeSourceInstance(source));
    return readPrepared("label-group-values", scope, reader);
  }

  /** 动态规则的根来源和关系来源一次冻结，计数、预览与落成员共用该组合。 */
  public <T> T readDynamic(java.util.Set<String> sourceKeys, Function<ReadSelection,T> reader) {
    if (sourceKeys.isEmpty()) throw new IllegalArgumentException("动态规则必须声明实际来源");
    return readPrepared("label-group-dynamic-values", java.util.Map.of("dynamicSources",String.join(",",new java.util.TreeSet<>(sourceKeys))),reader);
  }

  private <T> T readPrepared(String page, java.util.Map<String,String> scope, Function<ReadSelection,T> reader) {
    String owner = "label-group-control";
    var view = preparation.prepare(owner, owner, page, scope.toString(),scope,UUID.randomUUID());
    try { return reads.withinPublishedRead(view, fixed -> reader.apply(selections.selection(fixed))); }
    finally {
      try { cleanup.executeWithoutResult(status -> reads.releaseView(view.viewId(), owner, owner)); }
      catch (RuntimeException failure) {
        // 清理失败不覆盖查询原错误或撤销已返回的完整数据；固定租约到期维护负责兜底。
        LOGGER.warn("标签候选内部租约清理未完成，交到期维护，view={}",view.viewId(),failure);
      }
    }
  }
}
