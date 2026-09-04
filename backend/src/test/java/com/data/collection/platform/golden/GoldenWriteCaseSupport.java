package com.data.collection.platform.golden;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.zip.GZIPInputStream;

/**
 * 写接口用例的表状态引擎：受影响表的还原与快照。
 *
 * <p>还原基线有两个来源：冻结种子文件（platform-seed.sql.gz 按表注释分块的 INSERT）与
 * 全链路引导完成后的运行期捕获快照（迁移播种/协调器重建的表不在种子文件中）。
 * REPLAY 还原 = 关闭外键触发器（session_replication_role = replica）后 DELETE 再重放对应
 * 基线行，并校准 id 序列，使任意执行顺序下写用例彼此隔离、且不污染读快照断言的链路状态。</p>
 */
public final class GoldenWriteCaseSupport {

  private static final Path SEED_FIXTURE = Path.of("golden-baseline", "fixtures", "platform-seed.sql.gz");
  private static final Pattern TABLE_MARKER = Pattern.compile("^-- table: ([\\w]+), rows: \\d+");
  private static final int INSERT_BATCH_SIZE = 500;
  private static final ObjectMapper JSON = new ObjectMapper();

  /** 种子文件按表分块的 INSERT 语句。 */
  private static final Map<String, String> SEED_BLOCKS = loadSeedBlocks();

  /** 运行期捕获的整表基线行：表名 → 行数组（引导完成后写入，写用例期间只读）。 */
  private static final Map<String, ArrayNode> CAPTURED_BASELINES = new LinkedHashMap<>();

  private GoldenWriteCaseSupport() {}

  /**
   * 捕获指定表在引导完成后的整表基线（用于不在种子文件中的 REPLAY 表）。
   *
   * <p>必须在任何写用例执行前调用一次；重复调用以首次为准，避免用例执行后的状态被误当基线。
   * 已捕获或在种子文件中的表直接跳过。</p>
   *
   * @param tables 表名列表
   * @throws SQLException 读取失败
   */
  public static synchronized void captureReplayBaselines(List<String> tables) throws SQLException {
    try (Connection connection = platformConnection()) {
      for (String table : tables) {
        if (CAPTURED_BASELINES.containsKey(table) || SEED_BLOCKS.containsKey(table)) {
          continue;
        }
        CAPTURED_BASELINES.put(table, readRows(connection, table, null));
      }
    }
  }

  /**
   * 还原受影响表到基线状态：用例执行前调用保证起点干净，快照后调用保证不污染后续用例。
   *
   * @param table 受影响表声明
   * @throws SQLException 还原失败
   */
  public static void restore(GoldenEndpointCatalog.AffectedTable table) throws SQLException {
    switch (table.restore()) {
      case REPLAY -> replayTable(table.table());
      case TRUNCATE -> truncateTable(table.table());
      case DELETE -> {
        if (table.scope() == null) {
          throw new IllegalStateException("DELETE 还原必须声明 scope: " + table.table());
        }
        execute("delete from " + table.table() + " where " + table.scope());
      }
      case NONE -> {
        // 声明性不还原：由用例自身保证幂等
      }
      case AUTO -> throw new IllegalStateException("AUTO 策略应在目录解析后展开: " + table.table());
    }
  }

