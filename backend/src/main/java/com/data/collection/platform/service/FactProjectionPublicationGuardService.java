package com.data.collection.platform.service;

import com.data.collection.platform.entity.QueuedFactProjectionTask;
import com.data.collection.platform.entity.read.CandidateReadContext;
import com.data.collection.platform.entity.read.FactReadSelection;
import com.data.collection.platform.entity.read.PublishedReadContext;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 候选预热执行权及最终缓存写入屏障；其它候选的较新 generation 不能证明本任务完成。 */
@Service
public class FactProjectionPublicationGuardService {
  private final JdbcTemplate jdbcTemplate;
  private final FactProjectionExecutionContext executionContext;
  private final com.data.collection.platform.service.read.CandidateCacheArtifactStore artifacts;
  private final TransactionTemplate writeTransaction;
  private final TransactionTemplate readTransaction;

  public FactProjectionPublicationGuardService(JdbcTemplate jdbcTemplate,
      FactProjectionExecutionContext executionContext,
      com.data.collection.platform.service.read.CandidateCacheArtifactStore artifacts,
      PlatformTransactionManager transactionManager) {
    this.jdbcTemplate = jdbcTemplate;
    this.executionContext = executionContext;
    this.artifacts = artifacts;
    this.writeTransaction = new TransactionTemplate(transactionManager);
    this.readTransaction = new TransactionTemplate(transactionManager);
    this.readTransaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
    this.readTransaction.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.readTransaction.setReadOnly(false);
  }

  /** 在允许行锁的短 REPEATABLE READ 事务内物化固定版本输入，不加入只读父事务。 */
  public <T> T withinRead(com.data.collection.platform.entity.read.ReadSelection selection, java.util.function.Supplier<T> reader) {
    return readTransaction.execute(status -> {
      protectRead(selection);
      return reader.get();
    });
  }

  /** 在短事务内保护显式版本；候选锁序为父运行→域→候选→任务，公开只锁发布记录。 */
  public void protectRead(com.data.collection.platform.entity.read.ReadSelection input) {
    if (input instanceof com.data.collection.platform.entity.read.DatasetReadSelection dataset) {
      if (dataset.facts() != null) { protectRead(dataset.facts()); }
      dataset.datasets().stream().sorted(java.util.Comparator.comparing(context -> context.publicationId().toString()))
          .forEach(context -> {
            List<UUID> locked = jdbcTemplate.queryForList("""
                select p.id from read_publications p join read_publication_domains d on d.id = p.domain_id
                 where p.id = ? and p.epoch_id = ? and p.sequence_no = ?
                   and p.state in ('PUBLISHED', 'RETIRING') and d.source_instance = ?
                   and d.fact_type = 'DATASET' and d.table_key = ? for key share of p
                """, UUID.class, context.publicationId(), context.epochId(), context.sequence(),
                context.sourceInstance(), context.datasetKey());
            if (locked.isEmpty()) {
              throw com.data.collection.platform.service.read.ReadViewException.rebindRequired(
                  context.publicationId(), "数据集版本已不可用，请重新绑定视图");
            }
          });
      return;
    }
    FactReadSelection selection = (FactReadSelection) input;
    if (selection.contexts().stream().anyMatch(context -> context.candidate())) {
      QueuedFactProjectionTask task = executionContext.currentTask();
      if (task == null || selection.contexts().stream().noneMatch(context -> context instanceof CandidateReadContext
          && context.publicationId().equals(task.publicationId()))) {
        throw new IllegalStateException("候选预热缺少匹配的投影执行权");
      }
      requireTaskAuthorization(task, false);
    }
    selection.contexts().stream().filter(context -> context instanceof PublishedReadContext)
        .sorted(java.util.Comparator.comparing(context -> context.publicationId().toString()))
        .forEach(context -> {
          PublishedReadContext published = (PublishedReadContext) context;
          List<UUID> locked = jdbcTemplate.queryForList("""
              select p.id from read_publications p join read_publication_domains d on d.id = p.domain_id
               where p.id = ? and p.epoch_id = ? and p.sequence_no = ?
                 and p.state in ('PUBLISHED', 'RETIRING') and d.source_instance = ? and d.fact_type = ?
               for key share of p
              """, UUID.class, published.publicationId(), published.epochId(), published.sequence(),
              published.sourceInstance(), published.table().name());
          if (locked.isEmpty()) {
            throw com.data.collection.platform.service.read.ReadViewException.rebindRequired(
                published.publicationId(), "读取版本已不可用，请重新绑定视图");
          }
        });
  }

