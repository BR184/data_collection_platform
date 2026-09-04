package com.data.collection.platform.golden;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;

/**
 * 黄金基线端点目录（classpath:golden-baseline/endpoint-catalog.yml）的加载与访问模型。
 *
 * <p>目录是"平台全部可产出数据接口"的机器强制清单：每个 Controller 端点必须登记且仅登记一次，
 * 键为 {@code METHOD path}（与反射枚举逐字一致）。EXCLUDED 条目必须显式给出排除原因，
 * 不允许静默遗漏。快照引擎按目录中的用例展开请求，并应用条目级易变字段掩码。</p>
 */
public final class GoldenEndpointCatalog {

  /** 端点在基线体系中的分类。 */
  public enum Category {
    /** JSON 数据读接口。 */
    READ,
    /** 下拉框、筛选选项类接口。 */
    OPTIONS,
    /** Excel/文件下载接口。 */
    EXPORT,
    /** 写接口（固定输入 + 响应与表状态快照 + 隔离还原）。 */
    WRITE,
    /** 显式排除项，必须给出原因。 */
    EXCLUDED
  }

  /** 响应体种类。 */
  public enum ResponseKind {
    /** ApiResponse JSON 结构。 */
    JSON,
    /** Excel 二进制流（快照为规范化 JSON）。 */
    EXCEL
  }

  /**
   * 一个读/导出用例：稳定 id 与可选查询串或请求体。
   *
   * @param id 用例稳定标识，出现在快照文件名中，重命名会导致旧快照失效
   * @param query 追加到端点路径后的查询串，不含前导问号；可为空
   * @param body POST 只读预览类用例的 JSON 请求体文本；GET 用例为空
   */
  public record ReadCase(String id, String query, String body) {
    /** 紧凑构造：空 body 归一为 null。 */
    public ReadCase {
      if (body != null && body.isBlank()) {
        body = null;
      }
    }
  }

  /** 写接口受影响表的还原策略。 */
  public enum RestoreMode {
    /** 自动：默认按 REPLAY 处理（种子文件重放或运行期捕获基线重放）。 */
    AUTO,
    /** 重放：整表恢复到全链路引导完成后的冻结基线状态。 */
    REPLAY,
    /** 清空整表（运行期审计/日志类表，基线即空态）。 */
    TRUNCATE,
    /** 范围删除：仅删除 scope 命中的行（用于用例新建行的定向清理）。 */
    DELETE,
    /** 不还原（仅限用例声明理由明确的特殊场景）。 */
    NONE
  }

  /**
   * 写接口的受影响表声明：表状态快照与还原范围。
   *
   * @param table 物理表名（qaflex_test schema 下）
   * @param scope 表状态快照的 WHERE 行过滤片段；空表示整表快照。还原范围不受其影响（DELETE 模式除外）
   * @param ignoreColumns 表状态快照中剔除的易变列名（如时间戳审计列）
   * @param restore 还原策略
   */
  public record AffectedTable(String table, String scope, List<String> ignoreColumns, RestoreMode restore) {
    /** 紧凑构造：归一空值并展开 AUTO 策略。 */
    public AffectedTable {
      scope = scope == null || scope.isBlank() ? null : scope.trim();
      ignoreColumns = ignoreColumns == null ? List.of() : List.copyOf(ignoreColumns);
      restore = restore == RestoreMode.AUTO ? RestoreMode.REPLAY : restore;
    }
  }

  /**
   * 一个写接口用例：固定请求与受影响表声明。
   *
   * @param id 用例稳定标识，出现在快照文件名中，重命名会导致旧快照失效
   * @param query 提供路径变量与查询参数的查询串，不含前导问号；可为空
   * @param body 请求体 JSON 文本；可为空
   * @param affectedTables 受影响表声明（至少一张；响应快照之外的行为契约）
   */
  public record WriteCase(String id, String query, String body, List<AffectedTable> affectedTables) {
    /** 紧凑构造：空 body 归一为 null，受影响表列表归一为不可变列表。 */
    public WriteCase {
      if (body != null && body.isBlank()) {
        body = null;
      }
      affectedTables = affectedTables == null ? List.of() : List.copyOf(affectedTables);
    }
  }

