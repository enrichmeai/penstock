# Penstock

*Formerly "AI Coding Agent".* A penstock carries water under pressure to the
turbine — it delivers work to where the work gets done.

**Your models, beyond the IDE.** A self-hosted, centrally-governed agent that runs
GitHub Copilot (or Claude / OpenAI / Ollama) against the workflows your IDE can't
reach — CI hooks, Slack commands, scheduled triage jobs, and custom tools that talk
to your internal systems (Jira, runbooks, observability). Built in **Spring Boot 3 +
Gradle + Java 21** with auth, rate limiting, audit logging, and Prometheus metrics
on by default.

If your developers already have Copilot in their IDE and you want **the same models,
the same contract, running the autonomous workflows no editor can host** — this is
what you run.

**Three surfaces, one backend**: a web UI for developers, a REST API for automation
(CI, Slack, bots, cron), and SSE streaming for real-time interactive work. Built-in
tools cover file I/O, code search, shell, and git. Adding a tool for your internal
systems (Jira, PagerDuty, your runbook repo) is 30–50 lines of Java.

See [`examples/`](./examples/) for ready-to-deploy GitHub Actions, Slack webhook,
and Jira integration samples.

## Default path: GitHub Copilot API

Out of the box the agent speaks to your org's Copilot API (`copilot` provider).
Set one environment variable and you're done:

```bash
export GITHUB_COPILOT_TOKEN=<github-token-with-copilot-scope>
# Optional — override for enterprise endpoints like GitHub Models
export COPILOT_BASE_URL=https://api.githubcopilot.com
export COPILOT_MODEL=gpt-4o          # or claude-3-5-sonnet, o1-mini, etc.
./gradlew bootRun
```

No new vendor contract, no new spend category — the same quota that powers your
developers' IDE. Anthropic, OpenAI, and Ollama remain available as fallbacks; flip
`AGENT_LLM_PROVIDER` to change.

## Beyond the IDE: three example integrations

Ready-to-deploy demos that show the agent operating where Copilot-for-IDE cannot:

- **[CI nightly triage](./examples/ci-nightly-triage/)** — a GitHub Actions
  workflow that calls the agent's REST API at 2am, asks it to diagnose failing
  tests, and opens a draft issue with the root cause. Zero human in the loop.
- **[Slack slash command](./examples/slack-webhook/)** — a minimal Python
  handler that wires `/agent <prompt>` in Slack to the agent, with Slack
  signature verification and the 3-second async response pattern.
- **[JiraTool](./src/main/java/com/example/agent/tools/JiraTool.java)** — a
  reference `Tool` implementation showing how to give the agent access to your
  internal systems. Copy this pattern for your runbook repo, PagerDuty,
  observability stack, anything. 30–50 lines per tool.

This is the shape of value Copilot-for-IDE structurally can't deliver: agent
workflows that run without a developer at a keyboard, and tools that talk to
your own systems.

## Features

- **Pluggable LLM layer**: `copilot` (default), `anthropic`, `openai`, `ollama` — switch via config. Automatic retry with exponential backoff on 429 / 5xx, respecting `Retry-After`.
- **Tool use loop**: the agent calls tools, receives results, and iterates until done.
- **Eight built-in tools**: `read_file`, `write_file`, `edit_file`, `list_dir`, `glob`, `grep`, `shell`, `git`.
- **Streaming responses** over Server-Sent Events — assistant turns, tool calls, and tool results stream to the UI as they happen.
- **Token usage tracking** — per-call usage is accumulated per session and exposed via the API and UI.
- **Pluggable persistence**: in-memory (default) or SQLite via JPA/Hibernate. Switch with one env var.
- **Optional HTTP Basic auth** in front of the API and UI.
- **OpenAPI / Swagger UI** at `/swagger-ui.html` for easy API exploration — behind auth when auth is enabled, since the document describes every endpoint and schema.
- **Explicit CORS** configuration, customisable for production.
- **Docker** + `docker-compose.yml` with a persistent SQLite volume and a bind-mounted workspace.
- **Sandboxed workspace**: every file/shell path resolves relative to `agent.workspace` and path traversal is blocked.
- **Polished web UI**: markdown rendering for assistant responses, syntax-preserving code blocks with copy buttons, a Stop button to abort in-flight runs, live token counters.
- **Comprehensive tests**: unit, JPA slice (H2), SSE streaming, and security (auth on/off).

