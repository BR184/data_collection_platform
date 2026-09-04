package com.data.collection.platform.service.dropdown;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.service.ReviewDataMirrorOptionRepository;
import com.data.collection.platform.service.TextQuerySupport;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * 下拉框可选字段的唯一注册目录。
 *
 * <p>新增可配置下拉字段必须在此显式登记（字段键、位置路径显示名、自动获取值池供应者），
 * 未登记字段不进入设置页面、也不接入判定管道；登记后设置页面与字段清单自动出现该项。
 */
@Component
public class DropdownOptionFieldRegistry {
  /** 评审数据-新增/编辑评审表单的项目名称下拉。 */
  public static final String REVIEW_FORM_PROJECT_NAME_FIELD = "review-data.form.project-name";

  private final Map<String, DropdownOptionFieldDefinition> fieldsByKey;

  /**
   * @param fieldKey 注册字段键（绑定表主键，全平台唯一）
   * @param displayName 字段位置路径显示名（单字段绑定时的配置推导名）
   * @param acquiredOptions 自动获取值池供应者（配置判定与手动添加联想共用同一数据源）
   */
  public record DropdownOptionFieldDefinition(
      String fieldKey, String displayName, Supplier<List<String>> acquiredOptions) {}

  public DropdownOptionFieldRegistry(ReviewDataMirrorOptionRepository mirrorOptionRepository) {
    List<DropdownOptionFieldDefinition> fields =
        List.of(
            new DropdownOptionFieldDefinition(
                REVIEW_FORM_PROJECT_NAME_FIELD,
                "评审数据-新增评审-项目名称",
                mirrorOptionRepository::loadLabelProjectNames));
    this.fieldsByKey =
        fields.stream()
            .collect(
                Collectors.toUnmodifiableMap(
                    DropdownOptionFieldDefinition::fieldKey, Function.identity()));
  }

  /**
   * 全部已注册字段（设置页面字段清单的数据源）。
   *
   * @return 注册字段定义列表
   */
  public List<DropdownOptionFieldDefinition> fields() {
    return List.copyOf(fieldsByKey.values());
  }

  /**
   * 按字段键查找注册字段。
   *
   * @param fieldKey 注册字段键
   * @return 字段定义；未注册时为空
   */
  public Optional<DropdownOptionFieldDefinition> find(String fieldKey) {
    String normalized = TextQuerySupport.trimToNull(fieldKey);
    return normalized == null
        ? Optional.empty()
        : Optional.ofNullable(fieldsByKey.get(normalized));
  }

  /**
   * 按字段键取注册字段，未注册即拒绝。
   *
   * @param fieldKey 注册字段键
   * @return 字段定义
   * @throws BizException 字段未注册
   */
  public DropdownOptionFieldDefinition requireField(String fieldKey) {
    return find(fieldKey)
        .orElseThrow(() -> new BizException("未注册的下拉字段：" + fieldKey));
  }
}
