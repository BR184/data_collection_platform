package com.data.collection.platform.service.sync;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.TableWhitelistOption;
import com.data.collection.platform.entity.sync.SyncRun;
import com.data.collection.platform.mapper.SyncRunMapper;
import com.data.collection.platform.service.GitlabConfigService;
import com.data.collection.platform.service.GitlabMirrorSchemaService;
import com.data.collection.platform.service.GitlabSourceInstanceSupport;
import com.data.collection.platform.service.GitlabWhitelistService;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** 执行一个同表、同关系定义的权威范围来源批次。 */
@Service
@Slf4j
public class SyncRunAuthoritativeScopeBatchExecutor {
  private final SyncRunMapper syncRunMapper;
  private final SyncRunAuthoritativeScopeRepository repository;
  private final SyncRunAuthoritativeScopeHeartbeatService heartbeatService;
  private final SyncRunAuthoritativeScopeCommitService commitService;
  private final GitlabConfigService configService;
  private final GitlabWhitelistService whitelistService;
  private final GitlabMirrorSchemaService mirrorSchemaService;
  private final SourceTableReader sourceTableReader;

  public SyncRunAuthoritativeScopeBatchExecutor(
      SyncRunMapper syncRunMapper,
      SyncRunAuthoritativeScopeRepository repository,
      SyncRunAuthoritativeScopeHeartbeatService heartbeatService,
      SyncRunAuthoritativeScopeCommitService commitService,
      GitlabConfigService configService,
      GitlabWhitelistService whitelistService,
      GitlabMirrorSchemaService mirrorSchemaService,
      SourceTableReader sourceTableReader) {
    this.syncRunMapper = syncRunMapper;
    this.repository = repository;
    this.heartbeatService = heartbeatService;
    this.commitService = commitService;
    this.configService = configService;
    this.whitelistService = whitelistService;
    this.mirrorSchemaService = mirrorSchemaService;
    this.sourceTableReader = sourceTableReader;
  }

  /** 执行已领取批次；普通失败保留原范围身份并进入退避。 */
  public void execute(List<SyncRunAuthoritativeScope> scopes, String owner) {
    if (scopes == null || scopes.isEmpty()) {
      return;
    }
    SyncRunAuthoritativeScope first = scopes.getFirst();
    List<Long> scopeIds = scopes.stream().map(SyncRunAuthoritativeScope::id).toList();
    try (SyncRunAuthoritativeScopeHeartbeatService.LeaseGuard leaseGuard =
        heartbeatService.monitor(first.runId(), scopeIds, owner)) {
      SyncRun run = syncRunMapper.selectById(first.runId());
      if (run == null || run.getConfigId() == null) {
        throw new IllegalStateException("权威范围找不到镜像父运行：" + first.runId());
      }
      GitlabSyncConfig config = configService.getConfigById(run.getConfigId());
      if (!configService.isSourceConfigured(config)) {
        throw new BizException("GitLab 数据源连接配置不完整，无法读取权威范围");
      }
      TableWhitelistOption option = resolveOption(config, first.childTable());
      GitlabMirrorSchemaService.PreparedMirrorTable prepared =
          mirrorSchemaService.getPreparedMirrorTableForSync(config, option);
      mirrorSchemaService.markTableSyncing(config.getId(), first.childTable());
      LinkedHashMap<Long, Map<String, Object>> lookupScopes = new LinkedHashMap<>();
      scopes.forEach(scope -> lookupScopes.put(scope.id(), scope.lookupScope()));
      Map<Long, List<Map<String, Object>>> sourceRows =
          sourceTableReader.readAuthoritativeScopes(
              config, option, prepared.mirrorSchema(), lookupScopes);
      leaseGuard.requireOwnership();
      commitService.commit(scopes, owner, prepared.mirrorSchema(), sourceRows);
      mirrorSchemaService.markTableIdle(config.getId(), first.childTable(), LocalDateTime.now());
    } catch (SyncAuthoritativeScopeLeaseLostException error) {
      log.info(
          "权威范围批次租约已转移，停止旧 worker，runId={}, childTable={}",
          first.runId(),
          first.childTable());
    } catch (Exception error) {
      String message =
          error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
      try {
        repository.failOwnedBatch(scopeIds, owner, first.runId(), message);
      } catch (SyncAuthoritativeScopeLeaseLostException leaseLost) {
        log.info(
            "权威范围失败提交时租约已转移，runId={}, childTable={}",
            first.runId(),
            first.childTable());
        return;
      }
      SyncRun run = syncRunMapper.selectById(first.runId());
      if (run != null && run.getConfigId() != null) {
        mirrorSchemaService.markTableError(run.getConfigId(), first.childTable());
      }
      log.warn(
          "权威范围批次执行失败，runId={}, childTable={}, scopeCount={}",
          first.runId(),
          first.childTable(),
          scopes.size(),
          error);
    }
  }

  private TableWhitelistOption resolveOption(
      GitlabSyncConfig config, String childTable) {
    String normalized = GitlabSourceInstanceSupport.normalizeSourceTableName(childTable);
    return whitelistService.resolveOptions(config).stream()
        .filter(
            option ->
                normalized.equals(
                    GitlabSourceInstanceSupport.normalizeSourceTableName(option.tableName())))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("权威范围子表不在当前同步配置中：" + normalized));
  }
}
