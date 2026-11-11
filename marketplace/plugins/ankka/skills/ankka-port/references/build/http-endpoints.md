# HTTP endpoints

> Expose a service over HTTP — routes, typed path parameters and bodies, responses, errors, query parameters and headers, access control and server-sent events — in Scala, Java, Kotlin, Python, TypeScript, Rust or Go.

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

**Java**

```java
package shoppingcart.javasdk;

import com.thinkmorestupidless.ankka.javasdk.Done;
import com.thinkmorestupidless.ankka.javasdk.annotations.Body;
import com.thinkmorestupidless.ankka.javasdk.annotations.Delete;
import com.thinkmorestupidless.ankka.javasdk.annotations.Get;
import com.thinkmorestupidless.ankka.javasdk.annotations.Post;
import com.thinkmorestupidless.ankka.javasdk.client.EntityRef;
import com.thinkmorestupidless.ankka.javasdk.http.Acl;
import com.thinkmorestupidless.ankka.javasdk.http.HttpEndpoint;

/** The cart over HTTP, route for route as the Scala cart serves it. */
public class CartEndpoint extends HttpEndpoint {

  @Override
  protected Acl acl() {
    return Acl.allowAll();
  }

  @Get("/carts/{cartId}")
  public Cart get(String cartId) {
    return cart(cartId).call(ShoppingCart::getCart).invoke();
  }

  @Get("/carts/{cartId}/total")
  public Integer total(String cartId) {
    return cart(cartId).call(ShoppingCart::totalQuantity).invoke();
  }

  @Post("/carts/{cartId}/items")
  public Done addItem(String cartId, @Body LineItem item) {
    return cart(cartId).call(ShoppingCart::addItem).invoke(item);
  }

  @Delete("/carts/{cartId}/items/{productId}")
  public Done removeItem(String cartId, String productId) {
    return cart(cartId).call(ShoppingCart::removeItem).invoke(productId);
  }

  @Post("/carts/{cartId}/checkout")
  public Cart checkout(String cartId) {
    return cart(cartId).call(ShoppingCart::checkout).invoke();
  }

  @Delete("/carts/{cartId}")
  public Done discard(String cartId) {
    return cart(cartId).call(ShoppingCart::discard).invoke();
  }

  private EntityRef cart(String cartId) {
    return componentClient().forEventSourcedEntity(cartId);
  }
}
```

**Kotlin**

```kotlin
/** The cart over HTTP, route for route as the Scala cart serves it, declared in code. */
class CartEndpoint : HttpEndpoint(), RouteRegistrar {
    override fun acl(): Acl = Acl.allowAll()

    override fun routes() = routes {
        get("/carts/{cartId}") { cart(path("cartId")).call(ShoppingCart::getCart).invokeSuspending() }
        get("/carts/{cartId}/total") { cart(path("cartId")).call(ShoppingCart::totalQuantity).invokeSuspending() }
        post<LineItem>("/carts/{cartId}/items") { item ->
            cart(path("cartId")).call(ShoppingCart::addItem).invokeSuspending(item)
        }
        delete("/carts/{cartId}/items/{productId}") {
            cart(path("cartId")).call(ShoppingCart::removeItem).invokeSuspending(path("productId"))
        }
        post("/carts/{cartId}/checkout") { cart(path("cartId")).call(ShoppingCart::checkout).invokeSuspending() }
        delete("/carts/{cartId}") { cart(path("cartId")).call(ShoppingCart::discard).invokeSuspending() }
    }

    private fun cart(cartId: String): EntityRef = componentClient().forEventSourcedEntity(cartId)
}
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

**Rust**

```rust
impl Endpoint for CartApi {
    const ENDPOINT_ID: &'static str = "ShoppingCartEndpoint";
    const PREFIX: &'static str = "/carts";

    fn acl() -> Acl {
        Acl::AllowAll
    }

    fn routes() -> Routes<CartApi> {
        Routes::new()
            .get("/{cartId}", CartApi::get_cart)
            .get("/{cartId}/total", CartApi::total)
            .post("/{cartId}/items", CartApi::add_item)
            .delete("/{cartId}/items/{productId}", CartApi::remove_item)
            .post("/{cartId}/checkout", CartApi::checkout)
            .delete("/{cartId}", CartApi::discard)
            // A literal beside a parameter: the router must prefer it over `/{cartId}`.
            .get("/awkward", |_: &Request| Ok("literal".to_string()))
            .get("/{cartId}/rows", CartApi::row)
            .get("/rows", CartApi::rows)
            .post("/{cartId}/checkouts", CartApi::start_checkout)
            .get("/{cartId}/checkouts", CartApi::checkout_status)
            .post("/ask/{session}", CartApi::ask)
            .get("/{cartId}/checkout-log", CartApi::checkout_log)
            .post("/{cartId}/items/explained", CartApi::add_item_or_explain)
    }
}
```

**Go**

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
A handler takes every path parameter its template names, however many, in template order, followed by
the body for the `…Body` forms and by the `Socket` for `socket`. **Annotate every handler parameter
with its type** — `{ (cartId: String) => … }`, not `{ cartId => … }` — because the annotation is how
the parameter is parsed, and a handler whose parameter has no type does not compile. A handler that
takes a different number of path parameters than its template names fails the service's start.

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

**Java**

```java
private static final Serializer<CartState> CART = Serializer.of(CartState.class);

