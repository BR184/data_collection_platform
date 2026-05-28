package com.data.collection.platform.service.sync;

import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.MirrorPrimaryKeyBatch;
import com.data.collection.platform.entity.SourceTableSchema;
import com.data.collection.platform.entity.TableWhitelistOption;
import com.data.collection.platform.entity.sync.SyncRunTableTask;
import com.data.collection.platform.service.PrimaryKeySignatureSupport;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class MirrorReconciliationService {
  private final SourceTableReader sourceTableReader;
  private final MirrorTableWriter mirrorTableWriter;
  private final SyncRunTableTaskLeaseService taskLeaseService;

  public MirrorReconciliationService(
      SourceTableReader sourceTableReader,
      MirrorTableWriter mirrorTableWriter,
      SyncRunTableTaskLeaseService taskLeaseService) {
    this.sourceTableReader = sourceTableReader;
    this.mirrorTableWriter = mirrorTableWriter;
    this.taskLeaseService = taskLeaseService;
  }

  public ReconciliationResult reconcileMirrorExtras(
      GitlabSyncConfig config,
      TableWhitelistOption option,
      SourceTableSchema mirrorSchema,
      SyncRunTableTask task,
      int batchSize) {
    String cursor = null;
    long scannedRows = 0L;
    long deletedRows = 0L;
    do {
      if (taskLeaseService.isRunCancellationRequested(task.getRunId())) {
        return new ReconciliationResult(scannedRows, deletedRows);
      }
      MirrorPrimaryKeyBatch batch = mirrorTableWriter.listActivePrimaryKeys(mirrorSchema, cursor, batchSize);
      if (batch == null || batch.keys() == null || batch.keys().isEmpty()) {
        return new ReconciliationResult(scannedRows, deletedRows);
      }
      scannedRows += batch.keys().size();
      Set<String> existingSourceKeys = sourceTableReader.findExistingPrimaryKeySignatures(config, option, batch.keys());
      List<Map<String, Object>> mirrorOnlyRows =
          batch.keys().stream()
              .filter(
                  keyRow ->
                      !existingSourceKeys.contains(
                          PrimaryKeySignatureSupport.signature(mirrorSchema.primaryKeys(), keyRow)))
              .toList();
      if (!mirrorOnlyRows.isEmpty()) {
        deletedRows += mirrorTableWriter.markRowsDeletedByPrimaryKeys(mirrorSchema, mirrorOnlyRows, task.getId());
      }
      cursor = batch.nextCursor();
    } while (cursor != null && !cursor.isBlank());
    return new ReconciliationResult(scannedRows, deletedRows);
  }

  public record ReconciliationResult(long scannedRows, long deletedRows) {
  }
}
