package com.data.collection.platform.golden;

import com.data.collection.platform.entity.sync.SyncRunType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * 黄金基线全链路运行时：编排 GitLab 源库与平台库双容器，导入冻结夹具，种子同步配置，
 * 提供登录会话与全量同步/事实构建终态等待。
 *
 * <p>容器与同步结果在整个套件内共享一次：源库夹具在 Spring 上下文启动前恢复，
 * 平台种子在 Flyway 迁移完成后导入。切片为有界窗口快照，数据载入放宽外键触发器
 * （session_replication_role = replica），与冻结 manifest 的孤儿盘点记录配套。
 */
public final class GoldenBaselineSupport {

  private static final String FIXTURE_DIR = "golden-baseline/fixtures";
  public static final String SOURCE_DB_NAME = "gitlabhq_full_import_test";
  private static final String SOURCE_DB_USER = "gitlab";
  private static final String SOURCE_DB_PASSWORD = "secret";
  private static final String SYNC_CONFIG_NAME = "golden-baseline";
  private static final Duration POLL_INTERVAL = Duration.ofSeconds(2);
  private static final Duration SYNC_TIMEOUT = Duration.ofMinutes(8);
  private static final Duration FACT_TIMEOUT = Duration.ofMinutes(5);
  private static final Set<String> SYNC_RUN_TERMINAL_STATUSES =
      Set.of("SUCCESS", "PARTIAL_SUCCESS", "FAILED", "CANCELLED", "TIMEOUT", "MERGED");
  private static final ObjectMapper JSON = new ObjectMapper();

  /** GitLab 源库容器：承载冻结的 schema 与数据切片，平台以 DIRECT 模式只读同步。 */
  public static final PostgreSQLContainer<?> GITLAB_SOURCE =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName(SOURCE_DB_NAME)
          .withUsername(SOURCE_DB_USER)
          .withPassword(SOURCE_DB_PASSWORD);

  /** 平台库容器：Flyway 迁移后由 Spring 上下文使用，种子数据构成冻结输入的一部分。 */
  public static final PostgreSQLContainer<?> PLATFORM_DB =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("qaflex")
          .withUsername("qaflex")
          .withPassword("qaflex");

  private GoldenBaselineSupport() {}

  /** 平台库的 JDBC 连接串，指向 qaflex_test schema（与测试配置的迁移 schema 一致）。 */
  public static String platformJdbcUrl() {
    return "jdbc:postgresql://" + PLATFORM_DB.getHost() + ":" + PLATFORM_DB.getMappedPort(5432)
        + "/" + PLATFORM_DB.getDatabaseName() + "?currentSchema=qaflex_test,public";
  }

  /** 启动 GitLab 源库并恢复冻结的 schema 与数据切片；必须在 Spring 上下文启动前完成。 */
  public static void startSourceDatabase() {
    GITLAB_SOURCE.start();
    importFixture(GITLAB_SOURCE, "gitlab-schema.sql.gz", List.of());
    importFixture(GITLAB_SOURCE, "gitlab-source-slice.sql.gz",
        List.of("SET session_replication_role = replica"));
  }

  /** 在平台库导入冻结种子数据；必须在 Flyway 迁移完成后调用。 */
  public static void importPlatformSeed() {
    importFixture(PLATFORM_DB, "platform-seed.sql.gz", List.of("SET search_path = qaflex_test"));
  }

  /**
   * 种子一行 DIRECT 模式同步配置，指向 GitLab 源容器。
   *
   * @return 新配置 id
   */
  public static long seedSyncConfig() throws SQLException {
    try (Connection connection = platformConnection();
        Statement statement = connection.createStatement()) {
      statement.execute(
          """
          insert into gitlab_sync_configs
            (name, enabled, auto_sync_enabled, source_mode, whitelist_mode,
             db_host, db_port, db_name, db_username, db_password)
          values ('%s', true, false, 'DIRECT', 'RECOMMENDED', '%s', %d, '%s', '%s', '%s')
          """
              .formatted(
                  SYNC_CONFIG_NAME,
                  GITLAB_SOURCE.getHost(),
                  GITLAB_SOURCE.getMappedPort(5432),
                  SOURCE_DB_NAME,
                  SOURCE_DB_USER,
                  SOURCE_DB_PASSWORD));
      ResultSet rs = statement.executeQuery(
          "select id from gitlab_sync_configs where name = '" + SYNC_CONFIG_NAME + "'");
      rs.next();
      return rs.getLong(1);
    }
  }

