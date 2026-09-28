package com.data.collection.platform.service.statistics;

import com.data.collection.platform.entity.FactProjectionScope;
import com.data.collection.platform.entity.FactType;
import com.data.collection.platform.entity.ProjectionScopeType;
import com.data.collection.platform.service.FactProjectionScopeKeyCodec;
import com.data.collection.platform.service.GitlabSourceInstanceSupport;
import java.util.Set;

/** 统计板测试共享的快照读取范围。 */
final class StatisticBoardTestSnapshotScopes {
  private StatisticBoardTestSnapshotScopes() {}

  /** 测试夹具使用的默认来源项目范围计划，仅用于构造可编译的快照请求。 */
  static StatisticBoardSnapshotService.SourceReadPlan defaultProjectScopes() {
    return StatisticBoardSnapshotService.SourceReadPlan.of(StatisticBoardTestSnapshotScopes::defaultProjectScopeSet);
  }

  /** 快照服务桩执行一致性边界动作时使用的读取上下文。 */
  static StatisticBoardSnapshotService.SourceRead testSourceRead() {
    return new StatisticBoardSnapshotService.SourceRead(
        defaultProjectScopeSet(), "test-source-version");
  }

  static Set<FactProjectionScope> defaultProjectScopeSet() {
    return Set.of(
        new FactProjectionScope(
            GitlabSourceInstanceSupport.DEFAULT_SOURCE_INSTANCE,
            FactType.ISSUE,
            ProjectionScopeType.PROJECT,
            FactProjectionScopeKeyCodec.project(325L)));
  }
}
