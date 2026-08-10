package com.data.collection.platform.bi.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.data.collection.platform.bi.infrastructure.BiCatMirrorModels.ScopeMapping;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BiCatSnapshotCollectorTest {
  private final List<CapturedRequest> requests = new CopyOnWriteArrayList<>();
  private HttpServer server;
  private BiCatHttpClient client;

  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.start();
    client = client(server.getAddress().getPort(), 256_000);
  }

  @AfterEach
  void stopServer() {
    server.stop(0);
  }

  @Test
  void collectStage_validCatalogAndStatistics_preservesRawAndNormalizedData() {
    respond("/integrationSearch/getAllProject", 200, projects());
    respond("/testingPhase/getAllByProjectId", 200, phaseTree());
    respond("/integrationSearch/getStatisticsInfoByTPId", 200, statistics());
    respond("/getFeatureInfoByModuleId", 200, features());
    var collector = collector();
    var mapping = mapping();

    var catalog = collector.collectCatalog(client);
    var snapshot = collector.collectStage(client, mapping, "UNIT_TEST", catalog);

    assertThat(catalog.projects()).singleElement().satisfies(project -> {
      assertThat(project.id()).isEqualTo("project-cc");
      assertThat(project.note()).isEqualTo("CAT 项目原始备注");
    });
    assertThat(catalog.nodes()).extracting("nodeType")
        .containsExactly("VERSION", "TEST_PHASE", "TEST_PHASE");
    assertThat(catalog.rawResponses()).hasSize(2)
        .allSatisfy(raw -> assertThat(raw.responsePayload()).contains("\"code\":200"));
    assertThat(snapshot.dataStatus()).isEqualTo("READY");
    assertThat(snapshot.overallPassRate()).isEqualByComparingTo("97.20");
    assertThat(snapshot.attainedFunctionCount()).isEqualTo(12L);
    assertThat(snapshot.totalFunctionCount()).isEqualTo(15L);
    assertThat(snapshot.modules()).singleElement().satisfies(module ->
        assertThat(module.passRate()).isEqualByComparingTo("96.50"));
    assertThat(snapshot.functions()).extracting("id")
        .containsExactly("feature-001", "feature-002");
    assertThat(snapshot.rawResponses()).hasSize(2);
    assertThat(requests).filteredOn(request ->
        request.path().equals("/integrationSearch/getStatisticsInfoByTPId"))
        .extracting(CapturedRequest::query)
        .containsExactly("testingPhaseId=unit-phase-r4");
    assertThat(requests).filteredOn(request ->
        request.path().equals("/testingPhase/getAllByProjectId"))
        .extracting(CapturedRequest::query)
        .containsExactly("projectId=project-cc");
    assertThat(requests).filteredOn(request ->
        request.path().equals("/getFeatureInfoByModuleId"))
        .singleElement()
        .satisfies(request -> {
          assertThat(request.method()).isEqualTo("POST");
          assertThat(request.body()).contains(
              "\"moduleId\":\"module-001\"",
              "\"testingPhaseId\":\"unit-phase-r4\"");
        });
  }

  @Test
  void collectStage_conflictingModuleNames_rejectsSnapshotBeforePublication() {
    respond("/integrationSearch/getAllProject", 200, projects());
    respond("/testingPhase/getAllByProjectId", 200, phaseTree());
    respond(
        "/integrationSearch/getStatisticsInfoByTPId",
        200,
        statistics().replace("\"name\":\"草图模块\"", "\"name\":\"零件模块\""));
    var collector = collector();
    var catalog = collector.collectCatalog(client);

    assertThatThrownBy(() -> collector.collectStage(client, mapping(), "INTEGRATION_TEST", catalog))
        .hasMessageContaining("模块名称字段不一致");
  }

  @Test
  void collectCatalog_responseExceedsLimit_rejectsBeforeDeserialization() {
    client = client(server.getAddress().getPort(), 64);
    respond("/integrationSearch/getAllProject", 200, projects());

    assertThatThrownBy(() -> collector().collectCatalog(client))
        .isInstanceOf(BiCatUpstreamException.class)
        .hasMessageContaining("超过大小上限");
  }

  private BiCatSnapshotCollector collector() {
    return new BiCatSnapshotCollector(Clock.fixed(
        Instant.parse("2026-08-06T08:00:00Z"), ZoneOffset.UTC));
  }

  private BiCatHttpClient client(int port, int maxResponseBytes) {
    return new BiCatHttpClient(
        new BiCatHttpClient.RuntimeConfig(
            URI.create("http://127.0.0.1:" + port),
            "/integrationSearch/getAllProject",
            "/testingPhase/getAllByProjectId",
            "/integrationSearch/getStatisticsInfoByTPId",
            "/getFeatureInfoByModuleId",
            Duration.ofSeconds(1),
            Duration.ofSeconds(2),
            maxResponseBytes),
        new ObjectMapper());
  }

  private ScopeMapping mapping() {
    return new ScopeMapping(
        10L,
        "CC2026R4",
        "project-cc",
        "cat-version-r4",
        "unit-phase-r4",
        "integration-phase-r4");
  }

  private void respond(String path, int status, String body) {
    server.createContext(path, exchange -> writeResponse(exchange, status, body));
  }

  private void writeResponse(HttpExchange exchange, int status, String body) throws IOException {
    byte[] requestBody = exchange.getRequestBody().readAllBytes();
    requests.add(new CapturedRequest(
        exchange.getRequestMethod(),
        exchange.getRequestURI().getPath(),
        exchange.getRequestURI().getRawQuery(),
        new String(requestBody, StandardCharsets.UTF_8)));
    byte[] response = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json;charset=UTF-8");
    exchange.sendResponseHeaders(status, response.length);
    exchange.getResponseBody().write(response);
    exchange.close();
  }

  private String projects() {
    return """
        {"code":200,"message":"success","data":[
          {"id":"project-cc","name":"CC","note":"CAT 项目原始备注",
           "createTime":"2026-08-01T00:00:00Z","createUserId":"user-1",
           "defaultProject":true}
        ]}
        """;
  }

  private String phaseTree() {
    return """
        {"code":200,"message":"success","data":[
          {"id":"cat-version-r4","name":"CC R4","group_id":"0","children":[
            {"id":"unit-phase-r4","name":"UT","group_id":"cat-version-r4",
             "projectId":"project-cc","versionId":"cat-version-r4"},
            {"id":"integration-phase-r4","name":"IT","group_id":"cat-version-r4",
             "projectId":"project-cc","versionId":"cat-version-r4"}
          ]}
        ]}
        """;
  }

  private String statistics() {
    return """
        {"code":200,"message":"success","data":{"result":[
          {"id":"module-001","moduleName":"草图模块","name":"草图模块",
           "passFeatureCount":12,"notPassFeatureCount":3,"testPassRate":96.5}
        ],"passRate":97.2}}
        """;
  }

  private String features() {
    return """
        {"code":200,"message":"success","data":{"statisticsInfoList":[
          {"id":"feature-001","name":"拉伸凸台/基体","featureUniqueId":"feature-001",
           "featureLabel":"核心功能","testPassRate":96.0},
          {"id":"feature-002","name":"模型视图","featureUniqueId":"feature-002",
           "featureLabel":"基础功能","testPassRate":88.5}
        ],"page":null,"pageSize":null,"totalCount":2,"sumPageCount":null}}
        """;
  }

  private record CapturedRequest(String method, String path, String query, String body) {}
}
