---
name: ankka-go
description: Write, run, test or deploy an ankka service in Go — components as values built by generic constructors (ankka.EventSourced, KeyValue, Workflow, View, Consumer, TimedAction, Agent, NewEndpoint) from parts such as Command, Query, Step, OnChange and Get/Post, explicit registration with ankka.NewService, refusals returned as errors, ReadOnlyEffect for queries, struct json tags as the stored format and ankka.Cases for sum types, the context.Context every handler is given and every call takes, the testkit package and its kits, the sidecar that hosts the process, the gRPC protocol and discovery, the shared JSON encoding with Scala, Python and TypeScript, and the descriptor fields of a process-hosted service. Use when the task involves Go, golang, go.mod, go test, the ankka Go SDK (github.com/thinkmorestupidless/ankka-go), or a service in a language other than Scala.
---

# ankka in Go

A Go service runs as its own process with the ankka runtime beside it as a sidecar. The sidecar owns
everything durable and distributed (sharding, the journal, projections, timers, the workflow engine, the
agent loop and the model's key, HTTP binding and ACLs, cluster membership); the process owns the
decisions: which events for this command, what a step does, an agent's plan and tools. The module
`github.com/thinkmorestupidless/ankka-go` speaks the gRPC protocol so your code never does. Every
component guide in the other ankka skills applies; this skill holds what differs.

## Rules

1. **Every component is a value built by a constructor, from parts.** `var Entity =
   ankka.EventSourced[Cart, Event]("shopping-cart", Events, Empty, Apply, ankka.Command("add-item",
   addItem), ankka.Query("get-cart", getCart), ankka.SnapshotEvery(3))`. The others are `ankka.KeyValue[S]`
   (`ankka.Update`, `ankka.Query`), `ankka.Workflow[S]` (`ankka.WorkflowCommand`, `ankka.Step`, settings),
   `ankka.View[Row]` and `ankka.KeyedView[Row]` (`ankka.OnChange`, `ankka.On`, `ankka.Queries`),
   `ankka.Consumer[Msg]`, `ankka.GraphConsumer[Msg]`, `ankka.TimedAction` (`ankka.Action`), `ankka.Agent`
   (`ankka.Ask`, `ankka.Tools`, `ankka.Guardrails`), `ankka.Autonomous`, `ankka.NewEndpoint(prefix, acl,
   routes...)` and `ankka.NewMcpEndpoint`. The first argument of a handler part is the wire name; the Go
   function's name is yours.
2. **Register explicitly, once.** `ankka.NewService(Entity, Rows, API)` lists every component;
   `svc.Problems()` lists every refusal (duplicate ids or wire names, a type the codec cannot render, a
   nil ACL, a tool with no description, a topic source with no start position) and `svc.Start(ctx)`
   refuses with all of them at once. Assert `Problems()` is empty in a unit test.
3. **Handlers are functions of a context, the state and the input.** A command is `func(ctx
   context.Context, s Cart, in LineItem) (ankka.Effect[Cart, Event, ankka.Done], error)`; a query returns
   `ankka.ReadOnlyEffect[R]` (`ankka.Reply(v)`), so a persisting query does not compile. A handler with no
   input takes `ankka.Unit`. The empty state is `func(id string) S`; the fold is `func(S, E) S`, pure.
4. **A refusal is a returned error.** `return effect[ankka.Done]{}, ankka.Refuse(ankka.Conflict, "cart
   is already checked out")` or `ankka.Refusef(ankka.BadRequest, "quantity was %d", n)`; nothing is
   persisted and the caller sees the code. Any other error, or a panic, is a failure of that one call,
   the state unchanged, and never stops the process. A call that was refused answers `*ankka.RefusalError`
   (`ankka.AsRefusal(err)`), which an endpoint returns to answer its code's HTTP status.
5. **Effects mirror Scala's in Go's case.** `ankka.Persist[S, E, R](events...)` then `.ThenReply(func(S)
   R)`, `.ThenReplyValue(v)`, `.ThenReplyState()`, `.ThenNoReply()`, `.DeleteEntity()`,
   `.ExpireAfter(d)`; key value `ankka.UpdateState[S, R](s)`, `KeepState`, `DeleteState`; workflow
   `ankka.WorkflowUpdate[S, R](s).ThenTransitionTo("charge", input)`, steps `ankka.StepUpdate(s)
   .ThenTransitionTo(..)`, `.ThenPause(ankka.PauseOptions{After, OnTimeout})`, `.ThenEnd()`,
   `.ThenFail(code, msg)`; views `ankka.UpdateRow`, `DeleteRow`, `IgnoreChange`; consumers
   `ankka.Produce`, `ProduceAll`, `ConsumerDone`, `ConsumerIgnore`. Go cannot infer a result type from
   a return statement, so `Persist` and `UpdateState` name their type arguments; a local alias (`type
   effect[R any] = ankka.Effect[Cart, Event, R]`) keeps signatures short.
6. **Types are structs, and the `json` tags are the stored format.** ``ProductID string
   `json:"productId"` `` reads the journal Scala, Python and TypeScript write; a tag option
   (`omitempty`, `string`) is refused, and so are unsigned integers, `any`, maps with non-string keys,
   embedded structs and unexported fields. A pointer is the only optional; `int64` is a long, lossless
   past 2⁵³; `float64` a double; `time.Time` an instant; `time.Duration` a duration. A sum type is a sealed
   interface (an unexported marker method) with `ankka.Cases[Event](ankka.Case[Event,
   ItemAdded]("ItemAdded"), ...)`, written with `"type"`; a string enumeration is
   `ankka.Enumeration[Standing](...)`. Manifests are `ankka.StateCodec(ankka.CodecFor[Cart](
   ankka.Manifest("shopping-cart")))` and `ankka.EventCodec`, `ankka.RowCodec`. A top-level string,
   integer or bool crosses as `text/plain`; `ankka.Done` answers 204 from a route.
7. **The context carries the request; hand it on.** Every handler is given a `context.Context`, and
   every call takes it: `ankka.Invoke[R](ctx, componentID, id, wire, input)`, `ankka.QueryView[Row](ctx,
   viewID, ankka.All())`, `ankka.ViewRow[Row](ctx, viewID, key)`, `ankka.Schedule(ctx, timerID, delay,
   ankka.TimerTarget{ComponentID, Handler}, input)`, `ankka.Cancel`, `ankka.AwaitEnd[R]`,
   `ankka.SendAndAwait[R]`, `ankka.Secrets(ctx)`, `ankka.CallService`. The trace, the caller and
   cancellation ride on it, so work on another goroutine must be given that `ctx`, never
   `context.Background()`, which has no client. `ankka.CommandFrom(ctx)`, `ChangeFrom`, `TimerFrom`,
   `AgentFrom` and `RequestFrom` read what the handler is running for.
8. **Endpoints take the ACL positionally.** `ankka.NewEndpoint("/carts", ankka.AllowAll(), ankka.Get(
   "/{cartId}", get), ankka.Post("/{cartId}/items", add))`, with `ankka.DenyAll()`,
   `ankka.Authenticate(ankka.Issuer("..."))` and `ankka.AllowIf(ankka.ServiceNamed("x"))`; a nil ACL is
   refused. A `Get` or `Delete` handler is `func(ctx, *ankka.Request) (R, error)`, a `Post`/`Put`/`Patch`
   one also takes the decoded body; `req.Param("cartId")`, `req.Query`, `req.Header(name)`,
   `req.Principal`. `&ankka.HttpProblem{Status: 404, Message: "..."}` returned as the error sets a
   status; `.ACL(acl)` on a route replaces the endpoint's. `ankka.SSE(template, f)` answers an
   `iter.Seq2[string, error]`; `ankka.SocketRoute` holds a WebSocket. The process never binds an HTTP port.
9. **Steps and agents run in the sidecar's engines.** Workflow settings are parts:
   `ankka.StepTimeout(d)`, `ankka.StepRecovery(ankka.Recovery{MaxRetries: 1, FailoverTo:
   "compensate"})`, `ankka.ForStep("charge", ...)`, `ankka.WorkflowTimeout(d)`. A step that returns an
   error or panics is retried and failed over; `ThenFail` ends the workflow. An agent's plan names tools
   and guardrails by name (`ankka.SystemMessage(..).UserMessage(q).Tools("lookup").ThenReply()`); a
   tool's input struct is its JSON Schema, a `desc` tag describing each field. The model is configured on
   the sidecar and the process never sees a key.
10. **Test with the kits in `testkit`, under `go test`.** `testkit.EventSourced(Entity,
    testkit.EntityID("c1"))` with `kit.Command(ctx, wire, input)` answering a result (`Events()`,
    `Reply()`, `State()`, `Refusal()`, `Failure()`) and `kit.Query`; `testkit.KeyValue`,
    `testkit.Workflow` (`RunStep`, `RunUntilPause`, `Resume`), `testkit.View` (`OnChange`, `Row`),
    `testkit.KeyedView`, `testkit.Consumer` (`Published()`), `testkit.GraphConsumer`,
    `testkit.TimedAction`, `testkit.Agent(a, session, testkit.NewScriptedModel().ExpectToolCall(..)
    .ExpectText(..))`, `testkit.Endpoint(API, Entity)` and `testkit.Mcp`, with `testkit.Calling(...)`
    for the components a handler calls. No sidecar, every value round-tripped through its codec.
    `testkit.StartT(t, svc)` runs the real sidecar image and Postgres in Docker, only under
    `ANKKA_SLOW=1`, with `kit.HTTP()`, `kit.Context(ctx)` for calls from the test, and `kit.Restart(ctx)`
    to prove durability.
11. **Several messages are one effect, and a graph is published by a graph consumer.**
    `ankka.ProduceAll(ankka.Message{Payload: x, Key: "k"}, …)` publishes several messages for one
    change, each under its own record key if it names one. `ankka.GraphConsumer[Msg](id, source, topic,
    f)` returns `ankka.Publish(ankka.Node(id, ankka.Labels("Cart"), ankka.WithProperties(p)),
    ankka.Edge(id, "TYPE", from, to), …)`; each element is its whole state, and the SDK writes the
    delta's JSON, its `node:<id>`/`edge:<id>` key and its version (the change's sequence, or
    `ankka.AtVersion(v)`). Tombstones come from `ankka.WhenDeleted`. Test with `testkit.GraphConsumer`.
