# Go SDK

> A compact map of the Go SDK — installing it, values and codecs, and for every component kind its constructor, handler shapes, effects and test kit — plus the request context, the image and the SDK's own development commands.

Source: https://docs.ankka.cloud/reference/go-sdk/
The Go SDK is the module `github.com/thinkmorestupidless/ankka-go`, for Go 1.26 and later. A Go service
is a program that the ankka sidecar hosts: the sidecar owns the journal, sharding, projections, timers,
HTTP and the agent loop, and your functions decide what each command does. This page lists what each
component kind is made of. [Services in other languages](../concepts/polyglot.md) explains the model.

## Installing

The module is published at the same version as every ankka release, so pin the version of the platform
you deploy to:

```bash
go get github.com/thinkmorestupidless/ankka-go@v0.14.0
```

The floor is Go 1.26. A service runs with the standard toolchain, `go run .`, and builds to one static
executable with `CGO_ENABLED=0`; nothing else is installed. The module depends on `google.golang.org/grpc`
and `google.golang.org/protobuf`, and its test kit on `testcontainers-go`. They are one module, so
`go.sum` lists both, but a program links only the packages it imports: a service that does not import
the test kit carries none of the Docker client. Docker is needed for integration tests and for running the
sidecar locally; a JVM is not.

A tag of the module is never moved once it is published, because the Go checksum database remembers the
first content it saw for a version and every later `go get` checks against it. A version withdrawn is
retracted in a later release's `go.mod`.

## The module and its packages

| Import | Holds |
|---|---|
| `github.com/thinkmorestupidless/ankka-go`, imported as `ankka` | every component constructor, the effects, the codecs, the request context, the client calls, the service and its options |
| `github.com/thinkmorestupidless/ankka-go/testkit` | the component test kits, which run under `go test` with nothing else, and the integration test kit, which starts the sidecar and Postgres in Docker |

## Values and codecs

A value that crosses the protocol is an ordinary Go type, and its codec is built from the type by
reflection when the service, or a test kit, first uses it. The cart's domain:

```go
// LineItem is one product in a cart.
type LineItem struct {
	ProductID string `json:"productId"`
	Name      string `json:"name"`
	Quantity  int    `json:"quantity"`
}

// Cart is a cart's state.
type Cart struct {
	CartID     string     `json:"cartId"`
	Items      []LineItem `json:"items"`
	CheckedOut bool       `json:"checkedOut"`
}


// Event is what a cart persists.
type Event interface{ cartEvent() }

// ItemAdded is an item put in the cart.
type ItemAdded struct {
	Item LineItem `json:"item"`
}

// ItemRemoved is a product taken out of the cart.
type ItemRemoved struct {
	ProductID string `json:"productId"`
}

// CheckedOut is the cart ordered.
type CheckedOut struct{}

// Discarded is the cart thrown away.
type Discarded struct{}

func (ItemAdded) cartEvent()   {}
func (ItemRemoved) cartEvent() {}
func (CheckedOut) cartEvent()  {}
func (Discarded) cartEvent()   {}

// Events are the cases of Event, under the Scala sample's discriminators.
var Events = ankka.Cases[Event](
	ankka.Case[Event, ItemAdded]("ItemAdded"),
	ankka.Case[Event, ItemRemoved]("ItemRemoved"),
	ankka.Case[Event, CheckedOut]("CheckedOut"),
	ankka.Case[Event, Discarded]("Discarded"),
)
```

| Go type | On the wire |
|---|---|
| `string` | a JSON string; at top level, `text/plain` |
| `int`, `int32` | a JSON integer; at top level, the `int` text payload. A fraction on read is refused |
| `int64` | a JSON integer, lossless past 2⁵³; at top level, `long` |
| `int16`, `int8` | a JSON integer; at top level, `short` and `byte` |
| `float64`, `float32` | a JSON number as Scala writes it: `1.0`, `1.0E10`. `NaN` and the infinities are refused |
| `bool` | `true` / `false` |
| `time.Time` | ISO-8601 in UTC with 0, 3, 6 or 9 fractional digits; the monotonic reading is dropped |
| `time.Duration` | ISO-8601, `PT1.5S`; at top level, `duration-millis` |
| `ankka.LocalDate`, `ankka.LocalDateTime` | ISO-8601 strings |
| `[]byte` | base64 inside JSON; raw bytes at top level |
| `*T` | `null` when nil; an absent field reads as nil. A pointer is the only way to say optional |
| `[]T`, `[N]T` | a JSON array; `[]` when nil or empty |
| `map[string]T` | a JSON object, its keys written sorted |
| a struct | a JSON object, every field written, in declaration order |
| an interface with registered cases | the case's object with `"type": "<Case>"` |
| a named string type with registered values | `{"type": "Ready"}`; a bare string is accepted on read |
| `ankka.Done` | the empty `done` payload, the reply of a handler that answers only that it finished |
| `ankka.Unit` | the empty `unit` payload, the input of a handler that takes nothing |

The encoding is exactly what the Scala, Java, Kotlin, Python, TypeScript and Rust SDKs write, so one journal is
readable from all of them; [Serialization](../build/serialization.md) has the rules. The JSON is written
by the SDK, never by `encoding/json`, so a number never passes through a float on its way.

**Field names come from the `json` tag**, or the field's name in lower camel case when it has none; the
name is the wire contract, so a Go service sharing data with a Scala one writes `productId`. A tag with an
option (`omitempty`, `string`) is refused, because every field is written and an optional field is a
pointer. `json:"-"` leaves a field out.

