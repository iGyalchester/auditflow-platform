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
- `shared/common-lib/src/main/resources/db/migration/` — the whole
  relational schema, as Flyway migrations. Every service applies them on
  startup and Flyway's advisory lock serialises the three that share the
  database, so a compose start or a rolling deploy cannot race. Read the
  index block on each table with the queries in Stop 4 next to it: every
  one is there for a query that exists, and there is deliberately no
  single-column index on `customer_id` for `audit_events`, because the
  primary key already leads with it. An index nobody reads is not free -
  it is a write on every insert.

  The rule that matters if you touch this: **never edit a version that has
  been applied**, add a new `V<n>__<name>.sql`. Flyway checksums each
  version and refuses to start if an applied one changed. That is the
  behaviour you want, but it turns an edit into a failed deploy rather
  than a silent divergence.

Proof: `AuditEventTest`.

---

## Stop 1 — The front door: an event arrives (`services/ingestion-service`)

The path of one `POST /api/v1/events`, in filter order:

1. `security/RateLimitConfig.java` — a token bucket per client, before
   anything else. The filter itself is
   `common-lib/ratelimit/RateLimitFilter.java`, shared with the gateway;
   each service keeps only a properties record naming its prefix and
   defaults. Notice `shouldNotFilter` and that a refused request is a `429`
   with `Retry-After`. Read `ratelimit/ClientKeyResolver.java` too — the
   interesting decision is what it *refuses* to do with `X-Forwarded-For`,
   and it is the reason one filter is better than two: that decision had to
   be got right, and kept right, in both copies.
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

   Also read `occurredAt`. Every event used to be stamped
   `Instant.now()` at this line, which quietly made `occurred_at` mean
   *arrival* time. The two agree only while everything is healthy - and
   diverge exactly when the evidence matters most: the collector draining
   an hour of backlog after an outage would date all of it to the
   catch-up minute, and Resistance already knew when its login failed and
   was throwing that away. Reports window on this column, so the answer to
   "what happened between 09:00 and 10:00" was really "what we heard about
   then". The source's value now wins when it sends one; a value more than
   five minutes ahead of our clock is a 400, because that is either a
   broken clock or an attempt to park evidence outside the window a report
   will look at.
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
   writes the result to every `DataSink`, in `@Order`. That is the entire
   pipeline engine — about 20 lines — because the interfaces in Stop 0 did
   the design work.
   The config is longer than the adapter, and its class comment is the one
   to read: with no error handler Spring retried a failing record ten times
   with no pause and then **committed the offset and moved on**, so a brief
   S3 or Aurora outage silently lost the event. It now backs off over about
   40 seconds and dead-letters what it still cannot handle. Note the
   dead-letter producer's per-type serializers: a record that failed to
   *deserialize* arrives as raw `byte[]` and has to reach the DLT
   unchanged, or the bytes worth investigating are lost on the way to the
   topic that exists to keep them.
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
     for alerting. `@Order(30)`, after S3 (10) and Aurora (20), so an alert
     never fires for an event that is not yet stored. It used to be
     `LOWEST_PRECEDENCE`, which an unordered bean also has — so "last" was a
     tie the container broke however it liked.
4. `retention/RetentionPurgeJob.java` — nightly, batched deletes past the
   retention window. Read `RetentionProperties` for why the default is 400
   days (a little longer than the 365-day evidence lock).

Proof: `ControlClassifierTest`, `AnomalyDetectorTest`,
`EnrichedTopicSinkTest`, `RetentionPurgeJobTest`, `KafkaConsumerAdapterTest`
(sinks run evidence-first however they are injected); with Docker
`AuroraWriterAdapterIntegrationTest`, `RetentionPurgeJobIntegrationTest`,
and `EnrichmentPipelineIntegrationTest` — a real broker proving a transient
failure is survived, a permanent one is dead-lettered, and poison bytes
reach the DLT unchanged without being retried.

---

## Stop 3 — Alerting: from enriched event to a Slack message (`services/alerting-service`)

1. `dispatch/EnrichedEventListener.java` — consumes the enriched topic and
   hands each event to the dispatcher. Nothing else.