  /** 统计平台库 qaflex_test schema 下某表的行数，供链路 smoke 断言使用。 */
  public static long countPlatformRows(String table) throws SQLException {
    try (Connection connection = platformConnection();
        Statement statement = connection.createStatement();
        ResultSet rs = statement.executeQuery("select count(*) from " + table)) {
      rs.next();
      return rs.getLong(1);
    }
  }

  /**
   * 在平台库执行单值查询，返回首行首列的字符串形式。
   *
   * @param sql 返回单列的查询
   * @return 首行首列字符串；无行时抛出 IllegalStateException
   * @throws SQLException 查询失败
   */
  public static String queryScalar(String sql) throws SQLException {
    try (Connection connection = platformConnection();
        Statement statement = connection.createStatement();
        ResultSet rs = statement.executeQuery(sql)) {
      if (!rs.next()) {
        throw new IllegalStateException("标量查询无结果：" + sql);
      }
      return rs.getString(1);
    }
  }

  private static Connection platformConnection() throws SQLException {
    return DriverManager.getConnection(
        platformJdbcUrl(), PLATFORM_DB.getUsername(), PLATFORM_DB.getPassword());
  }

  private static void importFixture(
      PostgreSQLContainer<?> container, String fixtureName, List<String> sessionSetup) {
    Path sql = decompressFixture(fixtureName);
    String containerPath = "/tmp/" + fixtureName.replace(".gz", ".sql");
    container.copyFileToContainer(MountableFile.forHostPath(sql), containerPath);
    StringBuilder psql = new StringBuilder("psql -U ").append(container.getUsername())
        .append(" -d ").append(container.getDatabaseName())
        .append(" -v ON_ERROR_STOP=1 -q");
    for (String setup : sessionSetup) {
      psql.append(" -c \"").append(setup).append("\"");
    }
    psql.append(" -f ").append(containerPath);
    try {
      var result = container.execInContainer("sh", "-c", psql.toString());
      if (result.getExitCode() != 0) {
        throw new IllegalStateException(
            "夹具导入失败：" + fixtureName + "\n" + result.getStderr());
      }
    } catch (IOException | InterruptedException e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      throw new IllegalStateException("夹具导入执行异常：" + fixtureName, e);
    } finally {
      try {
        Files.deleteIfExists(sql);
      } catch (IOException ignored) {
        // 临时解压文件清理失败不影响结果
      }
    }
  }

  private static Path decompressFixture(String fixtureName) {
    Path resource = Path.of(FIXTURE_DIR, fixtureName);
    try (InputStream input = Thread.currentThread().getContextClassLoader()
        .getResourceAsStream(resource.toString().replace('\\', '/'))) {
      if (input == null) {
        throw new IllegalStateException("冻结夹具缺失：" + resource);
      }
      Path target = Files.createTempFile("golden-" + fixtureName.replace(".gz", ""), ".sql");
      try (var output = Files.newOutputStream(target);
          var gunzip = new java.util.zip.GZIPInputStream(input)) {
        gunzip.transferTo(output);
      }
      target.toFile().deleteOnExit();
      return target;
    } catch (IOException e) {
      throw new UncheckedIOException("解压夹具失败：" + fixtureName, e);
    }
  }

  /** 触发真实全量同步并等待同步与自动跟随的事实刷新全部到达终态；失败抛 AssertionError。 */
  public static void triggerFullSyncAndAwait(GoldenHttpSession session, long configId)
      throws SQLException, IOException, InterruptedException {
    session.postJson("/api/gitlab-sync/full-sync/by-config?configId=" + configId, null);
    long fullRunId = awaitRunTerminal(configId, SyncRunType.FULL_SYNC, 0L, SYNC_TIMEOUT);
    awaitRunTerminal(configId, SyncRunType.FACT_REFRESH, fullRunId, FACT_TIMEOUT);
  }

