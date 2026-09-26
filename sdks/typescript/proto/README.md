# The sidecar protocol

This directory is the artifact an SDK consumes: the `.proto` files under `src/main/protobuf`,
`ENCODING.md` (what the bytes inside a `Payload` mean) and `fixtures/` (documents every SDK's
default codec must encode and decode exactly). An SDK copies the whole directory in — the Python
SDK's `uv run python scripts/proto.py` and the TypeScript SDK's `npm run proto` do — and CI checks the copies are identical.

The sbt project here (`ankka-protocol`) only generates Scala for the sidecar; it is never
published. Nothing of ankka's depends on it except `sidecar`, and it depends on nothing of
ankka's.

## Version

The protocol version is `1.0`, carried in discovery by both sides and checked by the sidecar.
It is written once for code in `controlplane-api` (`Protocol.version`) and once here.

`MAJOR.MINOR`. Within a major:

- adding an optional field, a message, an rpc or a fixture is a **minor**, and a sidecar that
  speaks a *later* minor accepts an SDK that declares an earlier one;
- renaming, removing or changing the meaning of anything, or changing the encoding of a shape the
  fixtures already cover, is a **major**, and the sidecar refuses a `Spec` declaring another
  major, naming both versions.

## Layout

| file | conversation |
|---|---|
| `payload.proto` | `Payload`, `Metadata`, `Outcome`, `Retention`, `Error`, `Failure` — shared by everything |
| `discovery.proto` | `Discovery.Discover` / `ReportError`: the process describes its components and endpoints |
| `event_sourced.proto`, `key_value.proto`, `workflow.proto` | one bidirectional stream per loaded instance |
| `view.proto`, `consumer.proto`, `timed_action.proto` | stateless: one request, one effect |
| `endpoint.proto` | `Http.Handle` / `HandleStream`: HTTP requests the sidecar forwards for declared routes |
| `agent.proto` | `Agent.Plan` / `InvokeTool` / `CheckGuardrail`: the process plans and runs tools, the sidecar runs the loop |
| `client.proto` | `Client`: the callback service the sidecar serves — component calls, view queries, timers |

Every service but `Client` is implemented by the developer's process on loopback at
`ANKKA_PROCESS_PORT` (9010) and dialled by the sidecar; `Client` is served by the sidecar on
loopback at `ANKKA_SIDECAR_PORT` (9011). Neither ever binds another interface.

## Rules the messages do not state on their own

- **A stateful conversation has one command in flight**, and the sidecar enforces it. A workflow's
  stream is the exception it needs: the engine keeps answering commands while a step runs, so one
  command *and* one step may be in flight at once; a command arriving mid-step is answered from
  the state before the step, and the step's `new_state` applies when the step replies.
- **A workflow declares what the sidecar enforces** — timeouts and recovery — in
  `WorkflowDetail.settings`; absent, the engine's defaults apply (no overall limit, 30s a step, a
  failed step fails the workflow). A `failover_to` must name a declared step; it runs with no
  input. A step that answers `Failure` is retried and failed over as declared; a step that answers
  `StepOutcome.fail` ends the workflow, as it asked.
- **A view or consumer learns of its source's deletion** by `deleted = true` with no event or
  message; the default answer is to drop the row (a view) or ignore (a consumer). The source's id
  travels as `ce-subject` in the metadata and the change's sequence number as `ankka.sequence`.
- **A timed action's payload is what the process scheduled**, the `Payload` it put in
  `Client.Schedule`, carried through the timer table unread; the timer's name and the attempt
  count arrive as metadata `ankka.timer` and `ankka.attempts`. A `fail`, an exception or an
  unreachable process is retried on the sweeper's schedule with the count incremented.
- **A plan names, it does not carry**: `AgentPlan.model`, `tools` and `guardrails` are names the
  sidecar resolves against its configuration and against what discovery declared. The process is
  called back for a tool with the model's arguments as JSON text, and for a guardrail with the
  stage and the text; the loop, the memory and the model key are the sidecar's.
- **The sidecar's `ReportError`** is delivered before it refuses to start, once, with every
  problem; a process should log it and may expose it, as the conformance reference does.
