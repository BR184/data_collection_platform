package com.huayun.service;

import com.huayun.entity.mergeRequest.MergeRequest;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** 从同一MR API响应解析业务元数据，不依赖网页语言或CSS结构。 */
public final class GitLabMergeRequestMetadata {
    private final String projectName;
    private final String status;
    private final String mergedTime;
    private final String mergedBy;

    private GitLabMergeRequestMetadata(String projectName, String status, String mergedTime, String mergedBy) {
        this.projectName = projectName;
        this.status = status;
        this.mergedTime = mergedTime;
        this.mergedBy = mergedBy;
    }

    /**
     * 读取API状态、首个“项目：”标签及合并证据。开放状态沿用OPEN，关闭状态沿用CLOSED。
     * 合并时间按API偏移解析后转为服务器本地时间，保留原库的秒级格式；缺失状态/标签或已合并时间时拒绝解析。
     */
    public static GitLabMergeRequestMetadata from(MergeRequest mr) {
        if (mr == null || mr.getState() == null || mr.getLabels() == null) {
            throw new IllegalArgumentException("MR API缺少状态或标签，不能使用网页默认值代替");
        }
        String status;
        switch (mr.getState()) {
            case "opened": status = "OPEN"; break;
            case "closed": status = "CLOSED"; break;
            case "merged": status = "MERGED"; break;
            default: throw new IllegalArgumentException("MR API返回不支持的状态：" + mr.getState());
        }
        String projectName = "未标注项目名";
        for (String label : mr.getLabels()) {
            if (label == null) continue;
            int colon = label.indexOf('：');
            if (colon >= 0 && "项目".equals(label.substring(0, colon))) {
                projectName = label.substring(colon + 1).trim();
                break;
            }
        }
        String mergedTime = "";
        String mergedBy = "";
        if ("MERGED".equals(status)) {
            if (mr.getMergedAt() == null || mr.getMergedAt().trim().isEmpty()) {
                throw new IllegalArgumentException("已合并MR缺少merged_at，不能写成空合并时间");
            }
            mergedTime = OffsetDateTime.parse(mr.getMergedAt())
                    .atZoneSameInstant(ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            if (mr.getMergedBy() != null && mr.getMergedBy().getName() != null) {
                mergedBy = mr.getMergedBy().getName();
            }
        }
        return new GitLabMergeRequestMetadata(projectName, status, mergedTime, mergedBy);
    }

    /** 返回原项目名称字段的业务值。 */
    public String getProjectName() { return projectName; }
    /** 返回原库使用的OPEN/CLOSED/MERGED状态。 */
    public String getStatus() { return status; }
    /** 返回原库格式的本地合并时间；未合并为空串。 */
    public String getMergedTime() { return mergedTime; }
    /** 返回API合并人的显示名；未合并或无合并人证据时为空串。 */
    public String getMergedBy() { return mergedBy; }
}
