-- 旧 code_review.form.* 随旧写入口退役；新 form.record.* 已在 V20261022_02 登记。
delete from platform_role_permissions
 where permission_code in
       ('code_review.form.create', 'code_review.form.edit', 'code_review.form.delete');

delete from platform_default_role_permissions
 where permission_code in
       ('code_review.form.create', 'code_review.form.edit', 'code_review.form.delete');

delete from platform_permissions
 where permission_code in
       ('code_review.form.create', 'code_review.form.edit', 'code_review.form.delete');