  /**
   * 目录中的单个端点条目。
   *
   * @param method HTTP 方法大写形式
   * @param path 与 Controller 反射枚举逐字一致的路径模板（含 {var} 变量）
   * @param category 分类
   * @param response 响应体种类
   * @param ignorePaths 快照比对时忽略的 JSON 路径（易变字段掩码）
   * @param ignoreArrayOrder 忽略元素顺序的 JSON 路径（无稳定排序的集合输出，如 DISTINCT 选项）
   * @param cases 读/导出用例；EXCLUDED 与 WRITE 条目可为空
   * @param writeCases 写接口用例；仅 WRITE 条目必填（至少一个）
   * @param excludeReason 排除原因；仅 EXCLUDED 必填
   */
  public record EndpointEntry(
      String method,
      String path,
      Category category,
      ResponseKind response,
      List<String> ignorePaths,
      List<String> ignoreArrayOrder,
      List<ReadCase> cases,
      List<WriteCase> writeCases,
      String excludeReason) {}

  private static final String CATALOG_RESOURCE = "golden-baseline/endpoint-catalog.yml";

  private final Map<String, EndpointEntry> entriesByKey;

  private GoldenEndpointCatalog(Map<String, EndpointEntry> entriesByKey) {
    // 不可变但必须保序：动态用例按 YAML 声明顺序展开，失败索引才能稳定对应到端点。
    this.entriesByKey = Collections.unmodifiableMap(entriesByKey);
  }

  /**
   * 从 classpath 加载并解析端点目录。
   *
   * @return 不可变目录实例
   * @throws IllegalStateException 资源缺失、YAML 结构非法或条目键重复
   */
  public static GoldenEndpointCatalog load() {
    try (InputStream in = Thread.currentThread()
        .getContextClassLoader()
        .getResourceAsStream(CATALOG_RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("缺少黄金基线端点目录资源: " + CATALOG_RESOURCE);
      }
      return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException("读取黄金基线端点目录失败: " + CATALOG_RESOURCE, e);
    }
  }

  /**
   * 解析目录 YAML 文本为不可变目录。
   *
   * @param yamlText 目录文件全文
   * @return 不可变目录实例
   * @throws IllegalStateException YAML 结构非法或条目键重复
   */
  @SuppressWarnings("unchecked")
  static GoldenEndpointCatalog parse(String yamlText) {
    Yaml yaml = new Yaml();
    Object root = yaml.load(yamlText);
    if (!(root instanceof Map<?, ?> rootMap)
        || !(rootMap.get("endpoints") instanceof List<?> endpointList)) {
      throw new IllegalStateException("端点目录必须包含 endpoints 列表");
    }
    Map<String, EndpointEntry> entries = new LinkedHashMap<>();
    for (Object item : endpointList) {
      if (!(item instanceof Map<?, ?>)) {
        throw new IllegalStateException("端点条目必须是键值映射");
      }
      Map<String, Object> map = (Map<String, Object>) item;
      EndpointEntry entry = toEntry(map);
      String key = key(entry.method(), entry.path());
      EndpointEntry previous = entries.putIfAbsent(key, entry);
      if (previous != null) {
        throw new IllegalStateException("端点目录键重复: " + key);
      }
      entries.put(key, entry);
    }
    return new GoldenEndpointCatalog(entries);
  }

  /**
   * 计算端点目录键。
   *
   * @param method HTTP 方法
   * @param path 路径模板
   * @return 形如 {@code GET /api/xxx} 的键
   */
  public static String key(String method, String path) {
    return method.toUpperCase(Locale.ROOT) + " " + path;
  }

  /**
   * 返回全部条目（键 {@code METHOD path} → 条目）。
   *
   * @return 不可变映射
   */
  public Map<String, EndpointEntry> entries() {
    return entriesByKey;
  }

  private static EndpointEntry toEntry(Map<String, Object> map) {
    String method = requireText(map, "method");
    String path = requireText(map, "path");
    Category category = Category.valueOf(requireText(map, "category").toUpperCase(Locale.ROOT));
    String responseText = text(map, "response");
    ResponseKind response = responseText == null
        ? ResponseKind.JSON
        : ResponseKind.valueOf(responseText.toUpperCase(Locale.ROOT));
    List<String> ignorePaths = stringList(map.get("ignore-paths"));
    List<String> ignoreArrayOrder = stringList(map.get("ignore-array-order"));
    List<ReadCase> cases = readCases(map.get("cases"));
    List<WriteCase> writeCases = writeCases(map.get("write-cases"));
    String excludeReason = text(map, "exclude-reason");
    validateEntry(method, path, category, cases, writeCases, excludeReason);
    return new EndpointEntry(
        method, path, category, response, ignorePaths, ignoreArrayOrder, cases, writeCases,
        excludeReason);
  }

