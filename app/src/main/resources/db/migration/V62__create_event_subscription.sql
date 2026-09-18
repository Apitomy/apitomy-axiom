-- EventSubscription: a filtered view over the event stream that routes
-- matching events to a destination (Manager triage, workflow dispatch, etc).
CREATE TABLE event_subscription (
    id BIGINT PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    filters TEXT,
    created_on TIMESTAMP NOT NULL,
    modified_on TIMESTAMP NOT NULL
);

CREATE SEQUENCE IF NOT EXISTS event_subscription_SEQ START WITH 1 INCREMENT BY 50;

CREATE TABLE event_subscription_label (
    event_subscription_id BIGINT NOT NULL REFERENCES event_subscription(id) ON DELETE CASCADE,
    label VARCHAR(255) NOT NULL,
    UNIQUE (event_subscription_id, label)
);
