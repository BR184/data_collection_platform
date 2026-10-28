package com.data.collection.platform.service.form;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.common.response.ResultCode;
import com.data.collection.platform.domain.form.FormFieldSpec;
import com.data.collection.platform.domain.form.FormSourceUrlSupport;
import com.data.collection.platform.entity.CollectFormNotificationPayloadResponse;
import com.data.collection.platform.entity.form.FormAccountView;
import com.data.collection.platform.entity.form.FormActorContext;
import com.data.collection.platform.entity.form.FormCapabilitiesView;
import com.data.collection.platform.entity.form.FormCreateRequest;
import com.data.collection.platform.entity.form.FormCreateResponse;
import com.data.collection.platform.entity.form.FormDetailResponse;
import com.data.collection.platform.entity.form.FormReadableVersionView;
import com.data.collection.platform.entity.form.FormRecordView;
import com.data.collection.platform.entity.form.FormResourceView;
import com.data.collection.platform.entity.form.FormReviewerCandidatesResponse;
import com.data.collection.platform.entity.form.FormUpdateRequest;
import com.data.collection.platform.entity.form.FormVoidRequest;
import com.data.collection.platform.security.PlatformPermissionCodes;
import com.data.collection.platform.service.GitlabResourceRootResolver;
import com.data.collection.platform.service.GitlabSourceInstanceSupport;
import com.data.collection.platform.service.PlatformIdentityService;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
  private final FormRecordDirectoryReader directoryReader;
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
      FormRecordDirectoryReader directoryReader,
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
    this.directoryReader = directoryReader;
    this.jdbcTemplate = jdbcTemplate;
    this.objectMapper = objectMapper;
  }

  /**
   * detail 只读：返回当前版指针、全部可读权威版本与各自冻结规格，并按目录列出所有版本的
   * 有效记录；每条记录携带自身承载版本与创建版本，业务值按承载版本回表。
   */
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
    FormVersionContext current =
        contextService.resolve(templateKey, gitlabBaseUrl, projectId, resourceType, resourceId, false);
    // 原位 LEGACY/NONE 版本没有实例表，只读展示历史记录。
    boolean writable = current.writable() && current.recordTable() != null;
    FormRecordDirectoryReader.SourceSet sourceSet =
        directoryReader.captureSourceSet(current.scopeId());
    Map<String, JsonNode> versionSpecs = new LinkedHashMap<>();
    List<FormReadableVersionView> readable = new ArrayList<>(sourceSet.versions().size());
    for (FormRecordDirectoryReader.VersionEntry version : sourceSet.versions()) {
      String raw =
          jdbcTemplate.queryForObject(
              "select field_spec::text from form_template_versions where id = ?",
              String.class,
              version.versionId());
      versionSpecs.put(Long.toString(version.versionId()), contextService.readFieldSpec(raw));
      readable.add(
          new FormReadableVersionView(
              Long.toString(version.versionId()),
              versionLabel(version.versionId()),
              version.versionStatus(),
              version.instanceMode(),
              version.fieldSpecHash()));
    }
    List<FormRecordDirectoryReader.LocatedRecord> located =
        directoryReader.listByResource(
            current.scopeId(), current.resourceType(), Long.toString(current.resourceId()),
            current.projectId());
    List<Long> ids =
        located.stream().map(FormRecordDirectoryReader.LocatedRecord::recordId).toList();
    Map<Long, String> creatorNames = store.creatorNames(ids);
    List<FormRecordView> records = new ArrayList<>(located.size());
    for (FormRecordDirectoryReader.LocatedRecord location : located) {
      FormVersionContext storage =
          directoryService.readVersionContext(current, location.storageVersionId());
      FormRecordRow row =
          "NONE".equals(location.instanceMode())
              ? legacyRow(location, storage)
              : store.findByIdAny(storage, location.recordId());
      if (row == null) {
        throw new com.data.collection.platform.common.exception.FormConflictException(
            "目录记录无法按承载版本回表：record=" + location.recordId());
      }
      records.add(
          viewFactory.toView(
              row, actor, writable, creatorNames.get(row.id()),
              Long.toString(location.storageVersionId())));
    }
    String sourceSetHash =
        sourceSet.hash() != null
            ? sourceSet.hash()
            : jdbcTemplate.queryForObject(
                "select form_source_set_hash(?)", String.class, current.scopeId());
    Long currentInstanceId =
        current.instanceTable() == null ? null : store.findInstanceId(current);
    String displayName =
        identityService.displayNameForUser(actor.userId(), actor.displayName());
    return new FormDetailResponse(
        current.spec().templateKey(),
        current.fieldSpecJson(),
        Long.toString(current.scopeId()),
        Long.toString(current.storageVersionId()),
        Long.toString(current.storageVersionId()),
        current.versionLabel(),
        current.scopeStatus(),
        writable,
        currentInstanceId == null ? null : Long.toString(currentInstanceId),
        List.copyOf(readable),
        Map.copyOf(versionSpecs),
        Long.toString(sourceSet.revision()),
        sourceSetHash,
        List.copyOf(records),
        new FormResourceView(
            current.canonicalGitlabBaseUrl(),
            Long.toString(current.projectId()),
            current.resourceType(),
            Long.toString(current.resourceId()),
            current.rootId() == null ? null : Long.toString(current.rootId()),
            current.webUrl(),
            current.severityLevel()),
        new FormAccountView(actor.userId(), displayName),
        new FormCapabilitiesView(
            writable && actor.hasPermission(PlatformPermissionCodes.FORM_RECORD_CREATE),
            writable && actor.hasPermission(PlatformPermissionCodes.FORM_RECORD_EDIT_OWN),
            writable && actor.hasPermission(PlatformPermissionCodes.FORM_RECORD_DELETE_OWN)));
  }

  private String versionLabel(long versionId) {
    return jdbcTemplate.queryForObject(
        "select version_label from form_template_versions where id = ?", String.class, versionId);
  }

  private FormRecordRow legacyRow(
      FormRecordDirectoryReader.LocatedRecord location, FormVersionContext storage) {
    Map<String, Object> fields = new LinkedHashMap<>();
    Map<String, String> fieldNames = new LinkedHashMap<>();
    for (FormFieldSpec field : storage.spec().fields()) {
      Object raw = location.fields().get(field.storage().resolvedColumn());
      fields.put(field.apiKey(), legacyValue(raw, field.type()));
      if (field.storage().nameColumn() != null) {
        Object nameValue = location.fields().get(field.storage().nameColumn());
        fieldNames.put(field.apiKey(), nameValue == null ? null : String.valueOf(nameValue));
      }
    }
    return new FormRecordRow(
        location.recordId(),
        location.instanceId() == null ? -1L : location.instanceId(),
        location.originVersionId(),
        storage.versionLabel(),
        location.createdBy(),
        location.requestKey(),
        location.createPayloadHash(),
        1,
        location.deleted(),
        false,
        List.of(),
        fields,
        fieldNames,
        Map.of(),
        null,
        null);
  }

  private Object legacyValue(Object raw, String type) {
    if (raw == null) {
      return null;
    }
    return switch (type) {
      case "integer" ->
          raw instanceof Number number ? number.intValue() : Integer.valueOf(raw.toString());
      case "boolean" -> raw instanceof Boolean flag ? flag : Boolean.valueOf(raw.toString());
      case "enum-array" -> legacyArray(raw);
      default -> raw instanceof String text ? text : raw.toString();
    };
  }

  private List<String> legacyArray(Object raw) {
    if (!(raw instanceof java.sql.Array array)) {
      return List.of(String.valueOf(raw));
    }
    try {
      Object values = array.getArray();
      if (values instanceof Object[] items) {
        List<String> result = new ArrayList<>(items.length);
        for (Object item : items) {
          result.add(item == null ? null : String.valueOf(item));
        }
        return List.copyOf(result);
      }
      return List.of();
    } catch (java.sql.SQLException error) {
      throw new com.data.collection.platform.common.exception.FormConflictException(
          "旧记录数组列无法读取");
    }
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

  /** 编辑本人记录：按目录承载版本与 expectedStorageVersionId 定位，CAS 失败不覆盖。 */
  public FormRecordView update(
      String id, FormUpdateRequest request, FormActorContext actor, String clientIp, String userAgent) {
    requireAuthenticated(actor);
    requirePermission(actor, PlatformPermissionCodes.FORM_RECORD_EDIT_OWN);
    long recordId = parseId(id);
    Integer expected = request.recordVersion();
    if (expected == null || expected <= 0) {
      throw new BizException(ResultCode.BAD_REQUEST, "记录版本必须为正整数");
    }
    FormVersionContext current =
        contextService.resolve(
            request.templateKey(),
            request.gitlabBaseUrl(),
            request.projectId(),
            request.resourceType(),
            request.resourceId(),
            true);
    fieldValueService.requirePayloadSize(request.fields());
    FormVersionContext storage =
        requireRecordStorage(
            recordId, request.scopeId(), request.expectedStorageVersionId(), current, actor);
    ValidatedFormValues values = fieldValueService.validate(storage, request.fields());
    return writeService.updateStorage(
        storage, recordId, expected, values, actor, actorName(actor), clientIp, userAgent);
  }

  /** 作废本人记录：确认文本与版本同时核验，重复作废只返回已作废状态。 */
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
    FormVersionContext current =
        contextService.resolve(
            request.templateKey(),
            request.gitlabBaseUrl(),
            request.projectId(),
            request.resourceType(),
            request.resourceId(),
            true);
    FormVersionContext storage =
        requireRecordStorage(
            recordId, request.scopeId(), request.expectedStorageVersionId(), current, actor);
    return writeService.voidStorage(
        storage, recordId, expected, actor, actorName(actor), clientIp, userAgent);
  }

  /**
   * 按目录定位记录自身承载版本：范围/完整C/账号/预期的物理版本必须全部一致；
   * originVersionId 不参与选表，旧版记录按自身版本编辑。
   */
  private FormVersionContext requireRecordStorage(
      long recordId,
      String requestScopeId,
      String expectedStorageVersionId,
      FormVersionContext current,
      FormActorContext actor) {
    if (expectedStorageVersionId == null || expectedStorageVersionId.isBlank()) {
      throw new BizException(ResultCode.BAD_REQUEST, "expectedStorageVersionId 不能为空");
    }
    long expectedVersion = parsePositiveId(expectedStorageVersionId, "承载版本 ID");
    long scopeId = parsePositiveId(requestScopeId, "范围 ID");
    if (scopeId != current.scopeId()) {
      throw new BizException(ResultCode.BAD_REQUEST, "范围与资源上下文不一致");
    }
    FormRecordDirectoryService.DirectoryRow directory =
        directoryService
            .findByRecordId(recordId)
            .orElseThrow(() -> new AccessDeniedException("记录缺少目录证据，不能编辑或作废"));
    if (directory.scopeId() != current.scopeId()
        || directory.sourceConfigId() != current.sourceConfigId()
        || !directory.sourceInstance().equals(current.sourceInstance())
        || !directory.gitlabBaseUrl().equals(current.canonicalGitlabBaseUrl())
        || directory.projectId() != current.projectId()
        || !directory.resourceType().equals(current.resourceType())
        || !directory.resourceId().equals(Long.toString(current.resourceId()))) {
      throw new com.data.collection.platform.common.exception.FormConflictException(
          "记录完整资源与当前上下文不一致");
    }
    if (!"MATERIALIZED".equals(directory.instanceMode()) || directory.createdBy() == null) {
      throw new com.data.collection.platform.common.exception.FormConflictException(
          "该记录无创建人证据，只读");
    }
    if (!directory.createdBy().equals(actor.userId())) {
      throw new AccessDeniedException("只能操作本人记录");
    }
    if (directory.storageVersionId() != expectedVersion) {
      throw new com.data.collection.platform.common.exception.FormConflictException(
          "记录承载版本与请求不一致，请刷新后重试");
    }
    return directoryService.storageVersionContext(current, directory.storageVersionId());
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
