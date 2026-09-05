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
matches events against customer `AlertRule`s. The SOC2/GDPR/HIPAA report
generators live in `common-lib` (`com.auditflow.common.reports`) and are
served by `api-gateway-service` (8080), the REST facade, straight from
Aurora; `AthenaQueryBuilder`, the not-yet-executed lake path, sits beside
them in common-lib. Everything depends on `shared/common-lib`'s
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
  S3; the `auditflow-events` bucket is created for you by
  `docker/localstack-init/create-bucket.sh`, mounted into LocalStack's
  `ready.d`). To run the services in containers too:
  `mvn -DskipTests clean package && docker compose --profile app up --build -d`,
  optionally with `AUDIT_INGESTION_TOKENS="tenant=token"` to lock the
  intake down (unset means open, which is the dev default).
- Keep business logic behind the `common-lib` interfaces — a new storage
  backend is a new `DataSink`, never a rewrite of callers.
- **Schema changes are Flyway migrations in
  `shared/common-lib/src/main/resources/db/migration/`.** Never edit a
  version that has been applied anywhere; add a new `V<n>__<name>.sql`.
  Flyway checksums each applied version and refuses to start when one
  changes, so an edit becomes a failed deploy rather than silent drift.
  Statements no longer need to be idempotent and dollar-quoted blocks are
  fine — both were constraints of the startup script this replaced.

## Known gaps (deliberate, tracked)

- Controls are hard-coded in `ControlClassifier` (roadmap: YAML-driven);
  no frontend yet (the Cognito dev callback expects a Vite app on
  `localhost:5173`).
- `AthenaQueryBuilder` is injection-hardened (identifier validation,
  escaped literals, Athena-format timestamps) but still only *builds* SQL —
  nothing executes it; reporting is served from Aurora today. Switch to
  Athena execution parameters when that lands.

Two things that read like gaps but are deliberate and finished:

- `AlertRule.conditionExpression` **is live**: a sandboxed SpEL predicate
  (`ConditionEvaluator`, SimpleEvaluationContext — no type refs or
  constructors, plus an `AllowListMethodResolver` limiting calls to
  read-only predicate methods and a 512-character cap, so a rule cannot
  allocate or backtrack its way through a consumer thread; customer
  expressions stay untrusted input). Drools remains
  the upgrade path if RETE-class evaluation is ever needed.
- Deploy path exists (`Dockerfile`, `aws` profile with MSK IAM + real S3,
  manual Deploy workflow) — compute is gated by `ecs_enabled` in the infra
  repo.

**No longer gaps** (this section used to claim otherwise — do not trust a
stale memory of it):

- The gateway **verifies** Cognito ID tokens itself: `SecurityConfig`
  builds a `NimbusJwtDecoder` from the JWKS URI and checks signature,
  expiry, issuer, app-client audience and the tenant claim
  (`CognitoJwtAuthTest`). There is no `JwtAuthFilter` any more. The API
  Gateway authorizer in the infra repo is defence in depth, not the only
  gate.
- api-gateway controllers are real, not placeholders: `AuditLogController`,
  `AlertController`, `AlertRuleController`, `ReportController`,
  `MeController`.
- `agent/collector-agent` exists and works: it tails a MySQL general log,
  redacts PII literals, and pushes events with a keyset cursor that
  survives a batch boundary.
- Notifiers really send: `SlackNotifier` posts to an incoming webhook and
  `EmailNotifier` calls SES. Each logs *instead* only when unconfigured.
