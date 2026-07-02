# GitLab 采样数据导出导入指南

本指南用于从内网 GitLab 数据库导出采样数据，并在外网测试环境中导入，以便在外网进行开发和测试。

## 📋 概述

**目标**: 从内网 GitLab PostgreSQL 数据库导出最新的采样数据（≤10MB），保持外键关联完整性，用于外网测试。

**内网数据源配置**:
- 主机: `172.22.10.233`
- 端口: `5432`
- 数据库: `gitlabhq_production`
- 用户名: `qaflex_reader`
- 来源标识: `cc`

**外网目标配置**:
- 主机: `localhost`
- 端口: `15432`
- 数据库: `qaflex`
- 用户名: `qaflex`

---

## 🚀 使用流程

### 步骤 1: 内网导出数据

#### 前置要求

1. **Python 3.7+** 和 **psycopg2** 库:
   ```bash
   pip install psycopg2-binary
   ```

2. 确保有内网数据库的只读权限（用户 `qaflex_reader`）

#### 执行导出

在内网环境中运行导出脚本：

```bash
# 方法 1: 通过环境变量提供密码（推荐）
export GITLAB_DB_PASSWORD='your_password_here'
python scripts/export-gitlab-sample-data.py

# 方法 2: 交互式输入密码
python scripts/export-gitlab-sample-data.py
# 按提示输入密码
```

#### 导出结果

脚本会生成压缩文件：`gitlab_sample_data.sql.gz`

- **内容**: GitLab 核心表的最新 10,000 条记录
- **大小**: 通常 2-8 MB（压缩后）
- **格式**: Gzip 压缩的 SQL 脚本

导出的表包括：
- `projects` (项目)
- `users` (用户)
- `issues` (议题) - **平台核心数据**
- `merge_requests` (合并请求) - **平台核心数据**
- `notes` (评论)
- `labels` (标签)
- `milestones` (里程碑)
- 其他关联表...

---

### 步骤 2: 传输文件到外网

使用任何安全的文件传输方式将 `gitlab_sample_data.sql.gz` 从内网传输到外网开发环境。

**示例方式**:
- U盘拷贝
- 内部文件共享系统
- 安全的文件传输协议

---

### 步骤 3: 外网导入数据

#### 前置要求

1. **PostgreSQL 客户端工具** (`psql`)
2. 外网测试数据库已启动并可访问

#### 解压文件

```bash
# 解压 SQL 文件
gunzip gitlab_sample_data.sql.gz
```

解压后得到：`gitlab_sample_data.sql`

#### 方法 A: 使用 PowerShell 脚本导入（推荐）

```powershell
# 在外网环境中运行
.\scripts\import-gitlab-sample-data.ps1
```

脚本会：
1. 检查 SQL 文件是否存在
2. 提示确认（避免误操作）
3. 安全地导入数据
4. 显示导入结果

#### 方法 B: 手动导入

```bash
# 设置密码环境变量（避免命令行泄露）
export PGPASSWORD='your_password'

# 导入数据
psql -h localhost -p 15432 -U qaflex -d qaflex \
  -f gitlab_sample_data.sql \
  --set ON_ERROR_STOP=on

# 清理密码环境变量
unset PGPASSWORD
```

---

### 步骤 4: 验证导入

导入完成后，验证数据：

```sql
-- 连接到数据库
psql -h localhost -p 15432 -U qaflex -d qaflex

-- 检查各表的记录数
SELECT 'projects' AS table_name, COUNT(*) FROM projects
UNION ALL
SELECT 'users', COUNT(*) FROM users
UNION ALL
SELECT 'issues', COUNT(*) FROM issues
UNION ALL
SELECT 'merge_requests', COUNT(*) FROM merge_requests
UNION ALL
SELECT 'notes', COUNT(*) FROM notes
UNION ALL
SELECT 'labels', COUNT(*) FROM labels
UNION ALL
SELECT 'milestones', COUNT(*) FROM milestones;

-- 检查最新议题
SELECT id, title, state, created_at
FROM issues
ORDER BY id DESC
LIMIT 10;

-- 检查最新合并请求
SELECT id, title, state, created_at
FROM merge_requests
ORDER BY id DESC
LIMIT 10;
```

---

## 🔧 高级配置

### 自定义采样策略

编辑 `scripts/export-gitlab-sample-data.py` 中的 `TABLE_SAMPLING_STRATEGY`：

```python
TABLE_SAMPLING_STRATEGY = [
    # (表名, 采样行数, 排序字段, 是否是关键表)
    ('issues', 8000, 'id', True),  # 增加议题采样数
    ('merge_requests', 5000, 'id', True),  # 增加合并请求采样数
    # ... 其他表配置
]
```

### 调整文件大小限制

修改导出脚本中的配置：

```python
SAMPLE_SIZE = 10000  # 每张大表的最大采样行数
TARGET_SIZE_MB = 20  # 调整目标文件大小为 20MB
```

### 针对特定项目导出

