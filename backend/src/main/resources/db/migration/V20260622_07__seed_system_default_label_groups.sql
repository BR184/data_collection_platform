insert into label_groups (name, value_type, group_type, description, enabled, created_by, updated_by)
values
  ('评审数据管理', 'STRING', 'STATIC', '系统默认标签组：用于按老平台口径收口该页面模块范围，用户可编辑成员或停用。', true, 'system', 'system'),
  ('代码走查非法数据', 'STRING', 'STATIC', '系统默认标签组：用于按老平台口径收口该页面模块范围，用户可编辑成员或停用。', true, 'system', 'system'),
  ('系统测试缺陷汇总', 'STRING', 'STATIC', '系统默认标签组：用于按老平台口径收口该页面模块范围，用户可编辑成员或停用。', true, 'system', 'system'),
  ('申请延期缺陷分析', 'STRING', 'STATIC', '系统默认标签组：用于按老平台口径收口该页面模块范围，用户可编辑成员或停用。', true, 'system', 'system'),
  ('系统测试非法数据', 'STRING', 'STATIC', '系统默认标签组：用于按老平台口径收口该页面模块范围，用户可编辑成员或停用。', true, 'system', 'system'),
  ('系统测试缺陷原因分析', 'STRING', 'STATIC', '系统默认标签组：用于按老平台口径收口该页面模块范围，用户可编辑成员或停用。', true, 'system', 'system'),
  ('议题阶段统计', 'STRING', 'STATIC', '系统默认标签组：用于按老平台口径收口该页面模块范围，用户可编辑成员或停用。', true, 'system', 'system'),
  ('客户问题缺陷汇总', 'STRING', 'STATIC', '系统默认标签组：用于按老平台口径收口该页面模块范围，用户可编辑成员或停用。', true, 'system', 'system'),
  ('客户问题缺陷非法数据', 'STRING', 'STATIC', '系统默认标签组：用于按老平台口径收口该页面模块范围，用户可编辑成员或停用。', true, 'system', 'system'),
  ('客户问题缺陷原因分析', 'STRING', 'STATIC', '系统默认标签组：用于按老平台口径收口该页面模块范围，用户可编辑成员或停用。', true, 'system', 'system'),
  ('CC_PRODUCT议题', 'STRING', 'STATIC', '系统默认标签组：用于按老平台口径收口该页面模块范围，用户可编辑成员或停用。', true, 'system', 'system'),
  ('延期问题', 'STRING', 'STATIC', '系统默认标签组：用于按老平台口径收口该页面模块范围，用户可编辑成员或停用。', true, 'system', 'system'),
  ('缺陷响应效率', 'STRING', 'STATIC', '系统默认标签组：用于按老平台口径收口该页面模块范围，用户可编辑成员或停用。', true, 'system', 'system'),
  ('按功能展示缺陷数量', 'STRING', 'STATIC', '系统默认标签组：用于按老平台口径收口该页面模块范围，用户可编辑成员或停用。', true, 'system', 'system')
on conflict (name) do update set
  value_type = excluded.value_type,
  group_type = excluded.group_type,
  description = coalesce(nullif(label_groups.description, ''), excluded.description),
  updated_by = excluded.updated_by,
  updated_at = now();
