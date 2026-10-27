package com.data.collection.platform.service.form;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.common.response.ResultCode;
import com.data.collection.platform.domain.form.FormSourceUrlSupport;
import com.data.collection.platform.entity.CollectFormNotificationPayloadResponse;
import com.data.collection.platform.entity.form.FormAccountView;
import com.data.collection.platform.entity.form.FormActorContext;
import com.data.collection.platform.entity.form.FormCapabilitiesView;
import com.data.collection.platform.entity.form.FormCreateRequest;
import com.data.collection.platform.entity.form.FormCreateResponse;
import com.data.collection.platform.entity.form.FormDetailResponse;
import com.data.collection.platform.entity.form.FormRecordView;
import com.data.collection.platform.entity.form.FormResourceView;
import com.data.collection.platform.entity.form.FormReviewerCandidatesResponse;
import com.data.collection.platform.entity.form.FormUpdateRequest;
import com.data.collection.platform.entity.form.FormVoidRequest;
import com.data.collection.platform.security.PlatformPermissionCodes;
import com.data.collection.platform.service.GitlabResourceRootResolver;
import com.data.collection.platform.service.GitlabSourceInstanceSupport;
import com.data.collection.platform.service.PlatformIdentityService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 三模板记录服务的读侧与会话编排；写事务由 {@link FormRecordWriteService} 承担。 */
@Service
public class FormRecordService {

  private final FormRecordContextService contextService;
  private final FormFieldValueService fieldValueService;
  private final FormRecordWriteService writeService;
  private final FormRecordStore store;
  private final FormRecordViewFactory viewFactory;
  private final FormReviewerDirectoryService reviewerDirectory;
  private final PlatformIdentityService identityService;
  private final FormRecordDirectoryService directoryService;
  private final JdbcTemplate jdbcTemplate;
  private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

  public FormRecordService(
      FormRecordContextService contextService,
      FormFieldValueService fieldValueService,
      FormRecordWriteService writeService,
      FormRecordStore store,
      FormRecordViewFactory viewFactory,
      FormReviewerDirectoryService reviewerDirectory,
      PlatformIdentityService identityService,
      FormRecordDirectoryService directoryService,
      JdbcTemplate jdbcTemplate,
      com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
    this.contextService = contextService;
    this.fieldValueService = fieldValueService;
    this.writeService = writeService;
    this.store = store;
    this.viewFactory = viewFactory;
    this.reviewerDirectory = reviewerDirectory;
    this.identityService = identityService;
    this.directoryService = directoryService;
    this.jdbcTemplate = jdbcTemplate;
    this.objectMapper = objectMapper;
  }

  /** detail 只读；返回冻结规格、结构版本、资源、账号与全部未作废记录。 */
  @Transactional(readOnly = true)
  public FormDetailResponse detail(
      String templateKey,
      String gitlabBaseUrl,
      Long projectId,
      String resourceType,
      String resourceId,
      FormActorContext actor) {
    requireAuthenticated(actor);
    requirePermission(actor, PlatformPermissionCodes.FORM_RECORD_CREATE);
    FormVersionContext context =
        contextService.resolve(templateKey, gitlabBaseUrl, projectId, resourceType, resourceId, false);
    Long instanceId = store.findInstanceId(context);
    List<FormRecordRow> rows =
        instanceId == null ? List.of() : store.listActive(context, instanceId);
    List<Long> ids = rows.stream().map(FormRecordRow::id).toList();
    Map<Long, String> creatorNames = store.creatorNames(ids);
    List<FormRecordView> records = new ArrayList<>(rows.size());
    for (FormRecordRow row : rows) {
      records.add(
          viewFactory.toView(
              row, actor, context.writable(), creatorNames.get(row.id())));
    }
    boolean writable = context.writable();
    String displayName =
        identityService.displayNameForUser(actor.userId(), actor.displayName());
    return new FormDetailResponse(
        context.spec().templateKey(),
        context.fieldSpecJson(),
        Long.toString(context.scopeId()),
        Long.toString(context.storageVersionId()),
        context.versionLabel(),
        context.scopeStatus(),
        writable,
        instanceId == null ? null : Long.toString(instanceId),
        List.copyOf(records),
        new FormResourceView(
            context.canonicalGitlabBaseUrl(),
            Long.toString(context.projectId()),
            context.resourceType(),
            Long.toString(context.resourceId()),
            context.rootId() == null ? null : Long.toString(context.rootId()),
            context.webUrl(),
            context.severityLevel()),
        new FormAccountView(actor.userId(), displayName),
        new FormCapabilitiesView(
            writable && actor.hasPermission(PlatformPermissionCodes.FORM_RECORD_CREATE),
            writable && actor.hasPermission(PlatformPermissionCodes.FORM_RECORD_EDIT_OWN),
            writable && actor.hasPermission(PlatformPermissionCodes.FORM_RECORD_DELETE_OWN)));
  }

