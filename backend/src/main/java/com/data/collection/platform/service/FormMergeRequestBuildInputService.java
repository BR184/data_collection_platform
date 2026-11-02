package com.data.collection.platform.service;

import com.data.collection.platform.common.exception.FormConflictException;
import com.data.collection.platform.entity.MergeRequestFact;
import com.data.collection.platform.entity.read.FactRevisionTarget;
import com.data.collection.platform.service.form.FormHeadCaptureService;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * MR 走查投影的输入冻结：读取业务值前锁定全部 FORM 侧 head 并绑定到实际候选。
 *
 * <p>与议题消费同一证据语义：未消费 head 必须领取成功；已被他人持有时整份候选让位。已消费 head
 * 生成只读证据，最终公开事务复核修订未漂移。只对已登记 {@code FORM} 权威的 MR 生效，生产
 * {@code COMMENT} 权威保持原评论路径。
 */
@Service
class FormMergeRequestBuildInputService {
  static final String RESOURCE_TYPE = "merge_request";
  static final String TEMPLATE_KEY = "code_review";
  private static final int CHUNK = 500;

  private final JdbcTemplate jdbcTemplate;
  private final FormHeadCaptureService captureService;

  FormMergeRequestBuildInputService(
      JdbcTemplate jdbcTemplate, FormHeadCaptureService captureService) {
    this.jdbcTemplate = jdbcTemplate;
    this.captureService = captureService;
  }

  /**
   * 在读取 MR 走查记录前冻结候选输入证据。
   *
   * @param target 候选发布身份
   * @param sourceInstance 来源实例
   * @param facts 本批 MR 事实
   * @param now 冻结时刻
   * @return 本次领取并绑定的 FORM 证据数量
   */
  int freeze(
      FactRevisionTarget target,
      String sourceInstance,
      Collection<MergeRequestFact> facts,
      LocalDateTime now) {
    if (facts == null || facts.isEmpty()) {
      captureService.completeManifest(target.publicationId());
      return 0;
    }
    Map<Long, Set<Long>> iidsByProject = new LinkedHashMap<>();
    for (MergeRequestFact fact : facts) {
      if (fact.getProjectId() == null || fact.getMergeRequestIid() == null) {
        continue;
      }
      iidsByProject
          .computeIfAbsent(fact.getProjectId(), ignored -> new LinkedHashSet<>())
          .add(fact.getMergeRequestIid());
    }
    List<Authority> authorities = loadAuthorities(sourceInstance, iidsByProject);
    if (authorities.isEmpty()) {
      captureService.completeManifest(target.publicationId());
      return 0;
    }
    List<FormHeadCaptureService.HeadKey> keys = new ArrayList<>(authorities.size());
    for (Authority authority : authorities) {
      keys.add(
          new FormHeadCaptureService.HeadKey(
              authority.projectId(), authority.resourceId(), authority.templateKey()));
    }
    Map<FormHeadCaptureService.HeadKey, FormHeadCaptureService.HeadRow> heads =
        captureService.loadHeads(sourceInstance, RESOURCE_TYPE, keys);
    String worker = FormIssueBuildInputService.workerOf(target);
    List<FormHeadCaptureService.InputEvidence> evidence = new ArrayList<>(authorities.size());
    for (Authority authority : authorities) {
      FormHeadCaptureService.HeadKey key =
          new FormHeadCaptureService.HeadKey(
              authority.projectId(), authority.resourceId(), authority.templateKey());
      FormHeadCaptureService.HeadRow head = heads.get(key);
      if (head == null) {
        throw new FormConflictException(
            "MR " + authority.resourceId() + " 的 " + authority.templateKey()
                + " 缺少变更 head，拒绝无证构建");
      }
      evidence.add(captureService.ensureEvidence(authority.scopeId(), head.id(), worker, now));
    }
    captureService.bindEvidence(target.publicationId(), evidence);
    captureService.completeManifest(target.publicationId());
    return evidence.size();
  }

  private List<Authority> loadAuthorities(
      String sourceInstance, Map<Long, Set<Long>> iidsByProject) {
    List<Authority> result = new ArrayList<>();
    for (Map.Entry<Long, Set<Long>> project : iidsByProject.entrySet()) {
      List<Long> ids = List.copyOf(project.getValue());
      for (int offset = 0; offset < ids.size(); offset += CHUNK) {
        List<Long> chunk = ids.subList(offset, Math.min(offset + CHUNK, ids.size()));
        String placeholders = String.join(", ", java.util.Collections.nCopies(chunk.size(), "?"));
        List<Object> args = new ArrayList<>(chunk.size() + 3);
        args.add(sourceInstance);
        args.add(project.getKey());
        args.add(TEMPLATE_KEY);
        args.addAll(chunk);
        result.addAll(
            jdbcTemplate.query(
                """
                select scope_id, project_id, resource_id, template_key
                  from form_resource_authorities
                 where source_instance = ? and project_id = ? and template_key = ?
                   and resource_type = 'merge_request' and authority = 'FORM'
                   and resource_id in (%s)
                 order by resource_id
                """
                    .formatted(placeholders),
                (rs, rowNum) ->
                    new Authority(
                        rs.getLong("scope_id"),
                        rs.getLong("project_id"),
                        rs.getLong("resource_id"),
                        rs.getString("template_key")),
                args.toArray()));
      }
    }
    return result;
  }

  private record Authority(
      long scopeId, long projectId, long resourceId, String templateKey) {}
}