12. **A replicated entity** adds `ankka.Replicated()`; `HandOverTo`, `ReplicateTo` and
    `StopReplicatingTo` follow `Persist`. `ankka.CommandFrom(ctx)` gives `Installation` and `AtHome`;
    `ankka.ApplyWithContext` replaces the fold with one whose context `ankka.EventFrom` reads.
    `ankka.MirroredChanges()` makes a publishing consumer publish at every installation. It needs a
    runtime speaking protocol 1.19.
13. **The process holds no durable state.** It may restart freely; the sidecar re-opens entities when it
    returns. Do not keep state in package variables across commands. Ports: the process listens on 9010,
    the sidecar on 9011, both loopback.
14. **Deploy as a process-hosted service.** The descriptor sets `"hosting": "process"` and
    `"protocol"`; the image holds only your executable (`CGO_ENABLED=0 go build`, on a distroless static
    base); the platform adds the sidecar. `ANTHROPIC_*`, `OPENAI_*`, `ANKKA_MODEL_*` and `ANKKA_DB_*` go to
    the sidecar, everything else to the process.

## Development loop

Go 1.26 or later. `ankka init <name> --language go` makes a project; `go get
github.com/thinkmorestupidless/ankka-go` adds the SDK to one. `go vet ./...`; `go test ./...` runs the
unit kits with no Docker; `ANKKA_SLOW=1 go test ./...` adds the tests that start the sidecar;
`docker compose up -d && go run .` runs the service, its routes on port 9000. `ANKKA_SIDECAR_IMAGE` names
a sidecar image other than the SDK's own version. In the SDK's own checkout, `sdks/go`: the same three
commands, `scripts/proto.sh --check`, and `go run ./cmd/conformance` in `examples/shopping-cart`. See
`references/get-started/first-service-go.md` for the first service and `references/reference/go-sdk.md`
for every constructor, effect, call and kit.

