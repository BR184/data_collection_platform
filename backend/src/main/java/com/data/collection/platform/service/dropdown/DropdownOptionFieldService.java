package com.data.collection.platform.service.dropdown;

import com.data.collection.platform.common.JsonUtils;
import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.entity.dropdown.DropdownOptionBindingRequest;
import com.data.collection.platform.entity.dropdown.DropdownOptionConfigSaveRequest;
import com.data.collection.platform.entity.dropdown.DropdownOptionFieldConfigResponse;
import com.data.collection.platform.entity.dropdown.DropdownOptionFieldSummaryResponse;
import com.data.collection.platform.entity.dropdown.DropdownOptionPreviewRequest;
import com.data.collection.platform.entity.dropdown.DropdownOptionPreviewResponse;
import com.data.collection.platform.entity.dropdown.DropdownOptionRulesPayload;
import com.data.collection.platform.service.TextQuerySupport;
import com.data.collection.platform.service.dropdown.DropdownOptionConfigRepository.FieldBinding;
import com.data.collection.platform.service.dropdown.DropdownOptionConfigRepository.StoredConfig;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 下拉框选项设置的面向管理员编排层：字段清单、配置读写、值池联想、预览与字段绑定/拆分。
 *
 * <p>判定语义在 {@link DropdownOptionFilterService}，持久化在 {@link DropdownOptionConfigRepository}；
 * 本类负责校验编排、配置推导名（由绑定关系实时计算，不存库）与首次保存即创建绑定等用例串联。
 */
@Service
public class DropdownOptionFieldService {
  private static final int DEFAULT_ACQUIRED_OPTIONS_LIMIT = 50;
  private static final int MAX_ACQUIRED_OPTIONS_LIMIT = 200;

  private final DropdownOptionFieldRegistry fieldRegistry;
  private final DropdownOptionConfigRepository configRepository;
  private final DropdownOptionRuleSupport ruleSupport;
  private final DropdownOptionFilterService filterService;
  private final JsonUtils jsonUtils;

  public DropdownOptionFieldService(
      DropdownOptionFieldRegistry fieldRegistry,
      DropdownOptionConfigRepository configRepository,
      DropdownOptionRuleSupport ruleSupport,
      DropdownOptionFilterService filterService,
      JsonUtils jsonUtils) {
    this.fieldRegistry = fieldRegistry;
    this.configRepository = configRepository;
    this.ruleSupport = ruleSupport;
    this.filterService = filterService;
    this.jsonUtils = jsonUtils;
  }

  /**
   * 字段清单：全部注册字段及其绑定状态与配置推导名。
   *
   * @return 按注册顺序排列的字段摘要
   */
  public List<DropdownOptionFieldSummaryResponse> listFields() {
    Map<Long, List<String>> consumersByConfig = consumersByConfig();
    Map<String, Long> configIdByField = new HashMap<>();
    for (FieldBinding binding : configRepository.loadAllBindings()) {
      configIdByField.put(binding.fieldKey(), binding.configId());
    }
    List<DropdownOptionFieldSummaryResponse> result = new ArrayList<>();
    for (DropdownOptionFieldRegistry.DropdownOptionFieldDefinition definition : fieldRegistry.fields()) {
      Long configId = configIdByField.get(definition.fieldKey());
      boolean configured = configId != null && configHasContent(configId);
      result.add(
          new DropdownOptionFieldSummaryResponse(
              definition.fieldKey(),
              definition.displayName(),
              configured,
              configId,
              deriveConfigLabel(configId, consumersByConfig)));
    }
    return List.copyOf(result);
  }

