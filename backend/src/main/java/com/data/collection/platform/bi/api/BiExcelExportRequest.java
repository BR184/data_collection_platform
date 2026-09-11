package com.data.collection.platform.bi.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import java.util.List;

/**
 * 浏览器请求后端生成图表 Excel 数据表时提交的载荷。
 *
 * <p>前四个字段是下载授权身份（与 PNG 授权一致，服务端据此校验查看+下载权限、页面/图表模板合法性
 * 与来源版本是否为当前发布版）；其余字段是前端按图表语义提取好的表格内容，后端只负责序列化为 .xlsx，
 * 不重算业务数据。</p>
 *
 * @param productVersionId 产品版本稳定 ID
 * @param pageKey 页面键（requirements/design/coding/unit-test/integration-test/system-test）
 * @param chartTemplateId 图表模板稳定 ID
 * @param sourceVersion 页面加载时冻结的来源版本
 * @param title 图表标题，用于工作表名与文件名
 * @param productVersionName 产品版本可读名，写入元信息行；可为空
 * @param explanation 图表业务口径与达标标准说明，写入标题下的说明行；可为空
 * @param headers 表头文本，不可为空
 * @param rows 数据行，单元格为数值或文本；可为空列表
 */
public record BiExcelExportRequest(
    @Positive long productVersionId,
    @NotBlank String pageKey,
    @NotBlank String chartTemplateId,
    @NotBlank String sourceVersion,
    @NotBlank String title,
    String productVersionName,
    String explanation,
    @NotEmpty List<String> headers,
    List<List<Object>> rows) {}
