# The sidecar protocol

This directory is the artifact an SDK consumes: the `.proto` files under `src/main/protobuf`,
`ENCODING.md` (what the bytes inside a `Payload` mean), `WASM-ABI.md` (how a WebAssembly module
speaks the same messages without a network) and `fixtures/` (documents every SDK's default codec
must encode and decode exactly). An SDK copies the whole directory in — the Python
SDK's `uv run python scripts/proto.py`, the TypeScript SDK's `npm run proto` and the Rust crate's
`scripts/proto.sh` do — and CI checks the copies are identical.

The sbt project here (`ankka-protocol`) only generates Scala for the sidecar; it is never
published. Nothing of ankka's depends on it except `sidecar`, and it depends on nothing of
ankka's.

## Version

The protocol version is `1.15`, carried in discovery by both sides and checked by the sidecar.
It is written once for code in `controlplane-api` (`Protocol.version`) and once here. `1.6` added
the secret store: `GetSecret`, `PutSecret` and `DeleteSecret` on `Client`, and the imports of the
same names for a module. `1.7` added where a topic source starts and the version of a view or
consumer that reads one. `1.8` added `Request` on `Client`: a call to another service, made by the
runtime as the service. A process that declared an earlier minor is answered a refusal naming both
versions if it sends one; an earlier runtime answers a 1.8 process's `Request` with `UNIMPLEMENTED`,
which each SDK reports as the runtime being too old. No module import carries it yet. `1.9` added
socket routes: `Route.socket` in discovery and `Http.HandleSocket`, a stream in each direction for
as long as a socket is open. The sidecar refuses a socket route from a process that declares an
earlier minor, and a module cannot declare one. `1.10` added three imports for a module and changed
no message: `request`, which carries `ServiceRequest` and `ServiceReply` across a module's memory, and
`now` and `random`, which carry none. A process is the same at 1.9 and 1.10. `1.11` added approvals, MCP servers and result
guardrails to an agent: a tool's `approval` and an agent's `mcp_servers` and `result_guardrails` in
discovery, the `RESULT` stage of `CheckGuardrail`, the `approval` case of `InvokeReply` and
`StreamToken`, `Decide` on `Client`, and the `event` frame of a stream route, a
server-sent event of its own name. `1.12` added recurring
timers: `ScheduleRecurring` on `Client` and the `schedule_recurring` import for a module,
and `ankka.due` on every timed action request. `1.13` added a view's declared queries, asked by name
with values; the keyed view, whose several entity sources each send their changes with `source_id` and
which answers with `rows`, several row changes by key; and a version on a view that reads entities.
`1.14` added what a project must know about a topic source and a publication: `Source.contract`,
`Source.broker` and `Source.parallel`, `ConsumerDetail.produces` (a `Publication` with its
contract and broker; `produces_to` stays), and `fixtures/contracts/`, the fingerprints every SDK
must compute for a schema document. `1.15` added cross-project access: `CallerMatcher.granted`, a
caller holding a grant its service's project made on the route or method; `Caller.machine`, a machine
registered on an organization, proven by a token the control plane issued; and `Source.project` and
`Publication.project`, another project's topic, which the broker serves only under a grant. The
sidecar refuses a `Spec` that uses any of them and declares an earlier minor, and an SDK refuses
discovery from a runtime stating an earlier one, as for socket routes: a runtime that did not know
`granted` would read the matcher as nothing.

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
| `view.proto`, `consumer.proto`, `timed_action.proto` | stateless: one request, one effect (a keyed view's effect may change several rows) |
| `endpoint.proto` | `Http.Handle` / `HandleStream` / `HandleSocket`: HTTP requests, and open sockets, the sidecar forwards for declared routes |
| `agent.proto` | `Agent.Plan` / `InvokeTool` / `CheckGuardrail`: the process plans and runs tools, the sidecar runs the loop |
| `client.proto` | `Client`: the callback service the sidecar serves — component calls, view queries, timers |
| `wasm.proto` | the module mode's envelopes: `WasmSpec`, and the requests and replies that carry held state (`WASM-ABI.md`) |

Every service but `Client` is implemented by the developer's process on loopback at
`ANKKA_PROCESS_PORT` (9010) and dialled by the sidecar; `Client` is served by the sidecar on
loopback at `ANKKA_SIDECAR_PORT` (9011). Neither ever binds another interface.

## Rules the messages do not state on their own

- **`ankka-caller` is the runtime's, carried and never written.** Metadata the runtime sends with a command,
  a step, a tool call or a check carries `ankka-trace-id`, `ankka-span-id` and `ankka-caller`, the handler
  whose work this is. An SDK forwards a handler's metadata unchanged on the calls it makes through the
  client, which is how those calls are attributed; user code never sets `ankka-caller`, and the runtime
  ignores one that does not name a component and handler the service declared.
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
  travels as `ce-subject` in the metadata and the change's sequence number as `ankka.sequence`:
  an event's sequence number, a key value state's revision, `0` for a topic's message.
- **A consumer may answer with several messages**, `produce_all`, each with an optional record
  `key`; a message without one is keyed by its subject, as `produce`'s is. They are published in
  order and the change is handled when all are accepted; if one is refused the change comes again
  and all are published again. A runtime that accepts `produce_all` says so on every consumer
  request, in the metadata entry `ankka.protocol`. **An SDK must not answer `produce_all` to a
  request without that entry, or with one below `1.3`**: an earlier runtime reads a case it does
  not know as no effect and drops the messages. It fails the request instead, saying which
  version it was given and which it needs. A reply is at most 4 MiB.
- **A timed action's payload is what the process scheduled**, the `Payload` it put in
  `Client.Schedule` or `Client.ScheduleRecurring`, carried through the timer table unread; the
  timer's name, the attempt count and the due time the run is for arrive as metadata
  `ankka.timer`, `ankka.attempts` and `ankka.due` (epoch milliseconds, as `ankka.now`). A `fail`, an
  exception or an unreachable process is retried on the sweeper's schedule with the count
  incremented and the same `ankka.due`.
- **A recurring timer is a new call, not a field**: `ScheduleRecurring` carries the period. A
  runtime older than 1.12 answers it `UNIMPLEMENTED`, and an SDK reports that as the runtime being
  too old for recurring timers; a period added to `ScheduleRequest` would have been read by such a
  runtime as absent, and the timer scheduled to fire once.
- **A plan names, it does not carry**: `AgentPlan.model`, `tools` and `guardrails` are names the
  sidecar resolves against its configuration and against what discovery declared. The process is
  called back for a tool with the model's arguments as JSON text, and for a guardrail with the
  stage and the text; the loop, the memory and the model key are the sidecar's.
- **The sidecar's `ReportError`** is delivered before it refuses to start, once, with every
  problem; a process should log it and may expose it, as the conformance reference does.

## The module mode

A service can instead be a WebAssembly module the runtime loads into its own process
(`ANKKA_WASM_MODULE`). It declares and handles exactly what a process does, through the exports and
imports `WASM-ABI.md` lists, and nothing above changes for it. The rules the messages do not state:

- **One call in flight per instance.** A guest instance runs one export at a time; the runtime keeps
  a pool of instances for commands and builds a fresh one for anything that may block (a workflow
  step, an agent's tools, a view, a consumer, a timed action, an HTTP route), so a slow call never
  holds up a command.
- **The runtime owns the state in both shapes.** A stateless guest is handed it on every call; a
  stateful one on the first call after `open`, and it keeps the decoded value until `ankka1_close`.
  Every reply carries the encoded state either way, so a trap loses nothing: the instance is
  replaced and the state handed again.
- **A stateful component is pinned** to one guest instance for its loaded life, since that instance
  is what holds its state.
- **There is no `ReportError` into a module.** A module the runtime refuses — a missing export,
  another ABI version, a streaming handler or route, an import from anywhere but `ankka1` — is
  refused at start with every problem in the runtime's log, and the runtime exits.
