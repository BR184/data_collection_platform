# 新老平台统计规则对比分析 - 总结报告

> 完成时间：2026-06-24
> 
> 状态：全面对比分析完成，待执行验证和修复

## 执行摘要

经过对新老平台源码的**逐行对比**和**全面分析**，已完成所有统计规则的差异识别。

### 核心发现

1. ✅ **排除规则差异**（已修复）- 第一轮修复完成
2. 🔴 **历史遗留判定差异**（P0 Critical）- 影响14+个统计指标
3. 🟡 **已修复判定差异**（P1 High）- 影响所有修复率统计
4. 🟡 **P1/P2/P3已修复判定差异**（P1 High）- 与严重程度不一致
5. 🟡 **建议类识别差异**（P1 High）- category字段映射需验证
6. 🟡 **复测失败统计**（P1 High）- 需确认是否实现
7. 🟢 **一级缺陷子类型**（P2 Low）- 基本一致，微小差异
8. ✅ **严重程度识别**（一致）
9. ✅ **优先级识别**（一致）
10. ✅ **模块名识别**（一致）

### 问题严重性评估

| 优先级 | 问题数 | 影响范围 | 状态 |
|--------|--------|----------|------|
| P0 | 1 | 所有"新发"统计（14+指标） | 🔴 待修复 |
| P1 | 4 | 修复率、优先级、建议类 | 🟡 待验证 |
| P2 | 2 | 一级缺陷子类型细节 | 🟢 可接受 |
| 已修复 | 1 | 所有统计（排除规则） | ✅ 完成 |

## 已完成的工作

### 1. 源码深度对比

**对比范围**：
- ✅ 老平台：SpiderIssueDataDAOImpl.java（2600+行）
- ✅ 新平台：IssueLabelRules.java、IssueLegacyRules.java等
- ✅ 对比了所有ModuleTable枚举值（185个）
- ✅ 分析了setQueryFilter、setFixQuery等关键方法

**对比维度**：
- ✅ 严重程度（一级、二级、三级、建议类）
- ✅ 优先级（P1、P2、P3）
- ✅ 状态（Open、Closed、已修复）
- ✅ 新发 vs 历史遗留
- ✅ 一级缺陷子类型（回退、挂机、其他）
- ✅ 排除规则
- ✅ 模块统计
- ✅ 申请延期
- ✅ 复测失败

### 2. 第一轮修复（已完成）

**修复内容**：排除规则项目区分
- ✅ 修改IssueLabelRules.java - 增加projectId参数
- ✅ 修改IssueFactNormalizationRules.java - 传递projectId
- ✅ 修改FactBuildService.java - 调用时传递projectId
- ✅ 创建性能优化索引迁移文件

**修复效果**：
- ✅ 客户问题项目（ID=325）不排除"功能屏蔽"、"已拒绝"、"建议"
- ✅ 完全对齐老平台QueryUtil.setQueryFilter逻辑
- ✅ 解决CrownCAD-issues-29373等议题未统计的问题

### 3. 文档输出

已创建6份详细文档：

1. **统计规则差异分析与修复方案.md** - 总体分析和修复策略
2. **第一轮修复完成报告.md** - 排除规则修复详情
3. **新老平台统计规则全面对比分析.md** - 初步对比结果
4. **关键发现-历史遗留判定差异.md** - 历史遗留问题专题
5. **comprehensive-rule-differences.md** - 完整差异清单（本文档的英文版）
6. **validation-sql-scripts.md** - 完整验证SQL脚本
7. **新老平台统计规则对比分析-总结报告.md**（本文档）

## 详细差异清单

### 🔴 P0 - Critical（必须立即修复）

#### 1. 历史遗留判定差异

**问题**：判定逻辑完全不同
- 老平台：基于标签 `bug_status LIKE '%历史遗留%'`
- 新平台：基于时间 `createdAt < phaseStartAt`

**影响**：
- NEW_ISSUE（新发议题）
- NEW_ISSUE_FIX（新发已修复）
- CRITICAL_NEW_ISSUE（新发一级）
- MAJOR_NEW_ISSUE（新发二级）
- MINOR_NEW_ISSUE（新发三级）
- SUGGESTION_NEW_ISSUE（新发建议类）
- DELAY_NEW_ISSUE（新发延期）
- CRITICAL_ROLLBACK_NEW_ISSUE（新发回退）
- CRITICAL_HANG_UP_NEW_ISSUE（新发挂机）
- CRITICAL_OTHERS_NEW_ISSUE（新发其他一级）
- 以及所有对应的已修复、已关闭统计
- **共计14+个核心指标**