// A socket: the client sends "refresh" and is sent the cart, for as long as it keeps the socket
// open. The handler is ordinary blocking code on a virtual thread; receive() answers empty once
// the socket is closed, which ends the loop and the handler.
@Socket("/{cartId}/watch")
public void watch(String cartId, WebSocket socket) {
  Optional<String> frame;
  while ((frame = socket.receive()).isPresent()) {
    if (frame.get().equals("refresh")) {
      var cart =
          componentClient().forEventSourcedEntity(cartId).call(ShoppingCart::getCart).invoke();
      socket.send(new String(CART.toBytes(cart), StandardCharsets.UTF_8));
    } else {
      socket.send("{\"error\":\"unknown request '" + frame.get() + "'; send refresh\"}");
    }
  }
}
```

**Kotlin**

```kotlin
// A socket: the client sends "refresh" and is sent the cart, for as long as it keeps the socket
// open. The handler is ordinary blocking code on a virtual thread; `receiveOrNull()` answers null
// once the socket is closed, which ends the loop and the handler.
@Socket("/carts/{cartId}/watch")
fun watch(cartId: String, socket: WebSocket) {
    generateSequence { socket.receiveOrNull() }.forEach { message ->
        when (message) {
            "refresh" -> socket.send(cartJson(cartId))
            else -> socket.send("""{"error":"unknown request '$message'; send refresh"}""")
        }
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

**Go**

The Go reference's socket echoes each frame, and answers `context` with the room and the opening request's tag:

```go
// Sockets (protocol 1.9). Echoes each frame; "context" is answered with the room and the
// opening request's tag, read after any number of frames.
ankka.SocketRoute("/socket/{room}", func(_ context.Context, s *ankka.Socket) error {
	room := s.Request().Param("room")
	for {
		text, err := s.Receive()
		if err == io.EOF {
			logSocket("closed:" + room)
			return nil
		}
		if err != nil {
			return err
		}
		if text == "context" {
			text = room + " " + s.Request().Query.Get("tag")
		}
		if err := s.Send(text); err != nil {
			return err
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
| `Streamed(contentType, length, body)` | `200`, the content type given, the body written as it is read |

### Streamed bodies

Every route above reads its request's body whole before the handler runs, and every response is written
from a value held whole. For an upload or a download larger than an instance should hold, a route streams
instead:

- **`postStream` and `putStream`** declare a route whose handler is given a `StreamedBody`: the request's
  content type, its declared length when the client sent one, and an `InputStream` read as the body
  arrives. The handler reads it on its own thread, and the server closes it when the handler returns.
- **`Streamed(contentType, length, body)`** as a handler's return value writes `body` to the client as it
  is read, and closes it when the response is written or the client goes away. With a length the response
  says it; without, it is sent in chunks.

A stream route reads at most `ankka.http.max-stream-bytes`, five gigabytes unless
`ANKKA_HTTP_MAX_STREAM_BYTES` says otherwise. A request declaring a larger body is answered `413` and the
handler never runs. Every other route keeps Pekko HTTP's own bound on a body read whole,
`pekko.http.server.parsing.max-content-length`.

The streamed upload in [Objects a service keeps](object-storage.md) passes the request's body straight to a
bucket, and the download writes the object as it is read from it.

A Python or TypeScript service's routes are strict: the platform reads a request's body whole before
handing it to the process, and a response's whole before writing it. A large upload or download in those
languages goes through a signed URL, straight between the browser and the bucket.

### Pages, redirects and cookies

An endpoint that serves a website rather than an API returns HTML, sends the browser elsewhere, and
keeps a session. `Respond` wraps any body the endpoint can already answer with a status and headers:

**Scala**

```scala
get("/account")(() => Respond(Html(page), headers = Vector("Set-Cookie" -> cookie)))
post("/logout")(() => Respond.redirect("/")) // 303 See Other, Location: /
get("/style.css")(() => Bytes("text/css", stylesheet))
```

**Java**

```java
@Get("/site/account")
public Response account() {
  return Response.html(PAGE).header("Set-Cookie", COOKIE);
}

@Post("/site/logout")
public Response logout() {
  return Response.redirect("/"); // 303 See Other, Location: /
}

@Get("/site/style.css")
public Response stylesheet() {
  return Response.bytes(STYLESHEET, "text/css");
}
```

**Kotlin**

```kotlin
get("/account") { Response.html(page).header("Set-Cookie", cookie) }
post("/logout") { Response.redirect("/") } // 303 See Other, Location: /
get("/style.css") { Response.bytes(stylesheet, "text/css") }
```

**Rust**

```rust
/// Returns HTML, sends the browser elsewhere, and keeps a session.
pub struct PagesEndpoint;

impl PagesEndpoint {
    fn account(_: &Request) -> Result<Response, HttpProblem> {
        let cookie = "session=abc123; Path=/; HttpOnly";
        Ok(Response::html(PAGE).header("Set-Cookie", cookie))
    }

    /// `303 See Other`, the status that is safe after a form post; `.status(n)` changes it.
    fn logout(_: &Request, (): ()) -> Result<Response, HttpProblem> {
        Ok(Response::redirect("/"))
    }

    /// `Content-Type` is never a header here: a bytes response names its own.
    fn stylesheet(_: &Request) -> Result<Response, HttpProblem> {
        Ok(Response::bytes("text/css", STYLESHEET.to_vec()))
    }
}

impl Endpoint for PagesEndpoint {
    const ENDPOINT_ID: &'static str = "PagesEndpoint";
    const PREFIX: &'static str = "/site";

    fn acl() -> Acl {
        Acl::AllowAll
    }

    fn routes() -> Routes<PagesEndpoint> {
        Routes::new()
            .get("/account", PagesEndpoint::account)
            .post("/logout", PagesEndpoint::logout)
            .get("/style.css", PagesEndpoint::stylesheet)
    }
}
```

**Go**

```go
// RawResponse is any body with the status, content type and headers the handler chooses.
ankka.Get("/account", func(context.Context, *ankka.Request) (ankka.RawResponse, error) {
	return ankka.RawResponse{Status: 200, ContentType: "text/html; charset=utf-8",
		Headers: http.Header{"Set-Cookie": {"session=s-1; HttpOnly"}}, Body: []byte(accountPage)}, nil
}),
// 303 See Other, the status that is safe after a form post.
ankka.Post("/logout", func(context.Context, *ankka.Request, ankka.Unit) (ankka.RawResponse, error) {
	return ankka.RawResponse{Status: 303, Headers: http.Header{"Location": {"/"}}}, nil
}),
ankka.Get("/style.css", func(context.Context, *ankka.Request) (ankka.RawResponse, error) {
	return ankka.RawResponse{Status: 200, ContentType: "text/css", Body: []byte(stylesheet)}, nil
}),
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

**Scala**

```scala
/** Headers. */
get("/trace") { () =>
  request.header("X-Trace-Id").getOrElse("none")
}
```

**Java**

```java
/** Headers. */
@Get("/search/trace")
public String trace() {
  return request().header("X-Trace-Id").orElse("none");
}
```

**Kotlin**

```kotlin
/** Headers. */
@Get("/search/trace")
fun trace(): String = request().headerOrNull("X-Trace-Id") ?: "none"
```

**Python**

```python
@get("/trace")
def trace(self) -> str:
    """Headers."""
    return self.request.header("X-Trace-Id") or "none"
```

**TypeScript**

```ts
/** Headers. */
trace: get("/trace", s.string, (_ep: SearchEndpoint, req) => req.headers.get("X-Trace-Id") ?? "none"),
```

**Rust**

```rust
/// Headers.
fn trace(request: &Request) -> Result<String, HttpProblem> {
    Ok(request.header("X-Trace-Id").unwrap_or("none").to_string())
}
```

**Go**

```go
// Headers, matched by name case-insensitively.
ankka.Get("/trace", func(_ context.Context, req *ankka.Request) (string, error) {
	if id := req.Header("X-Trace-Id"); id != "" {
		return id, nil
	}
	return "none", nil
}),
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

**Scala**

```scala
sse("/stream") { () =>
  val term  = query.required[String]("q")
  val count = query.optional[Int]("count").getOrElse(2)
  org.apache.pekko.stream.scaladsl.Source((1 to count).map(n => s"$term-$n").toVector)
}
```

**Java**

```java
@Sse("/search/stream")
public Stream<SseEvent> stream() {
  // Read while the handler runs: the stream's elements are pulled later, on another thread.
  var term = request().requiredQuery("q");
  int count = request().query("count").map(Integer::parseInt).orElse(2);
  return IntStream.rangeClosed(1, count).mapToObj(n -> SseEvent.text(term + "-" + n));
}
```

**Kotlin**

```kotlin
/** A streaming route reads its parameters while the stream is built, on the request's thread. */
@Sse("/search/stream")
fun stream(): Stream<SseEvent> {
    val term = request().requiredQuery("q")
    val count = request().queryOrNull("count")?.toInt() ?: 2
    return (1..count).map { SseEvent.text("$term-$it") }.stream()
}
```

**Python**

```python
@sse("/stream")
async def stream(self) -> AsyncIterator[str]:
    term = self.request.query_param("q") or ""
    count = int(self.request.query_param("count") or 2)
    for n in range(1, count + 1):
        yield f"{term}-{n}"
```

**TypeScript**

```ts
stream: sse("/stream", async function* (_ep: SearchEndpoint, req) {
  const term = req.query.get("q") ?? ""
  const count = Number(req.query.get("count") ?? 2)
  for (let n = 1; n <= count; n++) yield `${term}-${n}`
}),
```

**Go**

```go
// The request is the route's argument, so a stream reads its parameters as it is built, before
// any element is pulled.
ankka.SSE("/stream", func(_ context.Context, req *ankka.Request) iter.Seq2[string, error] {
	term, count := req.Query.Get("q"), 2
	if n, err := strconv.Atoi(req.Query.Get("count")); err == nil {
		count = n
	}
	return func(yield func(string, error) bool) {
		for n := 1; n <= count; n++ {
			if !yield(fmt.Sprintf("%s-%d", term, n), nil) {
				return
			}
		}
	}
}),
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

**Scala**

```scala
/** An endpoint whose ACL inspects the request — the same context the handler sees. */
final class GatedEndpoint extends HttpEndpoint("/gated"):

  val acl: Acl = Acl.AllowIf(context =>
    context.header("X-Api-Key").contains("let-me-in") || context.query.flag("public")
  )

  get("/")(() => "allowed")
```

**Java**

```java
/** An endpoint whose ACL inspects the request: the same request the handler sees. */
public class GatedRoutes extends HttpEndpoint {

  @Override
  protected Acl acl() {
    return Acl.allowIf(
        request ->
            request.header("X-Api-Key").filter("let-me-in"::equals).isPresent()
                || request.flag("public"));
  }

  @Get("/gated")
  public String gated() {
    return "allowed";
  }
}
```

**Kotlin**

```kotlin
/** An endpoint whose ACL inspects the request: the same request the handler sees. */
class GatedEndpoint : HttpEndpoint() {
    override fun acl(): Acl = Acl.allowIf { it.header("X-Api-Key").orNull() == "let-me-in" || it.flag("public") }

    @Get("/gated")
    fun root(): String = "allowed"
}
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

**Scala**

```scala
"com.thinkmorestupidless" %% "ankka-auth-oidc" % ankkaVersion
```

and declare the access rule with `Oidc.authenticate()`, which reads the issuers from the service's
environment. The handler reads `principal`: the token's subject, name, email, roles, every other claim
by name, and the issuer that verified it.

**Scala**

```scala
/** An endpoint whose users sign in with an identity provider the service lists. */
final class AccountEndpoint(val acl: Acl = Oidc.authenticate()) extends HttpEndpoint("/account"):

  get("/me")(() =>
    s"${principal.subject} from ${principal.issuer.getOrElse("?")} " +
      s"roles=${principal.roles.toList.sorted.mkString(",")} " +
      s"tier=${principal.claims.getOrElse("tier", "")}"
  )
```

**Java**

```java
/** An endpoint whose users sign in with an identity provider the service lists. */
public class AccountRoutes extends HttpEndpoint {

  /** The issuers the service's environment lists; a test holds its own issuer's rule here. */
  static Supplier<Acl> rule = Acl::oidc;

  @Override
  protected Acl acl() {
    return rule.get();
  }

  @Get("/account/me")
  public String me() {
    var principal = principal().orElseThrow();
    return principal.subject()
        + " from "
        + principal.issuer().orElse("?")
        + " roles="
        + String.join(",", new TreeSet<>(principal.roles()))
        + " tier="
        + principal.claims().getOrDefault("tier", "");
  }
}
```

**Kotlin**

```kotlin
/** An endpoint whose users sign in with an identity provider the service lists. */
class AccountEndpoint : HttpEndpoint() {
    override fun acl(): Acl = Acl.oidc()

    @Get("/account/me")
    fun me(): String {
        val principal = principal().orElseThrow()
        return "${principal.subject()} from ${principal.issuer().orNull() ?: "?"} " +
            "roles=${principal.roles().sorted().joinToString(",")} " +
            "tier=${principal.claims()["tier"] ?: ""}"
    }
}
```

**Python**

```python
class AccountEndpoint(Endpoint):
    """An endpoint whose users sign in with an identity provider the service lists."""

    prefix = "/account"
    acl = Acl.AUTHENTICATED

    @get("/me")
    def me(self) -> str:
        principal = self.request.principal
        assert principal is not None  # the ACL established one before the handler ran
        return (
            f"{principal.subject} from {principal.issuer or '?'} "
            f"roles={','.join(sorted(principal.roles))} tier={principal.claims.get('tier', '')}"
        )
```

**TypeScript**

```ts
/** An endpoint whose users sign in with an identity provider the service lists. */
export class AccountEndpoint extends Endpoint {
  static readonly prefix = "/account"
  static readonly acl = Acl.authenticated

  static readonly routes = {
    me: get("/me", s.string, (_ep: AccountEndpoint, req) => {
      const p = req.principal!
      return `${p.subject} from ${p.issuer ?? "?"} roles=${[...p.roles].sort().join(",")} tier=${p.claims.tier ?? ""}`
    }),
  }
}
```

**Rust**

```rust
pub struct PrivateEndpoint;

impl Endpoint for PrivateEndpoint {
    const ENDPOINT_ID: &'static str = "PrivateEndpoint";
    const PREFIX: &'static str = "/private";

    fn acl() -> Acl {
        Acl::Authenticated
    }

    fn routes() -> Routes<PrivateEndpoint> {
        Routes::new()
            .get("/", |_: &Request| Ok("private".to_string()))
            .get("/me", |request: &Request| {
                let p = request
                    .principal()
                    .expect("an authenticated route is handed its principal");
                let mut roles = p.roles.clone();
                roles.sort();
                Ok(ankka::serde_json::json!({
                    "subject": p.subject,
                    "roles": roles,
                    "tier": p.claims.get("tier"),
                    "issuer": p.issuer,
                })
                .to_string())
            })
    }
}
```

**Go**

```go
// AccountEndpoint's users sign in with an identity provider the service lists: the sidecar verifies
// the token before the process is asked anything, and the handler reads the principal.
var AccountEndpoint = ankka.NewEndpoint("/account", ankka.Authenticate(ankka.Issuer("corp")),
	ankka.Get("/me", func(_ context.Context, req *ankka.Request) (string, error) {
		p := req.Principal
		roles := append([]string{}, p.Roles...)
		sort.Strings(roles)
		return fmt.Sprintf("%s from %s roles=%s tier=%s",
			p.Subject, p.Issuer, strings.Join(roles, ","), p.Claims["tier"]), nil
	}),
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
| a service of another project, or a registered machine, that this service's project has granted the route | `Callers.granted` |
| a registered machine, as a request from the internet that proved which machine it is | `Callers.internet` |

**Scala**

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

**Java**

```java
/**
 * Only the internet and the orders service in this project; any other caller is refused 403. An
 * endpoint has one ACL for all its routes, so routes for another audience, such as another
 * instance of this very service and nothing else ({@code Callers.self()}), are an endpoint of
 * their own.
 */
public class CallerRoutes extends HttpEndpoint {

  @Override
  protected Acl acl() {
    return Acl.allowCallers(Callers.internet(), Callers.service("orders"));
  }

  @Get("/callers/only-orders")
  public String onlyOrders() {
    return "admitted: " + describe(caller());
  }
```

**Kotlin**

```kotlin
// An ACL belongs to an endpoint class, so a route admitting other callers is a class of its own.

/** Only the internet and the orders service in this project; any other caller is refused 403. */
class OnlyOrdersEndpoint : HttpEndpoint(), RouteRegistrar {
    override fun acl(): Acl = Acl.allowCallers(Callers.internet(), Callers.service("orders"))

    override fun routes() = routes {
        get("/callers/only-orders") { "admitted: ${describe(request.caller())}" }
    }
}

/** Another instance of this very service, and nothing else. */
class OnlySelfEndpoint : HttpEndpoint(), RouteRegistrar {
    override fun acl(): Acl = Acl.allowCallers(Callers.self())

    override fun routes() = routes { get("/callers/only-self") { "admitted: myself" } }
}
```

**Python**

```python
prefix = "/callers"
# Only the internet and the orders service in this project; any other caller is refused 403.
acl = Acl.allow_callers(Callers.internet, Callers.service("orders"))

# Another instance of this very service, and nothing else: a route's own ACL replaces the
# endpoint's for that route.
@get("/self", acl=Acl.allow_callers(Callers.self_))
def only_self(self) -> str:
    return "self"
```

**TypeScript**

```ts
// Only the internet and the orders service in this project; any other caller is refused 403.
onlyOrders: get("/only-orders", s.string, (_ep: CallersEndpoint, req) => `admitted: ${describe(req.caller)}`, {
  acl: Acl.allowCallers(Callers.internet, Callers.service("orders")),
}),
// Another instance of this very service, and nothing else.
onlySelf: get("/only-self", s.string, () => "admitted: myself", { acl: Acl.allowCallers(Callers.self) }),
```

**Rust**

```rust
/// Caller-naming ACLs: the suite names callers through the local caller header.
pub struct CallersEndpoint;

impl CallersEndpoint {
    fn whoami(request: &Request) -> Result<String, HttpProblem> {
        Ok(match request.caller() {
            Caller::Service { project, name } => format!("service:{project}/{name}"),
            Caller::Machine { organization, name } => format!("machine:{organization}/{name}"),
            Caller::Gateway => "gateway".to_string(),
            Caller::Local => "local".to_string(),
        })
    }
}

impl Endpoint for CallersEndpoint {
    const ENDPOINT_ID: &'static str = "CallersEndpoint";
    const PREFIX: &'static str = "/callers";

    fn acl() -> Acl {
        Acl::Callers(vec![
            CallerMatcher::Internet,
            CallerMatcher::service("orders"),
        ])
    }

    fn routes() -> Routes<CallersEndpoint> {
        Routes::new()
            .get("/whoami", CallersEndpoint::whoami)
            .get("/self", |_: &Request| Ok("self".to_string()))
            .with_acl(Acl::Callers(vec![CallerMatcher::SelfService]))
            .get("/granted", |_: &Request| Ok("granted".to_string()))
            .with_acl(Acl::Callers(vec![CallerMatcher::Granted]))
    }
}
```

**Go**

```go
// CallersEndpoint admits only the internet and the orders service in this project; any other
// caller is refused 403. One route admits another instance of this very service, and nothing else.
var CallersEndpoint = ankka.NewEndpoint("/callers", ankka.AllowIf(ankka.Internet(), ankka.ServiceNamed("orders")),
	ankka.Get("/only-orders", func(_ context.Context, req *ankka.Request) (string, error) {
		return "admitted: " + describeCaller(req.Caller), nil
	}),
	ankka.Get("/only-self", func(context.Context, *ankka.Request) (string, error) {
		return "admitted: myself", nil
	}).ACL(ankka.AllowIf(ankka.Self())),
```

A handler reads the caller as `caller`, which is always present:

**Scala**

```scala
get("/whoami")(() => whoIsCalling)
```

**Java**

```java
/** The caller is always present: the platform established it before any ACL ran. */
@Get("/callers/whoami")
public String whoami() {
  return describe(caller());
}

static String describe(Caller caller) {
  return switch (caller) {
    case Caller.Gateway g -> "the internet";
    case Caller.Service s -> "service " + s.project() + "/" + s.name();
    case Caller.Machine m -> "machine " + m.organization() + "/" + m.name();
    case Caller.Local l -> "this machine";
  };
}
```

**Kotlin**

```kotlin
get("/callers/whoami") { whoIsCalling(request.caller()) }
```

**Python**

```python
@get("/whoami")
def whoami(self) -> str:
    c = self.request.caller
    if isinstance(c, ServiceCaller):
        return f"service:{c.project}/{c.name}"
    if isinstance(c, MachineCaller):
        return f"machine:{c.organization}/{c.name}"
    return "gateway" if isinstance(c, Gateway) else "local"
```

**TypeScript**

```ts
whoami: get("/whoami", s.string, (_ep: CallersEndpoint, req) => whoIsCalling(req.caller)),
```

**Rust**

```rust
/// Caller-naming ACLs: the suite names callers through the local caller header.
pub struct CallersEndpoint;

impl CallersEndpoint {
    fn whoami(request: &Request) -> Result<String, HttpProblem> {
        Ok(match request.caller() {
            Caller::Service { project, name } => format!("service:{project}/{name}"),
            Caller::Machine { organization, name } => format!("machine:{organization}/{name}"),
            Caller::Gateway => "gateway".to_string(),
            Caller::Local => "local".to_string(),
        })
    }
}

impl Endpoint for CallersEndpoint {
    const ENDPOINT_ID: &'static str = "CallersEndpoint";
    const PREFIX: &'static str = "/callers";

    fn acl() -> Acl {
        Acl::Callers(vec![
            CallerMatcher::Internet,
            CallerMatcher::service("orders"),
        ])
    }

    fn routes() -> Routes<CallersEndpoint> {
        Routes::new()
            .get("/whoami", CallersEndpoint::whoami)
            .get("/self", |_: &Request| Ok("self".to_string()))
            .with_acl(Acl::Callers(vec![CallerMatcher::SelfService]))
            .get("/granted", |_: &Request| Ok("granted".to_string()))
            .with_acl(Acl::Callers(vec![CallerMatcher::Granted]))
    }
}
```

**Go**

```go
// The caller is always present, set before any ACL runs, and independent of the principal.
ankka.Get("/whoami", func(_ context.Context, req *ankka.Request) (string, error) {
	return describeCaller(req.Caller), nil
}),
```

`caller` is set before any ACL runs, so an `AllowIf` predicate can read it too, and it is independent of
`principal`: a request from the `orders` service on behalf of a signed-in user has both.

The refusal body names no caller, so an unauthorised workload learns nothing about whose certificate it
would need. A client certificate the installation issued that names no service is refused `403` before
routing.

### Let another project call a route

`Callers.granted` keeps two decisions apart. Whether anyone outside the project may ever call a route is
the route's ACL, in reviewed code: a route that does not name `Callers.granted` is never opened by a
grant. Who may call it today is a grant, which an owner of the project's organization makes and revokes
as data, with no change to either service. A revocation refuses the caller within two minutes, and closes
the sockets and event streams the grant admitted. A grant names one route by its method and its path
template, or one gRPC method, so a caller granted one route is refused every other.

**Scala**

```scala
/**
 * A player's wallet, which another project may be granted. The endpoint's ACL admits granted
 * callers: whether anyone outside the project may ever call these routes is decided here, in
 * reviewed code; who may, today, is a grant the project's owners make and revoke as data.
 */
final class WalletEndpoint(client: ComponentClient) extends HttpEndpoint("/v1/wallets"):

  val acl: Acl = Acl.allowCallers(Callers.granted)

  /**
   * A deposit, with the caller's idempotency key: a granted call is delivered at least once, and
   * the wallet applies each key once. The answer names the caller as the platform established it.
   */
  postBody("/{player}/{currency}/deposits") {
    (player: String, currency: String, body: DepositRequest) =>
      val key = request
        .header("Idempotency-Key")
        .getOrElse(throw HttpProblem.badRequest("a deposit needs an Idempotency-Key header"))
      val reply =
        wallet(player).call(WalletEntity.deposit).invoke(Deposit(key, currency, body.amount))
      DepositMade(reply.currency, reply.balance, reply.applied, Who(caller))
  }
```

**Java**

```java
/**
 * A player's wallet, which another project may be granted. The endpoint's ACL admits granted
 * callers: whether anyone outside the project may ever call these routes is decided here, in
 * reviewed code; who may, today, is a grant the project's owners make and revoke as data.
 */
public class WalletRoutes extends HttpEndpoint {

  public record DepositRequest(long amount) {}

  public record DepositMade(String key, String currency, long amount, String by) {}

  @Override
  protected Acl acl() {
    return Acl.allowCallers(Callers.granted());
  }

  /**
   * A deposit, with the caller's idempotency key: a granted call is delivered at least once, and
   * the wallet applies each key once. The answer names the caller as the platform established it.
   */
  @Post("/v1/wallets/{player}/{currency}/deposits")
  public DepositMade deposit(String player, String currency, @Body DepositRequest body) {
    var key =
        request()
            .header("Idempotency-Key")
            .orElseThrow(
                () ->
                    new ProblemException(
                        Problem.badRequest("a deposit needs an Idempotency-Key header")));
    return new DepositMade(key, currency, body.amount(), CallerRoutes.describe(caller()));
  }
}
```

**Kotlin**

```kotlin
/**
 * A player's wallet, which another project may be granted. The endpoint's ACL admits granted
 * callers: whether anyone outside the project may ever call these routes is decided here, in
 * reviewed code; who may, today, is a grant the project's owners make and revoke as data.
 */
class WalletEndpoint : HttpEndpoint(), RouteRegistrar {
    override fun acl(): Acl = Acl.allowCallers(Callers.granted())

    override fun routes() = routes {
        /*
         * A deposit, with the caller's idempotency key: a granted call is delivered at least once,
         * and the wallet applies each key once. The answer names the caller as the platform
         * established it.
         */
        post<DepositRequest>("/v1/wallets/{player}/{currency}/deposits") { body ->
            val key =
                request.headerOrNull("Idempotency-Key")
                    ?: throw ProblemException(Problem.badRequest("a deposit needs an Idempotency-Key header"))
            val deposit = Deposit(key, path("currency"), body.amount)
            val reply = wallet(path("player")).call(WalletEntity::deposit).invokeSuspending(deposit)
            DepositMade(reply.currency, reply.balance, reply.applied, describe(request.caller()))
        }
    }

    private fun wallet(player: String): EntityRef = componentClient().forEventSourcedEntity(player)
}
```

**Python**

```python
class WalletEndpoint(Endpoint):
    """A player's wallet, which another project may be granted. The endpoint's ACL admits granted
    callers: whether anyone outside the project may ever call these routes is decided here, in
    reviewed code; who may, today, is a grant the project's owners make and revoke as data."""

    prefix = "/v1/wallets"
    acl = Acl.allow_callers(Callers.granted)

    def __init__(self, client: ComponentClient) -> None:
        self.client = client

    @post("/{player}/{currency}/deposits")
    async def deposit(self, player: str, currency: str, body: DepositRequest) -> DepositMade:
        """A deposit, with the caller's idempotency key: a granted call is delivered at least once,
        and the wallet applies each key once. The answer names the caller as the platform established it."""
        key = self.request.header("Idempotency-Key")
        if key is None:
            raise HttpProblem(400, "a deposit needs an Idempotency-Key header")
        reply = await self._wallet(player).call("deposit").invoke(Deposit(key, currency, body.amount), reply=DepositReply)
        return DepositMade(reply.currency, reply.balance, reply.applied, who(self.request.caller))
```

**TypeScript**

```ts
const DepositRequest = s.record("DepositRequest", { amount: s.long })
const DepositMade = s.record("DepositMade", { currency: s.string, balance: s.long, applied: s.boolean, by: s.string })

/**
 * A player's wallet, which another project may be granted. The endpoint's ACL admits granted
 * callers: whether anyone outside the project may ever call these routes is decided here, in
 * reviewed code; who may, today, is a grant the project's owners make and revoke as data.
 */
export class WalletEndpoint extends Endpoint {
  static readonly prefix = "/v1/wallets"
  static readonly acl = Acl.allowCallers(Callers.granted)

  static readonly routes = {
    /**
     * A deposit, with the caller's idempotency key: a granted call is delivered at least once, and
     * the wallet applies each key once. The answer names the caller as the platform established it.
     */
    deposit: post("/{player}/{currency}/deposits", DepositRequest, DepositMade, async (ep: WalletEndpoint, req, body) => {
      const key = req.headers.get("Idempotency-Key")
      if (key === null) throw new HttpProblem(400, "a deposit needs an Idempotency-Key header")
      const deposit = { key, currency: req.params.currency, amount: body.amount }
      const reply = await ep.client.of(WalletEntity, req.params.player).call(WalletEntity.handlers.deposit).invoke(deposit)
      return { ...reply, by: describe(req.caller) }
    }),
  }
}
```

**Rust**

```rust
/// Caller-naming ACLs: the suite names callers through the local caller header.
pub struct CallersEndpoint;

impl CallersEndpoint {
    fn whoami(request: &Request) -> Result<String, HttpProblem> {
        Ok(match request.caller() {
            Caller::Service { project, name } => format!("service:{project}/{name}"),
            Caller::Machine { organization, name } => format!("machine:{organization}/{name}"),
            Caller::Gateway => "gateway".to_string(),
            Caller::Local => "local".to_string(),
        })
    }
}

impl Endpoint for CallersEndpoint {
    const ENDPOINT_ID: &'static str = "CallersEndpoint";
    const PREFIX: &'static str = "/callers";

    fn acl() -> Acl {
        Acl::Callers(vec![
            CallerMatcher::Internet,
            CallerMatcher::service("orders"),
        ])
    }

    fn routes() -> Routes<CallersEndpoint> {
        Routes::new()
            .get("/whoami", CallersEndpoint::whoami)
            .get("/self", |_: &Request| Ok("self".to_string()))
            .with_acl(Acl::Callers(vec![CallerMatcher::SelfService]))
            .get("/granted", |_: &Request| Ok("granted".to_string()))
            .with_acl(Acl::Callers(vec![CallerMatcher::Granted]))
    }
}
```

**Go**

```go
// WalletEndpoint is a player's wallet, which another project may be granted. The endpoint's ACL
// admits granted callers: whether anyone outside the project may ever call these routes is decided
// here, in reviewed code; who may, today, is a grant the project's owners make and revoke as data.
var WalletEndpoint = ankka.NewEndpoint("/v1/wallets", ankka.AllowIf(ankka.Granted()),
	// A deposit, with the caller's idempotency key: a granted call is delivered at least once, and
	// the wallet applies each key once. The answer names the caller as the platform established it.
	ankka.Post("/{player}/{currency}/deposits",
		func(ctx context.Context, req *ankka.Request, body DepositRequest) (DepositMade, error) {
			key := req.Header("Idempotency-Key")
			if key == "" {
				return DepositMade{}, &ankka.HttpProblem{Status: 400,
					Message: "a deposit needs an Idempotency-Key header"}
			}
			deposit := Deposit{Key: key, Currency: req.Param("currency"), Amount: body.Amount}
			reply, err := ankka.Invoke[Deposited](ctx, "wallet", req.Param("player"), "deposit", deposit)
			if err != nil {
				return DepositMade{}, err
			}
			return DepositMade{Currency: reply.Currency, Balance: reply.Balance, Applied: reply.Applied,
				Caller: describeCaller(req.Caller)}, nil
		}),
)
```

A granted call is delivered at least once, as every call is. The platform does not deduplicate it: a
command another project may send twice must be safe to apply twice, which the wallet above makes it by
the idempotency key each deposit carries. A registered machine arrives as
`Caller.Machine(organization, name)`. [Cross-project access](../platform/cross-project-access.md)
describes grants, machines and their tokens.

**Outside a cluster every caller is the local machine**, `Caller.Local`, because there is no certificate to
read, and every `allowCallers` admits it. The service logs once at startup that callers are not enforced.
A test names a caller through the test kit, which shares a secret with the service in the same JVM:

**Scala**

```scala
test("the orders service is admitted and the payments service is not") {
  assertEquals(get("/callers/only-orders", Some(Caller.Service("local", "orders")))._1, 200)
  assertEquals(get("/callers/only-orders", Some(Caller.Service("local", "payments")))._1, 403)
}
```

**Java**

```java
/** The status of a GET made as {@code caller}: the kit's header names the caller. */
public static int statusAs(AnkkaTestKit kit, String path, Caller caller) throws Exception {
  var asCaller = kit.asCaller(caller);
  var request =
      HttpRequest.newBuilder(kit.http().resolve(path))
          .header(asCaller.getKey(), asCaller.getValue())
          .build();
  return kit.httpClient().send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
}

// statusAs(kit, "/callers/only-orders", new Caller.Service("local", "orders")) is 200;
// statusAs(kit, "/callers/only-orders", new Caller.Service("local", "payments")) is 403.
```

**Kotlin**

```kotlin
val orders = kit.get("/callers/only-orders", caller = Caller.Service("local", "orders"))
assertEquals(200, orders.status, "${orders.body} ${orders.headers}")
assertEquals("admitted: service:local/orders", orders.body)
assertEquals(403, kit.get("/callers/only-orders", caller = Caller.Service("local", "payments")).status)
```

**Python**

```python
def test_the_handler_reads_the_caller_the_platform_established() -> None:
    kit = EndpointTestKit.of(CallersEndpoint)
    assert kit.get("/callers/whoami", caller=ServiceCaller("local", "orders")).text() == "service:local/orders"
    assert kit.get("/callers/whoami", caller=Gateway()).text() == "gateway"
    assert kit.get("/callers/whoami", caller=MachineCaller("acme", "nightly")).text() == "machine:acme/nightly"
    # Outside a cluster every caller is the local machine.
    assert kit.get("/callers/whoami").text() == "local"
```

where the request carries the header `testKit.asCaller(caller)` returns. A service running locally is in
project `local` and is itself `local/local`. In Python, the integration kit does the same through the real
sidecar, which applies the route's caller rules: `kit.http.get(path, headers=kit.caller_headers(caller))`.

### A route with its own ACL

`withAcl` gives the routes declared inside it a different ACL from the endpoint's. It *replaces* the
endpoint's for those routes rather than adding to it, so an open endpoint can hold one protected route,
and a closed one can open a single route, without either being split in two at a second prefix:

**Scala**

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

**Kotlin**

```kotlin
/**
 * One path, two audiences: reading a cart is public, purging one is not.
 *
 * An ACL belongs to an endpoint class, so the routes under one path that admit different callers
 * are two classes, each with its own ACL.
 */
class CartReadEndpoint : HttpEndpoint() {
    override fun acl(): Acl = Acl.allowAll()

    @Get("/mixed/{cartId}")
    fun read(cartId: String): String = "cart:$cartId"
}

class CartPurgeEndpoint : HttpEndpoint() {
    override fun acl(): Acl =
        Acl.authenticate { request ->
            request.headerOrNull("X-Support-Id")?.let { AuthDecision.allow(Principal.of(it)) }
                ?: AuthDecision.unauthenticated("realm=\"support\"")
        }

    @Delete("/mixed/{cartId}")
    fun purge(cartId: String): String = "purged:$cartId by ${principal().orElseThrow().subject()}"
}
```

**Python**

```python
class MixedAclEndpoint(Endpoint):
    """One endpoint, two audiences: reading a cart is public, purging one is not. A route's own
    ``acl`` replaces the endpoint's for that route, so neither audience needs an endpoint of its
    own at a second prefix."""

    prefix = "/mixed"
    acl = Acl.ALLOW_ALL

    @get("/{cartId}")
    def cart(self, cartId: str) -> str:
        return f"cart:{cartId}"

    @delete("/{cartId}", acl=Acl.AUTHENTICATED)
    def purge(self, cartId: str) -> str:
        principal = self.request.principal
        return f"purged:{cartId} by {principal.subject if principal else '?'}"
```

**TypeScript**

```ts
/**
 * One endpoint, two audiences: reading a cart is public, purging one is not.
 *
 * A route's `acl` replaces the endpoint's for that route, so neither audience needs an endpoint of
 * its own at a second prefix.
 */
export class MixedAclEndpoint extends Endpoint {
  static readonly prefix = "/mixed"
  static readonly acl = Acl.allowAll

  static readonly routes = {
    read: get("/{cartId}", s.string, (_ep: MixedAclEndpoint, req) => `cart:${req.params.cartId}`),
    purge: del(
      "/{cartId}",
      s.string,
      (_ep: MixedAclEndpoint, req) => `purged:${req.params.cartId} by ${req.principal!.subject}`,
      { acl: Acl.authenticated },
    ),
  }
}
```

**Rust**

```rust
/// Caller-naming ACLs: the suite names callers through the local caller header.
pub struct CallersEndpoint;

impl CallersEndpoint {
    fn whoami(request: &Request) -> Result<String, HttpProblem> {
        Ok(match request.caller() {
            Caller::Service { project, name } => format!("service:{project}/{name}"),
            Caller::Machine { organization, name } => format!("machine:{organization}/{name}"),
            Caller::Gateway => "gateway".to_string(),
            Caller::Local => "local".to_string(),
        })
    }
}

impl Endpoint for CallersEndpoint {
    const ENDPOINT_ID: &'static str = "CallersEndpoint";
    const PREFIX: &'static str = "/callers";

    fn acl() -> Acl {
        Acl::Callers(vec![
            CallerMatcher::Internet,
            CallerMatcher::service("orders"),
        ])
    }

    fn routes() -> Routes<CallersEndpoint> {
        Routes::new()
            .get("/whoami", CallersEndpoint::whoami)
            .get("/self", |_: &Request| Ok("self".to_string()))
            .with_acl(Acl::Callers(vec![CallerMatcher::SelfService]))
            .get("/granted", |_: &Request| Ok("granted".to_string()))
            .with_acl(Acl::Callers(vec![CallerMatcher::Granted]))
    }
}
```

**Go**

```go
// MixedAclEndpoint is one endpoint, two audiences: reading a cart is public, purging one is not.
// A route's ACL replaces the endpoint's for that route, so neither audience needs an endpoint of
// its own at a second prefix.
var MixedAclEndpoint = ankka.NewEndpoint("/mixed", ankka.AllowAll(),
	ankka.Get("/{cartId}", func(_ context.Context, req *ankka.Request) (string, error) {
		return "cart:" + req.Param("cartId"), nil
	}),
	ankka.Delete("/{cartId}", func(_ context.Context, req *ankka.Request) (string, error) {
		return "purged:" + req.Param("cartId") + " by " + req.Principal.Subject, nil
	}).ACL(ankka.Authenticate(ankka.Issuer("support"))),
)
```

Scopes nest, and the innermost one wins. A request whose path matches no route of the endpoint is judged
by the endpoint's own ACL, so an endpoint that refuses answers the same way for a path that exists and one
that does not, rather than disclosing which is which.

## Call another service

`clients.services` calls another service's endpoints as this service, by its name, so the service called
reads this one as the caller and its access rule can admit it by name. The same client is given to a
workflow's step, a consumer, a timed action and an agent, in Scala, Python and TypeScript; in Java and
Kotlin to a workflow's step, a consumer and a timed action.
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
```

**Java**

```java
public static ServiceBuilder service() {
  return Ankka.service("shopping-cart")
      .register(ShoppingCart.class)
      .register(CartRows.class)
      .register(CheckoutNotifier.class)
      .register(CheckoutsSeen.class)
      .withEndpoint(CartEndpoint.class)
      .withEndpoint(CheckoutsSeenEndpoint.class)
      .withEndpoint(CartKeysEndpoint.class)
      .withMcpEndpoint(CartMcpEndpoint.class);
}
```

**Kotlin**

```kotlin
fun service(): ServiceBuilder =
    Ankka.service("shopping-cart")
        .register(ShoppingCart::class.java)
        .register(CartRows::class.java)
        .register(CheckoutNotifier::class.java)
        .register(CheckoutsSeen::class.java)
        .register(CheckoutWorkflow::class.java)
        .withEndpoint(CartEndpoint::class.java)
        .withEndpoint(CheckoutsSeenEndpoint::class.java)
        .withEndpoint(CartKeysEndpoint::class.java)
        .withMcpEndpoint(CartMcpEndpoint::class.java)

fun main() {
    service().start().whenTerminated().toCompletableFuture().join()
}
```

**Python**

```python
def service() -> Any:
    """The whole inventory: registration is explicit, so nothing is discovered by scanning."""
    registered = (
        Ankka.service()
        .register(ShoppingCartEntity)
        .register(CartRows)
        .register(CheckoutNotifier)
        .register(CheckoutLog)
        .register(CheckoutWorkflow)
        .register(CartAssistant)
        .register(CartAnswerer)
        .register(ShoppingCartEndpoint)
        .register(QuestionsEndpoint)
        .register(CallingEndpoint)
        .register(CartKeysEndpoint)
        .register(CartMcpEndpoint)
    )
    # The graph is published to a topic, and a sidecar with no broker refuses a service that
    # publishes to one. So the cart runs as it is with no broker, and with one it publishes its graph.
    if os.environ.get("ANKKA_KAFKA_BOOTSTRAP_SERVERS"):
        registered = registered.register(CartGraph).register(CartContentsGraph)
    return registered
```

**TypeScript**

```ts
import { Ankka } from "ankka"
import { ShoppingCartEntity } from "./cart.ts"
import { ShoppingCartEndpoint } from "./api.ts"

export const service = () => Ankka.service().register(ShoppingCartEntity).register(ShoppingCartEndpoint)

if (import.meta.main) await service().listen()
```

**Rust**

```rust
/// Everything the module hosts, registered by value; `service!` emits the exports once.
pub fn build() -> Service {
    let service = Service::new("ankka-rust")
        .register(entity::ShoppingCart)
        .register(cart_rows::CartRows)
        .register(checkout_workflow::CheckoutWorkflow)
        .register(checkout_notifier::CheckoutNotifier)
        .register(checkout_log::CheckoutLog)
        .register(assistant::CartAssistant)
        .endpoint(endpoint::CartApi);
    // The carts' graph is published to a topic, and a component that publishes needs a broker:
    // it is registered when the service's descriptor names one.
    match ankka::config("ANKKA_KAFKA_BOOTSTRAP_SERVERS") {
        Some(_) => service
            .register(cart_graph::CartGraph)
            .register(cart_contents_graph::CartContentsGraph),
        None => service,
    }
}

#[cfg(not(feature = "conformance"))]
ankka::service!(build);
```

**Go**

```go
// Service is the whole inventory: registration is explicit, so nothing is found by scanning.
func Service() *ankka.Service { return ankka.NewService(Entity, API, Calling) }
```

```go
func main() {
	if err := cart.Service().Start(context.Background()); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}
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
with helpers for one or many (in Go, `req *ankka.Request` carries them as `url.Values` and `http.Header`), the `principal` when the ACL established one, and the `caller` the platform
established. Every SDK answers with a status by raising or throwing an `HttpProblem(status, message)`:

**Scala**

```scala
get("/{cartId}/rows") { (cartId: String) =>
  clients.viewClient
    .forView(CartRows)
    .get(cartId)
    .getOrElse(throw HttpProblem.notFound(s"no row for cart '$cartId'"))
}
```

**Java**

```java
@Get("/{cartId}/checkout-row")
public CheckoutRows.Row checkoutRow(String cartId) {
  return viewClient()
      .forView(CheckoutRows.class)
      .get(cartId)
      .orElseThrow(
          () -> new ProblemException(Problem.notFound("no row for cart '" + cartId + "'")));
}
```

**Kotlin**

```kotlin
get("/carts/{cartId}/rows") {
    viewClient().forView(CartRows::class.java).getOrNull(path("cartId"))
        ?: throw ProblemException(Problem.notFound("no row for cart '${path("cartId")}'"))
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

**Rust**

```rust
fn row(request: &Request) -> Result<CartRow, HttpProblem> {
    let cart_id = request.path("cartId");
    let rows: Vec<CartRow> = request
        .client()
        .query(CartRows, "by-id", cart_id.to_string())?;
    rows.into_iter()
        .next()
        .ok_or_else(|| HttpProblem::new(404, format!("no row for cart '{cart_id}'")))
}
```

**Go**

```go
ankka.Get("/{cartId}/rows", func(ctx context.Context, req *ankka.Request) (Row, error) {
	row, found, err := ankka.ViewRow[Row](ctx, RowsID, req.Param("cartId"))
	if err == nil && !found {
		err = &ankka.HttpProblem{Status: 404, Message: "no row for '" + req.Param("cartId") + "'"}
	}
	return row, err
}),
```

A `CommandError` from a component call propagates as its code's status, as in Scala. Routes are matched
by the same rules, so a literal segment outranks a parameter.

The Python ACL is a required class attribute: an endpoint that declares no `acl` raises `RegistrationError`
when the class is defined, naming it. `Acl.ALLOW_ALL` admits any caller, `Acl.DENY_ALL` refuses everything,
and `Acl.AUTHENTICATED` admits a request carrying a token from one of the issuers the service lists. The
runtime verifies the token before the process is asked anything, exactly as `Oidc.authenticate()` does in
Scala, and hands the handler `self.request.principal` with the token's subject, roles, every other claim
under `claims`, and the name of the issuer that verified it. TypeScript declares `Acl.authenticated`,
Rust `Acl::Authenticated` and Go `ankka.Authenticate(ankka.Issuer(name))`, with the same principal. The issuers are the `ANKKA_AUTH_` named set described in
[Identity and machine accounts](../platform/identity.md#a-services-own-users); a service that declares the
rule and lists no issuer does not start, and its report names the route and the variable. A route decorator
takes an `acl` of its own, which replaces the endpoint's for that route exactly as `withAcl` does in Scala:

**Scala**

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

**Python**

```python
class MixedAclEndpoint(Endpoint):
    """One endpoint, two audiences: reading a cart is public, purging one is not. A route's own
    ``acl`` replaces the endpoint's for that route, so neither audience needs an endpoint of its
    own at a second prefix."""

    prefix = "/mixed"
    acl = Acl.ALLOW_ALL

    @get("/{cartId}")
    def cart(self, cartId: str) -> str:
        return f"cart:{cartId}"

    @delete("/{cartId}", acl=Acl.AUTHENTICATED)
    def purge(self, cartId: str) -> str:
        principal = self.request.principal
        return f"purged:{cartId} by {principal.subject if principal else '?'}"
```

**TypeScript**

```ts
export class CartsEndpoint extends Endpoint {
  static readonly prefix = "/carts"
  static readonly acl = Acl.allowAll

  static readonly routes = {
    getCart: get("/{cartId}", s.string, (_ep: CartsEndpoint, req) => `cart:${req.params.cartId}`),
    purge: del("/{cartId}", s.string, () => "never reached", { acl: Acl.denyAll }),
  }
}
```

**Rust**

```rust
/// Caller-naming ACLs: the suite names callers through the local caller header.
pub struct CallersEndpoint;

impl CallersEndpoint {
    fn whoami(request: &Request) -> Result<String, HttpProblem> {
        Ok(match request.caller() {
            Caller::Service { project, name } => format!("service:{project}/{name}"),
            Caller::Machine { organization, name } => format!("machine:{organization}/{name}"),
            Caller::Gateway => "gateway".to_string(),
            Caller::Local => "local".to_string(),
        })
    }
}

impl Endpoint for CallersEndpoint {
    const ENDPOINT_ID: &'static str = "CallersEndpoint";
    const PREFIX: &'static str = "/callers";

    fn acl() -> Acl {
        Acl::Callers(vec![
            CallerMatcher::Internet,
            CallerMatcher::service("orders"),
        ])
    }

    fn routes() -> Routes<CallersEndpoint> {
        Routes::new()
            .get("/whoami", CallersEndpoint::whoami)
            .get("/self", |_: &Request| Ok("self".to_string()))
            .with_acl(Acl::Callers(vec![CallerMatcher::SelfService]))
            .get("/granted", |_: &Request| Ok("granted".to_string()))
            .with_acl(Acl::Callers(vec![CallerMatcher::Granted]))
    }
}
```

**Go**

```go
// CartsEndpoint is open, but for one route nobody may reach until a rule of its own says otherwise.
var CartsEndpoint = ankka.NewEndpoint("/carts-admin", ankka.AllowAll(),
	ankka.Get("/{cartId}", func(_ context.Context, req *ankka.Request) (string, error) {
		return "cart:" + req.Param("cartId"), nil
	}),
	ankka.Delete("/{cartId}", func(_ context.Context, req *ankka.Request) (ankka.Done, error) {
		return ankka.Done{}, nil
	}).ACL(ankka.DenyAll()),
)
```

Every SDK names callers as Scala does, with the same meaning; the sidecar applies the ACL before the process
is asked anything, and hands the handler the caller:

**Scala**

```scala
final class CallersEndpoint extends HttpEndpoint("/callers"):
  val acl: Acl = Acl.allowCallers(Callers.internet, Callers.service("orders"))

  get("/whoami")(() =>
    caller match
      case Caller.Service(project, name) => s"service:$project/$name"
      case Caller.Machine(org, name)     => s"machine:$org/$name"
      case Caller.Gateway                => "gateway"
      case Caller.Local                  => "local"
  )

  withAcl(Acl.allowCallers(Callers.self)) {
    get("/self")(() => "self")
    sse("/events")(() => Source.single("tick"))
  }
```

**Java**

```java
/**
 * Only the internet and the orders service in this project; any other caller is refused 403. An
 * endpoint has one ACL for all its routes, so routes for another audience, such as another
 * instance of this very service and nothing else ({@code Callers.self()}), are an endpoint of
 * their own.
 */
public class CallerRoutes extends HttpEndpoint {

  @Override
  protected Acl acl() {
    return Acl.allowCallers(Callers.internet(), Callers.service("orders"));
  }

  @Get("/callers/only-orders")
  public String onlyOrders() {
    return "admitted: " + describe(caller());
  }
```

**Kotlin**

```kotlin
// An ACL belongs to an endpoint class, so a route admitting other callers is a class of its own.

/** Only the internet and the orders service in this project; any other caller is refused 403. */
class OnlyOrdersEndpoint : HttpEndpoint(), RouteRegistrar {
    override fun acl(): Acl = Acl.allowCallers(Callers.internet(), Callers.service("orders"))

    override fun routes() = routes {
        get("/callers/only-orders") { "admitted: ${describe(request.caller())}" }
    }
}

/** Another instance of this very service, and nothing else. */
class OnlySelfEndpoint : HttpEndpoint(), RouteRegistrar {
    override fun acl(): Acl = Acl.allowCallers(Callers.self())

    override fun routes() = routes { get("/callers/only-self") { "admitted: myself" } }
}
```

**Python**

```python
prefix = "/callers"
# Only the internet and the orders service in this project; any other caller is refused 403.
acl = Acl.allow_callers(Callers.internet, Callers.service("orders"))

# Another instance of this very service, and nothing else: a route's own ACL replaces the
# endpoint's for that route.
@get("/self", acl=Acl.allow_callers(Callers.self_))
def only_self(self) -> str:
    return "self"
```

```python
@get("/whoami")
def whoami(self) -> str:
    c = self.request.caller
    if isinstance(c, ServiceCaller):
        return f"service:{c.project}/{c.name}"
    if isinstance(c, MachineCaller):
        return f"machine:{c.organization}/{c.name}"
    return "gateway" if isinstance(c, Gateway) else "local"
```

**TypeScript**

```ts
// Only the internet and the orders service in this project; any other caller is refused 403.
onlyOrders: get("/only-orders", s.string, (_ep: CallersEndpoint, req) => `admitted: ${describe(req.caller)}`, {
  acl: Acl.allowCallers(Callers.internet, Callers.service("orders")),
}),
// Another instance of this very service, and nothing else.
onlySelf: get("/only-self", s.string, () => "admitted: myself", { acl: Acl.allowCallers(Callers.self) }),
```

```ts
whoami: get("/whoami", s.string, (_ep: CallersEndpoint, req) => whoIsCalling(req.caller)),
```

**Rust**

```rust
/// Caller-naming ACLs: the suite names callers through the local caller header.
pub struct CallersEndpoint;

impl CallersEndpoint {
    fn whoami(request: &Request) -> Result<String, HttpProblem> {
        Ok(match request.caller() {
            Caller::Service { project, name } => format!("service:{project}/{name}"),
            Caller::Machine { organization, name } => format!("machine:{organization}/{name}"),
            Caller::Gateway => "gateway".to_string(),
            Caller::Local => "local".to_string(),
        })
    }
}

impl Endpoint for CallersEndpoint {
    const ENDPOINT_ID: &'static str = "CallersEndpoint";
    const PREFIX: &'static str = "/callers";

    fn acl() -> Acl {
        Acl::Callers(vec![
            CallerMatcher::Internet,
            CallerMatcher::service("orders"),
        ])
    }

    fn routes() -> Routes<CallersEndpoint> {
        Routes::new()
            .get("/whoami", CallersEndpoint::whoami)
            .get("/self", |_: &Request| Ok("self".to_string()))
            .with_acl(Acl::Callers(vec![CallerMatcher::SelfService]))
            .get("/granted", |_: &Request| Ok("granted".to_string()))
            .with_acl(Acl::Callers(vec![CallerMatcher::Granted]))
    }
}
```

**Go**

```go
// Callers names callers through the local caller header, since every target runs outside a
// cluster, where this service's own identity is local/local.
var Callers = ankka.NewEndpoint("/callers", ankka.AllowIf(ankka.Internet(), ankka.ServiceNamed("orders")),
	ankka.Get("/whoami", func(ctx context.Context, _ *ankka.Request) (string, error) {
		return callerWord(ankka.CallerFrom(ctx)), nil
	}),
	ankka.Get("/self", func(context.Context, *ankka.Request) (string, error) { return "self", nil }).ACL(ankka.AllowIf(ankka.Self())),
	ankka.SSE("/events", func(context.Context, *ankka.Request) iter.Seq2[string, error] {
		return func(yield func(string, error) bool) { yield("tick", nil) }
	}).ACL(ankka.AllowIf(ankka.Self())),
	// Whoever the project granted this route to.
	ankka.Get("/granted", func(context.Context, *ankka.Request) (string, error) { return "granted", nil }).ACL(ankka.AllowIf(ankka.Granted())),
)
```

In Python `Callers.self_` carries a trailing underscore so it does not shadow `self`. A Python or TypeScript
service calls another as itself through `services`, and a Go service through `ankka.CallService(ctx, …)`, as
[Calling other services](calling-services.md) describes.

A `str` return value is answered as `text/plain`, and a `str` body is read as raw text, not as a JSON
string — the same encoding the Scala SDK uses.
