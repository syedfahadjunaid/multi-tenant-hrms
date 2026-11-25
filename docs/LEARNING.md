# Learning guide

This codebase doubles as learning material. Read it in this order; each step builds on the
one before. For the Java core service there is also a file-by-file reading list in
[README.md](../README.md#learning-java-with-this-codebase).

| # | Concept | What to understand | Read |
|---|---|---|---|
| 1 | The big picture | Which service does what, and how a request travels | `README.md` diagram, `AGENTS.md` ("Request flow") |
| 2 | Spring Boot basics | Beans, dependency injection, configuration records, profiles | `CoreServiceApplication.java`, `HrmsProperties.java`, `application.yml` |
| 3 | REST + validation | Controllers, DTO records, Bean Validation, RFC 9457 error bodies | `EmployeeController.java`, `EmployeeDtos.java`, `GlobalExceptionHandler.java` |
| 4 | JPA / Hibernate | Entities, repositories, transactions, why `open-in-view` is off | `Employee.java`, `EmployeeRepository.java`, `EmployeeService.java` |
| 5 | Schema migrations | Flyway versions, constraints, triggers, composite foreign keys | `src/main/resources/db/migration/V1..V3` |
| 6 | Multi-tenancy | One database, many companies: `@TenantId`, tenant from the token only | `TenantIdentifierResolver.java`, `CurrentUser.java` |
| 7 | OAuth2 resource server | JWT signature/issuer/audience checks, mapping Keycloak roles | core `SecurityConfig.java`, `KeycloakRealmRoleConverter.java` |
| 8 | Authorization | Role checks (`@PreAuthorize`) vs relationship checks; 404 vs 403 vs 409 vs 422 | `LeaveService.java`, `LeaveController.java` |
| 9 | State machines | An enum with behaviour and an exhaustive `switch` | `LeaveStatus.java`, `LeaveStatusTests.java` |
| 10 | Concurrency | Optimistic (`@Version`), pessimistic (`FOR UPDATE`) and advisory locks: when to use which | `LeaveRequest.java`, `EmployeeRepository.java`, `EmployeeService.java` |
| 11 | Domain events + audit | Events in the same transaction; append-only audit with `Propagation.MANDATORY` | `ManagerChanged.java`, `AuditService.java` |
| 12 | Rate limiting | A Redis fixed window in a servlet filter | `RateLimitFilter.java` |
| 13 | Testing Java | Testcontainers, MockMvc with `jwt()`, a fresh tenant per test | `EmployeeApiTests.java`, `LeaveApiTests.java`, `TestUsers.java` |
| 14 | OpenID Connect sign-in | Authorization code + PKCE, confidential vs public clients, ID / access / refresh tokens | bff `KeycloakClientConfig.java`, `infra/keycloak/hrms-realm.json` |
| 15 | Backend-for-frontend | Server-side sessions in Redis, HttpOnly + SameSite cookies, CSRF double-submit, RP-initiated logout | bff `SecurityConfig.java`, `web/src/auth/session.ts` |
| 16 | API gateway | Spring Cloud Gateway routes and filters, token relay, failing closed | `ApiRoutes.java`, `TokenRelay.java` |
| 17 | Testing over real HTTP | Fake identity provider and fake backend on the JDK's HTTP server | `BffFlowTests.java`, `FakeKeycloak.java` |
| 18 | Service-to-service trust | Relaying the user's token; the next service verifies it again | `AiClient.java`, `app/security.py` |
| 19 | RAG basics | Chunking, embeddings, cosine similarity, pgvector, the "not found" cut-off | `chunking.py`, `embeddings.py`, `store.py`, `retrieval.py` |
| 20 | Permission-aware retrieval | Filters inside the SQL, never after it | `PgVectorStore.search` and `keyword_search` in `store.py` |
| 21 | Better retrieval | Postgres full-text search, reciprocal rank fusion, cross-encoder reranking | `store.py`, `retrieval.py`, `rerank.py` |
| 22 | LLM integration | Anthropic SDK, `effort`, refusal fallback, `stop_reason`, offline mode | `app/llm.py`, `app/rag/service.py` |
| 23 | AI safety | Prompt-injection fencing, PII redaction | `prompts.py`, `pii.py` |
| 24 | Evaluating AI | hit@1, MRR, held-out accuracy, bootstrap CIs, paired comparisons; LLM-as-judge | `evals/retrieval/run.py`, `evals/answers/` |
| 25 | React frontend | TanStack Query hooks, React Router, typed API client, Testing Library | `web/src/api/`, `web/src/pages/` |
| 26 | Web security | CSP, nginx as the single origin, open-redirect guard | `web/nginx.conf`, `session.ts` |
| 27 | Containers + CI | Multi-stage non-root images, layered jars, Compose profiles and health checks, GitHub Actions, Trivy | `Dockerfile`s, `docker-compose.yml`, `.github/` |

## Glossary

- **OIDC (OpenID Connect):** sign-in built on OAuth2. Keycloak issues an *ID token* (who
  you are), an *access token* (what you may call, 5 minutes) and a *refresh token* (gets new
  access tokens).
- **PKCE:** a one-time secret the client proves it holds when exchanging the login code, so
  a stolen code is useless.
- **Confidential client:** a client that can keep a secret (a server, like the BFF), unlike
  a browser app.
- **BFF (backend-for-frontend):** a server that holds the tokens for the browser and gives
  it only a session cookie.
- **CSRF:** another site making your browser send a request with your cookies. Blocked here
  by requiring a header that only this origin can set.
- **JWT / JWKS:** a signed token format, and the endpoint publishing the keys that check
  the signature.
- **Audience (`aud`):** which API a token is meant for (`hrms-api`).
- **Tenant:** one customer company. All of its data carries its `tenant_id`.
- **RBAC:** role-based access control (HR_ADMIN, MANAGER, EMPLOYEE).
- **Optimistic lock:** detect a conflicting update afterwards with a version number.
- **Pessimistic lock:** block others while you work (`SELECT … FOR UPDATE`).
- **Advisory lock:** a named lock in Postgres that isn't tied to a row.
- **Flyway:** versioned SQL migrations, applied in order, never edited.
- **Testcontainers:** real databases in throwaway Docker containers during tests.
- **ProblemDetail (RFC 9457):** a standard JSON error body.
- **RAG (retrieval-augmented generation):** find relevant passages, then let the LLM answer
  only from them.
- **Embedding:** a vector capturing a text's meaning; similar texts get nearby vectors.
- **pgvector / HNSW:** vectors in Postgres, and the index that searches them quickly.
- **RRF:** reciprocal rank fusion, which merges two rankings.
- **Cross-encoder:** a model that scores a question and a passage together; more accurate
  and slower.
- **hit@1 / MRR:** whether the right document is ranked first / mean reciprocal rank.
- **Held-out accuracy:** a score on questions not used for tuning.
- **Bootstrap CI:** a confidence interval from resampling the questions.
- **LLM-as-judge:** a second model grading answers against a rubric.

## Exercises

Each exercise touches several layers; run the checks under "Run and verify" in
[AGENTS.md](../AGENTS.md) until green:

1. Add a `phone` field to employees end to end: migration `V4__employee_phone.sql`, entity,
   DTOs with validation, an API test, and the web form.
2. Build the public-holiday calendar from "Left to build": a new tenant-scoped table with a
   composite foreign key, HR-only endpoints, `WorkingDays` changes, and tests.
3. Send an email when a leave request is approved, from an `AFTER_COMMIT` event listener,
   with a test that uses a fake mail sender.
4. In the BFF, add a test that a `PUT` without the CSRF header is refused. Then add
   per-session rate limiting and test it in `BffFlowTests`.
5. Add a new retrieval-eval slice (for example questions with typos). Write the decision
   rule down first, run `python -m evals.retrieval.run`, and record the result.
