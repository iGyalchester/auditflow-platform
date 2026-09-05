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

-- The primary key is (customer_id, event_id), not event_id alone. Event ids
-- are chosen by the source: the collector agent hashes (time, thread,
-- statement), applications use a UUID, and nothing stops two customers
-- producing the same one. With event_id alone the second customer's event
-- silently lost the ON CONFLICT race and vanished from their audit trail.
CREATE TABLE IF NOT EXISTS audit_events (
    event_id      VARCHAR(64) NOT NULL,
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
    controls      TEXT,
    CONSTRAINT audit_events_customer_event_pk PRIMARY KEY (customer_id, event_id)
);
ALTER TABLE audit_events ADD COLUMN IF NOT EXISTS controls TEXT;
-- Migration for a database created before the key was widened. Build the
-- unique index first, then drop the old single-column key: plain statements
-- only, because Spring's ScriptUtils splits on ';' and cannot read a DO block.
-- A migrated table ends with a unique index rather than a declared PRIMARY
-- KEY, which is what ON CONFLICT (customer_id, event_id) actually needs; a
-- fresh table gets the constraint above and these two lines are no-ops.
CREATE UNIQUE INDEX IF NOT EXISTS audit_events_customer_event_pk ON audit_events(customer_id, event_id);
ALTER TABLE audit_events DROP CONSTRAINT IF EXISTS audit_events_pkey;
-- The gateway's list query is
--   WHERE customer_id = ? ... ORDER BY occurred_at DESC LIMIT ?
-- so it wants both halves in one index: find the tenant, then walk it
-- newest-first and stop at the limit. Without this Postgres filters by
-- customer and then sorts the whole result to answer a 50-row page.
CREATE INDEX IF NOT EXISTS idx_audit_events_customer_occurred
    ON audit_events(customer_id, occurred_at DESC);

-- Redundant since the key became (customer_id, event_id): that index already
-- has customer_id as its leading column, so this one duplicates it and costs
-- a write on every insert for nothing.
DROP INDEX IF EXISTS idx_audit_events_customer_id;

-- Kept: RetentionPurgeJob deletes by time across all tenants, so it needs
-- occurred_at leading, which neither the primary key nor the composite above
-- can give it.
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
-- Same shape as the audit_events list: WHERE customer_id = ? ORDER BY
-- triggered_at DESC LIMIT ?.
CREATE INDEX IF NOT EXISTS idx_alert_history_customer_triggered
    ON alert_history(customer_id, triggered_at DESC);

-- Covered by the leading column of the composite above.
DROP INDEX IF EXISTS idx_alert_history_customer_id;

-- ON DELETE SET NULL scans the child table on every rule delete
CREATE INDEX IF NOT EXISTS idx_alert_history_rule_id ON alert_history(rule_id);
