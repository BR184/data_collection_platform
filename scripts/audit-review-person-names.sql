-- =============================================================================
-- 评审数据页面「人名质量」只读诊断脚本
-- =============================================================================
-- 用途：一次性扫描评审数据里所有人名，找出脏数据/过期数据候选，例如
--       刘佳琪 vs 刘佳祺（形近/同音错字）、徐昊 vs 徐昊2（数字后缀重名）、
--       离职账号仍进下拉候选、拼音/工号与中文名并存等。
--       同事只给了两个例子，本脚本把问题「通用化」全量扫。
--
-- 【数据安全】不修改任何业务数据（无 INSERT/UPDATE/DELETE/DDL 落库）。
--       仅在会话内创建临时视图 _audit_nm/_audit_nt 辅助查询，断开连接自动消失，
--       脚本末尾亦显式 DROP。可在生产内网安全运行。
--
-- 【重要】各段结果是「候选」，需人工确认后再决定订正；脚本本身不做任何合并/改名。
--       段 3（形近）会包含同姓不同名的正常同事，请优先怀疑「差异字同音」的配对。
--
-- 覆盖的人名来源（评审数据页面下拉候选的全部落点）：
--   [F 正式] review_record_experts.expert_name / review_problem_items.reviewer_name,owner_name
--            / review_records.review_owner,author_name      —— 新平台手工录入
--   [M 镜像] ods_gitlab_users.name                          —— GitLab 用户镜像（下拉主源）
--   [L 遗留] review_data_match_mode_reports.review_experts,review_charger
--                                                          —— 兼容模式老平台 Mongo 快照
--   说明：读模式=compatibility 时，[L 遗留] 会并入下拉候选，且每 10 分钟全量重刷回流。
--
-- 运行方式（在内网能连到平台库的机器上）：
--   psql "<平台库连接串>" -f scripts/audit-review-person-names.sql -o audit-result.txt
--   或在 DBeaver/pgAdmin 里「整段脚本一次性执行」（临时视图需在同一会话内先建后用）。
-- =============================================================================


-- -----------------------------------------------------------------------------
-- 会话级临时视图：统一收集 6 处人名（含来源标记），并聚合成每名的来源用量标签
-- -----------------------------------------------------------------------------
drop view if exists _audit_nm;
create temp view _audit_nm as
  select btrim(expert_name) as n, 'F' as s from review_record_experts
    where deleted = false and nullif(btrim(expert_name), '') is not null
  union all select btrim(reviewer_name), 'F' from review_problem_items
    where deleted = false and nullif(btrim(reviewer_name), '') is not null
  union all select btrim(owner_name), 'F' from review_problem_items
    where deleted = false and nullif(btrim(owner_name), '') is not null
  union all select btrim(review_owner), 'F' from review_records
    where deleted = false and nullif(btrim(review_owner), '') is not null
  union all select btrim(author_name), 'F' from review_records
    where deleted = false and nullif(btrim(author_name), '') is not null
  union all select btrim(name), 'M' from ods_gitlab_users
    where nullif(btrim(name), '') is not null
  union all select btrim(x), 'L' from review_data_match_mode_reports,
    lateral regexp_split_to_table(coalesce(review_experts, ''), '[,，、;；\n\r\[\]"]+') as x
    where nullif(btrim(x), '') is not null
  union all select btrim(review_charger), 'L' from review_data_match_mode_reports
    where nullif(btrim(review_charger), '') is not null;

drop view if exists _audit_nt;
create temp view _audit_nt as
  select n as name,
         count(*) filter (where s = 'F') as f_cnt,
         count(*) filter (where s = 'M') as m_cnt,
         count(*) filter (where s = 'L') as l_cnt,
         count(*) as total,
         '正式' || (count(*) filter (where s = 'F'))::text
           || ' / 镜像' || (count(*) filter (where s = 'M'))::text
           || ' / 遗留' || (count(*) filter (where s = 'L'))::text as src_tag
    from _audit_nm
   group by n;


