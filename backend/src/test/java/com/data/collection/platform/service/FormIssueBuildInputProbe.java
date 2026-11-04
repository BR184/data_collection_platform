package com.data.collection.platform.service;

/**
 * 黄金夹具对议题输入签名的只读探针。
 *
 * <p>{@link FormIssueBuildInputService} 是包内服务；黄金测试位于独立的 golden 包，只能通过本
 * 探针调用生产同一份冻结输入实现，不能手写哈希或复制签名规则。
 */
public final class FormIssueBuildInputProbe {

  private FormIssueBuildInputProbe() {}

  /** 返回服务类型，供测试上下文按类型取得真实 Bean。 */
  public static Class<?> beanType() {
    return FormIssueBuildInputService.class;
  }

  /**
   * 读取议题当前输入签名。
   *
   * @param bean 生产上下文中的 {@link FormIssueBuildInputService} 实例
   * @param sourceInstance 来源实例
   * @param projectId 项目
   * @param issueIid 议题 IID
   * @return 长度为 2 的数组：[状态名, 签名]；状态非 OK 时签名为 null
   */
  public static String[] statusAndHash(
      Object bean, String sourceInstance, long projectId, long issueIid) {
    FormIssueBuildInputService.Snapshot snapshot =
        ((FormIssueBuildInputService) bean).current(sourceInstance, projectId, issueIid);
    return new String[] {snapshot.status().name(), snapshot.hash()};
  }
}
