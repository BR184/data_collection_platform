# 当前黄金快照更新范围

## 当前状态与待办

| 编号 | 目标 | 完成标准 |
| --- | --- | --- |
| G1 | 确认生成范围 | 2026-11-05用户批准发布，执行新增67份、更新32份；夹具和EXCLUDED保持；补充获批时间处理 |
| G2 | 按范围生成并审阅 | 实际新增/修改与下表逐项核对，意外业务变化先修实现 |
| G3 | 严格复比 | 已通过227/0/0/0；恢复18199及其专属服务 |

代码输入a98cf79e。最新严格compare为227项：29快照差异、26缺失错误、1条目录URL双重编码错误。URL已修，实际HTTP200、目录护栏2/2；目录共缺27份响应、40份表状态。原26缺失错误首先阻断表状态断言，不能当作只有26个文件待生成。

两份走查Excel实际行数2897/356，与f74fdef7快照的完整行多重集合相等，没有记录增删或单元格业务值变化；差异来自现有稳定并列排序。其他主线业务差异按实际失败字段逐项核对；输入签名、权限、分区与表目录按现有代码/迁移合同核对。详细运行证据在本任务临时目录与Git历史。

本次更新不改变业务口径：新增快照记录已实现的端点；更新快照记录表单成功样本的真实副作用、既有主线值和稳定排序。模板目录创建/更新时间按用户批准加入跨运行忽略；失败创建另做全列前后不变断言。用户批准任务时间采用实际领取条件（QUEUED且run_after不晚于数据库当前时间）断言，再忽略绝对时刻；生产调度不变。生成器可能重写其它已有文件，只有已声明易变值的改动恢复为生成前版本；出现额外业务差异停止提交并重新审阅。

## 更新32份

| 快照 | 本次结论 |
| --- | --- |
| `question-metrics/post___illegal-records_refresh-one__first-issue__issue_fact.json` | 成功表单真实消费增加事实修订及输入签名，保留旧修订 |
| `customer-issues/post___illegal-records_refresh-one__first-issue__issue_fact.json` | 成功表单真实消费增加事实修订及输入签名，保留旧修订 |
| `permission-settings/put___roles_{roleCode}__normal-user-two-codes.json` | 替换旧表单权限码并保留主线权限目录；按正式迁移产出生成 |
| `permission-settings/post___restore-defaults__restore.json` | 替换旧表单权限码并保留主线权限目录；按正式迁移产出生成 |
| `gitlab-sync/put___config__golden-update.json` | 冻结来源Web地址及新增表单事实刷新运行记录 |
| `statistic-boards/get___{boardKey}__customer-issue-defect-summary.json` | 保留主线数据异常筛选项，候选14→15 |
| `statistic-boards/get___{boardKey}__customer-issue-delay-issues.json` | 差异业务值与已批准主线f74fdef7逐字段相等 |
| `statistic-boards/get___{boardKey}__customer-issue-customer-statistics.json` | 差异业务值与已批准主线f74fdef7逐字段相等 |
| `analytics-dashboards/get___{dashboardKey}__quality-rd.json` | 差异业务值与已批准主线f74fdef7逐字段相等 |
| `analytics-dashboards/get___{dashboardKey}_rules__quality-rd.json` | 差异业务值与已批准主线f74fdef7逐字段相等 |
| `analytics-dashboards/get___{dashboardKey}_details_{viewKey}__quality-rd-quality-code-review-records.json` | 差异业务值与已批准主线f74fdef7逐字段相等 |
| `quality-board/get___rd_dashboard__cc-default.json` | 差异业务值与已批准主线f74fdef7逐字段相等 |
| `quality-board/get___rd_dashboard__cc-cc2026r4.json` | 差异业务值与已批准主线f74fdef7逐字段相等 |
| `quality-board/get___rd_code-review-records_export__default.json` | 与主线逐行多重集合相等（2897/356行），采用已实现的稳定并列排序 |
| `quality-board/get___rd_code-review-records_export__cc2026r4.json` | 与主线逐行多重集合相等（2897/356行），采用已实现的稳定并列排序 |
| `customer-issues/get___records__delay-default.json` | 差异业务值与已批准主线f74fdef7逐字段相等 |
| `customer-issues/get___records__cc-product-default.json` | 差异业务值与已批准主线f74fdef7逐字段相等 |
| `customer-issues/get___records__delay-page1.json` | 差异业务值与已批准主线f74fdef7逐字段相等 |
| `customer-issues/get___records_export__cc-product.json` | 差异业务值与已批准主线f74fdef7逐字段相等 |
| `code-review/get___multi-board_overview__cc-default.json` | 新增FORM/COMMENT均时分区字段，既有COMMENT统计值保持 |
| `code-review/get___multi-board_overview__dgm-default.json` | 新增FORM/COMMENT均时分区字段，既有COMMENT统计值保持 |
| `code-review/get___multi-board_overview__cc-project-9.json` | 新增FORM/COMMENT均时分区字段，既有COMMENT统计值保持 |
| `permission-settings/get____default.json` | 替换旧表单权限码并保留主线权限目录；按正式迁移产出生成 |
| `facts/get___build-tasks_latest__default.json` | 真实表单消费产生最新定向事实任务，取代原全量任务 |
| `facts/get___issue-diagnostics__default.json` | 延期统计恢复主线280/261；成功样本使有模板回复计数81→82 |
| `gitlab-sync/get___configs__default.json` | 冻结来源Web地址及新增表单事实刷新运行记录 |
| `gitlab-sync/get___status__by-config.json` | 冻结来源Web地址及新增表单事实刷新运行记录 |
| `database-browser/get___tables__default.json` | 登记表单关系，目录43→56；沿服务端可见表清单 |
| `bi/get___customer-issues__default.json` | 差异业务值与已批准主线f74fdef7逐字段相等 |