  /** 新增一条记录；同 key 重放返回 REPLAYED，同走查人返回 CONFIRM_REQUIRED 且不写入。 */
  public FormCreateResponse create(
      FormCreateRequest request, FormActorContext actor, String clientIp, String userAgent) {
    requireAuthenticated(actor);
    requirePermission(actor, PlatformPermissionCodes.FORM_RECORD_CREATE);
    FormVersionContext context =
        contextService.resolve(
            request.templateKey(),
            request.gitlabBaseUrl(),
            request.projectId(),
            request.resourceType(),
            request.resourceId(),
            true);
    requireResourceMirrored(context);
    fieldValueService.requirePayloadSize(request.fields());
    String requestKey = canonicalRequestKey(request.requestKey());
    // 先按 requestKey 在迁移后的当前权威表核验重放：请求可携带已退役的旧结构版本，
    // 指纹按该版本冻结规格计算，因此结构发布不会让“已写入但响应丢失”的提交变成永久冲突。
    FormCreateResponse replay = replayIfPresent(context, request, actor, requestKey);
    if (replay != null) {
      return replay;
    }
    // 目录跨版重放：登记行或其它版本创建的记录按目录 origin 证据核验首次指纹。
    long requestVersionId =
        parsePositiveId(request.storageVersionId(), "结构版本 ID");
    long requestScopeId = parsePositiveId(request.scopeId(), "范围 ID");
    if (requestScopeId != context.scopeId()) {
      throw new BizException(ResultCode.BAD_REQUEST, "范围与资源上下文不一致");
    }
    FormCreateResponse directoryReplay =
        directoryService.replayIfPresent(context, requestVersionId, request.fields(), actor, requestKey);
    if (directoryReplay != null) {
      return directoryReplay;
    }
    // 新提交必须与当前结构版本一致。
    contextService.requireExpected(context, request.scopeId(), request.storageVersionId());
    ValidatedFormValues canonicalValues =
        fieldValueService.validateForReplay(context, request.fields());
    String payloadHash =
        fieldValueService.fingerprint(context, actor.userId(), canonicalValues.canonicalFields());
    // 新记录必须取当前权威人员姓名写入快照。
    ValidatedFormValues values = fieldValueService.validate(context, request.fields());
    try {
      return writeService.create(
          context,
          values,
          actor,
          actorName(actor),
          requestKey,
          request.fields(),
          payloadHash,
          request.confirmSameReviewer(),
          clientIp,
          userAgent);
    } catch (FormRecordWriteService.ReplaySignal signal) {
      return new FormCreateResponse("REPLAYED", signal.record(), null);
    } catch (FormRecordWriteService.ConfirmRequiredSignal signal) {
      return new FormCreateResponse("CONFIRM_REQUIRED", null, signal.reviewerUserId());
    }
  }

  /** 编辑本人记录；旧页面迟到保存按记录版本冲突拒绝，不覆盖其他记录。 */
  public FormRecordView update(
      String id, FormUpdateRequest request, FormActorContext actor, String clientIp, String userAgent) {
    requireAuthenticated(actor);
    requirePermission(actor, PlatformPermissionCodes.FORM_RECORD_EDIT_OWN);
    long recordId = parseId(id);
    Integer expected = request.recordVersion();
    if (expected == null || expected <= 0) {
      throw new BizException(ResultCode.BAD_REQUEST, "记录版本必须为正整数");
    }
    FormVersionContext context =
        resolveWrite(request.templateKey(), request.gitlabBaseUrl(), request.projectId(),
            request.resourceType(), request.resourceId(), request.scopeId(),
            request.storageVersionId());
    fieldValueService.requirePayloadSize(request.fields());
    ValidatedFormValues values = fieldValueService.validate(context, request.fields());
    return writeService.update(
        context,
        recordId,
        expected,
        values,
        actor,
        actorName(actor),
        clientIp,
        userAgent);
  }

