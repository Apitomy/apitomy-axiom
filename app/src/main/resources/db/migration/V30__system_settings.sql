CREATE TABLE IF NOT EXISTS system_settings (
    id                             BIGINT AUTO_INCREMENT PRIMARY KEY,
    manager_max_turns              INT NOT NULL DEFAULT 5,
    manager_confidence_threshold   DOUBLE NOT NULL DEFAULT 0.7,
    manager_timeout_seconds        INT NOT NULL DEFAULT 120,
    manager_model                  VARCHAR(255),
    claude_code_max_turns          INT NOT NULL DEFAULT 50,
    claude_code_max_budget_usd     DOUBLE NOT NULL DEFAULT 5.0,
    claude_code_timeout_seconds    INT NOT NULL DEFAULT 600,
    claude_code_model              VARCHAR(255),
    claude_code_available_models   TEXT NOT NULL,
    opencode_max_steps             INT NOT NULL DEFAULT 50,
    opencode_timeout_seconds       INT NOT NULL DEFAULT 600,
    opencode_model                 VARCHAR(255),
    opencode_available_models      TEXT NOT NULL,
    assistant_max_sessions         INT NOT NULL DEFAULT 3,
    assistant_timeout_seconds      INT NOT NULL DEFAULT 300,
    ai_engine                      VARCHAR(255) NOT NULL DEFAULT 'claude-code',
    event_source_log_retention_days INT NOT NULL DEFAULT 7,
    script_timeout_seconds         INT NOT NULL DEFAULT 60
);

INSERT INTO system_settings (
    claude_code_available_models,
    opencode_available_models
) VALUES (
    'claude-opus-4-7,claude-sonnet-4-6,claude-opus-4-6,claude-haiku-4-5-20251001,opus,sonnet,haiku',
    'anthropic/claude-sonnet-4-6,anthropic/claude-opus-4-6,anthropic/claude-haiku-4-5-20251001,openai/gpt-4o,openai/o3-mini'
);
