# Roadmap: MVP → Production

This is a staged plan to take the agent from "static-reviewed MVP" to something we can confidently run in production. Each phase has a clear goal, an ordered checklist, and exit criteria. Don't skip phases — each one depends on the previous.

Rough effort estimates assume one person working in focused sessions (not calendar time).

---

## Phase 0 — Make sure it actually works (½ day)

**Goal:** prove the MVP compiles, tests pass, and every feature runs end-to-end on real infrastructure. Nothing else is worth doing until this is green.

**Tasks**
- [ ] Install JDK 21, run `./bootstrap.sh`, then `./gradlew build`. Fix any compile errors.
- [ ] `./gradlew test` — all 6 test classes must pass.
- [ ] Smoke test with `AGENT_STORAGE_TYPE=memory` + real `ANTHROPIC_API_KEY`: create a session, ask it to read a file, verify the streamed response.
- [ ] Smoke test with `AGENT_STORAGE_TYPE=sqlite`: restart the app, confirm prior sessions rehydrate from `./data/agent.db`.
- [ ] Smoke test each provider you care about (GitHub Copilot / Anthropic / OpenAI / Ollama).
- [ ] Smoke test with `AGENT_AUTH_ENABLED=true`: browser login works, curl with `-u` works, no-creds returns 401.
- [ ] `docker compose up --build` — container starts, `/api/health` is green, a chat turn completes.
- [ ] Stop button in the UI actually kills an in-flight run.
- [ ] Open `/swagger-ui.html` — all endpoints appear and are callable.

**Exit criteria:** build is green, every feature demoed at least once, at least one deliberate failure (wrong API key, blocked shell command) behaves gracefully.

---

## Phase 1 — Safety (3–5 days) ✅ **landed**

Design doc: [PHASE_1_DESIGN.md](./PHASE_1_DESIGN.md)

**Goal:** the agent can't burn through your API budget, take down the host, or expose data to the wrong user. This is the "don't ship without" phase.

### 1.1 Contain the shell tool ✅ (1 day)
The block-list regex is not a real defence. Pick one of:
- **Recommended:** run the whole app inside Docker with `--read-only --network=none --tmpfs /tmp` and bind-mount only the workspace. The shell tool then has no way to touch anything else on the host.
- Alternatively, spawn a short-lived sidecar container per shell call using the Docker API.
- Document the threat model explicitly in `SECURITY.md` — what attacks are in-scope vs out-of-scope.

### 1.2 Resource limits ✅ (1 day)
- Replace `SimpleAsyncTaskExecutor` in `AgentController` with a bounded `ThreadPoolTaskExecutor` (e.g. 8 threads, 32 queue, caller-runs policy).
- Cap agent loop cost: add `agent.llm.max-tokens-per-session` (default 200k) and `agent.llm.max-turns-per-request` (default 10). Bail with a clear message when exceeded.
- Truncate tool outputs to a configurable max (e.g. 16 KB) with an explicit "... truncated" marker.
- Trim long sessions before sending to the LLM — either last-N-messages window or a summarisation pass. Start with simple windowing.

### 1.3 Rate limiting ✅ (½ day)
- Add Bucket4j (`com.bucket4j:bucket4j-core`) and a simple per-principal rate-limit filter: e.g. 30 requests/min for `/api/chat*`, 600/min for everything else.
- Return `429` with a `Retry-After` header.

### 1.4 Per-user session ownership ✅ (1 day)
- Add `user_id` column on `agent_sessions`. Populate from the authenticated principal.
- Filter `list()` and `get()` by owner. 403 on cross-user access.
- Update `SecurityConfigTest` to cover cross-tenant isolation.

### 1.5 Error hygiene ✅ (½ day)
- Global `@ControllerAdvice` that maps exceptions to sanitised JSON (no stack traces, no internal paths).
- Distinct handlers for `AccessDeniedException`, `AuthenticationException`, client errors, server errors.

**Exit criteria:** shell tool is sandboxed; malicious/runaway agent runs are bounded; rate limit demonstrable with a tight loop of requests; two users in auth mode can't see each other's sessions.

---

## Phase 2 — Operability (2–3 days) ✅ **landed**

**Goal:** when something goes wrong at 3am, you can find out why without SSH.

### 2.1 Structured logging ✅ (½ day)
- `logback-spring.xml` with `net.logstash.logback.encoder.LogstashEncoder` for JSON output in prod profile, pretty output in dev.
- MDC request filter stamping every log with `requestId`, `sessionId`, `userId`.
- Echo `requestId` in response headers for correlation.

### 2.2 Metrics ✅ (½ day)
- Add `spring-boot-starter-actuator` and `micrometer-registry-prometheus`.
- Expose `/actuator/prometheus`.
- Custom counters: `llm_calls_total{provider,outcome}`, `llm_tokens_total{provider,kind}`, `tool_calls_total{tool,outcome}`, `sse_streams_active`.

### 2.3 Health + readiness ✅ (¼ day)
- Actuator health with custom indicators: DB reachable (when SQLite), LLM provider reachable (cheap HEAD/GET).
- Split `/actuator/health/liveness` and `/actuator/health/readiness`; point K8s probes at the appropriate ones.

### 2.4 Database migrations ✅ (landed with Phase 1.4)
- Replace `ddl-auto=update` with `validate`.
- Add Flyway; initial migration `V1__initial_schema.sql` matching current schema.
- Document the migration workflow in README.

