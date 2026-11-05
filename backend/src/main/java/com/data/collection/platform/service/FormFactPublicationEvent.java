package com.data.collection.platform.service;

/** 旧引擎事实批次与表单消费水位共同提交后，唤醒该来源的既有延期写回轮次。 */
public record FormFactPublicationEvent(String sourceInstance) {}
