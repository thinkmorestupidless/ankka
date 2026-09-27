# Calling components

> Call entities, workflows and agents through the component client — blocking or asynchronous, with typed refusals and timeouts — and query views through the view client.

Source: https://docs.ankka.cloud/build/component-client/
Components call each other through the component client, never by calling methods directly. The target
of a call — an entity instance, a workflow instance, an agent session — may be on another node of the
service's cluster, and the client routes the call there. What the client hides is the routing. What it
does not hide is that a call can fail: a refusal arrives as a `CommandError` with an error code, never as
a default value.

Endpoints, workflow steps, consumers, timed actions and agents all have a client. An entity's command
handler does not call other components: it decides from its own state and returns an effect. See
[Designing a service](../concepts/designing-services.md) for where cross-component logic belongs.

## Addressing a component

The client is addressed first by kind and instance, then by handler:

**Scala**

```scala
val cart = componentClient.forEventSourcedEntity(EntityId("cart-1"))

cart.call(ShoppingCartEntity.addItem).invoke(LineItem("p1", "Widget", 2))   // Done
cart.call(ShoppingCartEntity.getCart).invoke()                              // ShoppingCart
```

**Python**

```python
cart = client.for_event_sourced_entity("shopping-cart", "c1")

await cart.call("add-item").invoke(LineItem("p1", "Pen", 2))            # Done
state = await cart.call("get-cart").invoke(reply=ShoppingCart)          # decoded as ShoppingCart
```

**TypeScript**

```ts
const cart = client.of(ShoppingCartEntity, "cart-1")

await cart.call(ShoppingCartEntity.handlers.addItem).invoke({ productId: "p1", name: "Widget", quantity: 2 })
await cart.call(ShoppingCartEntity.handlers.getCart).invoke()          // ShoppingCart
```

In Scala and TypeScript the handler is the typed value the component declared, so the argument and reply
types are checked by the compiler and the component's id travels with the handler. Python names the
component by its id and the handler by its wire name, and every call is awaited.

| Addresses | Scala | Python | TypeScript |
|---|---|---|---|
| an event sourced entity instance | `forEventSourcedEntity(entityId)` | `for_event_sourced_entity(component_id, entity_id)` | `of(Entity, entityId)` |
| a key value entity instance | `forKeyValueEntity(entityId)` | `for_key_value_entity(component_id, entity_id)` | `of(Entity, entityId)` |
| a workflow instance | `forWorkflow(workflowId)`; `lifecycle(companion)` for the engine's state | `for_workflow(component_id, workflow_id)` | `of(Workflow, workflowId)` |
| an agent session | `forAgent(sessionId)`, with `import com.thinkmorestupidless.ankka.agent.*` | `for_agent(component_id, session_id)` | `of(Agent, sessionId)` |
| a view's rows | the view client; see [Views](views.md) | `views.get(view_id, key, Row)`, `views.all(view_id, Row)` | `views.get(viewId, key, Row)`, `views.all(viewId, Row)` |
| timers | the timer scheduler; see [Timers](timers.md) | `timers.schedule(...)`, `timers.cancel(timer_id)` | `timers.schedule(...)`, `timers.cancel(timerId)` |

A streaming handler is reached with `stream(handler)(input)` in Scala and `.call(name).stream(input)` in
Python and TypeScript.

The instance is identified by the id. An instance that has never been written to exists already, holding
its empty state, so reading one is not an error.

Where the client comes from depends on what is calling:

| Caller | Scala | Python and TypeScript |
|---|---|---|
| An HTTP endpoint | `clients.componentClient`, from the factory given to `HttpServer.of` | the endpoint's own `client` |
| A workflow | `context.componentClient`, from the `WorkflowContext` | `self.context.client` / `this.client` |
| A consumer | its context's `componentClient` | `self.client` / `this.client` |
| A timed action | `context.componentClient`, from the `TimedActionContext` | `self.client` / `this.client` |
| An agent | `componentClient`, inherited from `Agent` | `self.client` / `this.client` |
| A test | `testKit.componentClient`, from `AnkkaTestKit` | the test kit's own client |

In Python and TypeScript the client talks to the sidecar, which routes the call through the cluster
exactly as a Scala call is routed.

## Blocking is free

`invoke` blocks until the reply arrives. That is the recommended style, because every place a call can
be made from — endpoints, workflow steps, consumers, timed actions, agent loops — runs on a virtual
thread, and a blocked virtual thread releases the operating-system thread it was using. Sequential code
stays readable and costs nothing to wait.

`invokeAsync` returns a `Future` instead, for fanning out several calls at once.
`ComponentClient.await(future, timeout)` collects one, parking the virtual thread in the same way:

