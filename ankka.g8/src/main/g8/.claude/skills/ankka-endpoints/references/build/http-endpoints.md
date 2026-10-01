# HTTP endpoints

> Expose a service over HTTP — routes, typed path parameters and bodies, responses, errors, query parameters and headers, access control and server-sent events — in Scala, Python or TypeScript.

Source: https://docs.ankka.cloud/build/http-endpoints/
An HTTP endpoint is how the outside world reaches a service. It declares routes under a path prefix,
turns each request into calls on components, and turns their replies into responses. Endpoints hold no
state; the components behind them do.

Every endpoint declares an access control list (ACL) saying who may call it. A service is private to its
cluster until it is exposed, but exposing it changes only who can reach the endpoint, never who is
allowed to call it. Decide the ACL before exposing the service; see [Expose a service](../deploy/expose.md).

## An endpoint

An endpoint declares a prefix, an access control list and its routes. This is the shopping cart sample's
whole endpoint, the same routes in each language:

**Scala**

```scala
package shoppingcart.api

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.thinkmorestupidless.ankka.core.{Codecs, EntityId}
import com.thinkmorestupidless.ankka.http.*
import com.thinkmorestupidless.ankka.sdk.ComponentClient
import shoppingcart.application.ShoppingCartEntity
import shoppingcart.domain.{LineItem, ShoppingCart}

/**
 * The API layer: HTTP in, component calls out.
 *
 * Note what is absent — no try/catch, no status codes, no error mapping. A rejection from the
 * entity carries its own `ErrorCode`, which the runtime turns into the right status, so this layer
 * only has to describe the happy path.
 */
final class ShoppingCartEndpoint(client: ComponentClient) extends HttpEndpoint("/carts"):

  // Response and request bodies need JSON codecs; derived at compile time.
  private given JsonValueCodec[ShoppingCart] = Codecs.make[ShoppingCart]
  private given JsonValueCodec[LineItem]     = Codecs.make[LineItem]

  /** A public read/write API, stated deliberately rather than defaulted. */
  val acl: Acl = Acl.AllowAll

  get("/{cartId}") { (cartId: String) =>
    cart(cartId).call(ShoppingCartEntity.getCart).invoke()
  }

  get("/{cartId}/total") { (cartId: String) =>
    cart(cartId).call(ShoppingCartEntity.totalQuantity).invoke()
  }

  postBody("/{cartId}/items") { (cartId: String, item: LineItem) =>
    cart(cartId).call(ShoppingCartEntity.addItem).invoke(item)
  }

  delete("/{cartId}/items/{productId}") { (cartId: String, productId: String) =>
    cart(cartId).call(ShoppingCartEntity.removeItem).invoke(productId)
  }

  post("/{cartId}/checkout") { (cartId: String) =>
    cart(cartId).call(ShoppingCartEntity.checkout).invoke()
  }

  delete("/{cartId}") { (cartId: String) =>
    cart(cartId).call(ShoppingCartEntity.discard).invoke()
  }

  private def cart(cartId: String) =
    client.forEventSourcedEntity(EntityId(cartId))
```

**Python**

```python
class ShoppingCartEndpoint(Endpoint):
    """The Scala sample's routes, exactly: /carts/{cartId}, /total, /items, /items/{productId}, /checkout, DELETE /carts/{cartId}."""

    prefix = "/carts"
    acl = Acl.ALLOW_ALL

    def __init__(self, client: ComponentClient) -> None:
        self.client = client

    def _cart(self, cart_id: str) -> Calls:
        return self.client.with_metadata(self.request.metadata).for_event_sourced_entity("shopping-cart", cart_id)

    @get("/{cartId}")
    async def get_cart(self, cartId: str) -> ShoppingCart:
        return await self._cart(cartId).call("get-cart").invoke(reply=ShoppingCart)

    @get("/{cartId}/total")
    async def total(self, cartId: str) -> int:
        return await self._cart(cartId).call("total-quantity").invoke(reply=int)

    @post("/{cartId}/items")
    async def add_item(self, cartId: str, item: LineItem) -> Done:
        return await self._cart(cartId).call("add-item").invoke(item, reply=Done)

    @delete("/{cartId}/items/{productId}")
    async def remove_item(self, cartId: str, productId: str) -> Done:
        return await self._cart(cartId).call("remove-item").invoke(productId, reply=Done)

    @post("/{cartId}/checkout")
    async def checkout(self, cartId: str) -> ShoppingCart:
        return await self._cart(cartId).call("checkout").invoke(reply=ShoppingCart)

    @delete("/{cartId}")
    async def discard(self, cartId: str) -> Done:
        return await self._cart(cartId).call("discard").invoke(reply=Done)
```

