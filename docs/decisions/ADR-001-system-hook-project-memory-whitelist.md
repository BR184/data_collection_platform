# ADR-001: System Hook 项目内存白名单拦截

## 状态
Accepted

## 日期
2026-05-20

## 背景
平台需要从 GitLab 增量感知变更，并尽量避免传统 Webhook 的并发、重复投递、项目级配置漂移和批量维护问题。内网 GitLab 项目数量很大，其中包含大量不需要纳入数据采集的僵尸项目。实际业务只希望监听少量指定项目的事件，并将这些事件转换成精确同步任务。

GitLab System Hook 是实例级入口。它不会在每次提交时打包全部项目信息；一次事件只生成一次事件 payload，payload 包含该事件相关的项目、用户、提交、Issue、Merge Request 等信息。项目总量本身不会使单次 payload 变大，但所有项目中被启用事件类型命中的事件都会投递到平台。

因此，平台侧必须在 System Hook 入口做极早期项目过滤，避免非目标项目进入落库、去重、同步规划、源库直连查询和镜像写入链路。

## 决策
继续使用 GitLab System Hook 作为统一入口，并在平台侧实现项目内存白名单拦截。

拦截位置应放在 `GitlabSystemHookService.accept()` 中，密钥校验通过后、写入 `gitlab_system_hook_events` 和 `gitlab_hook_events` 前。处理顺序为：

1. 根据 System Hook Secret 解析到启用的 `GitlabSyncConfig`。
2. 校验 Secret。
3. 从 payload 提取 `project_id`。
4. 使用内存白名单判断该项目是否允许处理。
5. 不在白名单时直接返回成功响应，不落库、不进入去重、不提交 `SYSTEM_HOOK` 同步运行。
6. 在白名单内时才按现有链路写入 hook event、去重、规划精确同步任务。

白名单数据来源优先使用平台配置字段，例如现有 `system_hook_project_id` 可作为单项目配置的兼容入口；后续可扩展为多个项目 ID 的配置项。运行时应构建内存集合，例如 `Set<Long>`，用于 O(1) 判断。

## 行为约束
- 非白名单项目事件必须返回 HTTP 200，避免 GitLab 认为投递失败并重试。
- 非白名单项目事件不应写入同步日志主表，避免日志被无关项目刷屏。
- 非白名单项目事件默认不写入 `gitlab_system_hook_events`，除非显式开启采样审计。
- 白名单为空时的语义必须明确：
  - 如果 System Hook 已启用但没有配置项目白名单，推荐视为拒绝全部项目，以避免误接全量实例事件。
  - 若需要兼容旧行为，应提供显式开关表示“允许全部项目”。
- 白名单判断应在任何昂贵操作前完成，包括 JSON 大对象持久化、去重查询、同步运行提交、源库直连查询。

## 压力边界
System Hook 的 GitLab 侧压力主要由事件数量决定，而不是项目总量决定。

示例：
- GitLab 有 10000 个项目。
- 只有 20 个项目高频提交。
- 每次提交只会产生对应项目的事件 payload，不会携带 10000 个项目。
- 如果这 20 个项目都在白名单内，平台会处理这些事件。
- 如果其中部分项目不在白名单内，平台会在入口快速返回，后续同步链路无压力。

平台侧白名单不能消除 GitLab 生成 payload 和发起 HTTP 请求的成本，但可以避免平台数据库、同步调度器、直连 GitLab PostgreSQL 和镜像写入承受非目标项目压力。

## 实现建议
新增一个小型服务，例如 `GitlabSystemHookProjectWhitelistService`：

- 输入：`GitlabSyncConfig`、`projectId`。
- 输出：`allowed / ignored`。
- 内部维护按 `configId` 缓存的 `Set<Long>`。
- 配置变更后刷新缓存，或用短 TTL 避免重启依赖。
- 记录低频 debug 日志或指标计数，例如 `system_hook_ignored_by_project_whitelist`。

入口伪代码：

```java
GitlabSyncConfig config = configService.getConfigForSystemHook(secret);
validateSecret(config, secret);
Long projectId = extractProjectId(payload);
if (!projectWhitelistService.isAllowed(config, projectId)) {
  log.debug("System Hook ignored by project whitelist, configId={}, projectId={}", config.getId(), projectId);
  return;
}
persistAndDispatch(config, eventType, payload);
```

配置建议：

```text
systemHookEnabled=true
systemHookSecret=...
systemHookProjectIds=123,456,789
systemHookAllowAllProjects=false
```

如果短期只支持一个项目，可以先复用 `system_hook_project_id`：

```text
systemHookProjectId=123
```

## 可观测性
为了确认白名单有效，建议补充以下信息：

- System Hook 接收总数。
- 被项目白名单忽略的数量。
- 进入同步规划的数量。
- 每个 `project_id` 的近期命中计数，建议只保留 top N 或采样，避免高基数指标拖垮监控。
- 同步日志中继续保留白名单内事件的 `System Hook 已唤醒同步：...` 信息。

## 替代方案
### Project Hook
优点是 GitLab 侧只对目标项目投递，天然减少无关事件。缺点是项目多时配置维护复杂，容易出现漏配、重复配置、项目迁移后配置漂移，并且用户已经遇到 Webhook 并发和配置管理风险。

结论：不作为主路径。

### GitLab 侧 System Hook 过滤
如果 GitLab 版本或插件能力支持实例级事件过滤，可以减少 GitLab 侧 HTTP 投递。当前不能依赖此能力作为通用方案。

结论：可作为后续优化，但平台侧仍需白名单保护。

### 平台侧落库后异步过滤
优点是审计完整。缺点是无关事件仍会写数据库、参与去重和日志链路，无法解决平台压力问题。

结论：不采用。

## 后果
- 平台可以继续享受 System Hook 的统一配置和较低维护成本。
- 非目标项目不会进入同步运行链路，显著降低平台侧压力。
- GitLab 仍会为非目标项目事件产生一次 HTTP 投递成本。
- 需要在配置页明确项目白名单语义，避免用户误以为 System Hook 已在 GitLab 侧过滤。
- 后续实现时应补单测和真实链路测试：白名单内项目生成 `SYSTEM_HOOK` run，白名单外项目返回成功但不生成 run。
