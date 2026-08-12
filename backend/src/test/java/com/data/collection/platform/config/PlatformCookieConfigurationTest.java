package com.data.collection.platform.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.servlet.ServletContextInitializer;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockServletContext;

class PlatformCookieConfigurationTest {
  private final PlatformCookieConfiguration configuration = new PlatformCookieConfiguration();

  @Test
  void shouldDeriveStableDistinctCookieNamesFromInstanceIdentity() {
    PlatformCookieNames first = PlatformCookieNames.forInstance("qaflex-instance-a");
    PlatformCookieNames same = PlatformCookieNames.forInstance("qaflex-instance-a");
    PlatformCookieNames second = PlatformCookieNames.forInstance("qaflex-instance-b");

    assertThat(first).isEqualTo(same);
    assertThat(first.sessionCookieName()).startsWith("QAFLEX_SESSION_");
    assertThat(first.csrfCookieName()).startsWith("QAFLEX_XSRF_");
    assertThat(first.sessionCookieName()).isNotEqualTo(second.sessionCookieName());
    assertThat(first.csrfCookieName()).isNotEqualTo(second.csrfCookieName());
    assertThat(first.sessionCookieName()).doesNotContain("qaflex-instance-a");
  }

  @Test
  void shouldFallBackToApplicationAndListeningPortForDirectRuns() {
    MockEnvironment environment = new MockEnvironment()
        .withProperty("spring.application.name", "data-collection-platform-backend")
        .withProperty("server.port", "18081");

    PlatformCookieNames first = configuration.platformCookieNames(environment);
    environment.setProperty("server.port", "18082");
    PlatformCookieNames second = configuration.platformCookieNames(environment);

    assertThat(first.sessionCookieName()).isNotEqualTo(second.sessionCookieName());
    assertThat(first.csrfCookieName()).isNotEqualTo(second.csrfCookieName());
  }

  @Test
  void shouldPreferExplicitStableInstanceIdentityOverRuntimePort() {
    MockEnvironment firstEnvironment = new MockEnvironment()
        .withProperty("platform.instance.id", "stable-deployment")
        .withProperty("server.port", "18081");
    MockEnvironment secondEnvironment = new MockEnvironment()
        .withProperty("platform.instance.id", "stable-deployment")
        .withProperty("server.port", "18082");

    assertThat(configuration.platformCookieNames(firstEnvironment))
        .isEqualTo(configuration.platformCookieNames(secondEnvironment));
  }

  @Test
  void shouldApplyScopedSessionCookieNameAtServletStartup() throws Exception {
    PlatformCookieNames names = PlatformCookieNames.forInstance("qaflex-instance-a");
    MockServletContext servletContext = new MockServletContext();

    configuration.platformSessionCookieInitializer(names).onStartup(servletContext);

    assertThat(servletContext.getSessionCookieConfig().getName())
        .isEqualTo(names.sessionCookieName());
  }

  @Test
  void shouldKeepSessionsIndependentAcrossTwoRealTomcatInstances() throws Exception {
    PlatformCookieNames firstNames = PlatformCookieNames.forInstance("qaflex-instance-a");
    PlatformCookieNames secondNames = PlatformCookieNames.forInstance("qaflex-instance-b");
    CookieManager cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    HttpClient client = HttpClient.newBuilder().cookieHandler(cookieManager).build();

    try (RunningServer first = startSessionCounter(firstNames);
        RunningServer second = startSessionCounter(secondNames)) {
      assertThat(get(client, first.uri())).isEqualTo("1");
      assertThat(get(client, second.uri())).isEqualTo("1");
      assertThat(get(client, first.uri())).isEqualTo("2");
      assertThat(get(client, second.uri())).isEqualTo("2");
      assertThat(cookieManager.getCookieStore().getCookies())
          .extracting(java.net.HttpCookie::getName)
          .containsExactlyInAnyOrder(
              firstNames.sessionCookieName(),
              secondNames.sessionCookieName());
    }
  }

  private RunningServer startSessionCounter(PlatformCookieNames cookieNames) {
    TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory(0);
    ServletContextInitializer sessionCookieInitializer =
        configuration.platformSessionCookieInitializer(cookieNames);
    WebServer webServer = factory.getWebServer(
        sessionCookieInitializer,
        servletContext -> servletContext.addServlet("session-counter", new SessionCounterServlet())
            .addMapping("/session-counter"));
    webServer.start();
    return new RunningServer(
        webServer,
        URI.create("http://127.0.0.1:" + webServer.getPort() + "/session-counter"));
  }

  private static String get(HttpClient client, URI uri) throws IOException, InterruptedException {
    HttpResponse<String> response = client.send(
        HttpRequest.newBuilder(uri).GET().build(),
        HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(200);
    return response.body();
  }

  private static final class SessionCounterServlet extends HttpServlet {
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
        throws IOException {
      Integer current = (Integer) request.getSession().getAttribute("requestCount");
      int next = current == null ? 1 : current + 1;
      request.getSession().setAttribute("requestCount", next);
      response.setContentType("text/plain");
      response.getWriter().write(Integer.toString(next));
    }
  }

  private record RunningServer(WebServer webServer, URI uri) implements AutoCloseable {
    @Override
    public void close() {
      webServer.stop();
    }
  }
}