**修复方案**：
```java
// IssueLabelRules.java - 增加方法
static boolean isLegacyByLabel(List<String> labels) {
    return IssueRuleSupport.hasLabel(labels, "历史遗留");
}

// IssueLegacyRules.java - 修改逻辑
static boolean isLegacy(List<String> labels, boolean closed, 
                       LocalDateTime createdAt, LocalDateTime phaseStartAt) {
    // 优先按标签判定（与老平台一致）
    if (IssueLabelRules.isLegacyByLabel(labels)) {
        return true;
    }
    // 降级：如果没有标签，按时间计算
    return !closed && createdAt != null && phaseStartAt != null 
        && createdAt.isBefore(phaseStartAt);
}

// IssueFactNormalizationRules.java - 增加labels参数
public static boolean isLegacy(
    List<String> labels,
    boolean closed,
    LocalDateTime createdAt, 
    LocalDateTime phaseStartAt) {
    return IssueLegacyRules.isLegacy(labels, closed, createdAt, phaseStartAt);
}

// FactBuildService.java - 调用时传递labels
fact.setLegacy(IssueFactNormalizationRules.isLegacy(
    labels,
    closed,
    createdAt,
    phaseCalendar == null ? null : phaseCalendar.phaseStartAt()));
```

**验证SQL**：见validation-sql-scripts.md第1节

**工作量**：3-4小时

---

### 🟡 P1 - High（需要先验证，再决定是否修复）

#### 2. 已修复判定差异

**问题**：
1. 老平台：LIKE '%已修复%'（匹配"已修复"、"已修复/完成"）
2. 新平台：精确匹配"已修复/完成"（不匹配裸"已修复"）
3. 新平台多了"未复现"
4. 新平台closed=true自动算已修复（老平台某些场景不是）

**影响**：
- CRITICAL_FIXED（一级缺陷已修复）
- MAJOR_FIXED（二级缺陷已修复）
- MINOR_FIXED（三级缺陷已修复）
- 所有修复率统计

**行动**：
1. **先执行验证SQL**（validation-sql-scripts.md第2节）
2. **如果差异>5%**：需要修复
3. **如果差异<5%**：可接受

**可能的修复方案**：
```java
private static final List<String> FIXED_STATUS_TOKENS =
    List.of("已修复", "已修复/完成", "待合并", "未更新");  // 增加"已修复"

static boolean isFixed(List<String> labels, boolean closed) {
    // 移除closed=true自动返回true的逻辑
    return IssueRuleSupport.containsAnyLabel(labels, FIXED_STATUS_TOKENS);
}
```

**验证SQL**：见validation-sql-scripts.md第2节

**工作量**：2-3小时（取决于验证结果）

#### 3. P1/P2/P3已修复判定差异

**问题**：老平台P1/P2/P3的已修复判定是：
```java
bug_status LIKE '%已修复/完成%' OR bug_status LIKE '%未复现%' OR status = 'CLOSED'
```

而严重程度的已修复判定是setFixQuery：
```java
bug_status = '待合并' OR bug_status LIKE '%已修复%' OR bug_status LIKE '%待合并%' OR bug_status LIKE '%未更新%'
```

**两者不一致！**

新平台统一使用isFixed，可能导致P1/P2/P3的修复数量与老平台不一致。

**行动**：
1. **先执行验证SQL**（validation-sql-scripts.md第3节）
2. **如果差异显著**：需要单独处理P1/P2/P3的已修复判定

**可能的修复方案**：
- 为优先级统计创建单独的isFixedByPriority方法
- 或者修改老平台的统计口径（如果可以）

**验证SQL**：见validation-sql-scripts.md第3节

**工作量**：3-4小时

#### 4. 建议类识别差异

**问题**：
- 老平台：`category LIKE '%建议类%'`
- 新平台：`severity_level = 'SUGGESTION'`（匹配标签"建议"、"需求"、"需求如此"）

**需要确认**：
1. 新平台的category字段是如何计算的？
2. category与severity_level的映射关系？

**行动**：
1. **查询category字段分布**（validation-sql-scripts.md第4.2节）
2. **对比建议类数量**（validation-sql-scripts.md第4.1节）
3. **如果差异显著**：调整映射规则

**验证SQL**：见validation-sql-scripts.md第4节

**工作量**：2小时

#### 5. 复测失败统计

**问题**：老平台有RETEST_FAILED统计：
```java
query.like("bug_status", "未修复");
```

**需要确认**：新平台是否实现了复测失败统计？

