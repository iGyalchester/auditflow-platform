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
                                                                        │      │      │
                                                                        ▼      ▼      ▼
                                                                  S3 (evidence) Aurora/Postgres
                                                                                (queryable metadata)
                                                                     Kafka (enriched-events)
                                                                               │
  alerting-service  ← consumes enriched-events; matches AlertRules from DB;   ◄┘
                      notifies Slack webhook / SMTP email; records alert_history
  reporting-service → generates SOC2/GDPR/HIPAA reports from Postgres evidence metadata
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
  `ControlClassifier` → `AnomalyDetector`), fans the result out to every
  configured `DataSink` (S3 for immutable evidence, Aurora/Postgres for
  queryable metadata incl. per-event controls in `event_controls`), then
  publishes the enriched event to the `enriched-events` topic for alerting.
  Control mappings are config-driven from
  `shared/compliance-controls/*.yaml` (SOC 2, GDPR, HIPAA), loaded at
  startup by `ComplianceControlsCatalog`.
- `services/alerting-service` — consumes `enriched-events`; `RuleEngine`
  matches events against the customer's enabled `AlertRule`s loaded from
  Postgres; matches are dispatched to the rule's channels (`SlackNotifier`
  POSTs to a configured webhook, `EmailNotifier` sends via SMTP — both fall
  back to logging when unconfigured) and recorded in `alert_history`.
- `services/reporting-service` — `GET /api/v1/reports` generates
  framework-scoped evidence reports (`SOC2ReportGenerator`,
  `GDPRReportGenerator`, `HIPAAReportGenerator`) from events + controls in
  Postgres; `AthenaQueryBuilder` builds the SQL for the S3 evidence lake but
  Athena execution remains deferred. Also hosts the RAG insights endpoint —
  see "Asking compliance questions (RAG)" below.
- `services/api-gateway-service` — public REST facade: `AuditLogController`
  and `AlertController` read customer-scoped data from Postgres,
  `ReportController` proxies to reporting-service. `JwtAuthFilter` is still
  a stub that extracts the bearer token but does not verify it against
  Cognito — until then endpoints take an explicit `customerId` query param.

## What's implemented vs. stubbed

- **Real, working**: the full event path — ingestion → Kafka →
  enrichment (YAML-config-driven control classification, anomaly detection,
  S3 + Postgres persistence incl. `event_controls`) → `enriched-events` →
  alerting (DB-backed rules, Slack webhook / SMTP email notifiers,
  `alert_history`); report generation over Postgres via
  `GET /api/v1/reports`; the RAG compliance-insights endpoint on
  reporting-service (Voyage AI embeddings + in-memory vector retrieval +
  Claude answer generation, degrading gracefully without API keys); gateway
  reads for audit-logs and alerts and the report proxy; `JwtAuthFilter`
  token extraction.
- **Stubbed / deferred intentionally**: `JwtAuthFilter` doesn't verify
  signatures yet (needs Cognito/JWKS, which is out-of-scope infra), so
  gateway endpoints take `customerId` as a query parameter instead of from a
  verified token — do not expose this publicly as-is; notifiers fall back to
  logging when no webhook URL / SMTP host is configured;
  `AthenaQueryBuilder` builds SQL but nothing executes it against real
  Athena (reports source from Postgres); there's no `agent/` collector
  module yet (`PostgresCollector`/`MySQLCollector`/`APICollector` from the
  plan) and no frontend.
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

6. Read it back through the gateway (with enrichment + gateway running):

   ```bash
   curl "http://localhost:8080/api/v1/audit-logs?customerId=cust-1"
   curl "http://localhost:8080/api/v1/alerts?customerId=cust-1"
   curl "http://localhost:8080/api/v1/reports?customerId=cust-1&framework=SOC2&from=2026-01-01T00:00:00Z&to=2027-01-01T00:00:00Z"
   ```

   Alerts fire only for events matching a row in `alert_rules` (none are
   seeded by default). Notifiers log instead of delivering until
   `ALERT_SLACK_WEBHOOK_URL` (Slack) or `spring.mail.host` +
   `ALERT_EMAIL_TO` (email) are configured.

## Asking compliance questions (RAG)

reporting-service exposes a small retrieval-augmented generation endpoint
over the compliance-controls catalog: the ~12 controls from
`shared/compliance-controls/*.yaml` are embedded via Voyage AI into an
in-memory vector store, the most relevant ones are retrieved by cosine
similarity, and Claude (Anthropic Java SDK) writes an answer grounded in
them, citing controlIds.

```bash
export VOYAGE_API_KEY=pa-...        # free key from voyageai.com
export ANTHROPIC_API_KEY=sk-ant-...
mvn -pl services/reporting-service spring-boot:run

curl "http://localhost:8084/api/v1/reports/insights?question=Which%20controls%20cover%20data%20exports%3F"
```

Response: `{question, retrievalMode, generated, answer, sources:[{controlId,
framework, name, score}]}`. Both keys are optional and degrade per the
notifier convention: without `VOYAGE_API_KEY` retrieval falls back to
keyword-overlap ranking (`retrievalMode: "KEYWORD"`), and without
`ANTHROPIC_API_KEY` the answer lists the retrieved controls with
`generated: false`. The endpoint touches neither Kafka nor Postgres, so it
runs without `docker compose up`.

## Testing

Unit tests cover business logic (`ControlClassifier`,
`ComplianceControlsCatalog`, `AnomalyDetector`, `RuleEngine`, report
generators, `AthenaQueryBuilder`) directly, no mocking. Integration tests
spin up real infrastructure via Testcontainers rather than mocking
`KafkaTemplate`/`JdbcTemplate` — per the plan's "no mocking internal
components" principle: `EventIngestionIntegrationTest` (Kafka),
`AuroraWriterAdapterIntegrationTest` (Postgres), `AlertingIntegrationTest`
(Kafka + Postgres, end-to-end enriched-event → alert_history),
`AuditLogControllerIntegrationTest` / `AlertControllerIntegrationTest` /
`ReportControllerIntegrationTest` (Postgres-backed API reads). External
boundaries are faked at the wire, not the class: Slack via
`MockRestServiceServer`, email via a real in-process SMTP server
(GreenMail). They're annotated `@Testcontainers(disabledWithoutDocker =
true)`, so `mvn clean install` succeeds even on a machine without Docker
(the integration tests are skipped, not failed); run with Docker available
to actually exercise them. The parent pom passes `-Dapi.version=1.44` to
the surefire fork via `argLine` — required on Docker Engine 29+, and it must
be `argLine` (not `systemPropertyVariables`, which is applied too late for
docker-java and silently skips the tests).

## Open questions

- No existing Terraform/infra repo for AuditFlow was found under
  `Documents\GitHub` on this machine — if it lives elsewhere (another
  machine, a separate cloud repo), link it here once located.
- JWT signature verification is the biggest remaining gap: until it exists,
  `customerId` comes from the query string, so the gateway must not be
  exposed beyond a trusted network.
- `agent/` (PostgresCollector/MySQLCollector/APICollector) and `frontend/`
  are not started yet.
