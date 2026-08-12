-- Scheduled Jobs: CRON-style automation for Axiom

CREATE TABLE scheduled_job (
    id              BIGSERIAL PRIMARY KEY,
    name            VARCHAR(255) NOT NULL UNIQUE,
    description     TEXT,
    enabled         BOOLEAN NOT NULL DEFAULT FALSE,

    -- Schedule
    schedule            VARCHAR(50) NOT NULL,
    schedule_time       VARCHAR(10),
    schedule_day_of_week VARCHAR(20),
    next_run_at         TIMESTAMP,
    last_run_at         TIMESTAMP,

    -- Execution
    execution_mode      VARCHAR(20) NOT NULL,
    prompt_template     TEXT,
    script_template     TEXT,
    model               VARCHAR(255),
    engine              VARCHAR(255),
    allowed_tools       TEXT,
    max_steps           INTEGER,
    max_budget_usd      DOUBLE PRECISION,
    environment         TEXT,

    -- Timestamps
    created_on          TIMESTAMP NOT NULL,
    updated_on          TIMESTAMP NOT NULL
);

CREATE TABLE scheduled_job_label (
    scheduled_job_id    BIGINT NOT NULL REFERENCES scheduled_job(id) ON DELETE CASCADE,
    label               VARCHAR(255) NOT NULL
);

CREATE TABLE scheduled_job_run (
    id              BIGSERIAL PRIMARY KEY,
    job_id          BIGINT NOT NULL REFERENCES scheduled_job(id) ON DELETE CASCADE,
    status          VARCHAR(20) NOT NULL,
    run_trigger     VARCHAR(20) NOT NULL DEFAULT 'scheduled',
    started_at      TIMESTAMP,
    completed_at    TIMESTAMP,
    output          TEXT,
    error           TEXT,
    execution_log   TEXT,
    cost_usd        DOUBLE PRECISION,
    duration_ms     BIGINT,
    trace_id        UUID,
    created_on      TIMESTAMP NOT NULL
);

CREATE INDEX idx_scheduled_job_run_job_id ON scheduled_job_run(job_id);
CREATE INDEX idx_scheduled_job_run_status ON scheduled_job_run(status);
