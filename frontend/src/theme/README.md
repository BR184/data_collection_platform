# QA Flex Platform 全局配色系统使用指南

## 📁 文件结构

```
frontend/src/theme/
├── colors.css       # 核心配色变量定义
├── button.css       # 按钮样式增强
└── components.css   # 组件样式增强
```

## 🎨 设计理念

参考字节跳动、飞书、阿里云等大厂设计体系，打造：
- **现代化**：清爽简洁的视觉风格
- **专业性**：科技感的品牌蓝色
- **统一性**：全局统一的交互规范
- **易用性**：符合直觉的色彩语义

## 🌈 核心配色

### 品牌主色
- **主色调**：`#3370ff` - 专业科技蓝
- **悬停态**：`#4d83ff` 
- **激活态**：`#2556d8`
- **浅色系**：6 个渐变层级，用于背景和边框

### 功能色
- **成功色**：`#00b42a` - 清新绿色
- **警告色**：`#ff7d00` - 温暖橙色
- **危险色**：`#f53f3f` - 醒目红色
- **信息色**：`#165dff` - 中性蓝色

### 中性色
- **文本色**：主要文本 `#1d2129`、常规文本 `#4e5969`、次要文本 `#86909c`
- **背景色**：白色 `#ffffff`、基础 `#f7f8fa`、浅色 `#f2f3f5`、悬停 `#e5e6eb`
- **边框色**：基础 `#e5e6eb`、浅色 `#f2f3f5`、深色 `#c9cdd4`

## 🔧 使用方法

### 1. CSS 变量使用

在任何样式文件中直接使用预定义的 CSS 变量：

```css
.custom-button {
  background-color: var(--brand-primary);
  color: var(--text-white);
  border-radius: var(--radius-base);
  box-shadow: var(--shadow-base);
}

.custom-button:hover {
  background-color: var(--brand-primary-hover);
  box-shadow: var(--shadow-md);
}
```

### 2. Element Plus 按钮类型

推荐使用标准的 Element Plus 按钮类型，已自动应用新配色：

```vue
<template>
  <!-- 主要操作 -->
  <el-button type="primary">主要按钮</el-button>
  
  <!-- 成功操作（如：保存、提交、确认） -->
  <el-button type="success">保存</el-button>
  
  <!-- 警告操作（如：导出、备份） -->
  <el-button type="warning">导出数据</el-button>
  
  <!-- 危险操作（如：删除、清空） -->
  <el-button type="danger">删除</el-button>
  
  <!-- 信息操作（如：查看详情、刷新） -->
  <el-button type="info">查看详情</el-button>
  
  <!-- 默认操作（如：取消、关闭） -->
  <el-button>取消</el-button>
  
  <!-- 朴素按钮 -->
  <el-button type="primary" plain>朴素按钮</el-button>
  
  <!-- 文本按钮 -->
  <el-button type="text">文本按钮</el-button>
</template>
```

### 3. 业务场景按钮类

针对常见业务场景，提供了语义化的 class：

```vue
<template>
  <!-- 创建/新增操作（带渐变效果） -->
  <el-button type="primary" class="btn-action-create">
    <el-icon><Plus /></el-icon>
    添加条目
  </el-button>
  
  <!-- 导出操作 -->
  <el-button type="success" class="btn-action-export">
    <el-icon><Download /></el-icon>
    导出数据
  </el-button>
  
  <!-- 设置操作 -->
  <el-button class="btn-action-settings">
    <el-icon><Setting /></el-icon>
    设置
  </el-button>
  
  <!-- 刷新操作 -->
  <el-button type="info" class="btn-action-refresh">
    <el-icon><Refresh /></el-icon>
    刷新
  </el-button>
</template>
```

### 4. 表格数字标签

表格中的数字显示已优化为渐变标签样式：

```vue
<template>
  <el-table :data="tableData">
    <el-table-column label="数量">
      <template #default="{ row }">
        <!-- 自动应用渐变标签样式 -->
        <span 
          class="cell-number-badge" 
          :class="{ 'is-zero': row.count === 0 }"
        >
          {{ row.count }}
        </span>
      </template>
    </el-table-column>
  </el-table>
</template>
```

