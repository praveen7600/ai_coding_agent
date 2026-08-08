CREATE TABLE sandboxes (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    task_id          UUID NOT NULL,
    container_id     VARCHAR(64),
    image            VARCHAR(200) NOT NULL,
    status           VARCHAR(20) NOT NULL DEFAULT 'CREATING',
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_activity_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    destroyed_at     TIMESTAMPTZ
);

CREATE INDEX idx_sandboxes_task_id ON sandboxes (task_id);

-- The core race-condition guard: Postgres enforces at most one row with
-- status CREATING/RUNNING per task, even if two requests for the same task
-- try to create a sandbox at the same instant. The second insert fails with
-- a unique violation and the app re-reads the row the first request won.
CREATE UNIQUE INDEX idx_sandboxes_one_active_per_task
    ON sandboxes (task_id)
    WHERE status IN ('CREATING', 'RUNNING');

-- Reaper query pattern: find stale active sandboxes to destroy.
CREATE INDEX idx_sandboxes_status_last_activity ON sandboxes (status, last_activity_at);
