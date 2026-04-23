# Demo 数据中文化与真实链路清理

更新时间：2026-04-23

## 1. 目标

本轮按“内网数据以中文为主”的要求清理本地 demo 数据：

- 不直接写 `issue_fact` / `merge_request_fact`
- 通过 ODS 或业务源表种子生成数据
- 再调用事实层重建
- 页面最终消费事实层或业务表结果

保留的非中文内容仅限技术标识或专有名词，例如：

- `CC_Product`
- `P1 / P2 / P3`
- `main / dev / release`
- 邮箱、用户名、系统枚举、JSON key 等技术字段

## 2. 已清理的 seed

客户问题：

- `scripts/seed-local-customer-issue-demo-data.sql`
- 用户显示名改为中文
- 标签描述改为中文
- issue 标题与描述改为中文
- 功能名前缀改为 `【工程图导出】`、`【草图约束】`、`【平台登录】` 等中文形式
- 仍通过 ODS issue / label / note 表进入 `issue_fact`

代码走查：

- `scripts/seed-local-statistic-board-demo-data.sql`
- `scripts/seed-local-gitlab-valid-code-review-data.sql`
- MR 标题移除 `feat:` / `fix:` 英文前缀
- 源分支从 `feature/payment-cache` 这类英文业务名改成中文业务名
- 目标分支 `main / dev / release` 保留为技术分支名

评审数据：

- `scripts/seed-local-review-data-management-demo.sql`
- 已经是中文业务数据，本轮重新执行确认

## 3. 执行方式

为避免 Windows PowerShell 管道污染中文，统一使用容器内 `psql -f`：

```powershell
docker cp scripts/seed-local-customer-issue-demo-data.sql qa-flex-postgres:/tmp/seed-local-customer-issue-demo-data.sql
docker exec qa-flex-postgres psql -U qaflex -d qaflex -f /tmp/seed-local-customer-issue-demo-data.sql
```

本轮执行了全部本地 seed，并重建事实：

```text
POST /api/facts/rebuild?scope=all&full=true
```

事实层重建结果：

- `issue_fact`：396 条
- `merge_request_fact`：9 条

## 4. 抽样验证

客户问题 `projectId = 325`：

- `#1201`：`【工程图导出】客户反馈导出后标注位置偏移`
- `#1202`：`【草图约束】客户反馈约束拖拽后尺寸未刷新`
- `#1203`：`【平台登录】客户反馈登录后偶发空白页`
- `function_name` 已生成中文值：`工程图导出 / 草图约束 / 平台登录 / 模块标记 / 字段映射`

代码走查 MR：

- `101`：`支付中心订单查询缓存优化`
- `102`：`订单服务导出字段补齐`
- `103`：`报表平台筛选条件修正`
- 源分支为中文业务名，例如 `支付中心缓存优化`

系统测试 issue：

- 标题、模块、标签均保持中文业务语义

评审数据：

- 标题、评审产物、责任人均保持中文业务语义

## 5. 后续约束

后续新增 demo 数据时，默认遵守：

- 用户能看到的标题、描述、姓名、模块、功能、原因必须优先中文
- 不直接写事实表
- 如果必须保留英文，需属于技术字段、协议字段或专有名词
- 本地灌中文 seed 时不要使用 PowerShell `<` 或管道直连 `psql`