  /**
   * 轮询 sync_runs 直到目标运行到达终态。
   *
   * @param minRunId 只接受 id 大于该值的运行（事实刷新等自动跟随运行按提交顺序晚于父运行）；
   *                 传 0 表示等待该类型任意运行
   * @return 到达终态的运行号
   */
  private static long awaitRunTerminal(
      long configId, SyncRunType runType, long minRunId, Duration timeout) throws SQLException {
    long deadline = System.nanoTime() + timeout.toNanos();
    String lastStatus = "absent";
    while (System.nanoTime() < deadline) {
      String sql = "select id, status from sync_runs where config_id = ? and run_type = ? and id > ? order by id desc limit 1";
      try (Connection connection = platformConnection();
          PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setLong(1, configId);
        statement.setString(2, runType.name());
        statement.setLong(3, minRunId);
        try (ResultSet rs = statement.executeQuery()) {
          if (rs.next()) {
            String status = rs.getString("status");
            if (SYNC_RUN_TERMINAL_STATUSES.contains(status)) {
              if ("SUCCESS".equals(status)) {
                return rs.getLong("id");
              }
              throw new AssertionError(
                  runType + " 运行终态异常：" + status + "\n" + recentSyncDiagnostics(configId));
            }
            lastStatus = status;
          }
        }
      }
      try {
        Thread.sleep(POLL_INTERVAL.toMillis());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("等待同步终态被中断", e);
      }
    }
    throw new AssertionError(
        runType + " 运行等待超时（最后状态 " + lastStatus + "）\n" + recentSyncDiagnostics(configId));
  }

  private static String recentSyncDiagnostics(long configId) {
    List<String> lines = new ArrayList<>();
    String runSql = """
        select run_type, status, coalesce(left(error_message, 200), request_reason), started_at
          from sync_runs
         where config_id = ?
         order by id desc
         limit 5
        """;
    String taskSql = """
        select source_table, status, task_type
          from sync_run_table_tasks
         where config_id = ?
           and status in ('FAILED', 'RETRYING')
         order by id desc
         limit 10
        """;
    try (Connection connection = platformConnection()) {
      try (PreparedStatement statement = connection.prepareStatement(runSql)) {
        statement.setLong(1, configId);
        try (ResultSet rs = statement.executeQuery()) {
          while (rs.next()) {
            lines.add("[run] %s %s %s %s".formatted(
                rs.getTimestamp(4), rs.getString(1), rs.getString(2), rs.getString(3)));
          }
        }
      }
      try (PreparedStatement statement = connection.prepareStatement(taskSql)) {
        statement.setLong(1, configId);
        try (ResultSet rs = statement.executeQuery()) {
          while (rs.next()) {
            lines.add("[task] %s %s %s".formatted(
                rs.getString(1), rs.getString(2), rs.getString(3)));
          }
        }
      }
    } catch (SQLException e) {
      return "诊断读取失败：" + e.getMessage();
    }
    return String.join("\n", lines);
  }

  /**
   * 登录后的 HTTP 会话：cookie 自动保持，写请求自动携带会话采集的 X-XSRF-TOKEN。
   * 所有请求断言平台 ApiResponse success，失败时抛出带状态码与消息的 IllegalStateException。
   */
  public static final class GoldenHttpSession {

    private final HttpClient client =
        HttpClient.newBuilder().cookieHandler(new CookieManager()).connectTimeout(Duration.ofSeconds(10)).build();
    private final URI base;
    private volatile String csrfToken = "";

    private GoldenHttpSession(String baseUrl) {
      this.base = URI.create(baseUrl);
    }

    /**
     * 以真实登录链路建立会话：先获取 CSRF 令牌，再提交用户名密码登录。
     *
     * @return 已认证会话
     */
    public static GoldenHttpSession login(String baseUrl, String username, String password)
        throws IOException, InterruptedException {
      GoldenHttpSession session = new GoldenHttpSession(baseUrl);
      session.exchange("GET", "/api/auth/current", null);
      ObjectNode credentials = JSON.createObjectNode();
      credentials.put("username", username);
      credentials.put("password", password);
      session.exchange("POST", "/api/auth/login", JSON.writeValueAsString(credentials));
      return session;
    }

    /** GET 请求并断言业务成功，返回 data 节点；data 为空时返回 null。 */
    public JsonNode getJson(String path) throws IOException, InterruptedException {
      return parseSuccess(exchange("GET", path, null));
    }

    /** POST 请求并断言业务成功，返回 data 节点；data 为空时返回 null。 */
    public JsonNode postJson(String path, String body) throws IOException, InterruptedException {
      return parseSuccess(exchange("POST", path, body));
    }

