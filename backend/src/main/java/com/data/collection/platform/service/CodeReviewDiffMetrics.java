package com.data.collection.platform.service;

import java.net.URI;

record CodeReviewDiffMetrics(
    int addedLines,
    int deletedLines,
    URI sourceUri,
    String rawPayload) {}
