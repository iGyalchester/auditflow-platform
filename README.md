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

  alerting-service  → evaluates AlertRules against enriched events → Slack/Email (stub notifiers)
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
  type references or constructors. `SlackNotifier`/`EmailNotifier` are stub
  notifiers (they log rather than call real webhooks/SMTP for now).
- `services/reporting-service` — `SOC2ReportGenerator`, `GDPRReportGenerator`,
  `HIPAAReportGenerator` build framework-scoped evidence reports;
  `AthenaQueryBuilder` builds the SQL that will run against the S3 evidence
  lake once Athena is wired up.
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
  Kafka produce/consume), `RuleEngine` matching logic including sandboxed
  SpEL condition expressions, report generators, `AthenaQueryBuilder`
  (identifier-validated, literal-escaped, Athena-format timestamps),
  Cognito ID-token verification in the gateway (JWKS signature, expiry,
  issuer, app-client audience, tenant claim).
- **Stubbed intentionally**: `SlackNotifier`/`EmailNotifier` log instead of
  calling real services;
  `AthenaQueryBuilder` builds SQL but nothing executes it against real
  Athena; api-gateway controllers return empty placeholder responses instead
  of querying the other services; there's no `agent/` collector module yet
  (`PostgresCollector`/`MySQLCollector`/`APICollector` from the plan), no
  compliance-controls YAML config (controls are hard-coded in
  `ControlClassifier` for now), and no frontend.
- **Out of scope for this repo**: Terraform/AWS infrastructure, Jenkins CI,
  Cognito, KMS, VPC — provisioned separately per the plan.

## Running locally

Requires JDK 17+, Maven, and Docker.

1. Start local infrastructure:

   ```bash
   docker compose up -d
   ```

2. Create the local S3 bucket in LocalStack (one-time, until this is
   automated):

   ```bash
   aws --endpoint-url=http://localhost:4566 s3 mb s3://auditflow-events
   ```

3. Build everything:

   ```bash
   mvn clean install
   ```

4. Run a service (each is independently bootable):

   ```bash
   mvn -pl services/ingestion-service spring-boot:run
   mvn -pl services/enrichment-service spring-boot:run
   ```

   Ports: `api-gateway-service` 8080, `ingestion-service` 8081,
   `enrichment-service` 8082, `alerting-service` 8083, `reporting-service`
   8084.

5. Post a test event:

   ```bash
   curl -X POST http://localhost:8081/api/v1/events \
     -H "Content-Type: application/json" \
     -H "X-Audit-Token: $AUDIT_TOKEN" \
     -d '{"eventId":"evt-1","customerId":"cust-1","userId":"user-1","type":"DATA_EXPORT","resource":"customers_table","action":"EXPORT"}'
   ```

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
