package com.data.collection.platform.entity.read;

import java.time.Instant;
import java.util.UUID;

/** 完整数据集公开身份，适用于评审/走查/镜像/CAT；不把无根数据伪造成 GitLab 事实。 */
public record PublishedDatasetReadContext(String sourceInstance, String datasetKey, UUID epochId,
    long sequence, UUID publicationId, Instant dataAsOf) implements PublishedSourceContext {}