**A sum type is an interface whose cases are registered** with `ankka.Cases[I](ankka.Case[I, T]("Name"), …)`,
each case a struct that implements the interface; the declaration that stores the interface (an
entity's events, say) is given the registration. **An enumeration is a named string type whose values
are registered** with `ankka.Enumeration[E]("Running", "Paused", "Failed")`.

**A codec of your own** is `ankka.CustomCodec[T](manifest, contentType, encode, decode)`;
`ankka.CodecFor[T](ankka.Manifest("shopping-cart"))` is the default codec under a manifest of your
choosing, which is what the journal stores beside each value. A declaration takes either:
`ankka.StateCodec`, `ankka.EventCodec`, `ankka.RowCodec`, `ankka.ProducesWith`.

**What is refused**, at start and in the component test kit, naming the type and the field: unsigned
integers, complex numbers, channels, functions, `any` and an interface with no registered cases, a map
whose keys are not strings, an unexported field, an embedded struct, a tag with an option, two fields
with one name, a pointer to a pointer, and a case with a field named `type`. A stored record missing a
field is refused rather than read as the zero value, and a field the type does not know is ignored.

## The shape every component shares

A component is a value built by a constructor that takes what the kind cannot do without, then its
handlers and options as further arguments. A handler is a plain function, never a method: it is given
the request's `context.Context`, the current state where the kind has one, and the decoded input, and it
returns an effect and an error. The first argument of every handler constructor is the wire name, which is
the platform's; the Go function's name is yours.

The Go API differs from the other SDKs' in four deliberate ways:

- **A refusal is a returned error.** `return effect{}, ankka.Refuse(ankka.Conflict, "cart is already checked out")`,
  or `ankka.Refusef(code, format, args…)`. It reaches the caller with its code and nothing is persisted.
  Any other error, and a panic, is the command's failure, with the state unchanged; neither stops the
  process.
- **A query returns `ankka.ReadOnlyEffect[R]`**, a type no persisting builder makes, so a query that
  persists does not compile.
- **The empty state is a function of the entity id**, `func(entityID string) S`, so a state can carry its
  own id from the start.
- **There is no personal-data envelope.** The erasable envelope the Scala SDK carries has no Go
  counterpart.

`ankka.NewService(declarations…)` takes every component in any order. Registration is explicit;
`Start` refuses before it binds anything when the declarations have problems, naming every one at
once, and `Problems()` returns them for a test.

## Event sourced entity

| Part | API |
|---|---|
| Constructor | `ankka.EventSourced[S, E](id, events *SumType[E], empty func(string) S, apply func(S, E) S, parts…)`; `events` is nil when `E` is a struct |
| Handlers | `ankka.Command(wire, func(ctx, S, I) (Effect[S, E, R], error))`, `ankka.Query(wire, func(ctx, S, I) (ReadOnlyEffect[R], error))` |
| Options | `ankka.SnapshotEvery(n)` (0, never, by default), `ankka.StateCodec`, `ankka.EventCodec`, `ankka.Replicated()`, `ankka.ApplyWithContext(func(ctx, S, E) S)` |
| Effects | `ankka.Persist[S, E, R](events…)`, then `.ThenReply(func(S) R)`, `.ThenReplyValue(r)`, `.ThenReplyState()`, `.ThenNoReply()`, `.DeleteEntity()`, `.ExpireAfter(d)`; `ankka.Reply(r)`, `ankka.NoReply[R]()` in a query |
| In a handler | `ankka.CommandFrom(ctx)`: `EntityID`, `Sequence`, `Installation`, `AtHome`, `Metadata` |
| Replication | `ankka.Replicated()`; `.HandOverTo(installation)`, `.ReplicateTo(installation)`, `.StopReplicatingTo(installation)` on `Persist`; `ankka.EventFrom(ctx)` inside an apply given with `ApplyWithContext`: `Installation`, `Local` |

```go
// Entity is the shopping cart: the same wire names and manifests as the Scala sample's.
var Entity = ankka.EventSourced[Cart, Event](ComponentID, Events, Empty, Apply,
	ankka.Command("add-item", addItem),
	ankka.Command("remove-item", removeItem),
	ankka.Command("checkout", checkout),
	ankka.Command("discard", discard),
	ankka.Query("get-cart", func(_ context.Context, c Cart, _ ankka.Unit) (ankka.ReadOnlyEffect[Cart], error) {
		return ankka.Reply(c), nil
	}),
	ankka.Query("total-quantity", func(_ context.Context, c Cart, _ ankka.Unit) (ankka.ReadOnlyEffect[int], error) {
		return ankka.Reply(TotalQuantity(c)), nil
	}),
	ankka.StateCodec(ankka.CodecFor[Cart](ankka.Manifest("shopping-cart"))),
	ankka.EventCodec(ankka.CodecFor[Event](ankka.Manifest("shopping-cart-event"))),
	ankka.SnapshotEvery(3),
)

var checkedOut = ankka.Refuse(ankka.Conflict, "cart is already checked out")

func done(Cart) ankka.Done { return ankka.Done{} }

func addItem(_ context.Context, c Cart, item LineItem) (effect[ankka.Done], error) {
	if c.CheckedOut {
		return effect[ankka.Done]{}, checkedOut
	}
	if item.Quantity <= 0 {
		return effect[ankka.Done]{}, ankka.Refusef(ankka.BadRequest, "quantity must be greater than zero, was %d", item.Quantity)
	}
	return ankka.Persist[Cart, Event, ankka.Done](ItemAdded{Item: item}).ThenReply(done), nil
}

func removeItem(_ context.Context, c Cart, productID string) (effect[ankka.Done], error) {
	if c.CheckedOut {
		return effect[ankka.Done]{}, checkedOut
	}
	if !Contains(c, productID) {
		return effect[ankka.Done]{}, ankka.Refusef(ankka.NotFound, "cart does not contain '%s'", productID)
	}
	return ankka.Persist[Cart, Event, ankka.Done](ItemRemoved{ProductID: productID}).ThenReply(done), nil
}

// checkout keeps the cart, the record of what was ordered, and refuses every later change to it.
func checkout(_ context.Context, c Cart, _ ankka.Unit) (effect[Cart], error) {
	if c.CheckedOut {
		return effect[Cart]{}, checkedOut
	}
	if len(c.Items) == 0 {
		return effect[Cart]{}, ankka.Refuse(ankka.BadRequest, "cannot check out an empty cart")
	}
	return ankka.Persist[Cart, Event, Cart](CheckedOut{}).ThenReplyState(), nil
}

// discard persists the event, then deletes the cart, so a consumer downstream sees the discard
// rather than a cart that vanished; the same id then starts again empty.
func discard(_ context.Context, c Cart, _ ankka.Unit) (effect[ankka.Done], error) {
	if c.CheckedOut {
		return effect[ankka.Done]{}, checkedOut
	}
	return ankka.Persist[Cart, Event, ankka.Done](Discarded{}).DeleteEntity().ThenReply(done), nil
}
```

See [Event sourced entities](../build/event-sourced-entities.md) and
[Replicated entities](../concepts/replicated-entities.md).

## Key value entity

| Part | API |
|---|---|
| Constructor | `ankka.KeyValue[S](id, empty func(string) S, parts…)` |
| Handlers | `ankka.Update(wire, func(ctx, S, I) (KeyValueEffect[S, R], error))`, `ankka.Query` as for an event sourced entity |
| Effects | `ankka.UpdateState[S, R](s)`, `ankka.KeepState[S, R]()`, `ankka.DeleteState[S, R]()`, then `.ThenReply`, `.ThenReplyValue`, `.ThenReplyState`, `.ThenNoReply`, `.ExpireAfter(d)` |

```go
// ProfileState is a profile's state.
type ProfileState struct {
	Name string `json:"name"`
}

type kvEffect[R any] = ankka.KeyValueEffect[ProfileState, R]

// Profile is the key value entity: set, get and delete a name.
var Profile = ankka.KeyValue[ProfileState]("profile", func(string) ProfileState { return ProfileState{} },
	ankka.Update("set", func(_ context.Context, _ ProfileState, name string) (kvEffect[string], error) {
		if name == "" {
			return kvEffect[string]{}, ankka.Refuse(ankka.BadRequest, "a name is needed")
		}
		return ankka.UpdateState[ProfileState, string](ProfileState{Name: name}).ThenReplyValue("done"), nil
	}),
	ankka.Query("get", func(_ context.Context, s ProfileState, _ ankka.Unit) (ankka.ReadOnlyEffect[string], error) {
		if s.Name == "" {
			return ankka.Reply("none"), nil
		}
		return ankka.Reply(s.Name), nil
	}),
	ankka.Update("delete", func(_ context.Context, _ ProfileState, _ ankka.Unit) (kvEffect[string], error) {
		return ankka.DeleteState[ProfileState, string]().ThenReplyValue("done"), nil
	}),
	ankka.StateCodec(ankka.CodecFor[ProfileState](ankka.Manifest("profile"))),
)
```

See [Key value entities](../build/key-value-entities.md).

## View

| Part | API |
|---|---|
| Constructor | `ankka.View[Row](id, parts…)` |
| A source | `ankka.OnChange(source, func(ctx, change Src, row *Row) (ViewEffect[Row], error))`; a source is `ankka.Changes(entity)`, `ankka.WorkflowSource(workflow)` or `ankka.Topic(name, opts…)` |
| Options | `ankka.OnDelete(func(ctx, row *Row) (ViewEffect[Row], error))`, which deletes the row by default; `ankka.RowCodec`; `ankka.Queries(ankka.Declared(name, sql), ankka.WatchedQuery(name, sql))`; `ankka.SourceVersion(n)` |
| Effects | `ankka.UpdateRow(row)`, `ankka.DeleteRow[Row]()`, `ankka.IgnoreChange[Row]()` |
| In a handler | `ankka.ChangeFrom(ctx)`: `Subject`, `Sequence`, `Standing` (a workflow source's, else nil), `Installation`, `Local`, `Metadata` |
| Querying | `ankka.ViewRow[Row](ctx, viewID, key)`, `ankka.QueryView[Row](ctx, viewID, ankka.All())` or `ankka.Named(name, values)`, `ankka.QueryStream[Row]`, `ankka.Watch[Row](ctx, viewID, ankka.WatchQuery(name, values))` or `ankka.WatchKey(key)` |
| Table name | `ankka.TableOf(viewID)`, for a statement to name |

```go
// Row is one cart as the view keeps it: what it holds by product, and whether it was checked out.
type Row struct {
	CartID     string         `json:"cartId"`
	Quantities map[string]int `json:"quantities"`
	CheckedOut bool           `json:"checkedOut"`
}

// RowsID is the view's id.
const RowsID = "cart-rows"

// Rows is a queryable projection of every cart: the entity answers by cart id, this answers the
// rest.
var Rows = ankka.View[Row](RowsID,
	ankka.OnChange(ankka.Changes(Entity), func(ctx context.Context, e Event, row *Row) (ankka.ViewEffect[Row], error) {
		current := Row{Quantities: map[string]int{}}
		if row != nil {
			current = *row
			current.Quantities = maps.Clone(row.Quantities)
			if current.Quantities == nil {
				current.Quantities = map[string]int{}
			}
		}
		if c, ok := ankka.ChangeFrom(ctx); ok && current.CartID == "" {
			current.CartID = c.Subject
		}
		switch e := e.(type) {
		case ItemAdded:
			current.Quantities[e.Item.ProductID] += e.Item.Quantity
		case ItemRemoved:
			delete(current.Quantities, e.ProductID)
		case CheckedOut:
			current.CheckedOut = true
		default:
			// Discarded: the deletion that follows removes the row.
			return ankka.IgnoreChange[Row](), nil
		}
		return ankka.UpdateRow(current), nil
	}),
	ankka.RowCodec(ankka.CodecFor[Row](ankka.Manifest("cart-row"))),
)
```

## Keyed view

| Part | API |
|---|---|
| Constructor | `ankka.KeyedView[Row](id, parts…)` |
| A source | `ankka.On(source, func(ctx, change Src, rows ViewRows[Row]) (KeyedViewEffect[Row], error), deleted…)` |
| In a handler | `rows.Get(key)`, `rows.Ask(name, values)`, `ankka.ChangeFrom(ctx)` |
| Effects | `ankka.UpdateRowAt(key, row)`, `ankka.DeleteRowAt[Row](key)`, `ankka.IgnoreRows[Row]()`, `ankka.AllRows(effects…)` to combine |

```go
// JoinedRows: the left names a row key|holding and writes it from what it held, noting itself;
// the right finds every row holding it by asking the view's own query, and notes itself on each.
var JoinedRows = ankka.KeyedView[JoinedRow]("joined-rows",
	ankka.On(ankka.Changes(JoinedLeft), func(_ context.Context, e Noted, rows ankka.ViewRows[JoinedRow]) (ankka.KeyedViewEffect[JoinedRow], error) {
		key, holding, _ := strings.Cut(e.Text, "|")
		held, _, err := rows.Get(key)
		if err != nil {
			return ankka.KeyedViewEffect[JoinedRow]{}, err
		}
		notes := append(slices.Clone(held.Notes), "left")
		return ankka.UpdateRowAt(key, JoinedRow{Key: key, Holding: holding, Notes: notes}), nil
	}),
	ankka.On(ankka.Changes(JoinedRight), func(ctx context.Context, _ Noted, rows ankka.ViewRows[JoinedRow]) (ankka.KeyedViewEffect[JoinedRow], error) {
		c, _ := ankka.ChangeFrom(ctx)
		theirs, err := rows.Ask("of-right", map[string]string{"holding": c.Subject})
		if err != nil {
			return ankka.KeyedViewEffect[JoinedRow]{}, err
		}
		var each []ankka.KeyedViewEffect[JoinedRow]
		for _, r := range theirs {
			r.Notes = append(slices.Clone(r.Notes), "right")
			each = append(each, ankka.UpdateRowAt(r.Key, r))
		}
		return ankka.AllRows(each...), nil
	}),
	ankka.RowCodec(ankka.CodecFor[JoinedRow](ankka.Manifest("joined-row"))),
	ankka.Queries(ankka.Declared("of-right", "SELECT payload FROM "+ankka.TableOf("joined-rows")+" WHERE payload::jsonb->>'holding' = :holding ORDER BY row_key")),
)
```

See [Views](../build/views.md).

## Consumer

| Part | API |
|---|---|
| Constructor | `ankka.Consumer[Msg](id, source, func(ctx, Msg) (ConsumerEffect, error), parts…)` |
| A topic source | `ankka.Topic(name, ankka.StartFrom(ankka.Earliest() \| ankka.Latest() \| ankka.StartAt(t)), ankka.ExpectContract(c), ankka.OnBroker(name), ankka.FromProject(p), ankka.Parallel())`; a consumer must say where it starts |
| Publishing | `ankka.ProducesTo[Out](topic, opts…)`, or `ankka.ProducesWith(topic, codec, opts…)` for a manifest of your own; `ankka.StatesContract(c)`, `ankka.ToBroker(name)`, `ankka.ToProject(p)` |
| Options | `ankka.WhenDeleted(func(ctx) (ConsumerEffect, error))`, which ignores by default; `ankka.MirroredChanges()` for a replicated source's changes written elsewhere |
| Effects | `ankka.Produce(payload, metadata…)`, `ankka.ProduceAll(ankka.Message{Payload, Key, Metadata}…)`, `ankka.ConsumerDone()`, `ankka.ConsumerIgnore()` |
| In a handler | `ankka.ChangeFrom(ctx)`, as for a view; the context calls other components |

```go
// CheckoutFanout answers three messages for a checkout (the second under a key of its own, the
// third with a header), none for an item added, and a single one, the old way, for an item removed.
var CheckoutFanout = ankka.Consumer[cart.Event]("checkout-fanout", ankka.Changes(cart.Entity),
	func(ctx context.Context, e cart.Event) (ankka.ConsumerEffect, error) {
		switch e.(type) {
		case cart.ItemAdded:
			return ankka.ProduceAll(), nil
		case cart.ItemRemoved:
			return ankka.Produce(Fanned{N: 0}), nil
		case cart.CheckedOut:
			return ankka.ProduceAll(
				ankka.Message{Payload: Fanned{N: 1}},
				ankka.Message{Payload: Fanned{N: 2}, Key: "second:" + subject(ctx)},
				ankka.Message{Payload: Fanned{N: 3}, Metadata: ankka.Metadata{"x-n": "3"}},
			), nil
		}
		return ankka.ConsumerIgnore(), nil
	},
	ankka.ProducesWith("conformance-fanout", fannedCodec),
)
```

Delivery is at least once. A consumer that produces needs `ANKKA_KAFKA_BOOTSTRAP_SERVERS` on the
sidecar. `ProduceAll` publishes its messages in order, and the change is handled when the broker has
accepted all of them; none is handled at once. A returned error has the change delivered again. See
[Consumers](../build/consumers.md) and [Broker topics](../build/topics.md).

## Graph consumer

A consumer that publishes its source as graph deltas, registered as a consumer, that can publish nothing
but deltas.

| Part | API |
|---|---|
| Constructor | `ankka.GraphConsumer[Msg](id, source, topic, func(ctx, Msg) (GraphEffect, error), parts…)` |
| Elements | `ankka.Node(id, ankka.Labels(…), ankka.WithProperties(ankka.Properties{…}), ankka.AtVersion(v))`, `ankka.Edge(id, type, from, to, opts…)`, `ankka.NodeTombstone(id, opts…)`, `ankka.EdgeTombstone(id, type, from, to, opts…)` |
| Effects | `ankka.Publish(elements…)`, `ankka.GraphDone()`, `ankka.GraphIgnore()` |
| A refusal | `*ankka.GraphError`, whose `Why` names the rule broken |
| Reading | `ankka.ReadDelta(value, key)`, `ankka.NodeKey(id)`, `ankka.EdgeKey(id)`, `ankka.GraphDeltaSchema` |

A property value is a string, a bool, an integer, a float or a non-empty slice of one of those; a float
with a whole value counts as an integer, as the sink reads it.

```go
func cartNode(id string, checkedOut bool) ankka.Element {
	return ankka.Node("cart:"+id, ankka.Labels("Cart"), ankka.WithProperties(ankka.Properties{"cartId": id, "checkedOut": checkedOut}))
}

// CartGraph is the cart graph every reference publishes, where the conformance suite reads it.
var CartGraph = ankka.GraphConsumer[cart.Event]("cart-graph", ankka.Changes(cart.Entity), "conformance-graph",
	func(ctx context.Context, e cart.Event) (ankka.GraphEffect, error) {
		id := subject(ctx)
		switch e.(type) {
		case cart.ItemAdded, cart.ItemRemoved:
			return ankka.Publish(cartNode(id, false)), nil
		case cart.CheckedOut:
			return ankka.Publish(
				cartNode(id, true),
				ankka.Node("checkout:"+id, ankka.Labels("Checkout"), ankka.WithProperties(ankka.Properties{"cartId": id})),
				ankka.Edge("checked-out:"+id, "CHECKED_OUT", "cart:"+id, "checkout:"+id),
			), nil
		}
		return ankka.GraphIgnore(), nil
	},
	ankka.WhenDeleted(func(ctx context.Context) (ankka.GraphEffect, error) {
		return ankka.Publish(ankka.NodeTombstone("cart:" + subject(ctx))), nil
	}),
)
```

See [Publish a graph](../build/graph.md).

## Workflow

| Part | API |
|---|---|
| Constructor | `ankka.Workflow[S](id, empty func(string) S, parts…)` |
| Handlers | `ankka.WorkflowCommand(wire, func(ctx, S, I) (WorkflowEffect[S, R], error))`, `ankka.Query`, `ankka.Step(name, func(ctx, S, I) (StepEffect[S], error))` |
| Command effects | `ankka.WorkflowUpdate[S, R](s)` or `ankka.WorkflowKeep[S, R]()`, then `.ThenTransitionTo(step, input)` and `.ThenReply`, `.ThenReplyValue`, `.ThenReplyState`, `.ThenNoReply` |
| Step effects | `ankka.StepUpdate(s)` or `ankka.StepKeep[S]()`, then `.ThenTransitionTo(step, input)`, `.ThenPause(ankka.PauseOptions{After, OnTimeout})`, `.ThenEnd()`, `.ThenFail(code, message)`; `ankka.TransitionTo`, `ankka.Pause`, `ankka.End`, `ankka.FailWorkflow` alone |
| Settings | `ankka.WorkflowTimeout(d)`, `ankka.StepTimeout(d)`, `ankka.StepRecovery(ankka.Recovery{MaxRetries, FailoverTo})`, and `ankka.ForStep(name, …)` for one step |

```go
// Checkout is a checkout's state, in the Scala reference's shape: the id, the mode it was started
// in and where it has got to.
type Checkout struct {
	ID     string `json:"id"`
	Mode   string `json:"mode"`
	Status string `json:"status"`
}

var errDeclined = errors.New("payment declined")

func step(s Checkout) ankka.StepBuilder[Checkout] { return ankka.StepUpdate(s) }

// CheckoutFlow is a checkout as a durable multi-step process: reserve, then charge, or compensate.
// mode is ok, fail (the charge is declined and compensated), pause (a pause before charging),
// abort (declined; the compensation records it and fails the workflow), drop (declined; the
// compensation fails the workflow and records nothing) or doomed (declined, and the compensation
// fails too, so the workflow fails).
var CheckoutFlow = ankka.Workflow[Checkout]("checkout", func(id string) Checkout {
	return Checkout{ID: id, Status: "new"}
},
	ankka.WorkflowCommand("start", func(_ context.Context, s Checkout, mode string) (ankka.WorkflowEffect[Checkout, string], error) {
		if s.Status != "new" {
			return ankka.WorkflowEffect[Checkout, string]{}, ankka.Refuse(ankka.Conflict, "already started")
		}
		s.Status, s.Mode = "reserving", mode
		return ankka.WorkflowUpdate[Checkout, string](s).ThenTransitionTo("reserve", nil).ThenReplyValue("started"), nil
	}),
	ankka.Query("status", func(_ context.Context, s Checkout, _ ankka.Unit) (ankka.ReadOnlyEffect[string], error) {
		return ankka.Reply(s.Status), nil
	}),
	ankka.Step("reserve", func(ctx context.Context, s Checkout, _ ankka.Unit) (ankka.StepEffect[Checkout], error) {
		// A client call from a step: what the cart holds.
		if _, err := ankka.Invoke[int](ctx, cart.ComponentID, s.ID, "total-quantity", ankka.Unit{}); err != nil {
			return ankka.StepEffect[Checkout]{}, err
		}
		next := "charge"
		if s.Mode == "pause" {
			next = "wait"
		}
		s.Status = "reserved"
		return step(s).ThenTransitionTo(next, nil), nil
	}),
	ankka.Step("wait", func(_ context.Context, s Checkout, _ ankka.Unit) (ankka.StepEffect[Checkout], error) {
		s.Status = "waiting"
		return step(s).ThenPause(ankka.PauseOptions{After: 1500 * time.Millisecond, OnTimeout: "charge"}), nil
	}),
	ankka.Step("charge", func(_ context.Context, s Checkout, _ ankka.Unit) (ankka.StepEffect[Checkout], error) {
		if slices.Contains([]string{"fail", "abort", "drop", "doomed"}, s.Mode) {
			return ankka.StepEffect[Checkout]{}, errDeclined
		}
		s.Status = "charged"
		return step(s).ThenEnd(), nil
	}),
	ankka.Step("compensate", func(_ context.Context, s Checkout, _ ankka.Unit) (ankka.StepEffect[Checkout], error) {
		switch s.Mode {
		case "abort":
			s.Status = "aborted"
			return step(s).ThenFail(ankka.WorkflowFailed, "payment declined"), nil
		case "drop":
			return ankka.FailWorkflow[Checkout](ankka.WorkflowFailed, "payment declined"), nil
		case "doomed":
			return ankka.StepEffect[Checkout]{}, errors.New("compensation failed too")
		}
		s.Status = "compensated"
		return step(s).ThenEnd(), nil
	}),
	ankka.StateCodec(ankka.CodecFor[Checkout](ankka.Manifest("checkout"))),
	ankka.StepTimeout(10*time.Second),
	ankka.ForStep("charge", ankka.StepRecovery(ankka.Recovery{MaxRetries: 1, FailoverTo: "compensate"})),
)
```

A step runs on its own goroutine while the workflow answers commands from the state before the step;
a step that returns an error is retried as its recovery says, then failed over, and a step that returns
`ThenFail` ends the workflow. A transition names a step by its wire name. See [Workflows](../build/workflows.md).

## Timed action and timers

| Part | API |
|---|---|
| Constructor | `ankka.TimedAction(id, ankka.Action(wire, func(ctx, I) (ActionEffect, error))…)` |
| Effects | `ankka.ActionDone()`, `ankka.ActionFail(code, message)` |
| In a handler | `ankka.TimerFrom(ctx)`: `Name`, `Due`, `Attempts` |
| Scheduling | `ankka.Schedule(ctx, timerID, delay, ankka.TimerTarget{ComponentID, Handler}, input)`, `ankka.ScheduleRecurring(ctx, timerID, delay, period, target, input)`, `ankka.Cancel(ctx, timerID)` |

```go
// Reminder records on the conformance entity it is given the id of.
var Reminder = ankka.TimedAction("reminder",
	ankka.Action("remind", func(ctx context.Context, id string) (ankka.ActionEffect, error) {
		if _, err := ankka.Invoke[string](ctx, "conformance", id, "record", "reminded"); err != nil {
			return ankka.ActionEffect{}, err
		}
		return ankka.ActionDone(), nil
	}),
	// Records the due time it was run for, as the runtime told it.
	ankka.Action("tick", func(ctx context.Context, id string) (ankka.ActionEffect, error) {
		due := "undefined"
		if t, ok := ankka.TimerFrom(ctx); ok && !t.Due.IsZero() {
			due = fmt.Sprint(t.Due.UnixMilli())
		}
		if _, err := ankka.Invoke[string](ctx, "conformance", id, "record", "due:"+due); err != nil {
			return ankka.ActionEffect{}, err
		}
		return ankka.ActionDone(), nil
	}),
)
```

```go
// A recurring timer: due at once, then every second.
ankka.Post("/recur/{id}", func(ctx context.Context, req *ankka.Request, _ ankka.Unit) (ankka.Done, error) {
	id := req.Param("id")
	return ankka.Done{}, ankka.ScheduleRecurring(ctx, "recur-"+id, 0, time.Second, tickTarget, id)
}),
// The same timer set again, with a delay a replacement would be first due after.
ankka.Post("/recur/{id}/again", func(ctx context.Context, req *ankka.Request, _ ankka.Unit) (ankka.Done, error) {
	id := req.Param("id")
	return ankka.Done{}, ankka.ScheduleRecurring(ctx, "recur-"+id, 60*time.Second, time.Second, tickTarget, id)
}),
ankka.Post("/recur/{id}/cancel", func(ctx context.Context, req *ankka.Request, _ ankka.Unit) (ankka.Done, error) {
	return ankka.Done{}, ankka.Cancel(ctx, "recur-"+req.Param("id"))
}),
```

Scheduling twice under one id replaces the earlier timer, except that a recurring timer set again for
the same handler with the same period keeps its next due time. A period of zero is refused before
anything is sent. See [Timers](../build/timers.md).

## Agent

| Part | API |
|---|---|
| Constructor | `ankka.Agent(id, parts…)` |
| Handlers | `ankka.Ask(wire, func(ctx, I) (AgentEffect, error))`, `ankka.AskStream(wire, …)` for a streamed reply |
| Tools and guardrails | `ankka.Tools(ankka.Tool(name, description, func(ctx, I) (R, error)), ankka.ApprovedTool(name, description, within, run))`; `ankka.Guardrails(ankka.Guardrail(name, func(ctx, stage, text) (string, error)), ankka.ResultGuardrail(name, func(ctx, tool, text) (string, error)))` |
| MCP servers | `ankka.McpServers(ankka.McpServer(name, ankka.McpURL(u) \| ankka.McpService(project, service, path), ankka.McpHeader(name, variable), ankka.McpApproval(within)))` |
| Options | `ankka.Role(text)`, `ankka.MaxToolCallSteps(n)` (100 by default) |
| Effects | `ankka.SystemMessage(t)`, `ankka.UserMessage(t)`, `ankka.AgentModel(name)`, then `.WithContext(t)`, `.WithModel(name)`, `.Memory(bool)`, `.Tools(names…)`, `.Guardrails(names…)`, `.ThenReply()`, `.ThenReplyJSON(hint)`; `ankka.AgentError(code, message)` |
| Calling | `ankka.Invoke[R](ctx, agentID, sessionID, wire, input)`, which returns an `*ankka.ApprovalAwaitedError` carrying the requests when the turn waits; `ankka.DecideApproval[R](ctx, agentID, sessionID, wire, approvalID, ankka.Decision{Approved, By, Note})`; `ankka.InvokeStream` |

A tool's input struct is also the JSON Schema the model sees; a tool with no description is refused. A
guardrail answers `""` to pass or a reason to block. A handler returns a plan; the sidecar runs the model
loop, calls tools back in the process, connects to the MCP servers and enforces approval, so the process
never runs a server's tool nor a tool that awaits a decision. `ApprovedTool`'s `within` of zero sets no
time limit.

```go
// LookupArguments are the lookup tool's.
type LookupArguments struct {
	ID string `json:"id"`
}

// lookup records under the id and answers how many things are recorded there.
func lookup(mark, label string) func(context.Context, LookupArguments) (string, error) {
	return func(ctx context.Context, in LookupArguments) (string, error) {
		if in.ID == "" {
			return "", errors.New("an id is needed")
		}
		if _, err := ankka.Invoke[string](ctx, "conformance", in.ID, "record", mark); err != nil {
			return "", err
		}
		n, err := ankka.Invoke[int](ctx, "conformance", in.ID, "count", ankka.Unit{})
		if err != nil {
			return "", err
		}
		return fmt.Sprintf("%s for %s is %d", label, in.ID, n), nil
	}
}

var noSecrets = ankka.Guardrail("no-secrets", func(_ context.Context, stage ankka.GuardrailStage, text string) (string, error) {
	if strings.Contains(text, "sk-") {
		return string(stage) + " rejected by no-secrets", nil
	}
	return "", nil
})

func describe(_ context.Context, question string) (ankka.AgentEffect, error) {
	return ankka.SystemMessage("You are helpful.").UserMessage(question).Tools("lookup").Guardrails("no-secrets").ThenReply(), nil
}

// Assistant is an agent whose tool acts through the client.
var Assistant = ankka.Agent("assistant",
	ankka.Tools(ankka.Tool("lookup", "Looks up how many things were recorded under an id.", lookup("looked-up", "count"))),
	ankka.Guardrails(noSecrets),
	ankka.Ask("ask", describe),
	ankka.AskStream("stream", describe),
	// Answered by the model the sidecar has under the name second, not its default.
	ankka.Ask("ask-second", func(_ context.Context, question string) (ankka.AgentEffect, error) {
		return ankka.AgentModel("second").SystemMessage("You are helpful.").UserMessage(question).ThenReply(), nil
	}),
)
```

See [Agents](../build/agents.md) and [MCP servers](../build/mcp-servers.md).

## Autonomous agent

| Part | API |
|---|---|
| Task type | `ankka.NewTaskType[R](name, description, ankka.Rule(name, func(ctx, R) (Verdict, error))…)`; a rule answers `ankka.Accepted()` or `ankka.Rejected(reason)` |
| Constructor | `ankka.Autonomous(id, description, parts…)`: `ankka.Accepts(taskType, maxIterations)`, `ankka.Tools`, `ankka.Guardrails`, `ankka.Instructions(text)`, `ankka.AutonomousModel(name)`, `ankka.WithAutonomousSettings(s)` |
| Calling | `ankka.CreateTask(ctx, type, instructions, ankka.TaskOptions{…})`, `ankka.GetTask`, `ankka.WaitForTask`, `ankka.CancelTask`; `ankka.RunSingleTask(ctx, agentID, type, instructions, opts)`; `ankka.Instance(agentID, instanceID)` with `Assign`, `Suspend`, `Resume`, `Terminate`, `State`, `Notifications`, `Decide` |

```go
// Answer is a task's result.
type Answer struct {
	Answer  string   `json:"answer"`
	Sources []string `json:"sources"`
}

var flakySeen sync.Map

// AnswerTask is the one task type the answerer accepts.
var AnswerTask = ankka.NewTaskType[Answer]("answer", "Answer a question, citing what you looked up",
	ankka.Rule("cites-sources", func(_ context.Context, a Answer) (ankka.Verdict, error) {
		if len(a.Sources) > 0 {
			return ankka.Accepted(), nil
		}
		return ankka.Rejected("sources must not be empty"), nil
	}),
	// Fails the first time it sees "flaky-once": a rule that fails once, then decides.
	ankka.Rule("steady", func(_ context.Context, a Answer) (ankka.Verdict, error) {
		if a.Answer == "flaky-once" {
			if _, seen := flakySeen.LoadOrStore(a.Answer, true); !seen {
				return ankka.Verdict{}, errors.New("the rule threw")
			}
		}
		return ankka.Accepted(), nil
	}),
)

// Answerer is the autonomous agent.
var Answerer = ankka.Autonomous("answerer", "Answers questions",
	ankka.Tools(
		ankka.Tool("lookup", "Looks up how many things were recorded under an id.", lookup("looked-up", "count")),
		// Waits for a person before it runs; what it records is counted as a run.
		ankka.ApprovedTool("sensitive_lookup", "Looks up what was recorded under an id. A person approves every one.", 0,
			lookup("sensitive", "sensitive count")),
	),
	ankka.Guardrails(noSecrets),
	ankka.Accepts(AnswerTask, 4),
)
```

The Go test kits do not script an autonomous agent: test one end to end, through a sidecar with
`ANKKA_MODEL_SCRIPT`. See [Autonomous agents](../build/autonomous-agents.md).

## HTTP endpoint

| Part | API |
|---|---|
| Constructor | `ankka.NewEndpoint(prefix, acl, routes…)`; the ACL is a required argument, and a nil one is refused |
| ACLs | `ankka.AllowAll()`, `ankka.DenyAll()`, `ankka.Authenticate(ankka.Issuer(name)…)`, `ankka.AllowIf(ankka.Internet(), ankka.ServiceNamed(name), ankka.ServiceIn(project, name), ankka.AnyInProject(), ankka.Self(), ankka.Granted())` |
| Routes | `ankka.Get[R]` and `ankka.Delete[R](template, func(ctx, *Request) (R, error))`; `ankka.Post[B, R]`, `ankka.Put`, `ankka.Patch(template, func(ctx, *Request, B) (R, error))`; `ankka.SSE(template, func(ctx, *Request) iter.Seq2[string, error])`; `ankka.SSEEvents` for named events beside text; `ankka.SocketRoute(template, func(ctx, *Socket) error)`; each with `.ACL(acl)` for that route alone |
| The request | `req.Param(name)`, `req.Query` (`url.Values`), `req.Header(name)`, `req.Body`, `req.ContentType`, `req.Principal` (`Subject`, `Name`, `Email`, `Roles`, `Claims`, `Issuer`), `req.Caller` (`Kind`, `Project`, `Name`) |
| Replies | the reply is encoded by its type's codec; `ankka.Done` answers 204; `ankka.RawResponse` sets status, content type, headers and body |
| Errors | `&ankka.HttpProblem{Status, Message}`; a refusal from a call answers its code's status |
| A socket | `s.Receive()` (`io.EOF` once closed), `s.Send(text)`, `s.Request()`; returning nil closes it finished, an error closes it failed |

```go
// API is the Scala sample's routes, exactly: /carts/{cartId}, /total, /items, /items/{productId},
// /checkout, DELETE /carts/{cartId}.
var API = ankka.NewEndpoint("/carts", ankka.AllowAll(),
	// A literal beside a parameter: /carts/awkward is this route, never the cart "awkward".
	ankka.Get("/awkward", func(context.Context, *ankka.Request) (string, error) { return "literal", nil }),
	ankka.Get("/rows", func(ctx context.Context, _ *ankka.Request) ([]Row, error) {
		return ankka.QueryView[Row](ctx, RowsID, ankka.All())
	}),
	ankka.Get("/{cartId}/rows", func(ctx context.Context, req *ankka.Request) (Row, error) {
		row, found, err := ankka.ViewRow[Row](ctx, RowsID, req.Param("cartId"))
		if err == nil && !found {
			err = &ankka.HttpProblem{Status: 404, Message: "no row for '" + req.Param("cartId") + "'"}
		}
		return row, err
	}),
	ankka.Get("/{cartId}", func(ctx context.Context, req *ankka.Request) (Cart, error) {
		return ankka.Invoke[Cart](ctx, ComponentID, req.Param("cartId"), "get-cart", ankka.Unit{})
	}),
	ankka.Get("/{cartId}/total", func(ctx context.Context, req *ankka.Request) (int, error) {
		return ankka.Invoke[int](ctx, ComponentID, req.Param("cartId"), "total-quantity", ankka.Unit{})
	}),
	ankka.Post("/{cartId}/items", func(ctx context.Context, req *ankka.Request, item LineItem) (ankka.Done, error) {
		return ankka.Invoke[ankka.Done](ctx, ComponentID, req.Param("cartId"), "add-item", item)
	}),
	ankka.Delete("/{cartId}/items/{productId}", func(ctx context.Context, req *ankka.Request) (ankka.Done, error) {
		return ankka.Invoke[ankka.Done](ctx, ComponentID, req.Param("cartId"), "remove-item", req.Param("productId"))
	}),
	ankka.Post("/{cartId}/checkout", func(ctx context.Context, req *ankka.Request, _ ankka.Unit) (Cart, error) {
		return ankka.Invoke[Cart](ctx, ComponentID, req.Param("cartId"), "checkout", ankka.Unit{})
	}),
	ankka.Delete("/{cartId}", func(ctx context.Context, req *ankka.Request) (ankka.Done, error) {
		return ankka.Invoke[ankka.Done](ctx, ComponentID, req.Param("cartId"), "discard", ankka.Unit{})
	}),
)
```

The process never binds an HTTP port: the sidecar serves the routes and forwards each request. See
[HTTP endpoints](../build/http-endpoints.md).

## MCP endpoint

| Part | API |
|---|---|
| Constructor | `ankka.NewMcpEndpoint(path, acl, parts…)`; `ankka.AllowClientKeys()` and `ankka.AnyOf(…)` are its ACLs beside the others |
| Tools | `ankka.McpTool(name, description, func(ctx, I) (R, error), opts…)`; `ankka.McpCommandRef`, `ankka.McpQueryRef(name, description, componentID, handler, idArgument)`; `ankka.McpRouteRef(name, description, method, path)`; `ankka.ToolACL(acl)` |
| Options | `ankka.Brokered()`, `ankka.ServerInfo(name, version)` |

```go
// CartMcp is the cart's MCP endpoint at /mcp. get_cart answers the principal beside the cart, so a
// case can see who a client key or a token made the caller, and that nothing in it reads as the key.
var CartMcp = ankka.NewMcpEndpoint("/mcp", ankka.AnyOf(ankka.AllowClientKeys(), ankka.Authenticate()),
	ankka.McpTool("get_cart", "Reads one cart", func(ctx context.Context, in CartArguments) (ToolCart, error) {
		c, err := ankka.Invoke[cart.Cart](ctx, cart.ComponentID, in.CartID, "get-cart", ankka.Unit{})
		out := ToolCart{Cart: c, Claims: map[string]string{}}
		if p := ankka.RequestFrom(ctx).Principal; p != nil {
			out.Subject = &p.Subject
			for k, v := range p.Claims {
				out.Claims[k] = v
			}
		}
		return out, err
	}),
	ankka.McpTool("add_item", "Adds an item to a cart", func(ctx context.Context, in AddItemArguments) (ankka.Done, error) {
		return ankka.Invoke[ankka.Done](ctx, cart.ComponentID, in.CartID, "add-item", cart.LineItem{ProductID: in.ProductID, Name: in.ProductID, Quantity: in.Quantity})
	}),
	ankka.McpCommandRef("checkout", "Checks a cart out", cart.ComponentID, "checkout", "cartId"),
)
```

See [MCP endpoints](../build/mcp-endpoints.md).

## The request context and goroutines

Everything a handler can see about the request it runs for travels on its `context.Context`, read by
accessors: `ankka.RequestFrom(ctx)`, `ankka.CallerFrom(ctx)`, `ankka.CommandFrom(ctx)`,
`ankka.ChangeFrom(ctx)`, `ankka.TimerFrom(ctx)`, `ankka.MetadataFrom(ctx)` and `ankka.ConfigFrom(ctx)`,
the service's variables as the platform hands them. The same context carries the trace, so a call
made with it is a child span of the handler's, and it is cancelled when the request or the conversation
ends, so a wait inside a handler stops when its request does.

Go has no thread-local: **work handed to another goroutine sees the request only when it is given the
context.** A goroutine that outlives its handler takes `context.WithoutCancel(ctx)`, which keeps the
request's values and drops its cancellation:

```go
// A handler that answers nothing: the call is not awaited, and the route says so with 204.
ankka.Post("/{id}/no-reply", func(ctx context.Context, req *ankka.Request, _ ankka.Unit) (ankka.Done, error) {
	go func(ctx context.Context, id string) {
		_, _ = ankka.Invoke[ankka.Done](ctx, "conformance", id, "no-reply", "")
	}(context.WithoutCancel(ctx), req.Param("id"))
	return ankka.Done{}, nil
}),
```

The SDK recovers a panic in a handler into that request's failure, and cannot recover one in a goroutine
it did not start: a goroutine's panic stops the process. An entity's state is released when its
conversation ends, so anything a handler still does after that cannot reach it.

## Calling components

| Call | Does |
|---|---|
| `ankka.Invoke[R](ctx, componentID, id, wire, input)` | an entity's, a workflow's or an agent's handler, by wire name |
| `ankka.InvokeStream(ctx, componentID, id, wire, input)` | a streaming agent handler, as `iter.Seq2[string, error]` |
| `ankka.SendAndAwait[R](ctx, workflowID, id, wire, input, within)` | starts a workflow and answers its end state as one call |
| `ankka.AwaitEnd[R](ctx, workflowID, id, within)` | waits for a workflow already started |
| `ankka.ViewRow`, `ankka.QueryView`, `ankka.QueryStream`, `ankka.Watch` | a view's rows |

```go
ankka.Get("/{cartId}", func(ctx context.Context, req *ankka.Request) (Cart, error) {
	return ankka.Invoke[Cart](ctx, ComponentID, req.Param("cartId"), "get-cart", ankka.Unit{})
}),
ankka.Get("/{cartId}/total", func(ctx context.Context, req *ankka.Request) (int, error) {
	return ankka.Invoke[int](ctx, ComponentID, req.Param("cartId"), "total-quantity", ankka.Unit{})
}),
ankka.Post("/{cartId}/items", func(ctx context.Context, req *ankka.Request, item LineItem) (ankka.Done, error) {
	return ankka.Invoke[ankka.Done](ctx, ComponentID, req.Param("cartId"), "add-item", item)
}),
```

A refusal is an `*ankka.RefusalError` with the refusal's `Code`, `Message` and `Details`, which
`ankka.AsRefusal(err)` finds; a failed workflow is the code `ankka.WorkflowFailed` with its step and
reason in `Details`. A sidecar too old for a call is an `*ankka.ProtocolTooOldError` naming the version it
needs. See [Calling components](../build/component-client.md).

## Secret store

`ankka.Secrets(ctx)` is the service's secret store ([Secrets a service keeps](../build/secrets.md)):
`Put(name, value)`, `Get(name)` (the value and whether there is one) and `Delete(name)`. It is offered to
endpoints, workflow steps, consumers, timed actions and agents; from an entity's, a view's or a workflow
command's context every call is refused, naming which. The runtime beside the process holds the store and
its key; the process never sees the key.

```go
// The secret store. The name is a query parameter because it may hold a slash.
ankka.Post("/secrets", func(ctx context.Context, req *ankka.Request, value string) (ankka.Done, error) {
	return ankka.Done{}, ankka.Secrets(ctx).Put(req.Query.Get("name"), value)
}),
ankka.Get("/secrets", func(ctx context.Context, req *ankka.Request) (string, error) {
	name := req.Query.Get("name")
	value, found, err := ankka.Secrets(ctx).Get(name)
	if err == nil && !found {
		err = notFound("no secret '%s'", name)
	}
	return value, err
}),
ankka.Delete("/secrets", func(ctx context.Context, req *ankka.Request) (ankka.Done, error) {
	return ankka.Done{}, ankka.Secrets(ctx).Delete(req.Query.Get("name"))
}),
```

The integration test kit puts a generated `ANKKA_SECRET_KEY` on the sidecar it starts; the component test
kits have no secret store.

## Client keys

`ankka.ClientKeys(ctx)` issues, lists and revokes the client keys an MCP endpoint that admits
`ankka.AllowClientKeys()` accepts: `Issue(ankka.ClientKeyRequest{Subject, Scope, Attributes, …})` answers
the key once, `List()` answers each key's id, subject, scope and attributes but never the key, and
`Revoke(id)` withdraws one.

```go
// KeyRequest is what POST /mcp-keys is asked to issue: a key that never expires, for the cases.
type KeyRequest struct {
	Subject    string            `json:"subject"`
	Scope      []string          `json:"scope"`
	Attributes map[string]string `json:"attributes"`
}

// ListedKey is a key as the listing answers it: never the key.
type ListedKey struct {
	ID         string            `json:"id"`
	Subject    string            `json:"subject"`
	Attributes map[string]string `json:"attributes"`
	Label      *string           `json:"label"`
	Endpoint   string            `json:"endpoint"`
	Scope      []string          `json:"scope"`
}

// McpKeys is the reference's own routes over its client keys, as a product would write them.
var McpKeys = ankka.NewEndpoint("/mcp-keys", ankka.AllowAll(),
	ankka.Post("/", func(ctx context.Context, _ *ankka.Request, asked KeyRequest) (map[string]string, error) {
		issued, err := ankka.ClientKeys(ctx).Issue(ankka.ClientKeyRequest{
			Subject: asked.Subject, Never: true, Scope: asked.Scope, Attributes: asked.Attributes,
		})
		return map[string]string{"id": issued.ID, "clientKey": issued.ClientKey}, err
	}),
	ankka.Get("/", func(ctx context.Context, _ *ankka.Request) ([]ListedKey, error) {
		keys, err := ankka.ClientKeys(ctx).List()
		out := make([]ListedKey, 0, len(keys))
		for _, k := range keys {
			attrs := k.Attributes
			if attrs == nil {
				attrs = map[string]string{}
			}
			out = append(out, ListedKey{ID: k.ID, Subject: k.Subject, Attributes: attrs, Label: orNull(k.Label), Endpoint: k.Endpoint, Scope: append([]string{}, k.Scope...)})
		}
		return out, err
	}),
	ankka.Delete("/{id}", func(ctx context.Context, req *ankka.Request) (string, error) {
		return "revoked", ankka.ClientKeys(ctx).Revoke(req.Param("id"))
	}),
)
```

See [MCP endpoints](../build/mcp-endpoints.md#client-keys).

## Timers

A timer is set, replaced and cancelled from any handler that may wait: `ankka.Schedule`,
`ankka.ScheduleRecurring` and `ankka.Cancel`, naming the handler that will run in an `ankka.TimerTarget`.
A timer is a database row: it survives a restart and fires at least once.

```go
ankka.Post("/remind/{id}", func(ctx context.Context, req *ankka.Request, _ ankka.Unit) (ankka.Done, error) {
	id := req.Param("id")
	return ankka.Done{}, ankka.Schedule(ctx, "remind-"+id, time.Second, ankka.TimerTarget{ComponentID: "reminder", Handler: "remind"}, id)
}),
```

## Calling other services

`ankka.CallService(ctx, service, ankka.ServiceRequest{Method, Path, Headers, Body, ContentType, Project})`
calls another service's route as this service: the sidecar resolves the name, presents this service's
certificate and checks the other's. Any status is an answer, an `*ankka.ServiceResponse` with `Status`,
`ContentType`, `Body` and `Headers`. No answer is an `*ankka.ServiceError` whose `Reason` is
`ankka.ServiceUnresolvable`, `ankka.ServiceIdentityMismatch` or `ankka.ServiceUnanswered`, and a call the
runtime would not make, such as one from an entity, is an `*ankka.RefusalError`. A body is at most
4,000,000 bytes either way.

```go
// A tool calls another service as this service: the called service's ACL can admit it by name.
ankka.Tool("ask_scripted", "Asks the scripted service for what is at a path.", func(ctx context.Context, in PathArguments) (string, error) {
	res, err := ankka.CallService(ctx, "scripted", ankka.ServiceRequest{Method: "GET", Path: in.Path})
	if err != nil {
		return "", err
	}
	if res.Status < 200 || res.Status > 299 {
		return "", fmt.Errorf("the scripted service answered %d: %s", res.Status, res.Body)
	}
	return string(res.Body), nil
}),
```

See [Calling other services](../build/calling-services.md).

## The storage client

`ankka.StorageFrom(ctx)` is the service's buckets, or nil for an entity's, a view's or a workflow
command's context: `Default()` for the unnamed bucket, `Bucket(name)` for one the descriptor names. A
bucket has `Put(ctx, name, body io.Reader, length, contentType)`, `Get`, `Stat`, `List(ctx, prefix)` (an
`iter.Seq2`), `Delete`, `SignedURL(name, access, span)` and `PublicURL(name)`. The process speaks to its
store itself, from the `ANKKA_S3_*` variables; on a developer's machine it keeps objects in a directory
and serves its own signed URLs through the sidecar. `ankka.NewMemoryStorage()` is a store in memory, and
`ankka.WithStorage(ctx, store)` hands it to a handler under test.

```go
// storageCall runs f on the bucket the request names (absent: the unnamed bucket), and answers
// every refusal as a record rather than a status.
func storageCall(ctx context.Context, req *ankka.Request, f func(ankka.Bucket) (StorageRecord, error)) (StorageRecord, error) {
	refused := func(err error) (StorageRecord, error) {
		if r, ok := ankka.AsRefusal(err); ok {
			out := storageRecord("refused")
			out.Code, out.Message = r.Code.String(), r.Message
			return out, nil
		}
		return StorageRecord{}, err
	}
	store := ankka.StorageFrom(ctx)
	if store == nil {
		return refused(ankka.Refuse(ankka.Unavailable, "this service has no object storage"))
	}
	var bucket ankka.Bucket
	var err error
	if name := req.Query.Get("bucket"); req.Query.Has("bucket") {
		bucket, err = store.Bucket(name)
	} else {
		bucket, err = store.Default()
	}
	if err != nil {
		return refused(err)
	}
	out, err := f(bucket)
	if err != nil {
		return refused(err)
	}
	return out, nil
}

func storagePut(ctx context.Context, req *ankka.Request, body string) (StorageRecord, error) {
	return storageCall(ctx, req, func(b ankka.Bucket) (StorageRecord, error) {
		switch req.Query.Get("rules") {
		case "":
			contentType := req.ContentType
			if contentType == "" {
				contentType = "application/octet-stream"
			}
			if err := b.Put(ctx, req.Query.Get("name"), strings.NewReader(body), int64(len(body)), contentType); err != nil {
				return StorageRecord{}, err
			}
		case "prefix":
			for _, err := range b.List(ctx, body) {
				if err != nil {
					return StorageRecord{}, err
				}
				break
			}
		default:
			if _, err := b.Stat(ctx, body); err != nil {
				return StorageRecord{}, err
			}
		}
		return storageRecord("ok"), nil
	})
}

func storageGet(ctx context.Context, req *ankka.Request) (StorageRecord, error) {
	return storageCall(ctx, req, func(b ankka.Bucket) (StorageRecord, error) {
		name, out := req.Query.Get("name"), storageRecord("ok")
		switch op := req.Query.Get("op"); op {
		case "get":
			obj, err := b.Get(ctx, name)
			if err != nil || obj == nil {
				return storageRecord("absent"), err
			}
			data, err := io.ReadAll(obj.Body)
			if err != nil {
				return StorageRecord{}, err
			}
			out.ContentType, out.Text, out.Length = obj.ContentType, string(data), int64(len(data))
		case "stat":
			info, err := b.Stat(ctx, name)
			if err != nil || info == nil {
				return storageRecord("absent"), err
			}
			out.ContentType, out.Length = info.ContentType, info.Length
		case "list":
			for e, err := range b.List(ctx, req.Query.Get("prefix")) {
				if err != nil {
					return StorageRecord{}, err
				}
				out.Names = append(out.Names, e.Name)
			}
		case "url":
			url, err := b.PublicURL(name)
			if err != nil {
				return StorageRecord{}, err
			}
			out.URL = url
		case "signed-url":
			access := ankka.AccessRead
			if req.Query.Get("access") == "keep" {
				access = ankka.AccessKeep
			}
			url, err := b.SignedURL(name, access, 0)
			if err != nil {
				return StorageRecord{}, err
			}
			out.URL = url
		default:
			out = storageRecord("refused")
			out.Code, out.Message = ankka.BadRequest.String(), fmt.Sprintf("no storage op '%s'", op)
		}
		return out, nil
	})
}
```

Every call refuses with an `*ankka.RefusalError`: `BadRequest` for a name, prefix, length or span that
breaks a rule, `Unavailable` when the store cannot be reached or the bucket does not exist. See
[Object storage](../build/object-storage.md).

## Running a service

```go
func main() {
	if err := cart.Service().Start(context.Background()); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}
```

`Start` serves the protocol on loopback at `ANKKA_PROCESS_PORT` (9010) and dials the sidecar at
`ANKKA_SIDECAR_ADDRESS` (`127.0.0.1:9011`). On `SIGTERM` or `SIGINT`, or when its context ends, it stops
accepting, lets handlers in flight finish within the grace period (`ankka.GracePeriod(d)`, 25 seconds by
default), and returns. `ankka.Host("0.0.0.0")` is accepted for a sidecar in a container and every other
host is refused; `ankka.Port`, `ankka.SidecarAddress`, `ankka.Logger` and `ankka.StorageOf` are its other
options. Locally, run the sidecar with `docker compose --profile polyglot up -d` from the ankka repository.
A sidecar older than something the service declares is refused at discovery, naming the version it needs.

## Testing

| Kit | Drives |
|---|---|
| `testkit.EventSourced(entity, testkit.EntityID(id))` | One entity: `Command(ctx, wire, input)` and `Query` return the events, state, reply, refusal and retention |
| `testkit.KeyValue(entity)` | One key value entity |
| `testkit.Workflow(workflow)` | One workflow: `Command`, `RunStep`, `RunUntilPause`, `Resume`, `Progress` |
| `testkit.View(view)`, `testkit.KeyedView(view)` | A view's changes and its rows |
| `testkit.Consumer(consumer)`, `testkit.GraphConsumer(consumer)` | A consumer's `OnMessage` and `OnDelete`, and what it published with its keys, or the deltas |
| `testkit.TimedAction(action)` | A timed action's handlers |
| `testkit.Agent(agent, session, testkit.NewScriptedModel())` | An agent's plan, tools and guardrails, against a scripted model that fails when the script runs out |
| `testkit.Endpoint(endpoint, components…)`, `testkit.Mcp(endpoint, components…)` | An endpoint's routes, or an MCP endpoint's tools, with no sidecar |
| `testkit.Calling(components…)`, `testkit.AnswerService(name, answer)` | Options of the component kits: the components a handler calls, and the answer a call to another service gets; a call to a service no `AnswerService` names fails as unresolvable |
| `testkit.StartT(t, service)` | The whole service through the real sidecar image and a throwaway Postgres; `Restart(ctx)` starts a new sidecar on the same database; skipped unless `ANKKA_SLOW=1` |

```go
func TestTheRoutesCallTheCart(t *testing.T) {
	ctx := context.Background()
	kit := testkit.Endpoint(cart.API, cart.Entity)
	defer kit.Close()
	if r := kit.Post(ctx, "/carts/e1/items", pen); r.Status != 204 {
		t.Fatalf("POST items answered %d %s %v", r.Status, r.Text(), r.Failure)
	}
	if r := kit.Get(ctx, "/carts/e1/total"); r.Text() != "2" {
		t.Fatalf("GET total answered %d %q", r.Status, r.Text())
	}
	if r := kit.Delete(ctx, "/carts/e1/items/nothing"); r.Status != 404 {
		t.Fatalf("removing what is not there answered %d", r.Status)
	}
	if r := kit.Post(ctx, "/carts/e1/checkout", ankka.Unit{}); r.Status != 200 {
		t.Fatalf("checkout answered %d %s", r.Status, r.Text())
	}
	if r := kit.Post(ctx, "/carts/e1/items", ink); r.Status != 409 {
		t.Fatalf("adding to a checked out cart answered %d", r.Status)
	}
}
```

The component kits need nothing running and still round-trip every value through its codec, so a type the
encoding cannot carry fails there. The integration kit starts `ANKKA_SIDECAR_IMAGE` when it is set,
`ankka-sidecar:latest` for a development build of the SDK, and otherwise the sidecar published under the
SDK's own version. See [Testing](../build/testing.md).

## The version and the development build

`ankka.Version()` is the SDK's version as the service's build recorded it, without the `v`: `0.14.0` for
a service that depends on `v0.14.0`. It is `0.0.0` for a development build, when the dependency is
replaced by a local directory, when the SDK is itself the main module, or when the build recorded
nothing. The service reports the version to the sidecar at discovery, and the integration test kit reads
it to choose the sidecar image. Nothing in the module carries a version of its own.

## The image

The image holds the one executable and nothing else: two stages, the first building a static
executable, the second a distroless static base with no shell, no package manager and no language
runtime, running as a non-root user. The sidecar is the platform's container, never part of this image.

```dockerfile
# The developer's container: only their program. The sidecar is the platform's.
# Built by scripts/image.sh, whose build context holds the SDK at the root and this example under
# examples/shopping-cart, so the module's `replace => ../..` resolves inside the build. A service that
# depends on the released module needs no staging: the same two stages from its own directory.
FROM golang:1.27-alpine AS build
ENV GOWORK=off CGO_ENABLED=0
WORKDIR /src
COPY . .
WORKDIR /src/examples/shopping-cart
RUN go mod download && go build -trimpath -ldflags='-s -w' -o /cart ./cmd/cart

FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=build /cart /cart
ENV ANKKA_PROCESS_PORT=9010
USER nonroot
ENTRYPOINT ["/cart"]
```

A service that depends on the released module builds these two stages from its own directory. The
process binds loopback, which the sidecar shares inside the pod. See [Images](../deploy/images.md).

## Limitations

The Go SDK has the limitations of the Python and TypeScript SDKs: a route reads its body whole and a large
object goes through a signed URL; a process's own log lines carry no trace; judgments and gRPC endpoints
are the JVM SDKs'; a handler names a configured model provider rather than a model. Beside those, a Go service
cannot be built as a WebAssembly module; the component test kits have no secret store, no scripted
services and no autonomous agent kit; calls to other services have no typed helpers; and there is no
personal-data envelope. The whole list is in [Limitations](limitations.md).

## Developing the SDK

From `sdks/go` in the ankka repository:

```bash
go vet ./...                       # the vet checks CI runs
go test ./...                      # the fast tests, no Docker: fixtures, codec, kits, servicers
ANKKA_SLOW=1 go test ./...         # the Docker-backed tests too (needs the ankka-sidecar image)
scripts/proto.sh                   # copy protocol/ in and regenerate internal/pb
scripts/proto.sh --check           # fail if internal/pb is not what the copy generates
(cd examples/shopping-cart && go run ./cmd/conformance)   # the platform's conformance suite
examples/shopping-cart/scripts/image.sh                   # the example's image
```

The SDK carries a copy of the protocol and the stubs generated from it, both committed because `go get`
runs no generator; CI fails when the copy differs from the original or when regenerating changes the
stubs. The conformance suite needs sbt. See [Adding a language SDK](../contributing/language-sdks.md).
