package com.data.collection.platform.service.read;

import com.data.collection.platform.entity.read.DatasetRelationInput;
import com.data.collection.platform.entity.read.ReadPublicationDomainKey;
import java.util.List;

/** 一个生产者完整关系闭包的捕获请求；完整性与物理代际分离，由真实模式/键决定修订复用。 */
public record DatasetPublicationRequest(ReadPublicationDomainKey domain, String producerRunId,
    List<DatasetRelationInput> relations, DatasetInputEvidence evidence) {
  public DatasetPublicationRequest {
    if (domain == null || !domain.factType().equals("DATASET") || producerRunId == null || producerRunId.isBlank()
        || evidence == null || relations == null || relations.isEmpty()) {
      throw new IllegalArgumentException("数据集捕获必须声明拥有者、完整关系和正证据");
    }
    relations = List.copyOf(relations);
    if (relations.stream().map(DatasetRelationInput::tableKey).distinct().count() != relations.size()) {
      throw new IllegalArgumentException("完整数据集不能重复登记同一关系");
    }
  }
}
