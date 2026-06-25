package com.data.collection.platform.service.labelgroup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.entity.labelgroup.LabelGroupExpansionResponse;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class LabelGroupExpansionServiceTest {

  @Test
  void shouldExpandStaticGroupMembersByValueType() {
    LabelGroupExpansionService service =
        service(group("STRING", true, member("草图"), member("工程图")));

    LabelGroupExpansionResponse response =
        service.expand(1L, "STRING", "moduleName", "review-data-home", "cc");

    assertThat(response.valueType()).isEqualTo("STRING");
    assertThat(response.values()).containsExactly("草图", "工程图");
  }

  @Test
  void shouldRejectDisabledGroup() {
    LabelGroupExpansionService service = service(group("STRING", false, member("草图")));

    assertThatThrownBy(() -> service.expand(1L, "STRING", "moduleName", null, null))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("已禁用");
  }

  @Test
  void shouldRejectEmptyGroup() {
    LabelGroupExpansionService service = service(group("STRING", true));

    assertThatThrownBy(() -> service.expand(1L, "STRING", "moduleName", null, null))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("没有可用成员");
  }

  @Test
  void shouldRejectValueTypeMismatch() {
    LabelGroupExpansionService service = service(group("STRING", true, member("草图")));

    assertThatThrownBy(() -> service.expand(1L, "NUMBER", "moduleName", null, null))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("字段值类型与标签组值类型不兼容");
  }

  @Test
  void shouldExpandClosureStatusEquivalentValuesOnlyForClosureStatusField() {
    LabelGroupExpansionService service = service(group("STRING", true, member("需求如此")));

    LabelGroupExpansionResponse response =
        service.expand(1L, "STRING", "closure_status", "customer-issues-cc-product-issues", null);

    assertThat(response.values()).containsExactly("需求如此", "设计如此");
  }

  @Test
  void shouldExpandCustomerBugStatusEquivalentValues() {
    LabelGroupExpansionService service = service(group("STRING", true, member("需求如此")));

    LabelGroupExpansionResponse response =
        service.expand(1L, "STRING", "bugStatus", "customer-issues-cc-product-issues", null);

    assertThat(response.values()).containsExactly("需求如此", "设计如此");
  }

  private LabelGroupExpansionService service(LabelGroupRecord group) {
    LabelGroupRepository repository = mock(LabelGroupRepository.class);
    LabelGroupService groupService = new LabelGroupService(repository, null);
    when(repository.findById(1L)).thenReturn(Optional.of(group));
    return new LabelGroupExpansionService(groupService);
  }

  private LabelGroupRecord group(String valueType, boolean enabled, LabelGroupMemberRecord... members) {
    return new LabelGroupRecord(
        1L,
        "测试组",
        valueType,
        "STATIC",
        "SAME_TYPE",
        null,
        null,
        enabled,
        "system",
        OffsetDateTime.now(),
        "system",
        OffsetDateTime.now(),
        List.of(members),
        List.of(),
        null);
  }

  private LabelGroupMemberRecord member(String value) {
    return new LabelGroupMemberRecord(1L, 1L, value, value, 0);
  }
}
