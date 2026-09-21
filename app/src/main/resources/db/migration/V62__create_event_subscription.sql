-- EventSubscription: a filtered view over the event stream that routes
-- matching events to a destination (Manager triage, workflow dispatch, etc).
CREATE TABLE event_subscription (
    id BIGINT PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    filters TEXT,
    routing TEXT,
    created_on TIMESTAMP NOT NULL,
    modified_on TIMESTAMP NOT NULL
);

CREATE SEQUENCE IF NOT EXISTS event_subscription_SEQ START WITH 1 INCREMENT BY 50;

CREATE TABLE event_subscription_label (
    event_subscription_id BIGINT NOT NULL REFERENCES event_subscription(id) ON DELETE CASCADE,
    label VARCHAR(255) NOT NULL,
    UNIQUE (event_subscription_id, label)
);

-- Processing ledger: tracks which events have been processed by which subscriptions.
-- Survives restarts, enables retry, prevents reprocessing.
CREATE TABLE event_processing_ledger (
    id BIGINT PRIMARY KEY,
    event_id UUID NOT NULL REFERENCES stream_event(id),
    subscription_id BIGINT NOT NULL REFERENCES event_subscription(id) ON DELETE CASCADE,
    status VARCHAR(32) NOT NULL,
    error_message TEXT,
    created_on TIMESTAMP NOT NULL,
    processed_on TIMESTAMP,
    UNIQUE (event_id, subscription_id)
);

CREATE SEQUENCE IF NOT EXISTS event_processing_ledger_SEQ START WITH 1 INCREMENT BY 50;

CREATE INDEX idx_epl_status ON event_processing_ledger(status);
CREATE INDEX idx_epl_subscription ON event_processing_ledger(subscription_id);
CREATE INDEX idx_epl_event ON event_processing_ledger(event_id);
