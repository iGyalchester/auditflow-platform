# AuditFlow Platform

Continuous compliance monitoring SaaS. Captures data-access activity,
enriches it against SOC 2 / GDPR / HIPAA controls, alerts on anomalies, and
generates audit-ready reports.

This repo is the application backbone: a multi-module Maven/Spring Boot
project. **Cloud infrastructure (Terraform/AWS) is provisioned in a separate
repo/environment and is out of scope here** — nothing in this repo
provisions real AWS resources. Locally, Kafka/Postgres/S3 are simulated via
Docker so the services can run without touching AWS.

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
  customer `AlertRule`s; `SlackNotifier`/`EmailNotifier` are stub notifiers
  (they log rather than call real webhooks/SMTP for now).
- `services/reporting-service` — `SOC2ReportGenerator`, `GDPRReportGenerator`,
  `HIPAAReportGenerator` build framework-scoped evidence reports;
  `AthenaQueryBuilder` builds the SQL that will run against the S3 evidence
  lake once Athena is wired up.
- `services/api-gateway-service` — public REST facade (`AuditLogController`,
  `ReportController`, `AlertController`) plus `JwtAuthFilter`, a stub that
  extracts the bearer token but does not yet verify it against Cognito.

## What's implemented vs. stubbed

This is a first-pass backbone, not a feature-complete system:

- **Real, working**: ingestion → Kafka → enrichment pipeline (processors,
  Kafka produce/consume), `RuleEngine` matching logic, report generators,
  `AthenaQueryBuilder`, `JwtAuthFilter` token extraction.
- **Stubbed intentionally**: `SlackNotifier`/`EmailNotifier` log instead of
  calling real services; `JwtAuthFilter` doesn't verify signatures yet;
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

## Open questions

- No existing Terraform/infra repo for AuditFlow was found under
  `Documents\GitHub` on this machine — if it lives elsewhere (another
  machine, a separate cloud repo), link it here once located.
- Compliance-control-to-event mapping in `ControlClassifier` is currently
  hard-coded; the plan calls for config-driven YAML controls
  (`shared/compliance-controls/soc2-controls.yaml`) — worth doing before
  this goes further than a demo.
- `agent/` (PostgresCollector/MySQLCollector/APICollector) and `frontend/`
  are not started yet.
