# Contract: the Go SDK

**Feature**: [spec.md](../spec.md) | **Plan**: [plan.md](../plan.md) | **Data model**: [data-model.md](../data-model.md)

What a developer imports and what it promises. The sketches below are the shape of the API, not
its implementation; names may be tightened during implementation but what each guarantees may not.
Module `github.com/thinkmorestupidless/ankka-go`, Go 1.26 or later; packages `ankka` and
`ankka/testkit`. The protocol, descriptor and conformance contracts are feature 009's and later
features' and are unchanged.

## Declaring a service

```go
package main

import ankka "github.com/thinkmorestupidless/ankka-go"

func main() {
    svc := ankka.NewService(cart.Cart, cart.CartRows, cart.API)
    if err := svc.Start(context.Background()); err != nil {   // refuses naming every problem at once
        log.Fatal(err)
    }
}
```

- `NewService` takes component declarations in any order; `Start` validates the whole set and
  refuses once with every problem (duplicate ids, duplicate wire names, shared prefixes, a type the
  codec cannot render, a query that persists, a `nil` ACL, a tool without a description, a topic
  consumer with no start position, an authenticated route with no issuer).
- `Start` binds `127.0.0.1:$ANKKA_PROCESS_PORT` (9010) and dials `$ANKKA_SIDECAR_ADDRESS`
  (`127.0.0.1:9011`); `ankka.Host("0.0.0.0")` is accepted for the test kit and every other host is
  refused. It returns when the server has stopped: on `SIGTERM`/`SIGINT` it stops accepting, lets
  handlers in flight finish within the platform's grace, then exits.
- Discovery answers `protocol_version "1.22"`, `sdk {name: "ankka-go", version: ankka.Version()}`
  and every component and route; a sidecar older than something declared requires is refused,
  naming the version and the oldest unmet reason.
- Problems the sidecar reports back are logged once each through `log/slog`.

## Values and codecs

```go
type LineItem struct {
    ProductID string `json:"productId"`
    Name      string `json:"name"`
    Quantity  int    `json:"quantity"`
}

type CartEvent interface{ cartEvent() }
type ItemAdded struct{ Item LineItem `json:"item"` }
type ItemRemoved struct{ ProductID string `json:"productId"` }
func (ItemAdded) cartEvent()   {}
func (ItemRemoved) cartEvent() {}

var cartEvents = ankka.Cases[CartEvent](
    ankka.Case[CartEvent, ItemAdded]("ItemAdded"),
    ankka.Case[CartEvent, ItemRemoved]("ItemRemoved"),
)

type Standing string
var standings = ankka.Enumeration[Standing]("Running", "Paused", "Failed")
```

- The default codec for a type is built from its fields and `json` tag names at registration; a
  tag option refuses; a pointer is optional; an interface needs `Cases`; a string type with
  `Enumeration` is an enumeration. The rules and the refusals are the data model's table.
- `ankka.CodecFor[T]()` gives the default codec; `ankka.CustomCodec[T](manifest, contentType,
  encode, decode)` supplies one; `ankka.Manifest("shopping-cart")` renames the default manifest.
- Every fixture under `protocol/fixtures` passes both ways through the default codec; one it
  cannot handle fails the SDK's own test, never skips.

## An event sourced entity

```go
type Cart struct {
    Items      []LineItem `json:"items"`
    CheckedOut bool       `json:"checkedOut"`
}

var Cart = ankka.EventSourced[Cart, CartEvent]("shopping-cart", cartEvents,
    func() Cart { return Cart{} },
    func(s Cart, e CartEvent) Cart { /* fold */ },
    ankka.Command("add-item", addItem),
    ankka.Command("remove-item", removeItem),
    ankka.Query("get-cart", getCart),
    ankka.SnapshotEvery(3),
)

func addItem(ctx context.Context, s Cart, item LineItem) (ankka.Effect[Cart, CartEvent, ankka.Done], error) {
    if item.Quantity <= 0 {
        return ankka.Refuse[Cart, CartEvent, ankka.Done](ankka.BadRequest, "quantity must be positive"), nil
    }
    return ankka.Persist[Cart, CartEvent](ItemAdded{item}).ThenReply(func(Cart) ankka.Done { return ankka.Done{} }), nil
}

func getCart(ctx context.Context, s Cart, _ ankka.Unit) (ankka.ReadOnlyEffect[Cart, Cart], error) {
    return ankka.Reply(s), nil
}
```

- `Command` takes a function returning `Effect[S, E, R]`; `Query` one returning
  `ReadOnlyEffect[S, R]`; a query that persists does not compile and is refused at registration
  for a caller who built the effect another way.
