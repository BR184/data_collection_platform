-- 老平台质量达人榜的 CC 业务成员范围改为可维护数据，不再由 Java 显示名常量决定。
insert into quality_board_member_scopes(
    topic_key, business_source, member_name, display_order, enabled)
values
    ('QUALITY_RANKING', 'cc', '刘敏', 10, true),
    ('QUALITY_RANKING', 'cc', '张金花', 20, true),
    ('QUALITY_RANKING', 'cc', '杨依景', 30, true),
    ('QUALITY_RANKING', 'cc', '张月', 40, true),
    ('QUALITY_RANKING', 'cc', '李尚玉', 50, true),
    ('QUALITY_RANKING', 'cc', '曹巧元', 60, true),
    ('QUALITY_RANKING', 'cc', '田松', 70, true),
    ('QUALITY_RANKING', 'cc', '张辉', 80, true),
    ('QUALITY_RANKING', 'cc', 'mengxiangyu', 90, true),
    ('QUALITY_RANKING', 'cc', '梁启星', 100, true)
on conflict (topic_key, business_source, member_name) do nothing;
