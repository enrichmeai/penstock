# Penstock in JetBrains Air and Zed

Penstock speaks the [Agent Client Protocol](https://github.com/agentclientprotocol/java-sdk)
(ACP) — a JSON-RPC protocol that lets an editor run an external program as its
coding agent over stdin/stdout, instead of the editor's own built-in agent.
The editor starts Penstock as a subprocess, sends it prompts, and streams back
Penstock's replies and tool calls; this page covers registering Penstock as
that agent in JetBrains Air and in Zed, what you'll see the first time it asks
to touch a file or run a command, and where to look if nothing happens.

## Before you start

You need a Penstock jar and a workspace directory. Download
`penstock-<version>.jar` from a [release](https://github.com/enrichmeai/penstock/releases)
(`release.yml` publishes it under exactly that name), and pick a directory on
disk for Penstock to operate on — the editor must open that same directory,
or `--acp` refuses the session. Every tool path is rooted at
`agent.workspace` (the sandboxing invariant in `CLAUDE.md`), and `--acp`
mode requires the editor's `cwd` to resolve to it exactly.

The launch command is the same for both editors:

```bash
java -jar /path/to/penstock-<version>.jar --acp
```

`AgentApplication` treats `--acp` (or `AGENT_MODE=acp`) as the switch to ACP
stdio mode instead of the HTTP server. `--acp` mode only supports
`agent.storage.type=memory` (the default — leave `AGENT_STORAGE_TYPE` unset),
so set `AGENT_WORKSPACE=/path/to/your/workspace` and whichever
`GITHUB_COPILOT_TOKEN` / `ANTHROPIC_API_KEY` / `OPENAI_API_KEY` the LLM
provider needs, same as any other run.

## JetBrains Air

Source: [jetbrains.com/help/air/select-agents-and-models.html](https://www.jetbrains.com/help/air/select-agents-and-models.html)
(section "Add your own agent" / "Add an ACP-compatible agent").

Custom ACP agents are a feature of the **Air app** (the local desktop
client) — the browser and in-IDE agent selectors only list agents your
organization has enabled, with no way to add your own. In the Air app:

1. The agent must already be on the machine running the Air app — there's no
   remote install step, so put the jar somewhere local first.
2. In the task toolbar, click the agent selector, then **Add ACP Agent**. The
   Air app opens its `acp.json` file (stored in the Air app's own
   configuration folder; it applies to every task on that machine, not just
   one project).
3. Add an entry for Penstock under `agent_servers`:

```json
{
  "agent_servers": {
    "Penstock": {
      "command": "java",
      "args": ["-jar", "/path/to/penstock-<version>.jar", "--acp"],
      "env": {
        "AGENT_WORKSPACE": "/path/to/your/workspace",
        "GITHUB_COPILOT_TOKEN": "<your-token>"
      }
    }
  }
}
```

4. Save the file. Penstock now appears in the agent selector under the name
   you gave it (`Penstock` above — the JSON key is the label).

Air's docs describe per-agent login as a separate, optional step ("if you are
already logged in to the agent, it works right away; if not, the agent
prompts you to log in, either through a browser or with a command you run in
the terminal") — Penstock doesn't declare an ACP auth method, so this step
doesn't apply; the credential Penstock needs is the `env` block above.

## Zed

Source: [zed.dev/docs/ai/external-agents](https://zed.dev/docs/ai/external-agents).

1. Open agent settings (`agent: open settings` from the command palette), go
   to **External Agents**, click **Add Agent** → **Add Custom Agent**. Zed
   opens your settings file with an `agent_servers` entry ready to fill in.
2. Fill in the entry:

```json
{
  "agent_servers": {
    "Penstock": {
      "type": "custom",
      "command": "java",
      "args": ["-jar", "/path/to/penstock-<version>.jar", "--acp"],
      "env": {
        "AGENT_WORKSPACE": "/path/to/your/workspace",
        "GITHUB_COPILOT_TOKEN": "<your-token>"
      }
    }
  }
}
```

3. Save. Penstock appears in the new-thread menu in the Agent Panel.

Zed's `type: "custom"` field has no equivalent in Air's `acp.json` — leave it
out of the Air entry above; Air only takes `command`/`args`/`env`.

## What the permission prompt means

Reading tools (`read_file`, `list_dir`, `glob`, `grep`, and the `pod` tool's
read/list/receipts operations) run without asking. Everything that changes
something — `write_file`, `edit_file`, `shell`, `git`, and the `pod` tool's
`write` operation — goes through ACP's `session/request_permission` first:
the editor shows you the tool name, a short summary of what it's about to do
(the file path for a file edit, the command line for `shell`, the subcommand
for `git`), and four options:

- **Allow once** — run this one call, ask again next time.
- **Always allow** — run this and every later call to the same tool for the
  rest of this session (not remembered across sessions).
- **Reject** — refuse this one call; Penstock sees a "Refused" result and
  carries on the conversation.
- **Always reject** — refuse this and every later call to the same tool for
  the rest of this session.

Under the hood this is `PermissionGate` plus `AcpToolPermissionConfig`
(`src/main/java/com/example/agent/acp/`): `shell` and `git` are gated no
matter what, and `ShellTool`'s blocked-command patterns and `GitTool`'s
subcommand whitelist still apply underneath — a permission grant doesn't
widen what the tool is allowed to do, only whether it runs at all.

## Where the `pod` tool gets its credential

If your workspace includes a [Cistern](https://github.com/enrichmeai/cistern)
pod integration, the `pod` tool's outbound credential is controlled the same
way in ACP mode as everywhere else — see
[Acting as the signed-in user](../README.md#acting-as-the-signed-in-user) in
the main README for `agent.tools.cistern.credential-mode` and the per-user
credential properties.

## Troubleshooting: "nothing happens"

ACP talks JSON-RPC over stdout; anything else on stdout (a stray
`System.out.println`, a library that logs to stdout by default) corrupts the
stream and the editor just sits there with no visible error. Run the same
command by hand and watch stderr, not stdout:

```bash
java -jar /path/to/penstock-<version>.jar --acp
```

A healthy process prints nothing on stdout until the editor sends its first
message, and logs startup info to stderr. If you see Java exceptions on
stderr, fix those first; if stdout has anything on it before the editor
connects, something in the classpath is writing where only ACP frames are
allowed to go.

## Verified with

Not yet verified against a real editor — the owner runs this check (see
[#63](https://github.com/enrichmeai/penstock/issues/63)) and updates this
section with the IDE name, version, date, and a screenshot of the permission
prompt above.
