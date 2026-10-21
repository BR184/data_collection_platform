package com.data.collection.platform.entity.read;

import java.util.UUID;

/**
 * 不可变定义快照：范围 / 标签组 / 词典等配置的规范化负载。
 *
 * <p>发布身份引用它，使一个完整版本的定义不随后续配置编辑漂移。内容相同则以同一种类与校验和去重。
 *
 * @param id 快照标识
 * @param definitionKind 定义种类
 * @param schemaVersion 负载结构版本
 * @param payloadJson 规范化强类型负载（JSON）
 * @param checksum 负载校验和
 */
public record ReadDefinitionSnapshot(
    UUID id, String definitionKind, String schemaVersion, String payloadJson, String checksum) {}
