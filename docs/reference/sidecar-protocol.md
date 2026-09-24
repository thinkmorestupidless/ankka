---
title: Sidecar protocol
description: The gRPC protocol between the ankka sidecar and a service's process — transport, the discovery handshake, every service and RPC, the payload encoding, versioning, and the rules the messages do not state on their own.
kind: reference
languages: [python]
related: [concepts/polyglot.md, contributing/language-sdks.md, reference/python-sdk.md, reference/error-codes.md]
---

# Sidecar protocol

A service written in a language other than Scala runs as a process beside the ankka sidecar, which is the
ankka runtime started without a Scala service in it. The two speak a protobuf protocol over gRPC. The
process owns decisions: given this command and this state, what should happen. The sidecar owns everything
stateful and distributed: sharding, the journal, snapshots, projections, timers, HTTP, cluster formation,
observability and the agent loop.

This page is for people writing or debugging an SDK. A service's own code never sees the protocol; the SDK
does. The definitions are in
[`protocol/src/main/protobuf/ankka/protocol/v1`](https://github.com/thinkmorestupidless/ankka/blob/main/protocol/src/main/protobuf/ankka/protocol/v1),
and the encoding of the bytes inside a payload is in
[`protocol/ENCODING.md`](https://github.com/thinkmorestupidless/ankka/blob/main/protocol/ENCODING.md).

## Transport

Both sides listen on loopback only, inside the same pod, and neither binds any other interface.

| Server | Default address | Variable | Serves |
|---|---|---|---|
| The process | `127.0.0.1:9010` | `ANKKA_PROCESS_PORT` (process), `ANKKA_PROCESS_ADDRESS` (sidecar) | Every service except `Client` |
| The sidecar | `127.0.0.1:9011` | `ANKKA_SIDECAR_PORT` (sidecar), `ANKKA_SIDECAR_ADDRESS` (process) | `Client` |

The sidecar dials the process; the process dials the sidecar only to call other components, query views and
schedule timers. On the platform both containers are given these variables and a descriptor may not set
them.

## Discovery

Discovery is the first conversation. The sidecar calls `Discovery.Discover` with its protocol version and
runtime version, retrying with backoff until the process answers or `ANKKA_SIDECAR_DISCOVERY_TIMEOUT`
(60 seconds by default) passes. The process answers with a `Spec`:

- its protocol version, `"1.0"`;
- its SDK's name and version;
- every component: its kind, its component id, and its handlers, each with a wire name and whether it is
  read-only or streaming, plus the kind's details — snapshot frequency for an event sourced entity; steps
  and settings for a workflow; the source, row manifest and queries for a view; the source and topic for a
  consumer; the tools, with descriptions and JSON Schemas, and guardrails for an agent;
- every HTTP endpoint: its prefix, ACL and routes.

The sidecar validates the whole `Spec` and hosts exactly what it describes. If anything is wrong it calls
`Discovery.ReportError` once, with every problem, and refuses to start. A process should log what it is told;
this is where a developer sees why their service did not start.

## Services

The table is generated from the `.proto` files.

<!-- generated:start protocol -->
| Service | RPC | Request | Response | Defined in |
|---|---|---|---|---|
| `Agent` | `Plan` | `PlanRequest` | `PlanReply` | `agent.proto` |
| `Agent` | `InvokeTool` | `ToolRequest` | `ToolResult` | `agent.proto` |
| `Agent` | `CheckGuardrail` | `GuardrailRequest` | `GuardrailResult` | `agent.proto` |
| `Client` | `Invoke` | `InvokeRequest` | `InvokeReply` | `client.proto` |
| `Client` | `InvokeStream` | `InvokeRequest` | `stream StreamToken` | `client.proto` |
| `Client` | `Query` | `QueryRequest` | `QueryReply` | `client.proto` |
| `Client` | `Schedule` | `ScheduleRequest` | `Empty` | `client.proto` |
| `Client` | `Cancel` | `CancelRequest` | `Empty` | `client.proto` |
| `Consumer` | `Handle` | `ConsumerRequest` | `ConsumerEffect` | `consumer.proto` |
| `Discovery` | `Discover` | `SidecarInfo` | `Spec` | `discovery.proto` |
| `Discovery` | `ReportError` | `Problem` | `Empty` | `discovery.proto` |
| `Http` | `Handle` | `HttpRequest` | `HttpReply` | `endpoint.proto` |
| `Http` | `HandleStream` | `HttpRequest` | `stream StreamFrame` | `endpoint.proto` |
| `EventSourced` | `Handle` | `stream EventSourcedIn` | `stream EventSourcedOut` | `event_sourced.proto` |
| `KeyValue` | `Handle` | `stream KeyValueIn` | `stream KeyValueOut` | `key_value.proto` |
| `TimedAction` | `Invoke` | `TimedActionRequest` | `TimedActionEffect` | `timed_action.proto` |
| `View` | `Handle` | `ViewRequest` | `ViewEffect` | `view.proto` |
| `Workflow` | `Handle` | `stream WorkflowIn` | `stream WorkflowOut` | `workflow.proto` |
<!-- generated:end protocol -->

| File | Conversation |
|---|---|
| `payload.proto` | Shared messages: `Payload`, `Metadata`, `Outcome`, `Retention`, `Error`, `Failure`. |
| `discovery.proto` | The process describes its components and endpoints. |
| `event_sourced.proto`, `key_value.proto`, `workflow.proto` | One bidirectional stream per loaded instance. |
| `view.proto`, `consumer.proto`, `timed_action.proto` | Stateless: one request, one effect. |
| `endpoint.proto` | HTTP requests the sidecar forwards for declared routes, with streaming for server-sent events. |
| `agent.proto` | The process plans and runs tools and guardrails; the sidecar runs the loop. |
| `client.proto` | The sidecar's service for the process: component calls, streaming calls, view queries, timers. |

### Stateful conversations

An event sourced entity, key value entity or workflow instance is a bidirectional stream, opened when the
sidecar loads the instance. For an event sourced entity the sidecar sends `Init` with the latest snapshot, if
any, then the events after it, then commands one at a time. The process folds the events into state and
answers each command with a `Reply` — the events to persist, any retention, and an `Outcome` that is a reply
or a refusal — or with a `Failure` when the handler faulted. An `Init` with no snapshot means a fresh
instance, or one that was deleted or expired; start from the empty state.

When a command asks for a snapshot, the reply carries the state after its events. The sidecar stores it
beside the journal; the stored snapshot may sit at the sequence of the last event the reply persisted or at
the next one, and both are correct.

### Stateless conversations

A view, consumer or timed action is called once per change or timer, with a payload and metadata, and
answers with one effect. An HTTP route is called once per request, or once per stream for a server-sent
events route.

## Payloads

Every value crosses the protocol as a `Payload`: `content_type`, `manifest` and `data`. The sidecar never
reads `data`. It stores it under its manifest exactly as the Scala runtime stores a Scala value, which is
what lets a service in one language read a journal written by the same service in another — as long as both
languages' codecs agree. Every SDK's default codec produces and accepts exactly this:

| Content type | Used for | Encoding |
|---|---|---|
| `application/json` | Records and sum types | A JSON object with every field written, including empty collections and absent options as `null`. A sum type's case adds `"type": "<CaseName>"`. Instants are ISO-8601 in UTC with `Z`. |
| `text/plain` | Top-level primitives | The text itself, with no quotes: manifests `string`, `int`, `long`, `short`, `byte`, `double`, `float`, `boolean`, and `duration-millis` for a duration in milliseconds. |
| `application/octet-stream` | `Done`, unit, bytes, top-level options | Zero bytes for `done` and `unit`; the bytes for `bytes`; for `option[<manifest>]`, zero bytes for none, or `0x01` followed by the value's bytes. |

Readers are lenient where writers are strict: an absent optional field reads as none, field order does not
matter, and unknown fields are ignored. A missing required field or an unknown `type` is refused. Field names
are the contract, so a Python field matching a Scala one is `productId`, not `product_id`.

The fixtures in
[`protocol/fixtures`](https://github.com/thinkmorestupidless/ankka/blob/main/protocol/fixtures) are encoded
examples of every shape. An SDK decodes each fixture's bytes and compares the result with its `value`, then
re-encodes the value and compares the bytes. A fixture with no matching codec is a failure, not a skip.

## Errors

A refusal is an `Error` with a message and an `ErrorCode`, carried in an `Outcome`. A fault is a `Failure`,
which names the command it answers. The two are never interchangeable: a refusal is a decision the handler
made, and a failure is a handler that could not decide. See [Error codes](error-codes.md).

## Versioning

The protocol version is `MAJOR.MINOR`, currently `1.0`, and both sides state it in discovery.

- Adding an optional field, a message, an RPC or a fixture is a minor change. A sidecar speaking a later minor
  accepts an SDK that declares an earlier one.
- Renaming, removing or changing the meaning of anything, or changing the encoding of a shape the fixtures
  cover, is a major change. The sidecar refuses a `Spec` with another major, naming both versions.

A process-hosted service declares its protocol in its descriptor's `protocol` field, and the platform checks
it before starting anything: the same major as the platform's, and a minor no later than its own.

## Rules the messages do not state

These are part of the protocol, and an SDK that ignores one misbehaves in ways the types cannot show.

- **One command in flight per stateful stream.** The sidecar sends the next command only after the process
  has answered the last. A workflow's stream is the exception: the engine keeps answering commands while a
  step runs, so one command and one step may be in flight at once. A command arriving during a step is
  answered from the state before the step, and the step's new state applies when the step replies. An SDK
  must track the pending command and the pending step separately, and must not share per-request context
  between them.
- **A workflow declares what the sidecar enforces.** Timeouts and recovery live in `WorkflowDetail.settings`,
  because the process cannot enforce them. Absent, there is no overall limit, each step has 30 seconds, and a
  failed step fails the workflow. A `failover_to` step must be declared, and runs with no input.
- **A step's fault is a `Failure`; a step's `fail` is a decision.** A step that answers `Failure`, or times
  out, is retried and failed over as its recovery declares. A step that answers `StepOutcome` `fail` ends the
  workflow, because that is what it asked for. An SDK turns an exception in a step into a `Failure`, never
  into `fail`.
- **A view or consumer learns of its source's deletion** by a request with `deleted = true` and no event. The
  default answer is to delete the row, for a view, or ignore it, for a consumer. The source's id travels as
  the metadata entry `ce-subject`, and the change's sequence number as `ankka.sequence`.
- **A timed action's payload is what the process scheduled**, carried through the timer table unread. The
  timer's name and the attempt count arrive as metadata `ankka.timer` and `ankka.attempts`. A `fail`, an
  exception or an unreachable process is retried on the sweeper's schedule with the count incremented.
- **An agent plan names; it does not carry.** `AgentPlan.model`, `tools` and `guardrails` are names the
  sidecar resolves against its configuration and against discovery. The process is called back for a tool
  with the model's arguments as JSON text, and for a guardrail with the stage and the text. The loop, the
  session memory and the model key stay in the sidecar. A tool or guardrail call arrives after the handler
  that planned it has returned, so anything it needs from the session must be captured when the plan is
  made.
- **`Unavailable` means try again.** When an instance stops while callers are waiting on it, for example
  during a rolling replacement, the sidecar answers each waiting caller with `UNAVAILABLE` rather than letting
  it time out, and the sidecar's `Client` retries `UNAVAILABLE` briefly before giving up. An SDK should treat
  `UNAVAILABLE` and `TIMEOUT` as retryable.
