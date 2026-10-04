# Implementation Plan: Agent Approvals and MCP — A Tool That Waits for a Person, Tools That Reach Other Services

**Branch**: `029-agent-approvals-and-mcp` | **Date**: 2026-10-04 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/029-agent-approvals-and-mcp/spec.md`

## Summary

An agent gains four things. A **tool can require approval**: when the model calls it the tool
does not run, the agent records an approval request and gives it to its caller in place of an
answer, and a person's decision — approved, refused, or the platform's own at a declared time
limit — lets it go on. An **autonomous agent** waits the same way, and the wait uses none of its
task's budget. A **tool can call another service** as the agent's service, so the called
service's ACL admits it by name. And an agent can list **MCP servers**, whose tools are read at
start and offered to the model beside its own, with a credential taken from a variable, approval
declarable per server, and **result guardrails** that check what a server answers before the
model is told.

Technically: requiring approval is a property of `FunctionTool` (R1). A request agent's wait is
a **suspended turn** in the session entity, kept apart from its history so that a failed turn
still leaves no trace and compaction never touches it (R2); resuming runs the handler again to
rebuild its effect, which is safe because an effect is inert data (R3). An autonomous agent's
approval requests are two new events on its instance record, with a resume point that settles
every tool call of the last model message that has no result (R8). The caller is answered with
an outcome carried in the existing reply's metadata (R6). Expiry is a platform timed action over
the timers table (R10). The MCP client is the platform's own, over the JDK's HTTP client, with a
second transport through `ServiceClients` for a server that is an ankka service (R13, R15). A
result guardrail is the existing `Guardrail` with a third check, declared apart and run through
`Guardrails.check` in the one function both loops run a tool through (R17). A process sees all
of it as additions to discovery, one new stage of `CheckGuardrail`, one reply case and one call,
under one minor version (R18); the sidecar hosts the same loops, so nothing is implemented twice.

Planning found six things the spec did not have, and the spec is amended for each:

- **The tool name `mcp:<server>:<tool>` cannot be sent to a model.** A provider accepts letters,
  digits, underscores and hyphens. The name is `mcp__<server>__<tool>`, and a server's name has
  no underscore so it parses one way (R14).
- **A header's variable would be set in the wrong container.** For a process-hosted service the
  platform's program connects to the MCP server, and a descriptor's variables go to the process.
  The variables are prefixed `ANKKA_MCP_`, which the one declaration of platform variables
  routes to the platform's program, as it routes `ANKKA_AUTH_` (R16).
- **A server's address belongs to the environment.** It may be given by `ANKKA_MCP_<SERVER>_URL`,
  which replaces the definition's (R16).
- **A request agent's turn that is cut off after its last decision is ended, and its tool is not
  run again.** FR-004's "exactly once" holds for a restart before the decision; after it, the
  choice is at most once (R4).
- **A time limit needs `TimerRuntime`.** Without it the declaration is refused, not ignored (R10).
- **A stream needs a named event** to end with an approval request, which `http`'s stream routes
  cannot send today (R7).

## Technical Context

**Language/Version**: Scala 3 on JDK 21 (`core`, `sdk`, `runtime`, `http`, `agent`, `testkit`,
`sidecar`, `controlplane-api`, and the operator, which compiles `PlatformVariables`); Python ≥
3.12 (`sdks/python`); TypeScript on Node ≥ 22 (`sdks/typescript`); Rust, edition 2024
(`sdks/rust`, its protocol copy and message literals only); protobuf (`protocol`)

**Primary Dependencies**: none added, in any language. The MCP client uses the JDK's
`java.net.http` and the agent module's own `Json`; the service form uses `ServiceClients`, already
in `sdk` and `runtime`; expiry uses `TimerRuntime`, already in `runtime`.

**Storage**: no table and no DDL. The `ankka-session-memory` journal gains three events and
`SessionHistory` one defaulted field; `ToolResultMessage` gains one defaulted field. The
`ankka-agent-instance` journal gains two events and `Working` one defaulted field. One row of
`ankka_timers` per approval request with a time limit.

**Testing**: munit in `agent`, `core`, `sidecar`; `testkit` with Postgres (`ApprovalSuite`,
`AutonomousApprovalSuite`, `McpAgentSuite`, `AgentServiceCallSuite`, the two compatibility suites,
`ResumePointSuite`, `HttpSseSuite`); `ConformanceSuite`'s new `approval.*`, `auto.approval.*` and
`mcp.*` cases against the Scala reference and each SDK; one case added to `EndToEndClusterSuite`
(k3s); a spike against a real MCP server; pytest with mypy; Node's test runner with `tsc`;
`cargo test`; the docs build; the features check.

**Target Platform**: wherever an ankka service runs an agent — in process, or behind the sidecar
— on a developer's machine and in a cluster. Not a module.

**Project Type**: platform libraries and a protocol (the agent module, the runtime's declaration
of variables, the sidecar, two SDKs), documentation

**Performance Goals**: an agent that declares none of this pays one field read on a history it
already fetched, or one query where it reads no history (R5). A wait holds no thread and no actor
(R8). MCP tools are read once per start; a tool call is one HTTP request.

**Constraints**: a tool that requires approval never runs undecided, and a request agent's never
twice; a refused result reaches neither the model nor the session; a header's value is in no
definition, discovery, log or trace; journals written before read unchanged; `agent` still
depends on `core`, `sdk` and `runtime` only, and `http` learns nothing of agents; `runtime` and
the operator see no MCP code; the three SDK copies of `protocol/` identical to the canonical one;
names interned by the recorder stay bounded; `Test / parallelExecution := false` stays;
warning-free; no suite binds a fixed port

**Scale/Scope**: about 12 new Scala source files and 25 changed across seven modules, with 8 new
suites and about 12 changed; 3 protocol files and 2 fixtures; per SDK (Python, TypeScript) about
3 new source files, 8 changed, 3 new test files and the conformance reference changed; Rust about
8 literals; 1 new docs page and about 10 changed; the skills rendered again. 61 scenarios in five
feature files.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

`.specify/memory/constitution.md` is the unfilled template, so the gate is the principles
`CLAUDE.md` states as the codebase's constitution:

| Principle | Status | How the design honours it |
|---|---|---|
| Effects are inert data; the runtime interprets | pass | resuming depends on it: the handler is run again to rebuild its effect, and nothing else of the effect is stored (R3). No effect is added |
| Where two interpreters reduce the same thing, they share one function | pass | one tool runner for both loops holds the approval check and the result guardrails (R17); `Guardrails.check` stays the one place guardrails run; the sidecar hosts the Scala loops, not its own (R18) |
| Module dependency direction | pass | everything new is in `agent`, over `sdk`'s `ServiceClients` and `runtime`'s timers; `http` gains a generic `SseEvent` and no knowledge of agents (R6, R7); the operator still compiles one shared file (R16) |
| `runtime` never sees the generated protocol | pass | the new messages are translated in `sidecar`; `Conversation` gains a stage, not a type from the protocol |
| No classpath scanning; explicit registration; a missing thing fails at start | pass | MCP servers are declared on the agent and connected at start; an unreachable one, an unset variable and a time limit with no timers each fail the start by name (R10, R14, R16) |
| Wire names are a versioning boundary | pass | the suspended turn records the handler's wire name; the session's and the instance's new commands and events are declared strings; `ankka:decide` is reserved and a developer's handler may not take the prefix (R3, R6) |
| The protocol directory is copied whole, and CI proves it | pass | one edit to `protocol/`, three runs of the copy scripts; the Rust literals are fixed in the same slice (R18) |
| Stored forms stay readable both ways | pass | every new field has a default, every event is an added case, both compatibility suites pin them (data-model) |
| Never intern anything unbounded | pass | a tool's span is named by a declared tool or one read once at start; no id or argument is a name (R12) |
| A trace belongs on the thread doing the work | pass | a tool runs on the loop's own virtual thread, inside the handler's span |
| Never touch `ActorContext` from a `Future` callback | pass | the host's new `decide` path follows `start`: a `Future` on a virtual thread piped back to the actor |
| A host that stops under an operation loses the reply | pass | the autonomous host's `decide` is an operation like `assign`, behind the same `Stop` handling |
| A refusal is not a failure | pass | a decision's refusal is a tool result; a guardrail's fault is an exception and never a refusal (R17) |
| Tests are serialised; no fixed port; no literal image tag | pass | `TestMcpServer` and every test service bind an ephemeral loopback port |
| Could this check pass while the thing it checks is false? | pass | three named in R21, and the scripted MCP server is checked against a real one (R13) |
| Each acceptance scenario ends as a test that fails without the feature | pass | 61 scenario references, each mapped to a level in R21 |
| Docs: pages stand alone, samples from tested code, new pages in nav and a skill | pass | R22 |
| Every tracked file claimed by a CI path filter | pass | every new file is under a directory a filter claims |

**Violations to justify**: none against these principles. Where the plan departs from the spec's
wording is under *Complexity Tracking*.

**Post-design re-check**: unchanged. The contracts add no dependency, no module, no table and no
grant.

## Project Structure

### Documentation (this feature)

```text
specs/029-agent-approvals-and-mcp/
├── plan.md              # this file
├── research.md          # R1–R22: decisions with file-level evidence; eight things to verify first
├── data-model.md        # the suspended turn, the instance's approvals, their events and order of writes
├── quickstart.md        # the validation runs: pure → journal → MCP → TLS → sidecar → a real server → k3s → docs
├── contracts/
│   ├── scala-api.md         # tools, outcomes, decide, the context, MCP servers, result guardrails, configuration
│   ├── protocol.md          # the wire at 1.N, the conformance cases, the files a bump touches
│   └── sdk-apis.md          # Python and TypeScript
└── tasks.md             # /speckit-tasks, not created here
```

The acceptance scenarios are in `features/agents/` (five files), in the words of `GLOSSARY.md`.

### Source Code (repository root)

```text
modules/core/…/core/PlatformVariables.scala                    # ANKKA_MCP_ to the platform's program

