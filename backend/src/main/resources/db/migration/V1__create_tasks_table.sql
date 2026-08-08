CREATE TABLE tasks (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    title           VARCHAR(200) NOT NULL,
    description     TEXT,
    repo_url        VARCHAR(500) NOT NULL,
    user_id         UUID NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    context         JSONB,
    result_summary  TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_tasks_user_id ON tasks (user_id);
CREATE INDEX idx_tasks_user_id_status ON tasks (user_id, status);

-- GIN index so we can query into context (e.g. context->>'repoBranch') once
-- the orchestrator starts writing structured fields into it, without
-- needing a migration for every new key.
CREATE INDEX idx_tasks_context_gin ON tasks USING GIN (context);
