# MCP endpoints

> Serve a service's commands, queries and routes as tools to any MCP client — declaring tools in each language, who may call them, client keys issued by the service, what a client that signs in is told, and brokered sign-in.

Source: https://docs.ankka.cloud/build/mcp-endpoints/
An **MCP endpoint** serves some of a service's behaviour as **tools** to any client that speaks the
Model Context Protocol: Claude, a coding agent, a product's own assistant, or an agent of another
service. The client lists the tools, reads what each one does and what arguments it takes, and calls
them; the platform speaks the protocol, checks the arguments, admits the caller and traces each call,
so the service declares tools and nothing else.

An MCP endpoint is an endpoint like an [HTTP endpoint](http-endpoints.md): it holds no state, turns each
tool call into calls on the service's components, and states who may call it. It is served on the
service's HTTP port, at its own path, over the protocol's Streamable HTTP transport, and every hostname
the service answers at serves it.

This page is the server side. An agent that *uses* MCP servers, a sibling's MCP endpoint among them, is
described in [MCP servers](mcp-servers.md).

## Declaring an endpoint and its tools

A tool has a name, a description the model reads to decide when to call it, typed arguments and a
handler. This is the shopping cart sample's endpoint, at `/mcp`, with three tools:

**Scala**

```scala
/**
 * The cart as an MCP server: a customer's assistant — Claude, a coding agent, a product's own —
 * reads a cart, adds to it and checks it out through these tools, at `/mcp`, rather than through
 * the cart's routes.
 *
 * Two tools are the entity's own handlers, declared by reference; one has a handler of its own,
 * because its arguments are not the command's input.
 */
final class CartMcpEndpoint(client: ComponentClient) extends McpEndpoint("/mcp"):

  // A request carrying one of the cart's client keys, as the key's subject (`CartKeysEndpoint`).
  val acl: Acl = ClientKeys.authenticate()

  /** How a cart is told to the assistant: its JSON, which is also the tool's structured content. */
  private given JsonValueCodec[ShoppingCart] = Codecs.make

  val getCart = tool("get_cart")
    .describedAs("Reads one cart: its lines, and whether it is checked out")
    .query(ShoppingCartEntity.getCart, id = "cartId")

  val addItem = tool("add_item")
    .describedAs("Adds a quantity of a product to a cart")
    .param[String]("cartId", "The cart's id")
    .param[String]("productId", "The product's id")
    .param[Int]("quantity", "How many; greater than zero")
    .handle { (cartId: String, productId: String, quantity: Int) =>
      client
        .forEventSourcedEntity(EntityId(cartId))
        .call(ShoppingCartEntity.addItem)
        .invoke(LineItem(productId, productId, quantity))
    }

  val checkout = tool("checkout")
    .describedAs("Checks a cart out, answering what it held")
    .command(ShoppingCartEntity.checkout, id = "cartId")
```

**Python**

```python
class CartMcp(McpEndpoint):
    """The cart's MCP endpoint at ``/mcp``. ``get_cart`` answers the principal beside the cart, so a
    case can see who a client key or a token made the caller, and that nothing in it reads as the key."""

    path = "/mcp"
    acl = Acl.any_of(Acl.CLIENT_KEYS, Acl.AUTHENTICATED)

    def _cart(self, cart_id: str) -> Calls:
        assert self.client is not None
        return self.client.with_metadata(self.request.metadata).for_event_sourced_entity("shopping-cart", cart_id)

    @tool("Reads one cart")
    async def get_cart(self, cartId: str) -> ToolCart:
        cart = await self._cart(cartId).call("get-cart").invoke(reply=ShoppingCart)
        p = self.request.principal
        return ToolCart(cart, p.subject if p else None, dict(p.claims) if p else {})

    @tool("Adds an item to a cart")
    async def add_item(self, cartId: str, productId: str, quantity: int) -> Done:
        return await self._cart(cartId).call("add-item").invoke(LineItem(productId, productId, quantity))

    checkout = tool.command(ShoppingCartEntity, "checkout", id="cartId", description="Checks a cart out")
```