modules/agent/…/agent/FunctionTool.scala                       # approval, origin
modules/agent/…/agent/Approvals.scala                          # new: ApprovalRequest, Decision, AgentOutcome, AgentPart
modules/agent/…/agent/ToolRunner.scala                         # new: one function both loops run a tool through
modules/agent/…/agent/AgentLoop.scala                          # suspend, resume, the admission check
modules/agent/…/agent/SessionMemoryEntity.scala, memory.scala  # the suspended turn; a result's decision
modules/agent/…/agent/Agent.scala                              # services on the context; mcpServers, resultGuardrails
modules/agent/…/agent/AgentRuntime.scala                       # decide in the host; ask, decide, streamParts;
                                                               #   MCP at start; the expiry action
modules/agent/…/agent/AgentEffect.scala, Guardrails.scala, judgment/JudgedGuardrail.scala   # checkResult, Direction.Result
modules/agent/…/agent/ApprovalExpiry.scala                     # new: the platform timed action
modules/agent/…/agent/mcp/McpServer.scala                      # new: the declaration and its rules
modules/agent/…/agent/mcp/McpClient.scala, McpTransport.scala  # new: JSON-RPC over streamable HTTP; two transports
modules/agent/…/agent/mcp/McpTools.scala                       # new: connect at start, tools as FunctionTools
modules/agent/…/agent/TestMcpServer.scala                      # new: the scriptable server
modules/agent/…/agent/autonomous/InstanceEntity.scala          # two events, Working.approvals
modules/agent/…/agent/autonomous/IterationLoop.scala           # SettleCalls
modules/agent/…/agent/autonomous/AutonomousAgentHost.scala, Calls.scala, Notification.scala, AutonomousAgent.scala
modules/agent/src/main/resources/reference.conf                # the two timeouts

