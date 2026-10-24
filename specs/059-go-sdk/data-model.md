# Data Model: Go SDK

**Feature**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md) | **Research**: [research.md](./research.md)

The protocol, the encoding and the sidecar are unchanged (FR-041); their models are in
`specs/009-polyglot-runtimes/data-model.md` and later features' and are referenced, not repeated.
This document is the SDK's own values: what a developer declares, what the SDK holds while it runs,
and what the test kits expose. Names are the ones the contract uses
([contracts/go-sdk.md](./contracts/go-sdk.md)). The module is `github.com/thinkmorestupidless/ankka-go`,
imported as `ankka`; the test kits are its `testkit` package.

## Type plans (`codec.go`, `plan.go`)

A **plan** is what the codec builds from a Go type by reflection, once, at registration (R3). It is
the SDK's internal model of a shape; the developer never sees it, only the rules it enforces.

| Go type | plan | JSON (ENCODING.md) | notes |
|---|---|---|---|
| `string` | text | string | top level: the `string` text payload |
| `int`, `int32` | integer | integer, no fraction | top level: `int` text; a fraction on read refuses |
| `int64` | long | integer, no fraction | lossless past 2⁵³: read through `json.Number`, never `float64`; top level: `long` |
| `int16`, `int8` | short, byte | integer | top level: `short`, `byte` |
| `float64`, `float32` | double, float | shortest round-trip, `1.0` for whole values, `1.0E10` past 10⁷ | rendered as Scala's `Double.toString` (R3, V7); `NaN` and infinities refused on write |
| `bool` | boolean | `true`/`false` | |
| `time.Time` | instant | ISO-8601 UTC with `Z`, 0/3/6/9 fractional digits | written in UTC, monotonic reading dropped; read back equal |
| `time.Duration` | duration | ISO-8601 (`PT1.5S`) | top level: `duration-millis` text; a date part on read refuses |
| `ankka.LocalDate`, `ankka.LocalDateTime` | local date, local date-time | ISO-8601 strings | string-backed value types (R4) |
| `[]byte` | bytes | base64 string inside JSON | top level: the binary `bytes` payload |
| `*T` | optional | `null` when nil | the only way to say optional; an absent field reads as nil |
| `[]T`, `[N]T` | sequence | array, `[]` when nil or empty | |
| `map[string]T` | object | object | a Go map has no order, so keys are written sorted and the bytes are stable; the fixtures' maps carry one key, so they decide nothing here, and V7's comparison table covers a map of several |
| `struct` | record | object, every field written, declared order | name from the `json` tag or the lower-camel field name; `json:"-"` excludes; any tag option refuses |
| an interface with registered cases | sum type | the case's fields plus `"type":"<Case>"` | cases from `ankka.Cases[I]` (below); an unregistered dynamic type refuses on write, an unknown `type` on read |
| a named string type with registered values | enumeration | `{"type":"Ready"}` as a field; a bare string accepted on read | values from `ankka.Enumeration[E]` |
| `ankka.Done` | done | zero bytes, manifest `done` | the only value is `ankka.Done{}` |
| `ankka.Personal[T]` | the personal envelope | as `fixtures/erasure` | erasable values (feature 042) |

**Refused at registration and in the component test kit, naming the field**: unsigned integers,
`uintptr`, `complex`, channels, functions, `any` and interfaces with no registered cases, maps with
non-string keys, unexported fields, embedded structs (promoted fields are ambiguous in order), a
`json` tag option, two fields with one name, a case registered under two discriminators, a
discriminator `type` colliding with a field named `type`, a cycle that is not through a pointer or a
slice (which the plan handles by reference).

**Codec**: `{ Manifest string; ContentType string; Encode(v) ([]byte, error); Decode([]byte) (v, error) }`,
generic over the type, built by `ankka.CodecFor[T]()` (the default) or supplied by the developer
(`ankka.CustomCodec[T](manifest, contentType, enc, dec)`, FR-015). A top-level scalar selects its text
codec; `[]byte` the binary `bytes`; `*T` at the top level `option[<T's manifest>]`; `Done` the binary
`done`; everything else `application/json` with the type's name as the default manifest, overridable
(`ankka.Manifest("shopping-cart")` as an option).

