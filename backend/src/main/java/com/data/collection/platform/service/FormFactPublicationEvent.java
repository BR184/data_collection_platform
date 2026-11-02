package com.data.collection.platform.service;

import java.util.UUID;

/**
 * 议题事实已公开且本次公开消费了表单输入。
 *
 * <p>只在发布事务提交后触发来源级延期写回轮次，不复用旁路写回，也不提前唤醒失败的候选。
 *
 * @param sourceInstance 来源实例
 * @param publicationId 已公开的读取版本
 */
public record FormFactPublicationEvent(String sourceInstance, UUID publicationId) {}