**TypeScript**

```ts
/** The Scala sample's routes, exactly: /carts/{cartId}, /total, /items, /items/{productId}, /checkout, DELETE /carts/{cartId}. */
export class ShoppingCartEndpoint extends Endpoint {
  static readonly prefix = "/carts"
  static readonly acl = Acl.allowAll

  static readonly routes = {
    getCart: get("/{cartId}", ShoppingCart, (ep: ShoppingCartEndpoint, req) => ep.cart(req.params.cartId).call(ShoppingCartEntity.handlers.getCart).invoke()),
    total: get("/{cartId}/total", s.int, (ep: ShoppingCartEndpoint, req) => ep.cart(req.params.cartId).call(ShoppingCartEntity.handlers.totalQuantity).invoke()),
    addItem: post("/{cartId}/items", LineItem, Done, (ep: ShoppingCartEndpoint, req, item) => ep.cart(req.params.cartId).call(ShoppingCartEntity.handlers.addItem).invoke(item)),
    removeItem: del("/{cartId}/items/{productId}", Done, (ep: ShoppingCartEndpoint, req) =>
      ep.cart(req.params.cartId).call(ShoppingCartEntity.handlers.removeItem).invoke(req.params.productId),
    ),
    checkout: post("/{cartId}/checkout", ShoppingCart, (ep: ShoppingCartEndpoint, req) => ep.cart(req.params.cartId).call(ShoppingCartEntity.handlers.checkout).invoke()),
    discard: del("/{cartId}", Done, (ep: ShoppingCartEndpoint, req) => ep.cart(req.params.cartId).call(ShoppingCartEntity.handlers.discard).invoke()),
```

A Scala endpoint extends `HttpEndpoint(prefix)` and declares routes in its body, so they are collected
when the endpoint is constructed, at service start. A route whose handler takes a different number of
parameters than its template names fails the service at startup rather than on the first request that
matches it.

A Python endpoint is a class with a `prefix`, an `acl` and decorated methods — `@get`, `@post`, `@put`,
`@delete`, `@patch` and `@sse` — and a TypeScript one declares its routes in a `routes` object. In both,
path parameters bind by name from the template, at most one further parameter is the body, and the
declared reply type decides the response's encoding. A `GET` route cannot take a body. The constructor
may take a component client, and the SDK passes one when it does.

**A Python or TypeScript process never binds an HTTP port.** The sidecar serves the routes the process
declared, applies the ACL, opens the request's trace, and forwards each request to the process.

## Routes

| Method | Declared with | Body |
|---|---|---|
| `GET` | `get(template) { … }` | none |
| `DELETE` | `delete(template) { … }` | none |
| `POST` | `post(template) { … }` or `postBody(template) { … }` | `postBody` decodes one |
| `PUT` | `put(template) { … }` or `putBody(template) { … }` | `putBody` decodes one |
| `PATCH` | `patch(template) { … }` or `patchBody(template) { … }` | `patchBody` decodes one |
| `GET`, as server-sent events | `sse(template) { … }` | none |
| `POST`, as server-sent events | `sseBody(template) { … }` | one |

A template is relative to the prefix and names its path parameters in braces: `"/{cartId}/items/{productId}"`.
A handler takes up to two path parameters, in template order, followed by the body for the `…Body`
forms. **Annotate every handler parameter with its type** — `{ (cartId: String) => … }`, not
`{ cartId => … }` — because the annotation is what selects the right overload and how the parameter is
parsed.

A path parameter may be a `String`, `Int`, `Long`, `Boolean` or `java.util.UUID`. A value that does not
parse is a `400` naming the problem, before the handler runs.

**Literal segments outrank parameters.** With both `/{cartId}` and `/awkward` declared, a request for
`/awkward` goes to the literal route whatever order the two were declared in. Routes are matched most
specific first, so a route like `/users/me` never depends on being declared before `/users/{id}`.