## Case and value registrations (`cases.go`)

```
ankka.Cases[I any](cases ...ankka.Case[I]) *ankka.SumType[I]     // I is an interface type
ankka.Case[I, T any](discriminator string) ankka.Case[I]           // T is a struct implementing I
ankka.Enumeration[E ~string](values ...E) *ankka.Enum[E]
```

A `SumType[I]` is referenced by the component declaration that stores values of `I` (events,
state, replies, rows); the plan for `I` is built from it. A struct type may be a case of several
interfaces. Registering the same type twice under one interface, or a discriminator twice, is a
declaration problem.

## Component declarations (`eventsourced.go`, `keyvalue.go`, `workflow.go`, `view.go`, `consumer.go`, `timedaction.go`, `agent.go`, `autonomous.go`, `endpoint.go`, `mcp.go`)

Every component is a value built by a constructor that takes what the kind cannot do without, with
options for the rest; handlers are plain functions over the state, never methods on an instance,
because Go has no instance a conversation could hold (the server holds the state, R6). A declaration
exists to the runtime only once `Register`ed.

| kind | constructor (required) | options | handler shapes |
|---|---|---|---|
| event sourced entity | `ankka.EventSourced[S, E](id, events *SumType[E], empty func() S, apply func(S, E) S, handlers...)` | `SnapshotEvery(n)`, `StateCodec`, `Replicated()` | `ankka.Command[S, E, I, R](wire, func(ctx, S, I) (Effect[S, E, R], error))`, `ankka.Query[S, E, I, R](wire, func(ctx, S, I) (ReadOnlyEffect[S, R], error))` |
| key value entity | `ankka.KeyValue[S](id, empty, handlers...)` | `StateCodec` | `ankka.Update[S, I, R]`, `ankka.Query` as above with `KeyValueEffect[S, R]` |
| workflow | `ankka.Workflow[S](id, empty, steps, handlers...)` | `Settings(timeout, defaultStepTimeout, recovery, perStep...)` | commands return `WorkflowEffect[S, R]`; `ankka.Step[S, I](wire, func(ctx, S, I) (StepEffect[S], error))` |
| view | `ankka.View[Row](id, sources..., onChange...)` | `Queries(...)`, `Watched(...)`, `Recursive(...)`, `Version(n)` | one `OnChange[Src, Row](source, func(ctx, change Src, row *Row) ViewEffect[Row])` per source; a `Topic` or `WorkflowSource` is a source |
| keyed view | `ankka.KeyedView[Row](id, keyOf, sources...)` | as view | rows named by key |
| consumer | `ankka.Consumer[Msg](id, source, func(ctx, Msg, Metadata) (ConsumerEffect, error))` | `ProducesTo(topic)`, `StartFrom(position)`, `Project(p)` | `GraphConsumer` publishes deltas |
| timed action | `ankka.TimedAction(id, actions...)` | | `ankka.Action[I](wire, func(ctx, I) (ActionEffect, error))` |
| agent | `ankka.Agent(id, handlers...)` | `Tools(...)`, `Guardrails(...)`, `McpServers(...)`, `MaxToolCallSteps(n)` | `ankka.Ask[I](wire, func(ctx, I) AgentEffect)`, `ankka.AskStream`; `ankka.Tool[I](name, description, func(ctx, I) (any, error))`, `ankka.ApprovedTool`; `ankka.Guardrail(name, func(ctx, stage, text) (string, error))` |
| autonomous agent | `ankka.Autonomous(id, task rules...)` | | as feature 015's model, through `CheckTaskResult` |
| HTTP endpoint | `ankka.NewEndpoint(prefix, acl, routes...)` | `Issuers(...)` | `ankka.Get/Post/Put/Delete/Patch[B, R](path, func(ctx, *Request, B) (R, error))`, `ankka.SSE(path, func(ctx, *Request) iter.Seq2[string, error])`, `ankka.Socket(path, func(ctx, *ankka.Socket) error)`, each with `.ACL(acl)` |
| MCP endpoint | `ankka.NewMcpEndpoint(path, acl, tools...)` | `Brokered()` | `ankka.McpTool[I]`, `ankka.McpCommandRef`, `ankka.McpQueryRef`, `ankka.McpRouteRef` |

