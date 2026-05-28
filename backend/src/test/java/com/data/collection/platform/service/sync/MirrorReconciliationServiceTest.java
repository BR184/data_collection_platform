package com.data.collection.platform.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.MirrorPrimaryKeyBatch;
import com.data.collection.platform.entity.SourceMode;
import com.data.collection.platform.entity.SourceTableColumn;
import com.data.collection.platform.entity.SourceTableSchema;
import com.data.collection.platform.entity.TableWhitelistOption;
import com.data.collection.platform.entity.WhitelistMode;
import com.data.collection.platform.entity.sync.SyncRunTableTask;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MirrorReconciliationServiceTest {
  private SourceTableReader sourceTableReader;
  private MirrorTableWriter mirrorTableWriter;
  private SyncRunTableTaskLeaseService taskLeaseService;
  private MirrorReconciliationService service;

  @BeforeEach
  void setUp() {
    sourceTableReader = org.mockito.Mockito.mock(SourceTableReader.class);
    mirrorTableWriter = org.mockito.Mockito.mock(MirrorTableWriter.class);
    taskLeaseService = org.mockito.Mockito.mock(SyncRunTableTaskLeaseService.class);
    service = new MirrorReconciliationService(sourceTableReader, mirrorTableWriter, taskLeaseService);
  }

  @Test
  void shouldDeleteMirrorRowsMissingFromSourceAcrossPrimaryKeyPages() {
    GitlabSyncConfig config = config();
    TableWhitelistOption option = option();
    SourceTableSchema mirrorSchema = mirrorSchema();
    SyncRunTableTask task = task();
    List<Map<String, Object>> firstPageKeys = List.of(Map.of("id", "101"), Map.of("id", "999"));
    List<Map<String, Object>> secondPageKeys = List.of(Map.of("id", "102"), Map.of("id", "998"));
    List<Map<String, Object>> firstMirrorOnlyKeys = List.of(Map.of("id", "999"));
    List<Map<String, Object>> secondMirrorOnlyKeys = List.of(Map.of("id", "998"));

    when(taskLeaseService.isRunCancellationRequested(77L)).thenReturn(false, false);
    when(mirrorTableWriter.listActivePrimaryKeys(mirrorSchema, null, 2))
        .thenReturn(new MirrorPrimaryKeyBatch(firstPageKeys, "cursor-2"));
    when(mirrorTableWriter.listActivePrimaryKeys(mirrorSchema, "cursor-2", 2))
        .thenReturn(new MirrorPrimaryKeyBatch(secondPageKeys, null));
    when(sourceTableReader.findExistingPrimaryKeySignatures(config, option, firstPageKeys))
        .thenReturn(Set.of("101"));
    when(sourceTableReader.findExistingPrimaryKeySignatures(config, option, secondPageKeys))
        .thenReturn(Set.of("102"));
    when(mirrorTableWriter.markRowsDeletedByPrimaryKeys(mirrorSchema, firstMirrorOnlyKeys, 501L))
        .thenReturn(1);
    when(mirrorTableWriter.markRowsDeletedByPrimaryKeys(mirrorSchema, secondMirrorOnlyKeys, 501L))
        .thenReturn(1);

    MirrorReconciliationService.ReconciliationResult result =
        service.reconcileMirrorExtras(config, option, mirrorSchema, task, 2);

    assertThat(result.scannedRows()).isEqualTo(4);
    assertThat(result.deletedRows()).isEqualTo(2);
    verify(mirrorTableWriter).markRowsDeletedByPrimaryKeys(mirrorSchema, firstMirrorOnlyKeys, 501L);
    verify(mirrorTableWriter).markRowsDeletedByPrimaryKeys(mirrorSchema, secondMirrorOnlyKeys, 501L);
  }

  @Test
  void shouldStopBeforeScanningMirrorKeysWhenRunIsCancelling() {
    when(taskLeaseService.isRunCancellationRequested(77L)).thenReturn(true);

    MirrorReconciliationService.ReconciliationResult result =
        service.reconcileMirrorExtras(config(), option(), mirrorSchema(), task(), 500);

    assertThat(result.scannedRows()).isZero();
    assertThat(result.deletedRows()).isZero();
    verify(mirrorTableWriter, never()).listActivePrimaryKeys(any(), any(), eq(500));
    verify(sourceTableReader, never()).findExistingPrimaryKeySignatures(any(), any(), any());
  }

  @Test
  void shouldNotDeleteRowsWhenAllMirrorKeysExistInSource() {
    GitlabSyncConfig config = config();
    TableWhitelistOption option = option();
    SourceTableSchema mirrorSchema = mirrorSchema();
    SyncRunTableTask task = task();
    List<Map<String, Object>> keys = List.of(Map.of("id", "101"), Map.of("id", "102"));

    when(taskLeaseService.isRunCancellationRequested(77L)).thenReturn(false);
    when(mirrorTableWriter.listActivePrimaryKeys(mirrorSchema, null, 500))
        .thenReturn(new MirrorPrimaryKeyBatch(keys, null));
    when(sourceTableReader.findExistingPrimaryKeySignatures(config, option, keys))
        .thenReturn(Set.of("101", "102"));

    MirrorReconciliationService.ReconciliationResult result =
        service.reconcileMirrorExtras(config, option, mirrorSchema, task, 500);

    assertThat(result.scannedRows()).isEqualTo(2);
    assertThat(result.deletedRows()).isZero();
    verify(mirrorTableWriter, never()).markRowsDeletedByPrimaryKeys(any(), any(), any());
  }

  private GitlabSyncConfig config() {
    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setId(1L);
    config.setSourceInstance("alpha");
    config.setSourceMode(SourceMode.DOCKER);
    config.setWhitelistMode(WhitelistMode.RECOMMENDED);
    return config;
  }

  private TableWhitelistOption option() {
    return new TableWhitelistOption("issues", "issues", "id", "updated_at", true);
  }

  private SourceTableSchema mirrorSchema() {
    return new SourceTableSchema(
        "ods_gitlab_alpha_issues",
        List.of("id"),
        "updated_at",
        List.of(
            new SourceTableColumn("id", "bigint", false, 1),
            new SourceTableColumn("updated_at", "timestamp without time zone", true, 2)));
  }

  private SyncRunTableTask task() {
    SyncRunTableTask task = new SyncRunTableTask();
    task.setId(501L);
    task.setRunId(77L);
    return task;
  }
}
