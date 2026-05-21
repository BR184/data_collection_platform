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
        "sync_runs",
        definition(
            "同步运行",
            List.of("run_id", "source_instance", "run_type", "trigger_type", "status", "request_reason"),
            "id",
            column("id", "ID"),
            column("run_id", "运行标识"),
            column("source_instance", "数据源"),
            column("run_type", "同步类型"),
            column("trigger_type", "触发方式"),
            column("status", "状态"),
            column("planned_table_count", "计划表数"),
            column("completed_table_count", "完成表数"),
            column("applied_rows", "写入行数"),
            column("created_at", "创建时间"),
            column("finished_at", "完成时间")),
        "sync_run_events",
        definition(
            "同步运行事件",
            List.of("event_type", "table_name", "message", "source_instance"),
            "id",
            column("id", "ID"),
            column("run_id", "运行 ID"),
            column("source_instance", "数据源"),
            column("event_type", "事件类型"),
            column("table_name", "源表"),
            column("message", "消息"),
            column("created_at", "创建时间")),
        "sync_run_table_tasks",
        definition(
            "同步表任务",
            List.of("source_instance", "source_table", "mirror_table", "task_type", "status", "last_error"),
            "id",
            column("id", "ID"),
            column("run_id", "运行 ID"),
            column("source_instance", "数据源"),
            column("source_table", "源表"),
            column("mirror_table", "镜像表"),
            column("task_type", "任务类型"),
            column("status", "状态"),
            column("rows_scanned", "扫描行数"),
            column("rows_applied", "写入行数"),
            column("last_error", "最后错误"),
            column("created_at", "创建时间"),
            column("finished_at", "完成时间")),
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
