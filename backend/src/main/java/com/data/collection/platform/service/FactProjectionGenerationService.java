package com.data.collection.platform.service;

import com.data.collection.platform.entity.read.ReadPublication;
import com.data.collection.platform.entity.read.ReadPublicationDomainKey;
import com.data.collection.platform.entity.read.ReadPublicationProducerKind;
import com.data.collection.platform.entity.read.ReadPublicationState;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 完整候选发布事务内提交公开范围 generation；构建或预热阶段不能调用。 */
@Service
public class FactProjectionGenerationService {
  private final JdbcTemplate jdbcTemplate;

  public FactProjectionGenerationService(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /**
   * 提交本候选已证明的范围 generation，与事实指针/heads/栅栏共同回滚。
   * GitLab 兼容既有当前范围版本查询；其它生产者只使用本域发布记录上的范围代际，不能改写 GitLab 命名空间。
   *
   * @param publication 刚在同一事务置为 PUBLISHED 的候选
   * @param key 其一致性域
   * @return 已提交 GitLab 范围数量；独立兼容域为 0
   */
  @Transactional
  public int commitPreparedGenerations(ReadPublication publication, ReadPublicationDomainKey key) {
    if (publication.state() != ReadPublicationState.PUBLISHED) {
      throw new IllegalStateException("只有完整发布事务可以提交公开 generation");
    }
    if (key.producerKind() != ReadPublicationProducerKind.GITLAB_FACT) {
      return 0;
    }
    int advanced = jdbcTemplate.update("""
        insert into fact_projection_generations(source_instance, fact_type, scope_type, scope_key, generation, updated_at)
        select ?, ?, scope_type, scope_key, candidate_generation, current_timestamp
          from read_publication_scope_generations where publication_id = ?
        on conflict (source_instance, fact_type, scope_type, scope_key) do update
          set generation = excluded.generation, updated_at = current_timestamp
          where fact_projection_generations.generation = excluded.generation - 1
        """, key.sourceInstance(), key.factType(), publication.id());
    Integer required = jdbcTemplate.queryForObject(
        "select count(*) from read_publication_scope_generations where publication_id = ?",
        Integer.class, publication.id());
    if (required == null || advanced != required) {
      throw new IllegalStateException("投影范围基线已变化，候选需要重新预热：" + publication.id());
    }
    return advanced;
  }
}