**TypeScript**

```typescript
export class CartMcp extends McpEndpoint {
  static readonly path = "/mcp"
  static readonly acl = Acl.anyOf(Acl.clientKeys, Acl.authenticated)
  static readonly tools = tools<CartMcp>()
    .tool("get_cart", "Reads one cart", GetCartArguments, ToolCart, async (args, ep) => {
      const p = ep.request.principal
      const cart = await ep.client.of(ShoppingCartEntity, args.cartId).call(ShoppingCartEntity.handlers.getCart).invoke()
      return { cart, subject: p?.subject ?? null, claims: { ...(p?.claims ?? {}) } }
    })
    .tool("add_item", "Adds an item to a cart", AddItemArguments, undefined, async (args, ep) => {
      await ep.client.of(ShoppingCartEntity, args.cartId).call(ShoppingCartEntity.handlers.addItem).invoke({ productId: args.productId, name: args.productId, quantity: args.quantity })
      return done
    })
    .command("checkout", "Checks a cart out", ShoppingCartEntity, "checkout", { id: "cartId" })
}
```

**Rust**

```rust
/// The cart's MCP endpoint at `/mcp`. `get_cart` answers the principal beside the cart, so a case
/// can see who a client key or a token made the caller, and that nothing in it reads as the key.
pub struct CartMcp;

impl McpEndpoint for CartMcp {
    const ENDPOINT_ID: &'static str = "cart-mcp";
    const PATH: &'static str = "/mcp";

    fn acl() -> Acl {
        Acl::AnyOf(vec![Acl::ClientKeys, Acl::Authenticated])
    }

    fn tools() -> McpTools<CartMcp> {
        McpTools::new()
            .tool(
                "get_cart",
                "Reads one cart",
                Schema::object().string("cartId", "The cart's id"),
                |args: CartArgs, request: &McpRequest| {
                    let cart: crate::domain::ShoppingCart =
                        request
                            .client()
                            .invoke(ShoppingCart, &args.cart_id, "get-cart", ())?;
                    let principal = request.principal();
                    Ok(ToolCart {
                        cart,
                        subject: principal.map(|p| p.subject.clone()),
                        claims: principal.map(|p| p.claims.clone()).unwrap_or_default(),
                    })
                },
            )
            .tool(
                "add_item",
                "Adds an item to a cart",
                Schema::object()
                    .string("cartId", "The cart's id")
                    .string("productId", "The product's id")
                    .integer("quantity", "How many; greater than zero"),
                |args: AddItemArgs, request: &McpRequest| {
                    let item = crate::domain::LineItem {
                        product_id: args.product_id.clone(),
                        name: args.product_id,
                        quantity: args.quantity,
                    };
                    request.client().invoke::<_, _, Done, _>(
                        ShoppingCart,
                        &args.cart_id,
                        "add-item",
                        item,
                    )
                },
            )
            .command(
                "checkout",
                "Checks a cart out",
                ShoppingCart,
                "checkout",
                "cartId",
                Schema::object(),
            )
    }
}
```

A tool's name is what a client calls it by and is part of the endpoint's contract, as a route's path is:
letters, digits, `_` and `-`, at most 64 characters, unique within the endpoint. Its arguments are
described to the client as a JSON Schema built from their types, every argument required; a call whose
arguments the types refuse is answered with a protocol error naming the argument, and no handler runs.

What a handler answers is the tool's result. A value with a JSON encoding is answered as structured
content and as the same JSON in text; a string is answered as text. A handler that fails, or a command
the entity refuses, is a tool error: the client is told `isError` and the message, which a model reads
and can act on. Only a malformed request, an unknown tool or arguments the types refuse are protocol
errors.

A service registers its MCP endpoint beside its HTTP endpoints, in the same server.

## A command, query or route as a tool