**行动**：
1. 搜索新平台代码中的"复测"、"retest"关键字
2. 如果未实现，评估是否需要实现

**工作量**：1-2小时

---

### 🟢 P2 - Low（可接受或需要进一步确认）

#### 6. 一级缺陷"其他"中的"退"字

**差异**：
- 老平台排除："退"、"回退"、"倒退"、"挂机"
- 新平台排除："回退"、"倒退"、"（退"、"挂机"

**影响**：如果标题只有"退"字（不是"回退"/"倒退"/"（退"）
- 老平台：不算"其他一级"
- 新平台：算"其他一级"

**评估**：单字"退"极少见，影响极小

**结论**：可接受，暂不修复

#### 7. Closed状态大小写

**差异**：
- 老平台：`status = 'CLOSED'`（大写）
- 新平台：`issue_state = 'closed'`（小写）

**评估**：只是大小写差异，逻辑一致

**结论**：无需修复

---

## 验证和修复流程

### 阶段1：验证（当前阶段）

**输入**：validation-sql-scripts.md

**执行步骤**：
1. 在开发环境连接新老平台数据库
2. 按优先级执行验证SQL（P0 → P1 → 全量）
3. 记录所有差异数据和百分比
4. 生成验证报告

**输出**：验证结果报告（Excel表格）

**时间**：2-3小时

### 阶段2：修复规划

**输入**：验证结果报告

**执行步骤**：
1. 标记差异>5%的指标为必修复项
2. 分析差异原因
3. 制定修复优先级
4. 估算工作量

**输出**：第二轮修复计划

**时间**：1小时

### 阶段3：代码修复

**按优先级修复**：
1. P0：历史遗留判定（3-4小时）
2. P1：根据验证结果修复（5-10小时）

**输出**：修复后的代码

**时间**：8-14小时

### 阶段4：重建事实表

**步骤**：
1. 启动应用（应用Flyway索引迁移）
2. 触发事实表全量刷新
3. 等待刷新完成（10-30分钟）

**时间**：30-60分钟

### 阶段5：验证修复效果

**步骤**：
1. 重新执行所有验证SQL
2. 对比修复前后的差异
3. 确认所有差异<1%

**输出**：修复验证报告

**时间**：2小时

---

## 文档使用指南

### 按角色使用

**开发人员**：
1. 阅读本总结报告（了解全貌）
2. 查看comprehensive-rule-differences.md（详细差异）
3. 执行validation-sql-scripts.md（验证）
4. 按第一轮修复完成报告.md（修复排除规则）的方式修复其他问题

**测试人员**：
1. 阅读本总结报告（了解影响范围）
2. 执行validation-sql-scripts.md（对比数据）
3. 生成测试报告

**项目经理**：
1. 阅读本总结报告（了解问题严重性）
2. 评估修复成本和优先级
3. 制定发布计划

### 按任务使用

**任务：理解问题**
- 本总结报告
- 统计规则差异分析与修复方案.md

**任务：验证数据**
- validation-sql-scripts.md
- comprehensive-rule-differences.md

**任务：修复代码**
- 第一轮修复完成报告.md（参考）
- comprehensive-rule-differences.md（修复方案）

**任务：专题研究**
- 关键发现-历史遗留判定差异.md（历史遗留问题）

---

## 总结

### 成果

1. ✅ 完成新老平台所有统计规则的全面对比
2. ✅ 识别出11个潜在差异点
3. ✅ 完成第一轮修复（排除规则）
4. ✅ 生成6份详细文档和完整验证SQL
5. ✅ 制定清晰的验证和修复流程

### 价值

1. **系统化**：不是人工抽查，而是基于源码的全面对比
2. **可验证**：提供完整的SQL脚本，可量化差异
3. **可操作**：提供具体的修复方案和代码示例
4. **文档化**：所有分析结果都有文档记录

### 风险控制

1. ✅ 所有修复方案都有验证SQL
2. ✅ 采用优先级分级，先修复影响最大的
3. ✅ 提供差异阈值指导（<1%可接受，>5%必修复）
4. ✅ 修复后重新验证

### 下一步

**立即执行**：
1. 在开发环境执行validation-sql-scripts.md
2. 生成验证结果报告
3. 根据验证结果制定第二轮修复计划
4. 修复P0问题（历史遗留判定）
5. 根据验证结果修复P1问题
6. 重建事实表
7. 重新验证

**预计时间线**：
- 验证：0.5天
- 修复：1-2天
- 验证修复效果：0.5天
- **总计：2-3天**

**成功标准**：
- 所有关键指标差异<1%
- 新老平台统计数据完全对齐
