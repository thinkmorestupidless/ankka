# Contract: the Python, TypeScript and Rust APIs

What a developer writes in each language. The wire is [protocol.md](protocol.md); the platform
answers the protocol, verifies the credential, checks the arguments and asks the process to run the
tool (R13). References are resolved by the SDK at registration into ordinary tools.

## Python (`ankka`)

```python
from dataclasses import dataclass
from ankka import Acl, McpEndpoint, tool, Expiry

@dataclass(frozen=True)
class AddItem:
    cart_id: str
    product_id: str
    quantity: int

class CartMcp(McpEndpoint):
    path = "/mcp"
    acl = Acl.any_of(Acl.CLIENT_KEYS, Acl.AUTHENTICATED)

    @tool("Reads one cart")
    async def get_cart(self, cart_id: str) -> Cart:
        return await self.client.for_event_sourced_entity(ShoppingCart, cart_id).call(ShoppingCart.get_cart)

    @tool("Adds an item to a cart")
    async def add_item(self, args: AddItem) -> Cart: ...

    checkout = tool.command(ShoppingCart, "checkout", id="cart_id", description="Checks a cart out",
                            acl=Acl.any_of(Acl.CLIENT_KEYS, Acl.allow_callers(Callers.service("office"))))
    get_job = tool.query(Job, "get", id="job_id", description="Reads one job")
    todays = tool.route(OrdersEndpoint, "GET", "/orders/{day}", description="The day's orders")

# in a handler:
issued = await self.client_keys.issue("acme", expiry=Expiry.after(days=365), scope={"list_jobs", "book_job"},
                                      attributes={"person": "alice", "role": "owner"}, label="Alice's assistant")
issued.id, issued.client_key                         # the key is shown nowhere again
await self.client_keys.list()                        # ClientKeySummary records
await self.client_keys.revoke(issued.id)
self.request.principal.subject, self.request.principal.claims["person"]
```