### Phase 2 operability

- **Structured logging** — `logback-spring.xml` emits plain text by default; activate `-Dspring.profiles.active=prod` for JSON via `logstash-logback-encoder`. Every log line carries `requestId`, `userId`, and (where applicable) `sessionId` in MDC.
- **Request IDs** — the `RequestIdFilter` reads `X-Request-Id` or generates one, stamps MDC, and echoes the ID as a response header so clients can correlate.
- **Metrics** — `/actuator/prometheus` exposes `llm_calls_total{provider,outcome}`, `llm_tokens_total{provider,kind}`, `tool_calls_total{tool,outcome=ok|refused|error}`, `tool_call_duration_ms{tool}`, and `sse_streams_active` gauge. **Requires authentication when auth is enabled** — the labels carry tool names, providers and token counts. If your scraper cannot authenticate, set `AGENT_METRICS_PUBLIC_SCRAPE=true` and restrict the port at the network; `/actuator/health/**` stays open either way for load balancers.
- **Readiness vs liveness** — `/actuator/health/liveness` stays up while an LLM provider is misconfigured; `/actuator/health/readiness` goes DOWN so load balancers pull the instance out of rotation. Custom `LlmProviderHealthIndicator` does a config-only check (no external calls).
- **Audit log** — every LLM call and tool invocation is persisted to `audit_events` in the database-backed storage modes (SQLite and Postgres). Owner-scoped read at `GET /api/sessions/{id}/audit`. A `tool_call` row's `detail.outcome` is `OK`, `REFUSED` or `ERROR` (`detail.ok` is kept for older readers and is true only for `OK`), so a refused read never looks like a granted one — the sequence is shown for real in [docs/consent-demo.md](docs/consent-demo.md).
- **Graceful SSE shutdown** — on `ContextClosedEvent` the `SseEmitterRegistry` sends a terminating `shutdown` event and completes every in-flight emitter so clients don't see socket resets.
- **CI** — `.github/workflows/build.yml` caches Gradle, auto-bootstraps the wrapper, runs `./gradlew build`, uploads test reports and the built jar.

### Phase 1 safety (see [ROADMAP.md](./ROADMAP.md) and [SECURITY.md](./SECURITY.md))

- **Shell sandbox**: optional allow-list (`agent.tools.shell.allowed-commands`), kill switch (`AGENT_TOOLS_SHELL_ENABLED=false`), expanded block-list, Docker hardening flags (`read_only`, `cap_drop`, `pids_limit`, `no-new-privileges`).
- **Resource limits**: bounded SSE thread pool, per-request + per-session token budgets, tool-output truncation, last-N context windowing. Defaults are 25 turns and 150k tokens per request (`AGENT_LLM_MAX_TURNS_PER_REQUEST`, `AGENT_LLM_MAX_TOKENS_PER_REQUEST`), measured against a real repository — see below.
- **Rate limiting**: Bucket4j filter with separate limits for `/api/chat*` vs `/api/**`; keyed by principal-else-IP; `429 Retry-After` responses.
- **Per-user session ownership**: every session is stamped with the authenticated user (or `anonymous` when auth is off). Cross-user access returns 404, never leaking existence.
- **Error hygiene**: global `@RestControllerAdvice` returns stable `ApiError` JSON with request IDs; messages are sanitised unless the exception is marked `@SafeMessage`.
- **Flyway migrations** replace `ddl-auto=update` when `agent.storage.type=sqlite`.

## Quick start

### 1. Prerequisites

- JDK 21 (`java -version` should report 21)
- Either `gradle` installed globally, OR run `./bootstrap.sh` to download the wrapper jar.

### 2. Bootstrap the Gradle wrapper (first time only)