### 5. 自定义组件配色

创建自定义组件时，使用标准变量确保一致性：

```vue
<style scoped>
.custom-card {
  background: var(--bg-white);
  border: 1px solid var(--border-light);
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-sm);
  padding: var(--spacing-md);
}

.custom-card:hover {
  border-color: var(--brand-primary-light-3);
  box-shadow: var(--shadow-base);
}

.custom-title {
  color: var(--text-primary);
  font-size: 16px;
  font-weight: 600;
  margin-bottom: var(--spacing-sm);
}

.custom-description {
  color: var(--text-secondary);
  font-size: 14px;
  line-height: 1.6;
}
</style>
```

## 📊 配色变量速查

### 间距系统
```css
--spacing-xs: 4px
--spacing-sm: 8px
--spacing-base: 12px
--spacing-md: 16px
--spacing-lg: 24px
--spacing-xl: 32px
```

### 圆角系统
```css
--radius-xs: 2px
--radius-sm: 4px
--radius-base: 6px
--radius-md: 8px
--radius-lg: 12px
--radius-round: 9999px
```

### 阴影系统
```css
--shadow-sm: 浅阴影（悬停卡片）
--shadow-base: 基础阴影（下拉菜单）
--shadow-md: 中等阴影（对话框）
--shadow-lg: 深阴影（模态框）
```

## 🎯 最佳实践

### ✅ 推荐做法

1. **使用语义化颜色**：
   ```css
   /* 好 */
   color: var(--text-primary);
   background: var(--bg-white);
   
   /* 不好 */
   color: #1d2129;
   background: #ffffff;
   ```

2. **选择合适的按钮类型**：
   - 一个页面最多 1 个 `type="primary"` 主按钮
   - 危险操作必须使用 `type="danger"`
   - 次要操作使用 `default` 或 `plain`

3. **保持视觉层次**：
   ```vue
   <!-- 主操作 + 次要操作 -->
   <el-button type="primary">提交审核</el-button>
   <el-button>取消</el-button>
   ```

### ❌ 避免做法

1. **不要硬编码颜色值**
2. **不要在一个页面使用过多主按钮**
3. **不要随意改变 Element Plus 组件的默认行为**

## 🔄 迁移指南

### 旧代码迁移

如果你的代码中有这样的按钮：

```vue
<!-- 旧代码 -->
<el-button style="background: #409EFF; color: white">保存</el-button>
```

改为：

```vue
<!-- 新代码 -->
<el-button type="success">保存</el-button>
```

### 自定义样式迁移

```css
/* 旧代码 */
.my-button {
  background: #409EFF;
  color: white;
  border-radius: 4px;
}

/* 新代码 */
.my-button {
  background: var(--brand-primary);
  color: var(--text-white);
  border-radius: var(--radius-base);
}
```

## 🚀 效果展示

新配色系统带来的改进：

- ✨ **视觉统一**：所有按钮、组件使用统一的配色方案
- 🎨 **现代感**：渐变、阴影、圆角等细节提升
- 🖱️ **交互优化**：悬停、激活态有明确的视觉反馈
- 📱 **品牌感**：科技蓝主色调传达专业与可靠
- 🔧 **易维护**：通过 CSS 变量统一管理，修改方便

## 📝 注意事项

1. **不要直接修改 theme/ 目录下的文件**，这些是全局配置
2. **组件库升级时**，检查是否有样式冲突
3. **深色模式**：当前仅支持浅色模式，深色模式需要单独配置
4. **浏览器兼容性**：CSS 变量在现代浏览器中支持良好（IE 不支持）

## 🛠️ 故障排查

### 样式没有生效？

1. 检查导入顺序，确保 theme 文件在 styles.css 之前导入
2. 清除浏览器缓存
3. 检查是否有局部样式覆盖了全局样式

### 颜色显示不对？

1. 检查是否使用了 `!important`（应避免使用）
2. 检查是否有内联样式覆盖
3. 使用浏览器开发工具检查实际应用的 CSS 变量值

---

**维护者**：前端团队  
**最后更新**：2026-07-03  
**版本**：v1.0.0