```scala
val pending = cartIds.map(id =>
  componentClient.forEventSourcedEntity(EntityId(id)).call(ShoppingCartEntity.totalQuantity).invokeAsync()
)
val totals = pending.map(ComponentClient.await(_, 10.seconds))
```

The calls run concurrently, and the whole takes as long as the slowest. The
[multi-agent orchestration](multi-agent-orchestration.md) guide uses the same pattern to consult several
agents at once.

## Refusals and failures

A component that refuses a command returns an error effect with a code. The caller receives it as a
`CommandError` carrying that code and message, whether the component ran on this node or another:

```scala
try cart.call(ShoppingCartEntity.addItem).invoke(LineItem("p1", "Widget", 0))
catch case e: CommandError if e.code == ErrorCode.BadRequest => ...
```

Nothing is persisted by a refused command. In an endpoint, let a `CommandError` propagate: the server
answers with the status for its code, so a `Conflict` in an entity is a `409` at the edge with no mapping
in between. See [Error codes](../reference/error-codes.md).

## Timeouts

A blocking `invoke` waits for `ankka.ask-timeout`, 10 seconds by default, and then fails with a
`CommandError` whose code is `Timeout`. Change it in the service's `application.conf`:

```hocon
ankka.ask-timeout = 30s
```

A timeout says the reply did not arrive in time, not that the command did not happen. A command that
timed out may still have been applied. When a caller retries after a timeout, the command must be safe to
receive twice.

Agent calls usually take longer than an entity call. A workflow step that calls an agent should await
with an explicit timeout, as `ComponentClient.await(answer, 90.seconds)` does, and declare a step timeout
to match; see [Workflows](workflows.md).

## Metadata

`withMetadata(metadata)` on an invocation attaches metadata to the call, which the handler reads from its
command context. The runtime uses metadata to carry a request's trace from component to component.

## Views

Views are queried through the view client, not the component client, because a view is not addressed by
an instance id: the point of a view is to be queried by attributes rather than by key. An endpoint gets
it as `clients.viewClient`:

```scala
val rows = clients.viewClient.forView(CartRows)
```

`forView` takes the view's companion, so the row type is carried into the queries. The queries
themselves are described in [Views](views.md).

## The request context does not follow work to another thread

A handler's request — query parameters, headers, the principal — and its trace belong to the handler's
own thread. A call made from that thread with `invoke` or `invokeAsync` carries the trace, because the
call is issued there. Work handed to another thread, such as a `Future` callback or a thread pool, cannot
see the request, and calls made from it appear in the console as unattributed time. Read what you need
first, and issue calls from the handler's thread.

## Encoding a call

In Python, `invoke(input, reply=Type)` encodes the input with its type's default codec and decodes the
reply as `reply`; without `reply` the call expects `Done`, and `codec=` and `reply_codec=` override
either codec. TypeScript takes both shapes from the handler, so a call needs no annotation. In Scala both
come from the handler's `given` serializers. A refusal is a `CommandError` in every SDK, carrying the
`ErrorCode` the handler chose.

## Pass the request's metadata on

A call made from an endpoint should be a child of the request in the console, not a trace of its own.
Scala gets that from the request context on the handler's own thread; Python and TypeScript carry it by
passing the request's metadata to the client:

**Scala**

```scala
post("/{cartId}/checkouts") { (cartId: String, mode: String) =>
  // The request's context is already on this thread, so the call joins its trace.
  client.forWorkflow(EntityId(cartId)).call(CheckoutWorkflow.start).invoke(mode)
}
```

**Python**

```python
@post("/{cartId}/checkouts")
async def start_checkout(self, cartId: str, mode: str) -> Done:
    """``mode`` is the body: ``ok``, ``fail`` or ``pause``."""
    return await self.client.with_metadata(self.request.metadata).for_workflow("checkout", cartId).call("start").invoke(mode or "ok", reply=Done)
```

**TypeScript**

```ts
/** The body is the mode: `ok`, `fail` or `pause`. */
startCheckout: post("/{cartId}/checkouts", s.string, Done, (ep: ShoppingCartEndpoint, req, mode) =>
  ep.client.of(CheckoutWorkflow, req.params.cartId).call(CheckoutWorkflow.handlers.start).invoke(mode || "ok"),
),
```

`with_metadata` and `withMetadata` return a client whose calls carry that metadata; a call made without
it starts a trace of its own. The Scala form has the caveat in
[the request context does not follow work to another thread](#the-request-context-does-not-follow-work-to-another-thread):
work handed to another thread cannot see the context, and there the metadata must be passed explicitly.