- A refusal is `Refuse`, reaching the caller with its code; a returned `error` or a panic is the
  command's failure with the state unchanged; neither stops the process.
- `ankka.CommandFrom(ctx)` gives the entity id, the sequence, the installation the change is
  written at and whether the entity is at its home.

## A workflow, a view, a consumer, a timed action

```go
var Checkout = ankka.Workflow[CheckoutState]("checkout", empty,
    ankka.Steps(ankka.Step("reserve", reserve), ankka.Step("charge", charge), ankka.Step("compensate", compensate)),
    ankka.Command("start", start),
    ankka.Query("status", status),
    ankka.Settings(ankka.StepTimeout(30*time.Second), ankka.Recovery(3, "compensate")),
)

var CartRows = ankka.View[CartRow]("cart-rows",
    ankka.OnChange(Cart, func(ctx context.Context, e CartEvent, row *CartRow) ankka.ViewEffect[CartRow] { ... }),
    ankka.Queries(ankka.Query("by-owner", "SELECT * FROM cart_rows WHERE owner = :owner")),
)

var CheckoutNotifier = ankka.Consumer("checkout-notifier", ankka.Changes(Cart),
    func(ctx context.Context, e CartEvent, m ankka.Metadata) (ankka.ConsumerEffect, error) { ... },
    ankka.ProducesTo("checkouts"))                               // or ankka.ProducesWith(topic, codec) for a manifest of the service's choosing

var Reminder = ankka.TimedAction("reminder", ankka.Action("remind", remind))
```

- A step returns `StepEffect[S]`: transition, pause with a timeout, end or fail; a command during a
  step is answered from the state before it; a second step while one runs is refused.
- A view takes one `OnChange` per source: an entity, a topic (`ankka.Topic(name, ankka.StartFrom(...))`)
  or a workflow (`ankka.WorkflowSource`); queries are declared, recursive or watched.
- A consumer answers `Done`, `Ignore` or `Produce` of one or several messages with keys; a graph
  consumer publishes deltas.

## An endpoint, a socket, an MCP endpoint

```go
var API = ankka.NewEndpoint("/carts", ankka.AllowAll(),
    ankka.Post("/{cartId}/items", func(ctx context.Context, req *ankka.Request, item LineItem) (ankka.Done, error) {
        return ankka.Invoke[ankka.Done](ctx, "shopping-cart", req.Param("cartId"), "add-item", item)
    }),
    ankka.Get("/{cartId}", getCart),
    ankka.SSE("/{cartId}/events", events),                        // iter.Seq2[string, error]
    ankka.SSEEvents("/{cartId}/watched", watched),                 // iter.Seq2[ankka.SSEEvent, error]: named events beside text; ankka.WatchEvents(ankka.Watch(...)) turns a watch into row, removed, caught-up and ended
    ankka.Socket("/{cartId}/live", live),                          // func(ctx, *ankka.Socket) error
    ankka.Get("/admin", admin).ACL(ankka.Authenticate(ankka.Issuer("https://..."))),
)

var CartMcp = ankka.NewMcpEndpoint("/mcp", ankka.AnyOf(ankka.ClientKeys(), authenticated),
    ankka.McpTool("get_cart", "The cart and the principal", getCartTool),
    ankka.McpCommandRef("checkout", Cart, "checkout"),
)
```

- `NewEndpoint` requires the ACL positionally; a `nil` ACL is refused at registration naming the
  endpoint. A route may state its own ACL.
- `*ankka.Request` carries `Param`, `Query`, `Header`, `Body`, and `Principal` with claims by name
  when authenticated. A body type is decoded by its codec; a reply is encoded by its; an
  `*ankka.HttpProblem` returned sets the status.
- `SSE` streams a reply; `Socket` holds a conversation (`Send`, `Receive`, closed when the handler
  returns); both need a sidecar at the minor that carries them.
- An MCP endpoint's tools run in the process with typed arguments and the principal; client keys
  are issued, listed and revoked through `ankka.ClientKeys(ctx)`.

## An agent

```go
var Assistant = ankka.Agent("assistant",
    ankka.Ask("ask", func(ctx context.Context, q string) ankka.AgentEffect {
        return ankka.SystemMessage("You help with carts.").UserMessage(q).Tools("lookup").ThenReply()
    }),
    ankka.AskStream("stream", ...),
    ankka.Tools(ankka.Tool("lookup", "Looks a cart up", func(ctx context.Context, in LookupInput) (Cart, error) { ... })),
    ankka.Guardrails(ankka.Guardrail("no-secrets", check)),
)

type LookupInput struct {
    CartID string `json:"cartId" desc:"The cart to look up"`
}
```

