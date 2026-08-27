-- 按登录用户做数据隔离：模板与消息记录带上租户。历史数据归到当前运维账号 LQH。

ALTER TABLE t_msg_template
    ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT 'LQH' COMMENT '租户' AFTER template_id;

ALTER TABLE t_msg_record
    ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT 'LQH' COMMENT '租户' AFTER msg_id;

CREATE INDEX idx_template_tenant_name ON t_msg_template (tenant_id, name);
CREATE INDEX idx_record_tenant_time ON t_msg_record (tenant_id, id);