  private static void validateEntry(
      String method, String path, Category category,
      List<ReadCase> cases, List<WriteCase> writeCases, String excludeReason) {
    switch (category) {
      case EXCLUDED -> {
        if (excludeReason == null || excludeReason.isBlank()) {
          throw new IllegalStateException("EXCLUDED 条目必须给出排除原因: " + method + " " + path);
        }
        if (!cases.isEmpty() || !writeCases.isEmpty()) {
          throw new IllegalStateException("EXCLUDED 条目不允许声明用例: " + method + " " + path);
        }
      }
      case WRITE -> {
        if (writeCases.isEmpty()) {
          throw new IllegalStateException(
              "WRITE 条目必须声明至少一个 write-case（无法快照的行为须改判 EXCLUDED 并给出原因）: "
                  + method + " " + path);
        }
        if (!cases.isEmpty()) {
          throw new IllegalStateException("WRITE 条目不允许声明 read cases: " + method + " " + path);
        }
      }
      default -> {
        if (cases.isEmpty()) {
          throw new IllegalStateException("数据产出条目必须声明至少一个用例: " + method + " " + path);
        }
        if (!writeCases.isEmpty()) {
          throw new IllegalStateException("非 WRITE 条目不允许声明 write-cases: " + method + " " + path);
        }
      }
    }
  }

  @SuppressWarnings("unchecked")
  private static List<WriteCase> writeCases(Object raw) {
    if (raw == null) {
      return List.of();
    }
    if (!(raw instanceof List<?> list)) {
      throw new IllegalStateException("write-cases 必须是列表");
    }
    List<WriteCase> cases = new ArrayList<>();
    for (Object item : list) {
      if (!(item instanceof Map<?, ?>)) {
        throw new IllegalStateException("write-case 条目必须是键值映射");
      }
      Map<String, Object> map = (Map<String, Object>) item;
      if (map.get("id") == null) {
        throw new IllegalStateException("write-case 缺少必填字段: id");
      }
      Object query = map.get("query");
      Object body = map.get("body");
      cases.add(new WriteCase(
          String.valueOf(map.get("id")),
          query == null ? "" : String.valueOf(query),
          body == null ? null : String.valueOf(body),
          affectedTables(map.get("affected-tables"))));
    }
    return List.copyOf(cases);
  }

  @SuppressWarnings("unchecked")
  private static List<AffectedTable> affectedTables(Object raw) {
    if (raw == null) {
      return List.of();
    }
    if (!(raw instanceof List<?> list)) {
      throw new IllegalStateException("affected-tables 必须是列表");
    }
    List<AffectedTable> tables = new ArrayList<>();
    for (Object item : list) {
      if (!(item instanceof Map<?, ?>)) {
        throw new IllegalStateException("affected-tables 条目必须是键值映射");
      }
      Map<String, Object> map = (Map<String, Object>) item;
      String table = text(map, "table");
      if (table == null || table.isBlank()) {
        throw new IllegalStateException("affected-table 缺少必填字段: table");
      }
      RestoreMode restore = map.get("restore") == null
          ? RestoreMode.AUTO
          : RestoreMode.valueOf(String.valueOf(map.get("restore")).toUpperCase(Locale.ROOT));
      tables.add(new AffectedTable(
          table,
          text(map, "scope"),
          stringList(map.get("ignore-columns")),
          restore));
    }
    return List.copyOf(tables);
  }

  @SuppressWarnings("unchecked")
  private static List<ReadCase> readCases(Object raw) {
    if (raw == null) {
      return List.of();
    }
    if (!(raw instanceof List<?> list)) {
      throw new IllegalStateException("cases 必须是列表");
    }
    List<ReadCase> cases = new ArrayList<>();
    for (Object item : list) {
      if (!(item instanceof Map<?, ?>)) {
        throw new IllegalStateException("case 条目必须是键值映射");
      }
      Map<String, Object> map = (Map<String, Object>) item;
      String id = String.valueOf(map.get("id"));
      Object query = map.get("query");
      Object body = map.get("body");
      cases.add(new ReadCase(
          id,
          query == null ? "" : String.valueOf(query),
          body == null ? null : String.valueOf(body)));
    }
    return List.copyOf(cases);
  }

  @SuppressWarnings("unchecked")
  private static List<String> stringList(Object raw) {
    if (raw == null) {
      return List.of();
    }
    if (!(raw instanceof List<?> list)) {
      throw new IllegalStateException("路径列表字段必须是字符串列表");
    }
    return List.copyOf((List<String>) list);
  }

  private static String requireText(Map<String, Object> map, String field) {
    String value = text(map, field);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("端点条目缺少必填字段: " + field);
    }
    return value;
  }

  private static String text(Map<String, Object> map, String field) {
    Object value = map.get(field);
    return value == null ? null : String.valueOf(value);
  }
}
