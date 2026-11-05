package com.data.collection.platform.service.form;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.common.response.ResultCode;
import com.data.collection.platform.entity.form.FormReviewerCandidate;
import com.data.collection.platform.entity.form.FormReviewerCandidatesResponse;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 表单走查人候选与权威姓名快照；离职人员只要姓名仍有效即可选。 */
@Service
public class FormReviewerDirectoryService {

  private static final int DEFAULT_LIMIT = 20;
  private static final int MAX_LIMIT = 100;
  private static final int MAX_KEYWORD_CODE_POINTS = 128;

  private final JdbcTemplate jdbcTemplate;

  public FormReviewerDirectoryService(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /**
   * 按稳定 userId 游标搜索候选。
   *
   * @param keyword 关键词，匹配 userId/姓名/部门
   * @param limit 每页数量，默认20、最大100
   * @param cursor 上一页最后一条 userId
   * @param currentUserId 当前会话账号，用于独立返回本人候选
   * @return 候选分页
   */
  public FormReviewerCandidatesResponse search(
      String keyword, Integer limit, String cursor, String currentUserId) {
    String normalizedKeyword = keyword == null ? "" : keyword.trim();
    if (normalizedKeyword.codePointCount(0, normalizedKeyword.length())
        > MAX_KEYWORD_CODE_POINTS) {
      throw new BizException(ResultCode.BAD_REQUEST, "搜索关键词不能超过128个字符");
    }
    int pageSize = normalizeLimit(limit);
    String normalizedCursor = cursor == null ? "" : cursor.trim();
    if (normalizedCursor.length() > 128) {
      throw new BizException(ResultCode.BAD_REQUEST, "分页游标无效");
    }

    StringBuilder sql =
        new StringBuilder(
            "select user_id, real_name, dept_name from platform_ldap_users "
                + "where btrim(coalesce(real_name, '')) <> ''");
    List<Object> args = new ArrayList<>();
    if (!normalizedKeyword.isEmpty()) {
      String pattern = likePattern(normalizedKeyword);
      sql.append(
          " and (user_id ilike ? escape '\\' or real_name ilike ? escape '\\'"
              + " or coalesce(dept_name, '') ilike ? escape '\\')");
      args.add(pattern);
      args.add(pattern);
      args.add(pattern);
    }
    if (!normalizedCursor.isEmpty()) {
      sql.append(" and user_id > ?");
      args.add(normalizedCursor);
    }
    sql.append(" order by user_id limit ?");
    args.add(pageSize + 1);

    List<FormReviewerCandidate> rows =
        jdbcTemplate.query(
            sql.toString(),
            (rs, rowNum) ->
                new FormReviewerCandidate(
                    rs.getString("user_id"),
                    rs.getString("real_name"),
                    rs.getString("dept_name")),
            args.toArray());
    boolean hasMore = rows.size() > pageSize;
    List<FormReviewerCandidate> items =
        hasMore ? List.copyOf(rows.subList(0, pageSize)) : List.copyOf(rows);
    String nextCursor = hasMore ? items.get(items.size() - 1).userId() : null;
    return new FormReviewerCandidatesResponse(items, nextCursor, findCandidate(currentUserId));
  }

  /** 返回当前权威姓名；用户不存在或姓名为空时拒绝提交。 */
  public String requireRealName(String userId) {
    FormReviewerCandidate candidate = findCandidate(userId);
    if (candidate == null) {
      throw new BizException(ResultCode.BAD_REQUEST, "走查人不存在或姓名为空：" + userId);
    }
    return candidate.realName();
  }

  /** 查询单个候选；姓名为空的用户不构成有效候选。 */
  public FormReviewerCandidate findCandidate(String userId) {
    if (userId == null || userId.isBlank()) {
      return null;
    }
    List<FormReviewerCandidate> rows =
        jdbcTemplate.query(
            """
            select user_id, real_name, dept_name
              from platform_ldap_users
             where user_id = ? and btrim(coalesce(real_name, '')) <> ''
            """,
            (rs, rowNum) ->
                new FormReviewerCandidate(
                    rs.getString("user_id"),
                    rs.getString("real_name"),
                    rs.getString("dept_name")),
            userId.trim());
    return rows.isEmpty() ? null : rows.get(0);
  }

  private int normalizeLimit(Integer limit) {
    if (limit == null) {
      return DEFAULT_LIMIT;
    }
    if (limit <= 0 || limit > MAX_LIMIT) {
      throw new BizException(ResultCode.BAD_REQUEST, "分页数量必须在1到100之间");
    }
    return limit;
  }

  private String likePattern(String keyword) {
    String escaped =
        keyword.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    return "%" + escaped + "%";
  }
}
