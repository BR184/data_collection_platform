package com.data.collection.platform.service;

class GitlabDiffFetchException extends RuntimeException {
  private final boolean retryable;

  GitlabDiffFetchException(String message, boolean retryable) {
    super(message);
    this.retryable = retryable;
  }

  boolean retryable() {
    return retryable;
  }
}