## Request and response bodies

A body is decoded with a `JsonValueCodec` in scope, or taken as raw text when the body type is `String`.
The sample declares its codecs as private givens, derived with `Codecs.make`. A body that does not
decode is a `400`.

What a handler returns decides the response:

| Return type | Response |
|---|---|
| `Done` or `Unit` | `204 No Content` |
| `String` | `200`, `text/plain` |
| `Int`, `Long`, `Double`, `Boolean` | `200`, `text/plain`, the value as text |
| any type with a `JsonValueCodec` | `200`, `application/json` |
| `Html(markup)` | `200`, `text/html; charset=UTF-8` |
| `Bytes(contentType, body)` | `200`, the content type given: a stylesheet, an image, a download |
| `Respond(body, status, headers)` | any of the above under a status and headers of the handler's choosing |

### Pages, redirects and cookies

An endpoint that serves a website rather than an API returns HTML, sends the browser elsewhere, and
keeps a session. `Respond` wraps any body the endpoint can already answer with a status and headers:

```scala
get("/account")(() => Respond(Html(page), headers = Vector("Set-Cookie" -> cookie)))
post("/logout")(() => Respond.redirect("/"))            // 303 See Other, Location: /
get("/style.css")(() => Bytes("text/css", stylesheet))
```

`Respond.redirect` answers `303 See Other`, the status that is safe after a form post; pass another
status to change it. `Content-Type` is never a header here: it comes from the body, and a `Bytes`
value names its own.

## Errors

A handler reports a problem by throwing, or by letting a component's refusal propagate. Either way the
caller receives a JSON body with the status and a message:

```json
{"status":409,"error":"cart is already checked out"}
```

| Thrown | Status |
|---|---|
| `HttpProblem(status, message)` | as given; `HttpProblem.badRequest`, `unauthorized`, `forbidden`, `notFound`, `conflict` are shorthands |
| `CommandError` with `ErrorCode.BadRequest` | `400` |
| `CommandError` with `ErrorCode.Unauthorized` | `401` |
| `CommandError` with `ErrorCode.Forbidden` | `403` |
| `CommandError` with `ErrorCode.NotFound` | `404` |
| `CommandError` with `ErrorCode.Conflict` | `409` |
| `CommandError` with `ErrorCode.Timeout` | `504` |
| `CommandError` with `ErrorCode.Unavailable` | `503` |
| `CommandError` with `ErrorCode.Internal` | `500` |
| `IllegalArgumentException` | `400` |
| anything else | `500`, with the message withheld and the failure logged |

This mapping is why an entity's `effects.error(message, ErrorCode.Conflict)` reaches an HTTP caller as a
`409` with no code in the endpoint. See [Error codes](../reference/error-codes.md).

## Query parameters and headers

Path parameters and the body arrive as typed arguments, because they are structural: a request either
has them or is not for that route. Query parameters and headers vary per call, so a handler reads them
from the request instead:

```scala
/** Required, optional-with-default, repeated, and flag parameters. */
get("/") { () =>
  SearchResult(
    term = query.required[String]("q"),
    limit = query.optional[Int]("limit").getOrElse(20),
    tags = query.all[String]("tag").toList,
    verbose = query.flag("verbose")
  )
}
```

```scala
/** Headers. */
get("/trace") { () =>
  request.header("X-Trace-Id").getOrElse("none")
}
```

| Method on `query` | Meaning |
|---|---|
| `required[A](name)` | The value, parsed. Absent is a `400` naming the parameter. |
| `optional[A](name)` | `Some(value)` or `None`. Present but unparseable is a `400`. |
| `all[A](name)` | Every value of a repeated parameter, in order. |
| `flag(name)` | `true` when present with no value or with `true`, so `?verbose` and `?verbose=true` agree. |
| `raw(name)`, `rawAll(name)`, `contains(name)` | Unparsed access. |

`required` does not substitute a default. A missing parameter the handler needed is the caller's mistake,
and a `400` saying which is more useful than a puzzling empty result. The same parsers read query values
and path segments, so `?limit=abc` and a bad path segment produce the same message.

`request` also carries the method, the path, all headers, the remote address and the principal when the
ACL established one.

