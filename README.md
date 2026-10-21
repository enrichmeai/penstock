# Penstock

*Formerly "AI Coding Agent".* A penstock carries water under pressure to the
turbine — it delivers work to where the work gets done.

**Penstock runs your agents on your data, with receipts.** A self-hosted agent
runtime, built on GitHub Copilot (or Claude / OpenAI / Ollama), for the work
that happens where no editor is open. Four things define it:

1. **Where it runs.** Headless — CI hooks, Slack commands, scheduled triage
   jobs, webhooks — not a developer's keyboard.
2. **Whose hardware.** Self-hosted, Apache 2.0. The transcript, the audit log
   and the credentials never leave your deployment.
3. **Whose data, provably.** Through the [`pod` tool](./src/main/java/com/example/agent/tools/CisternTool.java),
   an agent reads and writes a person's own data in a
   [Cistern](https://github.com/enrichmeai/cistern) pod, under rules that
   person wrote — and every access is receipted on their side, not ours.
4. **And it can sit inside Air, too.** `--acp` has Penstock speak the Agent
   Client Protocol so it shows up as a selectable agent inside JetBrains Air
   (and Zed) — see [In your IDE (ACP)](#in-your-ide-acp). Not yet verified
   against a real editor ([#63](https://github.com/enrichmeai/penstock/issues/63)).

Built in **Spring Boot 3 + Gradle + Java 21** with auth, rate limiting, audit
logging, and Prometheus metrics on by default. See
[Penstock and JetBrains Air](#penstock-and-jetbrains-air) below for how this
differs from governing agent spend and model choice.

**Three surfaces, one backend**: a web UI for developers, a REST API for automation
(CI, Slack, bots, cron), and SSE streaming for real-time interactive work. Built-in
tools cover file I/O, code search, shell, and git. Adding a tool for your internal
systems (Jira, PagerDuty, your runbook repo) is one Java class implementing `Tool`.

See [`examples/`](./examples/) for ready-to-deploy GitHub Actions and Slack webhook
samples, and [`JiraTool`](./src/main/java/com/example/agent/tools/JiraTool.java) for a
Jira integration.

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

Ready-to-deploy demos that show the agent operating headless, where no editor
is open:

- **[CI nightly triage](./examples/ci-nightly-triage/)** — a GitHub Actions
  workflow that calls the agent's REST API at 2am, asks it to diagnose failing
  tests, and opens a draft issue with the root cause. Zero human in the loop.
- **[Slack slash command](./examples/slack-webhook/)** — a minimal Python
  handler that wires `/agent <prompt>` in Slack to the agent, with Slack
  signature verification and the 3-second async response pattern.
- **[JiraTool](./src/main/java/com/example/agent/tools/JiraTool.java)** — a
  reference `Tool` implementation showing how to give the agent access to your
  internal systems. Copy this pattern for your runbook repo, PagerDuty,
  observability stack, anything. One class per tool.

None of this needs an editor open: agent workflows that run without a
developer at a keyboard, against your own infrastructure, with tools that
talk to your own systems.

## Penstock and JetBrains Air

[JetBrains Air](https://blog.jetbrains.com/blog/2026/09/22/introducing-jetbrains-air/)
(announced 2026-09-22) puts agents inside the IDE and governs which agents and
models a developer may spend against. Penstock answers a different question:
whose *data* an agent is allowed to touch while it works, and proof afterwards
that it stayed inside the rules.

| | Penstock | JetBrains Air |
| --- | --- | --- |
| Where it runs | Headless — CI, Slack, cron, webhooks; no editor needed | Inside JetBrains IDEs, the browser (`air.jetbrains.cloud`), or the local Air App desktop client ([docs](https://www.jetbrains.com/help/air/ways-to-reach-air-teams.html)) |
| Whose hardware | Self-hosted, Apache 2.0 — nothing leaves your deployment | "A cloud environment is a Docker container that runs on a virtual machine in the cloud" ([docs](https://www.jetbrains.com/help/air/configure-environments.html)); no self-hosted mode is documented |
| What's governed | Whose data an agent touches, under rules that person wrote, receipted on their side via the `pod` tool and a [Cistern](https://github.com/enrichmeai/cistern) pod | Agent/model permissions, spend, and auditability for the organization: "organizational policy, visibility, auditability, cost management, and accountability for AI-assisted and agent-driven development" ([announcement](https://blog.jetbrains.com/blog/2026/09/22/introducing-jetbrains-air/)); "Set permissions for the whole organization or tailor them to specific teams or individuals" ([product page](https://www.jetbrains.com/air/)) |
| How they fit together | Penstock as a selectable agent inside Air via the Agent Client Protocol (`--acp`, [docs/ide-agents.md](./docs/ide-agents.md)) — not yet verified against a real editor ([#63](https://github.com/enrichmeai/penstock/issues/63)) | — |

These aren't competing claims about the same thing: Air governs *which agent
and model* a developer may spend against, from inside the IDE. Penstock
governs *whose data* an agent may touch while running headless, and gives the
data's owner a receipt afterwards — see [Acting as the signed-in
user](#acting-as-the-signed-in-user).

## Features

- **Pluggable LLM layer**: `copilot` (default), `anthropic`, `openai`, `ollama` — switch via config. Automatic retry with exponential backoff on 429 / 5xx, respecting `Retry-After`.
- **Tool use loop**: the agent calls tools, receives results, and iterates until done.
- **Ten built-in tools**: `read_file`, `write_file`, `edit_file`, `list_dir`, `glob`, `grep`, `shell`, `git`, `pod` and `jira_search` (both are registered by default, and every call returns an error until they are configured; `--acp` leaves out `jira_search`). Three more are off by default: `web_fetch`, registered only with `agent.tools.web.enabled=true` (see [Safety notes](#safety-notes)), and the read-only `list_patterns` and `load_pattern`, the agent's on-demand skills (#175), registered only with `agent.memory.enabled=true`.
- **Streaming responses** over Server-Sent Events — assistant turns, tool calls, and tool results stream to the UI as they happen.
- **Token usage tracking** — per-call usage is accumulated per session and exposed via the API and UI.
- **Pluggable persistence**: in-memory (default), SQLite, or Postgres via JPA/Hibernate. Switch with one env var.
- **Auth on by default** in front of the API and UI: HTTP Basic (the default mode) or OIDC JWT bearer tokens. Opt out with `AGENT_AUTH_ENABLED=false`.
- **OpenAPI / Swagger UI** at `/swagger-ui.html` for easy API exploration — behind auth when auth is enabled, since the document describes every endpoint and schema.
- **Explicit CORS** configuration, customisable for production.
- **Docker** + `docker-compose.yml` with a persistent SQLite volume and a bind-mounted workspace.
- **Sandboxed workspace**: every file/shell path resolves relative to `agent.workspace` and path traversal is blocked.
- **Polished web UI**: markdown rendering for assistant responses, syntax-preserving code blocks with copy buttons, a Stop button to abort in-flight runs, live token counters.
- **Comprehensive tests**: unit, JPA slice (H2), SSE streaming, and security (auth on/off).

### Phase 2 operability

- **Structured logging** — `logback-spring.xml` emits plain text by default; activate `-Dspring.profiles.active=prod` for JSON via `logstash-logback-encoder`. Every log line carries `requestId`, `userId`, and (where applicable) `sessionId` in MDC.
- **Request IDs** — the `RequestIdFilter` reads `X-Request-Id` or generates one, stamps MDC, and echoes the ID as a response header so clients can correlate.
- **Metrics** — `/actuator/prometheus` exposes `llm_calls_total{provider,outcome}`, `llm_tokens_total{provider,kind}`, `tool_calls_total{tool,outcome}`, `tool_call_duration_ms{tool}`, and `sse_streams_active` gauge. **Requires authentication when auth is enabled** — the labels carry tool names, providers and token counts. If your scraper cannot authenticate, set `AGENT_METRICS_PUBLIC_SCRAPE=true` and restrict the port at the network; `/actuator/health/**` stays open either way for load balancers.
- **Readiness vs liveness** — `/actuator/health/liveness` stays up while an LLM provider is misconfigured; `/actuator/health/readiness` goes DOWN so load balancers pull the instance out of rotation. Custom `LlmProviderHealthIndicator` does a config-only check (no external calls).
- **Audit log** — every LLM call and tool invocation is persisted to `audit_events` in SQLite and Postgres modes; memory mode stores nothing, and tool calls appear only in the application log. Owner-scoped read at `GET /api/sessions/{id}/audit`.
- **Graceful SSE shutdown** — on `ContextClosedEvent` the `SseEmitterRegistry` sends a terminating `shutdown` event and completes every in-flight emitter so clients don't see socket resets.
- **CI** — `.github/workflows/build.yml` caches Gradle, auto-bootstraps the wrapper, runs `./gradlew build`, uploads test reports and the built jar.

### Phase 1 safety (see [ROADMAP.md](./ROADMAP.md) and [SECURITY.md](./SECURITY.md))

- **Shell sandbox**: optional allow-list (`agent.tools.shell.allowed-commands`), kill switch (`AGENT_TOOLS_SHELL_ENABLED=false`), expanded block-list, Docker hardening flags (`read_only`, `cap_drop`, `pids_limit`, `no-new-privileges`).
- **Resource limits**: bounded SSE thread pool, per-request + per-session token budgets, tool-output truncation, last-N context windowing. Defaults are 25 turns and 150k tokens per request (`AGENT_LLM_MAX_TURNS_PER_REQUEST`, `AGENT_LLM_MAX_TOKENS_PER_REQUEST`), measured against a real repository — see below.
- **Rate limiting**: Bucket4j filter with separate limits for `/api/chat*` vs `/api/**`; keyed by principal-else-IP; `429 Retry-After` responses.
- **Per-user session ownership**: every session is stamped with the authenticated user (or `anonymous` when auth is off). Cross-user access returns 404, never leaking existence.
- **Error hygiene**: global `@RestControllerAdvice` returns stable `ApiError` JSON with request IDs; messages are sanitised unless the exception is marked `@SafeMessage`.
- **Flyway migrations** replace `ddl-auto=update` when `agent.storage.type` is `sqlite` or `postgres`.

## Quick start

### 1. Prerequisites

- JDK 21 (`java -version` should report 21)
- Either `gradle` installed globally, OR run `./bootstrap.sh` to download the wrapper jar.

### 2. Bootstrap the Gradle wrapper (first time only)

```bash
./bootstrap.sh
# or, if you have gradle installed:
gradle wrapper --gradle-version 8.14.6
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

Open **http://localhost:8080** and start chatting. Auth is on: sign in as `admin` with the password logged once at startup, or set `AGENT_AUTH_PASSWORD` first (see [Authentication](#authentication)).

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
- Builds the jar in a Gradle/JDK 21 stage, runs it on `eclipse-temurin:21-jdk-jammy` with Gradle and a pre-seeded dependency cache, so the agent can build and test the code it edits.
- Runs `ollama` + a one-shot `ollama-pull`, reachable at `http://ollama:11434`.
- Keeps the SQLite DB on the `agent-data` volume (not a bind mount — see the doc).
- Bind-mounts your chosen workspace into `/workspace`.
- Adds `host.docker.internal` so the container can reach a host-native Ollama.
- Exposes a `HEALTHCHECK` that curls `/api/health`.

## Storage

Three backends, selected at startup:

| `AGENT_STORAGE_TYPE` | What it does |
| --- | --- |
| `memory` (default) | In-process `ConcurrentHashMap`. JPA auto-config is excluded so no DB file is created. |
| `sqlite` | SQLite via Spring Data JPA + Hibernate, schema created by Flyway (`ddl-auto: none`). File path set by `AGENT_SQLITE_PATH` (default `./data/agent.db`). |
| `postgres` | Postgres via Spring Data JPA + Hibernate, schema created by Flyway. `AGENT_DB_URL` (default `jdbc:postgresql://localhost:5432/agent`), `AGENT_DB_USER` (default `agent`), `AGENT_DB_PASSWORD`, `AGENT_DB_POOL_MAX` (default `10`), `AGENT_DB_POOL_MIN` (default `2`). |

Messages are stored as JSON blobs in an `agent_messages` table so tool calls / results round-trip without schema changes.

## Streaming

`POST /api/chat/stream` returns a Server-Sent Events stream:

```
event: session    data: { "sessionId": "...", "title": "..." }
event: token      data: { "text": "…" }
event: message    data: { "role":"ASSISTANT","text":"…","toolCalls":[…], … }
event: message    data: { "role":"TOOL","toolResults":[…] }
event: approval   data: { "sessionId":"…","callId":"…","tool":"shell","arguments":{…},"expiresAt":"…" }
…
event: session    data: { "sessionId": "...", "title": "..." }
event: done       data: { "ok": true }
```

`token` events carry per-token deltas of the assistant's text (streaming is on by
default, `AGENT_LLM_STREAMING_ENABLED`); each finished message still arrives as a
`message`. The closing `session` event carries the updated title. A failure sends
`error` (`{ "error": "..." }`), and a server shutting down sends `shutdown` to every
open stream.

An `approval` event means a tool call is waiting under `agent.tools.policy.<tool>=ask`: the
stream pauses until `POST /api/sessions/{id}/approvals/{callId}` answers it or
`agent.tools.ask-timeout-seconds` passes (see [Safety notes](#safety-notes)).

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

## In your IDE (ACP)

`java -jar penstock-<version>.jar --acp` speaks the [Agent Client Protocol](https://github.com/agentclientprotocol/java-sdk)
over stdin/stdout instead of serving HTTP — launch it from an ACP-aware editor
in place of the editor's own agent. It answers `initialize`, opens sessions,
streams replies, and honours `session/cancel`; the read-only tools
(`read_file`, `list_dir`, `glob`, `grep`, and `pod`'s read/list/receipts
operations) run unprompted, while `write_file`, `edit_file`, `shell`, `git`
and `pod`'s `write` operation each go through `session/request_permission`
first. The working directory the editor opens must be this agent's configured
workspace; anything else is refused. See
[Penstock in JetBrains Air and Zed](./docs/ide-agents.md) for registration
steps in each editor, what the permission prompt looks like, and
troubleshooting.

## Start in one command

The release workflow attaches `start.sh` to every release from the first one after v0.5.2 (#147); v0.5.2
does not carry it, so until that release the command below fails. Before then, clone this repository and,
from your project's folder, run `sh /path/to/penstock/scripts/start.sh` (the script names the project
after the folder it runs in).

From inside the project you work on, with Java 21 and [Claude Code](https://code.claude.com) installed:

```bash
curl -fsSL https://github.com/enrichmeai/penstock/releases/latest/download/start.sh | sh
```

It downloads the latest release jar to `~/.penstock/` and checks it against the SHA-256 digest GitHub
publishes for the release asset, creates a private memory root at `~/penstock-memory` (a git repository
declaring this project; in an existing one it only adds this project's line to `memory.yaml`, and
leaves the file as it is when the file does not list its projects in a `projects:` block), and registers the
`penstock-memory` MCP server below with Claude Code for this project. Then start Claude Code there and
type `/mcp__penstock-memory__start-with-memory`. Nothing leaves your machine: no account, no network
listener, no credential. Running it again changes nothing. `PENSTOCK_MEMORY_ROOT`, `PENSTOCK_PROJECT`,
`PENSTOCK_HOME` and `PENSTOCK_VERSION` change the defaults; the script's header lists them.

To remove it from a project, run `claude mcp remove penstock-memory` in that project's folder (the
registration is local to it). The jar in `~/.penstock/` is shared by every project you set up, so
delete it only once none uses it. Your memory root stays.

## Your memory in Claude Code and Claude Desktop (MCP)

`--mcp` runs Penstock as a local [MCP](https://modelcontextprotocol.io) server over stdio (#116). The client launches it as a subprocess on your machine, so there is no network listener, no hosting and no credential.

| | What it does |
|---|---|
| `recall` (tool) | Returns the "Owner's memory" block a Penstock turn would give its model for the question. It is built by the same code, so the project's rules in `memory.yaml` apply: a public project never gets private cards, and `personal: none` gets no personal memory. Read-only. |
| `draft_episode` (tool) | Writes **one new draft** episode in the project's `drafts/` folder for you to review. Recall does not read drafts: keep one by moving it into `episodes/` and committing it. It never overwrites and never touches facts, patterns or `memory.yaml`. Any line that looks like a credential refuses the whole draft. |
| `start-with-memory` (prompt) | Tells the client to call `recall` before it plans. With MCP the client decides when to call a tool; this prompt narrows that gap. |

Every call is audited (`mcp.recall`, `mcp.draft_episode`, plus the sections and cards a recall returned). With `AGENT_STORAGE_TYPE=memory` the audit goes to stderr; with sqlite or postgres it is stored in `audit_events`.

Add it to Claude Code from inside a project's folder (local scope: only you, only this project). Put another option between the last `-e` and the server name, as Claude Code's MCP docs require:

```bash
claude mcp add -e AGENT_MEMORY_ROOT=/path/to/your/memory -e AGENT_MEMORY_PROJECT=valuedocs \
  --transport stdio penstock-memory -- java -jar /path/to/penstock-<version>.jar --mcp
```

For Claude Desktop, add the same command to `claude_desktop_config.json`:

```json
{ "mcpServers": { "penstock-memory": {
    "command": "java", "args": ["-jar", "/path/to/penstock-<version>.jar", "--mcp"],
    "env": { "AGENT_MEMORY_ROOT": "/path/to/your/memory", "AGENT_MEMORY_PROJECT": "valuedocs" } } } }
```

A draft for a project that only `memory.yaml` declares passes `check-cards.sh` (#117).

A client must keep stdin open until it has read its answers: the server stops when stdin closes, so a one-shot `printf ... | java -jar penstock-<version>.jar --mcp` may exit before it replies. Claude Code and Claude Desktop keep the connection open.

`recall` also takes a `project` argument, so one server can answer for any project the root declares, each under its own rules. Run the jar directly rather than the Docker image: the image's entrypoint writes a line to stdout on first start, and stdout belongs to MCP messages.

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

On by default. In the default `basic` mode, set a stable password with:

```bash
export AGENT_AUTH_USERNAME=admin
export AGENT_AUTH_PASSWORD=a-real-secret
```

With `AGENT_AUTH_PASSWORD` unset, a random password is generated at startup and
logged once. Opt out explicitly with `AGENT_AUTH_ENABLED=false`.

Every endpoint requires authentication except `/api/health`, `/actuator/health/**`
and the login plumbing (`/login/**`, `/oauth2/**`, `/error`). In `basic` mode that is
HTTP Basic: the browser will prompt the user on first load; once authenticated,
credentials are sent automatically on every request (including SSE). With
`AGENT_AUTH_MODE=oidc` the API takes a JWT bearer token instead (see
[Authentication and the audit log](#authentication-and-the-audit-log)).

```bash
curl -u admin:a-real-secret http://localhost:8080/api/tools
```

## Configuration reference

All settings live in `src/main/resources/application.yml` under `agent.*` and can be
overridden with env vars or `--agent.*=value` command-line flags.

| Property | Default | Env var | Notes |
| --- | --- | --- | --- |
| `agent.workspace` | `${user.dir}/agent-workspace` | `AGENT_WORKSPACE` | Folder the agent is restricted to. |
| `agent.storage.type` | `memory` | `AGENT_STORAGE_TYPE` | `memory`, `sqlite` or `postgres`. |
| `agent.storage.sqlite-path` | `./data/agent.db` | `AGENT_SQLITE_PATH` | SQLite DB file. |
| `agent.auth.enabled` | `true` | `AGENT_AUTH_ENABLED` | Auth is on by default — a shell-executing agent never ships open. Set `false` explicitly for local experiments; the compose dev/demo stack does. |
| `agent.auth.username` | `admin` | `AGENT_AUTH_USERNAME` | |
| `agent.auth.password` | *(generated at startup if unset)* | `AGENT_AUTH_PASSWORD` | Unset (or the old `change-me`) → a random password is generated and logged once. |
| `agent.cors.allowed-origins` | *(none — cross-origin locked)* | `AGENT_CORS_ALLOWED_ORIGINS` | Comma-separated origins granted CORS with credentials; `*` allows any origin without credentials. The bundled UI is same-origin and needs none. |
| `agent.llm.provider` | `copilot` | `AGENT_LLM_PROVIDER` | `copilot` \| `anthropic` \| `openai` \| `ollama`. |
| `agent.llm.max-turns-per-request` | `25` | `AGENT_LLM_MAX_TURNS_PER_REQUEST` | Safety cap on the tool-use loop per request. See [Turn and token budgets](#turn-and-token-budgets). |
| `agent.llm.max-tokens-per-request` | `150000` | `AGENT_LLM_MAX_TOKENS_PER_REQUEST` | Token budget for one request. |
| `agent.llm.context.policy` | `last-n` | `AGENT_LLM_CONTEXT_POLICY` | What the model is sent of a session's history: `none` (all of it), `last-n` (the newest `agent.llm.context.last-n`, default 50), or `compact` (older messages folded into a summary; see [Turn and token budgets](#turn-and-token-budgets)). |
| `agent.llm.context.compact-threshold-ratio` | `0.6` | `AGENT_LLM_CONTEXT_COMPACTTHRESHOLDRATIO` | `compact`: fold once the estimated request exceeds this share of `max-tokens-per-request`. |
| `agent.llm.context.compact-keep-recent` | `8` | `AGENT_LLM_CONTEXT_COMPACTKEEPRECENT` | `compact`: the newest messages always sent verbatim. |
| `agent.llm.max-tokens-per-session` | `600000` | `AGENT_LLM_MAX_TOKENS_PER_SESSION` | Token budget for a whole session. |
| `agent.llm.copilot.api-key` | — | `GITHUB_COPILOT_TOKEN` / `GITHUB_TOKEN` | GitHub Copilot / GitHub Models token. |
| `agent.llm.copilot.base-url` | `https://api.githubcopilot.com` | `COPILOT_BASE_URL` | Override for enterprise gateways. |
| `agent.llm.copilot.model` | `gpt-4o` | `COPILOT_MODEL` | Any Copilot-exposed model (gpt-4o, claude-3-5-sonnet, o1-mini, …). |
| `agent.llm.anthropic.api-key` | — | `ANTHROPIC_API_KEY` | |
| `agent.llm.openai.api-key` | — | `OPENAI_API_KEY` | Service key. A per-user key (below) takes precedence for that user. |
| `agent.llm.openai.base-url` | `https://api.openai.com` | — | Origin only; `/v1/chat/completions` is appended. A LiteLLM gateway is `http://litellm:4000`. |
| `agent.credentials.per-user.openai.<userId>` | — | `AGENT_CREDENTIALS_PERUSER_OPENAI_<USERID>` | Gateway key for one user. The env form lower-cases the user id; see [Acting as the signed-in user](#acting-as-the-signed-in-user). |
| `agent.tools.cistern.base-url` | — | `CISTERN_BASE_URL` | The pod. Blank: the `pod` tool stays listed and every call returns an error. |
| `agent.tools.cistern.token` | — | `CISTERN_AGENT_TOKEN` | The agent's own pod credential. |
| `agent.tools.cistern.credential-mode` | `service` | `AGENT_TOOLS_CISTERN_CREDENTIALMODE` | `service` \| `per-user` \| `forward` — whose credential the pod sees. |
| `agent.credentials.per-user.cistern.<userId>` | — | `AGENT_CREDENTIALS_PERUSER_CISTERN_<USERID>` | Pod credential for one user, used in `per-user` mode. |
| `agent.llm.ollama.base-url` | `http://localhost:11434` | `OLLAMA_BASE_URL` | |
| `agent.tools.shell.enabled` | `true` | `AGENT_TOOLS_SHELL_ENABLED` | |
| `agent.tools.shell.timeout-seconds` | `600` | `AGENT_TOOLS_SHELL_TIMEOUT_SECONDS` | Per-command timeout. |
| `agent.tools.file.max-bytes` | `1048576` | — | Max size for a single `read_file`. |
| `agent.tools.policy.<tool>` | none (every tool `allow`) | `AGENT_TOOLS_POLICY_<TOOL>` (e.g. `AGENT_TOOLS_POLICY_WRITE_FILE=ask`) | `allow`, `ask` or `deny` for one tool, by its name (#179). See [Safety notes](#safety-notes). An entry naming no registered tool logs a warning at startup. |
| `agent.tools.ask-timeout-seconds` | `120` | `AGENT_TOOLS_ASK_TIMEOUT_SECONDS` | How long an `ask` waits for the user before it counts as denied. |
| `agent.hooks.pre-tool[n]` | none | — | Owner hooks before a tool call (#184): `command` (argv list), `tools` (names; empty means all), `timeout-seconds` (10). Can deny an allowed call; a failing hook denies. See [Safety notes](#safety-notes). |
| `agent.hooks.post-tool[n]` | none | — | Same shape, after a tool call; may add a note to the result. Fails open. |
| `agent.hooks.turn-start[n]`, `agent.hooks.turn-end[n]` | none | — | Same shape, observe only. Fail open. |
| `agent.tools.web.enabled` | `false` | `AGENT_TOOLS_WEB_ENABLED` | Registers `web_fetch` (#176). Off: the tool does not exist. |
| `agent.tools.web.allowed-hosts` | empty | `AGENT_TOOLS_WEB_ALLOWED_HOSTS` (comma-separated) | The only hosts `web_fetch` may reach: `docs.example.com` exactly (on the default port), `*.example.com` for its subdomains, or `host:8443` for one other port. Empty refuses every URL. |
| `agent.tools.web.max-bytes` | `200000` | — | Bytes read from one response; the rest is cut off and the result says `truncated`. |
| `agent.tools.web.timeout-seconds` | `15` | — | The whole fetch, every redirect and the body included; a slower response is cut off. |
| `agent.tools.web.max-redirects` | `5` | — | Redirects followed, each checked against the allow-list. |
| `agent.tools.web.max-query-chars` | `256` | — | A longer query string is refused. |
| `agent.tools.web.max-path-chars` | `512` | — | A longer path is refused. |
| `agent.memory.enabled` | `false` | `AGENT_MEMORY_ENABLED` | "Retrieve before reason" (#73) and "recall in the turn" (#88): load matching `facts/`, the episodes those facts were learned from (their `provenance`, however old, #152), the project's latest `episodes/`, and matching `patterns/` + `references/` into the turn's system prompt before the first model call, in that order under one 12 000-character cap (entries dropped whole from the end, so patterns go first; no episode twice). Each load is audited (`fact.loaded`, `episode.loaded` with `reason: provenance` or `latest`, `pattern.loaded`). Also registers `list_patterns` and `load_pattern` (#175): the model lists the patterns this project's audience may see (#112) and loads one when a task needs it, part-way through a turn. They see exactly what recall would, so on a sectioned root that declares `projects:` in `memory.yaml`, a pattern outside the project's audience is neither listed nor loadable. A flat root (no `projects/`), a root with no `projects:` block, or a project not declared there applies no audience filter, for these tools as for recall. A refused load is an error result. Each load is audited as `pattern.loaded` with `reason: on-demand`, so `GET /api/audit/recall` counts it. `PATTERN.md` is capped at 12 000 bytes, and the list at 50 patterns. Off by default — these folders are this project's own build memory. |
| `agent.memory.root` | `${user.dir}` | `AGENT_MEMORY_ROOT` | Directory containing `patterns/`, `requests/`, `references/`, `facts/` and `episodes/`. |
| `agent.memory.signature-depth` | `8` | `AGENT_MEMORY_SIGNATURE_DEPTH` | Max depth of the per-turn workspace walk used for signature matching; a file nested deeper is never considered. |
| `agent.memory.max-facts` | `8` | `AGENT_MEMORY_MAX_FACTS` | Recall in the turn (#88): at most this many active `facts/` join the block — those whose `triggers` occur in the input as whole words, plus the project's `account`, `identifier` and `location-of-secret` facts; by confidence, then `last_confirmed`. Superseded facts are never selected. |
| `agent.memory.max-episodes` | `2` | `AGENT_MEMORY_MAX_EPISODES` | At most this many of the project's latest `episodes/` join the block (asked, decided, refused, open; never `built` file lists or `learned`). |
| `agent.memory.project` | derived | `AGENT_MEMORY_PROJECT` | The project the workspace belongs to (`penstock`, `cistern`, `valuedocs`, `site`). Empty: the workspace directory's name through `agent.memory.project-aliases` (`enrichmeai.github.io` → `site`), logged once at startup. |

## REST API

| Verb | Path | Body / Params | Description |
| --- | --- | --- | --- |
| `GET` | `/api/health` | — | Liveness + provider + tool list (always unauthenticated) |
| `GET` | `/api/tools` | — | JSON-Schema of every registered tool |
| `POST` | `/api/chat` | `{ "sessionId"?, "message" }` | Non-streaming chat. |
| `POST` | `/api/chat/stream` | `{ "sessionId"?, "message" }` | SSE stream of messages as they're produced. |
| `GET` | `/api/sessions` | — | List sessions |
| `GET` | `/api/sessions/search` | `q`, `limit` (default 20, max 100) | Search the caller's own sessions by title or message text |
| `POST` | `/api/sessions` | — | Create a blank session |
| `GET` | `/api/sessions/{id}` | — | Full message history |
| `GET` | `/api/sessions/{id}/usage` | — | Token usage summary for a session |
| `DELETE` | `/api/sessions/{id}` | — | Delete a session |
| `GET` | `/api/sessions/{id}/export.md` | — | The session's transcript as Markdown |
| `GET` | `/api/sessions/{id}/approvals` | — | Tool calls waiting for an answer under policy `ask`: `[{sessionId, callId, tool, arguments, expiresAt}]` |
| `POST` | `/api/sessions/{id}/approvals/{callId}` | `{ "decision": "approve" \| "deny" }` | Answer one waiting call. `204` when it reached the turn; `404` when nothing with that id is waiting (answered, timed out, never asked) or the session is not yours. |
| `POST` | `/api/sessions/import` | Markdown transcript (`text/markdown`) | Create a new session owned by the caller from an exported transcript |
| `GET` | `/api/sessions/{id}/audit` | — | Audit events for a session (tool/LLM calls), owner-scoped |
| `GET` | `/api/audit/patterns` | — | Per-pattern `pattern.loaded` usage: `[{patternId, loads, lastLoadedAt, versions}]`, never-loaded patterns last. `[]` with `agent.memory.enabled=false`; `503` in memory storage mode (no audit table). |
| `GET` | `/api/audit/recall` | — | Per-card use across `pattern.loaded`, `fact.loaded` and `episode.loaded`: `[{kind, id, loads, lastLoadedAt, versions}]`, `kind` one of `pattern`, `fact`, `episode`; `versions` are pattern versions (`[]` for facts and episodes). Every card in the catalogue is listed, never-loaded ones last (#90). Same `[]` and `503` as `/api/audit/patterns`, which is its pattern rows. |

Interactive API docs at **`/swagger-ui.html`** when the app is running.

## Architecture

```
 ┌──────────────────────────────────────────────────────┐
 │ AgentController  (REST: /api/chat, /chat/stream…)   │
 └───────────────┬──────────────────────────────────────┘
                 │
           ┌─────▼──────┐       ┌─────────────────┐
           │ AgentService│──────▶│  LlmProvider    │◀── Copilot / Anthropic / OpenAI / Ollama
           └─────┬──────┘       └─────────────────┘
                 │
           ┌─────▼──────┐       ┌─────────────────┐
           │ToolRegistry│──────▶│  Tool (N impls) │◀── read/write/edit/list_dir/
           └─────┬──────┘       └─────────────────┘    glob/grep/shell/git/pod/jira_search
                 │
           ┌─────▼──────┐       ┌──────────────────────────┐
           │SessionStore│──────▶│ InMemory / JpaSessionStore│◀── SQLite / Postgres (JPA)
           └────────────┘       └──────────────────────────┘
```

The **agent loop** in `AgentService`:

1. Append the user's message to the session (and persist via `SessionStore`).
2. Call `LlmProvider.complete(systemPrompt, history, toolSpecs)`.
3. If the assistant returned tool calls → execute each via `ToolRegistry`, append a TOOL message, goto 2.
4. Otherwise, the assistant's text is the final reply.
5. Stop with a message saying which bound was hit after `max-turns-per-request` turns, or when a token budget is spent.

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
- `SecurityConfigTest` — eight nested Spring Boot tests: auth on by default with no settings (401, health open); auth off (open); Basic auth (401/200, wrong password rejected, metrics and API docs gated, health open); metrics reopened with `public-scrape` (API and docs stay gated); Basic plus an OAuth registration (redirect to the provider); `oidc` mode (JWT accepted, 401 without); no password configured (`change-me` refused, health open, API gated); and the `acp` profile active in a servlet app (auth still enforced).

## Extending

**Add a new tool** — implement `com.example.agent.tools.Tool` and expose it as a `@Component`. `ToolRegistry` auto-discovers all `Tool` beans.

**Add a new LLM provider** — implement `com.example.agent.llm.LlmProvider`, annotate with `@Component` and `@ConditionalOnProperty(name="agent.llm.provider", havingValue="your-name")`, then set `agent.llm.provider=your-name`.

**Harden for production** — rate limiting and the tool-call audit log are already there. Use `AGENT_AUTH_MODE=oidc` instead of the single Basic user; enforce HTTPS (put behind nginx / a load balancer); and use `postgres` storage so the audit log and sessions are persisted.

**Project memory** — `requests/`, `references/`, `patterns/`, `episodes/` and `facts/` at the repo root record what was wanted, what was trusted, how it was built, what happened (asked, built, decided, refused, learned, open), and what the owner now believes (one fact per file, with provenance, superseded rather than overwritten), as plain YAML/Markdown; start at `requests/README.md`. `scripts/write-episode.sh` drafts an episode from a task's own PR and commits, and with `--session <id>` (or `--audit <file>`) lists under `recalled:` what the session's turns loaded; `scripts/consolidate.sh` proposes facts from episodes' learned lines; `scripts/fact.sh <words>` asks the record before you ask the owner. `scripts/journal.sh` prints what was built, decided and believed in a window, and the loops still open (#90); `GET /api/audit/recall` counts how often each pattern, fact and episode was loaded, so a card nobody uses shows up. The owner's private cards live in a separate, sectioned memory root (`enrichmeai/memory`: `estate/` plus `projects/<name>/`); `check-cards.sh`, `fact.sh` and recall read it with `--root` / `MEMORY_ROOT` / `agent.memory.root` ([docs/memory-root.md](docs/memory-root.md)). Every card follows [memory format v1](docs/memory-format.md) (designed by Joseph Antony Aruja): a versioned, model-neutral schema per kind under `schema/`, a `visibility` boundary on every card, and a renderer contract that builds context for any model. With `agent.memory.enabled=true` a turn recalls on its own: matching facts, the episodes they were learned from (#152), the project's last episodes and matching patterns join the system prompt before the first model call (#88).

## Safety notes

- The agent is restricted to `agent.workspace`; attempts to access paths outside are rejected with a `SecurityException`.
- The `shell` tool runs inside the workspace and applies the `blocked-patterns` regex list. **Treat it like giving the LLM shell access on your machine** — start with a throwaway directory.
- `git` is whitelisted to a small set of safe subcommands (no `push`, no `reset --hard`, no `rebase`).
- **Hooks (#184).** Commands you configure in `agent.hooks.*` run at four points: `pre-tool`, `post-tool`, `turn-start` and `turn-end`. With none configured, nothing changes.
  - **How a hook runs.** Its command is an argv list, never a shell string. It runs in the workspace, as the same user and in the same container (if any) as Penstock itself; there is no separate sandbox. Its environment is minimal: `PATH`, `HOME`, `LANG` and `PENSTOCK_HOOK_EVENT`, never the agent's own, so no Penstock credential or token reaches it. It does see the call verbatim: the tool's arguments (a whole `write_file` content, say) and, after the call, its result, which can hold workspace data. A hook is code you run as the agent's user, so treat its command like a shell command. A child process a hook leaves running is killed when the hook ends, so a hook should not background work. That cleanup is best effort: a hook that starts a background child and exits at once may end before the child is seen. If that child keeps the hook's output open, the hook counts as failed (a `pre-tool` call is denied after 2 s), and the child may outlive it. Output over 64 KB counts as a failure (`output-too-large`).
  - **What it reads and answers.** It reads one JSON object on stdin: the event, the session, the user, and for tool events the call id, tool, arguments and (post-tool) result. It may answer with one JSON object on stdout. Empty output means "no opinion".
  - **`pre-tool`** runs only after the tool policy (#179) has allowed the call, so it can deny a call (`{"decision":"deny","reason":"…"}`, and the model sees the reason) but never allow one the policy denied or the user refused. A non-zero exit, a timeout, or output that is not a JSON object also denies: fail closed.
  - **`post-tool`** may answer `{"note":"…"}`, and the note is appended to the result the model sees (a lint or test summary, say). Its failures are logged and ignored.
  - **Every run** is audited as `hook.ran` (event, index, tool, outcome: `allow`, `deny`, `pass`, `note`, `error`, `timeout` or `output-too-large`, and duration).
  - **Hooks come only from Penstock's own configuration.** A repository cannot install one. The same hooks run in ACP mode.
  - **Example:** a pre-tool hook on `write_file` that refuses paths outside `src/`:

    ```yaml
    agent:
      hooks:
        pre-tool:
          - command: ["/opt/penstock-hooks/src-only.sh"]
            tools: [write_file, edit_file]
    ```

    ```sh
    #!/bin/sh
    # src-only.sh: allow writes under src/ only (needs jq)
    path=$(jq -r '.arguments.path // ""')
    case "$path" in
      *..*) ;;               # no climbing out of src/ with ..
      src/*) echo '{}'; exit 0 ;;
    esac
    jq -n --arg p "$path" '{decision: "deny", reason: ("writes are allowed under src/ only, not " + $p)}'
    ```
- **Web fetch (#176).** `web_fetch(url)` is off by default. When it is on:
  - It reaches only hosts on `agent.tools.web.allowed-hosts`, on the default port unless an entry names another (`host:port`; an IPv6 literal can be listed only on its default port). An empty list refuses every URL; it never means "allow all".
  - It sends GET only, over https only: no body, no cookies, no credentials.
  - Each redirect is checked against the same list.
  - The whole fetch, body included, is bounded by `timeout-seconds`.
  - It reads text types only (HTML has its scripts, styles and comments stripped), up to `max-bytes`.
  - It refuses a URL that carries a user name or password, or a path or query string over its length cap.
  - It refuses a URL with anything credential-shaped anywhere in it, checked as given and decoded once and twice. The shapes are `draft_episode`'s detector plus key-like parameters (`api_key=`, `key=`, `sig=` …), Slack, GitHub fine-grained and Google key prefixes, and JWTs. It over-matches on purpose, so a harmless `?page_token=…`, `?key=…` or `?session=…` is refused too; a docs site that needs such a parameter cannot be fetched.
  - The result is wrapped in `<untrusted_external_content url="…">…</untrusted_external_content>`. Any spelling of that tag inside the page (any case or spacing, or entity-encoded) is neutralised, so a page cannot close the wrapper early. While the tool is registered, the system prompt tells the model that this content is data, not instructions. That holds in ACP mode too.
  - Every request that went out, each redirect hop included, is audited as `web.fetched` (URL asked, URL fetched, status, bytes), and every refusal as `web.refused` (host and reason only, never the URL; a host that itself looks like a credential is recorded as `(redacted)`).
  - In the ACP profile, every call goes through the editor's permission prompt (kind `fetch`, which always asks). In web and REST mode, set `agent.tools.policy.web_fetch=ask` to approve each call.

  What it does not do:
  - It does not pin DNS: an allow-listed host is trusted to resolve where it should.
  - The length caps are the only check for workspace text leaving in a URL, so a short piece can still go out. Keep the list to hosts you trust.
  - There is no search yet. `web_search` waits for a provider choice.
- **Tool policy (#179).** `agent.tools.policy.<tool>` sets each tool to `allow`, `ask` or `deny`. With no entry a tool is `allow`, so by default every registered tool runs unasked in web and REST mode, `write_file`, `edit_file`, `shell`, `git` and `pod` writes included, exactly as before the setting existed. `deny` never runs the call and tells the model why. `ask` pauses the turn: the chat stream sends an `approval` event, `GET /api/sessions/{id}/approvals` lists the waiting calls (for clients of the non-streaming `/api/chat`), and `POST /api/sessions/{id}/approvals/{callId}` with `approve` or `deny` resumes it. No answer within `agent.tools.ask-timeout-seconds` (120) counts as a denial. Every `ask` asks; nothing is remembered between calls. Only the session's owner can answer. In the ACP profile, `ask` goes to the editor's own permission prompt, and a call approved there is not asked again by the mutating-tool gate. Each decision is audited as `tool.permission` (`denied`, `asked`, `allowed` or `timed-out`, with who answered: `policy`, the user, or `editor`). A call its policy allows outright writes no `tool.permission` event; its `tool_call` event is the record. Waiting holds a thread from the SSE pool (or the request thread for `/api/chat`) until the answer or the timeout. A client that disconnects does not release the wait early, so its call times out to `deny`. A stream lives 10 minutes in all, so a turn with several slow answers can run out of stream before it finishes. `tool.permission` events are written asynchronously, so read them by `callId` rather than relying on their stored order.

## Authentication and the audit log

Auth ships **on**. Turning it off (`AGENT_AUTH_ENABLED=false`, as the compose dev/demo stack
does) leaves no security context, so every row in `audit_events` records the user as
`anonymous` — the log tells you *what* happened, not *who* asked for it.

If the audit trail is a reason you are running this, keep auth on and use OIDC:

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
Those two — the `pod` tool's calls and `OpenAiProvider`'s model calls — also carry the inbound
`X-Request-Id`, so the pod's receipt, a gateway's log and this service's `audit_events` all name
the same request. No other outbound call does: not the other providers, and not `jira_search`.

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

## Memory store in a pod

`requests/`, `references/` and `patterns/` (this project's own build memory — see
[patterns/README.md](patterns/README.md)) can be mirrored into the owner's own Cistern pod
under `/memory/`, so a colleague or a hosted session can read a pattern and leave a verdict
without ever getting a copy of the repository. Three scripts:

- `scripts/memory-publish.sh [--dry-run] [--delete] [--base <url>]` — mirrors the three
  folders via `cistern sync` (unreleased, Cistern `main` only; `CISTERN_CLI_JAR` must point at
  a jar built from a `main` commit that carries it), running `scripts/check-cards.sh` first.
- `scripts/memory-grant.sh reviewer <webid> --as <slug>` grants a reviewer **read** on
  `/memory/patterns/` and **read + write** on their own `/memory/verdicts/<slug>/` —
  never on `/memory/requests/` or `/memory/references/`.
  `scripts/memory-grant.sh agent <webid>` grants a hosted session's own identity **read** on
  `/memory/` as a whole; pair it with `agent.tools.cistern.*` above so that session's `pod`
  tool calls present that identity.
- `scripts/memory-revoke.sh <webid> [--as <slug>]` ends either grant in one command and
  prints the receipts query to see what the grantee did while it was open.

A verdict is one file, `/memory/verdicts/<slug>/<pattern-id>-<YYYY-MM-DD>.yaml`, documented in
[patterns/README.md § "Review by grant"](patterns/README.md#review-by-grant) — it changes
nothing by itself; `revise`/`retire` still become a PR the owner opens after reading it.
`scripts/pattern-digest.sh --pod` reads those verdicts and the pod's read receipts straight
from the owner's own token and adds them to the usage digest.

All three scripts read `CISTERN_TOKEN` (the owner token) from the environment and never print
it. `.cistern-sync.json`, the state file `cistern sync` leaves in each mirrored folder, is
local state, never committed (see `.gitignore`).

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

**Compaction (opt-in, `agent.llm.context.policy=compact`, #173).** Once the estimated request
passes `compact-threshold-ratio` of `max-tokens-per-request` (0.6 by default), the older messages
are folded into one summary written by the same model: files touched, decisions, open
sub-tasks and unresolved tool errors. It goes in the system prompt, and the newest
`compact-keep-recent` messages go verbatim. The stored session history is never changed, only
what the model is sent; the summary call's tokens count against the budget like any other; a
line in the summary that looks like a credential is removed before it is sent; and each
compaction is audited as `context.compacted`. The summary is written by the configured provider
and model with no tools offered, so it is one extra model call each time it fires;
`compact-keep-recent` counts messages, not turns; and the size estimate (four characters per token)
includes the tool schemas sent on every call. The summary lasts for one request: the next message
in a long session folds the older history again from scratch, one more summary call. If the
summary call fails or comes back empty, the request goes out unfolded (it may then exceed the
provider's limit), the failure is audited as a failed model call, and compaction is not tried again
until the next request. Whether compaction lets the longer task in `docs/real-repo-validation.md`
finish has not been measured yet.

Lower them for a shared or metered deployment; raise them if you are driving a
local model where tokens are effectively free.
