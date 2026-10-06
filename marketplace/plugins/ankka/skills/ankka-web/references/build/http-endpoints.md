# HTTP endpoints

> Expose a service over HTTP — routes, typed path parameters and bodies, responses, errors, query parameters and headers, access control and server-sent events — in Scala, Python or TypeScript.

Source: https://docs.ankka.cloud/build/http-endpoints/
An HTTP endpoint is how the outside world reaches a service. It declares routes under a path prefix,
turns each request into calls on components, and turns their replies into responses. Endpoints hold no
state; the components behind them do. A Scala service can also serve a `.proto` service definition, beside
its HTTP endpoints or instead of them; see [gRPC endpoints](grpc-endpoints.md).

Every endpoint declares an access control list (ACL) saying who may call it. A service is private to its
cluster until it is exposed, but exposing it changes only who can reach the endpoint, never who is
allowed to call it. Decide the ACL before exposing the service; see [Expose a service](../deploy/expose.md).

## An endpoint

An endpoint declares a prefix, an access control list and its routes. This is the shopping cart sample's
whole endpoint, the same routes in each language:

**Scala**

```scala
package shoppingcart.api

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonValueCodec, writeToString}
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

  // A socket: the client sends "refresh" and is sent the cart, for as long as it keeps the socket
  // open. The handler is ordinary blocking code on a virtual thread; `receive()` answers `None`
  // once the socket is closed, which ends the loop and the handler.
  socket("/{cartId}/watch") { (cartId: String, socket: Socket) =>
    Iterator.continually(socket.receive()).takeWhile(_.isDefined).flatten.foreach {
      case "refresh" =>
        socket.send(writeToString(cart(cartId).call(ShoppingCartEntity.getCart).invoke()))
      case other => socket.send(s"""{"error":"unknown request '$other'; send refresh"}""")
    }
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
`@delete`, `@patch`, `@sse` and `@socket` — and a TypeScript one declares its routes in a `routes` object. In both,
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
| `GET`, opening a socket | `socket(template) { … }` | none; see [Sockets](#sockets) |

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

## Sockets

A socket route keeps a connection open in both directions. A request to it opens a **socket**: the
client and the handler send each other **frames** — pieces of text — until one of them closes it. The
handler runs for as long as the socket is open, on a virtual thread in Scala and as an async function
in Python and TypeScript, so it is written as an ordinary loop: wait for a frame, answer it, call a
component between frames.

**Scala**

```scala
// A socket: the client sends "refresh" and is sent the cart, for as long as it keeps the socket
// open. The handler is ordinary blocking code on a virtual thread; `receive()` answers `None`
// once the socket is closed, which ends the loop and the handler.
socket("/{cartId}/watch") { (cartId: String, socket: Socket) =>
  Iterator.continually(socket.receive()).takeWhile(_.isDefined).flatten.foreach {
    case "refresh" =>
      socket.send(writeToString(cart(cartId).call(ShoppingCartEntity.getCart).invoke()))
    case other => socket.send(s"""{"error":"unknown request '$other'; send refresh"}""")
  }
}
```

**Python**

```python
# A socket: the client sends "refresh" and is sent the cart, for as long as it keeps the socket
# open. `async for` ends when the socket is closed, and so does the handler.
@socket("/{cartId}/watch")
async def watch(self, cartId: str, socket: Socket) -> None:
    async for text in socket:
        if text == "refresh":
            cart = await self._cart(cartId).call("get-cart").invoke(reply=ShoppingCart)
            await socket.send(default_codec_for(ShoppingCart).encode(cart).decode("utf-8"))
        else:
            await socket.send(json.dumps({"error": f"unknown request {text!r}; send refresh"}))
