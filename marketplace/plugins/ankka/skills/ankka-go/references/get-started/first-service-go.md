# Your first service in Go

> Write a Go service with an event sourced entity and an HTTP endpoint, run it with go run beside the ankka sidecar, test it with and without the sidecar, and watch it in the local console.

Source: https://docs.ankka.cloud/get-started/first-service-go/
This tutorial writes a shopping cart in Go, runs it on your machine with `go run`, tests it, and looks at
it in the local console. You need Go 1.26 or later, Docker, and the `ankka-sidecar` image from
[Install the tools](install.md). No JVM.

A Go service runs as its own process. ankka's runtime runs beside it as a **sidecar**: a container that
owns everything stateful and distributed: the journal, sharding, projections, timers, HTTP and the agent
loop. Your process owns the decisions: given this command and this state, what should happen. The two
talk over gRPC on loopback, and the SDK hides that entirely.
[Services in other languages](../concepts/polyglot.md) explains the model.

## Create the project

This page builds the project one file at a time, so that each part is explained. To start from a
complete project instead, run `ankka init cart --language go`: it writes an entity, a view, an endpoint,
tests at both levels, a `docker-compose.yml` that starts Postgres and the sidecar, a Dockerfile, the
descriptor and GitHub workflows, and its README says how to run each. To follow this page, start empty:

```bash
mkdir cart && cd cart
go mod init example.com/cart
go get github.com/thinkmorestupidless/ankka-go@v0.14.0     # the version of the platform you will deploy to
```

The module has two packages: `ankka`, which a service imports, and `ankka/testkit`, which only its tests
import. The test kit's container dependencies reach a test binary and never the service's executable.

## An entity

An event sourced entity keeps its state as the fold of the events it persisted. Create `cart.go`:

```go
import (
	"context"

	ankka "github.com/thinkmorestupidless/ankka-go"
)

type LineItem struct {
	ProductID string `json:"productId"`
	Name      string `json:"name"`
	Quantity  int    `json:"quantity"`
}

type ShoppingCart struct {
	CartID     string     `json:"cartId"`
	Items      []LineItem `json:"items"`
	CheckedOut bool       `json:"checkedOut"`
}

// An event is one of a closed set of types, each written under its discriminator.
type Event interface{ cartEvent() }

type ItemAdded struct {
	Item LineItem `json:"item"`
}

type CheckedOut struct{}

func (ItemAdded) cartEvent()  {}
func (CheckedOut) cartEvent() {}

var events = ankka.Cases[Event](
	ankka.Case[Event, ItemAdded]("ItemAdded"),
	ankka.Case[Event, CheckedOut]("CheckedOut"),
)

var ShoppingCartEntity = ankka.EventSourced[ShoppingCart, Event]("shopping-cart", events, empty, apply,
	ankka.Command("add-item", addItem),
	ankka.Command("checkout", checkout),
	ankka.Query("get-cart", func(_ context.Context, cart ShoppingCart, _ ankka.Unit) (ankka.ReadOnlyEffect[ShoppingCart], error) {
		return ankka.Reply(cart), nil
	}),
	ankka.StateCodec(ankka.CodecFor[ShoppingCart](ankka.Manifest("shopping-cart"))),
	ankka.EventCodec(ankka.CodecFor[Event](ankka.Manifest("shopping-cart-event"))),
)

func empty(cartID string) ShoppingCart { return ShoppingCart{CartID: cartID, Items: []LineItem{}} }

func apply(cart ShoppingCart, event Event) ShoppingCart {
	switch e := event.(type) {
	case ItemAdded:
		cart.Items = append(cart.Items, e.Item)
	case CheckedOut:
		cart.CheckedOut = true
	}
	return cart
}

type effect[R any] = ankka.Effect[ShoppingCart, Event, R]

func addItem(_ context.Context, cart ShoppingCart, item LineItem) (effect[ankka.Done], error) {
	if cart.CheckedOut {
		return effect[ankka.Done]{}, ankka.Refuse(ankka.Conflict, "cart is already checked out")
	}
	if item.Quantity <= 0 {
		return effect[ankka.Done]{}, ankka.Refusef(ankka.BadRequest, "quantity must be greater than zero, was %d", item.Quantity)
	}
	return ankka.Persist[ShoppingCart, Event, ankka.Done](ItemAdded{Item: item}).
		ThenReply(func(ShoppingCart) ankka.Done { return ankka.Done{} }), nil
}

func checkout(_ context.Context, cart ShoppingCart, _ ankka.Unit) (effect[ShoppingCart], error) {
	if cart.CheckedOut {
		return effect[ShoppingCart]{}, ankka.Refuse(ankka.Conflict, "cart is already checked out")
	}
	return ankka.Persist[ShoppingCart, Event, ShoppingCart](CheckedOut{}).ThenReplyState(), nil
}
```