Wire names are the first argument of every handler constructor and never the Go function's name
(the wire-name principle). `ctx` is a `context.Context` carrying the request scope (below).

## Effects (`effects.go`)

Inert values, spelled as the Scala effects are, built through a package-level `ankka.Effects`
namespace; building one performs no I/O.

| kind | values | builders |
|---|---|---|
| shared | `Outcome = Reply(func(S) R, metadata) \| NoReply \| Refuse(code, msg)`; `Retention = DeleteNow \| ExpireAfter(time.Duration)`; `ErrorCode` with its HTTP status | `ankka.Refuse(code, msg)` is itself an effect of every kind |
| event sourced | `Effect[S, E, R]` (`kind: persist`, events, retention, outcome); `ReadOnlyEffect[S, R]` (`kind: read-only`, retention, outcome) | `Persist(e...).ThenReply(func(S) R)`, `.ThenReplyState()`, `.ThenNoReply()`, `.DeleteEntity()`, `.ExpireAfter(d)`; `Reply(r)`, `NoReply()`, `DeleteEntity()` |
| key value | `KeyValueEffect[S, R]` (`update` or `read-only`) | `UpdateState(s).ThenReply(...)`, `DeleteEntity()`, `Reply`, `Refuse` |
| workflow command | `WorkflowEffect[S, R]` (new state, transition, outcome) | `UpdateState(s).ThenTransitionTo(step, input).ThenReply(...)`, `TransitionTo`, `Reply`, `Refuse` |
| workflow step | `StepEffect[S]` (new state; `TransitionTo \| Pause{after, onTimeout} \| End \| Fail`) | `StepEffects.UpdateState(s).ThenTransitionTo / .ThenPause / .ThenEnd / .ThenFail` |
| view | `UpdateRow(row) \| DeleteRow \| Ignore` | |
| consumer | `Produce(values...) \| Done \| Ignore` | one or several messages, with keys and metadata |
| timed action | `Done \| Fail(code, msg)` | |
| agent | `AgentEffect` (model or provider, system, user, context, memory, tools, guardrails, reply shape text / stream / typed JSON, failure) | `SystemMessage(s).UserMessage(u).Tools(...).ThenReply()`, `.ThenReplyJSON[T]()`, `.ThenStream()` |

A `ReadOnlyEffect` is a distinct named type that no persisting builder returns, so `ankka.Query`'s
function type cannot be satisfied by a persisting handler (R5).

## Request scope (`context.go`)

Values carried on the `context.Context` every handler receives, read by accessors (never a
thread-local, Go has none):

```
ankka.RequestFrom(ctx)   → *Request { Method, Path, Params map[string]string, Query url.Values, Headers http.Header, Body []byte, Principal *Principal }
ankka.CallerFrom(ctx)    → Caller { Kind internet|service|local|unknown; Service, Project string; Granted bool }
ankka.CommandFrom(ctx)   → CommandScope { EntityID string; Sequence int64; Installation string; AtHome bool; Metadata }
ankka.TimerFrom(ctx)     → TimerScope { Name string; Due time.Time }
ankka.ClientFrom(ctx)    → *Client (below)
ankka.StorageFrom(ctx)   → Storage, or nil where a handler may not wait
ankka.ConfigFrom(ctx)    → the service's variables as the platform hands them
```

The context is cancelled when the conversation or request ends (R6); anything a handler still does
after that cannot reach the released state.

