-- AuditFlow relational schema (Aurora PostgreSQL / local Postgres).
-- Shared by every service through common-lib and applied on startup with
-- spring.sql.init (schema-locations=classpath:auditflow-schema.sql), so
-- whichever service boots first creates the tables. Every statement is
-- idempotent: CREATE ... IF NOT EXISTS for new databases, ALTER ... ADD
-- COLUMN IF NOT EXISTS to evolve existing ones.

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
    anomalous     BOOLEAN NOT NULL DEFAULT false,
    -- "FRAMEWORK:CONTROL,FRAMEWORK:CONTROL" as classified by enrichment
    controls      TEXT
);
ALTER TABLE audit_events ADD COLUMN IF NOT EXISTS controls TEXT;
CREATE INDEX IF NOT EXISTS idx_audit_events_customer_id ON audit_events(customer_id);
CREATE INDEX IF NOT EXISTS idx_audit_events_occurred_at ON audit_events(occurred_at);

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
    description   TEXT,
    -- comma-separated channel names, e.g. "slack,email"
    notification_channels VARCHAR(255)
);
ALTER TABLE alert_rules ADD COLUMN IF NOT EXISTS description TEXT;
ALTER TABLE alert_rules ADD COLUMN IF NOT EXISTS notification_channels VARCHAR(255);
CREATE INDEX IF NOT EXISTS idx_alert_rules_customer_id ON alert_rules(customer_id);

-- rule_id is nullable and ON DELETE SET NULL, not NOT NULL REFERENCES.
-- History is evidence: an alert that fired really did fire, and deleting
-- the rule afterwards must not erase or block that record. With the old
-- shape, deleting a rule that had ever fired failed on the foreign key -
-- a 500 from the API, with no way to remove the rule short of deleting its
-- history, which is the one thing an audit platform must not offer.
CREATE TABLE IF NOT EXISTS alert_history (
    alert_id      VARCHAR(64) PRIMARY KEY,
    rule_id       VARCHAR(64),
    event_id      VARCHAR(64) NOT NULL,
    customer_id   VARCHAR(64) NOT NULL,
    triggered_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    notified_channels VARCHAR(255),
    CONSTRAINT alert_history_rule_id_fkey FOREIGN KEY (rule_id)
        REFERENCES alert_rules(rule_id) ON DELETE SET NULL
);
-- Migration for a database created with the old shape. Plain statements
-- only: Spring's ScriptUtils splits on ';' and cannot read a DO block.
ALTER TABLE alert_history ALTER COLUMN rule_id DROP NOT NULL;
ALTER TABLE alert_history DROP CONSTRAINT IF EXISTS alert_history_rule_id_fkey,
    ADD CONSTRAINT alert_history_rule_id_fkey FOREIGN KEY (rule_id)
        REFERENCES alert_rules(rule_id) ON DELETE SET NULL;
CREATE INDEX IF NOT EXISTS idx_alert_history_customer_id ON alert_history(customer_id);
-- ON DELETE SET NULL scans the child table on every rule delete
CREATE INDEX IF NOT EXISTS idx_alert_history_rule_id ON alert_history(rule_id);