2. Where rules come from: `rules/RuleSeeder.java` reads
   `rules.example.json` on startup and inserts it into `alert_rules` **for
   customers that have no rules yet**; `rules/JdbcRuleRepository.java`
   reads that table into memory and refreshes on a timer. So the **table**
   is the source of truth and the file is just a seed — a rule created
   through the gateway API is live within one refresh.
   Both classes are worth reading for what they get wrong when done the
   obvious way. Seeding used to *upsert* every start, which quietly made
   the file the source of truth: an edit through the API was reverted by
   the next deploy, and a rule someone disabled after a 3am page came back
   enabled. And in the repository, notice the difference between a failed
   *first* load (throws — a service that starts with no rules matches
   nothing while looking perfectly healthy) and a failed *later* refresh
   (keeps the last good set — a database blip should not disarm every
   rule). One unreadable row is skipped rather than emptying the set.
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
   channels that actually got through. This is what the API lists. Notice
   the subselect on `rule_id` rather than the value itself: alerting reloads
   rules on a timer, so for up to one refresh interval it can still match a
   rule the API has already deleted. Writing that id straight in would
   violate the foreign key and lose the record of an alert that really
   fired; resolving it through the table stores NULL instead.

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
   OAuth2 resource server pointed at Cognito's JWKS; the open chain reads
   the dev headers through `DevHeaderAuthenticationFilter` and logs a
   warning you cannot miss. Both chains share one authorization table,
   `authorize(...)`: `/actuator/health` is open (the internal ALB has no
   token); the console's files and its client-side routes are open GETs
   (HTML holds no data; `config/SpaConfig.java` serves them and answers
   any dotless path with `index.html` so a reload deep in the app works);
   `/api/v1/operator/**` needs `ROLE_OPERATOR`, which
   `CognitoGroupsConverter` grants to members of the Cognito group
   `operators`; everything else falls to `anyRequest().denyAll()`, so
   `/actuator/env` is refused twice over. `otherActuatorPathsStayClosed`
   and `consoleShellAndRoutesAreOpenButNothingElseIs` pin that. The 401
   and 403 the chain answers itself go through `JsonAuthErrors`, so they
   have the same `{"error", "message"}` body as `api/ApiErrorHandler`
   gives every controller failure.
3. `security/CognitoTokenValidator.java` — the Cognito-specific rules on
   top of signature/expiry/issuer: `token_use` must be `id`, `aud` must be
   our app client, and `custom:customer_id` must be present. The comment
   explains why ID tokens and not access tokens.
4. `security/CurrentCustomer.java` — **the single place** controllers ask
   who the caller is: tenant and roles. With auth on, it is the verified
   claim and nothing else; the dev headers are never consulted, because
   the filter that reads them is not in that chain.
   `controllers/RequestScope.java` wraps it, turns "no customer" into a
   400, and holds the one rule about acting as someone else: an operator
   may send `X-Acting-Customer-Id` and every query runs as that tenant; a
   plain user sending it is refused with a 403 rather than ignored, so a
   client bug cannot quietly show the wrong tenant's data.
