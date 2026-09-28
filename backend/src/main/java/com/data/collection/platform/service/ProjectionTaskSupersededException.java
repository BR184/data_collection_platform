package com.data.collection.platform.service;

/** 投影任务的目标 generation 已被更高代际取代。 */
public class ProjectionTaskSupersededException extends RuntimeException {
  public ProjectionTaskSupersededException(long taskId) {
    super("事实投影任务目标代际已被取代：" + taskId);
  }
}
