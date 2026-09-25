# HTTP endpoints

> Expose a service over HTTP — routes, typed path parameters and bodies, responses, errors, query parameters and headers, access control and server-sent events — in Scala or Python.

Source: https://docs.ankka.cloud/build/http-endpoints/
An HTTP endpoint is how the outside world reaches a service. It declares routes under a path prefix,
turns each request into calls on components, and turns their replies into responses. Endpoints hold no
state; the components behind them do.

Every endpoint declares an access control list (ACL) saying who may call it. A service is private to its
cluster until it is exposed, but exposing it changes only who can reach the endpoint, never who is
allowed to call it. Decide the ACL before exposing the service; see [Expose a service](../deploy/expose.md).

## An endpoint in Scala

An endpoint extends `HttpEndpoint(prefix)`, declares its `acl`, and declares routes in its body. This is
the shopping cart sample's whole endpoint:

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

  private def cart(cartId: String) =
    client.forEventSourcedEntity(EntityId(cartId))
```

Note what is absent: no status codes, no error mapping, no `try`. A command the entity refuses carries an
error code, and the server turns it into the right status, so the endpoint describes only the successful
path.

Routes are collected as they are declared, when the endpoint is constructed, which is at service start.
A route whose handler takes a different number of parameters than its template names fails the service
at startup rather than on the first request that matches it.

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

ankka does not ship a check for a specific identity provider for your services, and deliberately has no
"same service" principal: establishing who a caller is needs a verified token or a client certificate,
and a check against a header the client sets is not security. Plug a real check into `Authenticate`. The
platform establishes no caller identity of its own — see [Limitations](../reference/limitations.md).

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

## Registering endpoints

Endpoints are served by the `HttpServer` extension. It takes one factory per endpoint, a function from the
service's clients to the endpoint:

```scala
Ankka.service
  .register(ShoppingCartEntity.descriptor)
  .withExtension(HttpServer.of(clients => ShoppingCartEndpoint(clients.componentClient)))
  .start()
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

## An endpoint in Python

A Python endpoint is a class with a `prefix`, an `acl`, and decorated methods. Path parameters bind by
name from the template; at most one further parameter is the body; the return annotation decides the
response's encoding.

```python
class ShoppingCartEndpoint(Endpoint):
    """The Scala sample's routes, exactly: /carts/{cartId}, /total, /items, /items/{productId}, /checkout."""

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
```

The decorators are `@get`, `@post`, `@put`, `@delete`, `@patch` and `@sse`. A `GET` route cannot take a
body. The endpoint's constructor may take a `ComponentClient`, and the SDK passes one when it does.

**The process never binds an HTTP port.** The sidecar serves the routes the process declared, applies
the ACL, opens the request's trace, and forwards each request to the process. Passing
`self.request.metadata` to the client with `with_metadata`, as `_cart` does, is what makes the calls a
handler makes appear as children of the request in the console's traces.

`self.request` carries `query` and `headers` as sequences of pairs, with `query_param(name)`,
`query_params(name)` and `header(name)` helpers, and `principal` when the ACL established one. Raise
`HttpProblem(status, message)` to answer with a status:

```python
@get("/{cartId}/rows")
async def row(self, cartId: str) -> CartRow:
    found = await self.client.views.get("cart-rows", cartId, CartRow)
    if found is None:
        raise HttpProblem(404, f"no row for cart '{cartId}'")
    return found  # type: ignore[no-any-return]
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

A `str` return value is answered as `text/plain`, and a `str` body is read as raw text, not as a JSON
string — the same encoding the Scala SDK uses.