5. Now the controllers, all the same shape — get the customer, pass it as a
   *query parameter*:
   - `controllers/AuditLogController.java` + `data/AuditLogRepository.java`
     (filters, newest first, limit).
   - `controllers/AlertController.java` + `data/AlertHistoryRepository.java`
     (LEFT-joins the rule name — left, because the rule may be gone: history
     is evidence and survives the rule that raised it, with `rule_id` set to
     NULL rather than the row being deleted or the delete being refused).
   - `controllers/AlertRuleController.java` + `data/AlertRuleRepository.java`
     — CRUD, ids server-generated, the condition validated with the Stop 3
     sandbox. Look at the repository's upsert: the `WHERE
     alert_rules.customer_id = EXCLUDED.customer_id` clause is what stops
     one tenant overwriting another's row. Note that PUT uses `update`, not
     that upsert: a row count of zero is "no such rule for you", and an
     upsert there would let a PUT to an unknown id *create* one, handing
     clients the choice of id in a globally unique namespace.
     The row mapping and parameter binding both come from
     `common-lib/rules/AlertRuleRows` — the same codec alerting reads
     with, so a new column is one edit rather than three that have to
     agree.
   - `controllers/ReportController.java` — pulls the customer's events for
     a window and runs a generator from
     `shared/common-lib/.../reports/`. Notice the 413 above 10,000 events
     instead of a truncated report — and that the query is given the
     framework, so that cap counts the events the report will contain.
     It used to load the whole window and let the generator filter in
     Java, which meant a tenant with 10,001 events and 50 SOC 2 events was
     refused a report fifty lines long. The generators keep their own
     filter as a guard; SQL narrows what is loaded, it does not become the
     only place the rule is written down.
   - `controllers/MeController.java` — "who does the gateway think I am";
     the quickest way to check a token.
   - `controllers/StatsController.java` + `data/StatsRepository.java` — the
     dashboard in one call. Counting happens in SQL (`FILTER`, a `GROUP BY`
     per UTC day, `unnest` over the comma-joined controls); the Java side
     only zero-fills the days so a quiet day is a zero bar, not a gap.
     `controllers/TimeWindow.java` is the one place `from`/`to` defaults
     and caps are decided, shared by stats, reports and the dry run.
   - `AlertRuleController.dryRun` — "how often would this rule have fired
     last week?", answered before the rule is saved by evaluating the draft
     over the customer's events with `common-lib/rules/RuleMatcher`, the
     same class alerting-service's `RuleEngine` delegates to. One definition
     of a match, so the preview cannot disagree with production.
   - `controllers/OperatorController.java` + `data/OperatorRepository.java`
     — the only queries that see every tenant; the path is gated on
     `ROLE_OPERATOR` in the security chain, not in the controller.
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
   Read the cursor section of its class comment: "remember the newest
   timestamp I have seen" is the obvious checkpoint and it loses rows two
   different ways. Taking it from `SELECT MAX(event_time)` over the whole
   table — which is what this did — moves it past rows the capped batch
   never returned, so a backlog bigger than one batch is silently, partly
   discarded. And a plain `>` skips rows sharing the boundary microsecond
   while a plain `>=` re-reads them forever. The answer is a keyset over
   `(event_time, thread_id)` plus the ids seen at that exact pair.
4. `collector/CheckpointStore.java` — that cursor, on disk. An exact
   cursor that lives only in memory still loses the one window you most
   want: a restarted agent began at `Instant.now()`, so everything logged
   while it was down was skipped silently. Two decisions in this file are
   worth the read. The write goes to a `.tmp` and is then moved into place,
   because a checkpoint truncated by a crash mid-write is *worse* than no
   checkpoint - it would parse as a valid but wrong position. And a load
   that is missing any field is treated as absent rather than defaulted: a
   cursor without its thread id would silently skip every row before
   whatever the default happened to be, whereas falling back to the
   lookback merely re-reads.
5. `redact/QueryRedactor.java` — read this one slowly. Every string and
   numeric literal becomes `?` **before** anything leaves the host,
   honouring SQL escaping rules. An audit trail must not become the PII
   leak it exists to detect.
6. `publish/IngestionPublisher.java` — posts through the same
   authenticated front door as everyone else (Stop 1). No privileged path
   for the agent.

Proof: `QueryRedactorTest`, `MySqlGeneralLogCollectorTest` (which drives
the cursor over an in-memory log: a 1,200-row backlog at batch 500, rows
sharing a timestamp, a batch of nothing but noise, and a timestamp holding
more rows than one batch); with Docker
`MySqlGeneralLogCollectorIntegrationTest` proves a PII-bearing query
round-trips redacted from a real MySQL general log, and that 23 statements
at batch size 5 each arrive exactly once.

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

## Stop 7 — The console (`frontend/`)

The gateway's jar carries a React app, and reading it in this order
explains every other screen:

1. `src/auth/AuthContext.tsx` — the one place the API learns how to
   authenticate a request. It asks the gateway for `/config.json` and
   picks a door: the dev headers (auth open) or a Cognito ID token
   (`auth/oidc.ts`, `oidc-client-ts` doing PKCE). Acting-as for operators
   is the same header on every request, and `hooks/useAsync.ts` refetches
   every page when it changes.
2. `src/api/client.ts` — one fetch wrapper: the gateway's error shape as
   `ApiError`, a 401 ending the session, a 429 waited out and retried
   once. Every endpoint has a typed function; nothing else calls `fetch`.
3. `src/util/timeRange.ts` + `hooks/useTimeRange.ts` — the global window
   lives in the URL, so every page reads the same one and every link
   carries it.
4. `src/charts/palette.ts` — the colours, with the validator runs that
   justify them in the comment. `EventsPerDayChart` (one-hue ramp for an
   ordered thing, risk), `HorizontalBarsChart` (one hue for names),
   `PlatformPerDayChart` (categorical slots in fixed order, folding past
   six). Every chart sits in `ChartCard`, which owns the table toggle.
5. The pages, each one `useAsync` around one or two client calls:
   `DashboardPage` (stats), `AuditLogPage` (filters in the URL, keyset
   paging, the drawer at `?event=`), `AlertsPage`, `RulesPage` +
   `RuleEditor` (debounced `/validate`, `/dry-run`), `ReportsPage`,
   `OperatorPage` ("View as" is `actAs` on the context), `SettingsPage`.

Proof: `src/test/*.test.tsx` render the whole app through a stubbed
`fetch` keyed by method and path (`test/helpers.tsx`), so a test reads
like a user story: sign in, open the explorer, filter, open an event,
create a rule from it. The drawer test found a real bug before it
shipped, which is the argument for that style.

**The thing to notice at this stop:** the console never decides who you
are or what you may see. It sends what it was given and shows what the
gateway answers, and the roles, the tenant and the 403s all come from
Stop 4.

## If you only have an hour

Read Stop 0, then `EventIngestionController` → `KafkaConsumerAdapter` →
`AlertDispatcher` → `AuditLogController`, and run `AlertingEndToEndTest`
with Docker. That is the spine; everything else hangs off it.

## What is deliberately not here yet

Postgres and generic-API collectors (only MySQL exists), controls in YAML
instead of `ControlClassifier`, the S3/Athena report path for very large
windows, per-customer notifier destinations, and the console's custom
domain (the infra repo has it gated on a variable, pending the DNS
decision). The README's
"implemented vs. stubbed" list is kept honest on purpose — check it before
assuming something works.
