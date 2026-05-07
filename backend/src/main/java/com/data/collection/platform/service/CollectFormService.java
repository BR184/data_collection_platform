package com.data.collection.platform.service;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.entity.CollectFormEditContext;
import com.data.collection.platform.entity.CollectFormDetailResponse;
import com.data.collection.platform.entity.CollectFormNotificationPayloadResponse;
import com.data.collection.platform.entity.CollectFormRecord;
import com.data.collection.platform.mapper.CollectFormRecordMapper;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class CollectFormService {

  private final CollectFormRecordMapper collectFormRecordMapper;
  private final CollectFormAuditService collectFormAuditService;

  public CollectFormService(
      CollectFormRecordMapper collectFormRecordMapper,
      CollectFormAuditService collectFormAuditService) {
    this.collectFormRecordMapper = collectFormRecordMapper;
    this.collectFormAuditService = collectFormAuditService;
  }

  public CollectFormDetailResponse getDetail(
      String gitlabBaseUrl,
      Long projectId,
      String resourceType,
      String resourceId,
      String templateCode) {
    validateContext(gitlabBaseUrl, projectId, resourceType, resourceId, templateCode);
    CollectFormRecord record = collectFormRecordMapper.selectByContext(
        gitlabBaseUrl.trim(),
        projectId,
        resourceType.trim(),
        resourceId.trim(),
        templateCode.trim());
    return record == null ? null : CollectFormDetailResponse.from(record);
  }

  public CollectFormDetailResponse save(
      String gitlabBaseUrl,
      Long projectId,
      Long requestIid,
      String resourceType,
      String resourceId,
      String templateCode,
      String formTitle,
      String reviewer,
      Integer reviewDurationMinutes,
      Integer specificationScore,
      Integer logicScore,
      Integer performanceScore,
      Integer designScore,
      Integer otherScore,
      String remark,
      CollectFormEditContext editContext) {
    validateContext(gitlabBaseUrl, projectId, resourceType, resourceId, templateCode);
    CollectFormRecord record = new CollectFormRecord();
    record.setGitlabBaseUrl(gitlabBaseUrl.trim());
    record.setProjectId(projectId);
    record.setRequestIid(requestIid);
    record.setResourceType(resourceType.trim());
    record.setResourceId(resourceId.trim());
    record.setTemplateCode(templateCode.trim());
    record.setFormTitle(StringUtils.hasText(formTitle) ? formTitle.trim() : defaultTitle(record.getTemplateCode()));
    record.setReviewer(StringUtils.hasText(reviewer) ? reviewer.trim() : "");
    record.setReviewDurationMinutes(normalizeScore(reviewDurationMinutes));
    record.setSpecificationScore(normalizeScore(specificationScore));
    record.setLogicScore(normalizeScore(logicScore));
    record.setPerformanceScore(normalizeScore(performanceScore));
    record.setDesignScore(normalizeScore(designScore));
    record.setOtherScore(normalizeScore(otherScore));
    record.setRemark(StringUtils.hasText(remark) ? remark.trim() : "");
    record.setDeleted(false);
    collectFormRecordMapper.upsert(record);
    CollectFormRecord latest = collectFormRecordMapper.selectByContext(
        record.getGitlabBaseUrl(),
        record.getProjectId(),
        record.getResourceType(),
        record.getResourceId(),
        record.getTemplateCode());
    collectFormAuditService.record("SAVE", latest, editContext);
    return latest == null ? null : CollectFormDetailResponse.from(latest);
  }

  public boolean delete(
      String gitlabBaseUrl,
      Long projectId,
      String resourceType,
      String resourceId,
      String templateCode,
      CollectFormEditContext editContext) {
    validateContext(gitlabBaseUrl, projectId, resourceType, resourceId, templateCode);
    CollectFormRecord existing = collectFormRecordMapper.selectByContext(
        gitlabBaseUrl.trim(),
        projectId,
        resourceType.trim(),
        resourceId.trim(),
        templateCode.trim());
    int updated = collectFormRecordMapper.logicalDelete(
            gitlabBaseUrl.trim(),
            projectId,
            resourceType.trim(),
            resourceId.trim(),
            templateCode.trim());
    if (updated > 0) {
      CollectFormRecord latest = existing == null ? null : collectFormRecordMapper.selectById(existing.getId());
      collectFormAuditService.record("DELETE", latest, editContext);
    }
    return updated > 0;
  }

  public CollectFormDetailResponse updateRecord(
      Long id,
      String formTitle,
      String reviewer,
      Integer reviewDurationMinutes,
      Integer specificationScore,
      Integer logicScore,
      Integer performanceScore,
      Integer designScore,
      Integer otherScore,
      String remark,
      boolean deleted,
      CollectFormEditContext editContext) {
    if (id == null || id <= 0) {
      throw new BizException("记录 ID 无效");
    }
    CollectFormRecord existing = collectFormRecordMapper.selectById(id);
    if (existing == null) {
      throw new BizException("表单记录不存在");
    }

    CollectFormRecord update = new CollectFormRecord();
    update.setId(id);
    update.setFormTitle(StringUtils.hasText(formTitle) ? formTitle.trim() : defaultTitle(existing.getTemplateCode()));
    update.setReviewer(StringUtils.hasText(reviewer) ? reviewer.trim() : "");
    update.setReviewDurationMinutes(normalizeScore(reviewDurationMinutes));
    update.setSpecificationScore(normalizeScore(specificationScore));
    update.setLogicScore(normalizeScore(logicScore));
    update.setPerformanceScore(normalizeScore(performanceScore));
    update.setDesignScore(normalizeScore(designScore));
    update.setOtherScore(normalizeScore(otherScore));
    update.setRemark(StringUtils.hasText(remark) ? remark.trim() : "");
    update.setDeleted(deleted);
    update.setUpdatedAt(LocalDateTime.now());

    collectFormRecordMapper.updateById(update);
    CollectFormRecord latest = collectFormRecordMapper.selectById(id);
    collectFormAuditService.record(deleted ? "DELETE" : "UPDATE", latest, editContext);
    return latest == null ? null : CollectFormDetailResponse.from(latest);
  }

  public CollectFormNotificationPayloadResponse buildNotificationPayload(
      String gitlabBaseUrl,
      Long projectId,
      Long requestIid,
      String resourceType) {
    if (!StringUtils.hasText(gitlabBaseUrl)) {
      throw new BizException("GitLab 来源地址不能为空");
    }
    if (projectId == null || projectId <= 0) {
      throw new BizException("Project ID 无效");
    }
    if (requestIid == null || requestIid <= 0) {
      throw new BizException("请求类型 IID 无效");
    }
    if (!StringUtils.hasText(resourceType)) {
      throw new BizException("资源类型不能为空");
    }
    return new CollectFormNotificationPayloadResponse(
        gitlabBaseUrl.trim(),
        projectId,
        requestIid,
        resourceType.trim());
  }

  private void validateContext(
      String gitlabBaseUrl,
      Long projectId,
      String resourceType,
      String resourceId,
      String templateCode) {
    if (!StringUtils.hasText(gitlabBaseUrl)) {
      throw new BizException("GitLab 来源地址不能为空");
    }
    if (projectId == null || projectId <= 0) {
      throw new BizException("Project ID 无效");
    }
    if (!StringUtils.hasText(resourceType)) {
      throw new BizException("资源类型不能为空");
    }
    if (!StringUtils.hasText(resourceId)) {
      throw new BizException("资源编号不能为空");
    }
    if (!StringUtils.hasText(templateCode)) {
      throw new BizException("模板编码不能为空");
    }
  }

  private Integer normalizeScore(Integer value) {
    return value == null || value < 0 ? 0 : value;
  }

  private String defaultTitle(String templateCode) {
    return "code_review".equals(templateCode) ? "代码走查表" : "采集表单";
  }
}
