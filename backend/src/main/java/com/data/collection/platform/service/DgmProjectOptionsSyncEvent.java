package com.data.collection.platform.service;

/**
 * DGM 项目目录一次同步尝试的终态。
 *
 * @param success 本次同步是否成功
 * @param message 失败说明；成功时为 null
 */
public record DgmProjectOptionsSyncEvent(boolean success, String message) {}