如果只需要特定项目的数据，可以修改导出逻辑添加 `WHERE` 过滤条件：

```python
# 在 export_table_sample 函数中修改查询
query = f"""
    SELECT * FROM {table_name}
    WHERE project_id IN (SELECT id FROM projects WHERE name LIKE '%CrownCAD%')
    ORDER BY {order_by} DESC
    LIMIT {limit}
"""
```

---

## ⚠️ 注意事项

### 数据一致性

1. **外键完整性**: 脚本通过按依赖顺序导出表来保持外键关系。如果遇到外键错误，检查是否缺少关联表数据。

2. **ID 序列**: 导入脚本会自动重置 PostgreSQL 序列，防止后续插入时的 ID 冲突。

3. **时间戳**: 导出的是内网数据库的最新数据（按 `id` 倒序），时间戳保持原值。

### 性能考虑

1. **导出时间**: 内网导出通常需要 2-5 分钟（取决于表大小和网络）

2. **导入时间**: 外网导入通常需要 1-3 分钟

3. **大表处理**: 如果某些表超大（如 `events` 表），脚本会自动限制采样行数

### 安全性

1. **密码保护**:
   - 导出脚本使用环境变量或交互式输入密码
   - 导入脚本使用 `PGPASSWORD` 环境变量，导入后自动清理

2. **数据脱敏**: 如需脱敏，建议导入后运行：
   ```sql
   -- 脱敏用户邮箱
   UPDATE users SET email = 'test' || id || '@example.com';

   -- 脱敏用户名
   UPDATE users SET username = 'user' || id;
   ```

### 故障排除

#### 问题 1: `psycopg2` 安装失败

**解决方案**: 使用二进制包
```bash
pip install psycopg2-binary
```

#### 问题 2: 外键约束错误

**原因**: 关联表数据缺失

**解决方案**:
1. 检查导出日志，确认关联表已导出
2. 增加关联表的采样行数
3. 导入时临时禁用外键检查（脚本已包含）

#### 问题 3: 文件过大

**解决方案**:
1. 减少 `SAMPLE_SIZE` 或 `TARGET_SIZE_MB`
2. 减少采样的表数量
3. 针对特定项目导出（添加 WHERE 过滤）

#### 问题 4: 导入后 ID 冲突

**原因**: 序列未正确重置

**解决方案**: 手动重置序列
```sql
SELECT setval(pg_get_serial_sequence('issues', 'id'),
              COALESCE((SELECT MAX(id) FROM issues), 1));
```

---

## 🔄 定期更新采样数据

建议定期从内网重新导出最新数据，以保持外网测试环境与内网同步：

1. **频率**: 每周或每两周一次
2. **流程**: 重复上述步骤 1-4
3. **备份**: 导入前备份现有外网数据库（如果需要）

---

## 📊 数据统计

导出后，脚本会显示统计信息：

```
✅ 导出完成!
   - 总行数: 28,450
   - 文件大小: 6.78 MB (压缩后)
   - 输出文件: gitlab_sample_data.sql.gz
```

---

## 🛠️ 与平台集成

导入 GitLab 采样数据后，需要配置平台同步：

### 1. 配置 GitLab 同步源

在外网平台的"系统设置 - GitLab 数据源配置"中添加：

- **数据源名称**: `GitLab_233` 或 `cc`
- **来源标识**: `cc`
- **GitLab Web 地址**: `http://172.22.10.233` (内网地址，外网不可访问，仅用于生成链接)
- **源数据库模式**: `直连 PostgreSQL`
- **数据库配置**: 指向外网测试数据库 `localhost:15432/qaflex`

### 2. 触发事实层构建

导入 GitLab 数据后，需要构建事实表：

```bash
# 方法 1: 通过平台 UI
# 访问 http://localhost:18181/#/mirror-settings
# 点击"事实表重建" -> "全量重建"

# 方法 2: 通过 API
curl -X POST http://localhost:18080/api/fact-build/rebuild-all?full=true \
  -H "Authorization: Bearer YOUR_TOKEN"
```

### 3. 验证数据

```sql
-- 检查事实表
SELECT COUNT(*) FROM issue_fact;
SELECT COUNT(*) FROM merge_request_fact;

-- 检查数据源标识
SELECT DISTINCT source_instance FROM issue_fact;
-- 应该显示: cc

-- 检查最新议题事实
SELECT id, title, project_name, testing_phase, illegal_types
FROM issue_fact
ORDER BY id DESC
LIMIT 10;
```

---

## 📝 总结

这套脚本提供了完整的内网到外网的数据采样流程：

- ✅ 智能采样（最新 10,000 条记录/表）
- ✅ 保持外键关联完整性
- ✅ 文件大小控制（≤10MB）
- ✅ 压缩传输（Gzip）
- ✅ 安全的密码处理
- ✅ 自动序列重置
- ✅ 详细的日志和错误提示

如有问题，请查看故障排除章节或联系开发团队。