Five things in this file are true of every ankka component in Go:

- **A struct's `json` tags are the stored format.** The codec writes `productId` exactly as tagged. A
  Scala, Java, Kotlin, Python, TypeScript or Rust service reading the same journal expects the same names, so keep them once
  data exists.
- **An event is one of a closed set.** `Event` is an interface only this package's types satisfy, and
  `ankka.Cases` names each case's discriminator, which is what the journal holds beside it. Start with a
  sum type even with one event, so a second one later does not change how the first is stored.
- **A handler returns an effect.** `Persist(...).ThenReply(...)` is a description of what should happen.
  The handler performs no I/O, which is why it can be tested with nothing running. A refusal is an
  ordinary returned error, made with `ankka.Refuse` or `ankka.Refusef`, and persists nothing.
- **The wire name is the first argument of `Command` and `Query`.** `"add-item"` is what the platform
  stores and routes by. Renaming the Go function `addItem` changes nothing; renaming `"add-item"` is a
  protocol change.
- **A query cannot persist.** A query's handler returns `ankka.ReadOnlyEffect[R]`, which nothing that
  persists can be. Return one that persists and the file does not compile.

## An endpoint

An endpoint is the service's HTTP edge. Create `api.go`:

```go
import (
	"context"

	ankka "github.com/thinkmorestupidless/ankka-go"
)

var ShoppingCartEndpoint = ankka.NewEndpoint("/carts", ankka.AllowAll(),
	ankka.Post("/{cartId}/items", func(ctx context.Context, req *ankka.Request, item LineItem) (ankka.Done, error) {
		return ankka.Invoke[ankka.Done](ctx, "shopping-cart", req.Param("cartId"), "add-item", item)
	}),
	ankka.Post("/{cartId}/checkout", func(ctx context.Context, req *ankka.Request, _ ankka.Unit) (ShoppingCart, error) {
		return ankka.Invoke[ShoppingCart](ctx, "shopping-cart", req.Param("cartId"), "checkout", ankka.Unit{})
	}),
	ankka.Get("/{cartId}", func(ctx context.Context, req *ankka.Request) (ShoppingCart, error) {
		return ankka.Invoke[ShoppingCart](ctx, "shopping-cart", req.Param("cartId"), "get-cart", ankka.Unit{})
	}),
)
```

A route names its template and the function that runs it; a `Post` route's function takes the decoded
body as its third argument, and its return value is encoded as the reply. `ankka.Done` answers 204, and a
refusal from the entity answers the status its code maps to. Your process never binds an HTTP port: the
sidecar serves the routes you declared, applies the endpoint's access rule, and forwards each request.
The access rule is a required argument, because an access rule nobody chose is not a default worth
having. `ankka.AllowAll()` is fine on your machine; decide it deliberately before the service is exposed,
as [HTTP endpoints](../build/http-endpoints.md) shows. The `context.Context` a route is handed carries the
request, which is what makes the entity call appear as a child of the request in a trace.

## Run it

Register both components and start. Create `main.go`:

```go
import (
	"context"
	"fmt"
	"os"

	ankka "github.com/thinkmorestupidless/ankka-go"
)

func service() *ankka.Service { return ankka.NewService(ShoppingCartEntity, ShoppingCartEndpoint) }

func main() {
	if err := service().Start(context.Background()); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}
```

Registration is explicit: a component that is not registered does not exist. `Start` serves the protocol
on port 9010 on loopback, where the sidecar finds it, and refuses to start, naming every problem, when a
component is declared wrongly.

Start Postgres and the sidecar from the root of the ankka repository, then your process:

```bash
docker compose --profile polyglot up -d      # in the ankka repository: Postgres and the sidecar
go run .                                     # in your project: your process, on 9010
```

In another terminal:

