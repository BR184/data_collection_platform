package com.data.collection.platform.entity.read;

import com.data.collection.platform.service.GitlabSourceInstanceSupport;
import java.util.UUID;

/**
 * 候选变更清单条目：一个业务根在候选中的处理结果。
 *
 * <p>记录根级 {@code capturedChangeVersion}，避免只保存一个最大值后把未构建的根全部结算。
 *
 * @param publicationId 所属候选发布身份
 * @param sourceSystem 来源系统（业务键的一部分）
 * @param sourceInstance 来源实例
 * @param factType 事实类型
 * @param projectId 项目标识；完整业务身份的一部分，兼容正式导入沿用 0 表示未关联项目
 * @param rootId 业务根标识
 * @param capturedChangeVersion 该根被捕获时对应的变化水位
 * @param previousRevisionId 修订前的物理修订身份；新增时为空
 * @param nextRevisionId 本候选产生的物理修订身份；删除时为空
 * @param operation 操作类型
 */
public record FactRootEntry(
    UUID publicationId,
    String sourceSystem,
    String sourceInstance,
    String factType,
    long projectId,
    long rootId,
    long capturedChangeVersion,
    Long previousRevisionId,
    Long nextRevisionId,
    FactRootOperation operation) {

  public FactRootEntry {
    if (publicationId == null || operation == null) {
      throw new IllegalArgumentException("候选根清单条目必须声明候选身份与操作类型");
    }
    if (rootId <= 0L) {
      throw new IllegalArgumentException("候选根清单条目必须带有效的业务根标识");
    }
    if (projectId < 0L) {
      throw new IllegalArgumentException("候选根清单条目必须带有效的项目标识");
    }
    if (capturedChangeVersion < 0L) {
      throw new IllegalArgumentException("候选根清单条目的捕获水位不得为负");
    }
    sourceSystem = sourceSystem == null || sourceSystem.isBlank() ? "GITLAB" : sourceSystem.trim();
    factType = factType == null ? "" : factType.trim();
    sourceInstance = GitlabSourceInstanceSupport.normalizeSourceInstance(sourceInstance);
  }
}