-- -----------------------------------------------------------------------------
-- 段 1：人名总清单 —— 每个名字在 正式/镜像/遗留 三源的出现次数（全量底表，先扫一眼）
-- -----------------------------------------------------------------------------
select '1-人名总清单' as "诊断段", name as "人名",
       f_cnt as "正式", m_cnt as "镜像", l_cnt as "遗留", total as "合计", src_tag as "来源分布"
  from _audit_nt
 order by total desc, name;


-- -----------------------------------------------------------------------------
-- 段 2：数字后缀重名（徐昊 / 徐昊2、王旭 / 王旭3 模式）
-- -----------------------------------------------------------------------------
select '2-数字后缀重名' as "诊断段",
       a.name as "基础名", a.src_tag as "基础名来源",
       b.name as "带后缀名", b.src_tag as "带后缀名来源",
       substring(b.name from length(a.name) + 1) as "后缀"
  from _audit_nt a join _audit_nt b
    on b.name <> a.name
   and b.name like a.name || '%'
   and substring(b.name from length(a.name) + 1) ~ '^[0-9]+$'
 order by a.name, b.name;


-- -----------------------------------------------------------------------------
-- 段 3：形近 / 疑似同音重名（刘佳琪 / 刘佳祺、孟秀平 / 孟秀萍 模式）
--       同字数、恰好差一个字；输出差异字与两名的来源用量，同音字优先怀疑
--       按字数降序 + 合计用量降序，让高频真实配对（如刘佳琪/刘佳祺）靠前
-- -----------------------------------------------------------------------------
select '3-形近疑似同音' as "诊断段",
       p.name_a as "名A", ta.src_tag as "A来源",
       p.name_b as "名B", tb.src_tag as "B来源",
       p.len as "字数", p.diff_pos as "差异位",
       substring(p.name_a from p.diff_pos for 1) as "A差异字",
       substring(p.name_b from p.diff_pos for 1) as "B差异字"
  from (
    select a.name as name_a, b.name as name_b, length(a.name) as len,
           (a.total + b.total) as pair_total,
           (select min(i) from generate_series(1, length(a.name)) i
             where substring(a.name from i for 1) <> substring(b.name from i for 1)) as diff_pos
      from _audit_nt a join _audit_nt b
        on a.name < b.name and length(a.name) = length(b.name)
     where length(a.name) between 2 and 4
  ) p
  join _audit_nt ta on ta.name = p.name_a
  join _audit_nt tb on tb.name = p.name_b
 where (select count(*) from generate_series(1, p.len) i
         where substring(p.name_a from i for 1) <> substring(p.name_b from i for 1)) = 1
 order by p.len desc, p.pair_total desc, p.name_a, p.name_b;


-- -----------------------------------------------------------------------------
-- 段 4：归一化碰撞 —— 去空格 / 转小写 / 去数字后缀 相同的人名聚成一组
--       一网打尽「徐昊/徐昊2」「大小写」「首尾空格」等变体
-- -----------------------------------------------------------------------------
select '4-归一化碰撞' as "诊断段",
       lower(btrim(regexp_replace(name, '[0-9]+$', ''))) as "归一化键",
       string_agg(name || '(' || src_tag || ')', '  |  ' order by name) as "变体及来源",
       count(*) as "变体数"
  from _audit_nt
 group by lower(btrim(regexp_replace(name, '[0-9]+$', '')))
having count(*) > 1
 order by count(*) desc, 1;


-- -----------------------------------------------------------------------------
-- 段 5：评审数据用到、但当前 GitLab 镜像无此人（离职清除 / 老平台遗留 / 错字）
--       这些名字仍会进下拉候选（经遗留快照或历史正式数据），是脏名的高信号来源
-- -----------------------------------------------------------------------------
select '5-非当前镜像用户' as "诊断段", name as "人名",
       f_cnt as "正式", l_cnt as "遗留", src_tag as "来源分布"
  from _audit_nt
 where m_cnt = 0 and (f_cnt > 0 or l_cnt > 0)
 order by (f_cnt + l_cnt) desc, name;


