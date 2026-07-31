package com.data.collection.platform.service.sync;

import com.data.collection.platform.entity.MirrorBatchWriteResult;
import com.data.collection.platform.entity.MirrorPrimaryKeyBatch;
import com.data.collection.platform.entity.SourceTableSchema;
import com.data.collection.platform.service.GitlabMirrorTableStorageService;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class MirrorTableWriter {
  private final GitlabMirrorTableStorageService storageService;

  public MirrorTableWriter(GitlabMirrorTableStorageService storageService) {
    this.storageService = storageService;
  }

  public MirrorBatchWriteResult writeBatch(
      SourceTableSchema mirrorSchema,
      List<Map<String, Object>> rows,
      Long taskId) {
    return storageService.upsertBatch(mirrorSchema, rows, taskId);
  }

  public MirrorBatchWriteResult writeBatch(
      SourceTableSchema mirrorSchema,
      List<Map<String, Object>> rows,
      Long taskId,
      boolean forceUpdate) {
    return storageService.upsertBatch(mirrorSchema, rows, taskId, forceUpdate);
  }

  /**
   * 将指定 lookup 范围按来源完整集合原子替换，支持新增、替换和清空关系。
   *
   * @param mirrorSchema 镜像表结构
   * @param lookupScope 完整范围列和值
   * @param rows 来源完整范围集合
   * @param taskId 当前同步任务编号
   * @return 来源行数及该范围实际写入、删除数量
   */
  public MirrorBatchWriteResult replaceAuthoritativeScope(
      SourceTableSchema mirrorSchema,
      Map<String, Object> lookupScope,
      List<Map<String, Object>> rows,
      Long taskId) {
    return storageService.replaceAuthoritativeScope(
        mirrorSchema, lookupScope, rows, taskId);
  }

  public MirrorPrimaryKeyBatch listActivePrimaryKeys(
      SourceTableSchema mirrorSchema,
      String cursor,
      int batchSize) {
    return storageService.listActivePrimaryKeys(mirrorSchema, cursor, batchSize);
  }

  public int markRowsDeletedByPrimaryKeys(
      SourceTableSchema mirrorSchema,
      List<Map<String, Object>> primaryKeyRows,
      Long taskId) {
    return storageService.markRowsDeletedByPrimaryKeys(mirrorSchema, primaryKeyRows, taskId);
  }
}
