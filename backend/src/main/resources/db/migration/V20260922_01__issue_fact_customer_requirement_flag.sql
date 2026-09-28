-- 客户需求身份派生列（R1）：labels 精确包含「需求」或「类别：建议」。
-- null 表示尚未重建；禁止默认 false 伪造历史分类。非客户议题写 false。
ALTER TABLE issue_fact
    ADD COLUMN IF NOT EXISTS is_customer_requirement BOOLEAN;