## Client (`client.go`, `services.go`, `secrets.go`, `clientkeys.go`, `storage.go`)

One lazy gRPC channel to `ANKKA_SIDECAR_ADDRESS`, scoped per request by the metadata the context
carries, so a call is nested under its handler in the trace (FR-009).

```
ankka.Invoke[R](ctx, componentID, entityID, wire, input) (R, error)        // entities, workflows, agents
ankka.InvokeStream(ctx, ...) iter.Seq2[Token, error]                        // a streaming agent reply
ankka.SendAndAwait[R](ctx, workflowID, id, wire, input) (R, error)          // 1.18
ankka.QueryView[Row](ctx, viewID, query, args) ([]Row, error); QueryStream; Watch   // 1.17 for the streams
ankka.Schedule(ctx, name, componentID, wire, input, after); ScheduleRecurring (1.12); Cancel
ankka.CallService(ctx, service, method, path, body, headers) (*ServiceResponse, error)  // 1.8
ankka.Secrets(ctx).Get/Put/Delete
ankka.ClientKeys(ctx).Issue/List/Revoke                                     // 1.20
ankka.StorageFrom(ctx).Put/Get/Stat/List/Delete/SignedURL/PublicURL         // S3, directory or memory
```

A refusal arrives as `*ankka.RefusalError{Code, Message}`; a sidecar too old for a call arrives
as `*ankka.ProtocolTooOldError{Needs string}` from the `UNIMPLEMENTED` status, naming the minor.

## Service and registry (`service.go`, `spec.go`)

```
ankka.NewService(components...) *Service
Service.Problems() []Problem           // every declaration problem, collected
Service.Spec() *pb.Spec                 // protocol_version "1.22", sdk {name "ankka-go", version ankka.Version()}
Service.Start(ctx, opts...) error      // refuses with every problem; binds; blocks until stopped
Service.Serve(listener)                 // for the test kit
```

Start options: `Host` (loopback or `0.0.0.0` only, else refused), `Port` (default
`ANKKA_PROCESS_PORT`, 9010), `SidecarAddress` (default `ANKKA_SIDECAR_ADDRESS`, `127.0.0.1:9011`),
`Storage`. **Gates** the SDK applies to the sidecar's version at discovery, oldest reason first
(FR-018): calls to other services 1.8, socket routes 1.9, recurring timers 1.12, grants
(`Project` on a source or publication) 1.15, workflow sources 1.16, query streams and watched
queries 1.17, awaited workflows 1.18, replicated entities and mirrored changes 1.19, MCP endpoints
and client keys 1.20, the directory store endpoint 1.21. `ankka.Version()` is the module's version
from the build's record, `0.0.0` for a development build (R8).

## Server state (`server/*.go`)

| conversation | state held | rule |
|---|---|---|
| event sourced | `{ entityID, state S, sequence int64, snapshotRequested bool }` per stream goroutine | fold snapshot and events before the first command; one command at a time; reply from the post-event state; snapshot when asked; release on stream end |
| key value | `{ entityID, state S }` | as above, without events |
| workflow | `{ id, state S, step *running }` with one reply channel | one command and one step in flight; a mid-step command answers from pre-step state; a second step refused; a step runs on a fresh instance in its own goroutine |
| view, consumer, timed action, agent, MCP tool, HTTP | none between calls | each unary call wrapped in `recover()` |
| socket | `{ frames chan string; closed }` relayed over `HandleSocket` | the handler's `ctx` ends when the socket closes |
| directory store endpoint | the `/_ankka/storage` routes, one GET and one PUT per depth to 16 | declared only to a sidecar at 1.21 or later |

Every panic in a handler is recovered into that request's `Failure` with the state unchanged; a
`Refuse` effect is a refusal; a returned `error` is a failure.

## Test kits (`testkit/`)