```

**TypeScript**

```ts
// A socket: the client sends "refresh" and is sent the cart, for as long as it keeps the socket open.
// `for await` ends when the socket is closed, and so does the handler.
watch: socket("/{cartId}/watch", async (ep: ShoppingCartEndpoint, req, socket) => {
  for await (const text of socket) {
    if (text === "refresh") {
      const cart = await ep.cart(req.params.cartId).call(ShoppingCartEntity.handlers.getCart).invoke()
      await socket.send(new TextDecoder().decode(defaultCodecFor(ShoppingCart).encode(cart)))
    } else {
      await socket.send(JSON.stringify({ error: `unknown request '${text}'; send refresh` }))
    }
  }
}),
```

In Scala, `socket.receive()` waits for the next frame and answers `None` once the socket is closed,
and `socket.send(text)` waits while the client is not reading and throws `SocketClosed` once it is
closed. In Python and TypeScript the socket is an async iterator of frames, which ends when the socket
is closed, and `send` is awaited. A handler that returns closes its socket; one that lets
`SocketClosed` escape has ended the same way, as a handler does when its client goes.

**The ACL is decided when the socket is opened.** The opening request is an ordinary request to the
route: an ACL that refuses it answers exactly what it answers any request — `401` with the challenge,
`403`, or `503` — no socket is opened and no handler runs. What it established — the caller, the
principal — is what the handler reads for the socket's whole life, through the same `request`,
`caller` and `principal` every handler uses, together with the opening request's path parameters,
query and headers. The socket is not checked again: a token that expires while the socket is open
leaves it open, so a service that must end a session at its token's expiry reads the principal's
expiry and closes the socket itself. A plain `GET` to a socket route that does not ask to open a socket
is answered `426`.

**A browser sends its token as a subprotocol.** A browser cannot set `Authorization` on a socket, so
an authenticated socket route also reads the token from a subprotocol the client offers,
`ankka.bearer.<token>`, when the request has no `Authorization` header. Offer `ankka.socket` beside it;
the platform selects that one and never echoes the token back:

```js
const socket = new WebSocket(`wss://${hostname}/carts/c1/watch`, ["ankka.socket", `ankka.bearer.${token}`])
```

Any other client sends the header as it would on any request.

**A socket is closed, never cut off.** Its client is told why with a close code:

| Close reason | Code | When |
|---|---|---|
| finished | 1000 | the handler returned |
| going away | 1001 | the instance is stopping; open the socket again and another instance answers |
| not text | 1003 | the client sent a frame that is not text |
| unread | 1008 | more frames were waiting for the handler than a socket holds |
| too large | 1009 | the client sent a frame larger than a frame may be |
| failed | 1011 | the handler threw, or the process behind it stopped |

A client's own close is answered with the client's code. The platform keeps a quiet socket open by
pinging it, which neither side sees as a frame, so a socket nobody writes to for hours stays open
through the gateway. The limits — how large a frame may be, how many frames may wait unread, how long
a socket may be quiet before it is pinged — are in the [configuration reference](../reference/configuration.md).

**The platform carries the socket and nothing else.** It keeps no frame and no record of who holds a
socket open. Presence, fan-out to many sockets and anything a reconnecting client should catch up on
are the service's own: a key value entity, a consumer, a view. A socket's whole life is one span in
the service's traces, recorded when it closes, and the calls its handler makes are under it.

A test opens a socket with `TestSocket` from the test kit, or in Python and TypeScript runs the
handler against a scripted socket with the endpoint test kit's `socket`.

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
  org.apache.pekko.stream.scaladsl.Source((1 to count).map(n => s"$term-$n").toVector)
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

To know which *user* a request is for, verify their token with `ankka-auth-oidc`, below. To know which
*workload* sent it, use `allowCallers`.

### Verify your users' tokens

A service whose users sign in with an identity provider lists the issuers it accepts, and an endpoint
admits a request only when it carries a token one of them signed. Add the module:

```scala
"com.thinkmorestupidless" %% "ankka-auth-oidc" % ankkaVersion
```

and declare the access rule with `Oidc.authenticate()`, which reads the issuers from the service's
environment. The handler reads `principal`: the token's subject, name, email, roles, every other claim
by name, and the issuer that verified it.

```scala
/** An endpoint whose users sign in with an identity provider the service lists. */
final class AccountEndpoint(val acl: Acl = Oidc.authenticate()) extends HttpEndpoint("/account"):

  get("/me")(() =>
    s"${principal.subject} from ${principal.issuer.getOrElse("?")} " +
      s"roles=${principal.roles.toList.sorted.mkString(",")} " +
      s"tier=${principal.claims.getOrElse("tier", "")}"
  )
