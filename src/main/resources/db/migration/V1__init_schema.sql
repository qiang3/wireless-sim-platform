CREATE TABLE app_user (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    username VARCHAR(50) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    role VARCHAR(20) NOT NULL DEFAULT 'USER',
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_app_user_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE simulation_scenario (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    owner_id BIGINT UNSIGNED NOT NULL,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(500) NULL,
    objective VARCHAR(30) NOT NULL,
    config_json JSON NOT NULL,
    version INT UNSIGNED NOT NULL DEFAULT 0,
    archived TINYINT(1) NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_scenario_owner_created (owner_id, created_at),
    CONSTRAINT fk_scenario_owner FOREIGN KEY (owner_id) REFERENCES app_user (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE experiment_task (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    task_no VARCHAR(32) NOT NULL,
    scenario_id BIGINT UNSIGNED NOT NULL,
    creator_id BIGINT UNSIGNED NOT NULL,
    algorithm VARCHAR(20) NOT NULL,
    training_config_json JSON NOT NULL,
    priority TINYINT UNSIGNED NOT NULL DEFAULT 5,
    status VARCHAR(20) NOT NULL,
    progress TINYINT UNSIGNED NOT NULL DEFAULT 0,
    retry_count TINYINT UNSIGNED NOT NULL DEFAULT 0,
    max_retry_count TINYINT UNSIGNED NOT NULL DEFAULT 2,
    idempotency_key VARCHAR(64) NOT NULL,
    error_message VARCHAR(1000) NULL,
    lock_version INT UNSIGNED NOT NULL DEFAULT 0,
    submitted_at DATETIME(3) NULL,
    started_at DATETIME(3) NULL,
    finished_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_task_no (task_no),
    UNIQUE KEY uk_task_creator_idempotency (creator_id, idempotency_key),
    KEY idx_task_creator_status_created (creator_id, status, created_at),
    KEY idx_task_scenario (scenario_id),
    CONSTRAINT fk_task_scenario FOREIGN KEY (scenario_id) REFERENCES simulation_scenario (id),
    CONSTRAINT fk_task_creator FOREIGN KEY (creator_id) REFERENCES app_user (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE task_execution (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    task_id BIGINT UNSIGNED NOT NULL,
    attempt_no TINYINT UNSIGNED NOT NULL,
    worker_id VARCHAR(100) NULL,
    status VARCHAR(20) NOT NULL,
    heartbeat_at DATETIME(3) NULL,
    started_at DATETIME(3) NULL,
    finished_at DATETIME(3) NULL,
    error_message VARCHAR(1000) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_execution_task_attempt (task_id, attempt_no),
    KEY idx_execution_status_heartbeat (status, heartbeat_at),
    CONSTRAINT fk_execution_task FOREIGN KEY (task_id) REFERENCES experiment_task (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE simulation_result (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    task_id BIGINT UNSIGNED NOT NULL,
    throughput DECIMAL(20, 8) NULL,
    average_aoi DECIMAL(20, 8) NULL,
    convergence_step INT UNSIGNED NULL,
    metrics_json JSON NOT NULL,
    artifact_path VARCHAR(500) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_result_task (task_id),
    CONSTRAINT fk_result_task FOREIGN KEY (task_id) REFERENCES experiment_task (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
