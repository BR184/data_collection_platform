package com.data.collection.platform.service;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.common.JsonUtils;
import com.data.collection.platform.entity.FactManualDisposition;
import com.data.collection.platform.entity.FactResumeMode;
import com.data.collection.platform.entity.FactTaskKind;
import com.data.collection.platform.entity.FactTaskResolutionAction;
import com.data.collection.platform.entity.FactTaskResolutionResult;
import com.data.collection.platform.entity.FactType;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.SyncSubmissionAction;
import com.data.collection.platform.entity.SyncTriggerType;
import com.data.collection.platform.entity.SyncType;
import com.data.collection.platform.entity.sync.SyncRun;
import com.data.collection.platform.entity.sync.SyncRunType;
import com.data.collection.platform.mapper.SyncRunMapper;
import com.data.collection.platform.service.sync.SyncRunEventRecorder;
import com.data.collection.platform.service.sync.SyncRunPayload;
import com.data.collection.platform.entity.sync.SyncRunSubmissionResult;
import com.data.collection.platform.service.sync.SyncRunSubmissionService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 人工处置待处理事实/投影任务的命令入口。
 *
 * <p>处置是执行权转移协议，不是一次状态改写：命令必须在同一事务内校验来源归属与原运行身份、取得任务行锁、
 * 建立可见的新运行并完成意图移交。继续只处理被选中的意图，不重新入队整个来源，也不静默扩大范围；取消只取消
 * 本次执行意图，不删除版本头、不推进发布水位。
 */
@Service
public class FactTaskResolutionService {
  private static final String EVENT_FACT_TASK_RESUMED = "FACT_TASK_RESUMED";
  private static final String EVENT_PROJECTION_TASK_TAKEOVER = "FACT_PROJECTION_TASK_TAKEOVER";
  /** 旧运行保存的不可变诊断快照事件类型；与运行内的接管记录类型分开，历史详情只合并快照。 */
  private static final String EVENT_FACT_TASK_RESUMED_SNAPSHOT = "FACT_TASK_RESUMED_SNAPSHOT";
  private static final String EVENT_PROJECTION_TASK_TAKEOVER_SNAPSHOT =
      "FACT_PROJECTION_TASK_TAKEOVER_SNAPSHOT";
  private static final String CANCEL_MESSAGE = "维护人员已取消本次执行意图";
  private static final int RESUME_ROOT_BATCH_SIZE = 200;

  private final GitlabConfigService configService;
  private final FactBuildTaskService factBuildTaskService;
  private final FactProjectionTaskService projectionTaskService;
  private final SyncRunSubmissionService submissionService;
  private final SyncRunEventRecorder eventRecorder;
  private final SyncRunMapper syncRunMapper;
  private final JsonUtils jsonUtils;

  public FactTaskResolutionService(
      GitlabConfigService configService,
      FactBuildTaskService factBuildTaskService,
      FactProjectionTaskService projectionTaskService,
      SyncRunSubmissionService submissionService,
      SyncRunEventRecorder eventRecorder,
      SyncRunMapper syncRunMapper,
      JsonUtils jsonUtils) {
    this.configService = configService;
    this.factBuildTaskService = factBuildTaskService;
    this.projectionTaskService = projectionTaskService;
    this.submissionService = submissionService;
    this.eventRecorder = eventRecorder;
    this.syncRunMapper = syncRunMapper;
    this.jsonUtils = jsonUtils;
  }

  /**
   * 处置一个待处理任务：继续（移交到可见的新运行）或取消本次执行意图。
   *
   * @param configId 数据源配置编号，用于来源与权限归属校验
   * @param kind 任务类型
   * @param taskId 任务主键
   * @param expectedTaskRunId 界面所见任务的原运行编号；与原归属不一致时拒绝，避免旧点击误触发新接管
   * @param action 继续或取消
   * @param resumeMode 继续时的意图恢复模式；为空按 {@code ORIGINAL} 处理，仅事实任务可显式选择另外两种
   * @return 处置结果，含原运行、新运行与来源仍待发布的根数量
   */
  @Transactional
  public FactTaskResolutionResult resolvePendingTask(
      Long configId,
      FactTaskKind kind,
      Long taskId,
      String expectedTaskRunId,
      FactTaskResolutionAction action,
      String resumeMode) {
    if (configId == null || kind == null || taskId == null || action == null) {
      throw new BizException("人工处置命令缺少必要参数");
    }
    if (expectedTaskRunId == null || expectedTaskRunId.isBlank()) {
      throw new BizException("人工处置命令必须携带任务的原运行编号");
    }
    GitlabSyncConfig config = configService.getConfigById(configId);
    String sourceInstance = GitlabSourceInstanceSupport.sourceInstanceOf(config);
    return switch (kind) {
      case FACT_BUILD ->
          resolveFactTask(
              config, sourceInstance, taskId, expectedTaskRunId, action,
              FactResumeMode.parse(resumeMode));
      case PROJECTION ->
          resolveProjectionTask(config, sourceInstance, taskId, expectedTaskRunId, action);
    };
  }