Most tools are a handler the service already has. Rather than write a handler that calls it, a tool can
**refer** to it: a command or a query of an entity, named by its handle, with the argument that is the
entity's id, or a route of one of the service's own HTTP endpoints, named by its method and path.

```scala
val getCart = tool("get_cart")
  .describedAs("Reads one cart: its lines, and whether it is checked out")
  .query(ShoppingCartEntity.getCart, id = "cartId")
```

A reference is checked when the service starts: a command, query or route that does not exist fails the
start, naming the tool. The tool's arguments are the id and the handler's own input; a route reference's
arguments are its path parameters and, for a route that takes a body, the body as JSON. A referenced call
runs as a call from the tool, under the caller the tool was called by, so the route's or entity's own
rules still apply.

A tool with a handler of its own is for arguments that are not the command's input, or for a tool that
calls more than one component:

```scala
val addItem = tool("add_item")
  .describedAs("Adds a quantity of a product to a cart")
  .param[String]("cartId", "The cart's id")
  .param[String]("productId", "The product's id")
  .param[Int]("quantity", "How many; greater than zero")
  .handle { (cartId: String, productId: String, quantity: Int) =>
    client
      .forEventSourcedEntity(EntityId(cartId))
      .call(ShoppingCartEntity.addItem)
      .invoke(LineItem(productId, productId, quantity))
  }
```

## Who may call

An MCP endpoint must state its ACL; one that states none fails the start. Three kinds of caller can be
admitted, alone or together with `Acl.anyOf`:

- **A client key**, issued by the service for one customer's assistant (`ClientKeys.authenticate()`). The
  request carries it as a bearer credential, and the handler is told the key's subject and attributes.
- **A token** from an issuer the service lists (`Oidc.authenticate()`), as an HTTP endpoint admits one. A
  client that can sign a person in reads where to sign in from the endpoint's resource metadata.
- **A named workload**, another service of the project or a granted one (`Acl.allowCallers`), as an HTTP
  endpoint admits one. An agent of a sibling service calls as its service.

The sample admits its client keys:

```scala
// A request carrying one of the cart's client keys, as the key's subject (`CartKeysEndpoint`).
val acl: Acl = ClientKeys.authenticate()
```

The references in the other languages admit a client key or a token, either one:
`Acl.any_of(Acl.CLIENT_KEYS, Acl.AUTHENTICATED)` in Python, `Acl.anyOf(Acl.clientKeys,
Acl.authenticated)` in TypeScript and `Acl::AnyOf(vec![Acl::ClientKeys, Acl::Authenticated])` in Rust.

A request with no credential the ACL admits is answered `401`, with a `WWW-Authenticate` challenge that
names the endpoint's resource metadata when the ACL admits tokens. A single tool can carry an ACL of its
own, which replaces the endpoint's for that tool; a tool the caller may not call is left out of the
listing, and calling it is answered as for a tool that does not exist, so the caller learns nothing about
it.

## Client keys

A **client key** is a credential a service issues to one customer's assistant, for one MCP endpoint. Only
the service knows who its customers are, so the service's own code issues keys, from a route behind the
service's own sign-in; the platform keeps them, checks them on every request and revokes them.

```scala
/**
 * Client keys for the cart's tools: one per customer's assistant, issued by the cart's own code,
 * because only the cart knows who its customers are. The key is in the answer to the issue, once;
 * after that the cart lists and revokes keys by id.
 *
 * Open to every caller here, as the rest of the sample is. A real product puts these routes behind
 * its own sign-in, and issues a key for the customer who is signed in.
 */
final class CartKeysEndpoint(keys: ClientKeys) extends HttpEndpoint("/mcp-keys"):

  final case class KeyRequest(
      subject: String,
      scope: Set[String] = Set.empty,
      label: Option[String] = None
  )

  private given JsonValueCodec[KeyRequest]      = Codecs.make
  private given JsonValueCodec[IssuedClientKey] = Codecs.make

  val acl: Acl = Acl.AllowAll

  postBody("/") { (asked: KeyRequest) =>
    keys.issue(asked.subject, Expiry.After(365.days), scope = asked.scope, label = asked.label)
  }

  delete("/{id}") { (id: String) =>
    keys.revoke(id)
    "revoked"
  }
```

