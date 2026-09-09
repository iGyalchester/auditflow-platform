# AuditFlow Console: a React front end for the compliance platform

> Status: **approved 2026-09-09**, being built slice by slice (one PR each,
> into `develop`). Infrastructure pieces land as two small PRs in
> **auditflow-infrastructure** (`Develop`). Grounded in auditflow-platform
> `develop` @ `8e2df1b` and auditflow-infrastructure `Develop` @ `8a2257e`.
> The plan is the contract; when a slice deviates, this file says why.

## Deviations log

- **Slice 1**: acting-as uses its own header, `X-Acting-Customer-Id`, in
  both modes, instead of overloading `X-Customer-Id` when auth is on. In
  dev `X-Customer-Id` *is* the identity, so one header could not mean
  "identity" locally and "act as" in the cloud without two code paths;
  a dedicated header keeps `RequestScope` the single rule and lets `/me`
  report `actingAs` the same way everywhere. `GET /config.json` also
  carries `hostedUiDomain`, because Cognito's discovery document has no
  `end_session_endpoint` and the console needs the hosted UI origin to
  sign out. `/me` without a customer is a 400 like every other endpoint
  in open mode (the console never calls it before dev sign-in).

- **Slice 2**: `POST /alert-rules/validate` returns `{valid, error}` only;
  the planned `sampleMatches` flag was dropped (whether the evaluator's
  internal sample event matches tells the author nothing about their
  data - the dry run answers that). Operator stats are
  `perDay[{day, events, alerts, eventsByCustomer}]` plus `topCustomers`
  and a `byCustomer` legend, which is the shape a stacked chart needs,
  rather than a per-customer-per-day matrix. The rule-matching decision
  moved from alerting-service's `RuleEngine` into `common-lib/rules/RuleMatcher`
  so the dry run and production share one definition of a match.

- **Slice 3**: with auth enforced the sign-in page shows a "Sign in with
  Cognito" button rather than redirecting on sight, so a person landing
  on a deep link sees where they are being sent (and tests can assert
  it). Sign-out builds Cognito's `/logout?client_id&logout_uri` URL by
  hand from `hostedUiDomain` (no `end_session_endpoint` in Cognito's
  discovery document). The remaining pages are honest placeholders that
  name the slice they arrive in, so the navigation is complete from day
  one. `useAsync` refetches every page when an operator's "view as"
  changes.

- **Slice 4**: the event drawer is addressed by `?event=<id>` rather than
  a nested route, so it composes with the filters in the same URL. The
  alerts feed links each row's event into the explorer's drawer instead
  of fetching a per-row event summary (fifty extra requests per page for
  a sentence). Text filters (user, search) apply on Apply/Enter; the
  selects and the checkbox apply immediately.

- **Slice 5**: "last fired" on the rules list comes from the newest 500
  alerts (one request) rather than a per-rule query. The report download
  fetches the text with the auth headers and hands the browser a file,
  because a plain `<a href>` cannot carry a bearer token when auth is
  enforced; the preview reuses the same fetch. The editor re-validates an
  existing condition on open and keeps Save disabled until the verdict
  is in.

## Context

AuditFlow is API-only: five services, a collector agent, and a gateway whose REST
endpoints (`/api/v1/audit-logs`, `/alerts`, `/alert-rules`, `/reports/*`, `/me`)
are the whole interface. Boris confirmed the pipeline works end to end today
(Resistance login failures → ingestion → Kafka → enrichment → Postgres → gateway),
but nobody can *see* it without curl. For a portfolio piece that is the gap.

Decisions taken with Boris (2026-09-09):
- **Served from the gateway jar**, same origin, like Resistance's mvc-service.
  No CORS, no new hosting.
- **All screens**: dashboard, audit-log explorer, alert-rules editor with a test
  facility, alerts feed, reports, **plus a platform-operator view** across
  customers. "Make it pretty."
