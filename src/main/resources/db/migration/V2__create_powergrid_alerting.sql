-- Power-grid monitoring & alert-push domain.
-- This platform is the alerting/notification tier (ingest -> classify -> dedup/suppress ->
-- multi-channel push), NOT the hard real-time protection/control loop.

CREATE TABLE pg_substation (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(128) NOT NULL,
    region VARCHAR(64) NOT NULL,
    voltage_level_kv INT NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_pg_substation_name (name),
    KEY idx_pg_substation_region (region)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE pg_device (
    id BIGINT NOT NULL AUTO_INCREMENT,
    substation_id BIGINT NOT NULL,
    code VARCHAR(64) NOT NULL,
    device_type VARCHAR(32) NOT NULL,
    name VARCHAR(128) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_pg_device_code (code),
    KEY idx_pg_device_substation (substation_id, device_type),
    CONSTRAINT fk_pg_device_substation FOREIGN KEY (substation_id) REFERENCES pg_substation (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Threshold rules keyed by device type + metric. comparator: GT (value > threshold) or
-- LT (value < threshold). duration_sec is the minimum sustained breach before an alert fires
-- (protects against single-sample spikes). severity maps to the priority topic.
CREATE TABLE pg_alert_rule (
    id BIGINT NOT NULL AUTO_INCREMENT,
    device_type VARCHAR(32) NOT NULL,
    metric VARCHAR(32) NOT NULL,
    comparator VARCHAR(4) NOT NULL,
    threshold DECIMAL(12,3) NOT NULL,
    clear_threshold DECIMAL(12,3) NULL,
    duration_sec INT NOT NULL DEFAULT 0,
    severity VARCHAR(16) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_pg_rule_type_metric (device_type, metric),
    KEY idx_pg_rule_enabled (enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- One open incident per (device, metric) is enforced via the generated active_key: it is
-- CONCAT(device_id,'-',metric) while ACTIVE and NULL once RECOVERED, so recovered rows never
-- collide (MySQL allows repeated NULLs in a UNIQUE index). Repeated breaches of an open incident
-- bump occurrence_count/last_seen_at instead of inserting duplicates -> storm dedup.
CREATE TABLE pg_alert_event (
    id BIGINT NOT NULL AUTO_INCREMENT,
    device_id BIGINT NOT NULL,
    region VARCHAR(64) NOT NULL,
    metric VARCHAR(32) NOT NULL,
    severity VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    triggered_value DECIMAL(12,3) NOT NULL,
    threshold DECIMAL(12,3) NOT NULL,
    occurrence_count INT NOT NULL DEFAULT 1,
    correlation_key VARCHAR(128) NULL,
    first_seen_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    last_seen_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    recovered_at DATETIME(3) NULL,
    active_key VARCHAR(128) GENERATED ALWAYS AS
        (IF(status = 'ACTIVE', CONCAT(device_id, '-', metric), NULL)) STORED,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_pg_alert_active (active_key),
    KEY idx_pg_alert_region_severity_time (region, severity, last_seen_at),
    KEY idx_pg_alert_status_time (status, last_seen_at, id),
    KEY idx_pg_alert_correlation (correlation_key),
    CONSTRAINT fk_pg_alert_device FOREIGN KEY (device_id) REFERENCES pg_device (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Durable, idempotent notification tasks for alert pushes. idempotency_key = eventId:channel so an
-- at-least-once consumer never double-pushes. Claim/lease/retry/DEAD transitions mirror the
-- reliability core used elsewhere.
CREATE TABLE pg_alert_notification (
    id BIGINT NOT NULL AUTO_INCREMENT,
    alert_event_id BIGINT NOT NULL,
    outbox_event_id VARCHAR(64) NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL,
    channel VARCHAR(32) NOT NULL,
    severity VARCHAR(16) NOT NULL,
    region VARCHAR(64) NOT NULL,
    recipient VARCHAR(320) NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    payload VARCHAR(1024) NOT NULL,
    scheduled_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    claimed_by VARCHAR(128) NULL,
    claimed_at DATETIME(3) NULL,
    sent_at DATETIME(3) NULL,
    attempts INT NOT NULL DEFAULT 0,
    last_error VARCHAR(1024) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_pg_notification_idempotency (idempotency_key),
    KEY idx_pg_notification_claim (status, scheduled_at, id),
    KEY idx_pg_notification_stale (status, claimed_at, id),
    CONSTRAINT fk_pg_notification_alert FOREIGN KEY (alert_event_id)
        REFERENCES pg_alert_event (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
