package com.data.collection.platform.service;

import static com.data.collection.platform.service.DatabaseBrowserDefinitionSupport.column;
import static com.data.collection.platform.service.DatabaseBrowserDefinitionSupport.definition;

import java.util.List;
import java.util.Map;

final class DatabaseBrowserSyncTableDefinitions {

  private DatabaseBrowserSyncTableDefinitions() {
  }

  static Map<String, DatabaseBrowserTableDefinition> definitions() {
    return Map.of(
        "gitlab_sync_configs",
        definition(
            "同步配置",
            List.of("name", "source_mode", "whitelist_mode", "db_name", "db_username", "docker_container_name"),
            "id",
            column("id", "ID"),
            column("name", "数据源名称"),
            column("source_mode", "数据源模式"),
            column("whitelist_mode", "白名单模式"),
            column("updated_at", "更新时间"),
            column("created_at", "创建时间")),
        "gitlab_system_hook_events",
        definition(
            "System Hook 事件",
            List.of("event_type", "project_path", "event_id", "delivery_id"),
            "id",
            column("id", "ID"),
            column("event_type", "事件类型"),
            column("project_path", "项目路径"),
            column("received_at", "接收时间")),
        "gitlab_mirror_records",
        definition(
            "旧版镜像记录",
            List.of("table_name", "record_key", "row_data"),
            "id",
            column("id", "ID"),
            column("table_name", "源表"),
            column("record_key", "主键"),
            column("updated_at_source", "源更新时间"),
            column("synced_at", "同步时间")));
  }
}
