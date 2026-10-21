package com.data.collection.platform.entity.read;

import java.util.UUID;

/**
 * 一致性域的当前发布指针与候选序号分配。
 *
 * @param id 域标识
 * @param key 域稳定键
 * @param currentPublicationId 当前已发布身份；为 {@code null} 表示该域尚无完整产出
 * @param nextSequence 下一个可分配的发布序号
 */
public record ReadPublicationDomain(
    UUID id, ReadPublicationDomainKey key, UUID currentPublicationId, long nextSequence) {}