```bash
curl -X POST localhost:9000/carts/c1/items -H 'content-type: application/json' \
     -d '{"productId":"p1","name":"Pen","quantity":2}'
curl localhost:9000/carts/c1
# {"cartId":"c1","items":[{"productId":"p1","name":"Pen","quantity":2}],"checkedOut":false}
```

Stop both and start both again, then read the cart: it is still there. The journal belongs to the sidecar
and lives in Postgres, under the same records a Scala service writes.

## Test it without the sidecar

The unit test kits run a component with no sidecar and no network. Create `cart_test.go`:

```go
var pen = LineItem{ProductID: "p1", Name: "Pen", Quantity: 2}

func TestAddingAnItemPersistsOneEvent(t *testing.T) {
	kit := testkit.EventSourced(ShoppingCartEntity, testkit.EntityID("c1"))
	result := kit.Command(context.Background(), "add-item", pen)
	if len(result.Events()) != 1 || result.Events()[0] != (ItemAdded{Item: pen}) {
		t.Fatalf("add-item persisted %v", result.Events())
	}
	cart := kit.Query(context.Background(), "get-cart", ankka.Unit{}).Reply().(ShoppingCart)
	if len(cart.Items) != 1 || cart.Items[0] != pen {
		t.Fatalf("the cart holds %v", cart.Items)
	}
}

func TestARefusedCommandPersistsNothing(t *testing.T) {
	kit := testkit.EventSourced(ShoppingCartEntity, testkit.EntityID("c1"))
	result := kit.Command(context.Background(), "add-item", LineItem{ProductID: "p1", Name: "Pen"})
	if result.Refusal() == nil || result.Refusal().Code != ankka.BadRequest || len(result.Events()) != 0 {
		t.Fatalf("a quantity of zero answered %+v", result)
	}
}
```

```bash
go test ./...
```

These take milliseconds. Inputs, events, state and replies still round-trip through your codecs, so a
shape the codec cannot express fails here rather than on first deployment. The kits need nothing beyond the
standard `testing` package.

## Test it through the real sidecar

The integration test kit starts Postgres and the sidecar image in Docker, serves your components to it,
and gives you an HTTP client for your routes. Add to `cart_test.go`:

```go
func TestACartSurvivesARestart(t *testing.T) {
	kit := testkit.StartT(t, service()) // skipped unless ANKKA_SLOW=1
	ctx := t.Context()
	if _, err := kit.HTTP().Post(ctx, "/carts/c1/items", pen); err != nil {
		t.Fatal(err)
	}
	if err := kit.Restart(ctx); err != nil { // a new sidecar, the same database
		t.Fatal(err)
	}
	cart, err := testkit.GetJSON[ShoppingCart](ctx, kit.HTTP(), "/carts/c1")
	if err != nil || len(cart.Items) != 1 || cart.Items[0].Name != "Pen" {
		t.Fatalf("after a restart the cart is %+v (%v)", cart, err)
	}
}
```

```bash
ANKKA_SLOW=1 go test ./...
```

`StartT` skips the test unless `ANKKA_SLOW=1` is set, so `go test ./...` stays fast and needs no Docker,
and it stops the containers when the test ends. `Restart` replaces the sidecar and keeps the database,
which is how a test proves the state is durable rather than cached. The sidecar is the one published with
the SDK's version; `ANKKA_SIDECAR_IMAGE` names another.

## Watch it in the local console

```bash
ankka local console       # http://localhost:9889
```

The sidecar is a local ankka service like any other, so the console lists it: its components, a form per
route, and a trace of each request. The trace of `POST /{cartId}/items` shows the endpoint and the
`shopping-cart#add-item` call beneath it. The console reads a cart's state through the entity's declared
query `get-cart`, and refuses to run a command. [The local console](../operate/local-console.md) covers
the rest.

## Next

[Deploy to a local platform](deploy-locally.md). A Go service is deployed exactly as a Scala one is, with
two extra fields in its descriptor: `"hosting": "process"` and the protocol version its SDK speaks. The
image holds only your executable: build it with `CGO_ENABLED=0 go build` in a `golang` image and copy it
onto `gcr.io/distroless/static-debian12:nonroot`, which has no shell and no Go toolchain.
[The Go SDK](../reference/go-sdk.md) is the reference for everything else.
