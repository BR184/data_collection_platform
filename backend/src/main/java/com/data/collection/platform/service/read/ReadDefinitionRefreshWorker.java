package com.data.collection.platform.service.read;

import com.data.collection.platform.service.GitlabConfigService;
import com.data.collection.platform.service.sync.SyncRunSubmissionService;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 定义独立变化也必须产生完整读版本；复用正式全量事实任务，不改写已有派生值或发布指针。 */
@Service
public class ReadDefinitionRefreshWorker {
  private static final Logger LOGGER = LoggerFactory.getLogger(ReadDefinitionRefreshWorker.class);
  private final JdbcTemplate jdbc;
  private final ReadPublicationRepository repository;
  private final GitlabConfigService configs;
  private final SyncRunSubmissionService submissions;

  public ReadDefinitionRefreshWorker(JdbcTemplate jdbc, ReadPublicationRepository repository,
      GitlabConfigService configs, SyncRunSubmissionService submissions) {
    this.jdbc = jdbc;
    this.repository = repository;
    this.configs = configs;
    this.submissions = submissions;
  }

  /** 每次至多一个来源；在途镜像/事实运行让位，失败保留旧点，由持久全量意图重试。 */
  @Scheduled(fixedDelayString = "${platform.gitlab-mirror.fact-worker-delay-ms:5000}")
  @Transactional
  public void requestChangedDefinitions() {
    requestOneDatasetDefinitionChange();
    List<Source> sources = jdbc.query("""
        select distinct config.id, domain.source_instance
          from read_publication_domains domain
          join gitlab_sync_configs config on config.source_instance = domain.source_instance
          join source_fact_publication_states state on state.source_instance = domain.source_instance
            and state.fact_type = domain.fact_type
         where domain.producer_kind = 'GITLAB_FACT' and domain.current_publication_id is not null and config.source_enabled
           and state.readiness_status = 'READY' and not state.full_publication_requested
           and not exists(select 1 from sync_runs run where run.source_instance = domain.source_instance
             and run.status in ('QUEUED', 'RUNNING', 'PAUSED', 'RETRYING', 'CANCELLING'))
         order by config.id
        """, (rs, row) -> new Source(rs.getLong(1), rs.getString(2)));
    for (Source source : sources) {
      String current = repository.currentFactDefinitions(source.instance());
      Boolean changed = jdbc.queryForObject("""
          select exists(select 1 from read_publication_domains domain
            join read_publications publication on publication.id = domain.current_publication_id
            join read_definition_snapshots definition on definition.id = publication.definition_snapshot_id
            where domain.producer_kind = 'GITLAB_FACT' and domain.source_instance = ?
              and definition.payload <> ?::jsonb)
          """, Boolean.class, source.instance(), current);
      if (!Boolean.TRUE.equals(changed)) continue;
      int requested = jdbc.update("""
          update source_fact_publication_states state set full_publication_requested = true,
            full_request_revision = full_request_revision + 1, updated_at = current_timestamp
           where source_instance = ? and readiness_status = 'READY' and not full_publication_requested
             and exists(select 1 from read_publication_domains domain where domain.producer_kind = 'GITLAB_FACT'
               and domain.source_instance = state.source_instance and domain.fact_type = state.fact_type
               and domain.current_publication_id is not null)
          """, source.instance());
      if (requested > 0) {
        submissions.submitFactRefresh(configs.getConfigById(source.configId()), true, "读取定义已变更，构建完整事实版本");
        LOGGER.info("已登记定义变更的完整重建，source={}", source.instance());
      }
      return;
    }
  }

  private void requestOneDatasetDefinitionChange() {
    String current = repository.currentFactDefinitions("default");
    var owners = jdbc.queryForList("""
        select domain.producer_kind from read_publication_domains domain
          join read_publications publication on publication.id = domain.current_publication_id
          join read_definition_snapshots definition on definition.id = publication.definition_snapshot_id
         where domain.producer_kind in ('FORMAL_RECORD','COMPAT_WALKTHROUGH','COMPAT_REVIEW')
           and domain.fact_type = 'DATASET' and domain.source_instance = 'default'
           and definition.payload <> ?::jsonb order by domain.producer_kind limit 1
        """, String.class, current);
    if (owners.isEmpty()) return;
    jdbc.update("""
        insert into read_dataset_refresh_requests(producer_kind) values (?)
        on conflict (producer_kind,source_instance,table_key) do update
          set requested_revision = read_dataset_refresh_requests.requested_revision + 1,
              updated_at = current_timestamp
          where read_dataset_refresh_requests.requested_revision = read_dataset_refresh_requests.completed_revision
        """, owners.getFirst());
  }

  private record Source(long configId, String instance) {}
}
