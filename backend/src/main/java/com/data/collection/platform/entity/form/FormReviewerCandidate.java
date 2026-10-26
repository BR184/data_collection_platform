package com.data.collection.platform.entity.form;

/** 人员候选；userId 是稳定业务身份，realName 是当时的权威姓名。 */
public record FormReviewerCandidate(String userId, String realName, String deptName) {}
