# stdio-json-rpc-agent

How Penstock became a selectable agent inside JetBrains Air and Zed: an existing
tool-use loop gets a second, stdio-JSON-RPC front door next to its HTTP one, so an
editor can drive it directly instead of through the web UI or REST API.

Built from `src/main/java/com/example/agent/acp/` and
[`docs/ide-agents.md`](../../docs/ide-agents.md) as shipped in v0.3.0
([#59](https://github.com/enrichmeai/penstock/issues/59),
[#65](https://github.com/enrichmeai/penstock/pull/65),
[#66](https://github.com/enrichmeai/penstock/pull/66),
[#67](https://github.com/enrichmeai/penstock/pull/67)). See
`requests/acp-in-ide.yaml` for the request this pattern serves, and
`references/acp-protocol-v1.yaml` / `references/acp-java-sdk-0.18.0.yaml` for the
protocol and SDK facts it relies on.

## When it applies

- Your app already has an in-process agent loop (something that takes a user
  message and tool specs, and produces either more tool calls or a final
  reply) that you want an editor to drive directly, without going through
  HTTP.
- The editor speaks [ACP](../../references/acp-protocol-v1.yaml) (or another
  stdio-JSON-RPC agent protocol shaped the same way: `initialize` once,
  `session/new` per session, a streamed prompt/response, a permission
  round-trip before anything mutating runs).
- Your app can run with no web server at all for this mode — a stdio agent
  and an HTTP server must not both try to own stdin/stdout.

## When it does not

- The editor's protocol doesn't run over a local subprocess (e.g. it only
  talks to a remote HTTP/WebSocket agent) — then you want the SDK's remote
  transport, not this pattern's stdio one.
- Your agent loop needs per-request credentials from the editor (an OAuth
  token the editor holds, say) — ACP's stdio launch has no bearer to carry;
  Penstock's version is deliberately read-only-by-default and gates writes
  through the permission round-trip instead of trying to forward a
  credential that doesn't exist in this mode (see
  `com.example.agent.acp.AcpSessionBridge`'s Javadoc).
- You need multiple concurrent editor connections to one running process.
  `AcpAgentSupport.run()` blocks on one stdio transport; running N editors
  means N subprocesses, not N sessions in one.

## The decision and why

Three things have to be true simultaneously for stdio ACP to work at all,
and each one failing silently is worse than a crash:

1. **One entry point, not two racing.** The stdio transport and the normal
   HTTP server must not both start — the mode switch
   (`AgentApplication.main`, this pattern's `ModeSwitchSkeleton`) decides
   *before* the framework picks its application type, because by the time a
   normal bean could read a flag, the context type is already chosen. Force
   "no web server" at the same place you force the mode's profile on.
2. **stdout is sacred.** The instant any other entry point is possible
   (prod logging, a debug `println`, a library default), stdout — the only
   channel the editor reads JSON-RPC frames from — gets corrupted, and the
   failure mode is total silence in the editor, not an error. Redirect
   *all* logging to stderr for this mode specifically (`logback-spring.xml`'s
   `acp & !prod` / `prod & acp` profiles; this pattern's
   `stderr-logging-profile-snippet.xml`), and don't key the normal-mode
   appender on `default` — activating any other profile drops `default` too.
3. **The sandbox root doesn't move.** `session/new.cwd` is the only
   workspace an editor can offer per session; accepting it as a per-session
   root would let the editor redefine what your path-sandboxing considers
   "inside" (Penstock: `WorkspacePath`'s root). Refuse any `cwd` that doesn't
   resolve to the one workspace this process was already configured with
   (`AcpModeSkeleton.requireWorkspaceCwd`).

Mutating tool calls route through the protocol's own permission prompt
(`session/request_permission` for ACP) rather than a second gate your app
invents — the editor already has a UI for "allow once / always allow /
reject / always reject" and the user expects to see it there. Read-only
tools skip it; see `com.example.agent.acp.PermissionGate` for where that
split lives in Penstock (not reproduced in this skeleton — it depends on
your own tool registry's shape).

## What varies per use

See `manifest.yaml`'s `inputs:` for the short list; spelled out:

- The mode's trigger (`<your-mode-flag>` / `<YOUR_MODE_ENV_VAR>`) and its
  internal profile name (`<your-profile-name>`).
- The `@AcpAgent` identity (`<your-agent-name>`, `<your-protocol-version>`).
- How your app already resolves its sandboxed root
  (`<YourWorkspaceType>` — Penstock uses `java.nio.file.Path`, resolved from
  `agent.workspace`).
- How your app already creates a session/turn object
  (`<your-session-id>` stands in for `AcpSessionBridge.create(...)` —
  whatever identity a stdio launch has, which is the OS user, not an HTTP
  principal).
- Which of your tools are read-only (run unprompted) versus mutating (go
  through the permission round-trip) — this skeleton doesn't include a
  `@Prompt`/`@Cancel` handler at all, because that's 100% your existing
  agent loop; see `com.example.agent.acp.AcpMode.prompt` for how Penstock's
  version bridges into `AgentService.chatStreaming`.
