package com.data.collection.platform.service.read;

import com.data.collection.platform.entity.read.ReadSelection;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 用发布时冻结的强类型定义解释历史事实；目录编辑仍作用于当前权威表。 */
public final class VersionedDefinitionSql {
  private static final Map<String, String> TABLES = Map.ofEntries(
      Map.entry("module_dictionary", "modules"), Map.entry("issue_scope_catalogs", "catalogs"),
      Map.entry("issue_scope_groups", "groups"), Map.entry("issue_scope_members", "members"),
      Map.entry("issue_customer_name_aliases", "customerAliases"), Map.entry("label_groups", "labelGroups"),
      Map.entry("label_group_members", "labelGroupMembers"), Map.entry("label_group_references", "labelGroupReferences"),
      Map.entry("label_group_dynamic_rules", "labelGroupRules"),
      Map.entry("quality_board_member_scopes", "qualityMemberScopes"));

  private VersionedDefinitionSql() {}

  /**
   * 为服务端 SELECT 合成冻结定义 CTE；同时支持命名参数，UUID 仅由强类型服务端读取身份生成。
   * 本方法不执行写入、不修改连接状态，也不从当前目录补齐历史版本。
   */
  public static String bind(ReadSelection selection, String sql) {
    List<String> definitions = ctes(selection, sql);
    if (definitions.isEmpty()) { return sql; }
    String body = sql.stripLeading();
    boolean hasWith = body.toLowerCase(Locale.ROOT).startsWith("with ")
        || body.toLowerCase(Locale.ROOT).startsWith("with\n");
    return "with " + String.join(", ", definitions) + (hasWith ? ", " : " ")
        + (hasWith ? body.substring(4).stripLeading() : body);
  }

  static List<String> ctes(ReadSelection selection, String sql) {
    String lower = sql.toLowerCase(Locale.ROOT);
    // 混合事实/数据集查询已由最外层固定定义身份；内部事实绑定不能再次声明同名 CTE 或换成另一份定义。
    if (lower.contains("frozen_read_definition as (")) { return List.of(); }
    List<Map.Entry<String, String>> used = TABLES.entrySet().stream()
        .filter(entry -> java.util.regex.Pattern.compile("\\b" + entry.getKey() + "\\b").matcher(lower).find())
        .sorted(Map.Entry.comparingByKey()).toList();
    if (used.isEmpty()) { return List.of(); }
    List<String> ctes = new ArrayList<>();
    ctes.add("frozen_read_definition as (select definition.payload from read_publications publication"
        + " join read_definition_snapshots definition on definition.id = publication.definition_snapshot_id"
        + " where publication.id = '" + selection.primaryPublicationId() + "'::uuid)");
    for (Map.Entry<String, String> entry : used) {
      ctes.add(entry.getKey() + " as (select restored.* from frozen_read_definition frozen"
          + " cross join lateral jsonb_populate_recordset(null::" + entry.getKey()
          + ", coalesce(frozen.payload->'" + entry.getValue() + "', '[]'::jsonb)) restored)");
    }
    return ctes;
  }
}
