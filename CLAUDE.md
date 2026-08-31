# CLAUDE.md — AuditFlow Platform

## What this is

The application backbone of **AuditFlow**, a continuous compliance-monitoring
SaaS: capture data-access activity, classify each event as evidence for
SOC 2 / GDPR / HIPAA controls, flag anomalies, alert, and generate
audit-ready reports. Java 17 / Spring Boot 3.3.2 multi-module Maven build.
Cloud infrastructure lives in the companion repo
**`auditflow-infrastructure`** (Terraform/AWS); nothing here provisions AWS.

## Owner context

- Owner: **Boris Gerard** (GitHub `iGyalchester`). Associate Software
  Engineer at JPMorganChase through Aug 2026; IBM ODM V8.10 Developer
  certified — deep experience with Decision Center / Rule Designer /
  Decision Server, rule flows, decision tables, and an Ab Initio→ODM
  migration. AWS Cloud Practitioner. This background matters here: the
  alerting `RuleEngine` is intentionally an "ODM in embryo" (rules as
  data evaluated against facts) and he understands that tradeoff space
  better than most — pitch rules-engine discussions at that level.
- Part of his three-repo portfolio ("my stack") with
  `auditflow-infrastructure` and `Resistance` (a Spring Boot 4 + React
  job-application tracker).
- His workflow preference across repos: small branches + PRs that **he
  merges himself** (never merge or approve for him), tests accompanying
  every change.

## Architecture in one paragraph

`ingestion-service` (8081) validates a JSON event and publishes it to
Kafka keyed by `customerId` → `enrichment-service` (8082) consumes and
runs the ordered `EventProcessor` chain (`UserContextEnricher` 10 →
`ControlClassifier` 20 → `AnomalyDetector` 30), then fans out to every
`DataSink`: S3 (immutable evidence JSON, `customerId/eventId.json`) and
Aurora/Postgres (queryable metadata, idempotent insert). `alerting-service`
matches events against customer `AlertRule`s; `reporting-service` holds the
SOC2/GDPR/HIPAA generators and `AthenaQueryBuilder`; `api-gateway-service`
(8080) is the REST facade. Everything depends on `shared/common-lib`'s
interfaces (`DataSink`, `EventProcessor`, `ReportGenerator`,
`EventCollector`) around the immutable builder-based `AuditEvent` —
multi-tenant (`customerId`) at every layer.

## Ground rules

- **Testing philosophy: no mocking internal components.** Unit-test
  business logic directly; integration-test transport/persistence against
  real Kafka/Postgres via Testcontainers
  (`@Testcontainers(disabledWithoutDocker = true)` so `mvn clean install`
  passes without Docker; CI runs them for real).
- Local dev: `docker compose up -d` (Kafka KRaft, Postgres 16, LocalStack
  S3; create the `auditflow-events` bucket by hand, see README).
- Keep business logic behind the `common-lib` interfaces — a new storage
  backend is a new `DataSink`, never a rewrite of callers.

## Known gaps (deliberate, tracked)

- `JwtAuthFilter` extracts but does **not verify** tokens (Cognito JWKS
  verification pending); the real gate today is the API Gateway JWT
  authorizer in the infra repo.
- api-gateway controllers return empty placeholders; no `agent/`
  collectors; controls hard-coded in `ControlClassifier` (roadmap:
  YAML-driven); notifiers log instead of sending; no frontend yet
  (Cognito dev callback expects a Vite app on `localhost:5173`).
- `AthenaQueryBuilder` is injection-hardened (identifier validation,
  escaped literals, Athena-format timestamps) but still only *builds* SQL —
  nothing executes it; switch to Athena execution parameters when that
  lands.
- `AlertRule.conditionExpression` **is live**: a sandboxed SpEL predicate
  (`ConditionEvaluator`, SimpleEvaluationContext — no type refs or
  constructors; customer expressions stay untrusted input). Drools remains
  the upgrade path if RETE-class evaluation is ever needed.
- Deploy path exists (`Dockerfile`, `aws` profile with MSK IAM + real S3,
  manual Deploy workflow) — compute is gated by `ecs_enabled` in the infra
  repo.