  private FactTaskResolutionResult resolveFactTask(
      GitlabSyncConfig config,
      String sourceInstance,
      long taskId,
      String expectedTaskRunId,
      FactTaskResolutionAction action,
      FactResumeMode resumeMode) {
    FactBuildTaskService.FactTaskSnapshot task =
        factBuildTaskService.lockTaskForResolution(taskId);
    if (task == null) {
      throw new BizException("事实任务不存在：" + taskId);
    }
    if (task.configId() != null && !task.configId().equals(config.getId())) {
      throw new BizException("该事实任务不属于当前数据源");
    }
    if (!sourceInstance.equals(task.sourceInstance())) {
      throw new BizException("该事实任务不属于当前来源");
    }
    if (!expectedTaskRunId.equals(task.runId())) {
      Long resumedRunId = existingResumeRunId(
          task.manualDisposition(), factBuildTaskService.findResumedTask(taskId), taskId);
      if (resumedRunId != null) {
        return new FactTaskResolutionResult(
            taskId,
            FactTaskKind.FACT_BUILD,
            task.runId(),
            externalRunId(resumedRunId),
            FactManualDisposition.RESUMED,
            pendingUpdates(config.getId(), sourceInstance, task.factType()));
      }
      throw new BizException("任务归属已变化，请刷新后重试");
    }
    if (FactManualDisposition.RESUMED.name().equals(task.manualDisposition())) {
      Long resumedRunId =
          existingResumeRunId(task.manualDisposition(), factBuildTaskService.findResumedTask(taskId), taskId);
      return new FactTaskResolutionResult(
          taskId,
          FactTaskKind.FACT_BUILD,
          task.runId(),
          resumedRunId == null ? null : externalRunId(resumedRunId),
          FactManualDisposition.RESUMED,
          pendingUpdates(config.getId(), sourceInstance, task.factType()));
    }
    if (FactManualDisposition.CANCELLED.name().equals(task.manualDisposition())) {
      return new FactTaskResolutionResult(
          taskId,
          FactTaskKind.FACT_BUILD,
          task.runId(),
          null,
          FactManualDisposition.CANCELLED,
          pendingUpdates(config.getId(), sourceInstance, task.factType()));
    }
    if (!resolvableTask(task.status(), task.manualDisposition()) || task.lockOwner() != null) {
      throw new BizException("该事实任务仍在自动执行或已不可处置：" + taskId);
    }
    if (action == FactTaskResolutionAction.CANCEL) {
      if (factBuildTaskService.cancelTaskByHumanDecision(taskId, CANCEL_MESSAGE) != 1) {
        throw new BizException("任务执行权已变化，请刷新后重试");
      }
      return new FactTaskResolutionResult(
          taskId,
          FactTaskKind.FACT_BUILD,
          task.runId(),
          null,
          FactManualDisposition.CANCELLED,
          pendingUpdates(config.getId(), sourceInstance, task.factType()));
    }

    boolean full = task.full() || resumeMode == FactResumeMode.FULL;
    List<Long> rootIds =
        resumeMode == FactResumeMode.ORIGINAL
            ? factBuildTaskService.loadAssignedRootIds(taskId)
            : List.of();
    if (resumeMode == FactResumeMode.ORIGINAL && !full && rootIds.isEmpty()) {
      throw new BizException(
          "原任务已释放根，无法按原意图恢复；请选择当前待发布根刷新或该事实族全量重建");
    }
    SyncRun resumedRun =
        createResumeRun(
            config,
            FactTaskKind.FACT_BUILD,
            taskId,
            task.runId(),
            resumeMode,
            task.factType());
    long newTaskId =
        factBuildTaskService.insertResumedTask(task, resumedRun.getId(), full, rootIds);
    // 继续之后原任务不再持有任何根：原任务转为 SKIPPED 后没有任何自动路径会消费它的根归属。
    factBuildTaskService.releaseTaskRoots(taskId);
    if (resumeMode == FactResumeMode.CURRENT_PENDING) {
      factBuildTaskService.assignPendingRootsToTask(
          config.getId(),
          sourceInstance,
          FactType.valueOf(task.factType()),
          newTaskId,
          RESUME_ROOT_BATCH_SIZE);
    }
    if (factBuildTaskService.markTaskResumed(taskId, "已移交人工继续（新任务 " + newTaskId + "）") != 1) {
      throw new BizException("任务执行权已变化，请刷新后重试");
    }
    Map<String, Object> diagnostic = factTakeoverDiagnostic(task, resumedRun, newTaskId, resumeMode, full);
    recordStrict(
        numericRunId(task.runId()),
        config.getId(),
        sourceInstance,
        EVENT_FACT_TASK_RESUMED_SNAPSHOT,
        "事实任务已由维护人员继续，原错误与时间保留在本事件中",
        diagnostic);
    recordStrict(
        resumedRun.getId(),
        config.getId(),
        sourceInstance,
        EVENT_FACT_TASK_RESUMED,
        "已接管事实任务 " + taskId,
        diagnostic);
    return new FactTaskResolutionResult(
        taskId,
        FactTaskKind.FACT_BUILD,
        task.runId(),
        resumedRun.getRunId(),
        FactManualDisposition.RESUMED,
        pendingUpdates(config.getId(), sourceInstance, task.factType()));
  }