**Python**

```python
class McpKeysEndpoint(Endpoint):
    """The reference's own routes over its client keys, as a product would write them."""

    prefix = "/mcp-keys"
    acl = Acl.ALLOW_ALL

    @post("/")
    async def issue(self, body: McpKeyRequest) -> IssuedKey:
        issued = await self.client_keys.issue(
            body.subject, Expiry.never(), scope=body.scope, attributes=body.attributes
        )
        return IssuedKey(issued.id, issued.client_key)

    @get("/")
    async def listed(self) -> list[ClientKeySummary]:
        return await self.client_keys.list()

    @delete("/{id}")
    async def revoke(self, id: str) -> str:
        await self.client_keys.revoke(id)
        return "revoked"
```

**TypeScript**

```typescript
export class McpKeysEndpoint extends Endpoint {
  static readonly prefix = "/mcp-keys"
  static readonly acl = Acl.allowAll
  static readonly routes = {
    issue: post("/", McpKeyRequest, IssuedKey, async (ep: McpKeysEndpoint, _req, body) => {
      const issued = await ep.clientKeys.issue({ subject: body.subject, expiry: Expiry.never, scope: body.scope, attributes: body.attributes })
      return { id: issued.id, clientKey: issued.clientKey }
    }),
    list: get("/", s.list(ListedKey), async (ep: McpKeysEndpoint) =>
      (await ep.clientKeys.list()).map((k) => ({ id: k.id, subject: k.subject, attributes: { ...k.attributes }, label: k.label, endpoint: k.endpoint, scope: [...k.scope] })),
    ),
    revoke: del("/{id}", s.string, async (ep: McpKeysEndpoint, req) => {
      await ep.clientKeys.revoke(req.params.id)
      return "revoked"
    }),
  }
}
```

**Rust**

```rust
/// The reference's own routes over its client keys, as a product would write them.
pub struct McpKeysEndpoint;

fn keys_of(request: &Request) -> Result<ankka::ClientKeys, HttpProblem> {
    request
        .context()
        .client_keys()
        .ok_or_else(|| HttpProblem::new(500, "an endpoint has the client keys"))
}

impl Endpoint for McpKeysEndpoint {
    const ENDPOINT_ID: &'static str = "mcp-keys";
    const PREFIX: &'static str = "/mcp-keys";

    fn acl() -> Acl {
        Acl::AllowAll
    }

    fn routes() -> Routes<McpKeysEndpoint> {
        Routes::new()
            .post("/", |request: &Request, body: McpKeyRequest| {
                let mut key = IssueClientKey::new(body.subject, Expiry::Never).scope(body.scope);
                for (name, value) in body.attributes {
                    key = key.attribute(name, value);
                }
                Ok(keys_of(request)?.issue(key)?)
            })
            .get("/", |request: &Request| Ok(keys_of(request)?.list()?))
            .delete("/{id}", |request: &Request| {
                keys_of(request)?.revoke(request.path("id"))?;
                Ok("revoked".to_string())
            })
    }
}
```

- **Issuing** takes the subject the key acts as, an expiry (a time, or never), and optionally the tools
  it may call (every tool when none is named), attributes, at most sixteen, which a handler reads among
  the principal's claims, and a label. The answer is the key's id and the key itself, `ankka_ck_` then
  the id and a secret. **The key is shown once**, in that answer: the platform keeps only its digest, and
  nothing it logs, traces or lists holds the key.
- **What the handler reads.** A request carrying the key runs as a principal whose subject is the key's
  subject, with the key's attributes among its claims beside `client_key`, the key's id, and `kind`,
  `client-key`. A tool reads `request.principal` as any handler does.
- **Listing** answers every key of the service, revoked and expired ones included, with its id, subject,
  attributes, label, endpoint, scope, expiry and when it was last used, never the key.
