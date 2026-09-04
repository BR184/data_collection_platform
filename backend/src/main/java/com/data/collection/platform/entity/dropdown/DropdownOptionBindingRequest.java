package com.data.collection.platform.entity.dropdown;

/**
 * 字段绑定/拆分请求。
 *
 * @param target NEW=新建空白配置并绑定；COPY=复制当前绑定配置为新配置并绑定；CONFIG=绑定到既有配置（共用）
 * @param configId target=CONFIG 时的既有配置 ID，其余目标忽略
 */
public record DropdownOptionBindingRequest(DropdownOptionBindingTarget target, Long configId) {}