  private FactTaskResolutionResult resolveProjectionTask(
      GitlabSyncConfig config,
      String sourceInstance,
      long taskId,
      String expectedTaskRunId,
      FactTaskResolutionAction action) {
    FactProjectionTaskService.ProjectionTaskSnapshot task =
        projectionTaskService.lockTaskForResolution(taskId);
    if (task == null) {
      throw new BizException("投影任务不存在：" + taskId);
    }
    if (!sourceInstance.equals(task.sourceInstance())) {
      throw new BizException("该投影任务不属于当前来源");
    }
    String currentRunId = String.valueOf(task.factRunId());
    boolean idempotentTakeover = takeoverRunMatches(task.factRunId(), taskId, expectedTaskRunId);
    if (!expectedTaskRunId.equals(currentRunId) && !idempotentTakeover) {
      throw new BizException("任务归属已变化，请刷新后重试");
    }
    if (idempotentTakeover) {
      return new FactTaskResolutionResult(
          taskId,
          FactTaskKind.PROJECTION,
          expectedTaskRunId,
          externalRunId(task.factRunId()),
          FactManualDisposition.RESUMED,
          pendingUpdates(config.getId(), sourceInstance, task.factType()));
    }
    if (FactManualDisposition.CANCELLED.name().equals(task.manualDisposition())) {
      return new FactTaskResolutionResult(
          taskId,
          FactTaskKind.PROJECTION,
          currentRunId,
          null,
          FactManualDisposition.CANCELLED,
          pendingUpdates(config.getId(), sourceInstance, task.factType()));
    }
    if (!resolvableTask(task.status(), task.manualDisposition()) || task.leaseOwner() != null) {
      throw new BizException("该投影任务仍在自动执行或已不可处置：" + taskId);
    }
    if (action == FactTaskResolutionAction.CANCEL) {
      if (projectionTaskService.cancelTaskByHumanDecision(taskId, CANCEL_MESSAGE) != 1) {
        throw new BizException("任务执行权已变化，请刷新后重试");
      }
      return new FactTaskResolutionResult(
          taskId,
          FactTaskKind.PROJECTION,
          currentRunId,
          null,
          FactManualDisposition.CANCELLED,
          pendingUpdates(config.getId(), sourceInstance, task.factType()));
    }
    SyncRun resumedRun =
        createResumeRun(
            config,
            FactTaskKind.PROJECTION,
            taskId,
            currentRunId,
            null,
            task.factType());
    Map<String, Object> diagnostic = projectionTakeoverDiagnostic(task, resumedRun);
    recordStrict(
        task.factRunId(),
        config.getId(),
        sourceInstance,
        EVENT_PROJECTION_TASK_TAKEOVER_SNAPSHOT,
        "投影任务已由维护人员继续，原错误、预算与目标 generation 保留在本事件中",
        diagnostic);
    if (projectionTaskService.handOverToRun(taskId, resumedRun.getId(), task.maxRetryCount()) != 1) {
      throw new BizException("任务执行权已变化，请刷新后重试");
    }
    recordStrict(
        resumedRun.getId(),
        config.getId(),
        sourceInstance,
        EVENT_PROJECTION_TASK_TAKEOVER,
        "已接管投影任务 " + taskId,
        diagnostic);
    return new FactTaskResolutionResult(
        taskId,
        FactTaskKind.PROJECTION,
        currentRunId,
        resumedRun.getRunId(),
        FactManualDisposition.RESUMED,
        pendingUpdates(config.getId(), sourceInstance, task.factType()));
  }

