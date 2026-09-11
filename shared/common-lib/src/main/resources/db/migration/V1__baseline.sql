-- AuditFlow relational schema, baseline.
--
-- Applied by Flyway on startup. Every service in this repo runs the same
-- migrations against the same database, and Flyway's Postgres advisory lock
-- serialises them, so concurrent boots cannot collide.
--
-- Ground rule: NEVER edit a version that has been applied anywhere. Add a
-- new V<n>__<name>.sql instead. Flyway records a checksum per version and
-- refuses to start if an applied one changed - which is the point, but it
-- means an edit here turns into a failed deploy rather than a silent drift.
--
-- Unlike the script this replaced, statements here need not be idempotent
-- and may use whatever Postgres syntax is convenient: Flyway applies each
-- version exactly once and understands dollar-quoted blocks.

CREATE TABLE customers (
    customer_id   VARCHAR(64) PRIMARY KEY,
    name          VARCHAR(255) NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE users (
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
CREATE TABLE audit_events (
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

-- The gateway's list query is
--   WHERE customer_id = ? ... ORDER BY occurred_at DESC LIMIT ?
-- so it wants both halves in one index: find the tenant, then walk it
-- newest-first and stop at the limit. Without this Postgres filters by
-- customer and then sorts the whole result to answer a 50-row page.
--
-- There is deliberately no single-column index on customer_id: the primary
-- key above already leads with it.
CREATE INDEX idx_audit_events_customer_occurred
    ON audit_events(customer_id, occurred_at DESC);

-- RetentionPurgeJob deletes by time across all tenants, so it needs
-- occurred_at leading, which neither the primary key nor the composite
-- above can give it.
CREATE INDEX idx_audit_events_occurred_at ON audit_events(occurred_at);

CREATE TABLE compliance_controls (
    control_id    VARCHAR(32) NOT NULL,
    framework     VARCHAR(32) NOT NULL,
    name          VARCHAR(255) NOT NULL,
    description   TEXT,
    PRIMARY KEY (control_id, framework)
);

CREATE TABLE alert_rules (
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

CREATE INDEX idx_alert_rules_customer_id ON alert_rules(customer_id);

-- rule_id is nullable and ON DELETE SET NULL, not NOT NULL REFERENCES.
-- History is evidence: an alert that fired really did fire, and deleting
-- the rule afterwards must not erase or block that record. With the old
-- shape, deleting a rule that had ever fired failed on the foreign key -
-- a 500 from the API, with no way to remove the rule short of deleting its
-- history, which is the one thing an audit platform must not offer.
CREATE TABLE alert_history (
    alert_id      VARCHAR(64) PRIMARY KEY,
    rule_id       VARCHAR(64),
    event_id      VARCHAR(64) NOT NULL,
    customer_id   VARCHAR(64) NOT NULL,
    triggered_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    notified_channels VARCHAR(255),
    CONSTRAINT alert_history_rule_id_fkey FOREIGN KEY (rule_id)
        REFERENCES alert_rules(rule_id) ON DELETE SET NULL
);

-- Same shape as the audit_events list: WHERE customer_id = ? ORDER BY
-- triggered_at DESC LIMIT ?.
CREATE INDEX idx_alert_history_customer_triggered
    ON alert_history(customer_id, triggered_at DESC);

-- ON DELETE SET NULL scans the child table on every rule delete
CREATE INDEX idx_alert_history_rule_id ON alert_history(rule_id);
