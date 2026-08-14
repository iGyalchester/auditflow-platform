# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

AuditFlow is a continuous compliance monitoring SaaS backbone: it captures data-access activity,
enriches it against SOC 2 / GDPR / HIPAA controls, alerts on anomalies, and generates audit-ready
reports. This repo is a multi-module Maven/Spring Boot project — the application backbone only.
**Cloud infrastructure (Terraform/AWS) lives in a separate repo and is out of scope here**; nothing
in this repo provisions real AWS resources. Locally, Kafka/Postgres/S3 are simulated via Docker
(Kafka container, Postgres container, LocalStack for S3) so services run without touching AWS.

See "What's implemented vs. stubbed" below before assuming a piece of functionality is real.

## Commands

Requires JDK 17+, Maven, and Docker.

On this machine `mvn` is not on PATH for either shell; use the Maven wrapper dist, e.g. in
PowerShell:

```powershell
$mvn = (Resolve-Path "$env:USERPROFILE\.m2\wrapper\dists\apache-maven-3.9.16-bin\*\apache-maven-3.9.16\bin\mvn.cmd").Path
& $mvn clean install
```

```bash
# Start local infra (Kafka, Postgres, LocalStack S3)
docker compose up -d

# One-time: create the local S3 bucket in LocalStack
aws --endpoint-url=http://localhost:4566 s3 mb s3://auditflow-events

# Build everything (compiles + runs unit tests; integration tests are
# @Testcontainers(disabledWithoutDocker = true), so this succeeds even
# without Docker running — they're skipped, not failed)
mvn clean install

# Run a single module's tests
mvn -pl services/enrichment-service test

# Run a single test class
mvn -pl services/enrichment-service test -Dtest=ControlClassifierTest

# Run a single service (each is independently bootable)
mvn -pl services/ingestion-service spring-boot:run
```

Ports: `api-gateway-service` 8080, `ingestion-service` 8081, `enrichment-service` 8082,
`alerting-service` 8083, `reporting-service` 8084.

Post a test event once ingestion-service is running:

```bash
curl -X POST http://localhost:8081/api/v1/events \
  -H "Content-Type: application/json" \
  -d '{"eventId":"evt-1","customerId":"cust-1","userId":"user-1","type":"DATA_EXPORT","resource":"customers_table","action":"EXPORT"}'
```

CI (`.github/workflows/build.yml`) runs `mvn -B clean install` on push to `main` and on every PR,
with Docker available so the Testcontainers integration tests execute for real (not skipped).

## Architecture

```
                 ┌──────────────────┐
  client apps →  │ api-gateway-      │  REST facade: audit-logs + alerts read Postgres
                 │ service           │  directly; reports proxy to reporting-service
                 └──────────────────┘

  source DBs  →  ingestion-service  →  Kafka (audit-events)  →  enrichment-service
  / APIs          (validate, publish)                             (classify, detect
                                                                     anomalies, persist)
                                                                        │      │      │
                                                                        ▼      ▼      ▼
                                                                  S3 (evidence) Aurora/Postgres
                                                                     Kafka (enriched-events)
                                                                               │
  alerting-service  ← consumes enriched-events; matches AlertRules from DB;   ◄┘
                      Slack webhook / SMTP notifiers; records alert_history
  reporting-service → GET /api/v1/reports: SOC2/GDPR/HIPAA reports from Postgres
```

`shared/common-lib` is the load-bearing module: it defines the interfaces and domain model every
service depends on (`DataSink`, `EventCollector`, `EventProcessor`, `ReportGenerator`;
`AuditEvent`, `ComplianceControl`, `AlertRule`; `EventType`, `RiskLevel`, package
`com.auditflow.common.*`). Business logic depends only on these interfaces, never on a concrete
cloud SDK directly — e.g. `DataSink` is implemented by `S3WriterAdapter` and
`AuroraWriterAdapter` in enrichment-service — so a storage backend can be swapped without touching
callers. When changing a shared interface, every implementer across services needs checking, not
just the one you're editing.

Per-service packages follow `com.auditflow.<service>`, split into `adapters` (external
systems: Kafka, S3, Aurora), `processors`/`generators`/`rules` (business logic), and
`api`/`controllers` (HTTP layer):

- **ingestion-service** (`com.auditflow.ingestion`) — `EventIngestionController` validates
  incoming audit events (`SchemaValidator`) and publishes them to Kafka via
  `KafkaProducerAdapter`.
- **enrichment-service** (`com.auditflow.enrichment`) — consumes raw events via
  `KafkaConsumerAdapter`, runs them through an ordered `EventProcessor` pipeline
  (`UserContextEnricher` → `ControlClassifier` → `AnomalyDetector`, ordered via
  `EventProcessor.order()`), fans the result out to every configured `DataSink`
  (`S3WriterAdapter` for immutable evidence, `AuroraWriterAdapter` for queryable metadata —
  including per-event controls into `event_controls`), then publishes the enriched event to
  `enriched-events` via `EnrichedEventPublisher`. `ControlClassifier` is config-driven:
  `ComplianceControlsCatalog` loads `shared/compliance-controls/*.yaml` (packaged onto the
  classpath by a `<resources>` entry in the enrichment pom) and fails fast on malformed files.
  This module also owns `schema.sql` (`spring.sql.init.mode: always`) — the other services read
  the same DB but never create tables.
