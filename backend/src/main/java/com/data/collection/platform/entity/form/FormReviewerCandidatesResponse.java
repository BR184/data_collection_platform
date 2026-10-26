package com.data.collection.platform.entity.form;

import java.util.List;

/** 人员候选分页；currentUserCandidate 独立于分页结果。 */
public record FormReviewerCandidatesResponse(
    List<FormReviewerCandidate> items,
    String nextCursor,
    FormReviewerCandidate currentUserCandidate) {}
