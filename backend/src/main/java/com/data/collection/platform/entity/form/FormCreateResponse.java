package com.data.collection.platform.entity.form;

/** 新增结果；CONFIRM_REQUIRED 不构成保存成功且本次不写入记录。 */
public record FormCreateResponse(String outcome, FormRecordView record, String reviewerUserId) {}