  /** 缓存最终写入与父授权、候选、目标范围及任务令牌核验同事务完成。 */
  public void writeSnapshot(Runnable writeSnapshot) {
    QueuedFactProjectionTask task = executionContext.currentTask();
    if (task == null) { writeSnapshot.run(); return; }
    writeTransaction.executeWithoutResult(status -> {
      requireTaskAuthorization(task);
      writeSnapshot.run();
    });
  }

  /**
   * 候选预热产物写入：执行权核验、写入与写入时内容快照登记同事务完成。
   *
   * <p>快照由唯一取证查询在本次事务内读回，因此登记的是本次提交真正落库的内容；后续完成证明先与该快照
   * 比较，不能用稍后重读的结果替代。
   *
   * @param artifactKind 产物存储类别
   * @param write 执行写入并返回缓存行主键
   */
  public void writePreparedArtifact(String artifactKind, java.util.function.LongSupplier write) {
    QueuedFactProjectionTask task = executionContext.currentTask();
    if (task == null) {
      throw new IllegalStateException("候选预热产物写入缺少投影执行上下文");
    }
    writeTransaction.executeWithoutResult(status -> {
      requireTaskAuthorization(task);
      long id = write.getAsLong();
      com.data.collection.platform.service.read.CandidateCacheArtifactStore.CandidateCacheArtifact artifact =
          artifacts.read(artifactKind, task.publicationId(), id);
      if (artifact == null) {
        throw new IllegalStateException("候选预热产物写入后不可取证：" + artifactKind + " id=" + id
            + "，发布 " + task.publicationId());
      }
      executionContext.recordWrittenArtifact(artifact);
    });
  }

  /** 在调用者事务内锁定真实执行权，供产物登记和任务成功共同提交。 */
  public void requireTaskAuthorization(QueuedFactProjectionTask task) {
    requireTaskAuthorization(task, true);
  }

  private void requireTaskAuthorization(QueuedFactProjectionTask task, boolean exclusiveTaskLock) {
    if (task.publicationId() == null) { throw new ProjectionTaskLeaseLostException(task.id()); }
    if (task.factRunId() != null) {
      List<Long> parents = jdbcTemplate.queryForList("""
          select id from sync_runs where id = ? and run_type = 'FACT_REFRESH'
            and status in ('RUNNING', 'RETRYING') and lease_owner = ?
            and not cancel_requested and lease_until >= clock_timestamp() for key share
          """, Long.class, task.factRunId(), task.factRunLeaseToken());
      if (parents.isEmpty()) { throw new ProjectionTaskLeaseLostException(task.id()); }
    }
    List<UUID> domains = jdbcTemplate.queryForList("""
        select d.id from read_publication_domains d join read_publications p on p.domain_id = d.id
         where p.id = ? for update of d
        """, UUID.class, task.publicationId());
    if (domains.isEmpty()) { throw new ProjectionTaskLeaseLostException(task.id()); }
    List<UUID> candidates = jdbcTemplate.queryForList("""
        select p.id from read_publications p
         where p.id = ? and p.state = 'PROJECTING'
           and ((?::bigint is null and p.producer_task_id is null)
             or p.producer_run_id = ?::text) for key share
        """, UUID.class, task.publicationId(), task.factRunId(), task.factRunId());
    if (candidates.isEmpty()) { throw new ProjectionTaskLeaseLostException(task.id()); }
    List<Long> targets = jdbcTemplate.queryForList("""
        select candidate_generation from read_publication_scope_generations
         where publication_id = ? and scope_type = ? and scope_key = ?
           and candidate_generation = ? for key share
        """, Long.class, task.publicationId(), task.scope().scopeType().name(),
        task.scope().scopeKey(), task.targetGeneration());
    if (targets.isEmpty()) { throw new ProjectionTaskSupersededException(task.id()); }
    List<Long> owned = jdbcTemplate.queryForList("""
        select id from fact_projection_refresh_tasks where id = ? and publication_id = ?
          and status = 'RUNNING' and lease_owner = ? and target_generation = ?
          and lease_until >= clock_timestamp()
        """ + (exclusiveTaskLock ? " for update" : " for key share"),
        Long.class, task.id(), task.publicationId(), task.leaseToken(), task.targetGeneration());
    if (owned.isEmpty()) { throw new ProjectionTaskLeaseLostException(task.id()); }
  }
}