  /** 作废本人记录；确认文本与版本同时核验，重复作废只返回已作废状态。 */
  public FormRecordView voidRecord(
      String id, FormVoidRequest request, FormActorContext actor, String clientIp, String userAgent) {
    requireAuthenticated(actor);
    requirePermission(actor, PlatformPermissionCodes.FORM_RECORD_DELETE_OWN);
    if (!request.confirmVoid() || !"作废".equals(request.confirmationText())) {
      throw new BizException(ResultCode.BAD_REQUEST, "作废需要明确确认");
    }
    long recordId = parseId(id);
    Integer expected = request.recordVersion();
    if (expected == null || expected <= 0) {
      throw new BizException(ResultCode.BAD_REQUEST, "记录版本必须为正整数");
    }
    FormVersionContext context =
        resolveWrite(request.templateKey(), request.gitlabBaseUrl(), request.projectId(),
            request.resourceType(), request.resourceId(), request.scopeId(),
            request.storageVersionId());
    return writeService.voidRecord(
        context, recordId, expected, actor, actorName(actor), clientIp, userAgent);
  }

  /** 人员候选按稳定 userId 游标返回；本人候选独立于分页。 */
  public FormReviewerCandidatesResponse reviewerCandidates(
      String keyword, Integer limit, String cursor, FormActorContext actor) {
    requireAuthenticated(actor);
    requirePermission(actor, PlatformPermissionCodes.FORM_RECORD_CREATE);
    return reviewerDirectory.search(keyword, limit, cursor, actor.userId());
  }

  /**
   * 保留 notification-payload 外部四字段契约，并校验来源、项目与资源。
   */
  public CollectFormNotificationPayloadResponse notificationPayload(
      String gitlabBaseUrl, Long projectId, Long requestIid, String resourceType, FormActorContext actor) {
    requireAuthenticated(actor);
    requirePermission(actor, PlatformPermissionCodes.FORM_RECORD_CREATE);
    if (projectId == null || projectId <= 0) {
      throw new BizException(ResultCode.BAD_REQUEST, "Project ID 无效");
    }
    if (requestIid == null || requestIid <= 0) {
      throw new BizException(ResultCode.BAD_REQUEST, "请求类型 IID 无效");
    }
    String type = resourceType == null ? "" : resourceType.trim();
    if (!"merge_request".equals(type) && !"issue".equals(type)) {
      throw new BizException(ResultCode.BAD_REQUEST, "资源类型不能为空或不支持");
    }
    String canonical;
    try {
      canonical = FormSourceUrlSupport.canonicalize(gitlabBaseUrl);
    } catch (IllegalArgumentException error) {
      throw new BizException(ResultCode.BAD_REQUEST, error.getMessage());
    }
    Long sourceConfigId = findSourceConfig(canonical);
    if (sourceConfigId == null) {
      throw new BizException(ResultCode.BAD_REQUEST, "来源地址未登记");
    }
    if (!scopeCovers(sourceConfigId, type, projectId)) {
      throw new BizException(ResultCode.BAD_REQUEST, "该资源未登记表单模板范围");
    }
    Long rootId =
        "merge_request".equals(type)
            ? GitlabResourceRootResolver.findMergeRequestRootId(
                jdbcTemplate,
                GitlabSourceInstanceSupport.DEFAULT_SOURCE_INSTANCE,
                projectId,
                requestIid)
            : GitlabResourceRootResolver.findIssueRootId(
                jdbcTemplate,
                GitlabSourceInstanceSupport.DEFAULT_SOURCE_INSTANCE,
                projectId,
                requestIid);
    if (rootId == null) {
      throw new BizException(ResultCode.BAD_REQUEST, "资源待同步，请稍后重试");
    }
    return new CollectFormNotificationPayloadResponse(
        gitlabBaseUrl.trim(), projectId, requestIid, type);
  }

  /**
   * 同 requestKey 重放核验；命中时按请求携带的冻结版本重算指纹。
   *
   * <p>只查询当前权威记录表：迁移会保留 requestKey 与载荷指纹，因此旧页面携带旧版本也能收敛到
   * 迁移后的同一条记录；未命中返回 {@code null}，由调用方按当前版本继续校验。
   */
  private FormCreateResponse replayIfPresent(
      FormVersionContext context,
      FormCreateRequest request,
      FormActorContext actor,
      String requestKey) {
    FormRecordRow existing = store.findByRequestKey(context, requestKey);
    if (existing == null) {
      return null;
    }
    long scopeId = parsePositiveId(request.scopeId(), "范围 ID");
    if (scopeId != context.scopeId()) {
      throw new BizException(ResultCode.BAD_REQUEST, "范围与资源上下文不一致");
    }
    long versionId = parsePositiveId(request.storageVersionId(), "结构版本 ID");
    FormVersionContext replayContext = contextService.replayContext(context, versionId);
    // 重放只依赖首次写入的规范指纹：人员目录当前姓名变化或人员消失不得阻断已有记录重放。
    ValidatedFormValues canonicalValues =
        fieldValueService.validateForReplay(replayContext, request.fields());
    String payloadHash =
        fieldValueService.fingerprint(
            replayContext, actor.userId(), canonicalValues.canonicalFields());
    return writeService.replayIfPresent(context, actor, requestKey, payloadHash);
  }

