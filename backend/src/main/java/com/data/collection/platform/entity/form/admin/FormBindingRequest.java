package com.data.collection.platform.entity.form.admin;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.List;

/**
 * 范围绑定候选。
 *
 * <p>规范 GitLab 基址由服务端按来源配置推导，客户端不能直接提交。
 */
public record FormBindingRequest(
    @NotNull @Positive Long sourceConfigId,
    @NotEmpty List<@NotNull @Positive Long> projectIds,
    @NotNull @Positive Long versionProjectId) {}
