# GitLab 完整数据采样导出导入指南

本指南用于从内网 GitLab 数据库导出**所有表**的采样数据，并在外网 GitLab 环境中完整导入。

## 📋 概述

**更新内容**：
- ✅ 导出 GitLab **所有表**（不再只导出选定的表）
- ✅ 自动分析外键依赖关系
- ✅ 拓扑排序确保导入顺序正确
- ✅ 保留完整的逻辑关系
- ✅ 可直接导入外网 GitLab 数据库

**导出策略**：
- 每张表最多导出 5,000 行（高优先级表可配置更多）
- 按主键倒序采样（获取最新数据）
- 自动排除系统表和大型日志表
- 目标文件大小：≤50MB（压缩后）

---

## 🚀 使用流程

### 步骤 1: 内网导出所有表

#### 在内网环境运行

```bash
# 1. 确保已安装 Python 和 psycopg2
pip install psycopg2-binary

# 2. 运行导出脚本（密码已内置）
python scripts/export-gitlab-sample-data.py
```

#### 导出过程

脚本会自动完成：

1. **连接内网 GitLab 数据库**
2. **扫描所有表** - 排除系统表和日志表
3. **分析外键关系** - 识别表之间的依赖
4. **拓扑排序** - 确定正确的导出顺序
5. **采样导出** - 按依赖顺序导出数据
6. **生成压缩文件** - `gitlab_full_sample.sql.gz`

#### 导出结果示例

```
================================================================================
GitLab 完整数据库采样导出工具
================================================================================

🔗 连接到内网数据库...
✅ 数据库连接成功

🔍 扫描数据库表...
   找到 156 张表

🔗 分析外键关系...
   找到 238 个外键约束

📋 规划导出顺序...
   导出顺序已确定

📦 开始导出数据...

  📊 users → 300/1,234 行
  📊 namespaces → 200/856 行
  📊 projects → 200/145 行
  📊 members → 1,000/4,567 行
  📊 issues → 5,000/45,678 行
  📊 merge_requests → 3,000/12,345 行
  📊 notes → 8,000/123,456 行
  📊 labels → 500/2,345 行
  📊 milestones → 300/567 行
  ...
  📊 ci_pipelines → 2,000/23,456 行
  📊 ci_builds → 3,000/45,678 行

💾 写入文件: gitlab_full_sample.sql.gz

✅ 导出完成!
   - 导出表数: 156/156
   - 总行数: 48,234
   - 文件大小: 42.8 MB (压缩后)
   - 输出文件: gitlab_full_sample.sql.gz
```

---

### 步骤 2: 传输文件到外网

将 `gitlab_full_sample.sql.gz` 从内网传输到外网环境。

---

### 步骤 3: 外网导入到 GitLab

#### 前置要求

1. **外网 GitLab 环境**（PostgreSQL 数据库）
2. **PostgreSQL 客户端工具** (`psql`)
3. **管理员权限**

#### 导入步骤

##### 方法 A: 导入到新的 GitLab 数据库（推荐）

```bash
# 1. 解压文件
gunzip gitlab_full_sample.sql.gz

# 2. 创建新的测试数据库
createdb gitlab_sample -O gitlab

# 3. 导入数据
psql -d gitlab_sample -U gitlab -f gitlab_full_sample.sql

# 4. 验证导入
psql -d gitlab_sample -U gitlab -c "
  SELECT 'projects' AS table_name, COUNT(*) FROM projects
  UNION ALL
  SELECT 'users', COUNT(*) FROM users
  UNION ALL
  SELECT 'issues', COUNT(*) FROM issues
  UNION ALL
  SELECT 'merge_requests', COUNT(*) FROM merge_requests;
"
```

##### 方法 B: 导入到现有 GitLab 数据库（谨慎）

⚠️ **警告**: 这会向现有数据库添加数据，可能导致 ID 冲突。

```bash
# 1. 备份现有数据库
pg_dump -d gitlabhq_production -U gitlab > backup_before_import.sql

# 2. 导入数据（使用 ON CONFLICT 处理冲突）
psql -d gitlabhq_production -U gitlab -f gitlab_full_sample.sql

# 3. 重置序列（防止后续 ID 冲突）
psql -d gitlabhq_production -U gitlab << 'EOF'
SELECT setval(pg_get_serial_sequence('projects', 'id'),
              COALESCE((SELECT MAX(id) FROM projects), 1));
SELECT setval(pg_get_serial_sequence('users', 'id'),
              COALESCE((SELECT MAX(id) FROM users), 1));
SELECT setval(pg_get_serial_sequence('issues', 'id'),
              COALESCE((SELECT MAX(id) FROM issues), 1));
SELECT setval(pg_get_serial_sequence('merge_requests', 'id'),
              COALESCE((SELECT MAX(id) FROM merge_requests), 1));
EOF
```

---

## 🔧 高级配置

### 调整采样行数

编辑 `scripts/export-gitlab-sample-data.py`：

```python
# 全局最大行数
MAX_ROWS_PER_TABLE = 5000

# 高优先级表（会导出更多数据）
HIGH_PRIORITY_TABLES = {
    'projects': 200,
    'users': 300,
    'issues': 8000,        # 增加到 8000 行
    'merge_requests': 5000, # 增加到 5000 行
    'notes': 10000,        # 增加到 10000 行
}
```

### 调整文件大小限制

```python
TARGET_SIZE_MB = 100  # 增加到 100MB
```