## Mistakes to check for

- A query that persists: it does not compile; do not build the effect another way to get round it.
- A refusal returned as a reply or a plain `errors.New`: only `ankka.Refuse` reaches the caller as its
  code; any other error is a failure.
- A goroutine, `errgroup` or callback calling `ankka.Invoke` with `context.Background()`: it has no
  client and no trace. Pass the handler's `ctx`.
- A `json` tag with `omitempty`, an unsigned integer, an embedded struct, `any`, or a field spelled
  differently from the journal (`product_id` where Scala stores `productId`).
- A sum type whose case is not in its `ankka.Cases`, or an `Apply` with no case for it.
- A field another language stores as a long declared `int` (use `int64`), or a double declared `int`
  (refuses `1.5`).
- A test parsing a `string` reply as JSON, or posting a string body as a JSON string.
- Transitioning to a step name that no `ankka.Step` declares, or `ForStep` naming one.
- A component declared but not passed to `ankka.NewService`; a test that never checks `Problems()`.
- Expecting `go test ./...` to start Docker: integration tests skip unless `ANKKA_SLOW=1`.
- A graph delta built by hand: JSON, a `node:`/`edge:` key or a version written in the handler. Return
  elements from a graph consumer; it writes all three.
- A record key set on a delta, or a delta published through an ordinary consumer's `Produce`.
- A graph consumer that publishes an element another entity owns (a cart publishing the `product` node).
  Publish the edge; the owning entity publishes the node.
