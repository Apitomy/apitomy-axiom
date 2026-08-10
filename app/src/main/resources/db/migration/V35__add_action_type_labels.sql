CREATE TABLE IF NOT EXISTS action_type_label (
    action_type_id BIGINT NOT NULL,
    label VARCHAR(255) NOT NULL,
    UNIQUE (action_type_id, label),
    FOREIGN KEY (action_type_id) REFERENCES action_type(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_action_type_label ON action_type_label(label, action_type_id);
