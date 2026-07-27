insert into issue_customer_name_aliases(alias_name, canonical_name)
values
    ('极目数字（苏普耐）', '极目数字'),
    ('极目数字(苏普耐)', '极目数字'),
    ('极目数字（苏普耐）——新版本适配测试', '极目数字'),
    ('极目数字(苏普耐)——新版本适配测试', '极目数字')
on conflict (alias_name)
do update set
    canonical_name = excluded.canonical_name,
    updated_at = current_timestamp;
