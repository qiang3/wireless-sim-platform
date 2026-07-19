-- Transactional Outbox事件表。
-- 业务事务只负责把待发布事件可靠写入MySQL；后续发布器再把事件发送到RabbitMQ。
CREATE TABLE outbox_event (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    event_id CHAR(36) NOT NULL,
    aggregate_type VARCHAR(32) NOT NULL,
    aggregate_id BIGINT UNSIGNED NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    attempt_no TINYINT UNSIGNED NOT NULL,
    schema_version SMALLINT UNSIGNED NOT NULL DEFAULT 1,
    payload_json JSON NOT NULL,
    priority TINYINT UNSIGNED NOT NULL DEFAULT 3,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    publish_attempts INT UNSIGNED NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    claimed_by VARCHAR(100) NULL,
    claimed_at DATETIME(3) NULL,
    last_error VARCHAR(1000) NULL,
    occurred_at DATETIME(3) NOT NULL,
    published_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_outbox_event_id (event_id),
    UNIQUE KEY uk_outbox_business_event (
        aggregate_type,
        aggregate_id,
        event_type,
        attempt_no
    ),
    KEY idx_outbox_publish_candidate (
        status,
        next_attempt_at,
        created_at,
        id
    ),
    KEY idx_outbox_sending_recovery (status, claimed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
