package com.data.collection.platform.golden;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 黄金基线覆盖护栏：反射枚举全部 Controller 端点，断言每个端点都登记在端点目录中。
 *
 * <p>本测试属于默认快速套件（不依赖容器、秒级完成）：任何新增或改名端点未同步登记
 * {@code golden-baseline/endpoint-catalog.yml} 时，日常 {@code mvn test} 立即失败，
 * 从机制上禁止"没有记录的接口"这一高风险项。EXCLUDED 条目同样必须显式登记并给出原因。</p>
 */
class GoldenBaselineCoverageGuardTest {

  private static final String CONTROLLER_PACKAGE = "com.data.collection.platform.controller";

  /** 验证代码中每个 Controller 端点都登记在目录中，且目录不含幽灵条目。 */
  @Test
  void test_every_controller_endpoint_is_registered_in_golden_catalog() {
    Set<String> codeEndpoints = scanControllerEndpoints();
    GoldenEndpointCatalog catalog = GoldenEndpointCatalog.load();
    Set<String> catalogKeys = new HashSet<>(catalog.entries().keySet());

    List<String> unregistered = new ArrayList<>(codeEndpoints);
    unregistered.removeAll(catalogKeys);
    List<String> ghosts = new ArrayList<>(catalogKeys);
    ghosts.removeAll(codeEndpoints);

    assertThat(unregistered)
        .as("以下 Controller 端点未登记黄金基线目录（EXCLUDED 也必须显式登记 + 原因）")
        .isEmpty();
    assertThat(ghosts)
        .as("目录中的以下条目在 Controller 代码中已不存在（改名/删除后须同步更新目录）")
        .isEmpty();
  }

  /** 验证目录条目自身结构合法：分类、原因、用例、掩码等字段满足各类别约束。 */
  @Test
  void test_catalog_entries_are_well_formed() {
    GoldenEndpointCatalog catalog = GoldenEndpointCatalog.load();
    assertThat(catalog.entries()).isNotEmpty();

    List<String> violations = new ArrayList<>();
    Set<String> caseIdsSeen = new HashSet<>();
    for (GoldenEndpointCatalog.EndpointEntry entry : catalog.entries().values()) {
      String key = GoldenEndpointCatalog.key(entry.method(), entry.path());
      switch (entry.category()) {
        case EXCLUDED -> {
          if (entry.excludeReason() == null || entry.excludeReason().isBlank()
              || entry.excludeReason().contains("TODO")) {
            violations.add(key + ": EXCLUDED 必须给出具体排除原因");
          }
          if (!entry.cases().isEmpty()) {
            violations.add(key + ": EXCLUDED 不应声明用例");
          }
        }
        case READ, OPTIONS, EXPORT -> {
          if (entry.cases().isEmpty()) {
            violations.add(key + ": " + entry.category() + " 必须至少声明一个 case");
          }
          if (entry.category() == GoldenEndpointCatalog.Category.EXPORT
              && entry.response() != GoldenEndpointCatalog.ResponseKind.EXCEL) {
            violations.add(key + ": EXPORT 必须 response: excel");
          }
          if (entry.category() != GoldenEndpointCatalog.Category.EXPORT
              && entry.response() != GoldenEndpointCatalog.ResponseKind.JSON) {
            violations.add(key + ": " + entry.category() + " 必须 response: json");
          }
        }
        case WRITE -> {
          if (entry.writeCases().isEmpty()) {
            violations.add(key + ": WRITE 必须至少声明一个 write-case（无法快照的行为须改判 EXCLUDED）");
          }
          if (!entry.cases().isEmpty()) {
            violations.add(key + ": WRITE 不应声明 read cases");
          }
        }
        default -> violations.add(key + ": 未知分类");
      }
      for (GoldenEndpointCatalog.ReadCase readCase : entry.cases()) {
        if (readCase.id() == null || readCase.id().isBlank()) {
          violations.add(key + ": case id 不能为空");
        } else if (!caseIdsSeen.add(key + "#" + readCase.id())) {
          violations.add(key + ": case id 重复 " + readCase.id());
        }
        String query = readCase.query() == null ? "" : readCase.query();
        if (query.startsWith("?") || query.startsWith("&") || query.endsWith("&")) {
          violations.add(key + ": case " + readCase.id() + " 查询串格式非法: " + query);
        }
      }
      for (GoldenEndpointCatalog.WriteCase writeCase : entry.writeCases()) {
        if (writeCase.id() == null || writeCase.id().isBlank()) {
          violations.add(key + ": write-case id 不能为空");
        } else if (!caseIdsSeen.add(key + "#" + writeCase.id())) {
          violations.add(key + ": write-case id 重复 " + writeCase.id());
        }
        if (writeCase.affectedTables().isEmpty()) {
          violations.add(key + ": write-case " + writeCase.id() + " 必须声明受影响表");
        }
        for (GoldenEndpointCatalog.AffectedTable table : writeCase.affectedTables()) {
          if (table.restore() == GoldenEndpointCatalog.RestoreMode.DELETE
              && table.scope() == null) {
            violations.add(key + ": write-case " + writeCase.id()
                + " 表 " + table.table() + " DELETE 还原必须声明 scope");
          }
        }
      }
    }
    assertThat(violations).as("目录结构违规").isEmpty();
  }