**`request` belongs to the handler's thread.** Each handler runs on its own virtual thread, so there is
exactly one request per thread, and the request is cleared when the handler returns. Work handed to
another thread cannot see it. Read what you need first and pass it on. For a streaming route this means
reading parameters while building the `Source`, because its elements are pulled later on another thread:

```scala
sse("/stream") { () =>
  val term  = query.required[String]("q")
  val count = query.optional[Int]("count").getOrElse(2)
  org.apache.pekko.stream.scaladsl.Source((1 to count).map(n => s"\$term-\$n").toVector)
}
```

## Access control

`acl` is abstract, so every endpoint states who may call it. An endpoint nobody decided about cannot be
compiled.

| ACL | Meaning |
|---|---|
| `Acl.DenyAll` | Every request is refused with `403`. The service logs a warning at startup. |
| `Acl.AllowAll` | Any caller. Right for a public API; state it deliberately. |
| `Acl.AllowIf(context => Boolean)` | A predicate over the request. A refusal is `403`. |
| `Acl.Authenticate(context => AuthDecision)` | An authenticator that decides who the caller is. |
| `Acl.allowCallers(callers*)` | Only the workloads named: the internet, a service, any service in the project, or this service. A refusal is `403`. |

`AllowIf` inspects the same request the handler will see:

```scala
/** An endpoint whose ACL inspects the request — the same context the handler sees. */
final class GatedEndpoint extends HttpEndpoint("/gated"):

  val acl: Acl = Acl.AllowIf(context =>
    context.header("X-Api-Key").contains("let-me-in") || context.query.flag("public")
  )

  get("/")(() => "allowed")
```

`Authenticate` returns one of four decisions, so a caller is told which kind of no they got:

| Decision | Response |
|---|---|
| `AuthDecision.Allow(principal)` | The request proceeds, and the handler reads `principal`. |
| `AuthDecision.Unauthenticated(challenge)` | `401` with `WWW-Authenticate: Bearer <challenge>`: log in. |
| `AuthDecision.Forbidden(reason)` | `403`: logged in, and not allowed. |
| `AuthDecision.Unavailable(reason)` | `503` with `Retry-After`: the check could not be made, for example because signing keys could not be fetched. |

ankka does not ship a check for a specific identity provider for your services. To know which *user* a
request is for, plug a verified token into `Authenticate`. To know which *workload* sent it, use
`allowCallers`.

### Name who may call

`Acl.allowCallers` admits a request only from the workloads it names. In a cluster every connection to a
service is mutual TLS, and the caller is read from the client certificate the platform issued the calling
workload, so it cannot be forged by anything the request says about itself:

| Caller | Admitted by |
|---|---|
| a request from outside the cluster, through the gateway | `Callers.internet` |
| the `orders` service in this service's project | `Callers.service("orders")` |
| the `invoices` service in the `billing` project | `Callers.service("billing", "invoices")` |
| any service in this service's project | `Callers.anyInProject` |
| another instance of this service | `Callers.self` |

```scala
// Only the internet and the orders service in this project; any other caller is refused 403.
withAcl(Acl.allowCallers(Callers.internet, Callers.service("orders"))) {
  get("/only-orders")(() => s"admitted: \${describe(caller)}")
}

// Another instance of this very service, and nothing else.
withAcl(Acl.allowCallers(Callers.self)) {
  get("/only-self")(() => "admitted: myself")
}
```

A handler reads the caller as `caller`, which is always present:

```scala
get("/whoami") { () =>
  caller match
    case Caller.Gateway                => "the internet, through the gateway"
    case Caller.Service(project, name) => s"the \$name service in project \$project"
    case Caller.Local                  => "this machine"
}
```

`caller` is set before any ACL runs, so an `AllowIf` predicate can read it too, and it is independent of
`principal`: a request from the `orders` service on behalf of a signed-in user has both.

The refusal body names no caller, so an unauthorised workload learns nothing about whose certificate it
would need. A client certificate the installation issued that names no service is refused `403` before
routing.

**Outside a cluster every caller is the local machine**, `Caller.Local`, because there is no certificate to
read, and every `allowCallers` admits it. The service logs once at startup that callers are not enforced.
A test names a caller through the test kit, which shares a secret with the service in the same JVM:

```scala
test("the orders service is admitted and the payments service is not") {
  assertEquals(get("/callers/only-orders", Some(Caller.Service("local", "orders")))._1, 200)
  assertEquals(get("/callers/only-orders", Some(Caller.Service("local", "payments")))._1, 403)
}
```