  /**
   * 生成表状态快照 JSON 文本：按全列排序的行数组，剔除声明的易变列。
   *
   * @param table 物理表名
   * @param scope 快照 WHERE 行过滤片段；null 表示整表
   * @param ignoreColumns 剔除的易变列名
   * @return 形如 {@code {"table": "...", "rows": [...]}} 的 JSON 文本
   * @throws SQLException 读取失败
   */
  public static String snapshotTable(String table, String scope, List<String> ignoreColumns)
      throws SQLException {
    try (Connection connection = platformConnection()) {
      ArrayNode rows = readRows(connection, table, scope, ignoreColumns.toArray(String[]::new));
      ObjectNode root = JSON.createObjectNode();
      root.put("table", table);
      root.set("rows", rows);
      return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root);
    } catch (IOException e) {
      throw new UncheckedIOException("序列化表状态快照失败: " + table, e);
    }
  }

  private static void replayTable(String table) throws SQLException {
    String seedBlock = SEED_BLOCKS.get(table);
    ArrayNode captured = CAPTURED_BASELINES.get(table);
    if (seedBlock == null && captured == null) {
      throw new IllegalStateException(
          "REPLAY 表缺少还原基线（须在引导后捕获或存在于种子文件）: " + table);
    }
    try (Connection connection = platformConnection();
        Statement statement = connection.createStatement()) {
      // TRUNCATE 无法绕过外键引用检查；replica 模式下 DELETE 可无视反向引用行级触发器
      statement.execute("set session_replication_role = replica");
      statement.execute("delete from " + table);
      if (seedBlock != null) {
        statement.execute(seedBlock);
      } else {
        insertRows(statement, table, captured);
      }
      realignIdSequence(statement, table);
      statement.execute("set session_replication_role = DEFAULT");
    }
  }

  private static void truncateTable(String table) throws SQLException {
    try (Connection connection = platformConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("set session_replication_role = replica");
      statement.execute("delete from " + table);
      statement.execute("set session_replication_role = DEFAULT");
    }
  }

  /** 将捕获基线行以字符串字面量分批回插；unknown 类型字面量由 PostgreSQL 按列类型收敛。 */
  private static void insertRows(Statement statement, String table, ArrayNode rows)
      throws SQLException {
    if (rows.isEmpty()) {
      return;
    }
    List<String> columns = new ArrayList<>();
    rows.get(0).fieldNames().forEachRemaining(columns::add);
    String columnList = columns.stream()
        .map(c -> "\"" + c + "\"")
        .collect(Collectors.joining(", "));
    for (int from = 0; from < rows.size(); from += INSERT_BATCH_SIZE) {
      int to = Math.min(from + INSERT_BATCH_SIZE, rows.size());
      String values = IntStream.range(from, to)
          .mapToObj(rows::get)
          .map(row -> rowValues(columns, row))
          .collect(Collectors.joining("), ("));
      statement.execute("insert into " + table + " (" + columnList + ") values ("
          + values + ")");
    }
  }

  private static String rowValues(List<String> columns, JsonNode row) {
    return columns.stream()
        .map(column -> {
          JsonNode value = row.get(column);
          return value == null || value.isNull() ? "NULL" : quoteLiteral(value.asText());
        })
        .collect(Collectors.joining(", "));
  }

  /** 校准单列 id 序列到当前最大 id，避免用例新建行时主键冲突；无 id 列的复合自然键表跳过。 */
  private static void realignIdSequence(Statement statement, String table) throws SQLException {
    // pg_get_serial_sequence 对不存在的列直接报错，必须先确认 id 列存在
    try (ResultSet rs = statement.executeQuery(
        "select 1 from pg_attribute "
            + "where attrelid = to_regclass('" + table + "') and attname = 'id' "
            + "and not attisdropped")) {
      if (!rs.next()) {
        return;
      }
    }
    String sequence;
    try (ResultSet rs = statement.executeQuery(
        "select pg_get_serial_sequence('" + table + "', 'id')")) {
      rs.next();
      sequence = rs.getString(1);
    }
    if (sequence == null) {
      return;
    }
    statement.execute("select setval('" + sequence + "', coalesce((select max(id) from "
        + table + "), 0) + 1, false)");
  }

  private static ArrayNode readRows(
      Connection connection, String table, String scope, String... ignoreColumns)
      throws SQLException {
    Set<String> ignored = Set.of(ignoreColumns);
    ArrayNode rows = JSON.createArrayNode();
    try (Statement statement = connection.createStatement()) {
      String sql = "select * from " + table + (scope == null ? "" : " where " + scope);
      try (ResultSet rs = statement.executeQuery(sql)) {
        int columnCount = rs.getMetaData().getColumnCount();
        List<String> columns = new ArrayList<>(columnCount);
        for (int i = 1; i <= columnCount; i++) {
          columns.add(rs.getMetaData().getColumnLabel(i));
        }
        while (rs.next()) {
          ObjectNode row = rows.addObject();
          for (String column : columns) {
            if (ignored.contains(column)) {
              continue;
            }
            String value = rs.getString(column);
            if (value == null) {
              row.putNull(column);
            } else {
              row.put(column, value);
            }
          }
        }
      }
      // 无主键假设下的确定性排序：全列排序后重写行序
      List<JsonNode> sorted = new ArrayList<>();
      rows.forEach(sorted::add);
      sorted.sort(java.util.Comparator.comparing(JsonNode::toString));
      rows.removeAll();
      sorted.forEach(rows::add);
    }
    return rows;
  }

  private static String quoteLiteral(String value) {
    return "'" + value.replace("'", "''") + "'";
  }

  private static void execute(String sql) throws SQLException {
    try (Connection connection = platformConnection();
        Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  /** 解析种子文件中按 {@code -- table: <名>, rows: <数>} 注释分块的 INSERT 语句。 */
  private static Map<String, String> loadSeedBlocks() {
    try (InputStream input = Thread.currentThread().getContextClassLoader()
        .getResourceAsStream(SEED_FIXTURE.toString().replace('\\', '/'))) {
      if (input == null) {
        throw new IllegalStateException("冻结种子缺失: " + SEED_FIXTURE);
      }
      Map<String, String> blocks = new LinkedHashMap<>();
      String currentTable = null;
      StringBuilder current = new StringBuilder();
      try (BufferedReader reader = new BufferedReader(
          new InputStreamReader(new GZIPInputStream(input), StandardCharsets.UTF_8))) {
        String line;
        while ((line = reader.readLine()) != null) {
          Matcher matcher = TABLE_MARKER.matcher(line);
          if (matcher.find()) {
            if (currentTable != null) {
              blocks.put(currentTable, current.toString());
            }
            currentTable = matcher.group(1);
            current = new StringBuilder();
          } else if (currentTable != null) {
            current.append(line).append('\n');
          }
        }
      }
      if (currentTable != null) {
        blocks.put(currentTable, current.toString());
      }
      return blocks;
    } catch (IOException e) {
      throw new UncheckedIOException("读取冻结种子失败: " + SEED_FIXTURE, e);
    }
  }

  private static Connection platformConnection() throws SQLException {
    return DriverManager.getConnection(
        GoldenBaselineSupport.platformJdbcUrl(),
        GoldenBaselineSupport.PLATFORM_DB.getUsername(),
        GoldenBaselineSupport.PLATFORM_DB.getPassword());
  }
}