### 2.5 Audit log ✅ (½ day)
- New `audit_events` table: timestamp, userId, sessionId, type (`tool_call` / `llm_call`), payload JSON.
- Write from a single `AuditLogger` service called from `ToolRegistry.invoke` and around `LlmProvider.complete`.
- Expose `GET /api/sessions/{id}/audit` (admin-only).

### 2.6 Graceful shutdown ✅ (¼ day)
- `server.shutdown=graceful`, `spring.lifecycle.timeout-per-shutdown-phase=30s`.
- SSE emitters: track active streams, call `complete()` on shutdown.

### 2.7 CI ✅ (½ day)
- `.github/workflows/build.yml`: JDK 21, cache Gradle, run `./gradlew build`.
- Matrix across JDK 21 and 22.
- Upload test reports as artifacts.

**Exit criteria:** a 500 error produces a structured log line with a request ID you can copy into a search; `/actuator/prometheus` shows non-zero counters after a chat; CI is green on `main`.

---

## Phase 3 — Scale (3–5 days, only if needed)

**Goal:** you can run more than one instance, behind a load balancer, for real users.

### 3.1 Postgres support ✅ (½ day)
- Add `org.postgresql:postgresql` and a Postgres profile.
- `agent.storage.type=postgres` alongside `sqlite` / `memory`.
- Flyway migrations work for both (SQLite and Postgres syntax diverges — keep migrations portable or split).

### 3.2 OIDC auth ✅ (1–2 days)
- Replace Basic with OAuth2 login via `spring-boot-starter-oauth2-client` + `spring-boot-starter-oauth2-resource-server`. ✅
- Support Google / GitHub / generic issuer via config. ✅ (partially — see follow-up below)
- Keep Basic as a fallback for service-to-service via API keys. ⚠️ follow-up

**Carve-outs deferred to follow-ups:**
- `SecurityConfig.registeredProviders()` hardcodes the trio `github` / `google` / `okta`. A generic issuer registered via `spring.security.oauth2.client.registration.<id>.*` works at the Spring level but won't appear in startup logs and isn't enumerable from config. Small additive fix.
- Service-to-service API-key fallback (`X-Api-Key` header, hashed storage, rotation policy, principal mapping) is not implemented. Underspecified — needs a security-design decision before building.

### 3.3 Multi-instance safety ✅ (landed)
- Decision: keep SSE; require sticky sessions at the LB. WebSockets considered and rejected — see [docs/adr/0002-multi-instance-sse.md](./docs/adr/0002-multi-instance-sse.md).
- Session store is already DB-backed; `JpaSessionStore.appendMessage` is per-message atomic so a pod death mid-turn only loses the unsaved tail of the in-flight assistant response.
- Heartbeat pings on SSE streams (comment frames) via `agent.sse.heartbeat-interval`, default 15s. Exported as `sse_heartbeats_sent_total{outcome}`.
- Sticky-session requirement + `AGENT_STORAGE_TYPE=postgres` requirement documented in README ("Running multiple replicas").

### 3.4 Secrets management (½ day)
- Integrate with the secret store you use (AWS Secrets Manager, HashiCorp Vault, GCP Secret Manager).
- Rotate API keys at startup or on SIGHUP.
- Remove plaintext secrets from env var docs.

### 3.5 Deployment template (½ day)
- Kubernetes manifests or Helm chart in `deploy/`.
- HPA on request rate / CPU.
- NetworkPolicy: the agent pod can only egress to the LLM provider hostnames.

**Exit criteria:** two replicas behind a load balancer handle streaming without breaking; OIDC login works with at least one real identity provider; a secret rotation doesn't require an app restart.

---

## Phase 4 — Polish (ongoing)

**Goal:** the product gets better. Pick what matters for your use case.

- **Provider streaming:** upgrade `LlmProvider.complete` to a streaming variant so the UI shows tokens appearing one-by-one (not just per-message).
- **Wire tests per provider:** record a real response with `curl`, save to `src/test/resources/fixtures/`, test `AnthropicProvider` end-to-end with `MockWebServer`.
- **Planning mode:** agent proposes a list of tool calls; user approves before execution. Great for dangerous tools (`shell`, `git`).
- **Cost dashboard:** per-session and per-user token/cost breakdowns.
- **Better markdown:** drop the inline renderer, switch to `marked.js` + `highlight.js` for proper syntax highlighting.
- **Session search:** full-text index over messages.
- **Multi-workspace:** let users register multiple workspaces per session.
- **Prompt improvements:** system prompt tuning, few-shot examples, tool descriptions polish — measurably improves agent quality.
- **Import/export sessions** as Markdown transcripts.
- **Web search tool / HTTP fetch tool** for agents that need to browse docs.
- **MCP support** — expose the tools via the Model Context Protocol so other agents can use them.
- **Agent Client Protocol** — speak ACP so Penstock runs as a selectable agent inside JetBrains Air and Zed — see [#59](https://github.com/enrichmeai/penstock/issues/59).

---

## Working agreement

- Each phase lands on its own branch and PR. No mixing concerns.
- Every feature comes with tests and a README update.
- `ROADMAP.md` is the source of truth — check boxes as we go.
- Phase 0 is non-negotiable before anything else.
- If a phase item turns out to be much bigger than estimated, we split it rather than letting it swallow the phase.
