package com.data.collection.platform.bi.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.data.collection.platform.bi.infrastructure.BiCatMirrorModels.Config;
import com.data.collection.platform.bi.infrastructure.BiCatMirrorModels.ScopeMapping;
import com.data.collection.platform.bi.infrastructure.BiCatMirrorModels.TestSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class BiCatMirrorSyncServiceTest {
  private static final Instant NOW = Instant.parse("2026-08-06T08:00:00Z");

  private final BiCatMirrorRepository repository = mock(BiCatMirrorRepository.class);
  private HttpServer server;

  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/integrationSearch/getAllProject", exchange ->
        respond(exchange, 200, projects()));
    server.createContext("/testingPhase/getAllByProjectId", exchange ->
        respond(exchange, 200, phaseTree()));
    server.createContext("/integrationSearch/getStatisticsInfoByTPId", exchange -> {
      if (exchange.getRequestURI().getRawQuery().contains("unit-phase-r4")) {
        respond(exchange, 200, statistics());
      } else {
        respond(exchange, 500, "{\"code\":500,\"message\":\"stage unavailable\"}");
      }
    });
    server.createContext("/getFeatureInfoByModuleId", exchange ->
        respond(exchange, 200, features()));
    server.start();
  }

  @AfterEach
  void stopServer() {
    server.stop(0);
  }

  @Test
  void execute_oneStageFails_publishesSuccessfulStageAndKeepsFailedStagePointerUntouched() {
    UUID runId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    when(repository.loadConfig()).thenReturn(config());
    when(repository.loadMappings()).thenReturn(List.of(mapping()));
    var service = new BiCatMirrorSyncService(
        repository,
        new BiCatProperties(),
        new ObjectMapper(),
        Clock.fixed(NOW, ZoneOffset.UTC));

    service.execute(runId, "FULL");

    ArgumentCaptor<TestSnapshot> published = ArgumentCaptor.forClass(TestSnapshot.class);
    verify(repository, times(1)).publishTestSnapshot(eq(runId), published.capture(), eq(NOW));
    assertThat(published.getValue().testStage()).isEqualTo("UNIT_TEST");
    verify(repository).finishRun(
        eq(runId),
        eq("FULL"),
        eq("PARTIAL_SUCCESS"),
        eq(1),
        eq(1),
        argThat(message -> message.contains("失败并继续保留旧快照")),
        eq(NOW),
        eq(20));
    verify(repository).pruneSnapshots(10);
    verify(repository, times(3)).heartbeat(eq(runId), eq(NOW), any());
  }

  private Config config() {
    return new Config(
        true,
        "http://127.0.0.1:" + server.getAddress().getPort(),
        true,
        20,
        true,
        LocalTime.of(2, 0));
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

  private void respond(HttpExchange exchange, int status, String body) throws IOException {
    exchange.getRequestBody().readAllBytes();
    byte[] response = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json;charset=UTF-8");
    exchange.sendResponseHeaders(status, response.length);
    exchange.getResponseBody().write(response);
    exchange.close();
  }

  private String projects() {
    return """
        {"code":200,"message":"success","data":[
          {"id":"project-cc","name":"CC","defaultProject":true}
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
           "featureLabel":"核心功能","testPassRate":96.0}
        ],"page":1,"pageSize":20,"totalCount":1,"sumPageCount":1}}
        """;
  }
}