```

A request with no token, or a token that is expired, for another audience, from an issuer the service
does not list, or signed with a shared secret, is answered `401` with a challenge. A request that
arrives when an issuer's keys cannot be fetched, and none are held, is answered `503`. The service
fetches nothing when it starts. The issuers are a named set of `ANKKA_AUTH_` variables, described in
[Identity and machine accounts](../platform/identity.md#a-services-own-users). A service that declares
the rule and lists no issuer does not start, and says which variable to set.

### Name who may call

`Acl.allowCallers` admits a request only from the workloads it names. In a cluster every connection to a
service is mutual TLS, and the caller is read from the client certificate the platform issued the calling
workload, so it cannot be forged by anything the request says about itself:

| Caller | Admitted by |
|---|---|
| a request from outside the cluster, through the gateway, or under a mount of a web-hosted service of this project | `Callers.internet` |
| the `orders` service in this service's project | `Callers.service("orders")` |
| the `invoices` service in the `billing` project | `Callers.service("billing", "invoices")` |
| any service in this service's project | `Callers.anyInProject` |
| another instance of this service | `Callers.self` |

```scala
// Only the internet and the orders service in this project; any other caller is refused 403.
withAcl(Acl.allowCallers(Callers.internet, Callers.service("orders"))) {
  get("/only-orders")(() => s"admitted: ${describe(caller)}")
}

// Another instance of this very service, and nothing else.
withAcl(Acl.allowCallers(Callers.self)) {
  get("/only-self")(() => "admitted: myself")
}
```

A handler reads the caller as `caller`, which is always present:

```scala
get("/whoami")(() => whoIsCalling)
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

  get("/{cartId}")((cartId: String) => s"cart:$cartId")

  withAcl(
    Acl.Authenticate(context =>
      context.header("X-Support-Id") match
        case Some(id) => AuthDecision.Allow(Principal(id))
        case None     => AuthDecision.Unauthenticated("""realm="support"""")
    )
  ) {
    delete("/{cartId}")((cartId: String) => s"purged:$cartId by ${principal.subject}")
  }
```

Scopes nest, and the innermost one wins. A request whose path matches no route of the endpoint is judged
by the endpoint's own ACL, so an endpoint that refuses answers the same way for a path that exists and one
that does not, rather than disclosing which is which.

## Call another service

`clients.services` calls another service's endpoints as this service, by its name, so the service called
reads this one as the caller and its access rule can admit it by name. The same client is given to a
workflow's step, a consumer, a timed action and an agent, in Scala, Python and TypeScript.
[Calling other services](calling-services.md) shows the call in each language, what it answers, the four
errors it can end in, how long it waits, and how to test it.

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
    .getOrElse(throw HttpProblem.notFound(s"no row for cart '$cartId'"))
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
  if (found === null) throw new HttpProblem(404, `no row for cart '${req.params.cartId}'`)
  return found
}),
```

A `CommandError` from a component call propagates as its code's status, as in Scala. Routes are matched
by the same rules, so a literal segment outranks a parameter.

The Python ACL is a required class attribute: an endpoint that declares no `acl` raises `RegistrationError`
when the class is defined, naming it. `Acl.ALLOW_ALL` admits any caller, `Acl.DENY_ALL` refuses everything,
and `Acl.AUTHENTICATED` admits a request carrying a token from one of the issuers the service lists. The
runtime verifies the token before the process is asked anything, exactly as `Oidc.authenticate()` does in
Scala, and hands the handler `self.request.principal` with the token's subject, roles, every other claim
under `claims`, and the name of the issuer that verified it. TypeScript declares `Acl.authenticated` and
Rust `Acl::Authenticated`, with the same principal. The issuers are the `ANKKA_AUTH_` named set described in
[Identity and machine accounts](../platform/identity.md#a-services-own-users); a service that declares the
rule and lists no issuer does not start, and its report names the route and the variable. A route decorator
takes an `acl` of its own, which replaces the endpoint's for that route exactly as `withAcl` does in Scala:

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
      return c.kind === "service" ? `service:${c.project}/${c.name}` : c.kind
    }),
    onlySelf: get("/self", s.string, () => "self", { acl: Acl.allowCallers(Callers.self) }),
  }
}
```

In Python `Callers.self_` carries a trailing underscore so it does not shadow `self`. A Python or TypeScript
service calls another as itself through `services`, as
[Calling other services](calling-services.md) describes.

A `str` return value is answered as `text/plain`, and a `str` body is read as raw text, not as a JSON
string — the same encoding the Scala SDK uses.