```bash
./bootstrap.sh
# or, if you have gradle installed:
gradle wrapper --gradle-version 8.10.2
```

### 3. Configure and run

```bash
# Pick one:
export GITHUB_COPILOT_TOKEN=ghp-...                # default provider (copilot)
# or: export ANTHROPIC_API_KEY=sk-ant-... AGENT_LLM_PROVIDER=anthropic
# or: export OPENAI_API_KEY=sk-...      AGENT_LLM_PROVIDER=openai
# or: export AGENT_LLM_PROVIDER=ollama                # local Ollama on :11434

export AGENT_WORKSPACE=/absolute/path/to/project   # the folder the agent can touch

./gradlew bootRun
```

Open **http://localhost:8080** and start chatting.

## Running with Docker

### From the published image (no checkout needed)

Every [release](https://github.com/enrichmeai/penstock/releases) publishes a
multi-arch (amd64 + arm64) image to GHCR, smoke-tested and shipped with an SBOM
and a vulnerability report:

```bash
docker run -p 8080:8080 \
  -e GITHUB_COPILOT_TOKEN=... \
  -v /abs/path/to/project:/workspace \
  ghcr.io/enrichmeai/penstock:latest
```

Swap the token for `AGENT_LLM_PROVIDER=anthropic` + `ANTHROPIC_API_KEY=...` (or
`openai` / `ollama`) as needed. The published image requires login **by
default**: with no `AGENT_AUTH_PASSWORD` set, a random password is generated
and printed once in the container log (`docker logs <container>`). Set
`AGENT_AUTH_PASSWORD` for a stable one, or opt out explicitly for local
experiments with `-e AGENT_AUTH_ENABLED=false` — an agent that executes shell
commands should not sit on a port unauthenticated.

### From a checkout (compose, fully offline)

```bash
export AGENT_WORKSPACE_HOST=/abs/path/to/project    # bind-mounted into /workspace

docker compose up -d --build
./scripts/demo-offline.sh                           # proves the whole loop works
```

Building the image requires BuildKit (the default since Docker 23). On older
engines, prefix builds with `DOCKER_BUILDKIT=1`.

Out of the box this needs **no API key and no internet** beyond a one-time model
download: compose also starts an Ollama container and pulls `llama3.2:3b` into a
volume. Open **http://localhost:8090**. Full walkthrough, model-choice guidance
and a faster GPU variant: [docs/offline-docker-compose.md](docs/offline-docker-compose.md).

To use a hosted provider instead, set `AGENT_LLM_PROVIDER` and the matching key
(e.g. `AGENT_LLM_PROVIDER=anthropic ANTHROPIC_API_KEY=sk-ant-...`).

The compose file:
- Builds the jar in a Gradle/JDK 21 stage, runs it on a slim Temurin JRE image.
- Runs `ollama` + a one-shot `ollama-pull`, reachable at `http://ollama:11434`.
- Keeps the SQLite DB on the `agent-data` volume (not a bind mount — see the doc).
- Bind-mounts your chosen workspace into `/workspace`.
- Adds `host.docker.internal` so the container can reach a host-native Ollama.
- Exposes a `HEALTHCHECK` that curls `/api/health`.

## Storage

Two backends, selected at startup:

| `AGENT_STORAGE_TYPE` | What it does |
| --- | --- |
| `memory` (default) | In-process `ConcurrentHashMap`. JPA auto-config is excluded so no DB file is created. |
| `sqlite` | SQLite via Spring Data JPA + Hibernate, schema auto-created. File path set by `AGENT_SQLITE_PATH` (default `./data/agent.db`). |

Messages are stored as JSON blobs in an `agent_messages` table so tool calls / results round-trip without schema changes.

## Streaming

`POST /api/chat/stream` returns a Server-Sent Events stream:

```
event: session    data: { "sessionId": "...", "title": "..." }
event: message    data: { "role":"ASSISTANT","text":"…","toolCalls":[…], … }
event: message    data: { "role":"TOOL","toolResults":[…] }
…
event: done       data: { "ok": true }
```

The web UI consumes this with `fetch()` + `ReadableStream` (EventSource can't POST).
The non-streaming `POST /api/chat` remains available for simple clients.

### Heartbeats and long-running turns

LLM calls and shell-tool invocations regularly exceed the 30–60 second idle
timeout that most cloud load balancers and corporate proxies impose on TCP
connections. To prevent those intermediaries from dropping live streams the
server writes an SSE comment-frame heartbeat to every active emitter on a
configurable interval (default 15 seconds):

```yaml
agent:
  sse:
    heartbeat-interval: 15s     # AGENT_SSE_HEARTBEAT_INTERVAL; set 0 to disable
```

Comment frames are part of the SSE spec — clients consume them transparently
without surfacing an event. Heartbeat activity is exported as
`sse_heartbeats_sent_total{outcome=ok|error}` on `/actuator/prometheus`.

## Running multiple replicas

Single-instance is the default and works fine for small teams. To run two or
more replicas behind a load balancer:

- **Storage must be Postgres** (`AGENT_STORAGE_TYPE=postgres`). SQLite is
  single-writer and pod-local; running two replicas against the same SQLite
  file (whether NFS-mounted or bind-mounted) causes silent corruption.
- **Enable sticky sessions on the load balancer.** The chat-streaming
  endpoint returns a long-lived `SseEmitter` bound to the JVM that handled the
  initial request; without affinity, a mid-turn re-route lands on a fresh
  emitter and the in-flight turn on the original pod becomes orphaned. Cookie
  affinity is preferred; source-IP hash is the fallback.
- **Keep heartbeats enabled** (they are by default). They mask short proxy
  flaps and let clients reconnect cleanly when a pod is drained.

The graceful-shutdown path in `SseEmitterRegistry` emits a `shutdown` event
and completes every in-flight emitter on `SIGTERM`, so rolling deploys
finish their current turn rather than presenting a socket reset to the user.
See [`docs/adr/0002-multi-instance-sse.md`](./docs/adr/0002-multi-instance-sse.md)
for the design rationale.

## Authentication

Off by default — enable with:

```bash
export AGENT_AUTH_ENABLED=true
export AGENT_AUTH_USERNAME=admin
export AGENT_AUTH_PASSWORD=a-real-secret
```

When enabled, all endpoints except `/api/health` require HTTP Basic. The browser
will prompt the user on first load; once authenticated, credentials are sent
automatically on every request (including SSE).

```bash
curl -u admin:a-real-secret http://localhost:8080/api/tools
```

## Configuration reference

All settings live in `src/main/resources/application.yml` under `agent.*` and can be
overridden with env vars or `--agent.*=value` command-line flags.

| Property | Default | Env var | Notes |
| --- | --- | --- | --- |
| `agent.workspace` | `${user.dir}/agent-workspace` | `AGENT_WORKSPACE` | Folder the agent is restricted to. |
| `agent.storage.type` | `memory` | `AGENT_STORAGE_TYPE` | `memory` or `sqlite`. |
| `agent.storage.sqlite-path` | `./data/agent.db` | `AGENT_SQLITE_PATH` | SQLite DB file. |
| `agent.auth.enabled` | `true` | `AGENT_AUTH_ENABLED` | Auth is on by default — a shell-executing agent never ships open. Set `false` explicitly for local experiments; the compose dev/demo stack does. |
| `agent.auth.username` | `admin` | `AGENT_AUTH_USERNAME` | |
| `agent.auth.password` | *(generated at startup if unset)* | `AGENT_AUTH_PASSWORD` | Unset (or the old `change-me`) → a random password is generated and logged once. |
| `agent.cors.allowed-origins` | *(none — cross-origin locked)* | `AGENT_CORS_ALLOWED_ORIGINS` | Comma-separated origins granted CORS with credentials; `*` allows any origin without credentials. The bundled UI is same-origin and needs none. |
| `agent.llm.provider` | `copilot` | `AGENT_LLM_PROVIDER` | `copilot` \| `anthropic` \| `openai` \| `ollama`. |
| `agent.llm.max-iterations` | `25` | — | Safety cap on the tool-use loop per turn. |
| `agent.llm.copilot.api-key` | — | `GITHUB_COPILOT_TOKEN` / `GITHUB_TOKEN` | GitHub Copilot / GitHub Models token. |
| `agent.llm.copilot.base-url` | `https://api.githubcopilot.com` | `COPILOT_BASE_URL` | Override for enterprise gateways. |
| `agent.llm.copilot.model` | `gpt-4o` | `COPILOT_MODEL` | Any Copilot-exposed model (gpt-4o, claude-3-5-sonnet, o1-mini, …). |
| `agent.llm.anthropic.api-key` | — | `ANTHROPIC_API_KEY` | |
| `agent.llm.openai.api-key` | — | `OPENAI_API_KEY` | Service key. A per-user key (below) takes precedence for that user. |
| `agent.llm.openai.base-url` | `https://api.openai.com` | — | Origin only; `/v1/chat/completions` is appended. A LiteLLM gateway is `http://litellm:4000`. |
| `agent.credentials.per-user.openai.<userId>` | — | `AGENT_CREDENTIALS_PERUSER_OPENAI_<USERID>` | Gateway key for one user. The env form lower-cases the user id; see [Acting as the signed-in user](#acting-as-the-signed-in-user). |
| `agent.tools.cistern.base-url` | — | `CISTERN_BASE_URL` | The pod. Blank disables the `pod` tool. |
| `agent.tools.cistern.token` | — | `CISTERN_AGENT_TOKEN` | The agent's own pod credential. |
| `agent.tools.cistern.credential-mode` | `service` | `AGENT_TOOLS_CISTERN_CREDENTIALMODE` | `service` \| `per-user` \| `forward` — whose credential the pod sees. |
| `agent.credentials.per-user.cistern.<userId>` | — | `AGENT_CREDENTIALS_PERUSER_CISTERN_<USERID>` | Pod credential for one user, used in `per-user` mode. |
| `agent.llm.ollama.base-url` | `http://localhost:11434` | `OLLAMA_BASE_URL` | |
| `agent.tools.shell.enabled` | `true` | — | |
| `agent.tools.shell.timeout-seconds` | `60` | — | Per-command timeout. |
| `agent.tools.file.max-bytes` | `1048576` | — | Max size for a single `read_file`. |

## REST API

| Verb | Path | Body / Params | Description |
| --- | --- | --- | --- |
| `GET` | `/api/health` | — | Liveness + provider + tool list (always unauthenticated) |
| `GET` | `/api/tools` | — | JSON-Schema of every registered tool |
| `POST` | `/api/chat` | `{ "sessionId"?, "message" }` | Non-streaming chat. |
| `POST` | `/api/chat/stream` | `{ "sessionId"?, "message" }` | SSE stream of messages as they're produced. |
| `GET` | `/api/sessions` | — | List sessions |
| `POST` | `/api/sessions` | — | Create a blank session |
| `GET` | `/api/sessions/{id}` | — | Full message history |
| `GET` | `/api/sessions/{id}/usage` | — | Token usage summary for a session |
| `DELETE` | `/api/sessions/{id}` | — | Delete a session |

Interactive API docs at **`/swagger-ui.html`** when the app is running.

### Tool outcomes

A `TOOL` message's `toolResults[]` (in `POST /api/chat` responses, `GET /api/sessions/{id}`
history and SSE `message` events) carries how each call ended:

```json
{ "callId": "call_1", "content": "Refused: the owner has not granted access to …",
  "outcome": "REFUSED", "isError": false }
```

| `outcome` | Meaning | `isError` |
| --- | --- | --- |
| `OK` | the tool did what was asked | `false` |
| `REFUSED` | the far system said no — the pod owner's rule, a permission the caller lacks. An answer, not a malfunction: the model is told to report it and not to try another route | `false` |
| `ERROR` | the tool could not do what was asked: bad arguments, an unreachable system, a fault | `true` |

`outcome` is the field of record; `isError` is derived from it and kept for clients written
before outcomes existed. A stored message or an exported transcript that predates `outcome`
is read by its `isError`. The same outcome is what the audit row and the
`tool_calls_total{outcome=ok|refused|error}` metric record, so a refused pod read is
distinguishable from a granted one everywhere without reading server logs. No allow-list or
pre-check exists on the agent's side: the far system decides, Penstock reports.

## Architecture

```
 ┌──────────────────────────────────────────────────────┐
 │ AgentController  (REST: /api/chat, /chat/stream…)   │
 └───────────────┬──────────────────────────────────────┘
                 │
           ┌─────▼──────┐       ┌─────────────────┐
           │ AgentService│──────▶│  LlmProvider    │◀── Anthropic / OpenAI / Ollama
           └─────┬──────┘       └─────────────────┘
                 │
           ┌─────▼──────┐       ┌─────────────────┐
           │ToolRegistry│──────▶│  Tool (N impls) │◀── read/write/edit/list_dir/
           └─────┬──────┘       └─────────────────┘    glob/grep/shell/git
                 │
           ┌─────▼──────┐       ┌──────────────────────────┐
           │SessionStore│──────▶│ InMemory / JpaSessionStore│◀── SQLite (JPA)
           └────────────┘       └──────────────────────────┘
```

The **agent loop** in `AgentService`:

1. Append the user's message to the session (and persist via `SessionStore`).
2. Call `LlmProvider.complete(systemPrompt, history, toolSpecs)`.
3. If the assistant returned tool calls → execute each via `ToolRegistry`, append a TOOL message, goto 2.
4. Otherwise, the assistant's text is the final reply.
5. Bail with a friendly message after `max-iterations` iterations.

The **streaming variant** (`chatStreaming`) invokes a callback on every message
as it's produced, which the SSE controller relays to the client.

## Testing

```bash
./gradlew test
```

- `FileToolsTest` — round-trip read/write/edit, path-traversal guard, ambiguous-edit guard.
- `AgentServiceTest` — the tool-use loop with a scripted fake provider; asserts token usage accumulates.
- `AgentControllerIT` — REST integration test of `/api/chat` and `/api/tools` using a stub LLM.
- `ChatStreamIT` — SSE streaming endpoint: scripted stub provider emits a tool call + final reply; asserts `session`, `message`, `done` events and usage totals.
- `JpaSessionStoreTest` — JPA slice test against H2 covering create/get/append/list/delete/update.
- `SecurityConfigTest` — two nested Spring Boot tests for auth on (401/200) and auth off (open).

## Extending

**Add a new tool** — implement `com.example.agent.tools.Tool` and expose it as a `@Component`. `ToolRegistry` auto-discovers all `Tool` beans.

**Add a new LLM provider** — implement `com.example.agent.llm.LlmProvider`, annotate with `@Component` and `@ConditionalOnProperty(name="agent.llm.provider", havingValue="your-name")`, then set `agent.llm.provider=your-name`.

**Harden for production** — swap `InMemoryUserDetailsManager` for a real user store; enforce HTTPS (put behind nginx / a load balancer); add rate limits; persist audit logs of tool calls.

## Safety notes

- The agent is restricted to `agent.workspace`; attempts to access paths outside are rejected with a `SecurityException`.
- The `shell` tool runs inside the workspace and applies the `blocked-patterns` regex list. **Treat it like giving the LLM shell access on your machine** — start with a throwaway directory.
- `git` is whitelisted to a small set of safe subcommands (no `push`, no `reset --hard`, no `rebase`).

## Authentication and the audit log

Auth ships **off** (`AGENT_AUTH_ENABLED=false`) so the agent starts with no setup. With it
off there is no security context, so every row in `audit_events` records the user as
`anonymous` — the log tells you *what* happened, not *who* asked for it.

If the audit trail is a reason you are running this, turn auth on before anything else:

```bash
export AGENT_AUTH_ENABLED=true
export AGENT_AUTH_MODE=oidc                       # or `basic` for a quick local run
export AGENT_OIDC_ISSUER_URI=https://your-idp.example/
export AGENT_OIDC_AUDIENCE=penstock
```

The principal then comes from the token (`AGENT_OIDC_PRINCIPAL_CLAIM`, default `sub`) and
is what every LLM call and tool invocation is attributed to.

## Acting as the signed-in user

Two outbound calls can carry the signed-in user's own credential instead of the agent's:
the `pod` tool's calls to a Cistern pod, and the model calls behind the `openai` provider.
Every outbound call on a turn also carries the inbound `X-Request-Id`, so the pod's receipt,
a gateway's log and this service's `audit_events` all name the same request.

**Whose credential the pod sees** is `agent.tools.cistern.credential-mode`
(env `AGENT_TOOLS_CISTERN_CREDENTIALMODE`):

| Mode | Credential presented | Pod sees | When it has none |
| --- | --- | --- | --- |
| `service` (default) | `agent.tools.cistern.token` | the agent's WebID | error |
| `per-user` | `agent.credentials.per-user.cistern.<userId>` | the user | falls back to `agent.tools.cistern.token` |
| `forward` | the bearer token the user signed in with (`agent.auth.mode=oidc`) | the user | refuses — the agent never substitutes its own credential |

`forward` needs a caller who authenticated with a bearer token; Basic auth and anonymous
callers get a refusal from the tool, not a quiet switch to the agent's identity. The token
is held for the length of the turn and is never persisted or logged.

**A per-user model gateway key** is `agent.credentials.per-user.openai.<userId>`: when the
`openai` provider makes a call for that user it authenticates with their key, and users
without one use `agent.llm.openai.api-key`. Point `agent.llm.openai.base-url` at a
LiteLLM gateway (`http://litellm:4000`; the provider appends `/v1/chat/completions`) and
each employee's calls arrive under their own virtual key.

The `<userId>` is the principal name the session is stamped with — the
`agent.auth.oidc.principal-claim` value under OIDC — and is matched exactly. From properties
or YAML the key is kept verbatim (`agent.credentials.per-user.openai.Bob`); a key with a
character outside letters, digits, `-` and `.` is written in brackets
(`agent.credentials.per-user.openai.[alice@example.com]`). From the environment it is
lower-cased and an inner underscore becomes a dot: `AGENT_CREDENTIALS_PERUSER_OPENAI_BOB=sk-x`
provisions user `bob`, so a principal with upper case, `@` or `-` in it must be configured in
properties, not the environment. The prefix is `PERUSER` — `AGENT_CREDENTIALS_PER_USER_…`
binds nothing, silently, because map entries are matched from the environment side where
the hyphen has already been removed.

## Licence

Apache License 2.0 — see [LICENSE](LICENSE) and [NOTICE](NOTICE). Free for commercial use,
no strings.

## Turn and token budgets

Three bounds stop a runaway loop: `max-turns-per-request`, `max-tokens-per-request`
and `max-tokens-per-session`. All three are configurable, and the defaults come
from measurement rather than taste — see `docs/real-repo-validation.md`.

Against a real repository, using a local 30B model:

| Task | Turns used |
|---|---|
| Find every reader of a config property | 2 |
| Add a Javadoc comment to one class | 5 |
| Add an endpoint plus a test for it | more than 10 — failed at the old cap |

**Which bound bites first matters more than either number.** When the two-file
task was retried after the `list_dir` fix, it completed its edits in about 11
turns and then stopped on the *token* budget at ~54k, not the turn cap. Raising
turns alone would have changed nothing. Reckon on roughly 4–5k tokens per turn
against a real codebase, and raise both together or neither.

Hitting a bound is not an error: the assistant says which one stopped it, the
session history is kept, and **sending another message continues the work with a
fresh turn and token budget**. Continuation is deliberately manual — the bounds
exist so an unattended loop cannot burn tokens indefinitely.

Lower them for a shared or metered deployment; raise them if you are driving a
local model where tokens are effectively free.
