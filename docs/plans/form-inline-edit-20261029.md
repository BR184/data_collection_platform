## 进度与中间物

- 阶段：用户已批准原位编辑，现状与隔离已核对；待实施和基础验收。
- 目录/分支/基线：`C:/Users/admin/.codex/worktrees/fill-ui/data_collection_platform`，`codex/form-filling-ui-20261028`，`9622a93a`；原UI计划已收口，设计归product的填写页条目。
- 恢复：先读AGENTS、progress及本计划，执行`git status --short --branch`和`git worktree list`；同一UI任务续做，不操作员工树。
- 需求/成功标准：编辑在对应记录位置展开，保存或取消后恢复同一位置的卡片；新建仍在独立区。已批准视觉不重做，接口/会话/草稿/校验/后端/管理页不改，不进入N3。
- 现状依据：view将全部blocks放在底部，editRecord主动scrollIntoView；startEdit按记录返回既有或新草稿，不需要改变该契约。
- 方案：展示派生数据按记录ID关联编辑块，保留稳定block/field键；新建及无法定位原记录的草稿留独立区，不丢输入；只在编辑完成后按需定位恢复卡片。拒绝复制两份编辑模板或用CSS排序造成DOM与阅读顺序不一致。
- API/协议：无新增；使用既有startEdit/submit/verify/discardBlock和data-record-id/data-block-key/data-field-id。
- 验证：先补保存、取消两个正式UI主流程，复用现有表单定向测试；再跑typecheck/lint/build及真实页面首条原位展开、保存/取消、新建独立区。用户要求基础验收且避免过度防御性测试，不扩大到业务并发矩阵、移动端或黄金。
- 环境：复用本任务预览18187/18094/15612及自有夹具，使用前核对；后台作业关闭，不使用共享/外部。证据归`.tmp/form-inline-edit-20261029/`。
- 下一步/风险：先失败回归，后改渲染与定位；等待中不抢焦点，失败/冲突仍保留编辑块。完成后归入product/progress并删除计划，过程从Git恢复；按职责提交双远端，不覆盖员工并行N2整改。