### 排除特定表

```python
EXCLUDED_TABLES = {
    'audit_events',      # 审计日志
    'web_hook_logs',     # Webhook 日志
    'your_custom_table', # 自定义排除
}
```

---

## 📊 导出的表分类

### 核心业务表
- `projects` - 项目
- `users` - 用户
- `namespaces` - 命名空间/组织
- `issues` - 议题
- `merge_requests` - 合并请求
- `notes` - 评论
- `labels` - 标签
- `milestones` - 里程碑
- `members` - 成员

### CI/CD 表
- `ci_pipelines` - CI 流水线
- `ci_builds` - CI 构建任务
- `ci_runners` - CI 运行器
- `ci_variables` - CI 变量

### 关联表
- `issue_assignees` - 议题负责人
- `merge_request_assignees` - MR 负责人
- `merge_request_reviewers` - MR 审查人
- `label_links` - 标签关联
- `issue_metrics` - 议题指标
- `merge_request_metrics` - MR 指标

### 其他表
- `events` - 事件
- `deployments` - 部署
- `environments` - 环境
- `snippets` - 代码片段
- `boards` - 看板
- ... 以及其他所有表

---

## ⚠️ 注意事项

### 外键完整性

✅ **已自动处理**：
- 脚本使用拓扑排序确保依赖表先导出
- 例如：`users` → `projects` → `issues` → `notes`
- 导入时 PostgreSQL 会验证外键约束

### ID 冲突处理

导入到现有数据库时：

1. **主键冲突**: 导出的数据使用原始 ID，可能与现有数据冲突
   - **解决方案**: 导入到新数据库（推荐）
   - **或**: 在 INSERT 语句中添加 `ON CONFLICT DO NOTHING`

2. **序列重置**: 导入后必须重置 PostgreSQL 序列
   ```sql
   SELECT setval(pg_get_serial_sequence('table_name', 'id'),
                 COALESCE((SELECT MAX(id) FROM table_name), 1));
   ```

### 循环依赖

某些表之间存在循环依赖（例如 `users` ↔ `namespaces`）：
- 脚本会检测循环依赖
- 导入时临时禁用外键检查（`SET session_replication_role = replica;`）
- 导入完成后恢复外键检查

### 数据一致性

- 导出的是**时间点快照**，不是实时同步
- 跨表的数据可能来自不同时间点（按主键倒序采样）
- 建议定期重新导出以获取最新数据

---

## 🛠️ 故障排除

### 问题 1: 内存不足

**症状**: 导出脚本占用大量内存

**解决方案**:
1. 减少 `MAX_ROWS_PER_TABLE`
2. 减少 `TARGET_SIZE_MB`
3. 增加排除表（`EXCLUDED_TABLES`）

### 问题 2: 导入外键错误

**症状**: `ERROR: insert or update on table "X" violates foreign key constraint`

**原因**: 依赖表数据不完整

**解决方案**:
1. 检查导出日志，确认依赖表已导出
2. 增加依赖表的采样行数
3. 临时禁用外键检查导入（已包含在脚本中）

### 问题 3: 导入速度慢

**优化方案**:
```sql
-- 导入前执行
SET synchronous_commit = off;
SET maintenance_work_mem = '1GB';

-- 导入数据...

-- 导入后恢复
SET synchronous_commit = on;
```

### 问题 4: 表结构不匹配

**症状**: `ERROR: column "X" does not exist`

**原因**: 内外网 GitLab 版本不同

**解决方案**:
1. 确保内外网 GitLab 版本接近
2. 或手动调整 SQL 文件中的列名

---

## 📝 与平台集成

导入到外网 GitLab 后，平台可以连接到这个测试 GitLab 数据库进行开发测试。

### 配置平台连接

在 `backend/src/main/resources/application.yml` 或环境变量中配置：

```yaml
platform:
  gitlab-mirror:
    # 配置连接到导入后的 GitLab 数据库
    configs:
      - name: "GitLab Sample (cc)"
        source-mode: DIRECT_DB
        db-host: localhost
        db-port: 5432
        db-name: gitlab_sample
        db-username: gitlab
        db-password: your_password
```

---

## 🔄 定期更新

建议每周或每两周从内网重新导出最新数据：

```bash
# 内网
python scripts/export-gitlab-sample-data.py

# 传输文件

# 外网
gunzip -f gitlab_full_sample.sql.gz
psql -d gitlab_sample -c "DROP SCHEMA public CASCADE; CREATE SCHEMA public;"
psql -d gitlab_sample -f gitlab_full_sample.sql
```

---

## 📊 预期结果

### 导出统计

- **表数量**: 约 150-200 张表
- **总行数**: 3-5 万行
- **文件大小**: 30-50 MB（压缩后）
- **导出时间**: 3-8 分钟

### 导入统计

- **导入时间**: 2-5 分钟
- **数据库大小**: 约 200-500 MB

---

## ✅ 总结

新的导出脚本特性：

- ✅ **完整性**: 导出所有表（排除系统表和日志表）
- ✅ **关系保留**: 自动分析并保持外键关系
- ✅ **顺序正确**: 拓扑排序确保导入不报错
- ✅ **可直接导入**: 生成的 SQL 可直接用于 GitLab 数据库
- ✅ **智能采样**: 高优先级表获得更多数据
- ✅ **压缩传输**: Gzip 压缩节省传输时间

如有问题，请查看故障排除章节或联系开发团队。
