package com.data.collection.platform.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.data.collection.platform.entity.database.DatabaseTableColumn;

final class DatabaseBrowserTableCatalog {

  private static final Map<String, DatabaseBrowserTableDefinition> SYSTEM_TABLE_DEFINITIONS =
      createDefinitions();

  private DatabaseBrowserTableCatalog() {
  }

  static Map<String, DatabaseBrowserTableDefinition> listDefinitions() {
    return SYSTEM_TABLE_DEFINITIONS;
  }

  static DatabaseBrowserTableDefinition findDefinition(String tableName) {
    return SYSTEM_TABLE_DEFINITIONS.get(tableName);
  }

  private static Map<String, DatabaseBrowserTableDefinition> createDefinitions() {
    Map<String, DatabaseBrowserTableDefinition> definitions = new LinkedHashMap<>();
    definitions.putAll(DatabaseBrowserSyncTableDefinitions.definitions());
    definitions.putAll(DatabaseBrowserCollectionTableDefinitions.definitions());
    definitions.putAll(DatabaseBrowserFactTableDefinitions.definitions());
    definitions.put(
        "platform_ldap_user_directory",
        new DatabaseBrowserTableDefinition(
            "LDAP 用户信息",
            List.of("user_id", "real_name", "role_names", "employee_no", "dept_name", "dept_code", "job_title"),
            List.of(
                column("user_id", "登录账号"),
                column("real_name", "姓名"),
                column("role_codes", "角色编码"),
                column("role_names", "角色名称"),
                column("employee_no", "工号"),
                column("email", "邮箱"),
                column("intranet_email", "内网邮箱"),
                column("mobile", "手机号"),
                column("dept_name", "部门"),
                column("dept_code", "部门编码"),
                column("job_title", "岗位"),
                column("account_status", "账号状态"),
                column("employment_status", "在职状态"),
                column("last_login_at", "最近登录时间"),
                column("source_synced_at", "LDAP 同步时间")),
            "user_id"));
    return Map.copyOf(definitions);
  }

  private static DatabaseTableColumn column(String key, String label) {
    return new DatabaseTableColumn(key, label, true);
  }
}
