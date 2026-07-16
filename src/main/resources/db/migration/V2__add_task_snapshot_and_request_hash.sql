ALTER TABLE experiment_task
    ADD COLUMN scenario_snapshot_json JSON NULL AFTER scenario_id,
    ADD COLUMN request_hash CHAR(64) NULL AFTER idempotency_key;

UPDATE experiment_task task
JOIN simulation_scenario scenario ON scenario.id = task.scenario_id
SET task.scenario_snapshot_json = JSON_OBJECT(
        'scenarioName', scenario.name,
        'description', scenario.description,
        'objective', scenario.objective,
        'config', scenario.config_json,
        'version', scenario.version
    ),
    task.request_hash = SHA2(CONCAT('legacy:', task.task_no), 256)
WHERE task.scenario_snapshot_json IS NULL
   OR task.request_hash IS NULL;

ALTER TABLE experiment_task
    MODIFY COLUMN scenario_snapshot_json JSON NOT NULL,
    MODIFY COLUMN request_hash CHAR(64) NOT NULL;
