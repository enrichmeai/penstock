# Running the agent fully offline with Docker Compose

The stack in `docker-compose.yml` runs the agent *and* its LLM on your machine.
After a one-time model pull it needs no internet and no API keys.

```
┌─────────────────┐        ┌──────────────────┐
│ agent           │  HTTP  │ ollama           │
│ Spring Boot     │───────▶│ llama3.2:3b      │
│ :8080 → :8090   │        │ (compose network)│
└────────┬────────┘        └──────────────────┘
         │ bind mount
         ▼
   ./agent-workspace      ← the only directory the agent may touch
```

## Quick start

```bash
mkdir -p agent-workspace              # the folder the agent is allowed to edit
docker compose up -d --build          # first run pulls the model (~2 GB)
open http://localhost:8090            # web UI
```

`ollama-pull` is a one-shot service that fetches the model into the
`ollama-models` volume and exits. It is the only step that needs internet; it
skips the download if the model is already present. Everything after that runs
offline — disconnect and it still works.

Ports: the UI is on **8090** by default (8080 inside the container). Override
with `AGENT_PORT=9000 docker compose up -d`. The bundled Ollama is deliberately
not published to the host, so it cannot collide with an Ollama you already run.

## Proving it works

```bash
./scripts/demo-offline.sh
```

The script checks health, drives one full tool-use turn through `POST /api/chat`,
and verifies the file actually appeared in `agent-workspace/` on the host. A
successful run looks like:

```
[1/5] health                    ok (provider=ollama, 9 tools)
[2/5] model is served locally   ok (llama3.2:3b in the ollama container)
[3/5] tool-use turn             ok (write_file → hello.txt)
[4/5] file on host              ok ("Hello from a fully offline agent")
[5/5] history persisted         ok (USER, ASSISTANT, TOOL, ASSISTANT)
```

## Choosing a model — this is the part that actually decides whether it works

A model must emit tool calls in Ollama's structured `tool_calls` field. Many
models advertise `tools` in `ollama show` and still return the call as prose,
which historically left the agent loop with nothing to execute. The agent now
recovers the two common text formats (see `TextToolCallParser`), so these models
work — but native structured output is still more reliable.

Measured by sending this repo's real payload (all 9 tool schemas, ~1.6k prompt
tokens, the question "What files are in the workspace directory?") to each model
four times and counting how often the call came back in the structured field:

| Model | Size | Native `tool_calls` | Notes |
|---|---|---|---|
| `llama3.2:3b` | 2.0 GB | 4/4 | Default. Small enough for a modest Docker VM, but the weakest reasoner — it sometimes invents a tool name. |
| `llama3.1:8b` | 4.9 GB | 4/4 | Same reliability, better instruction-following. Worth it if your VM has the room. |
| `qwen3-coder:30b` | 18 GB | 1/4 | Strongest writer, but usually emits Qwen's `<function=…>` XML; works only because the fallback parser recovers it. Needs a GPU host. |
| `qwen2.5-coder:3b` | 1.9 GB | 0/4 | Always returns fenced JSON as text. The fallback recovers the well-formed cases, but it is the least dependable — avoid. |

Sizes are what Ollama reports; the 30B figure is also roughly its resident
memory, which is why it does not fit a default Docker Desktop VM.

Change it with `OLLAMA_MODEL=llama3.1:8b docker compose up -d`.

## Context window — set it or lose your system prompt

Ollama does not use a model's full context window unless you ask. Left alone it
applies its own default and **silently discards whatever does not fit**, starting
at the front of the payload: the system prompt and the tool schemas. Nothing
errors and nothing is logged, so the only symptom is the agent quietly getting
worse.

Measured here with `llama3.2:3b` and a ~7,900-token prompt carrying a marker at
the very start:

| Path | `num_ctx` | Tokens actually evaluated | Recovered the marker? |
|---|---|---|---|
| Bundled container | default | **2050** | no — answered about something else entirely |
| Host Ollama 0.12.11 | default | **4096** | no — same |
| Either | 8192 / 16384 / 32768 | 7050 | yes |

The model itself advertises a 131,072-token context and ships no `num_ctx`
override, so this is purely about what the client asks for.

The agent therefore sends `options.num_ctx` on every request, from
`agent.llm.ollama.num-ctx` (`OLLAMA_NUM_CTX`), defaulting to **16384**. That is
measured safe for a 3B model in an 8.2 GB Docker VM — 32768 also ran without an
OOM kill. For scale, a bare first turn already costs ~1,600 tokens of system
prompt and tool schemas, and a single tool result can add ~4,000 more, so the
bundled default of 2050 is exceeded by about the second tool call of a real
session.

Two things to keep in mind:

- **KV cache is allocated up front and scales with `num_ctx`.** A bigger model
  plus a big window can exhaust the VM: `llama3.1:8b` (4.9 GB resident) at 32768
  is far closer to the edge than `llama3.2:3b` at 16384. If the ollama container
  starts dying, check `docker inspect penstock-ollama --format '{{.State.OOMKilled}}'`
  before suspecting anything else.
- **`num-ctx: 0` omits the option**, letting a server-side `OLLAMA_CONTEXT_LENGTH`
  win instead. Use that if you manage the Ollama server's configuration yourself.

The agent logs a WARN, once per session, when a prompt uses more than 80% of the
window — that is the signal you are about to start losing the front of the
conversation.

## Faster: use an Ollama running on the host

