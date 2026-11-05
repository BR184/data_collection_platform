package com.data.collection.platform.domain.form;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * 三模板冻结规格的启动加载与静态校验。
 *
 * <p>规格资源随代码发布；任一字段、词典或物理绑定不合法时应用拒绝启动，避免运行期用错误结构建表。
 */
@Component
public class FormTemplateSpecCatalog {

  public static final String CODE_REVIEW = "code_review";
  public static final String ISSUE_RESPONSE = "issue_response";
  public static final String ISSUE_REPLY = "issue_reply";
  public static final List<String> TEMPLATE_KEYS =
      List.of(CODE_REVIEW, ISSUE_RESPONSE, ISSUE_REPLY);

  private final Map<String, FormTemplateSpec> specs;
  private final Map<String, String> rawSpecs;

  public FormTemplateSpecCatalog(ObjectMapper objectMapper) {
    this(objectMapper, new FormSpecValidator(objectMapper));
  }

  @org.springframework.beans.factory.annotation.Autowired
  public FormTemplateSpecCatalog(ObjectMapper objectMapper, FormSpecValidator validator) {
    Map<String, FormTemplateSpec> loaded = new LinkedHashMap<>();
    Map<String, String> loadedRaw = new LinkedHashMap<>();
    for (String templateKey : TEMPLATE_KEYS) {
      LoadedSpec loadedSpec = load(objectMapper, templateKey);
      validate(validator, loadedSpec.spec());
      loaded.put(templateKey, loadedSpec.spec());
      loadedRaw.put(templateKey, loadedSpec.rawJson());
    }
    this.specs = Map.copyOf(loaded);
    this.rawSpecs = Map.copyOf(loadedRaw);
  }

  public FormTemplateSpec require(String templateKey) {
    FormTemplateSpec spec = specs.get(templateKey);
    if (spec == null) {
      throw new IllegalArgumentException("Unknown form template: " + templateKey);
    }
    return spec;
  }

  /** 返回资源原文，作为版本 field_spec 的冻结内容。 */
  public String rawJson(String templateKey) {
    String raw = rawSpecs.get(templateKey);
    if (raw == null) {
      throw new IllegalArgumentException("Unknown form template: " + templateKey);
    }
    return raw;
  }

  public Collection<FormTemplateSpec> all() {
    return specs.values();
  }

  private LoadedSpec load(ObjectMapper objectMapper, String templateKey) {
    String location = "form-templates/" + templateKey + ".json";
    ClassPathResource resource = new ClassPathResource(location);
    try (InputStream input = resource.getInputStream()) {
      String rawJson = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
      FormTemplateSpec spec = objectMapper.readValue(rawJson, FormTemplateSpec.class);
      if (!templateKey.equals(spec.templateKey())) {
        throw new IllegalStateException(
            location + " declares template_key " + spec.templateKey() + " instead of " + templateKey);
      }
      return new LoadedSpec(spec, rawJson);
    } catch (IOException error) {
      throw new IllegalStateException("Failed to load form template spec: " + location, error);
    }
  }

  private record LoadedSpec(FormTemplateSpec spec, String rawJson) {}

  private void validate(FormSpecValidator validator, FormTemplateSpec spec) {
    try {
      validator.validate(spec);
    } catch (IllegalArgumentException error) {
      throw invalid(spec, error.getMessage());
    }
  }

  private IllegalStateException invalid(FormTemplateSpec spec, String message) {
    return new IllegalStateException("Invalid form template spec " + spec.templateKey() + ": " + message);
  }
}
