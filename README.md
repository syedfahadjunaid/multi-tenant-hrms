# HRMS Platform

A multi-tenant HR management system with a secure, permission-aware AI policy assistant.
Built as an enterprise-style reference project: React/TypeScript frontend, Java/Spring Boot
core API, Python/FastAPI AI service, Keycloak single sign-on, PostgreSQL + pgvector, Redis,
Docker and CI.

```
  ┌──────────────────────────┐
  │ web  (React 19, Vite)    │  the browser holds an HttpOnly session cookie, never a token
  │ nginx :5280              │
  └────────────┬─────────────┘
               │ /api/*, sign-in, sign-out (same origin, cookie + CSRF header)
               ▼
  ┌──────────────────────────┐  code + PKCE + client secret  ┌──────────────┐
  │ bff  (Spring Cloud       │◄─────────────────────────────►│   Keycloak   │  OIDC, roles, tenant claim
  │ Gateway)  :8090          │  token refresh                └──────┬───────┘
  └────────────┬─────────────┘                                      │ signing keys (JWKS)
               │ /api/*  + the user's access token                  │ (both services)
               ▼                                                    ▼
┌───────────────────────────────────────┐        ┌───────────────────────────────┐
│ core-service  (Java 25, Spring Boot 4)│  JWT   │ ai-service  (Python, FastAPI) │
│ employees, audit log, rate limiting   ├───────►│ RAG over HR policy documents  │──► Claude API
│ :8080                                 │ relay  │ :8000                         │   (optional)
└───────┬───────────────────┬───────────┘        └───────────────┬───────────────┘
        │ schema "core"     │                                    │ schema "ai" (pgvector)
        ▼                   ▼                                    ▼
   PostgreSQL 18 ◄──────────┼────────────────────────────────────┘
                          Redis 8 (BFF sessions, per-user rate limits)
```

## What this project demonstrates

| Area | Implemented | Where |
|---|---|---|
| Frontend | React 19 + TypeScript SPA: role-aware navigation, employee directory, leave requests and approvals, AI assistant | `web/src/` |
| Leave workflow | Two-step approval (manager, then HR) as a compiler-checked state machine; balances; full history | `leave/LeaveStatus.java`, `LeaveService.java` |
| Relationship-based authorization | Who may act depends on their link to the record (requester, assigned manager, HR), not just their role; 404 vs 403 vs 409 chosen deliberately | `LeaveService.java` |
| Concurrency | Optimistic locking (`@Version`) so two approvers can't both win; row lock so parallel requests can't overdraw the balance; per-tenant advisory lock so parallel reorgs can't create a loop | `LeaveRequest.java`, `EmployeeRepository.java` |
| Org chart | HR changes reporting lines in place; loops (A → B → A) rejected by walking the chain; pending approvals follow the new manager | `EmployeeService.java`, `ManagerCell.tsx` |
| Domain events | `ManagerChanged` is published in the same transaction; the leave module reacts without the employee module knowing about it | `ManagerChanged.java`, `LeaveService#onManagerChanged` |
| Single sign-on (BFF) | Backend-for-frontend on Spring Cloud Gateway: OpenID Connect code flow with PKCE as a *confidential* client; tokens stay in a Redis-backed server session and the browser gets only an HttpOnly cookie; CSRF double-submit; refresh on the server; sign-out ends the Keycloak session too | `services/bff/`, `web/src/auth/` |
| Authentication | OAuth2 resource servers; JWT signature, expiry, issuer, audience and tenant checks | `SecurityConfig.java`, `security.py` |
| Authorization | Keycloak realm roles → `@PreAuthorize` per endpoint | `EmployeeController.java` |
| Multi-tenancy | Hibernate `@TenantId` filters every query by the caller's tenant; composite foreign keys stop cross-tenant links in the database itself | `TenantIdentifierResolver.java`, `V2__leave_requests.sql` |
| Audit trail | Written in the same transaction; Postgres triggers make it append-only | `AuditService.java`, `V1__init.sql` |
| Rate limiting | Redis fixed window, per user; stricter budget for the AI assistant | `RateLimitFilter.java` |
| AI / RAG | Chunking, a local embedding model (BAAI/bge-small-en-v1.5 on ONNX Runtime), pgvector similarity search, optional hybrid keyword search (Postgres full-text + RRF) and cross-encoder reranking, Claude for answers with citations | `app/rag/` |
| AI evaluation | Retrieval eval sets (up to 66 policies, 189 questions in two slices: everyday paraphrases and exact terms, incl. unanswerable ones); confidence intervals, held-out accuracy, paired and per-slice comparisons; embedding, reranker and hybrid trade-offs incl. latency; CI fails if quality drops | `evals/retrieval/` |
| Model versioning | Every vector records the model that made it; switching models re-embeds old chunks and never mixes vector spaces; dimension mismatches fail fast | `store.py` |
| AI security | Permission-aware retrieval, PII redaction, prompt-injection escaping, token relay | `store.py`, `pii.py`, `prompts.py` |
| Data security | One DB login + schema per service (least privilege); no default passwords | `infra/postgres/init/01-init.sh` |
| API quality | Validation, RFC 9457 problem responses, paging, optimistic locking | `EmployeeDtos.java`, `GlobalExceptionHandler.java` |
| Testing | Unit tests plus integration tests against real Postgres/Redis (Testcontainers) | `src/test`, `tests/` |
| DevOps | Multi-stage non-root Docker images, Compose, GitHub Actions, Trivy scan, Dependabot | `Dockerfile`s, `.github/` |
| Web security | Strict Content-Security-Policy, one origin for everything (nginx → BFF), open-redirect guard, LLM output rendered as text | `web/nginx.conf`, `session.ts` |
| Observability | Actuator health/readiness, Prometheus metrics on an internal port | `application.yml` |

