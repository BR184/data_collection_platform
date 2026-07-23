insert into issue_customer_name_aliases(alias_name, canonical_name)
values ('新世纪', '郑州新世纪')
on conflict (alias_name)
do update set
    canonical_name = excluded.canonical_name,
    updated_at = current_timestamp;