-- -----------------------------------------------------------------------------
-- 段 6：镜像里离职 / 停用账号，但仍会进入下拉候选（徐昊 类根因之一）
--       loadUserNames 只过滤 mirror_deleted=false，不过滤 state；
--       故 state<>'active'（blocked/deactivated）或长期无活动的账号仍显示在下拉里。
-- -----------------------------------------------------------------------------
select '6-离职停用仍在候选' as "诊断段",
       btrim(u.name) as "人名", u.username as "账号", u.state as "状态",
       u.last_activity_on as "最近活动", u.last_sign_in_at as "最近登录",
       coalesce(t.l_cnt, 0) as "遗留出现", coalesce(t.f_cnt, 0) as "正式出现"
  from ods_gitlab_users u
  left join _audit_nt t on t.name = btrim(u.name)
 where nullif(btrim(u.name), '') is not null
   and coalesce(u.mirror_deleted, false) = false
   and (u.state <> 'active' or u.last_activity_on < current_date - interval '365 days')
 order by u.state, u.last_activity_on nulls last;


-- -----------------------------------------------------------------------------
-- 段 7：已知案例精确核查（刘佳祺 / 刘佳琪 / 徐昊 / 徐昊2）
--       三源用量 + 正式表最晚评审日 + 遗留快照最晚评审时间 + 镜像账号状态
--       判断徐昊历史数据性质：最晚评审时间 vs 离职时间 → 离职后产生的多为误选
--       如需加查其他人名，直接在 targets 的 values 里补 ('名字')
-- -----------------------------------------------------------------------------
with targets(name) as (values ('刘佳祺'), ('刘佳琪'), ('徐昊'), ('徐昊2'))
select '7-已知案例核查' as "诊断段", t.name as "人名",
       coalesce(nt.f_cnt, 0) as "正式", coalesce(nt.m_cnt, 0) as "镜像", coalesce(nt.l_cnt, 0) as "遗留",
       (select max(rr.review_date) from review_record_experts e
          join review_records rr on rr.id = e.review_record_id
         where e.deleted = false and btrim(e.expert_name) = t.name) as "正式最晚评审日",
       (select max(r.review_time)::date from review_data_match_mode_reports r,
               lateral regexp_split_to_table(coalesce(r.review_experts, ''), '[,，、;；\n\r\[\]"]+') as x
         where btrim(x) = t.name) as "遗留最晚评审",
       (select string_agg(distinct u.state, ',') from ods_gitlab_users u where btrim(u.name) = t.name) as "镜像状态",
       (select max(u.last_activity_on) from ods_gitlab_users u where btrim(u.name) = t.name) as "镜像最近活动"
  from targets t
  left join _audit_nt nt on nt.name = t.name
 order by t.name;


-- -----------------------------------------------------------------------------
-- 段 8：兼容模式快照的评审专家频次（老平台 Mongo，读模式=兼容时进候选）
--       该表由定时任务全量重刷，新平台侧改名会被覆盖；确认脏名是否也存在于老平台侧
-- -----------------------------------------------------------------------------
select '8-遗留快照专家频次' as "诊断段", btrim(x) as "人名", count(*) as "出现次数"
  from review_data_match_mode_reports,
       lateral regexp_split_to_table(coalesce(review_experts, ''), '[,，、;；\n\r\[\]"]+') as x
 where nullif(btrim(x), '') is not null
 group by btrim(x)
 order by count(*) desc, btrim(x);


-- 清理会话级临时视图（不落库，断开亦自动消失）
drop view if exists _audit_nt;
drop view if exists _audit_nm;