    /** GET 并返回完整响应体文本（仅校验 HTTP 200；快照器负责比对完整 ApiResponse 结构）。 */
    public String getRawJson(String path) throws IOException, InterruptedException {
      return requireBodyOk(exchange("GET", path, null));
    }

    /** POST 并返回完整响应体文本（仅校验 HTTP 200）。 */
    public String postRawJson(String path, String body) throws IOException, InterruptedException {
      return requireBodyOk(exchange("POST", path, body));
    }

    /**
     * 任意方法的写请求并返回嵌入 HTTP 状态码的响应体（不校验状态码）。
     *
     * <p>写用例的确定性失败（校验拒绝 400 等）与成功（200）同为行为契约，
     * 返回 {@code {"httpStatus": <码>, "body": <JSON 或文本>}} 供响应快照断言。</p>
     *
     * @param method HTTP 方法大写形式
     * @param path 请求路径（含查询串）
     * @param body 请求体文本；可为空
     * @return 形如 {@code {"httpStatus":...,"body":...}} 的 JSON 文本
     */
    public String exchangeAnyJson(String method, String path, String body)
        throws IOException, InterruptedException {
      HttpResponse<String> response = exchange(method, path, body);
      ObjectNode root = JSON.createObjectNode();
      root.put("httpStatus", response.statusCode());
      JsonNode parsed = null;
      if (!response.body().isBlank()) {
        try {
          parsed = JSON.readTree(response.body());
        } catch (IOException e) {
          parsed = null;
        }
      }
      root.set("body", parsed == null ? JSON.getNodeFactory().textNode(response.body()) : parsed);
      return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root);
    }

    /** GET 并返回导出内容字节（Excel/CSV 等二进制或文本响应体）。 */
    public byte[] getBytes(String path) throws IOException, InterruptedException {
      HttpRequest request = newRequest("GET", path, null).header("Accept", "*/*").build();
      HttpResponse<byte[]> response =
          client.send(request, HttpResponse.BodyHandlers.ofByteArray());
      requireOk(response.statusCode(), new String(response.body(), StandardCharsets.UTF_8));
      return response.body();
    }

    private JsonNode parseSuccess(HttpResponse<String> response) throws IOException {
      requireOk(response.statusCode(), response.body());
      JsonNode payload = JSON.readTree(response.body());
      if (!payload.path("success").asBoolean(false)) {
        throw new IllegalStateException(
            "业务失败：" + payload.path("code").asText() + " " + payload.path("message").asText());
      }
      JsonNode data = payload.path("data");
      return data.isMissingNode() || data.isNull() ? null : data;
    }

    private static String requireBodyOk(HttpResponse<String> response) {
      requireOk(response.statusCode(), response.body());
      return response.body();
    }

    private static void requireOk(int statusCode, String body) {
      if (statusCode != 200) {
        throw new IllegalStateException("HTTP " + statusCode + " " + truncate(body));
      }
    }

    private HttpResponse<String> exchange(String method, String path, String body)
        throws IOException, InterruptedException {
      HttpResponse<String> response =
          client.send(newRequest(method, path, body).build(), HttpResponse.BodyHandlers.ofString());
      String token = response.headers().firstValue("x-xsrf-token").orElse("");
      if (!token.isEmpty()) {
        csrfToken = token;
      }
      return response;
    }

    private HttpRequest.Builder newRequest(String method, String path, String body) {
      HttpRequest.Builder builder = HttpRequest.newBuilder()
          .uri(base.resolve(path))
          // gitlab-sync/diagnostics 会探测 golden 档不可达的 GitLab Web API，阻塞至对端连接超时，
          // 请求预算需显著大于对端超时才能拿到确定性失败响应。
          .timeout(Duration.ofSeconds(120))
          .header("Accept", "application/json");
      if (!"GET".equals(method)) {
        builder.header("X-XSRF-TOKEN", csrfToken);
      }
      if (body != null) {
        builder.header("Content-Type", "application/json");
        builder.method(method, HttpRequest.BodyPublishers.ofString(body));
      } else {
        builder.method(method, HttpRequest.BodyPublishers.noBody());
      }
      return builder;
    }

    private static String truncate(String text) {
      return text.length() <= 300 ? text : text.substring(0, 300) + "...";
    }
  }
}
