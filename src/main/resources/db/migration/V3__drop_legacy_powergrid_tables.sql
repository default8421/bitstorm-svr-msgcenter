-- 下线已废弃的电网告警表。V2 必须保留（线上已执行过），本脚本按外键顺序清理。

SET FOREIGN_KEY_CHECKS = 0;
DROP TABLE IF EXISTS pg_alert_notification;
DROP TABLE IF EXISTS pg_alert_event;
DROP TABLE IF EXISTS pg_alert_rule;
DROP TABLE IF EXISTS pg_device;
DROP TABLE IF EXISTS pg_substation;
SET FOREIGN_KEY_CHECKS = 1;
