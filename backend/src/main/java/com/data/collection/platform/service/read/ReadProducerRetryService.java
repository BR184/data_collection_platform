package com.data.collection.platform.service.read;

import com.data.collection.platform.entity.read.ReadPublicationDomainKey;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 安装/镜像生产失败按域持久退避；不改变完整指针，不把失败候选认证为成功。 */
@Service
public class ReadProducerRetryService {
  private final JdbcTemplate jdbc;

  /** 使用平台同库连接记录控制状态；调用方在失败业务事务结束后独立调用。 */
  public ReadProducerRetryService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** 失败记录提交后至少一分钟再自动尝试；若记账本身失败，作为附加异常保留首错。 */
  public void failed(ReadPublicationDomainKey key, RuntimeException failure) {
    try {
      jdbc.update("""
          insert into read_publication_domains(id, producer_kind, source_instance, fact_type, table_key,
              producer_retry_after, producer_last_error)
          values (?, ?, ?, ?, ?, current_timestamp + interval '1 minute', ?)
          on conflict (producer_kind, source_instance, fact_type, table_key) do update
             set producer_retry_after = excluded.producer_retry_after,
                 producer_last_error = excluded.producer_last_error, updated_at = current_timestamp
          """, UUID.randomUUID(), key.producerKind().name(), key.sourceInstance(), key.factType(),
          key.tableKey(), failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage());
    } catch (RuntimeException recordingFailure) {
      if (recordingFailure != failure) { failure.addSuppressed(recordingFailure); }
    }
  }

  /** 完整接管或发布成功后清除该域错误；仅更新控制元数据。 */
  public void succeeded(ReadPublicationDomainKey key) {
    jdbc.update("""
        update read_publication_domains set producer_retry_after = '-infinity', producer_last_error = null
         where producer_kind = ? and source_instance = ? and fact_type = ? and table_key = ?
        """, key.producerKind().name(), key.sourceInstance(), key.factType(), key.tableKey());
  }
}
