package com.data.collection.platform.service.form;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.common.exception.FormConflictException;
import com.data.collection.platform.common.response.ResultCode;
import com.data.collection.platform.domain.form.FormTemplateSpecCatalog;
import com.data.collection.platform.entity.form.FormActorContext;
import com.data.collection.platform.entity.form.FormCreateResponse;
import com.data.collection.platform.entity.form.FormRecordView;
import java.time.Clock;
import java.time.LocalDateTime;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 表单记录写事务。
 *
 * <p>六步新增、记录级 CAS、审计与 head 在同一事务提交；审计/head 失败向外传播并整体回滚。
 * 信号异常只用于回滚“本次创建的空实例”并返回确定性业务结果，不作为错误日志输出。
 */
@Service
public class FormRecordWriteService {

  private final JdbcTemplate jdbcTemplate;
  private final FormRecordContextService contextService;
  private final FormRecordStore store;
  private final FormRecordViewFactory viewFactory;
  private final Clock clock;

  public FormRecordWriteService(
      JdbcTemplate jdbcTemplate,
      FormRecordContextService contextService,
      FormRecordStore store,
      FormRecordViewFactory viewFactory,
      Clock clock) {
    this.jdbcTemplate = jdbcTemplate;
    this.contextService = contextService;
    this.store = store;
    this.viewFactory = viewFactory;
    this.clock = clock;
  }

  /** 事务外的快速重放核验；未命中返回 {@code null}，由调用方进入写事务。 */
  public FormCreateResponse replayIfPresent(
      FormVersionContext context,
      FormActorContext actor,
      String requestKey,
      String payloadHash) {
    FormRecordRow row = store.findByRequestKey(context, requestKey);
    if (row == null) {
      return null;
    }
    verifyReplay(context, row, actor, payloadHash);
    return new FormCreateResponse(
        "REPLAYED", view(row, actor, context.writable()), null);
  }

  /**
   * 六步新增：key 核验、规范指纹、实例归并、锁内同人检查、唯一插入、审计/head。
   */
  @Transactional
  public FormCreateResponse create(
      FormVersionContext context,
      ValidatedFormValues values,
      FormActorContext actor,
      String actorName,
      String requestKey,
      String payloadHash,
      boolean confirmSameReviewer,
      String clientIp,
      String userAgent) {
    jdbcTemplate.execute(
        "select pg_advisory_xact_lock(hashtext('form_record_id_seq')::bigint)");
    contextService.lockWritableScope(context);
    requireResourceMirrored(context);
    LocalDateTime now = LocalDateTime.now(clock);
    long instanceId = store.ensureInstance(context, now);

    FormRecordRow existing = store.findByRequestKey(context, requestKey);
    if (existing != null) {
      verifyReplay(context, existing, actor, payloadHash);
      throw new ReplaySignal(view(existing, actor, context.writable()));
    }

    if (FormTemplateSpecCatalog.CODE_REVIEW.equals(context.spec().templateKey())
        && !confirmSameReviewer) {
      Object reviewer = values.responseFields().get("reviewerUserId");
      String reviewerUserId = reviewer == null ? null : reviewer.toString();
      if (store.sameReviewerExists(context, instanceId, reviewerUserId)) {
        throw new ConfirmRequiredSignal(reviewerUserId);
      }
    }

    FormRecordRow inserted =
        store.insertRecord(context, instanceId, values, actor.userId(), requestKey, payloadHash, now);
    if (inserted == null) {
      FormRecordRow raced = store.findByRequestKey(context, requestKey);
      if (raced == null) {
        throw new FormConflictException("并发写入冲突，请重试");
      }
      verifyReplay(context, raced, actor, payloadHash);
      throw new ReplaySignal(view(raced, actor, context.writable()));
    }
    finishWrite("CREATE", context, inserted, actor, actorName, clientIp, userAgent, now);
    return new FormCreateResponse(
        "CREATED", view(inserted, actor, context.writable()), null);
  }

  /** 编辑本人记录：字段按当前规格校验，CAS 失败时读取权威状态分类。 */
  @Transactional
  public FormRecordView update(
      FormVersionContext context,
      long id,
      int expectedVersion,
      ValidatedFormValues values,
      FormActorContext actor,
      String actorName,
      String clientIp,
      String userAgent) {
    contextService.lockWritableScope(context);
    requireResourceMirrored(context);
    long instanceId = requireInstance(context);
    FormRecordRow existing = requireRecord(context, id, instanceId);
    requireOwnership(existing, actor);
    if (existing.deleted()) {
      throw new FormConflictException("记录已作废，不能编辑");
    }
    if (existing.recordVersion() != expectedVersion) {
      throw new FormConflictException("记录已被他人修改，请刷新后重试");
    }
    if (expectedVersion == Integer.MAX_VALUE) {
      throw new FormConflictException("记录版本已达上限，不能继续修改");
    }
    LocalDateTime now = LocalDateTime.now(clock);
    int updated =
        store.updateRecord(
            context, id, instanceId, actor.userId(), expectedVersion, values, now);
    if (updated == 0) {
      throw classify(context, id, instanceId, actor);
    }
    FormRecordRow saved = requireRecord(context, id, instanceId);
    finishWrite("UPDATE", context, saved, actor, actorName, clientIp, userAgent, now);
    return view(saved, actor, context.writable());
  }