where the request carries the header `testKit.asCaller(caller)` returns. A service running locally is in
project `local` and is itself `local/local`.

### A route with its own ACL

`withAcl` gives the routes declared inside it a different ACL from the endpoint's. It *replaces* the
endpoint's for those routes rather than adding to it, so an open endpoint can hold one protected route,
and a closed one can open a single route, without either being split in two at a second prefix:

```scala
/**
 * One endpoint, two audiences: reading a cart is public, purging one is not.
 *
 * `withAcl` replaces the endpoint's ACL for the routes declared inside it, so neither audience
 * needs an endpoint of its own at a second prefix.
 */
final class MixedAclEndpoint extends HttpEndpoint("/mixed"):

  val acl: Acl = Acl.AllowAll

  get("/{cartId}")((cartId: String) => s"cart:\$cartId")

  withAcl(
    Acl.Authenticate(context =>
      context.header("X-Support-Id") match
        case Some(id) => AuthDecision.Allow(Principal(id))
        case None     => AuthDecision.Unauthenticated("""realm="support"""")
    )
  ) {
    delete("/{cartId}")((cartId: String) => s"purged:\$cartId by \${principal.subject}")
  }
```

Scopes nest, and the innermost one wins. A request whose path matches no route of the endpoint is judged
by the endpoint's own ACL, so an endpoint that refuses answers the same way for a path that exists and one
that does not, rather than disclosing which is which.

## Call another service

`clients.services` calls another service's endpoints as this service. It is addressed by name: a service
in this project by its name, one in another project by project and name.

```scala
// Calls `/callers/whoami` on another service in this project, as this service: the answer is
// how that service saw this one.
get("/call/{service}") { (service: String) =>
  try services(service).getText("/callers/whoami")
  catch case e: ServiceUnresolvable => throw HttpProblem(503, e.getMessage)
}
```

In a cluster the call is mutual TLS: it presents this service's certificate, so the callee's
`allowCallers` sees who is calling, and it accepts the callee only if its certificate names the service
asked for — a workload holding another service's certificate fails the handshake before anything is sent.
The address is the callee's Kubernetes Service and its port is read from DNS, so a descriptor that changes
the port changes nothing here.

Outside a cluster the same call reaches the named service on this machine over plain HTTP: the address
set as `ankka.local-services.<name>` if there is one, otherwise the address the service announced to the
local console.

| Method | Answers |
|---|---|
| `get[R](path)`, `post[B, R](path, body)`, `put[B, R](path, body)` | the JSON body decoded as `R`; any status other than 2xx throws `ServiceCallFailed` |
| `getText(path)` | the body as text, what a route returning a `String` sends |
| `delete(path)` | nothing; any 2xx succeeds |
| `request(method, path, body, contentType, headers)` | the `ServiceResponse`, whatever its status |

`ServiceUnresolvable` means nothing was found under the name and nothing was sent; `ServiceIdentityMismatch`
means the service reached is not the one asked for. There are no retries and no redirects: whether a call
is safe to repeat is the caller's to know. Components reach the same clients as `service.services`.

## Registering endpoints

In Scala, endpoints are served by the `HttpServer` extension, which takes one factory per endpoint, a
function from the service's clients to the endpoint. In Python and TypeScript an endpoint is registered
like any other component, and the sidecar serves it:

**Scala**

```scala
Ankka.service
  .register(ShoppingCartEntity.descriptor)
  .withExtension(HttpServer.of(clients => ShoppingCartEndpoint(clients.componentClient)))
  .start()
```

**Python**

```python
service = (
    Ankka.service()
    .register(ShoppingCartEntity)
    .register(ShoppingCartEndpoint)
)

asyncio.run(service.listen())
```

**TypeScript**

```ts
const service = Ankka.service()
  .register(ShoppingCartEntity)
  .register(ShoppingCartEndpoint)

await service.listen()
```

`clients` is an `EndpointClients`, which carries `componentClient` for components and `viewClient` for
views. The lambda cannot be shortened to `ShoppingCartEndpoint(_.componentClient)`: the placeholder would
bind to the inner expression, and that passes a function where a `ComponentClient` is expected.

