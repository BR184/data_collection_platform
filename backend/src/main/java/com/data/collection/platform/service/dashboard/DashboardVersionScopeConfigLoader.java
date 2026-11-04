package com.data.collection.platform.service.dashboard;

import com.data.collection.platform.service.IssueScopeCatalogService;
import com.data.collection.platform.service.IssueScopeDimension;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 加载并校验看板版本分组资源。
 *
 * <p>资源在应用启动时读取一次并冻结；任何重复、交叉或跨项目配置都会直接阻止启动，
 * 不允许运行时静默跳过。
 */
@Component
public class DashboardVersionScopeConfigLoader {
  static final String RESOURCE_PATH = "dashboard-version-scopes.json";
  static final int SUPPORTED_SCHEMA_VERSION = 1;

  private final DashboardVersionScopeConfig config;
  private final String resourceHash;

  @Autowired
  public DashboardVersionScopeConfigLoader(ObjectMapper objectMapper) {
    this(objectMapper, readResource());
  }

  DashboardVersionScopeConfigLoader(ObjectMapper objectMapper, byte[] content) {
    this.resourceHash = sha256(content);
    try {
      this.config = objectMapper.readValue(content, DashboardVersionScopeConfig.class);
    } catch (IOException error) {
      throw new IllegalStateException("看板版本分组资源无法解析：" + RESOURCE_PATH, error);
    }
    validate(config);
  }

  /** 返回已冻结的分组配置。 */
  public DashboardVersionScopeConfig config() {
    return config;
  }

  /** 返回分组资源内容的 SHA-256，用于来源版本失效。 */
  public String resourceHash() {
    return resourceHash;
  }

  private static byte[] readResource() {
    ClassPathResource resource = new ClassPathResource(RESOURCE_PATH);
    try (InputStream input = resource.getInputStream()) {
      return input.readAllBytes();
    } catch (IOException error) {
      throw new IllegalStateException("缺少看板版本分组资源：" + RESOURCE_PATH, error);
    }
  }

  private void validate(DashboardVersionScopeConfig candidate) {
    if (candidate == null) {
      throw new IllegalStateException("看板版本分组配置不能为空");
    }
    if (candidate.schemaVersion() != SUPPORTED_SCHEMA_VERSION) {
      throw new IllegalStateException(
          "不支持的看板版本分组 schemaVersion："
              + candidate.schemaVersion()
              + "，当前支持："
              + SUPPORTED_SCHEMA_VERSION);
    }
    Set<String> keys = new HashSet<>();
    Set<String> displayNames = new HashSet<>();
    Set<String> anchors = new HashSet<>();
    Set<String> memberKeys = new HashSet<>();
    for (DashboardVersionScopeConfig.ScopeDefinition scope : candidate.scopes()) {
      String key = requireText(scope.key(), "分组 key");
      String displayName = requireText(scope.displayName(), "分组显示名");
      if (!keys.add(key.toLowerCase(Locale.ROOT))) {
        throw new IllegalStateException("看板版本分组 key 重复：" + key);
      }
      if (!displayNames.add(displayName.toLowerCase(Locale.ROOT))) {
        throw new IllegalStateException("看板版本分组显示名重复：" + displayName);
      }
      if (scope.projectId() != IssueScopeCatalogService.CROWN_CAD_PROJECT_ID) {
        throw new IllegalStateException(
            "看板版本分组项目不属于已核实的平台项目 "
                + IssueScopeCatalogService.CROWN_CAD_PROJECT_ID
                + "："
                + scope.projectId());
      }
      IssueScopeDimension.parse(scope.dimension());
      String anchor = requireText(scope.anchorBusinessKey(), "分组锚点业务键");
      if (!anchors.add(anchor.toLowerCase(Locale.ROOT))) {
        throw new IllegalStateException("看板版本分组锚点重复：" + anchor);
      }
      if (scope.memberBusinessKeys().isEmpty()) {
        throw new IllegalStateException("看板版本分组缺少成员：" + key);
      }
      boolean anchorRegistered = false;
      for (String member : scope.memberBusinessKeys()) {
        String normalized = requireText(member, "分组成员业务键");
        if (!memberKeys.add(normalized.toLowerCase(Locale.ROOT))) {
          throw new IllegalStateException(
              "看板版本成员被多个分组复用或重复登记：" + normalized);
        }
        anchorRegistered |= normalized.equalsIgnoreCase(anchor);
      }
      if (!anchorRegistered) {
        throw new IllegalStateException("看板版本分组锚点不属于成员：" + key);
      }
      scope.reviewProjectNames().forEach(value -> requireText(value, "评审来源项目名"));
      scope.integrationTestPhases().forEach(value -> requireText(value, "集成测试阶段值"));
    }
  }

  private String requireText(String value, String label) {
    String normalized = StringUtils.hasText(value) ? value.trim() : null;
    if (normalized == null) {
      throw new IllegalStateException("看板版本分组缺少" + label);
    }
    return normalized;
  }

  private static String sha256(byte[] content) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(content));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("当前 JDK 不支持 SHA-256", error);
    }
  }
}