## 新增67份

| 类型 | 快照 |
| --- | --- |
| 响应 | `collect-forms/get___detail__missing-scope.json` |
| 响应 | `collect-forms/get___detail__golden-record.json` |
| 响应 | `collect-forms/get___exports_records__golden-version.json` |
| 响应 | `collect-forms/get___notification-payload__unregistered-source.json` |
| 响应 | `collect-forms/get___notification-payload__registered-issue.json` |
| 响应 | `collect-forms/get___reviewer-candidates__default.json` |
| 响应 | `collect-forms/post___records__missing-scope.json` |
| 表状态 | `collect-forms/post___records__missing-scope__form_record_audit_logs.json` |
| 表状态 | `collect-forms/post___records__missing-scope__form_change_heads.json` |
| 响应 | `collect-forms/post___records__golden-create.json` |
| 表状态 | `collect-forms/post___records__golden-create__form_s1_v1_instances.json` |
| 表状态 | `collect-forms/post___records__golden-create__form_s1_v1_records.json` |
| 表状态 | `collect-forms/post___records__golden-create__form_record_locations.json` |
| 表状态 | `collect-forms/post___records__golden-create__form_record_audit_logs.json` |
| 表状态 | `collect-forms/post___records__golden-create__form_change_heads.json` |
| 表状态 | `collect-forms/post___records__golden-create__fact_change_heads.json` |
| 表状态 | `collect-forms/post___records__golden-create__sync_runs.json` |
| 响应 | `collect-forms/put___records_{id}__missing-scope.json` |
| 表状态 | `collect-forms/put___records_{id}__missing-scope__form_record_audit_logs.json` |
| 表状态 | `collect-forms/put___records_{id}__missing-scope__form_change_heads.json` |
| 响应 | `collect-forms/put___records_{id}__golden-update.json` |
| 表状态 | `collect-forms/put___records_{id}__golden-update__form_s1_v1_instances.json` |
| 表状态 | `collect-forms/put___records_{id}__golden-update__form_s1_v1_records.json` |
| 表状态 | `collect-forms/put___records_{id}__golden-update__form_record_locations.json` |
| 表状态 | `collect-forms/put___records_{id}__golden-update__form_record_audit_logs.json` |
| 表状态 | `collect-forms/put___records_{id}__golden-update__form_change_heads.json` |
| 表状态 | `collect-forms/put___records_{id}__golden-update__fact_change_heads.json` |
| 表状态 | `collect-forms/put___records_{id}__golden-update__sync_runs.json` |
| 响应 | `collect-forms/post___records_{id}_void__missing-scope.json` |
| 表状态 | `collect-forms/post___records_{id}_void__missing-scope__form_record_audit_logs.json` |
| 表状态 | `collect-forms/post___records_{id}_void__missing-scope__form_change_heads.json` |
| 响应 | `collect-forms/post___records_{id}_void__golden-void.json` |
| 表状态 | `collect-forms/post___records_{id}_void__golden-void__form_s1_v1_instances.json` |
| 表状态 | `collect-forms/post___records_{id}_void__golden-void__form_s1_v1_records.json` |
| 表状态 | `collect-forms/post___records_{id}_void__golden-void__form_record_locations.json` |
| 表状态 | `collect-forms/post___records_{id}_void__golden-void__form_record_audit_logs.json` |
| 表状态 | `collect-forms/post___records_{id}_void__golden-void__form_change_heads.json` |
| 表状态 | `collect-forms/post___records_{id}_void__golden-void__fact_change_heads.json` |
| 表状态 | `collect-forms/post___records_{id}_void__golden-void__sync_runs.json` |
| 响应 | `collect-forms/get___entries__issue.json` |
| 响应 | `collect-forms/get___templates__default.json` |
| 响应 | `collect-forms/post___templates__missing-copy-version.json` |
| 表状态 | `collect-forms/post___templates__missing-copy-version__form_template_definitions.json` |
| 表状态 | `collect-forms/post___templates__missing-copy-version__form_template_scopes.json` |
| 表状态 | `collect-forms/post___templates__missing-copy-version__form_template_scope_drafts.json` |
| 响应 | `collect-forms/get___template-scopes_{scopeId}__missing-scope.json` |
| 响应 | `collect-forms/post___template-scopes__unknown-template.json` |
| 表状态 | `collect-forms/post___template-scopes__unknown-template__form_template_scopes.json` |
| 表状态 | `collect-forms/post___template-scopes__unknown-template__form_template_scope_drafts.json` |
| 响应 | `collect-forms/put___template-scopes_{scopeId}_draft__missing-scope.json` |
| 表状态 | `collect-forms/put___template-scopes_{scopeId}_draft__missing-scope__form_template_scope_drafts.json` |
| 响应 | `collect-forms/post___template-scopes_{scopeId}_preview__missing-scope.json` |
| 表状态 | `collect-forms/post___template-scopes_{scopeId}_preview__missing-scope__form_template_preview_evidence.json` |
| 响应 | `collect-forms/post___template-scopes_{scopeId}_publications__missing-scope.json` |
| 表状态 | `collect-forms/post___template-scopes_{scopeId}_publications__missing-scope__form_template_publications.json` |
| 表状态 | `collect-forms/post___template-scopes_{scopeId}_publications__missing-scope__form_template_versions.json` |
| 响应 | `collect-forms/post___template-scopes_{scopeId}_retire__missing-scope.json` |
| 表状态 | `collect-forms/post___template-scopes_{scopeId}_retire__missing-scope__form_template_scopes.json` |
| 响应 | `collect-forms/delete___template-scopes_{scopeId}__missing-scope.json` |
| 表状态 | `collect-forms/delete___template-scopes_{scopeId}__missing-scope__form_template_scopes.json` |
| 表状态 | `collect-forms/delete___template-scopes_{scopeId}__missing-scope__form_template_scope_drafts.json` |
| 响应 | `collect-forms/get___publications_{publicationId}__missing-publication.json` |
| 响应 | `collect-forms/post___publications_{publicationId}_recover__missing-publication.json` |
| 表状态 | `collect-forms/post___publications_{publicationId}_recover__missing-publication__form_template_publications.json` |
| 响应 | `collect-forms/get___binding-options__default.json` |
| 响应 | `collect-forms/get___version-milestones__unknown-source.json` |
| 响应 | `collect-forms/get___admin_projects__unknown-source.json` |

## 补充批准的表状态

2026-11-05用户批准以下3份表状态，与已批准响应的真实副作用保持一致：

| 快照 | 本次结论 |
| --- | --- |
| `gitlab-sync/put___config__golden-update__gitlab_sync_configs.json` | 固定测试来源 Web 地址，与获批响应一致 |
| `permission-settings/post___restore-defaults__restore__platform_role_permissions.json` | 恢复默认角色权限使用正式迁移后的表单权限 |
| `permission-settings/put___roles_{roleCode}__normal-user-two-codes__platform_role_permissions.json` | 修改角色权限后的全表状态使用正式迁移后的表单权限 |

## 运行边界

黄金使用专属Testcontainers双库与冻结来源。生产服务器部署先用独立端口/独立测试库，生产CC GitLab暂不连接。主线合并、正式包及生产启用仍按各自门禁办理。