On macOS, containers get no Metal/GPU access, so the bundled Ollama is CPU-only
and a 30B model is impractical inside it (check your VM's ceiling with
`docker info --format '{{.MemTotal}}'`). Point the agent at a host Ollama
instead — still entirely local:

```bash
ollama serve && ollama pull qwen3-coder:30b     # on the host
docker compose -f docker-compose.yml -f docker-compose.host-ollama.yml up -d
```

## Notes for anyone changing this stack

- **`/tmp` must be mounted `exec`.** sqlite-jdbc unpacks a native `.so` into
  `/tmp` and `dlopen()`s it. A default (noexec) tmpfs makes Flyway die with
  `UnsatisfiedLinkError` — and because the container is otherwise read-only, the
  failure is easy to misread as a permissions problem.
- **SQLite lives on a named volume, not a bind mount.** On Docker Desktop a bind
  mount is proxied through gRPC-FUSE, where SQLite's locking is unreliable.
  Extract the database with `docker compose cp agent:/data/agent.db ./agent.db`.
- **The SQLite pool is capped at one connection.** SQLite allows a single writer,
  and `AuditLogger` writes from a different thread than the agent loop; a larger
  pool produces `SQLITE_BUSY` partway through a conversation.
- The agent container keeps its hardening (`read_only`, `cap_drop: ALL`,
  `no-new-privileges`, `pids_limit`, 1 GB cap). Don't copy those onto the Ollama
  service — it needs several GB to hold a model.

## The agent's own toolchain

The runtime image carries a **JDK and a seeded Gradle home**, not just a JRE, so
the agent can compile and test the code it edits. Without that it can only write
plausible-looking code and hope — see `docs/real-repo-validation.md`, where it
invented a Spring annotation that does not exist and had no way to find out.

What ships in the image:

- `eclipse-temurin:21-jdk-jammy` (so `javac` exists at all)
- `/opt/gradle-seed` — the Gradle wrapper distribution plus every dependency this
  project resolves, including the test-runtime ones

`docker-entrypoint.sh` copies the seed into `GRADLE_USER_HOME` on first start.
It is copied rather than used in place because Gradle writes locks and caches
into its home and the container filesystem is read-only; the destination is the
`agent-gradle` volume, so the copy happens once rather than on every restart.

Verified with the network genuinely absent:

```bash
docker run -d --name probe --network none --read-only \
  --tmpfs /tmp:exec --tmpfs /home/agent/.cache \
  -v toolchain-gradle:/home/agent/.gradle -v toolchain-data:/data \
  -v /path/to/a/fresh/clone:/workspace \
  -e AGENT_STORAGE_TYPE=sqlite penstock:latest
docker exec probe bash -lc 'cd /workspace && rm -rf build .gradle && ./gradlew test'
# BUILD SUCCESSFUL in 47s — 4 actionable tasks: 4 executed
```

**Cost: the image goes from 519 MB to 1.39 GB.** That buys an agent that can
verify its own work. If you only ever use it to answer questions about code, the
JRE image was cheaper; if you want it to finish a change, this is the price.

Two things had to change to make a build survive the tool layer:

- **The image ships Gradle on `PATH`, so the agent runs `gradle test`, not
  `./gradlew test`.** The wrapper jar is deliberately not in the repository
  (`CLAUDE.md`, `bootstrap.sh`, and a CI step all say so), and the agent could
  never run `bootstrap.sh` to fetch one: `curl` and `wget` are on the shell
  block-list, and the whole point is to work offline. Putting Gradle in the image
  solves that without reversing the project's decision — the image build still
  bootstraps the wrapper the normal way for its own compile step.
  **The image's Gradle is 9.8.0** (8.14.5 before #120: Gradle 8's distribution
  ships a BouncyCastle with a critical CVE, fixed only in Gradle 9). A workspace
  whose build script relies on something Gradle 9 removed fails under `gradle test`
  in the container; it needs its own wrapper, and the network to fetch it.
- **The shell timeout default is now 600s** (`AGENT_TOOLS_SHELL_TIMEOUT_SECONDS`).
  At 60s no real build could finish. The timeout is now enforced by a watchdog:
  previously output was drained to EOF *before* the clock was checked, so a
  command that kept printing held the turn for its entire run and then reported
  success — the timeout never fired for exactly the commands that needed it.
  `ShellTool` also keeps the head *and tail* of long output, because a build
  prints dependency noise first and the reason it failed last.

## "The agent just answers, it never does anything"

Almost always the model, not the agent. The loop acts only on structured
`tool_calls`; a model that answers in prose produces a turn that looks
successful — assistant text, no error, nothing done.

Check which of the three is happening:

```bash
curl -s localhost:8090/actuator/prometheus | grep llm_tool_call_format_total
```

| `format` label | Meaning |
|---|---|
| `structured` | Working. The model uses Ollama's `tool_calls` field. |
| `recovered` | Working, but only because `TextToolCallParser` salvaged a call the model emitted as text. Best-effort — it will not catch every format. Prefer a model that scores `structured`. |
| `none` | No tool call. Normal for a plain question; if it is *all* you ever see, the model cannot make tool calls with this payload. |

`/actuator/health` says the same thing in words, under `llmProvider.toolCalls` —
including the distinction between "no completions yet" and "no tool call has ever
been produced", which call for opposite reactions: wait, versus change the model.

The logs carry the other half. A recovered call logs at INFO naming the model. A
reply that *looks* like a tool call but could not be parsed logs a WARN with the
first 200 characters, which is the case that would otherwise pass in silence.

If you are stuck on `none`, see the model table above — `qwen2.5-coder` scores
0/4 and was once the shipped default.