**Component test kits** (no sidecar, database or network): `testkit.EventSourced(component)`,
`testkit.KeyValue`, `testkit.Workflow`, `testkit.View`, `testkit.Consumer`, `testkit.TimedAction`,
`testkit.Endpoint`, `testkit.Agent`. Each call returns a `Result` with `Reply()`, `Events()`,
`State()`, `Refusal()`, `Failure()`; a workflow kit has `RunStep`, `Pending()` and follows transitions
to a pause; a view kit takes a change from any declared source including a workflow standing; a
consumer kit exposes `Published()` with keys and, for a graph consumer, `Elements()`; the agent kit
runs the loop against `testkit.ScriptedModel(turns...)`, which fails when the script runs out. Every
input, event, state, row and reply is round-tripped through the component's codecs, so an
unrenderable type fails here (FR-024).

**Integration test kit**: `testkit.Start(ctx, service, opts...) (*Kit, error)` and
`testkit.StartT(t, service, opts...) *Kit`. `Kit { HTTP *HTTPClient; Client; Storage; Restart(ctx);
StartBeside(ctx, image, env) (*Beside); Stop(ctx) }`. Options: `SidecarImage`, `Env`, `ReadyTimeout`,
`Postgres` image. Postgres `postgres:17-alpine` with the sidecar image's `/opt/docker/ddl` as init
scripts; the sidecar container with `ANKKA_PROCESS_ADDRESS=host.docker.internal:<port>`,
`ANKKA_SIDECAR_BIND=0.0.0.0`, `ANKKA_HTTP_PORT=9000`, `ANKKA_DB_*`, a random `ANKKA_SECRET_KEY`, the
test's `Env`; ready when `/_ankka/health` answers 200; the process served on `0.0.0.0:0`.

## Reference service (`examples/shopping-cart/`)

The Go copy of `ConformanceReference`: the cart (`shopping-cart`: `add-item`, `remove-item`,
`checkout`, `discard`, `get-cart`, `total-quantity`, snapshot every 3), `conformance` (`record`,
`record-many`, `refuse`, `no-reply`, `delete`, `expire`, `misbehave`, `count`), `profile`,
`checkout` workflow (`reserve`, `wait`, `charge`, `compensate`; `start`, `status`), the views
`cart-rows`, `checkout-rows`, `tree-rows`, `topic-rows`, `joined-rows`, the entities
`replicated-vehicle`, `tree-node`, `joined-left`, `joined-right`, the consumers `checkout-ends`,
`vehicle-feed`, `checkout-recorder`, `checkout-fanout`, `topic-relay`, `contract-relay`,
`partner-relay`, the graph consumers `cart-graph` and `profile-graph`, the timed action `reminder`
(`remind`, `tick`), the agents `assistant` (`ask`, `stream`, `ask-second`; tools `lookup`, `answer`,
`sensitive_lookup`), `approver` (`refund`, `ask_scripted`; MCP servers `tickets`, `guarded`), the
autonomous `answerer`; the endpoints `/carts`, `/joined`, `/vehicles`, `/tree`, `/conformance`,
`/autonomous`, `/callers`, `/private`, `/mcp-keys` with every route the Scala reference declares, and
the MCP endpoint `/mcp` with `get_cart`, `add_item`, `checkout`. The suite's 174 cases are the test.

## Published form

| artifact | identity |
|---|---|
| the module | `github.com/thinkmorestupidless/ankka-go` at tag `v<version>` of the mirror repository, the Go proxy serving it; packages `ankka` (root) and `ankka/testkit`; generated code under `internal/pb` |
| the mirror | `thinkmorestupidless/ankka-go`, `main` replaced by `git subtree split --prefix sdks/go` at each release, never edited there; tags never moved |
| the example image | `sample-shopping-cart-go:latest` locally; two-stage Dockerfile, distroless static base |
| the template | `cli/src/main/templates/go/`, rendered by `ankka init <name> --language go`, pinning `v{{ankka_version}}` |
| the version reported | `ankka.Version()`: the dependency's version from the build's record without its `v`; `0.0.0` for a `replace` or the main module |
