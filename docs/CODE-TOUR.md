# Code tour — read the platform in the order data moves through it

This is for someone who wants to understand AuditFlow by **reading the
code**, not just the README. It follows one event from the moment it
arrives to the moment a customer sees it in the API, and at each stop it
tells you which file to open, what to notice, and which test proves it.
Budget: an afternoon. You do not need AWS; everything here runs locally.

Before you start:

- Every service is a Spring Boot app with the same shape: `*Application`
  boots it, `application.yml` configures it, `adapters/` talk to the
  outside world (Kafka, Postgres, S3), and the interesting logic sits in
  between. When a file is confusing, find its test first — the test says
  what the class is *for*.
- Run `mvn clean install` once. Unit tests run everywhere; tests marked
  `@Testcontainers(disabledWithoutDocker = true)` need Docker and are
  skipped (not failed) without it.
- Keep `README.md` open next to this for the architecture diagram.

---

## Stop 0 — The vocabulary everyone shares (`shared/common-lib`)

Open these, in this order:

- `shared/common-lib/src/main/java/com/auditflow/common/model/AuditEvent.java` —
  **the** data structure. Every service passes this around. Notice it is
  immutable with a builder: processors return a modified *copy*, they
  never mutate. Read the fields once; you will meet all of them again.
- `shared/common-lib/src/main/java/com/auditflow/common/enums/EventType.java`
  and `RiskLevel.java` — the two vocabularies rules and reports speak.
- `shared/common-lib/src/main/java/com/auditflow/common/interfaces/` —
  four small interfaces that define the seams: `EventProcessor` (a step in
  the enrichment chain), `DataSink` (somewhere enriched events are
  written), `EventCollector` (a source the agent pulls from),
  `ReportGenerator` (a framework report). Almost every design decision in
  the platform is "which side of one of these seams does this belong on?"
- `shared/common-lib/src/main/resources/auditflow-schema.sql` — the whole
  relational schema. Every service runs it on startup and every statement
  is idempotent, so whichever service boots first creates the tables.

Proof: `AuditEventTest`.

---

## Stop 1 — The front door: an event arrives (`services/ingestion-service`)

The path of one `POST /api/v1/events`, in filter order:

1. `security/RateLimitFilter.java` — a token bucket per client, before
   anything else. Notice `shouldNotFilter` and that a refused request is a
   `429` with `Retry-After`. The limiter lives in common-lib
   (`ratelimit/TokenBucketLimiter.java`) so the gateway reuses it, and so
   does `ratelimit/ClientKeyResolver.java` — read that one's comment: the
   interesting decision is what it *refuses* to do with
   `X-Forwarded-For`.
2. `security/IngestTokenFilter.java` and `security/TenantTokens.java` — the
   tokens, each **bound to the one customer it may write as**
   (`tenant=token,tenant=token`). This is the part worth understanding: a
   token that only authenticates proves the caller is *a* known source, and
   then any source can post events under any `customerId` — one compromised
   emitter could forge another customer's audit trail. So the filter puts
   the resolved tenant on the request and the validator refuses an event
   claiming a different one. Tokens are compared as SHA-256 digests with
   `MessageDigest.isEqual`, over every entry with no early exit, so neither
   the token's length nor which tenant matched can be read off the timing.
   **Empty** configuration means "open", which is the local-dev default and
   the comment says why.
3. `api/EventIngestionController.java` — turns the wire shape
   (`IngestEventRequest`, bean-validated) into an `AuditEvent`, runs
   `validation/SchemaValidator.java` **with the tenant the filter bound**,
   and publishes. Notice the `@ExceptionHandler`s: an event claiming another
   customer is a **403** and never reaches Kafka; a publish that is not
   acknowledged is a **503**, not a 202. That second one is the whole point
   of the next file.
