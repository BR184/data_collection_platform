# 已确认/待审批残留功能记录

## 已删除

1. 评审数据管理的“模板”按钮。
2. `/api/review-data/template` 接口。
3. `ReviewDataTemplateWorkbookService`。

## 先记录，未删除

1. `CollectFormView` 中“空白模板 / 模板内容 / 获取模板”属于外部采集表单链路，是否删除需单独确认。
2. `DatabaseBrowserView` 中“模板编码”是数据库字段展示，不是功能按钮。
3. `CollectFormService` 的 `templateCode` 仍是采集表单领域参数，不等于页面模板功能。

## 核对结论

1. `CollectFormView` 的“模板”相关文案不属于无用功能。老平台同类外部表单链路里存在模板下载入口，例如 `PageHome/ContentComponents/QuestionnaireInfo/ImportUserInfo.vue`、`ModifyUserCurrentSubType.vue`、`SuspendAccount.vue`，对应按钮为“获取模板/获取导入模板”，说明这里是外部表单工作流的一部分。
2. `DatabaseBrowserView` 的“模板编码”是业务字段展示，不是独立功能入口，不应作为无用功能删除。
3. `CollectFormService.templateCode` 是表单记录的业务分流字段，用来区分 `code_review` 等表单类型，不是模板按钮残留。
4. 之前已删除的评审数据“模板”按钮和 `/api/review-data/template`，才是当前确认不需要保留的无用功能。

## 待开发事项

1. 外部采集表单功能仍处于未开发完成状态。当前 `CollectFormView`、`CollectFormService.templateCode`、`collect_form_records.template_code` 等代码和字段属于该链路的半成品，不应在“删除无用功能”清理中误删。
2. 后续继续开发外部采集表单前，需要重新核对老平台外部表单/模板下载/导入链路，确认哪些能力需要 1:1 重构，哪些只是历史管理后台功能。
3. 该事项不等同于评审数据管理“模板”按钮。评审数据管理模板下载已按当前需求删除；外部采集表单链路暂保留并等待后续完整设计和实现。