- **Custom domain `auditflow.areyouinquazzy.lol`**, with a placeholder for the
  registrar/DNS answer Boris still owes (which DNS hosts the apex, and whether
  Resistance's bootstrap or this repo owns the Route 53 zone).
- **Spend carefully**: the executing session already knows both codebases; no
  review-agent fan-out unless asked. Verify locally, push, let CI run.

Facts the plan builds on (found, not assumed):
- Gateway (`services/api-gateway-service`, port 8080, Tomcat, Spring Security):
  two mutually exclusive chains in `security/SecurityConfig.java` — Enforced
  (`audit.auth.enabled=true`, Cognito JWT via `NimbusJwtDecoder` +
  `CognitoTokenValidator` requiring `token_use=id` and `custom:customer_id`;
  `/api/**` authenticated, **everything else `denyAll`**) and Open (default;
  `X-Customer-Id` header via `security/CurrentCustomer.java`). CSRF off, stateless,
  no CORS anywhere, rate limit 20 rps/burst 40 per client key with
  `X-RateLimit-Remaining` and 429 + `Retry-After`. No static resources today.
  Errors are Spring's default body (no message).
- Endpoints (`controllers/`): `AuditLogController` (`type,from,to,limit`; rows
  `eventId,userId,sessionId,occurredAt,eventType,resource,action,riskLevel,
  anomalous,controls`), `AlertController` (`limit` only; `AlertRow` with
  `ruleName` LEFT JOIN), `AlertRuleController` (full CRUD, `AlertRuleRequest`,
  SpEL validated by `common-lib/rules/ConditionEvaluator`, channels `slack|email`),
  `ReportController` (`GET /reports` → `["gdpr","hipaa","soc2"]`;
  `GET /reports/{fw}?from&to` → text/plain attachment, 413 over 10k events),
  `MeController` (`customerId, subject`). `DEFAULT_LIMIT 100`, `MAX_LIMIT 1000`,
  no cursors.
- Data (`common-lib/.../db/migration/V1__baseline.sql`): `audit_events`
  (PK customer_id+event_id, index customer_id+occurred_at DESC), `alert_rules`,
  `alert_history` (rule_id nullable, `notified_channels` = channels that
  **succeeded**), `customers`, `compliance_controls`. Controls encode as
  `SOC2:AC-2,GDPR:Art-30` (`ControlClassifier`). Rules refresh in
  alerting-service every 30 s (`JdbcRuleRepository`). No dedup/cooldown.
- No stats/aggregate queries exist; actuator exposes `health` only.
- AWS (`auditflow-infrastructure`): API Gateway HTTP API with **one route
  `ANY /{proxy+}` behind a Cognito JWT authorizer**, VPC link → internal ALB
  (HTTP:80) → ECS gateway task. Cognito user pool with immutable custom attribute
  `customer_id`, hosted UI domain `auditflow-<env>-<acct>.auth.<region>.amazoncognito.com`,
  a **public client with the `code` flow** (PKCE-ready), callback/logout URLs
  from `cognito_callback_urls`/`cognito_logout_urls` tfvars (dev:
  `http://localhost:5173/callback`, staging/prod: `*.auditflow.example.com`
  placeholders). **No Cognito groups, no ACM cert, no Route 53, no CloudFront.**
  `ecs_enabled=false` in every tfvars (cost gate). Deploy workflow: manual,
  `mvn package` then one image per service from the root `Dockerfile`.
- CI (`.github/workflows/build.yml`): Maven only, no Node. Repo rule (CLAUDE.md):
  Testcontainers over mocks for anything touching Postgres/Kafka; Flyway
  migrations only additive; Boris merges PRs himself unless he says otherwise
  (today he said otherwise for the Resistance stack; ask before assuming).
- Resistance `frontend/` is the template: Vite + React 19 + TS, `api/client.ts`,
  `hooks/useAsync.ts`, `components/{AppShell,Dialog,ConfirmDialog,Toast,
  ErrorBanner,EmptyState,StatCard,forms/FormField}`, `charts/{ChartCard,palette}`,
  `test/helpers.tsx` (`renderApp` with a `"METHOD url"` fetch table), CSS tokens
  with light/dark in `index.css`; served by the `frontend` Maven profile
  (`frontend-maven-plugin` 1.15.1, Node v22.12.0) + `config/SpaConfig.java`.

## Target architecture

```
browser ── https://auditflow.areyouinquazzy.lol ──> API Gateway (HTTP API)
             ├─ ANY /api/{proxy+}   Cognito JWT authorizer ──┐
             └─ ANY /{proxy+}       no authorizer ───────────┴─ VPC link → ALB → api-gateway-service :8080
                                                                 ├─ /api/**        bearer ID token (Spring), operator role for /api/v1/operator/**
                                                                 ├─ /assets/**, /index.html, /favicon.svg   the React bundle (public)
                                                                 └─ /*             index.html fallback for client routes
dev:  vite :5173 proxies /api → :8080; gateway runs with audit.auth.enabled=false;
      the console's dev sign-in page sets X-Customer-Id (and X-Roles: operator) itself.
```

Login in the cloud is Cognito Hosted UI, authorization code + PKCE, handled by
`oidc-client-ts` (the one new runtime dependency; it owns PKCE, token storage,
silent refresh, and the `/callback` route). The **ID token** is the bearer (the
gateway requires `token_use=id`). Locally there is no Cognito: a dev sign-in
page picks a customer id, optionally the operator role, and the client sends the
headers the Open chain already honours.

Operator role: a Cognito group `operators` → `cognito:groups` claim →
`ROLE_OPERATOR`. Operators get `/api/v1/operator/**` and may act as any customer
by sending `X-Customer-Id` (honoured for operators even when auth is enforced;
`RequestScope` becomes the single place that decides). Ordinary users stay
scoped to their claim exactly as today.

Stack additions, deliberately few: `oidc-client-ts`, `recharts` (as in
Resistance), `frontend-maven-plugin`. No state or CSS library. A
`@RestControllerAdvice` gives every gateway error one JSON shape
`{"error": "<code>", "message": "..."}` so the console parses one thing.

## Skills the executing session must load

- `dataviz` before any chart: this console needs a **categorical** palette
  (six event types), a **sequential** ramp (four risk levels, LOW→CRITICAL), and
  the single accent; run `scripts/validate_palette.js` for light and dark and
  paste the output into `frontend/src/charts/palette.ts`. Legend for ≥2 series,
  tooltips, a "View as table" toggle, `role="img"` + label on every chart.
- `artifact-design` is not needed (this is an app, not an artifact).

## Slices (one PR each; platform PRs into `develop`, infra PRs into `Develop`)

### Slice 1 — Gateway serves the console; roles; one error shape (platform)

- `frontend` Maven profile in `services/api-gateway-service/pom.xml` copied from
  Resistance (`frontend-maven-plugin.version`, `node.version` as root-pom
  properties; `-Dfrontend.skip=true` opt-out; `npm ci` + `npm run build` in
  `../../frontend`, dist → `target/classes/static`). A placeholder
  `frontend/` with a minimal Vite app so the profile builds from day one
  (`index.html`, `main.tsx`, the tokens file). `.dockerignore`: add
  `frontend/node_modules`, `frontend/dist`. `build.yml`: a `frontend` job
  (Node 22, `npm ci && npm run build && npm test -- --run`).
- `config/SpaConfig.java` (the Resistance resolver: files from
  `classpath:/static/`, dotless miss → `index.html`, dotted miss → 404).
- `SecurityConfig`: both chains permit `GET` on `/`, `/index.html`, `/assets/**`,
  `/favicon.svg`, `/callback` and any dotless non-API GET; `/actuator/health/**`
  stays open; `/api/v1/operator/**` → `hasRole("OPERATOR")`; `/api/**`
  authenticated; every other method/path `denyAll` (unchanged posture for
  non-GET). `CognitoTokenValidator`/a converter maps `cognito:groups` containing
  `operators` to `ROLE_OPERATOR`. Open chain: `X-Roles: operator` header grants
  the role (dev only; the chain does not exist when auth is on).
- `RequestScope.customerId(...)`: claim by default; an operator's `X-Customer-Id`
  overrides; ordinary user sending it → 403 `{"error":"forbidden"}`.
- `GET /api/v1/me` → `{customerId, customerName, subject, roles: ["USER"|"OPERATOR"],
  actingAs?: customerId}` (name from `customers`, null if absent).
- `api/ApiErrorHandler` (`@RestControllerAdvice`): 400 `validation` with `fields`,
  400 `bad_request` (unparsable params/dates), 403 `forbidden`, 404 `not_found`,
  413 `too_many_events`, 429 passthrough, 500 `internal`; existing
  `ResponseStatusException` reasons become `message`.
- Tests: `SecurityConfig` slice tests for both chains (anonymous `/` = 200 shell,
  `/api/**` = 401 JSON, `/assets/x.js` missing = 404, operator path 403 for a
  USER and 200 with the group claim, dev `X-Roles` honoured only when open);
  `RequestScope` override rules; error handler shapes. Existing
  `CognitoJwtAuthTest` extended, not replaced.
- Docs: README "Console" stub + running-locally update (`-Dfrontend.skip`),
  CLAUDE.md map ("frontend exists, served by the gateway").

**Infra PR A (auditflow-infrastructure, Slice 1's twin)** — `modules/api-gateway`:
split the catch-all into `ANY /api/{proxy+}` (JWT authorizer, unchanged) and
`ANY /{proxy+}` (`authorization_type = "NONE"`) so HTML/JS and client-route
reloads reach the gateway; Spring still enforces the token on `/api/**`.
`modules/cognito`: `aws_cognito_user_group.operators`; callback/logout URL
variables gain `https://auditflow.areyouinquazzy.lol/callback` and `/` for
dev and prod (staging keeps its placeholder), alongside `http://localhost:5173/...`
in dev. `terraform validate` + plan in CI; no apply needed until `ecs_enabled`
flips.

### Slice 2 — Read API the console needs (platform)

New or extended gateway endpoints, all customer-scoped through `RequestScope`,
all SQL in `gateway/data/*Repository` with **Testcontainers Postgres tests**
(the repo's rule), controllers unit-tested with the security context set:

| Endpoint | Purpose |
|---|---|
| `GET /api/v1/stats?from&to` | one call for the dashboard: `totals {events, alerts, critical, anomalous, users}`, `perDay [{day, events, alerts, critical}]` zero-filled, `byType`, `byRisk`, `byControl`, `topUsers[10]`, `topResources[10]`. Window capped at 366 days. |
| `GET /api/v1/audit-logs` | adds `riskLevel`, `userId`, `anomalous`, `q` (ILIKE on resource/action); keeps `from/to/limit`. Rows gain nothing (the DB holds only these columns). Keyset paging = the client passes `to = oldest occurredAt seen`. |
| `GET /api/v1/audit-logs/{eventId}` | the row plus `alerts: [{alertId, ruleName, triggeredAt}]` that fired on it. |
| `GET /api/v1/alerts` | adds `from`, `to`, `ruleId`; `GET /api/v1/alerts/{alertId}` returns the alert with its event row and the rule's configured vs notified channels. |
| `POST /api/v1/alert-rules/validate` | `{eventType, riskThreshold, conditionExpression}` → `{valid, error, sampleMatches}` using `ConditionEvaluator` and its `SAMPLE_EVENT`; the editor calls it on every pause. |
| `POST /api/v1/alert-rules/dry-run?from&to` | same body → `{scanned, matched, sample: [5 matching event rows]}` by evaluating the rule in memory over the customer's events in the window (cap 10 000, else 413). "How noisy would this be" before saving. |
| `GET /api/v1/reports/{fw}/summary?from&to` | JSON preview: `{framework, from, to, events, byControl: {..}, byRisk: {..}}`; the existing text download stays. |
| `GET /api/v1/operator/customers` | operators: `[{customerId, name, events24h, events7d, alerts7d, rules, lastEventAt}]`. |
| `GET /api/v1/operator/stats?from&to` | operators: platform totals and per-customer per-day counts. |

`limit` stays 1..1000; `q` capped at 100 chars; every date param validated
(`from < to`, window cap). Rate limit unchanged; the console reads
`X-RateLimit-Remaining` and backs off on 429.

Docs: README API table gains the rows above; CODE-TOUR Stop 4 mentions stats
and dry-run.

### Slice 3 — Console foundation: auth, shell, dashboard (platform, `frontend/`)

- Project: copy Resistance's Vite/TS/Vitest setup; proxy `/api` → `:8080`.
- Look: **its own identity**, not a Resistance clone. Tokens in `index.css`
  (light/dark via `prefers-color-scheme`, `color-scheme`), a left **sidebar**
  shell (nav, customer name, operator badge, sign-out) with a slim top bar
  holding the **global time range** (presets 24h/7d/30d/90d + custom; kept in the
  URL query so every page and every link shares it). Accent, categorical
  (event types) and sequential (risk) palettes validated with the dataviz
  script and recorded in `charts/palette.ts`. Inter-like system font stack,
  8-px rhythm, cards with soft borders, tabular numerals for counts, skeleton
  loaders instead of "Loading…", empty states with a one-line hint.
- Auth: `auth/oidc.ts` wraps `oidc-client-ts` (authority = Cognito issuer,
  client id, redirect `/callback`, PKCE, `response_type=code`, scopes
  `openid email profile auditflow-api/read auditflow-api/write`; settings from
  `/config.json` served by the gateway so one bundle works in every
  environment: `GET /config.json` → `{authEnabled, issuer, clientId, logoutUrl}`
  added to Slice 1's controller set). When `authEnabled=false` the app shows
  the **dev sign-in** page (customer id, operator toggle) and `client.ts` sends
  `X-Customer-Id`/`X-Roles`. `RequireAuth` route guard asks `/api/v1/me` once.
- `api/client.ts`: bearer or dev headers, 401 → sign-in, 403 → "not allowed"
  banner, 429 → wait `Retry-After` then retry once, typed `ApiError` with the
  Slice-1 error shape. `hooks/useAsync.ts` reused as is.
- **Dashboard** (`pages/DashboardPage.tsx`): stat tiles (events, alerts,
  critical, anomalous, active users in the range, with delta vs the previous
  range), **events per day stacked by risk** (sequential ramp), **events by
  type** (categorical bars, direct labels), **alerts per day** (single hue),
  **controls coverage** (events per SOC 2/GDPR control, horizontal bars),
  top users and top resources tables, recent alerts list linking into the feed.
  Every chart via `ChartCard` (table toggle, `role="img"` sentence).
- Tests (Vitest + RTL, `renderApp` harness with fixtures): sign-in redirect when
  `/me` is 401, dev sign-in sets headers on the next request, time-range preset
  changes the URL and the `/stats` query, tiles and chart labels from a fixture,
  table toggle, empty range shows the empty state, 429 backs off once.
- Docs: README "Console" section with the local flow (`npm run dev` against the
  Open gateway, or the bundled app at `:8080`), screenshots slot.

### Slice 4 — Audit-log explorer and alerts feed (platform, `frontend/`)

- `pages/AuditLogPage.tsx`: filter bar (type, risk, user, text, anomalous) bound
  to the URL; table with risk badge, type chip, controls chips, relative time
  with absolute on hover; "Load older" keyset paging; row → **detail drawer**
  (all fields, decoded controls with framework labels, related alerts with links);
  "Export CSV" of the current result set (client-side, no new endpoint);
  "Create rule from this event" prefills the rule editor (type, risk, a suggested
  condition on `action`).
- `pages/AlertsPage.tsx`: feed newest first with rule name, event summary,
  channels notified (with "configured but not delivered" made visible when the
  rule's channels exceed `notifiedChannels`), filters by rule and range; detail
  drawer with the event and a link to the rule.
- Tests: filters produce the exact query string; paging appends and passes the
  cursor; drawer content; CSV content for a 2-row fixture; "not delivered" badge
  logic; empty and error states.

### Slice 5 — Rules editor and reports (platform, `frontend/`)

- `pages/RulesPage.tsx`: list with inline enable/disable (PUT, optimistic,
  per-row rollback), channels, threshold, last-fired (from alerts); **editor
  dialog**: name, description, event type, risk threshold, notification channels,
  and the **condition** with a side panel listing the fields of `AuditEvent` and
  the allow-listed methods (from `ConditionEvaluator`), live validation via
  `/validate` (debounced), and a **Dry run** button calling `/dry-run` for the
  current time range that shows "would have matched N of M events" plus five
  sample rows. Delete with confirm; deleting a rule that has alerts warns that
  history keeps them with the rule name blank.
- `pages/ReportsPage.tsx`: one card per framework, date range from the global
  picker, summary from `/summary` (events, by control, by risk), a text preview
  (fetch the download and show the first lines), and a Download button
  (`Content-Disposition` filename honoured). 413 explained ("narrow the range").
- Tests: validation error surfaces inline; dry-run renders counts and samples;
  save sends the exact `AlertRuleRequest`; toggle rollback on 500; delete
  confirm; report summary render, download link, 413 message.

### Slice 6 — Operator view, settings, polish, docs; custom domain (platform + infra)

- `pages/OperatorPage.tsx` (OPERATOR only; USER sees "not allowed"): customers
  table with search and sort, platform per-day chart, and **"View as"** which
  sets the acting customer (header) and returns to the dashboard with a visible
  banner "Viewing <name> as operator" and a one-click exit.
- `pages/SettingsPage.tsx`: who I am (customer, subject, roles), token expiry
  countdown with a "sign in again" hint, current rate-limit remaining, links to
  the API docs and the repo. Keyboard: `/` focuses the explorer's text filter,
  `?` shows shortcuts. Responsive: sidebar collapses under 900 px.
- Polish pass with a checklist: focus order and dialog traps, colour contrast
  (tokens re-validated), reduced-motion respected, every table has a caption,
  every chart a table view, no "null"/"undefined" ever rendered.
- Docs sweep: README (console section complete, screenshots, "what's implemented"
  no longer says no frontend, line 221 stale reporting mention fixed), CLAUDE.md,
  `docs/CODE-TOUR.md` Stop 7 "The console", `docs/plans/CONSOLE.md` marked built.

**Infra PR B (auditflow-infrastructure)** — the custom domain, once Boris
answers the DNS question:
- ACM certificate for `auditflow.areyouinquazzy.lol` (DNS validation) in the
  gateway's region; `aws_apigatewayv2_domain_name` + `aws_apigatewayv2_api_mapping`
  on the `$default` stage; Route 53 alias record.
- **Placeholder to resolve**: which repo owns the `areyouinquazzy.lol` hosted zone.
  Resistance's bootstrap has a `create_hosted_zone` flag for the tracker's use;
  if that zone is the apex zone, this repo reads it with `data "aws_route53_zone"`
  by name (the same "one creates, the other reads" rule as the OIDC provider).
  If the apex stays at the registrar's DNS, the plan becomes a single CNAME there
  and no Route 53 at all. Variables `console_domain` and `hosted_zone_name`
  default to empty so nothing is created until set.
- Cognito callback/logout URLs already point at the domain from Infra PR A;
  the hosted UI stays on the `amazoncognito.com` domain (a custom auth domain is
  a later nicety).

## Conventions for the executing session

- Branches `claude/console-<n>-<name>` off `develop` (platform) / `Develop`
  (infra); one PR per slice; stack when dependent and retarget as they merge.
  Ask Boris whether he merges or you do; his CLAUDE.md says he does.
- Verify before pushing: `mvn -B -q -pl services/api-gateway-service -am verify
  -Dfrontend.skip=true` (Testcontainers tests need Docker; in this container they
  are skipped, CI runs them), `cd frontend && npm ci && npm run build && npm test
  -- --run`, `terraform fmt -check` + `validate` for infra.
- No entities on the wire; every response is a record in `gateway/api/`.
- Every new endpoint: 401 anonymous test, 403 role test where relevant,
  customer-scoping test (another customer's rows never appear).
- Keep the Anthropic SDK and every other dependency out of the gateway; the
  console has exactly two runtime dependencies beyond React/router:
  `recharts` and `oidc-client-ts`.
- Credit discipline: no review-agent fan-out by default; one self-review pass
  per slice against the checklist in Slice 6.

## Verification (end to end, after Slice 6)

1. Platform: `mvn -DskipTests clean package` (bundles the console),
   `AUDIT_INGESTION_TOKENS=resistance=e2e-secret docker compose --profile app up --build -d`.
   Open `http://localhost:8080`: dev sign-in appears; choose `resistance`.
2. Resistance running against it (runbook in `docs/E2E-TEST-PLAN.md`): fail a
   login; within ten seconds the dashboard's "alerts" tile increments, the
   explorer shows the `LOGIN_FAILURE` row with `SOC2:AC-2, SOC2:IA-2`, the
   alerts feed shows `resistance-login-failures` with the channels it reached.
3. Rules: edit the login-failures rule's condition to something invalid → inline
   error; dry-run the valid one over 7 days → matches the failures; toggle it off
   → the next failure raises no alert (30 s refresh).
4. Reports: SOC 2 summary counts match the explorer; download opens a `.txt`
   whose first line is `SOC 2 Evidence Report`.
5. Operator: sign in as operator locally; customers table lists `resistance`;
   "View as" shows its dashboard with the banner; a plain user gets "not allowed".
6. Cloud (when `ecs_enabled` is flipped and the domain exists): Cognito hosted
   sign-in round-trips to `/callback`, `/api/v1/me` shows the claim's customer,
   a reload of `/alerts` returns the shell (the NONE route), `/api/**` without a
   token is 401 at API Gateway.
7. CI: Build (Maven + frontend job), Terraform plan for all environments green.