- A tool's input struct yields the JSON Schema the model is given; `desc` tags describe fields; a
  tool with no description is refused at registration.
- The sidecar runs the loop; the process plans, runs tools and consults guardrails. A tool may
  require approval; an MCP server's tools may be offered to the model.

## The component client

See the data model's `Client` section: typed, generic calls (`ankka.Invoke[R]`), view queries and
streams, timers, calls to other services, secrets, client keys and the storage client, all scoped
to the handler's context so the trace connects and a cancelled request cancels the wait. A refusal
is `*ankka.RefusalError`; a sidecar too old for a call is `*ankka.ProtocolTooOldError`.

## The component test kits (`ankka/testkit`)

```go
kit := testkit.EventSourced(cart.Cart)
r := kit.Command(ctx, "add-item", LineItem{"p1", "Pen", 2})
r.Events()      // []CartEvent{ItemAdded{...}}
r.Reply()       // ankka.Done{}
kit.State()     // Cart{Items: ...}
kit.Query(ctx, "get-cart", ankka.Unit{}).Reply()
```

- One kit per kind; no sidecar, database or network; effects shown as values; the state carried
  forward; every value round-tripped through the component's codecs, so an unrenderable type fails
  here.
- The workflow kit follows transitions to a pause; the view kit takes a change from any source
  including a workflow standing; the consumer kit shows what was published and its keys, and a graph
  consumer's elements; the agent kit runs the loop against `testkit.ScriptedModel`, which fails when
  the script runs out.

## The integration test kit

```go
func TestCart(t *testing.T) {
    testkit.Slow(t)                                    // skips unless ANKKA_SLOW=1 (or -short)
    kit := testkit.StartT(t, cart.Service())
    res, _ := kit.HTTP.Post("/carts/c1/items", LineItem{"p1", "Pen", 2})
    kit.Restart(ctx)
    cart, _ := kit.HTTP.GetJSON[Cart]("/carts/c1")
}
```

- Starts Postgres with the platform's DDL (out of the sidecar image) and the real sidecar image
  (`ANKKA_SIDECAR_IMAGE`, else `ankka-sidecar:latest` at version `0.0.0`, else the published image at
  the SDK's version); serves the test's components on `0.0.0.0:0`; waits for `/_ankka/health`;
  offers `HTTP`, `Client` and `Storage`; `Restart` replaces the sidecar on the same database;
  `StartBeside` runs another image on the same journal; `Stop` cleans up, also when startup failed.
- `Start(ctx, ...)` needs no `*testing.T`; `StartT` registers `Stop` with `t.Cleanup`.

## Guarantees the SDK gives the sidecar

- One command in flight per stateful instance, answered in order with the sidecar's id, from the
  state after the reply's events; the snapshot included exactly when asked; state released when the
  stream ends for any reason.
- A workflow: one command and one step in flight; a command mid-step from the pre-step state; a
  second step refused.
- A handler's panic or error is a `Failure` on the protocol, never a dropped reply and never an exit.
- Every value crosses the wire in the encoding of `protocol/ENCODING.md`; a journal written by the
  Scala cart is read by the Go cart and the reverse.
- The process binds loopback (or `0.0.0.0` for the kit) and nothing else; it speaks to its object
  store itself and serves `/_ankka/storage` only to a sidecar at 1.21 or later.

## Commands (from `sdks/go`)

| command | does |
|---|---|
| `go test ./...` | the SDK's and the examples' unit tests, the fixtures both ways (workspace mode, both modules) |
| `ANKKA_SLOW=1 go test ./...` | plus the Docker-backed tests: the cart through the real sidecar with a restart, journal portability both ways, the image's filesystem |
| `go vet ./...` | the vet checks CI runs |
| `scripts/proto.sh` | copies `protocol/` in and regenerates `internal/pb` through the tools module; `--check` regenerates into a temporary directory and diffs |
| `go run ./cmd/conformance` (in `examples/shopping-cart`) | serves the reference service on 9010 with the memory store and runs the platform's conformance suite against it; `ANKKA_CONFORMANCE_ONLY='*es.*'` narrows |
| `examples/shopping-cart/scripts/image.sh` | the example image, `sample-shopping-cart-go:latest`: stages `sdks/go` and the example into one build context, because the example's `go.mod` replaces the SDK with `../..`; the template's Dockerfile needs no staging since it depends on the released module |
