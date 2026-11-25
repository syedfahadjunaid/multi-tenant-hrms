# AGENTS.md: guide for coding agents working on this repository

Multi-tenant HRMS (HR management system) with a permission-aware AI policy assistant. It's
built as an enterprise-style reference project, and the code doubles as learning material
(the owner is learning Java), so keep explanatory comments and idiomatic code. The README is
the human-facing overview; this file is the operating manual. When they disagree, check the
code and fix the doc.

## Map

| Path | What | Stack |
|---|---|---|
| `services/bff/` | Backend-for-frontend: Keycloak sign-in as a confidential client, tokens in a Redis session, CSRF, `/api/**` → core with the access token | Java 25, Spring Boot 4.2, Spring Cloud Gateway (server-webmvc, 2026.0), Spring Security 7 OAuth2 client, Spring Session Data Redis |
| `services/core-service/` | Core API: employees, reporting lines, leave workflow, audit, rate limiting, proxy to AI | Java 25, Spring Boot 4.2, Spring Security 7, Hibernate 7, Flyway, Maven wrapper |
| `services/ai-service/` | Policy assistant: ingestion, retrieval (embeddings, optional keywords and reranker), Claude answers | Python 3.12, FastAPI, psycopg 3, pgvector, fastembed (ONNX), Anthropic SDK |
| `services/ai-service/evals/retrieval/` | Retrieval evaluation: datasets + runner (CIs, held-out accuracy, paired comparisons) | Python |
| `services/ai-service/evals/answers/` | Answer-quality evaluation: real answer path + LLM judge; output in `.claude/hillclimb/answer-quality/` | Python, Anthropic SDK |
| `web/` | SPA: employees, leave + approvals, assistant; sign-in via the BFF (no OIDC library) | React 19, TypeScript 6, Vite 8, Tailwind 4, TanStack Query, React Router 8 |
| `infra/keycloak/hrms-realm.json` | Realm: roles, clients (`hrms-bff` confidential, `hrms-dev-cli` dev-only), demo users (dev password lives here), user profile | Keycloak 26.8 |
| `infra/postgres/init/01-init.sh` | One DB login + schema per service (`core_svc`/`core`, `ai_svc`/`ai`, `keycloak` DB) | Postgres 18 + pgvector 0.8.7 |
| `docker-compose.yml` | Default profile = infra; `--profile app` = + services + web | |
| `scripts/get-token.ps1`, `scripts/seed-demo.ps1` | Sign in a demo user from PowerShell; load demo employees/reporting lines/documents | |
| `.github/workflows/ci.yml`, `.github/dependabot.yml` | CI (tests, lint, eval gate, Trivy, image builds), dependency updates | |

## Status: what's built and what's left

**Built** (each is working, tested and documented in the README):

1. Core API: employees, multi-tenancy (`@TenantId`), audit trail, per-user rate limiting,
   RFC 9457 errors.
2. AI policy assistant: document ingestion, permission-aware retrieval (pgvector), Claude
   answers with citations, PII redaction, prompt-injection escaping, offline mode.
3. React frontend: role-aware navigation, employee directory, leave requests + approvals,
   assistant page.
4. Leave workflow: employee → manager → HR approvals with history and balances; editable
   reporting lines, with loop detection and approvals that follow the new manager.
5. Retrieval quality: real embeddings (bge-small); reranker and hybrid search built but off
   (measured); a 189-question eval in two slices; a CI gate.
