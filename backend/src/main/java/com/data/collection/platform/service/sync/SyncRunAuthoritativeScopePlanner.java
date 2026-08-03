package com.data.collection.platform.service.sync;

import com.data.collection.platform.entity.sync.SyncRunTableTask;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/** 把扫描信号规范化为去重权威范围，不创建每范围表任务。 */
@Service
public class SyncRunAuthoritativeScopePlanner {
  private final SyncRunAuthoritativeScopeRepository repository;

  public SyncRunAuthoritativeScopePlanner(SyncRunAuthoritativeScopeRepository repository) {
    this.repository = repository;
  }

  /**
   * 根据普通增量页的来源行登记全部已配置子关系范围。
   *
   * <p>权威范围 worker 的结果不会递归登记范围；只有普通 INCREMENTAL producer 可以调用。
   */
  public int enqueueFromParentRows(
      SyncRunTableTask producerTask, List<Map<String, Object>> sourceRows) {
    if (producerTask == null
        || producerTask.getRunId() == null
        || producerTask.getRunId() <= 0L
        || producerTask.getSourceInstance() == null
        || producerTask.getSourceInstance().isBlank()
        || !"INCREMENTAL".equalsIgnoreCase(producerTask.getRowStrategy())
        || sourceRows == null
        || sourceRows.isEmpty()) {
      return 0;
    }
    String parentTable =
        com.data.collection.platform.service.GitlabSourceInstanceSupport
            .normalizeSourceTableName(producerTask.getSourceTable());
    Set<String> selectedTables =
        repository.selectedSourceTables(producerTask.getRunId());
    int inserted = 0;
    for (GitlabSourceLineageCatalog.Relation relation :
        GitlabSourceLineageCatalog.relationsForParent(parentTable)) {
      if (!selectedTables.contains(relation.childTable())) {
        continue;
      }
      LinkedHashMap<String, Map<String, Object>> scopes = new LinkedHashMap<>();
      for (Map<String, Object> sourceRow : sourceRows) {
        Map<String, Object> scope = relation.scopeForParentRow(sourceRow);
        if (!scope.isEmpty()) {
          String signature = new java.util.TreeMap<>(scope).toString();
          scopes.putIfAbsent(signature, scope);
        }
      }
      if (!scopes.isEmpty()) {
        inserted +=
            repository.enqueueScopes(
                producerTask.getRunId(),
                producerTask.getSourceInstance(),
                producerTask.getId(),
                relation.childTable(),
                relation.relationKey(),
                List.copyOf(scopes.values()));
      }
    }
    return inserted;
  }

  /** 登记控制面已经给出的完整权威范围，例如 System Hook 精确信号。 */
  public int enqueueDeclaredScope(
      long runId,
      String sourceInstance,
      String childTable,
      String relationKey,
      Map<String, Object> lookupScope) {
    return repository.enqueueScopes(
        runId,
        sourceInstance,
        null,
        childTable,
        relationKey,
        List.of(lookupScope));
  }
}