- An element built from a thin event, carrying only what changed. An element is its whole state: build it
  from an event that carries it, or read the entity through the client.
- A graph consumer over a topic with no version stated on its elements: a topic has no sequence number.
- Expecting ankka to create the delta topic or make it compacted. Declare it in the ankka-flow pipeline
  that reads it, and deploy that pipeline first.

## Calling another service

`ankka.CallService(ctx, "name", ankka.ServiceRequest{Method, Path, Body})` calls another service as this
one, through the sidecar, which holds the certificate; any status is an answer, and no answer is a
`*ankka.ServiceError`. Make the call from an endpoint, a step, a consumer, a timed action or an agent's
tool, never from an entity or a view, which decide from their own state. See
`references/build/calling-services.md`.

## Reference files

Open the one a task needs; each is one topic and stands alone.

### Get started

- `references/get-started/first-service-go.md` — Write a Go service with an event sourced entity and an HTTP endpoint, run it with go run beside the ankka sidecar, test it with and without the sidecar, and watch it in the local console.

### Concepts

- `references/concepts/polyglot.md` — How ankka hosts a service in Java, Kotlin, Python, TypeScript, Go or Rust — in its own JVM, beside a process as a sidecar, or as a WebAssembly module — with the runtime owning everything durable.

### Build

- `references/build/object-storage.md` — Keep photos, documents and exports in a service's buckets — put, get, list, stat and delete by name, sign a URL a browser uploads or downloads with, give a public address, and run the same code with no object store.
- `references/build/calling-services.md` — Call another service's routes as your own service — from an endpoint, a workflow step, a consumer, a timed action or an agent's tool, in any of the six languages — so that service's access rules can admit yours by name.
- `references/build/graph.md` — Publish a service's entities as nodes and edges with a graph consumer, which writes versioned graph deltas to a topic for a graph database to follow, with no key, version or JSON written by hand.
- `references/build/serialization.md` — How ankka encodes state, events, arguments and messages as JSON under a named manifest, what the JSON looks like in every language, and how to change a stored type without breaking a journal.
- `references/build/testing.md` — Test ankka components at two levels in every language, with unit test kits that run one component and nothing else, integration test kits that run the whole service against a real database, and scripted models.

### Run and deploy

- `references/deploy/deploy-a-service.md` — Write a service descriptor, apply it with the ankka CLI, and follow the service from UpdateInProgress to Ready, including environment variables, secrets, version declarations and Python services.

### Reference

- `references/reference/go-sdk.md` — A compact map of the Go SDK — installing it, values and codecs, and for every component kind its constructor, handler shapes, effects and test kit — plus the request context, the image and the SDK's own development commands.
- `references/reference/sidecar-protocol.md` — The gRPC protocol between the ankka sidecar and a service's process — transport, the discovery handshake, every service and RPC, the payload encoding, versioning, and the rules the messages do not state on their own.