  /**
   * 建立承载被选中意图的可见事实刷新运行。
   *
   * <p>同来源互斥由既有提交入口保证：提交被复用为其他运行时不启动重叠执行，而是返回明确冲突。
   */
  private SyncRun createResumeRun(
      GitlabSyncConfig config,
      FactTaskKind kind,
      long taskId,
      String originalRunId,
      FactResumeMode resumeMode,
      String factType) {
    Map<String, Object> intent = new LinkedHashMap<>();
    intent.put("kind", kind.name());
    intent.put("taskId", taskId);
    intent.put("originalRunId", originalRunId);
    if (resumeMode != null) {
      intent.put("mode", resumeMode.name());
    }
    SyncRunSubmissionResult submission =
        submissionService.submitRun(
            config,
            SyncType.COMPENSATION,
            SyncRunType.FACT_REFRESH,
            SyncTriggerType.MANUAL,
            resumeReason(kind, factType, taskId),
            List.of(),
            null,
            null,
            null,
            Map.of("resumedTask", intent));
    if (submission == null || submission.runId() == null) {
      throw new BizException("未能建立继续运行，请稍后重试");
    }
    SyncRun run = syncRunMapper.selectById(submission.runId());
    if (run == null) {
      throw new BizException("继续运行不存在，请刷新后重试");
    }
    boolean created =
        submission.action() == SyncSubmissionAction.CREATED
            || submission.action() == SyncSubmissionAction.QUEUED;
    if (created || resumeIntentMatches(run, kind, taskId, originalRunId)) {
      return run;
    }
    throw new BizException("当前数据源已有活动的事实刷新运行，请等待其结束后再继续待处理任务");
  }

  private String resumeReason(FactTaskKind kind, String factType, long taskId) {
    String suffix = factType == null || factType.isBlank() ? "" : "（" + factType + "）";
    return kind == FactTaskKind.FACT_BUILD
        ? "人工继续事实任务 " + taskId + suffix
        : "人工继续投影任务 " + taskId + suffix;
  }

  /** 运行 payload 是否就是本任务的继续运行；用于重复命令返回既有结果。 */
  private boolean resumeIntentMatches(
      SyncRun run, FactTaskKind kind, long taskId, String originalRunId) {
    SyncRunPayload payload = payloadOf(run);
    SyncRunPayload.ResumedTaskIntent intent = payload == null ? null : payload.resumedTask();
    return intent != null
        && kind.name().equals(intent.kind())
        && Long.valueOf(taskId).equals(intent.taskId())
        && originalRunId.equals(intent.originalRunId());
  }

  /** 投影行已归属另一运行且该运行就是本任务的接管运行时，返回该新运行编号。 */
  private boolean takeoverRunMatches(long currentRunId, long taskId, String expectedTaskRunId) {
    SyncRun run = syncRunMapper.selectById(currentRunId);
    return run != null
        && resumeIntentMatches(run, FactTaskKind.PROJECTION, taskId, expectedTaskRunId);
  }

  private Long existingResumeRunId(
      String manualDisposition,
      FactBuildTaskService.FactTaskSnapshot resumedTask,
      long taskId) {
    if (resumedTask == null
        || !FactManualDisposition.RESUMED.name().equals(manualDisposition)
        || resumedTask.runId() == null) {
      return null;
    }
    Long runId = numericRunId(resumedTask.runId());
    return runId;
  }

