# AuditFlow Platform

Continuous compliance monitoring SaaS. Captures data-access activity,
enriches it against SOC 2 / GDPR / HIPAA controls, alerts on anomalies, and
generates audit-ready reports.

This repo is the application backbone: a multi-module Maven/Spring Boot
project. Cloud infrastructure (Terraform/AWS) lives in the companion
[`auditflow-infrastructure`](https://github.com/iGyalchester/auditflow-infrastructure)
repo — nothing here provisions AWS resources, but the Deploy workflow (see
below) ships images onto the ECS Fargate services it defines. Locally,
Kafka/Postgres/S3 are simulated via Docker so the services run without
touching AWS.

## Architecture

```
                 ┌──────────────────┐
  client apps →  │ api-gateway-      │  REST facade (audit logs, reports, alerts)
                 │ service           │
                 └──────────────────┘

  source DBs  →  ingestion-service  →  Kafka (audit-events)  →  enrichment-service
  / APIs          (validate, publish)                             (classify, detect
                                                                     anomalies, persist)
                                                                        │      │
                                                                        ▼      ▼
                                                                  S3 (evidence) Aurora/Postgres
                                                                                (queryable metadata)

  alerting-service  → consumes audit-events-enriched → AlertRules (file-backed) → Slack webhook / SES email
  reporting-service → generates SOC2/GDPR/HIPAA reports from stored evidence
```

### A real producer: Resistance

![Resistance, a job-application tracker, pushes audit events to this platform's ingestion service and is also polled by the collector agent, which ships its MySQL query log; events flow through Kafka and enrichment into S3 Object Lock and Aurora](docs/resistance-auditflow-flow.svg)

[Resistance](https://github.com/iGyalchester/Resistance) is the first system
wired into this platform. Its services **push** login, data-change, and PII-access
events to `ingestion-service` (fire-and-forget on their side), and the
`collector-agent` **pulls** its MySQL `general_log` with checkpointed, at-least-once
delivery. Both routes go through the same token-checked endpoint.

### Modules

- `shared/common-lib` — the interfaces and domain model every service depends
  on: `DataSink`, `EventCollector`, `EventProcessor`, `ReportGenerator`;
  `AuditEvent`, `ComplianceControl`, `AlertRule`; `EventType`, `RiskLevel`.
  Business logic depends only on these interfaces, never on a concrete cloud
  SDK (`DataSink` is implemented by `S3WriterAdapter`, `AuroraWriterAdapter`,
  etc.), so a storage backend can be swapped without touching callers.
- `services/ingestion-service` — REST endpoint that validates incoming audit
  events and publishes them to Kafka.
- `services/enrichment-service` — consumes raw events, runs them through an
  ordered `EventProcessor` pipeline (`UserContextEnricher` →
  `ControlClassifier` → `AnomalyDetector`), then fans the result out to every
  configured `DataSink` (S3 for immutable evidence, Aurora/Postgres for
  queryable metadata).
- `services/alerting-service` — `RuleEngine` matches enriched events against
  customer `AlertRule`s: event type, risk threshold, and an optional
  `conditionExpression` — a sandboxed SpEL predicate over the event (e.g.
  `anomalous && resource == 'customers_table'`), evaluated in
  `SimpleEvaluationContext` so customer-supplied expressions can't reach
  type references or constructors. `EnrichedEventListener` feeds it from
  `audit-events-enriched`, `FileRuleRepository` supplies each customer's
  rules, and `AlertDispatcher` fans a match out to every channel the rule
  names: `SlackNotifier` (incoming webhook) and `EmailNotifier` (Amazon
  SES), each logging instead when unconfigured. One channel failing never
  blocks another.
- `services/reporting-service` — `AthenaQueryBuilder` builds the SQL that
  will run against the S3 evidence lake once Athena is wired up (the lake
  path for windows too large to serve from Aurora). The framework report
  generators live in `common-lib` (`com.auditflow.common.reports`) and are
  served by the gateway over Aurora today.
- `services/api-gateway-service` — public REST facade (`AuditLogController`,
  `ReportController`, `AlertController`) and the Cognito resource-server
  security (`SecurityConfig`, `CognitoTokenValidator`, `CurrentCustomer`):
  every `/api/**` call must carry a Cognito **ID token** for our app client,
  verified against the pool's JWKS (signature, expiry, issuer, audience) and
  required to name a tenant via `custom:customer_id`. Open in the default
  profile (customer from an `X-Customer-Id` header, dev only), enforced
  under the `aws` profile with fail-fast config. `GET /api/v1/me` echoes
  what the gateway decided.
- `agent/collector-agent` — the pull-based intake path, for source systems
  you can't modify: tails MySQL's general query log (`log_output=TABLE`),
  **redacts every literal client-side** (an audit trail must not become
  the PII leak it exists to detect), assigns deterministic event ids so
  restarts/retries dedupe downstream, and forwards through the same
  authenticated ingestion endpoint as every push source. Checkpoint
  advances only after confirmed delivery (at-least-once). Postgres and
  generic-API collectors are the same `EventCollector` seam, unbuilt.

## What's implemented vs. stubbed

This is a first-pass backbone, not a feature-complete system:

- **Real, working**: ingestion → Kafka → enrichment pipeline (processors,
  Kafka produce/consume; ingestion answers 202 only after the broker
  acknowledges the record - acks=all, idempotent producer - and 503 with
  Retry-After otherwise, so sources retry and the pull path is
  at-least-once end to end; the topic is declared on startup with a
  version-controlled retention), `RuleEngine` matching logic including sandboxed
  SpEL condition expressions, report generators, `AthenaQueryBuilder`
  (identifier-validated, literal-escaped, Athena-format timestamps),
  Cognito ID-token verification in the gateway (JWKS signature, expiry,
  issuer, app-client audience, tenant claim).
- **Real, working (alerting)**: enrichment republishes every enriched event
  to `audit-events-enriched`; alerting-service consumes it, loads rules
  from a JSON file (`audit.alerting.rules-file`, behind a `RuleRepository`
  seam), runs the engine, and notifies through a Slack incoming webhook
  (`ALERT_SLACK_WEBHOOK_URL`) and Amazon SES (`ALERT_EMAIL_FROM`/`_TO`).
  Unconfigured channels log instead of sending, so a local run needs
  neither. `rules.example.json` ships Resistance-flavoured rules.
- **Real, working (gateway reads)**: `GET /api/v1/audit-logs` (filters:
  `type`, `from`, `to`, `limit`) and `GET /api/v1/alerts` read Aurora
  scoped by the customer the security layer established - the tenant is a
  query parameter the caller never controls. Every alert that fires is
  recorded in `alert_history` with the channels that got through, and the
  rules file is synced into `alert_rules` on startup so rules are data.
  The schema lives once, in `common-lib` (`auditflow-schema.sql`), and every
  service applies it idempotently on boot - whichever starts first wins.
- **Real, working (rule management)**: `/api/v1/alert-rules` (list, get,
  create, replace, delete) on the gateway, scoped to the calling customer;
  a condition is validated on write with the same sandboxed SpEL evaluator
  alerting runs (now in `common-lib`), so `T(java.lang.Runtime)` is a 400,
  not a silently dead rule. alerting-service reads `alert_rules` and
  reloads every `audit.alerting.rules-refresh` (30 s); `rules.example.json`
  is only a seed, upserted on startup.
- **Real, working (reports)**: `GET /api/v1/reports/{soc2|gdpr|hipaa}?from&to`
  generates the framework's evidence report over the customer's stored
  events (enrichment now persists each event's controls, so the generators -
  shared in `common-lib` - have what they filter on). Defaults to the last
  30 days; `GET /api/v1/reports` lists the frameworks.
- **Real, working (rate limiting)**: per-client-IP token buckets on the
  gateway's `/api/**` (20/s, burst 40) and the ingestion endpoint (200/s,
  burst 500), ahead of authentication, answering 429 + `Retry-After`;
  `X-Forwarded-For` is trusted only under the `aws` profile (behind the
  ALB). In-memory and per instance by design - see `TokenBucketLimiter`.
- **Stubbed intentionally**: notifier destinations are global, not per
  customer;
  `AthenaQueryBuilder` builds SQL but nothing executes it against real
  Athena - reports run over Aurora (capped at 10,000 events per window,
  413 above that) and the lake path is the eventual answer for bigger
  windows; the `agent/` module has only the MySQL
  collector (the plan's Postgres and generic-API collectors are unbuilt), no
  compliance-controls YAML config (controls are hard-coded in
  `ControlClassifier` for now), and no frontend.
- **Out of scope for this repo**: Terraform/AWS infrastructure, Jenkins CI,
  Cognito, KMS, VPC — provisioned separately per the plan.

## Running locally

Requires JDK 17+, Maven, and Docker.

**Everything in containers** (the demo path):

```bash
mvn -DskipTests clean package
docker compose --profile app up --build -d
```

That starts Kafka, Postgres and LocalStack (the evidence bucket is created
by an init hook), then ingestion (8081), enrichment (8082), alerting (8083),
the gateway (8080) and reporting (8084). The gateway runs with auth
**open** and the ingestion token **empty** unless you export
`AUDIT_AUTH_ENABLED=true` + `COGNITO_*` / `AUDIT_INGESTION_TOKEN` first.

**Infrastructure only** (for `spring-boot:run` from your IDE):

```bash
docker compose up -d
mvn clean install
mvn -pl services/ingestion-service spring-boot:run     # and so on per service
```

### Try the API

```bash
# 1. an event arrives (what Resistance's audit client sends on a failed login)
curl -s -X POST localhost:8081/api/v1/events -H 'Content-Type: application/json' \
  -H "X-Audit-Token: ${AUDIT_INGESTION_TOKEN:-}" \
  -d '{"eventId":"demo-1","customerId":"resistance","userId":"boris@example.com",
       "type":"AUTH_EVENT","resource":"login","action":"LOGIN_FAILURE","ipAddress":"203.0.113.7"}'

# 2. it is queryable, scoped to the customer (X-Customer-Id stands in for the JWT claim while auth is open)
curl -s -H 'X-Customer-Id: resistance' 'localhost:8080/api/v1/audit-logs?type=AUTH_EVENT&limit=5'

# 3. the seeded "Failed login attempt" rule fired and was recorded
curl -s -H 'X-Customer-Id: resistance' localhost:8080/api/v1/alerts

# 4. customers manage rules themselves; a bad condition is refused at write time
curl -s -X POST localhost:8080/api/v1/alert-rules -H 'X-Customer-Id: resistance' \
  -H 'Content-Type: application/json' \
  -d '{"name":"Anomalous export","eventType":"DATA_EXPORT","conditionExpression":"anomalous","notificationChannels":["slack"]}'

# 5. a SOC 2 evidence report over the last 30 days
curl -s -H 'X-Customer-Id: resistance' localhost:8080/api/v1/reports/soc2
```

## Retention

Every store has a stated policy, in version control:

- **S3 evidence**: Object Lock (COMPLIANCE) for the infra's
  `object_lock_retention_days`; never expired automatically - the bucket
  policy forbids lifecycle rules on purpose.
- **Kafka topics**: `audit-events` and `audit-events-enriched` are declared
  by their producers on startup with `retention.ms` = 7 days (MSK
  Serverless retention is per topic).
- **Aurora metadata**: `enrichment-service` purges `audit_events` rows
  older than `audit.retention.days` (400, a little longer than the evidence
  lock so reports can always resolve locked evidence) nightly, in batches
  (`RetentionPurgeJob`). `AUDIT_RETENTION_ENABLED=false` switches it off.

## Testing

Unit tests cover business logic (`ControlClassifier`, `AnomalyDetector`,
`RuleEngine`, report generators, `AthenaQueryBuilder`) directly, no mocking.
Integration tests (`EventIngestionIntegrationTest`,
`AuroraWriterAdapterIntegrationTest`) spin up real Kafka/Postgres via
Testcontainers rather than mocking `KafkaTemplate`/`JdbcTemplate` — per the
plan's "no mocking internal components" principle. They're annotated
`@Testcontainers(disabledWithoutDocker = true)`, so `mvn clean install`
succeeds even on a machine without Docker (the integration tests are
skipped, not failed); run with Docker available to actually exercise them.

## Deploying to AWS

Images are built from the generic `Dockerfile` (one `--build-arg MODULE=...`
per service) and pushed by the manual **Deploy** workflow
(Actions → Deploy → pick dev/staging/prod), which authenticates through the
same GitHub-OIDC role the infra repo uses — no AWS keys in secrets. Set the
`AWS_REGION` and `AWS_ROLE_ARN` repository variables first (from the infra
repo's bootstrap outputs).

On ECS the containers run with `SPRING_PROFILES_ACTIVE=aws`, which flips
two things the local profile fakes:

- **Kafka → MSK Serverless with IAM auth** (`audit.kafka.msk-iam=true`):
  SASL_SSL + `aws-msk-iam-auth`, so the broker authenticates the ECS task
  role — no Kafka credentials exist anywhere.
- **S3 → the real evidence bucket**: the LocalStack endpoint override is
  blanked, so the SDK uses the regional endpoint and task-role credentials.

Aurora credentials arrive as environment variables injected by ECS from the
RDS-managed Secrets Manager secret. Rollout order lives in the infra repo's
README: apply (creates ECR), run Deploy (pushes images), flip
`ecs_enabled = true`, apply again.

## Open questions

- Compliance-control-to-event mapping in `ControlClassifier` is currently
  hard-coded; the plan calls for config-driven YAML controls
  (`shared/compliance-controls/soc2-controls.yaml`) — worth doing before
  this goes further than a demo.
- `agent/` (PostgresCollector/MySQLCollector/APICollector) and `frontend/`
  are not started yet.