- `McpEndpoint` is a class beside `Endpoint`: `path` and `acl` are required ClassVars checked in
  `__init_subclass__`; `brokered: ClassVar[bool] = False`. `@tool(description)` on a method derives
  the schema from the type hints with `agent._schema_for` (parameters become properties, or one
  dataclass parameter's fields do); `tool.command/query/route(...)` are module-level factories
  resolved in `Registry.spec()` against the registered components and endpoints, refusing at
  registration a component, handler or route the service lacks. The return annotation's codec
  gives `text` and, for a dataclass or dict, `structured_json`.
- `Acl` gains `CLIENT_KEYS` and `Acl.any_of(*acls)`.
- `HasClientKeys` mixin gives `self.client_keys` to endpoints, MCP endpoints, timed actions and
  agents, over the `Client` rpcs; `UNIMPLEMENTED` becomes "the runtime is too old for client keys
  (1.20)". `InMemoryClientKeys` for unit tests.
- `McpServicer.InvokeTool` finds the endpoint and tool, decodes the arguments with the tool's
  codec, sets the `ContextVar` request with the principal and caller, runs the method, and answers
  `ok`, `error` (an exception's message) or `failure`.
- The SDK refuses `Discover` from a runtime below `1.20` when a `McpEndpoint` is registered.

## TypeScript (`ankka`)

```ts
import { Acl, Callers, McpEndpoint, s, tools, Expiry } from "ankka";

export class CartMcp extends McpEndpoint {
  static readonly path = "/mcp";
  static readonly acl = Acl.anyOf(Acl.clientKeys, Acl.authenticated);
  static readonly tools = tools()
    .tool("get_cart", "Reads one cart", s.record("GetCart", { cartId: s.string }), Cart,
          (args, ctx) => ctx.client.forEventSourcedEntity(ShoppingCart, args.cartId).call(ShoppingCart.getCart))
    .command("checkout", "Checks a cart out", ShoppingCart, "checkout", { id: "cartId",
             acl: Acl.anyOf(Acl.clientKeys, Acl.allowCallers(Callers.service("office"))) })
    .query("get_job", "Reads one job", Job, "get", { id: "jobId" })
    .route("todays_orders", "The day's orders", OrdersEndpoint, "GET", "/orders/{day}");
}

const issued = await ctx.clientKeys.issue({ subject: "acme", expiry: Expiry.after({ days: 365 }),
  scope: ["list_jobs", "book_job"], attributes: { person: "alice", role: "owner" }, label: "Alice's assistant" });
await ctx.clientKeys.list(); await ctx.clientKeys.revoke(issued.id);
```

- `McpEndpointClass { path; acl; tools: ToolTable; brokered? }` beside `EndpointClass`; `tools()`
  builds a table as `routes()` does; `tool(name, description, input: Shape, reply: Shape, run)`
  derives `input_schema_json` with `toJsonSchema`; `command/query/route` are resolved in
  `renderSpec` against the registered components and endpoints and refused at registration when
  missing. The reply `Shape` decides `text` and `structured_json`.
- `Acl` gains `clientKeys` and `Acl.anyOf(...)`; `RequestContext.principal` is as for a route.
- `clientKeysFor(client)` in `client.ts` with `Code.Unimplemented` → "too old (1.20)".
- `server/mcp.ts` handles `invokeTool` as `server/http.ts` handles a route: the request in
  `AsyncLocalStorage`, the run function awaited, `ok` / `error` / `failure`.
- `server/discovery.ts` refuses a runtime below `1.20` when an MCP endpoint is registered.

## Rust (`ankka`)

```rust
pub struct CartMcp;
impl McpEndpoint for CartMcp {
    const ENDPOINT_ID: &'static str = "cart-mcp";
    const PATH: &'static str = "/mcp";
    fn acl() -> Acl { Acl::AnyOf(vec![Acl::ClientKeys, Acl::Authenticated]) }
    fn tools() -> Tools<Self> {
        Tools::new()
            .tool::<GetCart, Cart>("get_cart", "Reads one cart", Schema::object().string("cart_id", "The cart's id"),
                |args, ctx| ctx.client().for_event_sourced_entity::<ShoppingCart>(&args.cart_id).call(ShoppingCart::GET_CART))
            .command::<ShoppingCart>("checkout", "Checks a cart out", "checkout", "cart_id", Schema::object())
            .route("todays_orders", "The day's orders", OrdersEndpoint::ENDPOINT_ID, "GET", "/orders/{day}")
    }
}
// in a handler
let issued = ctx.client_keys().issue(IssueClientKey::new("acme", Expiry::After(Duration::from_secs(365 * 86_400)))
    .scope(["list_jobs", "book_job"]).attribute("person", "alice").label("Alice's assistant"))?;
```

- `trait McpEndpoint { const ENDPOINT_ID; const PATH; fn acl() -> Acl; fn tools() -> Tools<Self>; const BROKERED: bool = false; }`;
  `Acl` gains `ClientKeys` and `AnyOf(Vec<Acl>)`. Schemas are written with the existing `Schema`
  builder (Rust has no runtime type description); a `command` reference takes the input's schema
  for the same reason, and the id name. `Service::register` resolves references against the
  registered components and endpoints and refuses at `discover` what is missing.
- `Export::McpTool` and `ankka1_mcp_tool` in the `service!` macro; the dispatch decodes the
  arguments with `serde`, builds a `Context` with the principal and caller, runs the closure.
- `ctx.client_keys()` over the three imports, each in its own link block; `Error` in the reply.
- `Service::discover` refuses a runtime below `1.20` when an MCP endpoint is registered.

## Conformance (`sidecar/.../conformance/ConformanceSuite`, all four references)

| Case | Asserts |
|---|---|
| `mcp-endpoint.lists-tools` | `tools/list` at `/mcp` names `get_cart`, `add_item`, `checkout` with the reference's descriptions and schemas |
| `mcp-endpoint.call-reaches-handler` | `tools/call get_cart {cartId}` answers the cart the entity holds, as text and structured |
| `mcp-endpoint.handler-error-is-tool-error` | `add_item` with quantity 0 answers `isError: true` with the reference's message |
| `mcp-endpoint.unknown-tool-is-protocol-error` | `-32602` naming the tool |
| `mcp-endpoint.bad-argument-is-protocol-error` | `-32602` naming `quantity` |
| `mcp-endpoint.client-key-admits-subject` | a route of the reference issues a key for `acme`; a call carrying it reaches `get_cart` with `principal.subject = acme` and the attribute as a claim; the handler was given nothing that reads as the key |
| `mcp-endpoint.client-key-scope` | a key scoped to `get_cart` lists one tool; `checkout` is `-32602` |
| `mcp-endpoint.client-key-revoked` | after the reference's revoke route, the key is refused |
| `mcp-endpoint.token-admits-subject` | a token from `ConformanceTarget.issuer` runs `get_cart` as its subject |
| `mcp-endpoint.metadata-names-issuer` | `GET /.well-known/oauth-protected-resource/mcp` names the test issuer |
| `mcp-endpoint.reference-tool` | `checkout`, a reference in every reference, reaches the command |

The module shape skips nothing: a module's MCP endpoint admits keys and tokens because the sidecar
holds both verifiers. Each reference's `ComponentIds` gains nothing (an MCP endpoint is not a
component); `discovery.lists-every-component`'s route list gains the `MCP /mcp <tool>` records.