  private Map<String, Object> factTakeoverDiagnostic(
      FactBuildTaskService.FactTaskSnapshot task,
      SyncRun resumedRun,
      long newTaskId,
      FactResumeMode resumeMode,
      boolean full) {
    Map<String, Object> diagnostic = new LinkedHashMap<>();
    diagnostic.put("kind", FactTaskKind.FACT_BUILD.name());
    diagnostic.put("taskId", task.id());
    diagnostic.put("originalRunId", task.runId());
    diagnostic.put("newRunId", resumedRun.getRunId());
    diagnostic.put("newTaskId", newTaskId);
    diagnostic.put("resumeMode", resumeMode.name());
    diagnostic.put("full", full);
    diagnostic.put("originalStatus", task.status());
    diagnostic.put("originalDisposition", task.manualDisposition());
    diagnostic.put("rawError", task.errorMessage());
    diagnostic.put("originalMessage", task.message());
    diagnostic.put("retryCount", task.retryCount());
    diagnostic.put("maxRetryCount", task.maxRetryCount());
    diagnostic.put("sourceInstance", task.sourceInstance());
    diagnostic.put("factType", task.factType());
    diagnostic.put("scope", task.scope());
    diagnostic.put("startedAt", task.startedAt());
    diagnostic.put("finishedAt", task.finishedAt());
    return diagnostic;
  }

  private Map<String, Object> projectionTakeoverDiagnostic(
      FactProjectionTaskService.ProjectionTaskSnapshot task, SyncRun resumedRun) {
    Map<String, Object> diagnostic = new LinkedHashMap<>();
    diagnostic.put("kind", FactTaskKind.PROJECTION.name());
    diagnostic.put("taskId", task.id());
    diagnostic.put("originalRunId", String.valueOf(task.factRunId()));
    diagnostic.put("newRunId", resumedRun.getRunId());
    diagnostic.put("factBuildTaskId", task.factBuildTaskId());
    diagnostic.put("sourceInstance", task.sourceInstance());
    diagnostic.put("factType", task.factType());
    diagnostic.put("scopeType", task.scopeType());
    diagnostic.put("scopeKey", task.scopeKey());
    diagnostic.put("targetGeneration", task.targetGeneration());
    diagnostic.put("originalStatus", task.status());
    diagnostic.put("originalDisposition", task.manualDisposition());
    diagnostic.put("rawError", task.errorMessage());
    diagnostic.put("retryCount", task.retryCount());
    diagnostic.put("maxRetryCount", task.maxRetryCount());
    diagnostic.put("startedAt", task.startedAt());
    diagnostic.put("finishedAt", task.finishedAt());
    diagnostic.put("heartbeatAt", task.heartbeatAt());
    diagnostic.put("leaseUntil", task.leaseUntil());
    return diagnostic;
  }

  /** 严格写入接管诊断：写入失败即整体回滚，避免历史诊断与实际归属不一致。 */
  private void recordStrict(
      Long runId,
      Long configId,
      String sourceInstance,
      String eventType,
      String message,
      Map<String, Object> payload) {
    if (runId == null || syncRunMapper.selectById(runId) == null) {
      // 手工 runGuarded 路径的运行编号可能是 UUID，没有可挂载的事件行；此时该次执行不产生运行级历史。
      return;
    }
    eventRecorder.recordStrict(
        runId, configId, sourceInstance, eventType, message, jsonUtils.toJson(payload));
  }

  private SyncRunPayload payloadOf(SyncRun run) {
    if (run == null || run.getPayloadJson() == null || run.getPayloadJson().isBlank()) {
      return null;
    }
    try {
      return jsonUtils.fromJson(run.getPayloadJson(), SyncRunPayload.typeReference());
    } catch (IllegalStateException ignored) {
      return null;
    }
  }

  private String externalRunId(long runId) {
    SyncRun run = syncRunMapper.selectById(runId);
    return run == null ? null : run.getRunId();
  }

  private Long numericRunId(String runId) {
    if (runId == null || runId.isBlank()) {
      return null;
    }
    try {
      long parsed = Long.parseLong(runId.trim());
      return parsed <= 0L ? null : parsed;
    } catch (NumberFormatException ignored) {
      return null;
    }
  }

  private long pendingUpdates(Long configId, String sourceInstance, String factType) {
    return factBuildTaskService.countUnpublishedTargets(configId, sourceInstance, factType);
  }

  /**
   * 事实与投影任务同一口径：只有等待人工决定、或终态失败且尚未人工处置的任务可被接管。
   * 排队、重试中与已成功的任务都不在可处置范围，人工已处置的任务走幂等分支。
   */
  private boolean resolvableTask(String status, String manualDisposition) {
    if (FactManualDisposition.REQUIRES_DECISION.name().equals(manualDisposition)) {
      return true;
    }
    return FactManualDisposition.NONE.name().equals(manualDisposition)
        && "FAILED".equals(status);
  }
}
