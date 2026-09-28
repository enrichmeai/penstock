# The consent demo: a grant, a read, a revocation, a refusal — in the agent's own audit log

This is issue #8 run for real, against the governed-AI stack in
[`enrichmeai/governed-ai-demo`](https://github.com/enrichmeai/governed-ai-demo) (private; ask
for read access). Beat 3 of that demo is the granted read and beat 7 is the revocation; this
page shows what Penstock itself records for each, so the sequence is legible without opening
Cistern's receipts or a server log.

## The sequence

| Step | Who | What | Penstock records |
| --- | --- | --- | --- |
| 1. Grant | the steward (pod owner) | writes `/support/.acl` granting Bob's WebID read on `/support/` (`seed.sh grant`) | nothing — Penstock is not told about grants and does not ask |
| 2. Read | Bob, signed in to Penstock | asks the agent to read `/support/playbooks/outage.md`; the `pod` tool forwards Bob's own bearer to Cistern (`credential-mode=forward`) | `tool_call` row, `user_id=bob`, `outcome: OK` |
| 3. Revoke | the steward, out of band | `DELETE /support/.acl` → 204, while Bob's session stays open | nothing — same as step 1 |
| 4. Read again | Bob, same session | the same request; Cistern answers 403 | `tool_call` row, `user_id=bob`, `outcome: REFUSED` |

No agent-side enforcement exists anywhere in this sequence. Penstock holds no allow-list,
runs no pre-check against the pod and caches no decision; it presents the user's credential
and reports what the pod said. An agent that policed its own grants would prove nothing.

## What the audit rows look like

Run of 2026-09-11 against the stack in `governed-ai-demo`, `./demo.sh 7` (which runs beat 3
first for Bob's session, then revokes). Session `4b05f2e8-e87b-4606-938a-a176d61aab39`, read
back as Bob through `GET /api/sessions/{id}/audit`, verbatim:

```json
{"timestamp":"2026-09-11T05:13:09.600Z","eventType":"tool_call",
 "detail":{"tool":"pod","args":{"path":"/support/playbooks/outage.md","type":"read"},
           "outcome":"OK","ok":true,"contentBytes":422}}

{"timestamp":"2026-09-11T05:13:15.646Z","eventType":"tool_call",
 "detail":{"tool":"pod","args":{"path":"/support/playbooks/outage.md","type":"read"},
           "outcome":"REFUSED","ok":false,"contentBytes":217}}
```

The same two requests, from Cistern's side, in the same run:

```
ALLOWED           READ  2026-09-11T05:13:09.5826  agent=bob#me  rule=/support/.acl  requestId=demo-061259-b3
DENIED_FORBIDDEN  READ  2026-09-11T05:13:15.5803  agent=bob#me  rule=-              requestId=demo-061259-b7
```

Same path, same session, six seconds apart, and the second one refused: that is the whole
claim. Before this change both rows read `ok: true` with a byte count, and the only difference
between them was the number.

Two things about that output are worth stating exactly, because they are easy to overread.

**The request id joins the two sides through the log line, not through the audit row.**
`X-Request-Id` is forwarded by `CisternTool` and appears in Penstock's MDC:

```
05:13:09.597 INFO [demo-061259-b3] c.e.agent.tools.ToolRegistry - Tool pod (352 ms) outcome=OK
05:13:15.644 INFO [demo-061259-b7] c.e.agent.tools.ToolRegistry - Tool pod (84 ms) outcome=REFUSED
```

Those ids are the ones in Cistern's receipts above, so the join is real and demonstrable — but
it needs a log line to complete it. The audit row itself carries the outcome and not the request
id. Putting it in the row is a small follow-up and is not done here.

**The endpoint does not project `user_id`.** It returns `timestamp`, `eventType` and `detail`
only. The row *stores* the user (`AuditEventEntity`), and the endpoint is owner-scoped —
`agent.requireSession(id)` throws on cross-user access, so Bob reads Bob's session and no other.
Attribution is therefore enforced rather than displayed here.

Both rows are attributed to `bob` because identity is resolved once on the request thread
and travels with the session (CLAUDE.md, *Identity on background threads*); the agent loop
and the audit writer run on threads that have no `SecurityContext` and never read one.

`outcome` is the field of record. `ok` is kept for older readers and is true only for `OK`,
so a reader that still looks at `ok` sees the refusal as not-ok rather than as a success —
which is what #8 found wrong: before this change a refused read was audited as `ok: true`
with the refusal text's byte count, indistinguishable from a granted one.

## How fast the revocation lands

On the very next request. Cistern decides every request against the ACL as it is at that
moment and caches nothing — `cistern-wac/.../AccessControl.java` ("nothing here is cached. A
decision does not outlive the request that produced it") and
`cistern-webflux/.../AuthorizationFilter.java` ("only decision point, and nothing here
caches what it said"). On Penstock's side `CisternTool` has no cache either: every call is a
fresh `WebClient` request carrying the credential of the moment (verified by reading the
class; there is no state between calls beyond the configured base URL and mode). The
latency between the steward's `DELETE` and the refusal is therefore one request's
round trip, with no propagation delay to wait out.

Measured on the run above: the granted read completed at `05:13:09.58` and the refused one at
`05:13:15.58`, with the steward's `DELETE /support/.acl` between them and Bob's session never
reopened. The six seconds are almost entirely the model's turn — Cistern's own two decisions are
84 ms and 352 ms of tool time — so what the numbers show is not a propagation delay but its
absence: the next request that reached the pod after the rule was deleted was refused by it.

## Where to see the same thing from the other side

`./demo.sh 3` and `./demo.sh 7` in `governed-ai-demo` print, for the same requests, Cistern's
receipts: outcome, the rule that decided, the agent's WebID and the `X-Request-Id` that
Penstock sent — the same id as in Penstock's log line for the turn. `./demo.sh 8` prints the
receipts for the whole run.

## Acceptance list of #8, against this run

1. **A scoped grant issued to a named user for one capability** — `/support/.acl` grants
   `https://acme-telecom.example/people/bob#me` read on the `/support/` container and
   nothing else (`grants/today/support.acl.ttl`); payroll stays refused (beat 2).
2. **The agent invoking that capability successfully, with the audit row naming the real
   user and the server** — the `OK` row above: `user_id=bob`, `tool=pod`, the path; the
   server side of the same request is Cistern's receipt with the same `X-Request-Id`.
3. **Revocation performed out-of-band while the session stays open** — the steward's
   `DELETE`, with Bob's session id unchanged between the two reads.
4. **The next invocation refused, with the refusal surfaced in the agent UI rather than
   appearing as a generic tool error** — the `REFUSED` row above; in the UI the result is
   drawn as "Refused by the pod owner's rule" in its own colour, not as a tool error, and
   the model is told it is a decision, not a fault.
5. **The audit log telling the whole story end to end** — the rows above, in one session,
   read through `GET /api/sessions/{id}/audit` as Bob.
