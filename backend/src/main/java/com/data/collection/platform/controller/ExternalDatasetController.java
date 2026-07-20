package com.data.collection.platform.controller;

import com.data.collection.platform.common.response.ApiResponse;
import com.data.collection.platform.common.response.ResultCode;
import com.data.collection.platform.entity.external.ExternalDatasetCatalogResponse;
import com.data.collection.platform.entity.external.ExternalDatasetResponse;
import com.data.collection.platform.security.ExternalApiAuthenticationToken;
import com.data.collection.platform.security.ExternalApiClientPrincipal;
import com.data.collection.platform.service.external.ExternalDatasetProvider;
import com.data.collection.platform.service.external.ExternalDatasetRegistry;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/external/v1/datasets")
public class ExternalDatasetController {
  private static final String API_VERSION = "v1";

  private final ExternalDatasetRegistry registry;

  public ExternalDatasetController(ExternalDatasetRegistry registry) {
    this.registry = registry;
  }

  @GetMapping
  public ApiResponse<ExternalDatasetCatalogResponse> catalog() {
    ExternalApiClientPrincipal client = currentClient();
    return ApiResponse.success(new ExternalDatasetCatalogResponse(
        API_VERSION, now(), registry.descriptors(client)));
  }

  @GetMapping("/{datasetKey}")
  public ResponseEntity<ApiResponse<ExternalDatasetResponse<?>>> read(
      @PathVariable String datasetKey,
      @RequestParam Map<String, String> parameters) {
    ExternalApiClientPrincipal client = currentClient();
    ExternalDatasetProvider<?> provider = registry.provider(datasetKey, client);
    if (provider == null) {
      ResultCode code = registry.exists(datasetKey) ? ResultCode.FORBIDDEN : ResultCode.NOT_FOUND;
      int status = registry.exists(datasetKey) ? HttpStatus.FORBIDDEN.value() : HttpStatus.NOT_FOUND.value();
      return ResponseEntity.status(status).body(ApiResponse.fail(code, code == ResultCode.FORBIDDEN ? "调用方无权访问该数据集" : "数据集不存在"));
    }
    try {
      ExternalDatasetResponse<?> response = readProvider(provider, parameters);
      return ResponseEntity.ok(ApiResponse.success(response));
    } catch (IllegalArgumentException exception) {
      return ResponseEntity.badRequest()
          .body(ApiResponse.fail(ResultCode.BAD_REQUEST, exception.getMessage()));
    }
  }

  private ExternalDatasetResponse<?> readProvider(
      ExternalDatasetProvider<?> provider, Map<String, String> parameters) {
    return new ExternalDatasetResponse<>(
        provider.datasetKey(),
        provider.descriptor().schemaVersion(),
        now(),
        provider.load(parameters));
  }

  private ExternalApiClientPrincipal currentClient() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication instanceof ExternalApiAuthenticationToken token) {
      return token.getPrincipal();
    }
    throw new IllegalStateException("外部接口缺少机器调用认证");
  }

  private OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC);
  }
}