  /** 作废本人记录：重复作废在版本相符时返回已作废状态且不重复写审计。 */
  @Transactional
  public FormRecordView voidRecord(
      FormVersionContext context,
      long id,
      int expectedVersion,
      FormActorContext actor,
      String actorName,
      String clientIp,
      String userAgent) {
    contextService.lockWritableScope(context);
    requireResourceMirrored(context);
    long instanceId = requireInstance(context);
    FormRecordRow existing = requireRecord(context, id, instanceId);
    requireOwnership(existing, actor);
    if (existing.deleted()) {
      if (existing.recordVersion() == expectedVersion) {
        return view(existing, actor, context.writable());
      }
      throw new FormConflictException("记录已作废，且版本与本次请求不一致");
    }
    if (existing.recordVersion() != expectedVersion) {
      throw new FormConflictException("记录已被他人修改，请刷新后重试");
    }
    if (expectedVersion == Integer.MAX_VALUE) {
      throw new FormConflictException("记录版本已达上限，不能继续修改");
    }
    LocalDateTime now = LocalDateTime.now(clock);
    int updated =
        store.voidRecord(context, id, instanceId, actor.userId(), expectedVersion, now);
    if (updated == 0) {
      FormRecordRow current = store.findById(context, id, instanceId);
      if (current != null && current.deleted() && current.recordVersion() == expectedVersion) {
        return view(current, actor, context.writable());
      }
      throw classify(context, id, instanceId, actor);
    }
    FormRecordRow saved = requireRecord(context, id, instanceId);
    finishWrite("VOID", context, saved, actor, actorName, clientIp, userAgent, now);
    return view(saved, actor, context.writable());
  }

  private void finishWrite(
      String operation,
      FormVersionContext context,
      FormRecordRow row,
      FormActorContext actor,
      String actorName,
      String clientIp,
      String userAgent,
      LocalDateTime now) {
    long auditId =
        store.writeAudit(operation, context, row, actor.userId(), actorName, clientIp, userAgent, now);
    store.bumpHead(context, auditId, row, store.authorityRevision(context), now);
  }

  private FormRecordView view(FormRecordRow row, FormActorContext actor, boolean writable) {
    return viewFactory.toView(row, actor, writable, store.creatorName(row.id()));
  }

  private void verifyReplay(
      FormVersionContext context,
      FormRecordRow row,
      FormActorContext actor,
      String payloadHash) {
    Long instanceId = store.findInstanceId(context);
    boolean sameInstance = instanceId != null && instanceId == row.instanceId();
    boolean sameActor = row.createdBy() != null && row.createdBy().equals(actor.userId());
    boolean samePayload =
        row.createPayloadHash() != null && row.createPayloadHash().equalsIgnoreCase(payloadHash);
    if (!sameInstance || !sameActor || !samePayload) {
      throw new FormConflictException("请求标识与本次操作不一致");
    }
    if (row.deleted()) {
      throw new FormConflictException("该请求对应的记录已作废，不能重放");
    }
  }

  private long requireInstance(FormVersionContext context) {
    Long instanceId = store.findInstanceId(context);
    if (instanceId == null) {
      throw new BizException(ResultCode.BAD_REQUEST, "记录不存在或不属于当前资源");
    }
    return instanceId;
  }

  private FormRecordRow requireRecord(
      FormVersionContext context, long id, long instanceId) {
    FormRecordRow row = store.findById(context, id, instanceId);
    if (row == null) {
      throw new BizException(ResultCode.BAD_REQUEST, "记录不存在或不属于当前资源");
    }
    return row;
  }

  private void requireOwnership(FormRecordRow row, FormActorContext actor) {
    if (row.createdBy() == null || !row.createdBy().equals(actor.userId())) {
      throw new AccessDeniedException("只能操作本人创建的记录");
    }
  }

  private void requireResourceMirrored(FormVersionContext context) {
    if (context.rootId() == null) {
      throw new BizException(ResultCode.BAD_REQUEST, "资源待同步，请稍后重试");
    }
  }

  private FormConflictException classify(
      FormVersionContext context, long id, long instanceId, FormActorContext actor) {
    FormRecordRow current = store.findById(context, id, instanceId);
    if (current == null) {
      return new FormConflictException("记录不存在或不属于当前资源");
    }
    if (current.createdBy() == null || !current.createdBy().equals(actor.userId())) {
      throw new AccessDeniedException("只能操作本人创建的记录");
    }
    if (current.deleted()) {
      return new FormConflictException("记录已作废，不能继续修改");
    }
    return new FormConflictException("记录已被他人修改，请刷新后重试");
  }

  /** 事务内的重放信号：回滚可能已创建的空实例并返回当前权威记录。 */
  static final class ReplaySignal extends RuntimeException {
    private final transient FormRecordView record;

    ReplaySignal(FormRecordView record) {
      super(null, null, false, false);
      this.record = record;
    }

    FormRecordView record() {
      return record;
    }
  }

  /** 同走查人二次确认信号：回滚本次事务，不写入记录。 */
  static final class ConfirmRequiredSignal extends RuntimeException {
    private final String reviewerUserId;

    ConfirmRequiredSignal(String reviewerUserId) {
      super(null, null, false, false);
      this.reviewerUserId = reviewerUserId;
    }

    String reviewerUserId() {
      return reviewerUserId;
    }
  }
}