  private FormVersionContext resolveWrite(
      String templateKey,
      String gitlabBaseUrl,
      Long projectId,
      String resourceType,
      String resourceId,
      String scopeId,
      String storageVersionId) {
    FormVersionContext context =
        contextService.resolve(
            templateKey, gitlabBaseUrl, projectId, resourceType, resourceId, true);
    contextService.requireExpected(context, scopeId, storageVersionId);
    requireResourceMirrored(context);
    return context;
  }

  private Long findSourceConfig(String canonicalUrl) {
    List<Map<String, Object>> configs =
        jdbcTemplate.queryForList("select id, web_base_url from gitlab_sync_configs order by id");
    for (Map<String, Object> config : configs) {
      Object raw = config.get("web_base_url");
      if (raw == null) {
        continue;
      }
      try {
        if (canonicalUrl.equals(FormSourceUrlSupport.canonicalize(raw.toString()))) {
          return ((Number) config.get("id")).longValue();
        }
      } catch (IllegalArgumentException ignored) {
        // 非法来源配置由范围启动校验报告；此处只匹配合法项。
      }
    }
    return null;
  }

  private boolean scopeCovers(long sourceConfigId, String resourceType, long projectId) {
    List<Map<String, Object>> scopes =
        jdbcTemplate.queryForList(
            """
            select project_ids::text as project_ids from form_template_scopes
             where status = 'ACTIVE' and resource_type = ? and source_config_id = ?
            """,
            resourceType,
            sourceConfigId);
    for (Map<String, Object> scope : scopes) {
      Object raw = scope.get("project_ids");
      if (raw == null) {
        continue;
      }
      try {
        var node = objectMapper.readTree(raw.toString());
        for (var item : node) {
          if (item.canConvertToLong() && item.asLong() == projectId) {
            return true;
          }
        }
      } catch (Exception ignored) {
        // 范围项目集合损坏由启动校验报告。
      }
    }
    return false;
  }

  private void requireResourceMirrored(FormVersionContext context) {
    if (context.rootId() == null) {
      throw new BizException(ResultCode.BAD_REQUEST, "资源待同步，请稍后重试");
    }
  }

  private String actorName(FormActorContext actor) {
    String name = identityService.displayNameForUser(actor.userId(), actor.displayName());
    return name == null || name.isBlank() ? actor.userId() : name;
  }

  private void requireAuthenticated(FormActorContext actor) {
    if (actor == null || actor.userId() == null || actor.userId().isBlank()
        || "guest".equals(actor.userId())) {
      throw new InsufficientAuthenticationException("请先登录");
    }
  }

  private void requirePermission(FormActorContext actor, String permission) {
    if (!actor.hasPermission(permission)) {
      throw new AccessDeniedException("当前账号无权执行该操作");
    }
  }

  private String canonicalRequestKey(String requestKey) {
    if (requestKey == null || requestKey.isBlank()) {
      throw new BizException(ResultCode.BAD_REQUEST, "请求标识不能为空");
    }
    try {
      return UUID.fromString(requestKey.trim()).toString();
    } catch (IllegalArgumentException error) {
      throw new BizException(ResultCode.BAD_REQUEST, "请求标识必须是规范 UUID");
    }
  }

  private long parsePositiveId(String raw, String label) {
    if (raw == null || raw.isBlank()) {
      throw new BizException(ResultCode.BAD_REQUEST, label + "不能为空");
    }
    try {
      long value = Long.parseLong(raw.trim());
      if (value <= 0) {
        throw new NumberFormatException("non-positive");
      }
      return value;
    } catch (NumberFormatException error) {
      throw new BizException(ResultCode.BAD_REQUEST, label + "必须为正整数");
    }
  }

  private long parseId(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new BizException(ResultCode.BAD_REQUEST, "记录 ID 不能为空");
    }
    try {
      long id = Long.parseLong(raw.trim());
      if (id <= 0) {
        throw new NumberFormatException("non-positive");
      }
      return id;
    } catch (NumberFormatException error) {
      throw new BizException(ResultCode.BAD_REQUEST, "记录 ID 必须为正整数");
    }
  }
}
