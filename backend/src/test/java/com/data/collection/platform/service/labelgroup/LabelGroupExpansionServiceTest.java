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
  void shouldExpandStaticGroupMembers() {
    LabelGroupExpansionService service =
        service(group("module", true, member("module", "草图"), member("module", "工程图")));

    LabelGroupExpansionResponse response = service.expand(1L, "module", "review-data-home", "cc");

    assertThat(response.values()).containsExactly("草图", "工程图");
    assertThat(response.dimensionName()).isEqualTo("模块");
  }

  @Test
  void shouldRejectDisabledGroup() {
    LabelGroupExpansionService service = service(group("module", false, member("module", "草图")));

    assertThatThrownBy(() -> service.expand(1L, "module", null, null))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("已禁用");
  }

  @Test
  void shouldRejectEmptyGroup() {
    LabelGroupExpansionService service = service(group("module", true));

    assertThatThrownBy(() -> service.expand(1L, "module", null, null))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("没有可用成员");
  }

  @Test
  void shouldRejectDimensionMismatch() {
    LabelGroupExpansionService service = service(group("module", true, member("module", "草图")));

    assertThatThrownBy(() -> service.expand(1L, "project", null, null))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("只能应用到相同维度");
  }

  @Test
  void shouldRejectIncompatiblePage() {
    LabelGroupExpansionService service =
        service(group("review_owner", true, member("review_owner", "张三")));

    assertThatThrownBy(() -> service.expand(1L, "review_owner", "question-metrics-issue-search", null))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("当前页面不支持该标签维度");
  }

  @Test
  void shouldExpandClosureStatusEquivalentValues() {
    LabelGroupExpansionService service =
        service(group("closure_status", true, member("closure_status", "需求如此")));

    LabelGroupExpansionResponse response =
        service.expand(1L, "closure_status", "customer-issues-cc-product-issues", null);

    assertThat(response.values()).containsExactly("需求如此", "设计如此");
  }

  private LabelGroupExpansionService service(LabelGroupRecord group) {
    LabelGroupRepository repository = mock(LabelGroupRepository.class);
    LabelValueQueryService valueQueryService = mock(LabelValueQueryService.class);
    LabelDimensionCatalogService catalogService = new LabelDimensionCatalogService();
    LabelGroupService groupService = new LabelGroupService(repository, catalogService, valueQueryService);
    when(repository.findById(1L)).thenReturn(Optional.of(group));
    return new LabelGroupExpansionService(groupService, catalogService);
  }

  private LabelGroupRecord group(String dimensionKey, boolean enabled, LabelGroupMemberRecord... members) {
    return new LabelGroupRecord(
        1L,
        "测试组",
        dimensionKey,
        "STATIC",
        null,
        enabled,
        "system",
        OffsetDateTime.now(),
        "system",
        OffsetDateTime.now(),
        List.of(members));
  }

  private LabelGroupMemberRecord member(String dimensionKey, String value) {
    return new LabelGroupMemberRecord(1L, 1L, dimensionKey, value, value, 0);
  }
}
