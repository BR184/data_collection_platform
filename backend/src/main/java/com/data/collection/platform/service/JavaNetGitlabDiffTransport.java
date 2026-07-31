package com.data.collection.platform.service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
class JavaNetGitlabDiffTransport implements GitlabDiffTransport {
  private final HttpClient httpClient = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(10))
      .version(HttpClient.Version.HTTP_1_1)
      .build();

  @Override
  public Response get(URI uri, String token, Duration timeout) {
    HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(timeout).GET();
    if (StringUtils.hasText(token)) {
      request.header("PRIVATE-TOKEN", token.trim());
    }
    try {
      HttpResponse<String> response = httpClient.send(
          request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      return new Response(response.statusCode(), response.body());
    } catch (IOException error) {
      throw new GitlabDiffFetchException("GitLab diff 请求失败: " + error.getMessage(), true);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new GitlabDiffFetchException("GitLab diff 请求被中断", true);
    }
  }
}