  /**
   * 单字段完整配置视图。
   *
   * <p>未绑定字段返回空配置草稿（configId=null、version=0），首次保存即创建配置并绑定；
   * 已绑定字段返回配置内容、推导名与使用者清单（多字段共用时页面显著提示）。
   *
   * @param fieldKey 注册字段键
   * @return 配置视图
   */
  public DropdownOptionFieldConfigResponse getConfig(String fieldKey) {
    DropdownOptionFieldRegistry.DropdownOptionFieldDefinition definition =
        fieldRegistry.requireField(fieldKey);
    Optional<Long> bound = configRepository.findBoundConfigId(fieldKey);
    if (bound.isEmpty()) {
      return new DropdownOptionFieldConfigResponse(
          fieldKey,
          definition.displayName(),
          null,
          definition.displayName(),
          DropdownOptionRulesPayload.empty(),
          List.of(),
          0L,
          List.of(definition.displayName()));
    }
    long configId = bound.get();
    StoredConfig config =
        configRepository
            .loadConfig(configId)
            .orElseThrow(
                () -> new IllegalStateException("字段绑定指向的配置不存在：" + fieldKey));
    return new DropdownOptionFieldConfigResponse(
        fieldKey,
        definition.displayName(),
        config.id(),
        deriveConfigLabel(configId, consumersByConfig()),
        config.rules(),
        config.manualOptions(),
        config.version(),
        consumerDisplayNames(configId));
  }

  /**
   * 整体保存配置：未绑定字段首次保存即创建配置并绑定；已绑定字段走乐观锁更新。
   *
   * @param fieldKey 注册字段键
   * @param request 双套规则、手动选项与乐观锁版本号
   * @param operator 操作者用户名
   * @return 保存后的配置视图（含新版本号）
   * @throws BizException 规则非法或版本冲突
   */
  @Transactional
  public DropdownOptionFieldConfigResponse saveConfig(
      String fieldKey, DropdownOptionConfigSaveRequest request, String operator) {
    fieldRegistry.requireField(fieldKey);
    DropdownOptionRulesPayload rules = ruleSupport.normalizeAndValidate(request.rules());
    List<String> manualOptions = ruleSupport.normalizeManualOptions(request.manualOptions());
    String rulesJson = jsonUtils.toJson(rules);
    String manualOptionsJson = jsonUtils.toJson(manualOptions);
    Optional<Long> bound = configRepository.findBoundConfigId(fieldKey);
    if (bound.isEmpty()) {
      long configId = configRepository.insertConfig(rulesJson, manualOptionsJson, operator);
      configRepository.bindField(fieldKey, configId, operator);
    } else {
      long expectedVersion = request.version() == null ? 0L : request.version();
      configRepository.updateConfig(bound.get(), rulesJson, manualOptionsJson, expectedVersion, operator);
    }
    return getConfig(fieldKey);
  }

  /**
   * 字段绑定/拆分：NEW=新建空白配置并绑定，COPY=复制当前绑定配置为新配置并绑定（拆分默认路径），
   * CONFIG=绑定到既有配置（共用入口）。原配置不删除，继续服务其余绑定字段。
   *
   * @param fieldKey 注册字段键
   * @param request 绑定目标
   * @param operator 操作者用户名
   * @return 绑定后的配置视图
   * @throws BizException 目标非法、字段未绑定无法复制或目标配置不存在
   */
  @Transactional
  public DropdownOptionFieldConfigResponse bindField(
      String fieldKey, DropdownOptionBindingRequest request, String operator) {
    fieldRegistry.requireField(fieldKey);
    if (request == null || request.target() == null) {
      throw new BizException("缺少绑定目标类型");
    }
    switch (request.target()) {
      case NEW -> {
        long configId =
            configRepository.insertConfig(
                jsonUtils.toJson(DropdownOptionRulesPayload.empty()), jsonUtils.toJson(List.of()), operator);
        configRepository.bindField(fieldKey, configId, operator);
      }
      case COPY -> {
        Optional<Long> current = configRepository.findBoundConfigId(fieldKey);
        if (current.isEmpty()) {
          throw new BizException("当前字段尚未绑定配置，无法复制拆分");
        }
        long configId = configRepository.copyConfig(current.get(), operator);
        configRepository.bindField(fieldKey, configId, operator);
      }
      case CONFIG -> {
        if (request.configId() == null) {
          throw new BizException("缺少目标配置 ID");
        }
        if (!configRepository.configExists(request.configId())) {
          throw new BizException("目标配置不存在");
        }
        configRepository.bindField(fieldKey, request.configId(), operator);
      }
      default -> throw new BizException("不支持的绑定目标类型：" + request.target());
    }
    return getConfig(fieldKey);
  }

