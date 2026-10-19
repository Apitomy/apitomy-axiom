-- Axiom 3.x incremental migration 3.
--
-- Per-deployment overrides for built-in AI session templates. Built-in templates themselves are loaded from
-- the classpath; only the overridable fields (model, engine, environment) are stored here. A null column
-- means "use the built-in default".
CREATE SEQUENCE session_template_override_SEQ START WITH 1 INCREMENT BY 50;

CREATE TABLE session_template_override (
    id BIGINT NOT NULL,
    template_id VARCHAR(255) NOT NULL,
    model VARCHAR(255),
    engine VARCHAR(255),
    environment TEXT,
    PRIMARY KEY (id),
    CONSTRAINT uk_session_template_override_template_id UNIQUE (template_id)
);
