CREATE TABLE users (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email         VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    display_name  VARCHAR(100) NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX idx_users_email ON users (LOWER(email));

-- Now that a real users table exists, tasks.user_id can be a real FK
-- instead of an unenforced UUID column.
ALTER TABLE tasks
    ADD CONSTRAINT fk_tasks_user FOREIGN KEY (user_id) REFERENCES users (id);