- **alerting-service** (`com.auditflow.alerting`) — `EnrichedEventConsumer` (Kafka) →
  `AlertDispatcher`: loads the customer's enabled rules from `alert_rules`
  (`AlertRuleRepository`), matches via `RuleEngine`, notifies the rule's channels
  (`notification_channels` CSV column; empty = all), records to `alert_history`.
  `SlackNotifier` POSTs to `alerting.slack.webhook-url`; `EmailNotifier` sends via
  `JavaMailSender` — both fall back to logging when unconfigured, so no secrets are needed to
  boot or test.
- **reporting-service** (`com.auditflow.reporting`) — `ReportController`
  (`GET /api/v1/reports?customerId&framework&from&to`, ISO-8601 instants) selects the
  `ReportGenerator` by `framework()` and feeds it events+controls loaded by
  `EventQueryRepository` from Postgres. `AthenaQueryBuilder` builds SQL for the S3 evidence lake
  but nothing executes it — reports source from Postgres today. Also hosts the RAG insights
  endpoint (`com.auditflow.reporting.rag`): `GET /api/v1/reports/insights?question=` embeds the
  compliance-controls catalog via Voyage AI (`VOYAGE_API_KEY`) into an in-memory vector store and
  has Claude (`ANTHROPIC_API_KEY`, Anthropic Java SDK) generate a grounded answer citing
  controlIds; both degrade when keys are absent (keyword retrieval / retrieved-controls-only
  answer) per the notifier convention, so no secrets are needed to boot or test.
- **api-gateway-service** (`com.auditflow.gateway`) — `AuditLogController` and `AlertController`
  read customer-scoped rows from Postgres (repositories in `audit`/`alerts` packages);
  `ReportController` proxies to reporting-service via `RestClient`
  (`gateway.reporting.base-url`). `JwtAuthFilter` extracts the bearer token but does not verify
  it — until it does, every endpoint takes an explicit, unauthenticated `customerId` query param.

Each service has its own `AlertingServiceApplication`/`IngestionServiceApplication`-style
`@SpringBootApplication` entry point and is independently bootable via `spring-boot:run`.

### What's implemented vs. stubbed

- **Real, working**: the full event path (ingestion → Kafka → enrichment → S3/Postgres →
  `enriched-events` → alerting → notifiers + `alert_history`), YAML-driven control
  classification, report generation over Postgres, all gateway endpoints, `JwtAuthFilter` token
  extraction, the RAG insights endpoint (semantic retrieval + Claude generation with keys set;
  keyword retrieval + retrieved-controls-only answer without them).
- **Stubbed / deferred intentionally**: JWT signature verification (needs Cognito/JWKS —
  out-of-scope infra; `customerId` comes from the query string until then, so this isn't safe to
  expose publicly); Athena execution (`AthenaQueryBuilder` builds SQL nothing runs); notifiers
  fall back to logging when webhook/SMTP config is absent; no `agent/` collector module
  (`PostgresCollector`/`MySQLCollector`/`APICollector`); no frontend.
- **Out of scope for this repo**: Terraform/AWS infrastructure, Jenkins CI, Cognito, KMS, VPC.

## Testing conventions

Unit tests cover business logic directly, with no mocking of internal components. Integration
tests spin up real Kafka/Postgres via Testcontainers rather than mocking
`KafkaTemplate`/`JdbcTemplate` — this is a deliberate "no mocking internal components"
principle, not an oversight, so don't replace these with mocked-collaborator tests. External
boundaries are faked at the wire instead: Slack via `MockRestServiceServer` bound to the
`RestClient.Builder`, email via GreenMail (real in-process SMTP), the gateway→reporting proxy
via `MockRestServiceServer`. Integration tests are annotated
`@Testcontainers(disabledWithoutDocker = true)`; ITs that need tables create their own DDL in
`@BeforeEach` (only enrichment runs `schema.sql`), and any change to enrichment's `schema.sql`
must be mirrored in the DDL copies inside the alerting/gateway/reporting ITs.

The parent `pom.xml` passes `-Dapi.version=1.44` to the Surefire fork via `argLine` — Docker
Engine 29+ rejects the older API version `docker-java` negotiates by default, which silently
skips the Testcontainers tests instead of failing loudly. It must stay an `argLine` `-D` flag:
`systemPropertyVariables` is applied too late for docker-java's transport negotiation and the
tests skip (observed empirically). The parent also sets `maven.compiler.parameters=true` —
without `spring-boot-starter-parent`, nothing else enables `-parameters`, and Spring MVC needs it
to infer `@RequestParam` names; still prefer explicit names (`@RequestParam("customerId")`).