- **Revoking** a key by id refuses its next request on every instance of the service, within a second.

A key is admitted only at the MCP endpoint it was issued for. Client keys need the service to register
the MCP runtime and its components; a service with an endpoint that admits client keys and no runtime
fails the start.

## Pointing a client at it

The endpoint's address is the service's address and the endpoint's path. Locally, a service run with
`sbt run` serves it on port 9000; deployed, at the service's hostname once it is exposed.

- **Claude Code** adds it as an HTTP server, with the key as a header:

  ```bash
  claude mcp add --transport http cart http://localhost:9000/mcp --header "Authorization: Bearer ankka_ck_..."
  ```

- **Claude Desktop**, and any client configured by a JSON file, names the URL and the same header.
- **An agent of another service** lists it as an MCP server by service and path, and calls as its
  service; see [MCP servers](mcp-servers.md).

The endpoint is stateless: it issues no session id, and every request is answered on its own. A change to
the tools is seen by a client at its next listing.

## What a client that signs in is told

An endpoint whose ACL admits tokens serves its resource metadata at
`/.well-known/oauth-protected-resource<path>`: the endpoint's URL and the issuers whose tokens it admits,
as authorization servers. A client that can sign a person in reads it from the `401` challenge, signs the
person in at the issuer and calls with the token it is given. An endpoint that admits no tokens serves no
metadata.

That works when the issuer lets the client register itself. Many do not, and many clients can only sign
in, holding no key or token of their own. For those, an endpoint can opt into **brokered sign-in**
(`brokered = true` in Scala): the platform runs an authorization server for the endpoint, at
`/_ankka/sign-in<path>` on the service's own hostnames, which registers clients, sends the person to the
service's issuer to sign in, and mints a token for the endpoint naming who signed in. The resource
metadata then names the broker alone.

The broker is a client of the service's issuer and never an account store: it keeps no password and no
account, only the signed-in person's subject and claims for the life of the token it minted. It needs a
client id registered at the issuer, given as `ANKKA_AUTH_<NAME>_CLIENT_ID` (and
`ANKKA_AUTH_<NAME>_CLIENT_SECRET` for a confidential client), the MCP runtime and the service's timers.

## What fails the start

A service with an MCP endpoint does not start, and says why, when:

- the endpoint states no ACL;
- two tools share a name, or a name is not a tool name;
- a tool has no description;
- a tool's arguments have no schema the platform can describe;
- a reference names a command, query or route the service does not have;
- the endpoint's path is another endpoint's route, or a route of the service is under `/_ankka/sign-in`;
- the ACL admits client keys and the service has no MCP runtime;
- the endpoint opts into brokered sign-in and an issuer in its ACL has no client id, or the service has
  no timers.

## Testing

An MCP endpoint is tested as an HTTP endpoint is: start the service with `AnkkaTestKit`, the endpoint and
the MCP runtime, and send the protocol's requests. The sample's suite issues a key through its own route
and calls the tools as an assistant would:

```scala
/** One JSON-RPC request to the cart's MCP endpoint, as any MCP client sends it. */
private def mcp(method: String, params: String): String =
  val (status, body) = send(
    "POST",
    "/mcp",
    Some(s"""{"jsonrpc":"2.0","id":1,"method":"$method","params":$params}"""),
    Seq("Authorization" -> s"Bearer $assistantKey")
  )
  assertEquals(status, 200, body)
  body

private def callTool(name: String, arguments: String): Unit =
  last = (0, mcp("tools/call", s"""{"name":"$name","arguments":$arguments}"""))
```

## What it does not do

- An MCP endpoint offers tools. It offers no resources, prompts, sampling or elicitation.
- It keeps nothing between requests: no session, and no notice that the tools changed.
- There is no one MCP endpoint for every service of a project; each service serves its own.
- The platform runs an authorization server only for an endpoint that opted into brokered sign-in.

[Limitations](../reference/limitations.md) lists these with the rest of what ankka does not do.
