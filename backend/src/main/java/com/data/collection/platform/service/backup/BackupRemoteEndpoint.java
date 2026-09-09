package com.data.collection.platform.service.backup;

/** 远程备份目标端点：服务器地址、端口与登录用户名；密码与目录由配置层携带，不进入端点。 */
public record BackupRemoteEndpoint(String host, int port, String username) {}