4. `adapters/KafkaProducerAdapter.java` — waits for the broker's ack. The
   class comment explains why this is *not* an outbox: there is no
   database write here to make atomic with the publish. Then
   `adapters/KafkaProducerConfig.java`: `acks=all` and idempotence are what
   make the ack mean "replicated", and the topic is declared here with its
   retention because MSK Serverless retention is per topic.

Proof: `TenantTokensTest`, `IngestTokenFilterTest`, `RateLimitFilterTest`,
`EventIngestionControllerTest` (202 vs 503),
`EventIngestionControllerTenantBindingTest` (202 for your own customer, 403
for someone else's, 401 for no token), `KafkaProducerAdapterTest`, and with
Docker `EventIngestionIntegrationTest` (a real broker, the topic's
retention.ms, and a forged customerId that never reaches the topic).

**Things to notice at this stop:** two independent defenses (rate limit,
token) run *before* the request costs anything. And the contract of the
endpoint is honest: 202 means durable.

---

## Stop 2 — The pipeline: raw event to evidence (`services/enrichment-service`)

1. `adapters/KafkaConsumerConfig.java` then `adapters/KafkaConsumerAdapter.java`.
   The listener sorts `EventProcessor`s by `order()` and runs them, then
   writes the result to every `DataSink`. That is the entire pipeline
   engine — about 20 lines — because the interfaces in Stop 0 did the
   design work.
2. The processors, in their `order()`:
   - `processors/UserContextEnricher.java` (10) — stamps a tag. A
     placeholder for an identity-provider lookup; read it to see how a
     processor returns a modified copy.
   - `processors/ControlClassifier.java` (20) — maps an event type to the
     compliance controls it is evidence for (SOC 2 `AC-2`, GDPR `Art-30`
     ...). Hard-coded today; the roadmap moves it to YAML. This is what
     makes reports possible later.
   - `processors/AnomalyDetector.java` (30) — a deliberately simple
     heuristic that sets `anomalous` and bumps `riskLevel`. Runs last so it
     sees the classified event.
3. The sinks, which all run for every event:
   - `adapters/S3WriterAdapter.java` — the immutable evidence copy, one JSON
     object per event under `customer/eventId`. In AWS that bucket is
     Object-Locked (see the infrastructure repo); this class does not know
     or care.
   - `adapters/AuroraWriterAdapter.java` — the queryable copy. Notice
     `ON CONFLICT (customer_id, event_id) DO NOTHING`: redelivery from Kafka
     or the agent is harmless because the insert is idempotent, and the
     conflict target is the *whole* key because event ids come from the
     source — two customers can pick the same one, and on event_id alone the
     second one's event was silently dropped. Controls are stored in a
     compact string via `common/model/ComplianceControls.java`.
   - `adapters/EnrichedTopicSink.java` — republishes the *enriched* event
     for alerting. `@Order(LOWEST_PRECEDENCE)` so persistence happens
     before anyone is paged.
4. `retention/RetentionPurgeJob.java` — nightly, batched deletes past the
   retention window. Read `RetentionProperties` for why the default is 400
   days (a little longer than the 365-day evidence lock).

Proof: `ControlClassifierTest`, `AnomalyDetectorTest`,
`EnrichedTopicSinkTest`, `RetentionPurgeJobTest`; with Docker
`AuroraWriterAdapterIntegrationTest` and `RetentionPurgeJobIntegrationTest`.

---

## Stop 3 — Alerting: from enriched event to a Slack message (`services/alerting-service`)

1. `dispatch/EnrichedEventListener.java` — consumes the enriched topic and
   hands each event to the dispatcher. Nothing else.
2. Where rules come from: `rules/RuleSeeder.java` reads
   `rules.example.json` on startup and upserts it into `alert_rules`;
   `rules/JdbcRuleRepository.java` reads that table into memory and
   refreshes on a timer. So the **table** is the source of truth and the
   file is just a seed — a rule created through the gateway API is live
   within one refresh. Notice a failed refresh keeps the last good set.
3. `rules/RuleEngine.java` — does this rule match this event? Enabled,
   same customer, type, risk threshold, and then the condition.
4. `shared/common-lib/.../rules/ConditionEvaluator.java` — the condition is
   a customer-supplied SpEL expression, i.e. untrusted code. Read the class
   comment carefully: `SimpleEvaluationContext` is what stops
   `T(java.lang.Runtime)` from working, and the evaluator fails *closed*
   (an expression that errors matches nothing). `validate()` is the same
   sandbox run against a sample event so the gateway can refuse a bad rule
   at write time. This class is in common-lib precisely so both services
   use the identical sandbox.
   The neighbouring `AllowListMethodResolver` is the second half of that
   sandbox, and worth understanding: blocking type references and
   constructors still left *every public instance method* callable. On a
   String that includes `repeat`, `matches` and `getBytes` — enough to
   exhaust the heap or pin a CPU from a rule definition, on a consumer
   thread every tenant shares. So methods are allow-listed rather than
   blacklisted, and anything unlisted simply fails to resolve.
5. `dispatch/AlertDispatcher.java` — for each matching rule, every channel
   it names. Notice one failing channel never blocks another, the event is
   not re-queued (a retry would re-page the channels that already
   succeeded), and every fired rule is recorded.
6. `notifiers/SlackNotifier.java` and `notifiers/EmailNotifier.java` — a
   webhook POST and an SES send. Both **log instead of send when
   unconfigured**, which is why a local run needs neither.
7. `history/AlertHistoryWriter.java` — the `alert_history` row with the
   channels that actually got through. This is what the API lists.

Proof: `ConditionEvaluatorTest` (the injection payloads are in there),
`RuleEngineTest`, `AlertDispatcherTest`, `SlackNotifierTest`,
`EmailNotifierTest`, `JdbcRuleRepositoryTest`, `RuleSeederTest`; with
Docker `AlertingEndToEndTest` runs the whole chain — seed file → Postgres
→ Kafka → a real webhook call → history row — in one test.

---

## Stop 4 — The API a customer talks to (`services/api-gateway-service`)

Security first, because everything below depends on one fact it
establishes: *which customer is this?*

1. `security/AuthProperties.java` — two modes. Open (local dev: customer
   from the `X-Customer-Id` header) and enforced (`aws` profile: a Cognito
   ID token, startup fails without the issuer and client id).
2. `security/SecurityConfig.java` — the enforced chain is a standard Spring
   OAuth2 resource server pointed at Cognito's JWKS; the open chain permits
   everything and logs a warning you cannot miss.
3. `security/CognitoTokenValidator.java` — the Cognito-specific rules on
   top of signature/expiry/issuer: `token_use` must be `id`, `aud` must be
   our app client, and `custom:customer_id` must be present. The comment
   explains why ID tokens and not access tokens.
4. `security/CurrentCustomer.java` — **the single place** controllers ask
   for the tenant. With auth on, it is the verified claim and nothing else;
   the dev header is never consulted. `controllers/RequestScope.java` wraps
   it and turns "no customer" into a 400.
5. Now the controllers, all the same shape — get the customer, pass it as a
   *query parameter*:
   - `controllers/AuditLogController.java` + `data/AuditLogRepository.java`
     (filters, newest first, limit).
   - `controllers/AlertController.java` + `data/AlertHistoryRepository.java`
     (joins the rule name).
   - `controllers/AlertRuleController.java` + `data/AlertRuleRepository.java`
     — CRUD, ids server-generated, the condition validated with the Stop 3
     sandbox. Look at the repository's upsert: the `WHERE
     alert_rules.customer_id = EXCLUDED.customer_id` clause is what stops
     one tenant overwriting another's row.
   - `controllers/ReportController.java` — pulls the customer's events for
     a window and runs a generator from
     `shared/common-lib/.../reports/`. Notice the 413 above 10,000 events
     instead of a truncated report.
   - `controllers/MeController.java` — "who does the gateway think I am";
     the quickest way to check a token.
6. `security/RateLimitFilter.java` — same limiter as ingestion, per client,
   ahead of authentication. This is the copy that actually needs a header:
   two hops (API Gateway, then the internal ALB) stand in front, so the
   socket address is the load balancer and every caller would share one
   bucket. The `aws` profile names `X-Client-IP`, which the API Gateway
   integration sets from `$context.identity.sourceIp` with an `overwrite:`
   mapping, so the value cannot be supplied by the caller.

Proof: `CognitoJwtAuthTest` (mints real RSA-signed tokens against an
in-test JWKS server and tries eight ways to get in), `AuthDisabledTest`,
the four controller tests, `RateLimitFilterTest`; with Docker
`RepositoriesIntegrationTest`, which includes a cross-tenant hijack attempt.

**The thing to notice at this stop:** tenancy is not a filter someone has
to remember to add. The customer id is a parameter of every SQL statement,
and it comes from the security layer, never the caller.

---

## Stop 5 — The other door: pulling from a database you cannot change (`agent/collector-agent`)

Not every source can push. The agent runs next to a source database and
tails its query log.

1. `AgentRunner.java` — the poll loop. Read the comment on the empty-batch
   case and the `commit()` call: the collector's position advances only
   after the publisher confirmed delivery. That is what makes this path
   at-least-once (compare with Stop 1's at-most-once push from Resistance —
   the platform makes both trade-offs explicit).
2. `collector/CommittableCollector.java` — the seam that expresses that
   idea in one method.
3. `collector/MySqlGeneralLogCollector.java` — reads `mysql.general_log`
   from a read-only account, filters its own noise, builds deterministic
   event ids (a hash of time, thread and statement) so a re-sent batch
   dedupes at the Aurora sink.
4. `redact/QueryRedactor.java` — read this one slowly. Every string and
   numeric literal becomes `?` **before** anything leaves the host,
   honouring SQL escaping rules. An audit trail must not become the PII
   leak it exists to detect.
5. `publish/IngestionPublisher.java` — posts through the same
   authenticated front door as everyone else (Stop 1). No privileged path
   for the agent.

Proof: `QueryRedactorTest`, `MySqlGeneralLogCollectorTest`; with Docker
`MySqlGeneralLogCollectorIntegrationTest` proves a PII-bearing query
round-trips redacted from a real MySQL general log.

---

## Stop 6 — How it runs

- `docker-compose.yml` — infrastructure by default; `--profile app` adds
  every service built from the root `Dockerfile`. Read the comment block
  above the app services for which env vars change behaviour.
- Each service's `application.yml` (defaults are the dev-open values) and
  `application-aws.yml` (what the `aws` profile flips: MSK IAM auth, real
  S3, enforced JWTs, and the gateway's trusted client-IP header).
- `.github/workflows/build.yml` (every PR and push to `develop`/`main`) and
  `deploy.yml` (manual: build images, push to ECR, roll ECS).
- The AWS side — VPC, MSK, Aurora, the Object-Locked evidence bucket,
  Cognito, ECS — is Terraform in the `auditflow-infrastructure` repo; its
  README's "Choices worth knowing about" is the companion to this tour.

---

## If you only have an hour

Read Stop 0, then `EventIngestionController` → `KafkaConsumerAdapter` →
`AlertDispatcher` → `AuditLogController`, and run `AlertingEndToEndTest`
with Docker. That is the spine; everything else hangs off it.

## What is deliberately not here yet

Postgres and generic-API collectors (only MySQL exists), controls in YAML
instead of `ControlClassifier`, the S3/Athena report path for very large
windows, per-customer notifier destinations, and any frontend. The README's
"implemented vs. stubbed" list is kept honest on purpose — check it before
assuming something works.
