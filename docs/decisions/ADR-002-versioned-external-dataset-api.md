# ADR-002: 版本化外部数据集 API

## Status

Accepted

## Date

2026-07-20

## Context

BI 看板以及后续其它平台需要读取数据采集平台的事实和统计数据。现有统计板接口服务于数据采集平台页面，依赖用户会话和页面权限，响应也是动态表格单元格，不能作为稳定的跨平台数据契约。

外部调用还需要满足以下约束：

- 只读，不向数据采集平台回写数据。
- 不把页面登录会话暴露给其它平台。
- 不在源码或数据库中保存调用令牌明文。
- 不让每个新平台重复实现认证和响应协议。
- 保留统计服务内部的业务规则，但不让外部消费者依赖页面 DTO。

## Decision

新增 `/api/external/v1/datasets` 版本化只读 API。

- `/api/external/v1/datasets` 返回当前调用方有权访问的数据集目录。
- `/api/external/v1/datasets/{datasetKey}` 返回一个数据集的 schema 版本、生成时间和强类型 payload。
- 每个数据集通过 `ExternalDatasetProvider` 注册，独立声明参数和字段；公共认证、授权和响应外壳不随数据集复制。
- 使用 `Authorization: Bearer <token>` 的服务间认证。部署配置只保存 token 的 SHA-256 摘要，客户端使用数据集白名单授权。
- 外部 API 默认关闭，只有显式设置 `PLATFORM_EXTERNAL_API_ENABLED=true` 并配置客户端后才开放。
- 外部 API 使用只匹配 `/api/external/**` 的独立无状态 Spring Security 过滤链，禁用 Session、请求缓存与 CSRF；平台 LDAP 登录和浏览器业务接口使用另一条 Session 安全链。
- 开启外部 API 时，客户端 ID、64 位 SHA-256 Token 摘要和非空数据集白名单属于启动前置条件；配置无效时应用直接启动失败。
- 第一批数据集为 `system-test-module-fix-rates`，产品版本参数为 `productVersion`，修复数使用事实字段 `is_fixed=true`，建议类缺陷排除，分母为零的修复率返回 `null`。
- 新增聚合数据集 `bi-dashboard`，一次返回 BI 看板需求中所有展示区域：质量目标、模块修复率、评审分布/密度、系统测试轮次修复、严重程度分布、缺陷原因、申请延期、修复人统计和代码提交趋势。其参数为必填 `productVersion`，以及可选 `codeGranularity=day|week`、`codeSource=all|cc|dgm` 和 `repositoryName`；所有建议类缺陷均在 provider 层排除，分母为零的比率返回 `null`。
- 后续评审数据集必须复用 `review_visible_*` 统一读模型：正式评审表与尚未交接、未映射的 `review_data_match_mode_*` 兼容快照按稳定来源键合并，不能丢失仅存在于老平台的历史评审数据。
- 外部平台应通过自己的后端调用该 API；不要求数据采集平台开放浏览器跨域访问。

## Alternatives Considered

### 直接复用 `/api/statistic-boards/*`

拒绝。该接口依赖用户登录和页面权限，响应字段随页面表格定义变化，且缺少 BI 所需的独立 P1/P2 修复数。

### 每个消费者单独增加一个接口

拒绝。认证、错误格式、版本治理和授权会重复实现，后续维护容易产生多个不一致的外部契约。

### 匿名公开 JSON 接口

拒绝。数据包含内部研发质量信息，不应因独立 BI 部署而失去访问控制。

## Consequences

正面影响：

- BI 和后续平台共享一套认证、目录、版本与错误协议。
- 数据集统计逻辑仍在服务端，消费者不能通过页面四舍五入值反推业务事实。
- 新增数据集只需实现 provider 并声明 schema，不影响已有页面接口。

运维要求：

- 为每个消费者生成独立 token，保存其 SHA-256 摘要并限制数据集范围。
- 令牌轮换通过部署配置完成；当前版本不提供网页端令牌管理入口。
- 生产环境必须通过 HTTPS 或内网受控链路调用。

## Initial API Contract

生成调用令牌摘要（令牌本身只交给调用方保管）：

```text
printf '%s' 'replace-with-a-random-token' | sha256sum
```

部署配置示例（Spring Boot 环境变量绑定）：

```text
PLATFORM_EXTERNAL_API_ENABLED=true
PLATFORM_EXTERNAL_API_CLIENTS_0_CLIENT_ID=bi-dashboard
PLATFORM_EXTERNAL_API_CLIENTS_0_TOKEN_SHA256=<sha256>
PLATFORM_EXTERNAL_API_CLIENTS_0_ALLOWED_DATASETS_0=bi-dashboard
# 如需兼容旧调用方，可额外配置：
# PLATFORM_EXTERNAL_API_CLIENTS_0_ALLOWED_DATASETS_1=system-test-module-fix-rates
```

数据集目录：

```http
GET /api/external/v1/datasets
Authorization: Bearer <token>
```

模块修复率数据：

```http
GET /api/external/v1/datasets/system-test-module-fix-rates?productVersion=CC2026R4
Authorization: Bearer <token>
```

BI 看板聚合数据：

```http
GET /api/external/v1/datasets/bi-dashboard?productVersion=CC2026R4&codeGranularity=week
Authorization: Bearer <token>
```

`bi-dashboard` 的 `payload` 包含以下稳定 section：

- `qualityTargets.metrics[]`：质量目标及达成状态。
- `availableProductVersions[]`：可供 BI 切换的启用产品版本目录。
- `moduleFixRates[]`：各模块系统测试修复率。
- `reviewDistributions`：按评审类型、问题类别和评审规模的分布/密度。
- `phaseFixes`、`severityDistribution`、`defectCauseDistribution`、`delayedDefects`：系统测试各图表/表格数据。
- `fixUsers[]`：按实际 `fix_user` 的一级、二级、三级、已修复、未修复和总数。
- `codeSubmissionTrend`：按日或周的新增代码、累计新增代码和合并请求数；兼容模式读取兼容走查数据，非兼容模式读取正式合并请求事实表。

该接口只返回看板展示所需聚合数据，不提供下钻、明细列表或文件导出能力。

成功响应仍使用平台统一的 `ApiResponse` 外壳，`data` 中包含 `datasetKey`、`schemaVersion`、UTC `generatedAt` 和 `payload`。`payload.modules[].*.fixRatePercent` 为数值；分母为零时为 `null`，调用方应按自身展示规范显示 `/`，不能显示为 0。