## Quick start

Prerequisite: [Docker Desktop](https://www.docker.com/products/docker-desktop/).

```powershell
Copy-Item .env.example .env
docker compose --profile app up -d --build
```

The first start takes a few minutes (image downloads, Maven and npm builds). Then load the
demo data: employee records for the demo users (so they can request leave), the reporting
line bob → mark, and two HR policy documents.

```powershell
.\scripts\seed-demo.ps1 -Documents
```

Open **http://localhost:5280** and sign in as one of the demo users below.

| Service | URL |
|---|---|
| **Web app** | http://localhost:5280 |
| BFF (the browser's API; also what `npm run dev` forwards to) | http://localhost:8090 |
| Core API (bearer tokens, e.g. from `get-token.ps1`) | http://localhost:8080 |
| AI service (+ Swagger UI at `/docs`) | http://localhost:8000 |
| Keycloak admin console (user `admin`, password in `.env`) | http://localhost:8180 |
| Postgres / Redis (host ports, chosen to avoid clashes) | `localhost:15432` / `localhost:16379` |

### Demo users

All demo users share one development-only password, stored in `infra/keycloak/hrms-realm.json`.

| User | Roles | Tenant |
|---|---|---|
| `alice` | HR_ADMIN, EMPLOYEE | acme |
| `mark` | MANAGER, EMPLOYEE | acme |
| `bob` | EMPLOYEE | acme |
| `carol` | HR_ADMIN, EMPLOYEE | globex |

### Try it in the browser

**Leave approval, end to end:**

1. Sign in as **bob** (employee). Open *Leave*, see the 25-day balance, and request three days
   of annual leave. The request is *Waiting for manager*.
2. Sign out, sign in as **mark** (bob's manager). *Leave → Approvals* shows bob's request.
   Approve it with a comment; it moves to *Waiting for HR*. Mark can't approve the HR step.
3. Sign in as **alice** (HR admin) and approve it. Back as bob, the request is *Approved*,
   the balance has dropped by three days, and *Show history* lists every step with comments.

**Reorganising a team:** as **bob**, request leave again (it waits for mark). As **alice**,
open *Employees* and use *Change* in bob's *Manager* column to move him under alice. The
picker never offers bob's own reports, and the server rejects loops anyway. Bob's waiting
request moves from mark's queue to alice's, and its history explains why. Choosing
*No manager* sends it straight to HR instead.

**Roles, tenants and the AI assistant:**

1. As **alice**, open *Employees* and *Policy assistant*, where HR can add documents and
   choose which roles may see each one.
2. As **bob**, there's no *Employees* menu, and the assistant answers leave questions but
   never returns the salary bands.
3. As **carol** (globex), none of acme's employees, documents or leave requests are visible.

### Try it from PowerShell

The browser goes through the BFF, but the core API is an ordinary OAuth2 resource server: any
client with a valid token can call it. The dev-only CLI client gets one with a password grant.

```powershell
# Sign in (puts an access token in $env:TOKEN; it expires after 5 minutes)
. .\scripts\get-token.ps1 alice
$h = @{ Authorization = "Bearer $env:TOKEN" }

# Who am I?
Invoke-RestMethod http://localhost:8080/api/me -Headers $h

# Create an employee (HR_ADMIN only)
$emp = @{ employeeCode = 'ACME-001'; firstName = 'Dana'; lastName = 'Lee'; email = 'dana@acme.test';
          department = 'Engineering'; jobTitle = 'Engineer'; hireDate = '2024-03-01' } | ConvertTo-Json
Invoke-RestMethod http://localhost:8080/api/employees -Method Post -Headers $h -Body $emp -ContentType 'application/json'
Invoke-RestMethod http://localhost:8080/api/employees -Headers $h

# Leave: bob requests, mark approves, alice (HR) approves
. .\scripts\get-token.ps1 bob
$req = @{ type = 'ANNUAL'; startDate = '2027-02-08'; endDate = '2027-02-10'; reason = 'Ski trip' } | ConvertTo-Json
$leave = Invoke-RestMethod http://localhost:8080/api/leave/requests -Method Post -Body $req -ContentType 'application/json' `
    -Headers @{ Authorization = "Bearer $env:TOKEN" }
function Approve($id, $comment) {
    Invoke-RestMethod "http://localhost:8080/api/leave/requests/$id/approve" -Method Post -ContentType 'application/json' `
        -Body (@{ comment = $comment } | ConvertTo-Json) -Headers @{ Authorization = "Bearer $env:TOKEN" }
}
. .\scripts\get-token.ps1 mark
Approve $leave.id 'Have fun'          # status -> PENDING_HR
. .\scripts\get-token.ps1 alice
Approve $leave.id 'Approved'          # status -> APPROVED

# Ask the assistant through the core API
function Ask($question) {
    Invoke-RestMethod http://localhost:8080/api/assistant/ask -Method Post -ContentType 'application/json' `
        -Headers @{ Authorization = "Bearer $env:TOKEN" } -Body (@{ question = $question } | ConvertTo-Json)
}
Ask 'What are the engineering salary bands?'      # alice (HR_ADMIN) gets "Salary bands" as a source

# bob (EMPLOYEE) gets the leave policy, but the salary bands are never retrieved for him
. .\scripts\get-token.ps1 bob
Ask 'How many days of annual leave do I get?'
Ask 'What are the engineering salary bands?'      # no sources: not allowed for his role

# Sign in as carol (another tenant): she sees none of acme's employees or documents
. .\scripts\get-token.ps1 carol
Invoke-RestMethod http://localhost:8080/api/employees -Headers @{ Authorization = "Bearer $env:TOKEN" }
```

**Offline vs. real AI answers.** With `ANTHROPIC_API_KEY` empty, the assistant runs in offline
mode and returns the best-matching passage. No API calls, no cost. Put a key in `.env` and
restart (`docker compose --profile app up -d`) to get generated answers with citations.
Real calls are billed to that key.

## Day-to-day development

Run the infrastructure in Docker and the service you're working on from your IDE:

```powershell
docker compose up -d                     # Postgres, Keycloak, Redis only
```

**Java service.** Install JDK 25 (`winget install EclipseAdoptium.Temurin.25.JDK`) and
[IntelliJ IDEA Community](https://www.jetbrains.com/idea/download/). Open
`services/core-service` and run `CoreServiceApplication`. It reads `../../.env` automatically.

```powershell
cd services/core-service
.\mvnw.cmd spring-boot:run
.\mvnw.cmd verify                        # tests (needs Docker for Testcontainers)
```

The BFF (`services/bff`, `BffApplication`) works the same way. Its tests play the browser
over real HTTP: a fake Keycloak issues signed tokens, a fake core API records what the BFF
forwards, and sessions live in a Testcontainers Redis. They cover sign-in with PKCE, the
token relay, CSRF, server-side refresh, a rejected refresh, and sign-out.

**Python service.**

```powershell
cd services/ai-service
python -m venv .venv; .\.venv\Scripts\Activate.ps1
pip install -e ".[dev]"
uvicorn app.main:app --reload --port 8000
pytest; ruff check .; ruff format --check .   # integration tests need Docker running
python -m evals.retrieval.run                  # retrieval quality report (see below)
python -m evals.answers.run --limit 5          # answer quality (needs ANTHROPIC_API_KEY; see below)
```

The first run downloads the embedding model (~67 MB) into a local cache.

**Measuring retrieval quality.** `evals/retrieval/datasets` has two sets of realistic HR
policies with questions in everyday words, including questions no policy answers:

* `hr_large.json` (the default): **66 policies** full of near-neighbours (eight kinds of
  leave, overlapping expense rules, two salary-band documents, three ways to report
  misconduct, personal vs. company phones), and **189 questions in two slices**:
  * *general*: 131 answerable + 18 unanswerable everyday paraphrases;
  * *exact_terms*: 35 + 5 questions that use a term appearing verbatim in the right policy
    (TOIL, VPN, E4, "tailgating", "facilitation payments"), plus systems no policy mentions
    (SAP, Okta, JIRA). A test checks every term really is in the question and the policy.

  Where two documents genuinely answer a question, both count as right.
* `hr_small.json`: the 9-policy subset the demo seed loads.

The report gives 95% confidence intervals (bootstrap), **held-out accuracy** (the "not found"
cut-off is chosen on half the questions and scored on the other half, so it isn't flattered by
tuning on the test questions), a **paired comparison** with the default on the same questions
with a verdict, and the same per slice. Results on the general slice:

| Embedder | hit@1 (95% CI) | hit@3 | MRR | Held-out accuracy | ms/question |
|---|---|---|---|---|---|
| keyword hashing (old) | 26% (19–34) | 46% | 0.40 | 26% | 0 |
| **BAAI/bge-small-en-v1.5 (default)** | **80% (73–86)** | **92%** | **0.87** | **74%** | 14 |
| sentence-transformers/all-MiniLM-L6-v2 | 76% (68–82) | 91% | 0.85 | 68% | 9 |
| snowflake/snowflake-arctic-embed-s | 67% (60–76) | 83% | 0.77 | 65% | 25 |

Compared with bge-small, MiniLM shows "no clear difference" (−5 points, CI −11 to +1) and
arctic is measurably worse. On the 9-policy set MiniLM had looked one question *better*:
exactly the kind of noise a small set produces. The "not found" cut-off for bge-small is
0.55, because the best value rises with collection size (more documents, more chances for an
unrelated question to half-match one). 0.55 is best on the small set and within noise of
the best on the large one.

**Reranking (built, measured, off by default).** Retrieval supports an optional second stage:
a cross-encoder reads the question together with each of the top 20 candidates and re-orders
them (`app/rag/rerank.py`, `app/rag/retrieval.py`). The ms-marco models rank well but score
almost every policy paragraph near 0, so they can't decide "not found". For those, the
embedding score makes that decision and the reranker only sets the order. On the general slice:

| Pipeline, compared with bge-small alone | Change in hit@1 (95% CI) | Change in held-out accuracy | ms/question |
|---|---|---|---|
| + jina-reranker-v1-tiny-en | +2% (−6 to +9) | +0% (−8 to +8) | ~670 |
| + ms-marco-MiniLM-L-6-v2 | +7% (0 to +15) | +3% (−5 to +10) | ~720 |
| + ms-marco-MiniLM-L-12-v2 | **+8% (+1 to +15)** | +3% (−4 to +11) | ~1700 |

With many similar documents, ms-marco genuinely ranks better (top-3 rises from 92% to 97%).
The end-to-end gain is +3 points with an interval that includes zero, and every reranker
costs 35–85× the latency on a CPU. The rule, set before running this: turn reranking on only
if the end-to-end improvement is clearly above zero. It wasn't, so reranking ships off. If
you'd rather trade latency for better ranking (for example when an LLM answers from the top 5
passages), ms-marco-L6 is the best value:

```powershell
docker compose --profile app build --build-arg RERANKER_MODEL=Xenova/ms-marco-MiniLM-L-6-v2 ai-service
docker compose --profile app up -d ai-service
```

(`bge-reranker-base` is 1 GB and `jina-reranker-v2` is licensed non-commercial, so they weren't
considered.)

**Hybrid search (built, measured, off by default).** Retrieval can also search by keywords
with Postgres full-text search (an English-stemmed, GIN-indexed `tsvector` column next to the
embedding), and merge both rankings with **reciprocal rank fusion** (RRF). Everything stays
in Postgres, and the keyword query has the same tenant / role / model filter as the vector
query. Keywords only change the *order*: the embedding similarity still decides "not found",
so a keyword match can never make an unrelated question look answerable. Measured against a
real Postgres (`--store postgres`, which runs the production SQL in a throwaway container),
first on the 149 general questions only:

| Pipeline | hit@1 (95% CI) | Held-out accuracy | Change in hit@1 vs vectors only |
|---|---|---|---|
| **bge-small, vectors only (default)** | **80% (73–86)** | **74%** | |
| bge-small + Postgres keywords (RRF) | 72% (64–79) | 70% | −8% (−15 to −1): worse |
| bge-small + BM25 keywords (in-memory, for comparison) | 69% (60–76) | 66% | −11% (−20 to −4): worse |

On everyday paraphrases ("my son's daycare closed" rather than "carer's leave"), keyword
matching fires on generic words (*leave*, *day*, *office*, *company*) shared by many
neighbouring policies, and that noise outvotes the embedding. It isn't Postgres's ranking:
real BM25 did even worse.

To judge keyword search fairly, the eval then got its **exact-terms slice**, and the decision
rule was fixed before running it: *turn hybrid on only if it clearly improves the exact-terms
slice and isn't clearly worse on the general slice*.

| Slice | bge-small alone: hit@1 / held-out | + keywords: hit@1 / held-out | Change in held-out accuracy (95% CI) |
|---|---|---|---|
| exact terms (40 questions) | 89% / 82% | 91% / 82% | +0%: no clear difference |
| general (149 questions) | 80% / 75% | 72% / 69% | −6% (−12 to 0) |
| all 189 questions | 82% / 77% | 76% / 72% | −5% (−10 to −1): worse |

The surprise is that **bge-small already handles exact terms well** (89% top-1 on TOIL, VPN,
E4 and so on, ahead of pure word matching at 83%), so keywords have little left to fix.
They did lift a few misses ("self-certify" from rank 9 to 3) but changed only one top
answer. So hybrid stays off. It would matter most for terms an embedding model has never
seen, like internal product codes, ticket IDs or in-house system names. If your documents
have those, add real questions about them as a slice, measure, and turn it on:

```powershell
# in .env
RETRIEVAL_HYBRID=true
```

Then restart: `docker compose --profile app up -d ai-service`.

Reproduce or extend with `python -m evals.retrieval.run --model <name> --reranker <name>
--hybrid --store postgres --show-misses`. CI runs the large set and fails if bge-small's
hit@1 over all 189 questions drops below 75% (currently 82%).

**Measuring answer quality.** Finding the right policy is half the job; the other half is
whether Claude's answer is right. `python -m evals.answers.run` sends the same 189 questions
through the real answer path (`AssistantService.ask`: retrieval, PII redaction, the
injection-safe prompt, Claude with the production model and effort) and grades every answer
on three separate properties:

| Metric | Passes when | Graded by |
|---|---|---|
| **correct** (headline) | If the right policy reached Claude, the answer gives what that policy says. If it didn't, the answer says the documents don't cover it instead of guessing. | LLM judge that reads the right policy's text |
| **grounded** | Every policy statement in the answer is backed by the documents Claude was shown. | LLM judge |
| **cited** | The answer cites a document as `[n]`, and every `[n]` was actually shown. | Code, free |

The judge is `claude-sonnet-5-5`, a different model from the one answering, so it doesn't
grade its own style. It returns structured JSON (reasoning, then pass/fail). It treats the
answer and the policies as untrusted data: both are escaped and fenced in tags. Each case is
tagged with what happened *before* Claude was called, so a failure is blamed on the right
stage. Under today's settings:

| Case kind | Questions | What a good answer does |
|---|---|---|
| answerable | 157 | answers from the right policy, which is in the context |
| retrieval-miss | 9 | says "not found" (the right policy exists but wasn't retrieved) |
| unanswerable | 17 | says "not found" (related policies passed the cut-off, but none answers) |
| no-llm-call | 6 | nothing passed the cut-off; the service says "not found" without calling Claude |

Each run writes to `.claude/hillclimb/answer-quality/<variant>/`:
- `results.jsonl`: one row per question, with grades, the judge's reasoning, served model,
  tokens, latency, refusal and fallback flags;
- `traces/`: the exact prompt and answer;
- `judge/`: the judge's inputs and outputs;
- `errors.jsonl`: calls that failed after retries. These are retried on the next run and
  never scored as wrong answers.

Rows are written as cases finish, so an interrupted run resumes where it stopped. The summary
prints each metric with a 95% CI, split by case kind, plus latency, tokens and cost.

It needs `ANTHROPIC_API_KEY` in `.env` and costs money, so it isn't part of CI (the plumbing
is covered by `tests/test_answer_eval.py` with fake models). First time:

```powershell
cd services/ai-service
python -m evals.answers.run --answerer oracle --approve-harness  # review evals/answers/ first
python -m evals.answers.run --answerer empty   # known-bad: should score ~0% correct
python -m evals.answers.run --answerer wrong   # confident but wrong: fails correct, passes grounded
python -m evals.answers.run --limit 5          # pilot with Claude; check cost per case
python -m evals.answers.run                    # baseline, all 189 questions
```

The three `--answerer` checks call only the judge. They show that the grading is sound
before you trust its numbers: the oracle (the right policy's own text, cited) should score
~100% correct. `--approve-harness` records a hash of the eval's code and dataset; when either
changes, the runner refuses to spend money until someone reviews the change and approves it
again. Compare a change against the baseline as a variant, for example
`--variant v1 --effort low` or `--variant v1 --model claude-sonnet-5-5`.
`--summary` reprints the numbers. For a browsable per-question report, any `/claude-api`
command in Claude Code extracts a report builder (`build-report-lite.mjs`) that reads this
folder. **No baseline numbers yet:** the eval was built without an API key. The first full
run records them here.

**Web app.** Requires Node.js 24. Vite serves the app with instant reload on the same port as
the Docker image (5280, Keycloak's registered redirect) and forwards `/api`, `/oauth2`,
`/login/oauth2` and `/logout` to the BFF on port 8090, so sign-in works exactly as in Docker.
Keep the rest of the stack running in Docker (`docker compose --profile app up -d`).

```powershell
docker compose stop web                  # frees port 5280 if the full stack is running
cd web
npm install
npm run dev                              # http://localhost:5280
npm test; npm run lint; npm run build
```

To reset all data (including the Keycloak realm, which re-imports on next start):
`docker compose down -v`.

## Security design

| Threat | Control |
|---|---|
| Forged or stolen-and-expired tokens | RS256 signature via Keycloak JWKS, `exp`/`nbf` with clock skew, 5-minute access tokens; refresh tokens never leave the BFF and only work with its client secret |
| Token meant for another app | Audience must be `hrms-api` |
| Cross-tenant data access | Tenant only from the signed token; Hibernate tenant filter; tenant + role filter inside the vector search; users can't edit their own `tenant_id` in Keycloak |
| Privilege escalation | Role checks on every endpoint; DTOs prevent mass assignment; unknown JSON fields rejected (AI service) |
| Approving your own leave | Separation of duties: the requester can never approve, the manager step belongs only to the assigned manager, HR can't skip it |
| Probing for other people's requests | Requests you may not see return 404, not 403, so their existence isn't revealed |
| Taking over someone's employee record | Accounts link to employees only by an email Keycloak has *verified* (an unverified or self-edited address never matches) |
| Race conditions | `@Version` optimistic locking on requests; `SELECT … FOR UPDATE` on the employee while checking the balance; a per-tenant Postgres advisory lock serialises reporting-line changes |
| Loops in the org chart | Every manager change walks the reporting chain and is refused if it would make someone their own (indirect) manager |
| One service compromised → whole DB | Separate DB login and schema per service; AI service re-verifies the user's token itself |
| Prompt injection via documents | Documents escaped and fenced as data; model has no tools; it only sees documents the user may read |
| Personal data sent to a third-party LLM | Regex PII redaction (email, phone, card with Luhn check, ID numbers) before every LLM call; embeddings are computed locally, so documents never go to an embedding API |
| Supply chain at runtime | The embedding model is baked into the image at build time and the container runs with `HF_HUB_OFFLINE=1`, so it downloads nothing while serving |
| AI cost abuse / scraping | Per-user Redis rate limit, 10 assistant calls/minute |
| Tampering with history | Audit events and leave history append-only, enforced by database triggers |
| Information leakage | No stack traces or SQL in responses; actuator on an internal port; CSP/no-referrer headers; ports bound to 127.0.0.1 |
| Stolen tokens in the browser | There are none: the BFF keeps access and refresh tokens in a server-side session (Redis). The browser holds an HttpOnly, SameSite=Lax session cookie that JavaScript can't read. Code flow with PKCE *and* a client secret |
| Cross-site request forgery | Every POST/PUT/DELETE through the BFF needs an `X-XSRF-TOKEN` header matching a cookie that only this origin can read (double-submit); sign-out is a POST too |
| Sessions outliving the sign-in | BFF sessions expire after 30 idle minutes, like Keycloak's; when Keycloak rejects a refresh (its session ended), the BFF ends its session too; sign-out ends both |
| Cross-site scripting (XSS) | React escapes output; LLM answers rendered as plain text, never HTML; CSP allows no inline or third-party scripts |
| Open redirect after login | Return path must be a same-site path (`safeReturnTo`) |
| Clickjacking | `frame-ancestors 'none'` |
| Vulnerable dependencies / leaked secrets | Trivy in CI, Dependabot, `.env` git-ignored, pinned image tags, non-root containers |

Known gaps, deliberately left as next steps:
- the dev-only password-grant client;
- no TLS locally (set `BFF_COOKIE_SECURE=true` wherever the app is served over HTTPS);
- regex-based PII detection;
- retrieval evaluated on synthetic (not real) policies;
- no OIDC back-channel logout: signing out of Keycloak elsewhere ends the BFF session only at
  its next token refresh, within 5 minutes;
- refresh-token rotation is off. The BFF is a confidential client, so a refresh token is
  useless without its secret, and with rotation on, parallel API calls that refresh at the
  same moment would sign the user out. Turning it back on needs a per-session refresh lock.

## Project layout

```
.
├── AGENTS.md                   # operating manual for coding agents (CLAUDE.md imports it)
├── docker-compose.yml          # local stack (profiles: default = infra, "app" = + services)
├── .env.example                # copy to .env; all local secrets
├── infra/
│   ├── keycloak/hrms-realm.json   # realm, roles, clients, demo users
│   └── postgres/init/01-init.sh   # per-service DB logins and schemas
├── scripts/
│   ├── get-token.ps1           # sign in a demo user from PowerShell
│   └── seed-demo.ps1           # demo employees, reporting lines, policy documents
├── web/                        # React 19 + TypeScript + Vite + Tailwind
│   ├── nginx.conf              # serves the app, proxies /api, security headers
│   └── src/
│       ├── auth/               # sign-in/out through the BFF, CSRF header, sign-in gate
│       ├── api/                # fetch wrapper, types, TanStack Query hooks
│       ├── components/         # layout, role gate, UI building blocks
│       ├── lib/                # date formatting, working-day count, org-chart helpers
│       └── pages/              # home, employees, leave (+ approvals), assistant
├── services/
│   ├── bff/                    # backend-for-frontend: Spring Cloud Gateway + OAuth2 client + Spring Session (Redis)
│   │   └── src/main/java/com/hrms/bff/
│   │       ├── config/         # Keycloak client registration, security rules (login, CSRF, logout)
│   │       └── gateway/        # /api/** route and the token relay
│   ├── core-service/           # Java 25 + Spring Boot 4 (Maven)
│   │   └── src/main/java/com/hrms/core/
│   │       ├── config/         # SecurityConfig, typed properties
│   │       ├── security/       # role mapping, current user, rate limiting
│   │       ├── tenant/         # Hibernate tenant resolver
│   │       ├── employee/       # entity, repository, service, controller, DTOs
│   │       ├── leave/          # leave requests: state machine, rules, approvals, history
│   │       ├── audit/          # append-only audit trail
│   │       ├── assistant/      # client for the AI service (token relay)
│   │       └── common/         # errors, paging
│   └── ai-service/             # Python 3.12 + FastAPI
│       ├── app/
│       │   ├── security.py     # JWT verification, roles
│       │   ├── llm.py          # Claude client
│       │   └── rag/            # chunking, embeddings, reranking, retrieval, pgvector store, PII, prompts
│       └── evals/
│           ├── retrieval/      # datasets (9 + 66 policies) + runner (comparisons, CIs, CI gate)
│           └── answers/        # answer-quality eval: real answer path + LLM judge
└── .github/                    # CI pipeline, Dependabot
```

## Roadmap

Good next milestones, roughly in order. Each one is a solid resume bullet.

1. ~~**React frontend** with the authorization-code + PKCE flow~~ ✅
2. ~~**Leave requests with an approval workflow** (employee → manager → HR)~~ ✅ and ~~**editable reporting lines**~~ ✅ Natural follow-ups: public-holiday calendars per tenant, and email notifications on each step.
3. ~~**Real embeddings**~~ ✅ ~~**Reranker**~~ ✅ ~~**Larger eval set with confidence intervals**~~ ✅ ~~**Hybrid search**~~ ✅ (reranker and hybrid are off by default, see above). ~~**Exact-terms eval slice**~~ ✅ ~~**Answer-quality eval**~~ ✅ (built; its first baseline run needs an API key). Next: questions from **real users** once there are some, and prompt-injection cases for the answer eval (policies that contain hostile instructions).
4. ~~**API gateway / backend-for-frontend** (Spring Cloud Gateway) that keeps tokens server-side in an HttpOnly session cookie~~ ✅ Natural follow-ups: OIDC back-channel logout, and gateway-level rate limiting.
5. **Kafka events** (`EmployeeCreated`, …) consumed by the AI service and a notification service.
6. **Postgres row-level security** as a second tenant-isolation layer under Hibernate's.
7. **End-to-end browser tests** (Playwright) for the sign-in and role flows, run in CI.
8. **OpenTelemetry tracing** across the services, with Grafana dashboards.
9. **Kubernetes manifests / Helm chart** and deployment to a cloud provider.

## Learning Java with this codebase

Read the Java service in this order. Each file introduces a few concepts:

| # | File | Java / Spring concepts |
|---|---|---|
| 1 | `CoreServiceApplication.java` | `main`, packages, annotations |
| 2 | `employee/EmployeeDtos.java` | records, Bean Validation annotations |
| 3 | `employee/Employee.java` | classes, encapsulation, constructors, JPA entities |
| 4 | `employee/EmployeeRepository.java` | interfaces, generics |
| 5 | `employee/EmployeeService.java` | dependency injection, `Optional`, lambdas, transactions |
| 6 | `employee/EmployeeController.java` | REST endpoints, `ResponseEntity`, method security |
| 7 | `security/KeycloakRealmRoleConverter.java` | streams, `instanceof` pattern matching |
| 8 | `config/SecurityConfig.java` | builder/lambda DSLs, beans, the security filter chain |
| 9 | `security/RateLimitFilter.java` | inheritance, servlet filters, exceptions, text blocks |
| 10 | `employee/EmployeeApiTests.java` | JUnit 5, integration testing, Testcontainers |
| 11 | `leave/LeaveStatus.java` | enums with behaviour, exhaustive `switch` expressions, a state machine |
| 12 | `leave/LeaveService.java` | business rules, `Optional`, transactions, locking, authorization by relationship |
| 13 | `leave/LeaveStatusTests.java` | parameterized tests (`@CsvSource`, `@EnumSource`) |

A good habit: change something (add a `phone` field to `Employee`, end to end: migration
`V4__…sql` (the next free number), entity, DTOs, test), run `.\mvnw.cmd verify`, and read the
errors until it's green. AGENTS.md has a learning guide covering every layer (security, BFF,
RAG, evals, web), a glossary, and more exercises.