6. Answer-quality eval: real answer path plus an LLM judge. Built and tested offline; **its
   first baseline run is still to do** (needs an API key and the owner's OK).
7. Backend-for-frontend (Spring Cloud Gateway): tokens server-side, HttpOnly cookie, CSRF.

**Left to build**, in the suggested order. Each is a self-contained milestone:

1. **Answer-eval baseline**: put a key in `.env`, then run
   `--answerer oracle --approve-harness` (owner), `--answerer empty`, `--answerer wrong`,
   `--limit 5`, and the full run. Record the numbers in the README.
2. **Public-holiday calendars per tenant**:
   - a `holidays (tenant_id, date, name)` table with a composite key (new Flyway migration);
   - HR-only CRUD endpoints;
   - `WorkingDays` skips holidays (it currently skips weekends only);
   - tests for leave requests that span a holiday.
3. **Email notifications on each leave step**:
   - an `@TransactionalEventListener(phase = AFTER_COMMIT)` on leave events sends mail;
   - add a Mailpit container to compose for local mail;
   - test with a fake mail sender.
4. **OIDC back-channel logout** in the BFF (Spring Security `oidcLogout().backChannel()`),
   plus Keycloak's back-channel logout URL on the `hrms-bff` client. Then signing out of
   Keycloak elsewhere ends the BFF session at once.
5. **Gateway-level rate limiting** in the BFF: per session, with Redis, before requests reach
   core.
6. **Kafka events**:
   - publish `EmployeeCreated` and leave events through a transactional outbox;
   - the AI service and a new notification service consume them.
7. **Postgres row-level security** as a second tenant-isolation layer:
   - add `SET app.tenant_id` per transaction;
   - add policies on every tenant table;
   - add a test showing that native SQL cannot cross tenants.
8. **End-to-end browser tests**: Playwright for sign-in, leave approval and role-based
   menus, run in CI against `docker compose`.
9. **Observability**: OpenTelemetry tracing across bff → core → ai-service, with Grafana,
   Tempo and Prometheus in compose.
10. **Deployment**: Kubernetes manifests or a Helm chart, then deploy to a cloud provider,
    with TLS and `BFF_COOKIE_SECURE=true`.
11. **AI follow-ups**:
    - prompt-injection cases for the answer eval (policies containing hostile instructions);
    - questions from real users, once there are any.

## Run and verify

The dev machine is Windows (PowerShell 5.1 and Git Bash both available) with Docker Desktop.
**There is no local JDK or Maven**, so Java is built and tested inside Docker. Clean copies
(including zips) omit `.env`, `node_modules`, `.venv`, `target`, `dist`, caches and eval
output; Docker rebuilds them, or the clean-copy steps below do (on Windows, `python` may
point only to the Microsoft Store stub until Python 3.12 is installed).

**Prerequisites**
- **Docker Desktop** with Compose v2 (~8 GB RAM, ~6 GB disk; Keycloak, Postgres and three app
  services run at once). Nothing else is needed just to run the stack; the first build needs
  internet access for Maven, npm, PyPI, Docker Hub and Hugging Face (embedding model).
- **Optional for local dev/tests outside Docker:** JDK 25 (Temurin), Node.js 24, Python 3.12,
  PowerShell (`pwsh` 7 on macOS/Linux).

**Start the stack** (from the project root):

```powershell
Copy-Item .env.example .env                   # once; git-ignored (add ANTHROPIC_API_KEY for Claude)
docker compose --profile app up -d --build    # everything; web on http://localhost:5280 (5-10 min first time)
.\scripts\seed-demo.ps1 -Documents            # demo employees, reporting line, 9 policies from hr_small.json (once)
```

Keycloak imports `infra/keycloak/hrms-realm.json` (including `BFF_CLIENT_SECRET` from `.env`)
only on its first start. To reset everything from scratch: `docker compose down -v`.

**Clean-copy local test setup** (when running Python/web checks outside Docker):
- **Python** (`services/ai-service`): `python -m venv .venv`, `.\.venv\Scripts\pip install -e ".[dev]"`
  (first eval run downloads the ~70 MB embedding model into a local cache).
- **Web** (`web/`): `npm ci`.

| Service | Check before you call a change done |
|---|---|
| Java | `docker run --rm -v "${PWD}\services\core-service:/workspace" -v hrms-m2:/root/.m2 -v /var/run/docker.sock:/var/run/docker.sock -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal -w /workspace eclipse-temurin:25-jdk sh mvnw -B verify` (from the repo root; ~3-4 min, Testcontainers starts Postgres/Redis; or `.\mvnw.cmd verify` with a local JDK 25). Same command with `services\bff` for the BFF (~2 min; fake Keycloak + fake core API) |
| Python | In `services/ai-service`: `.\.venv\Scripts\python.exe -m pytest` (integration tests need Docker), `.\.venv\Scripts\ruff.exe check .`, `.\.venv\Scripts\ruff.exe format --check .` |
| Retrieval quality | `python -m evals.retrieval.run` (add `--store postgres` when SQL changes; `--hybrid`, `--reranker <name>`, `--model <name>`, `--dataset small`, `--show-misses`) |
| Answer quality | `python -m evals.answers.run` (**paid**, needs a key and the owner's OK; `--limit 5` pilot, `--variant v1 --effort low`, `--summary`). Offline: `pytest tests/test_answer_eval.py` |
| Web | In `web/`: `npm test`, `npm run lint`, `npm run build` (includes `tsc`) |
| Running stack | `docker compose --profile app up -d --build <service>`, then smoke-test with `. .\scripts\get-token.ps1 bob` and `Invoke-RestMethod` against `http://localhost:8080/api/...`. Auth or BFF changes: also sign in at http://localhost:5280 in a browser (an unauthenticated `GET :5280/api/me` must be 401) |

**Ports** (bound to 127.0.0.1; non-standard because other local projects hold 5432, 6379 and 5173):
web **5280** (also Keycloak's redirect URI, so changing it means updating the realm), BFF **8090**,
core **8080** (management/actuator **8081** in each Java service, not published), AI **8000**,
Keycloak **8180**, Postgres **15432** (`POSTGRES_HOST_PORT` in `.env`), Redis **16379**
(`REDIS_HOST_PORT` in `.env`). Before adding a port, check
`Get-NetTCPConnection -State Listen -LocalPort <port>`.

**Demo users** (realm `hrms`, password in `infra/keycloak/hrms-realm.json`, never repeat it in chat or docs):
`alice` HR_ADMIN @acme, `mark` MANAGER @acme, `bob` EMPLOYEE @acme (reports to mark),
`carol` HR_ADMIN @globex.

## Request flow

Browser → `web` (nginx :5280, the only origin the browser uses) → BFF for `/api/*`,
`/oauth2/*`, `/login/oauth2/*` and `/logout`. Sign-in: the BFF runs the code flow with PKCE
against Keycloak as the **confidential** client `hrms-bff`, stores the tokens in its Spring
Session (Redis), and gives the browser only the HttpOnly cookie `HRMS_SESSION`. API calls:
the BFF checks the session and the CSRF header, adds the access token (refreshing it if
needed, `TokenRelay`), strips the browser's cookies and forwards to core-service. Core then
verifies the JWT (signature, issuer, audience `hrms-api`, `tenant_id` claim) → Postgres
schema `core`. The assistant: core-service relays the user's own token to ai-service
(HTTP/1.1), which verifies it again and searches `ai.document_chunks` with tenant + role +
model filters in SQL, then (optionally) calls Claude. Scripts call core directly with tokens
from the dev-only `hrms-dev-cli` client.

## Invariants: do not break these

**Tenancy and authorization**
- The tenant comes **only** from the verified JWT (`tenant_id` claim). Never accept a tenant
  from a request body or query string. DTOs don't have such fields; Python models use
  `extra="forbid"`.
- Tenant-scoped JPA entities have a `@TenantId` field; Hibernate filters every JPQL query.
  **Native SQL bypasses that filter**: avoid it, or add `tenant_id` explicitly. (The only
  native query today is the advisory lock in `EmployeeRepository`.)
- Cross-row links use composite foreign keys `(tenant_id, id)`, so the DB rejects
  cross-tenant references. Keep that pattern for new tables.
- Role checks: `@PreAuthorize` on controller methods. Relationship checks (requester /
  assigned manager / HR, separation of duties) live in `LeaveService`. Status codes are
  deliberate: **404** if you may not see it, **403** if you may see it but not do this,
  **409** if the state forbids it, **422** for business-rule violations (with `errors[]`).
- Accounts link to employee records only by **verified** email (`email_verified` claim).
- ai-service never trusts core-service: it re-verifies the user token. Retrieval filters
  (tenant, `allowed_roles`, `embedding_model`) are inside the SQL of **both** vector and
  keyword search. Never filter afterwards in Python.

**Data integrity**
- Flyway migrations (`src/main/resources/db/migration`) are immutable once applied: add
  `V<n+1>__description.sql`, never edit an old one. Hibernate runs with `ddl-auto: validate`.
- `audit_events` and `leave_request_events` are append-only (DB triggers). Audit writes use
  `Propagation.MANDATORY`, so they commit with the change they describe.
- Leave workflow transitions live only in `LeaveStatus` (exhaustive `switch`). Add states or
  actions there and let the compiler point at every place to update.
- Optimistic locking (`@Version`) on requests and employees; a row lock while checking leave
  balance; a per-tenant advisory lock for reporting-line changes (prevents org-chart loops).
- ai-service owns its schema via idempotent DDL in `PgVectorStore.init_schema()`; each chunk
  records `embedding_model`, and startup re-embeds stale chunks.

**AI and web safety**
- Retrieved documents are untrusted: they are HTML-escaped and fenced in `<document>` tags
  (`prompts.py`); PII is redacted before any LLM call (`pii.py`); the model has no tools.
- LLM output is rendered as plain text in the UI. Never use `dangerouslySetInnerHTML`.
- The web CSP (`web/nginx.conf`) allows no inline scripts/styles and only `self` for
  connections. React `style={{...}}` is fine (CSSOM), `<style>`/inline HTML is not.
- **No tokens in the browser.** The SPA has no OIDC library: tokens live only in the BFF's
  session. The session cookie is HttpOnly and SameSite=Lax (Strict would break Keycloak's
  redirect back). Cookie names are app-specific (`HRMS_SESSION`, `HRMS-XSRF-TOKEN`), because
  cookies on localhost are shared across ports.
- Every state-changing request through the BFF needs `X-XSRF-TOKEN` (from the
  `HRMS-XSRF-TOKEN` cookie); `apiFetch` adds it, and sign-out is a POST with it. Don't add
  CSRF exemptions.
- The BFF fails closed: an unauthenticated `/api/**` call gets 401, never a redirect. No
  token, or a rejected refresh, means 401 and the session is ended. Apart from `/api/**`,
  sign-in/sign-out, `/error` and actuator health/info/prometheus endpoints (on internal port
  8081), everything is `denyAll`.
- Redirect URIs come from `hrms.bff.public-url`, never from request headers. Post-login
  return paths go through `safeReturnTo`; sign-in/out redirects are de-duplicated by
  `leavingPage()`.
- Secrets only in `.env` (git-ignored). Compose uses `${VAR:?...}` so nothing has a default
  password.

## Retrieval changes are decided by the eval

- Defaults today: embedding `BAAI/bge-small-en-v1.5` with "not found" cut-off **0.55**;
  **reranker off**; **hybrid (keyword) search off**, because keywords hurt everyday questions
  and bge-small already handles exact terms (89% top-1). The numbers are in the README.
- Cut-offs are per model (`DEFAULT_MIN_SCORES` in `embeddings.py`, `DEFAULT_RERANK_MIN_SCORES`
  in `rerank.py`; `None` there = the embedding decides "not found", the "vector gate").
  Never reuse one model's cut-off for another.
- Rule used so far: turn a retrieval feature on only if its **held-out accuracy** improvement
  over the default has a 95% CI above zero on `hr_large.json`. State the rule *before*
  running the comparison.
- `hr_large.json` has two slices: `general` (no `slice` field: everyday paraphrases) and
  `exact_terms` (each question has a `term` that must appear in the question and verbatim in
  a right policy, or in no policy if unanswerable). The runner reports every slice
  separately, and decisions should name which slice they rest on.
- Datasets must stay consistent (`tests/test_eval_datasets.py`). Don't add questions after
  seeing results just to make a feature look good. A new kind of question becomes a new
  slice, with the decision rule written down before running it.
- CI fails if bge-small's hit@1 over all large-set questions drops below 75% (currently 82%;
  89% on exact terms, 80% on general).

## Answer changes are decided by the answer eval

- `evals/answers/run.py` runs the real `AssistantService` (built by `build_service(settings,
  ...)` in `main.py`, the same function production uses) and grades each answer:
  - `correct` (headline): LLM judge with the right policy as reference;
  - `grounded`: LLM judge, against the shown documents only;
  - `cited`: code.

  The judge is `claude-sonnet-5-5`. Never judge with the model under test.
- Case kinds (`tags[0]`) are computed at run time from what retrieval put in the context:
  `answerable` / `retrieval-miss` / `unanswerable` / `no-llm-call`. Only `answerable` expects
  an answer; the rest expect "not found". Name the kind a decision rests on.
- Use it for prompt (`prompts.py`), model, effort or `top_k` changes: run a variant
  (`--variant v1 ...`) and compare with `baseline` on the same questions. State the decision
  rule before running.
- It costs money and needs `ANTHROPIC_API_KEY`, so it is not in CI, and agents must get the
  owner's OK before running it. `--approve-harness` is the owner's to pass, never an
  agent's. Offline coverage: `tests/test_answer_eval.py`.
- Failed calls go to `errors.jsonl` and never count as wrong answers. A row with zero usage
  from a real model is a bug (the runner refuses to write it).
- `LLMResult` carries `stop_reason` and `usage` for this eval; keep them filled in any new
  `LLMClient`.

## Conventions

**Java (core-service)**
- Constructor injection; records for DTOs and config (`HrmsProperties`); services
  `@Transactional(readOnly = true)` by default, plain `@Transactional` on writers; thin
  controllers; errors as RFC 9457 `ProblemDetail` via `GlobalExceptionHandler`.
- Spring Boot 4 specifics: starters like `spring-boot-starter-webmvc`; test annotations
  moved (e.g. `org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc`);
  Jackson 3; Testcontainers 2 (`org.testcontainers.postgresql.PostgreSQLContainer`).
- Tests: plain JUnit for pure logic, `@SpringBootTest` + MockMvc + Testcontainers for APIs.
  Use `TestUsers` (`user`, `person`, `unverified`, `newTenant`, `runAsTenant`) and a fresh
  tenant per test. In `jsonPath`, compare arrays with Hamcrest `contains(...)`, not `List.of`.
- `AiClient` must stay on HTTP/1.1 (the JDK client's h2c upgrade breaks Uvicorn).

**Python (ai-service)**
- Ruff (line length 100, includes bandit `S` rules); type hints; `Protocol` interfaces
  (`Embedder`, `Reranker`, `LLMClient`, `VectorStore`); `create_app(settings, service=...,
  verifier=...)` for dependency injection in tests; JWTs in tests are signed with a local key.
- Claude calls go through the official `anthropic` SDK (`llm.py`): default model
  `claude-opus-5-5`, explicit `output_config.effort`, server-side refusal fallbacks, check
  `stop_reason` before reading content. Offline mode (no `ANTHROPIC_API_KEY`) must keep working.
- Models are baked into the image at build time (`docker/download_models.py`) and the
  container runs with `HF_HUB_OFFLINE=1`.

**Web**
- All HTTP goes through `apiFetch` (`api/client.ts`) and TanStack Query hooks in
  `api/hooks.ts`; show server field errors with `ApiError.messageFor(field)`.
- Role-based UI is cosmetic; the server enforces. The leave UI renders exactly the
  `allowedActions` the API returns; don't re-implement permission rules in TypeScript.
- Named exports, TypeScript strict, Tailwind classes, Vitest + Testing Library.

## Things that already bit us

- **Port clashes**: another local project's Vite server sat on `[::1]:5173` and silently
  shadowed this app. That's why the web app is on 5280 (also in Keycloak's redirect URIs and
  core's CORS list).
- **Refresh-token rotation is off** (`revokeRefreshToken: false` in the realm). With it on,
  parallel API calls that refresh at the same moment race: Keycloak accepts the first and
  rejects the rest, which signs the user out. Rotation protects public clients; the BFF is
  confidential. Re-enabling it needs a per-session refresh lock in the BFF.
- **Keycloak placeholders**: `"secret": "${BFF_CLIENT_SECRET}"` in `hrms-realm.json` is filled
  from the Keycloak container's environment, but only at the first import. Change the running
  client's secret with kcadm.
- **Inside Docker, "localhost" is the container.** The BFF can't use Keycloak's discovery
  document, so `KeycloakClientConfig` builds the registration by hand: browser-facing
  authorize/logout URLs on localhost:8180, token and JWKS calls on keycloak:8080.
- **Keycloak realm import runs only on first start** (persisted in Postgres). Change the
  running realm with `kcadm.sh` *and* update `hrms-realm.json`. Pipe scripts with Git Bash
  (`docker compose exec -T keycloak bash < script.sh`). PowerShell adds a BOM/CRLF that breaks them.
- **Windows PowerShell 5.1**: an `if` block unwraps a one-element array (wrap in `@(...)`);
  `Invoke-RestMethod -Method Post` without a body sends a form content type (send JSON);
  console output is cp1252 (`run.py` calls `sys.stdout.reconfigure(errors="replace")`).
- **Rate limit**: `/api/assistant/**` allows 10 requests per user per minute, which also
  limits bulk document uploads (`seed-demo.ps1 -Documents` uploads 9 policies, so running it
  twice within a minute hits 429).
- **Toy embedder in unit tests** (`HashingEmbedder`) collides words via hashing; pick test
  phrases whose scores you have checked.
- The ms-marco rerankers score policy paragraphs ~0 but rank them well; that's why the
  vector gate exists.

## Learning guide

This codebase doubles as learning material; the reading order, glossary and hands-on exercises
live in [`docs/LEARNING.md`](docs/LEARNING.md), and `README.md` has a Java file-by-file list.

## Definition of done

1. Tests and linters pass for every service you touched (commands above).
2. Retrieval changes: eval re-run, numbers and decision recorded in the README.
3. Schema changes: a new Flyway migration (core) or idempotent DDL in `init_schema()` (ai).
4. The running stack is rebuilt and smoke-tested as a real user (`get-token.ps1`).
5. README and this file updated if behaviour, commands, ports or decisions changed.
6. Don't commit or push unless the owner asks. `main` is protected on GitHub (PR required
   with all 6 jobs in `.github/workflows/ci.yml` passing; no direct pushes or force pushes).
