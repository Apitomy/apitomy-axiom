CREATE TABLE IF NOT EXISTS dashboard (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(255) NOT NULL,
    description TEXT,
    is_default  BOOLEAN DEFAULT FALSE,
    widgets     TEXT,
    created_on  TIMESTAMP NOT NULL,
    updated_on  TIMESTAMP NOT NULL
);
CREATE SEQUENCE IF NOT EXISTS dashboard_SEQ START WITH 1 INCREMENT BY 50;

CREATE TABLE IF NOT EXISTS dashboard_label (
    dashboard_id BIGINT NOT NULL,
    label        VARCHAR(255) NOT NULL,
    UNIQUE (dashboard_id, label),
    FOREIGN KEY (dashboard_id) REFERENCES dashboard(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_dashboard_label ON dashboard_label(label, dashboard_id);
