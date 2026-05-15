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
            "Sync configuration",
            List.of("name", "source_mode", "whitelist_mode", "db_name", "db_username", "docker_container_name"),
            "id",
            column("id", "ID"),
            column("name", "Source name"),
            column("source_mode", "Source mode"),
            column("whitelist_mode", "Whitelist mode"),
            column("updated_at", "Updated at"),
            column("created_at", "Created at")),
        "gitlab_system_hook_events",
        definition(
            "System Hook events",
            List.of("event_type", "project_path", "event_id", "delivery_id"),
            "id",
            column("id", "ID"),
            column("event_type", "Event type"),
            column("project_path", "Project path"),
            column("received_at", "Received at")),
        "gitlab_mirror_records",
        definition(
            "Legacy mirror records",
            List.of("table_name", "record_key", "row_data"),
            "id",
            column("id", "ID"),
            column("table_name", "Source table"),
            column("record_key", "Primary key"),
            column("updated_at_source", "Source updated at"),
            column("synced_at", "Synced at")));
  }
}
