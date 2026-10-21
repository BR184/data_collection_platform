package com.data.collection.platform.service.read;

/**
 * 一次域内回收的结果。
 *
 * @param retiringPublications 本轮标记为正在回收（RETIRING）的历史版本数
 * @param deletedRevisions 本轮删除的不再被任何保留序号覆盖的修订行数
 * @param removedPublications 本轮删除元数据行的历史版本数
 */
public record ReadGcResult(
    int retiringPublications, int deletedRevisions, int removedPublications) {

  /** 空结果。 */
  public static ReadGcResult empty() {
    return new ReadGcResult(0, 0, 0);
  }
}