  /**
   * 扫描 controller 包下全部 {@code @RestController} 的方法级映射，返回
   * {@code METHOD path} 键集合（路径模板保持 {var} 原样，与目录逐字比对）。
   *
   * @return 代码中实际存在的端点键集合
   */
  private static Set<String> scanControllerEndpoints() {
    ClassPathScanningCandidateComponentProvider scanner =
        new ClassPathScanningCandidateComponentProvider(false);
    scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
    Set<String> endpoints = new HashSet<>();
    for (BeanDefinition definition
        : scanner.findCandidateComponents(CONTROLLER_PACKAGE)) {
      Class<?> controllerType = loadControllerType(definition);
      String base = classBasePath(controllerType);
      for (Method method : controllerType.getDeclaredMethods()) {
        collectMethodEndpoints(method, base, endpoints);
      }
    }
    assertThat(endpoints)
        .as("controller 包未扫描到任何端点，扫描逻辑失效")
        .isNotEmpty();
    return endpoints;
  }

  private static Class<?> loadControllerType(BeanDefinition definition) {
    try {
      return Class.forName(definition.getBeanClassName());
    } catch (ClassNotFoundException e) {
      throw new IllegalStateException("无法加载 Controller 类型: " + definition.getBeanClassName(), e);
    }
  }

  private static String classBasePath(Class<?> controllerType) {
    RequestMapping mapping = AnnotationUtils.findAnnotation(controllerType, RequestMapping.class);
    if (mapping == null) {
      return "";
    }
    if (mapping.value().length > 1 || mapping.path().length > 1) {
      throw new IllegalStateException(
          "黄金基线目录要求 Controller 基路径唯一: " + controllerType.getName());
    }
    return mapping.value().length == 0 ? "" : mapping.value()[0];
  }

  private static void collectMethodEndpoints(
      Method method, String base, Set<String> endpoints) {
    GetMapping get = AnnotationUtils.findAnnotation(method, GetMapping.class);
    PostMapping post = AnnotationUtils.findAnnotation(method, PostMapping.class);
    PutMapping put = AnnotationUtils.findAnnotation(method, PutMapping.class);
    DeleteMapping delete = AnnotationUtils.findAnnotation(method, DeleteMapping.class);
    PatchMapping patch = AnnotationUtils.findAnnotation(method, PatchMapping.class);
    if (get != null) {
      register(endpoints, "GET", base, get.value());
    }
    if (post != null) {
      register(endpoints, "POST", base, post.value());
    }
    if (put != null) {
      register(endpoints, "PUT", base, put.value());
    }
    if (delete != null) {
      register(endpoints, "DELETE", base, delete.value());
    }
    if (patch != null) {
      register(endpoints, "PATCH", base, patch.value());
    }
    if (get == null && post == null && put == null && delete == null && patch == null
        && AnnotationUtils.findAnnotation(method, RequestMapping.class) != null) {
      throw new IllegalStateException(
          "黄金基线目录不支持方法级 @RequestMapping（请改用具体方法注解）: "
              + method.getDeclaringClass().getName() + "#" + method.getName());
    }
  }

  private static void register(
      Set<String> endpoints, String verb, String base, String[] values) {
    if (values.length > 1) {
      throw new IllegalStateException("黄金基线目录要求端点路径唯一: " + verb + " " + base);
    }
    String child = values.length == 0 ? "" : values[0];
    endpoints.add(GoldenEndpointCatalog.key(verb, joinPath(base, child)));
  }

  private static String joinPath(String base, String child) {
    if (base.isEmpty()) {
      return child.isEmpty() ? "/" : child;
    }
    if (child.isEmpty()) {
      return base;
    }
    return base + "/" + child.substring(child.startsWith("/") ? 1 : 0);
  }
}
