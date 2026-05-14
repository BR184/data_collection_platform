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
            column("source_mode", "读取方式"),
            column("whitelist_mode", "白名单模式"),
            column("updated_at", "更新时间"),
            column("created_at", "创建时间")),
        "gitlab_sync_logs",
        definition(
            "同步日志",
            List.of("sync_type", "status", "message"),
            "id",
            column("id", "ID"),
            column("sync_type", "同步类型"),
            column("status", "状态"),
            column("table_count", "表数"),
            column("record_count", "记录数"),
            column("started_at", "开始时间"),
            column("finished_at", "结束时间")),
        "gitlab_sync_tasks",
        definition(
            "同步任务",
            List.of("run_id", "task_type", "trigger_type", "scope_key", "status", "finished_reason"),
            "id",
            column("id", "ID"),
            column("task_type", "任务类型"),
            column("trigger_type", "触发方式"),
            column("status", "任务状态"),
            column("queued_at", "排队时间"),
            column("started_at", "开始时间"),
            column("finished_at", "结束时间")),
        "gitlab_webhook_events",
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
            "旧镜像记录",
            List.of("table_name", "record_key", "row_data"),
            "id",
            column("id", "ID"),
            column("table_name", "源表名"),
            column("record_key", "主键值"),
            column("updated_at_source", "源更新时间"),
            column("synced_at", "镜像时间")));
  }
}
