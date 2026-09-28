package com.data.collection.platform.entity.statistics;

/**
 * 单个成员候选。
 *
 * <p>类型与取值成对下发，因此不存在"用哨兵值表达缺失"的歧义：缺失成员是
 * {@code kind = MISSING}（{@code value} 为空串），真实成员是 {@code kind = VALUE} 并携带成员名——
 * 哪怕成员名恰好是 {@code __missing__} 或"未标注客户"也照样是普通取值。
 *
 * @param kind 成员类型，与 {@code CustomerIssueFactQueryService.SelectionKind} 同名（VALUE / MISSING）
 * @param value 请求参数值；MISSING 为空串，VALUE 为成员名本身
 * @param label 展示标签；缺失成员使用"未标注…"文案
 */
public record StatisticBoardControlOption(String kind, String value, String label) {}
