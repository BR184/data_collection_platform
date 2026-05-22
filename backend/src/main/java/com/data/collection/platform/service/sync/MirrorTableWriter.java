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