`HttpServer.of(...)` binds the interface and port from configuration: `0.0.0.0` and `9000`, overridable
with `ANKKA_HTTP_INTERFACE` and `ANKKA_HTTP_PORT`. `HttpServer.at(interface, port)(...)` binds explicitly;
port `0` picks a free port, which is what tests use. On the platform the port comes from the service
descriptor, and setting `ANKKA_HTTP_PORT` yourself is refused; see
[Service descriptor](../reference/service-descriptor.md).

Two endpoints may not share a prefix. The server also answers `/_ankka/health` on its own.

## The request, outside Scala

`self.request` in Python and `req` in TypeScript carry the query and the headers as sequences of pairs,
with helpers for one or many, the `principal` when the ACL established one, and the `caller` the platform
established. Every SDK answers with a status by raising or throwing an `HttpProblem(status, message)`:

**Scala**

```scala
get("/{cartId}/rows") { (cartId: String) =>
  clients.viewClient
    .forView(CartRows)
    .byId(cartId)
    .getOrElse(throw HttpProblem.notFound(s"no row for cart '\$cartId'"))
}
```

**Python**

```python
@get("/{cartId}/rows")
async def row(self, cartId: str) -> CartRow:
    found = await self.client.views.get("cart-rows", cartId, CartRow)
    if found is None:
        raise HttpProblem(404, f"no row for cart '{cartId}'")
    return found  # type: ignore[no-any-return]
```

**TypeScript**

```ts
row: get("/{cartId}/rows", CartRow, async (ep: ShoppingCartEndpoint, req) => {
  const found = await ep.client.views.get(CartRows.componentId, req.params.cartId, CartRow)
  if (found === null) throw new HttpProblem(404, `no row for cart '\${req.params.cartId}'`)
  return found
}),
```

A `CommandError` from a component call propagates as its code's status, as in Scala. Routes are matched
by the same rules, so a literal segment outranks a parameter.

The Python ACL is a required class attribute: an endpoint that declares no `acl` raises `RegistrationError`
when the class is defined, naming it. `Acl.ALLOW_ALL` admits any caller, `Acl.DENY_ALL` refuses everything,
and `Acl.AUTHENTICATED` answers `503` for now, because the sidecar has no token verifier configured for a
service's own routes. A route decorator takes an `acl` of its own, which replaces the endpoint's for that
route exactly as `withAcl` does in Scala:

```python
class CartsEndpoint(Endpoint):
    prefix = "/carts"
    acl = Acl.ALLOW_ALL

    @get("/{cart_id}")
    async def get_cart(self, cart_id: str) -> Cart: ...

    @delete("/{cart_id}", acl=Acl.DENY_ALL)
    async def purge(self, cart_id: str) -> None: ...
```

Both SDKs name callers as Scala does, with the same meaning; the sidecar applies the ACL before the process
is asked anything, and hands the handler the caller:

**Python**

```python
class CallersEndpoint(Endpoint):
    prefix = "/callers"
    acl = Acl.allow_callers(Callers.internet, Callers.service("orders"))

    @get("/whoami")
    def whoami(self) -> str:
        c = self.request.caller
        if isinstance(c, ServiceCaller):
            return f"service:{c.project}/{c.name}"
        return "gateway" if isinstance(c, Gateway) else "local"

    @get("/self", acl=Acl.allow_callers(Callers.self_))
    def only_self(self) -> str:
        return "self"
```

**TypeScript**

```ts
export class CallersEndpoint extends Endpoint {
  static readonly prefix = "/callers"
  static readonly acl = Acl.allowCallers(Callers.internet, Callers.service("orders"))
  static readonly routes = {
    whoami: get("/whoami", s.string, (_ep: CallersEndpoint, req) => {
      const c = req.caller
      return c.kind === "service" ? `service:\${c.project}/\${c.name}` : c.kind
    }),
    onlySelf: get("/self", s.string, () => "self", { acl: Acl.allowCallers(Callers.self) }),
  }
}
```

In Python `Callers.self_` carries a trailing underscore so it does not shadow `self`. A Python or TypeScript
service can be called as described in [Name who may call](#name-who-may-call), but has no service client
of its own yet: calling another service as itself is Scala-only.

A `str` return value is answered as `text/plain`, and a `str` body is read as raw text, not as a JSON
string — the same encoding the Scala SDK uses.