modules/runtime/…/runtime/wire.scala                           # StreamAwaiting
modules/runtime/…/runtime/remote/Conversation.scala            # GuardrailStage.Result; the protocol version
modules/http/…/http/HttpEndpoint.scala, HttpServer.scala       # SseEvent

protocol/src/main/protobuf/ankka/protocol/v1/discovery.proto, agent.proto, client.proto
protocol/README.md, protocol/fixtures/autonomous/

sidecar/…/sidecar/RemoteAgent.scala, RemoteAutonomousAgent.scala, Discovery.scala, Translate.scala
sidecar/…/sidecar/ClientLogic.scala, ClientService.scala, GrpcConversation.scala, wasm/WasmConversation.scala
sidecar/src/test/…/conformance/ConformanceSuite.scala, ConformanceReference.scala

controlplane-api/…/api/Compatibility.scala                     # the protocol version

sdks/python/src/ankka/agent.py, autonomous.py, client.py, server.py, service.py, testkit/unit.py
sdks/python/src/ankka/approvals.py, mcp.py (new)
sdks/typescript/src/agent.ts, autonomous.ts, handlers.ts, client.ts, spec.ts, server/agent.ts, testkit/kinds.ts
sdks/typescript/src/approvals.ts, mcp.ts (new)
sdks/rust/ankka/src/…                                          # message literals; the protocol copy
sdks/*/examples/…/conformance.*                                # the reference agent and its routes

modules/testkit/src/test/…/ApprovalSuite.scala, McpAgentSuite.scala, AgentServiceCallSuite.scala (new)
modules/testkit/src/test/…/autonomous/AutonomousApprovalSuite.scala (new)
modules/testkit/src/test/resources/journal/session-memory.json, autonomous-events.json
controlplane/src/test/…/EndToEndClusterSuite.scala             # one case

docs/build/agents.md, autonomous-agents.md, mcp-servers.md (new)
docs/reference/limitations.md, configuration.md, sidecar-protocol.md, glossary.md, *-sdk.md
mkdocs.yml, tools/docs/skill/
```

**Structure Decision**: no module, image, table or published artifact is added. Everything an
agent does is in `agent`, where both loops live, including the MCP client; it is not a module of
its own because nothing but an agent uses it and it adds no dependency. The scriptable MCP server
is in `agent`'s main sources beside `TestModelProvider`, so a developer's tests get it with the
module they already have. `http` gains one generic type; `runtime` gains one stream token, one
guardrail stage and nothing about approvals or MCP.

## Order of work

Cut by user story, tests before the code they hold. Each slice stands on its own.

1. **Approvals for a request agent** (User Story 1). The tool's declaration; the session's
   suspended turn, its events and the compatibility pins; the tool runner; suspend, the admission
   check and resume in the loop; `ask`, `decide` and `approvals` on the client; the stream's last
   part and `SseEvent`; `ApprovalSuite`.
2. **Approvals for an autonomous agent** (User Story 2). The instance's two events and the pins;
   `SettleCalls`; the host's `decide`; the notifications and `AgentState`;
   `AutonomousApprovalSuite` and `ResumePointSuite`.
3. **Expiry** (FR-016). The timed action, scheduling in both kinds of agent, the refusals when
   `TimerRuntime` is absent. After 1 and 2 because it is a decision like any other.
4. **The service client in a Scala tool** (User Story 3, first half). `services` on both
   contexts, the tool's span, `AgentServiceCallSuite`, and the k3s case where a deployed
   service can be given a scripted model. Independent of 1 to 3.
5. **MCP servers** (User Story 4). `PlatformVariables`; the declaration and its rules; the client
   and `TestMcpServer`; the spike against a real server; tools at start; the service transport;
   approval per server; `McpAgentSuite`. Uses 1 for approval, 3 for a server's time limit and 4
   for the service transport and the span.
6. **Result guardrails** (FR-018). `checkResult`, the declarations, the tool runner. Uses 5.
7. **The protocol and the sidecar** — discovery, the guardrail stage, the reply case, `Decide`,
   the version; `RemoteAgent` and `RemoteAutonomousAgent`; `ClientLogic`; the Scala conformance
   reference and cases; the Rust literals.
8. **The Python and TypeScript SDKs**, independent of each other: declarations, client, unit
   kits, conformance references.
9. **A Python or TypeScript tool's service call** (User Story 3, second half) — when
   025-polyglot-service-client is on `main`.
10. **Documentation**, then the whole build.

Slices 1, 2 and 4 can start at once. If 025 is not on `main` when 1 to 8 and 10 are done, the
feature ships without slice 9 and the three rows of
`a tool's call to another service is admitted in every language` beyond Scala stay unbuilt, said
so on the limitations page.

## Complexity Tracking

No principle is violated. These are the places the plan departs from the spec's wording or widens
the platform's surface, each with the simpler thing that was rejected.

| Departure | Why needed | Simpler alternative rejected because |
|---|---|---|
| MCP tools are named `mcp__<server>__<tool>`, not `mcp:<server>:<tool>` (FR-012, amended) | a provider refuses a tool name with a colon | mapping the name inside each provider makes the name the model saw differ from the one the session records and a result is matched by (R14) |
| A header's variable must start `ANKKA_MCP_`, and a server's address may come from `ANKKA_MCP_<SERVER>_URL` (FR-017, amended) | behind the sidecar the platform's program makes the connection, and only declared variables reach it | any variable name, as the spec said, is unset where it is read for every Python and TypeScript service (R16) |
| The process's `Plan` does not carry MCP tools; the runtime adds them to every plan (FR-014, amended) | a request agent's tools are chosen per effect, and "beside the agent's own" has to mean one thing in every language | naming them in each plan makes a server's tools invisible until every handler is edited, and a typo a runtime error (R14) |
| A request agent's approved tool runs at most once; a turn cut off after its last decision is ended (FR-004, an edge case added) | exactly once across a crash between running and recording is not achievable | at least once, the autonomous rule, would run a refund twice with no caller to see it (R4) |
| Approved tools of one turn run when the last decision arrives, not as each is approved | a result recorded on its own needs its own event and makes the crash rule per call | running each at once reads more naturally and makes a cut-off turn half-run (R4) |
| A time limit is refused without `TimerRuntime` (FR-016, amended) | nothing else outlives the process | warning and never firing is a deadline that is not one (R10) |
| `http` gains `SseEvent` and a stream route for it | a stream of strings cannot end with something that is not the model's text | a marker string in the text stream is a silent corruption waiting for a model to write the marker (R7) |
| A decision for a turn that began as a stream is answered whole | a streamed `decide` doubles the client surface in three languages | — it is deferred, and said on the limitations page (R7) |
| `Guardrail` gains a third check instead of a new type | a judged guardrail and `Guardrail.forbidding` should work on a result unchanged | a second type needs a second judged variant, a second remote wrapper and a second path through `Guardrails.check` (R17) |
| Two new notifications, not one | a subscriber that saw a request needs to see it settled | `ApprovalRequested` alone leaves a watcher polling `state()` (R8) |

**One consequence to say in the release notes**: during the one rolling deploy that introduces a
tool requiring approval, a caller on a node still running the old version reads an awaiting
outcome as a reply it cannot decode. It is the same window in which that node does not have the
tool at all.
