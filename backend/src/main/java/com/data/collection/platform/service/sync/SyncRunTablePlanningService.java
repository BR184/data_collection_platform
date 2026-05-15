package com.data.collection.platform.service.sync;

import com.data.collection.platform.common.JsonUtils;
import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.entity.sync.SyncRun;
import com.data.collection.platform.entity.sync.SyncRunStatus;
import com.data.collection.platform.entity.sync.SyncRunTableState;
import com.data.collection.platform.entity.sync.SyncRunTableTask;
import com.data.collection.platform.mapper.SyncRunMapper;
import com.data.collection.platform.mapper.SyncRunTableStateMapper;
import com.data.collection.platform.mapper.SyncRunTableTaskMapper;
import com.data.collection.platform.service.GitlabSourceInstanceSupport;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class SyncRunTablePlanningService {
  private final SyncRunMapper syncRunMapper;
  private final SyncRunTableStateMapper stateMapper;
  private final SyncRunTableTaskMapper taskMapper;
  private final JsonUtils jsonUtils;

  public SyncRunTablePlanningService(
      SyncRunMapper syncRunMapper,
      SyncRunTableStateMapper stateMapper,
      SyncRunTableTaskMapper taskMapper,
      JsonUtils jsonUtils) {
    this.syncRunMapper = syncRunMapper;
    this.stateMapper = stateMapper;
    this.taskMapper = taskMapper;
    this.jsonUtils = jsonUtils;
  }

  public int planRunTables(Long runId) {
    SyncRun run = syncRunMapper.selectById(runId);
    if (run == null) {
      return 0;
    }
    List<String> sourceTables = extractSourceTables(run.getPayloadJson());
    if (sourceTables.isEmpty()) {
      return 0;
    }
    LocalDateTime now = LocalDateTime.now();
    int planned = 0;
    for (String sourceTable : sourceTables) {
      SyncRunTableState state = resolveRunnableState(run, sourceTable);
      SyncRunTableTask task = new SyncRunTableTask();
      task.setRunId(run.getId());
      task.setConfigId(run.getConfigId());
      task.setStateId(state.getId());
      task.setSourceInstance(run.getSourceInstance());
      task.setSourceTable(sourceTable);
      task.setMirrorTable(state.getMirrorTable());
      task.setTaskType(run.getRunType().name());
      task.setStatus(SyncRunStatus.QUEUED);
      task.setRowStrategy("INCREMENTAL");
      task.setWatermarkAt(state.getLastWatermarkAt());
      task.setBatchSize(500);
      task.setRunAfter(now);
      task.setRetryCount(0);
      task.setMaxRetryCount(3);
      task.setRowsScanned(0L);
      task.setRowsApplied(0L);
      task.setCreatedAt(now);
      task.setUpdatedAt(now);
      taskMapper.insert(task);
      planned++;
    }
    log.info("Planned {} table tasks for run {}", planned, runId);
    return planned;
  }

  private SyncRunTableState resolveRunnableState(SyncRun run, String sourceTable) {
    SyncRunTableState state =
        stateMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SyncRunTableState>()
            .eq(SyncRunTableState::getConfigId, run.getConfigId())
            .eq(SyncRunTableState::getSourceInstance, run.getSourceInstance())
            .eq(SyncRunTableState::getSourceTable, sourceTable)
            .eq(SyncRunTableState::getSyncEnabled, true)
            .last("limit 1"));
    if (state == null) {
      throw new BizException("Manual table refresh cannot run before mirror table state exists: " + sourceTable);
    }
    if (isBlank(state.getPrimaryKeyColumns())) {
      throw new BizException("Manual table refresh requires known primary key columns: " + sourceTable);
    }
    if (isBlank(state.getUpdatedAtColumn())) {
      throw new BizException("Manual table refresh requires an updated_at column: " + sourceTable);
    }
    if (state.getLastWatermarkAt() == null) {
      throw new BizException("Manual table refresh requires a completed full sync baseline first: " + sourceTable);
    }
    return state;
  }

  private boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  private List<String> extractSourceTables(String payloadJson) {
    Map<String, Object> payload = jsonUtils.toMap(payloadJson);
    Object rawTables = payload.get("sourceTables");
    if (!(rawTables instanceof List<?> tables) || tables.isEmpty()) {
      return List.of();
    }
    return tables.stream()
        .filter(String.class::isInstance)
        .map(String.class::cast)
        .map(GitlabSourceInstanceSupport::normalizeSourceTableName)
        .filter(table -> !table.isBlank())
        .distinct()
        .toList();
  }
}