  /**
   * 自动获取值池关键词联想（手动添加输入框候选来源）。
   *
   * @param fieldKey 注册字段键
   * @param keyword 关键词（大小写不敏感的包含匹配；空则返回值池前若干项）
   * @param limit 返回条数上限（1~200，默认 50）
   * @return 值池中匹配的选项（保持值池顺序）
   */
  public List<String> acquiredOptions(String fieldKey, String keyword, Integer limit) {
    DropdownOptionFieldRegistry.DropdownOptionFieldDefinition definition =
        fieldRegistry.requireField(fieldKey);
    String normalizedKeyword = TextQuerySupport.trimToNull(keyword);
    int safeLimit =
        limit == null
            ? DEFAULT_ACQUIRED_OPTIONS_LIMIT
            : Math.max(1, Math.min(limit, MAX_ACQUIRED_OPTIONS_LIMIT));
    return definition.acquiredOptions().get().stream()
        .filter(value -> normalizedKeyword == null
            || value.toLowerCase(Locale.ROOT).contains(normalizedKeyword.toLowerCase(Locale.ROOT)))
        .limit(safeLimit)
        .toList();
  }

  /**
   * 草稿预览：规则校验归一化后按判定管道求值，不经保存。
   *
   * @param fieldKey 注册字段键
   * @param request 草稿规则与手动选项
   * @return 最终显示列表
   * @throws BizException 规则非法
   */
  public DropdownOptionPreviewResponse preview(String fieldKey, DropdownOptionPreviewRequest request) {
    fieldRegistry.requireField(fieldKey);
    DropdownOptionRulesPayload rules =
        ruleSupport.normalizeAndValidate(request == null ? null : request.rules());
    List<String> manualOptions =
        ruleSupport.normalizeManualOptions(request == null ? null : request.manualOptions());
    return new DropdownOptionPreviewResponse(
        filterService.previewOptions(fieldKey, rules, manualOptions));
  }

  private Map<Long, List<String>> consumersByConfig() {
    Map<Long, List<String>> consumers = new LinkedHashMap<>();
    for (FieldBinding binding : configRepository.loadAllBindings()) {
      consumers.computeIfAbsent(binding.configId(), key -> new ArrayList<>()).add(binding.fieldKey());
    }
    return consumers;
  }

  private List<String> consumerDisplayNames(long configId) {
    return consumersByConfig().getOrDefault(configId, List.of()).stream()
        .map(fieldKey -> fieldRegistry.find(fieldKey)
            .map(DropdownOptionFieldRegistry.DropdownOptionFieldDefinition::displayName)
            .orElse(fieldKey))
        .toList();
  }

  /** 配置推导名：单字段绑定=该字段位置路径；多字段共用=全部位置路径 + 共用提示；未绑定=null。 */
  private String deriveConfigLabel(Long configId, Map<Long, List<String>> consumersByConfig) {
    if (configId == null) {
      return null;
    }
    List<String> consumers =
        consumersByConfig.getOrDefault(configId, List.of()).stream()
            .map(fieldKey -> fieldRegistry.find(fieldKey)
                .map(DropdownOptionFieldRegistry.DropdownOptionFieldDefinition::displayName)
                .orElse(fieldKey))
            .toList();
    if (consumers.isEmpty()) {
      return null;
    }
    if (consumers.size() == 1) {
      return consumers.get(0);
    }
    return String.join("、", consumers) + "（" + consumers.size() + " 个字段共用）";
  }

  private boolean configHasContent(long configId) {
    return configRepository
        .loadConfig(configId)
        .map(config -> !config.rules().acquiredRules().isEmpty()
            || !config.rules().manualRules().isEmpty()
            || !config.manualOptions().isEmpty())
        .orElse(false);
  }
}
