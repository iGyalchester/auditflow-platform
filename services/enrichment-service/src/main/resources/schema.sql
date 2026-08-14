CREATE TABLE IF NOT EXISTS customers (
    customer_id   VARCHAR(64) PRIMARY KEY,
    name          VARCHAR(255) NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS users (
    user_id       VARCHAR(64) PRIMARY KEY,
    customer_id   VARCHAR(64) NOT NULL REFERENCES customers(customer_id),
    email         VARCHAR(255),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS audit_events (
    event_id      VARCHAR(64) PRIMARY KEY,
    customer_id   VARCHAR(64) NOT NULL,
    user_id       VARCHAR(64),
    session_id    VARCHAR(64),
    occurred_at   TIMESTAMPTZ NOT NULL,
    event_type    VARCHAR(32) NOT NULL,
    resource      VARCHAR(512),
    action        VARCHAR(128),
    risk_level    VARCHAR(16),
    anomalous     BOOLEAN NOT NULL DEFAULT false
);
CREATE INDEX IF NOT EXISTS idx_audit_events_customer_id ON audit_events(customer_id);
CREATE INDEX IF NOT EXISTS idx_audit_events_occurred_at ON audit_events(occurred_at);

-- Which controls each event is evidence for; written by enrichment alongside
-- audit_events so reporting can filter evidence by framework without parsing
-- the S3 JSON.
CREATE TABLE IF NOT EXISTS event_controls (
    event_id      VARCHAR(64) NOT NULL,
    customer_id   VARCHAR(64) NOT NULL,
    control_id    VARCHAR(32) NOT NULL,
    framework     VARCHAR(32) NOT NULL,
    name          VARCHAR(255),
    PRIMARY KEY (event_id, control_id, framework)
);
CREATE INDEX IF NOT EXISTS idx_event_controls_customer_id ON event_controls(customer_id);

CREATE TABLE IF NOT EXISTS compliance_controls (
    control_id    VARCHAR(32) NOT NULL,
    framework     VARCHAR(32) NOT NULL,
    name          VARCHAR(255) NOT NULL,
    description   TEXT,
    PRIMARY KEY (control_id, framework)
);

CREATE TABLE IF NOT EXISTS alert_rules (
    rule_id       VARCHAR(64) PRIMARY KEY,
    customer_id   VARCHAR(64) NOT NULL,
    name          VARCHAR(255) NOT NULL,
    event_type    VARCHAR(32),
    risk_threshold VARCHAR(16),
    condition_expression TEXT,
    enabled       BOOLEAN NOT NULL DEFAULT true,
    notification_channels VARCHAR(255)
);
-- Upgrades a pre-existing local database created before the column existed
ALTER TABLE alert_rules ADD COLUMN IF NOT EXISTS notification_channels VARCHAR(255);
CREATE INDEX IF NOT EXISTS idx_alert_rules_customer_id ON alert_rules(customer_id);

CREATE TABLE IF NOT EXISTS alert_history (
    alert_id      VARCHAR(64) PRIMARY KEY,
    rule_id       VARCHAR(64) NOT NULL REFERENCES alert_rules(rule_id),
    event_id      VARCHAR(64) NOT NULL,
    customer_id   VARCHAR(64) NOT NULL,
    triggered_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    notified_channels VARCHAR(255)
);
CREATE INDEX IF NOT EXISTS idx_alert_history_customer_id ON alert_history(customer_id);
