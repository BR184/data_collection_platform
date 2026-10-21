package com.data.collection.platform.service.read;

import com.data.collection.platform.entity.read.DatasetRelationInput;
import com.data.collection.platform.entity.read.ReadPublicationDomainKey;
import com.data.collection.platform.entity.read.ReadPublicationProducerKind;
import com.data.collection.platform.common.JsonUtils;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** 镜像表独立发布完整已提交内容；分页、失败或中止不移动数据库浏览器的读取指针。 */
@Service
public class MirrorDatasetPublicationWorker {
  private static final Logger LOGGER = LoggerFactory.getLogger(MirrorDatasetPublicationWorker.class);
  private final JdbcTemplate jdbc;
  private final DatasetPublicationService datasets;
  private final JsonUtils json;
  private final ReadProducerRetryService retries;

  public MirrorDatasetPublicationWorker(JdbcTemplate jdbc, DatasetPublicationService datasets,
      JsonUtils json, ReadProducerRetryService retries) {
    this.jdbc = jdbc;
    this.datasets = datasets;
    this.json = json;
    this.retries = retries;
  }

  /** 每轮至多一个表；服从平台 background-jobs 调度，手动同步不依赖自动镜像开关。 */
  @Scheduled(scheduler = "readPublicationScheduler", fixedDelayString = "${platform.gitlab-mirror.fact-worker-delay-ms:5000}")
  public void publishOneCompleteTable() {
    var prepared = jdbc.query("""
        select p.id, p.producer_run_id, d.source_instance, d.table_key from read_publications p
          join read_publication_domains d on d.id = p.domain_id
         where d.producer_kind = 'MIRROR_TABLE' and d.fact_type = 'DATASET'
           and p.state in ('PROJECTING', 'READY_TO_PUBLISH')
           and d.producer_retry_after <= current_timestamp
         order by p.created_at, p.id limit 1
        """, (rs, row) -> new Prepared(rs.getObject(1, UUID.class), rs.getString(2),
            new ReadPublicationDomainKey(ReadPublicationProducerKind.MIRROR_TABLE, rs.getString(3), "DATASET", rs.getString(4))));
    if (!prepared.isEmpty()) {
      Prepared publication = prepared.getFirst();
      try {
        datasets.publishPreparedDataset(publication.id(), publication.run());
        retries.succeeded(publication.key());
      } catch (RuntimeException failure) {
        retries.failed(publication.key(), failure);
        LOGGER.warn("镜像完整候选恢复发布失败，publicationId={}，已按域退避", publication.id(), failure);
      }
      return;
    }
    var inputs = jdbc.query("""
        select state.id, state.source_instance, state.mirror_table, state.primary_key_columns,
          run.id as run_id
          from sync_run_table_states state
          join sync_run_table_tasks task on task.state_id = state.id
          join sync_runs run on run.id = task.run_id
          left join read_publication_domains domain on domain.producer_kind = 'MIRROR_TABLE'
            and domain.source_instance = state.source_instance and domain.fact_type = 'DATASET'
            and domain.table_key = state.mirror_table
         where state.last_full_verified_at is not null and not state.dirty_flag
           and coalesce(domain.producer_retry_after, '-infinity'::timestamptz) <= current_timestamp
           and run.status in ('SUCCESS', 'PARTIAL_SUCCESS', 'FAILED', 'CANCELLED', 'TIMEOUT')
           and run.id = (select max(latest.run_id) from sync_run_table_tasks latest where latest.state_id = state.id)
         group by state.id, run.id, domain.last_successful_verification_at
        having bool_and(task.status = 'SUCCESS')
           and max(task.finished_at) > coalesce(domain.last_successful_verification_at, '-infinity'::timestamptz)
         order by max(task.finished_at), state.id limit 1
        """, (rs, row) -> new Input(rs.getLong("id"), rs.getString("source_instance"), rs.getString("mirror_table"),
            rs.getString("primary_key_columns"), rs.getLong("run_id")));
    if (inputs.isEmpty()) { return; }
    Input input = inputs.getFirst();
    String run = "mirror-dataset:" + input.run() + ":" + input.state();
    ReadPublicationDomainKey domain = new ReadPublicationDomainKey(ReadPublicationProducerKind.MIRROR_TABLE,
        input.source(), "DATASET", input.table());
    try {
      // 同步控制面的真实契约是逗号分隔列名，不是 JSON 数组。
      List<String> keys = input.keys() == null || input.keys().isBlank() ? List.of()
          : java.util.Arrays.stream(input.keys().split(",")).map(String::trim).toList();
      DatasetRelationInput.requireIdentifier(input.table());
      var candidate = datasets.prepareCompleteDataset(new DatasetPublicationRequest(domain, run,
          List.of(new DatasetRelationInput(input.table(), input.table(), keys == null ? List.of() : keys,
              List.of(), "select * from " + input.table(), List.of())),
          new DatasetInputEvidence.MirrorTableRun(input.run(), input.state())));
      if (candidate.state() != com.data.collection.platform.entity.read.ReadPublicationState.PUBLISHED) {
        datasets.publishPreparedDataset(candidate.id(), run);
      }
      retries.succeeded(domain);
    } catch (RuntimeException failure) {
      retries.failed(domain, failure);
      LOGGER.warn("镜像表完整读版本尚未发布，table={}, run={}，已按域退避", input.table(), input.run(), failure);
    }
  }

  private record Prepared(UUID id, String run, ReadPublicationDomainKey key) {}
  private record Input(long state, String source, String table, String keys, long run) {}
}
