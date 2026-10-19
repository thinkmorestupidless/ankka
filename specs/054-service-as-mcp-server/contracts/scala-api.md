# Contract: the Scala API

What a Scala service writes. Module `ankka-mcp` (R1); decisions in [../research.md](../research.md).
Everything below is public unless marked `private[ankka]`.

## Declaring an MCP endpoint

```scala
import com.thinkmorestupidless.ankka.http.{Acl, Callers, EndpointClients, HttpServer}
import com.thinkmorestupidless.ankka.auth.oidc.Oidc
import com.thinkmorestupidless.ankka.mcp.{ClientKeys, McpEndpoint, McpRuntime}

final class CartMcp(clients: EndpointClients) extends McpEndpoint("/mcp"):
  def acl = Acl.anyOf(ClientKeys.authenticate(), Oidc.authenticate())

  val getCart = tool("get_cart").describedAs("Reads one cart")
    .query(ShoppingCart.getCart, id = "cartId")

  val addItem = tool("add_item").describedAs("Adds an item to a cart")
    .param[String]("cartId", "The cart's id")
    .param[String]("productId", "The product")
    .param[Int]("quantity", "How many; positive")
    .handle { (cartId, productId, quantity) =>
      clients.componentClient.forEventSourcedEntity(cartId).call(ShoppingCart.addItem).invoke(LineItem(productId, quantity))
    }

  val checkout = tool("checkout").describedAs("Checks a cart out")
    .withAcl(Acl.anyOf(ClientKeys.authenticate(), Acl.allowCallers(Callers.service("office"))))
    .command(ShoppingCart.checkout, id = "cartId")

  val todaysOrders = tool("todays_orders").describedAs("The day's orders")
    .route(OrdersEndpoint, "GET", "/orders/{day}")

Ankka.service("cart")
  .register(ShoppingCart.descriptor)
  .registerAll(McpRuntime.descriptors)          // only when client keys or brokered sign-in are used
  .withExtension(McpRuntime())                  // likewise
  .withExtension(HttpServer.of(clients => CartMcp(clients), clients => OrdersEndpoint(clients)))
```

### `McpEndpoint`

```scala
abstract class McpEndpoint(val path: String = "/mcp") extends HttpEndpoint(path):
  def acl: Acl                                   // abstract, as every endpoint's
  def name: String = McpEndpoint.serviceName     // serverInfo.name
  def version: String = McpEndpoint.serviceVersion
  def brokered: Boolean = false                  // opt into brokered sign-in (R11)
  protected def tool(name: String): ToolBuilder
  final def tools: Vector[McpTool]               // what tool(...) built, in declaration order
  // inherited and meaningful: request, caller, principal
  // inherited and refused at start if used: get, post, put, patch, delete, sse, socket
```

### `ToolBuilder`

```scala
final class ToolBuilder private[mcp] (name: String):
  def describedAs(description: String): Described
final class Described:
  def withAcl(acl: Acl): Described
  def param[A: SchemaType](name: String, description: String): Params1[A]       // up to Params3
  def arguments[A: JsonSchema: JsonValueCodec]: Arguments[A]
  def command[C, I: ToolInput, O: ToolOutput](handle: CommandHandle[C, I, O], id: String): McpTool
  def command[C, I: SchemaType, O: ToolOutput](handle: CommandHandle[C, I, O], id: String, input: String): McpTool
  def command[C, O: ToolOutput](handle: NoArgHandle[C, O], id: String): McpTool
  def query …                                                                   // the same three
  def route(endpoint: HttpEndpoint, method: String, template: String): McpTool
final class Params1[A]:  def param[B: SchemaType](…): Params2[A, B];  def handle[R: ToolOutput](f: A => R): McpTool
final class Arguments[A]: def handle[R: ToolOutput](f: A => R): McpTool
```

`ToolInput[I]`: derived for a product type (`inline given derived[A](using Mirror.ProductOf[A],
JsonValueCodec[A])`); no instance for a scalar, so a scalar input must be named with the `input`
overload. `ToolOutput` is the agent module's, with a new instance for `Done` ("done").

### `McpTool`

```scala
final class McpTool private[mcp] (val spec: ToolSpec, val acl: Option[Acl], private[ankka] val kind: ToolKind,
                                  private[ankka] val run: ToolCall => ToolAnswer)
final case class ToolCall(arguments: Json, context: RequestContext)
final case class ToolAnswer(text: String, structured: Option[Json] = None)
```

### What fails the start (`problems`, folded into `invalid ankka http configuration`)

Each names the MCP endpoint by path and, where there is one, the tool:

- `MCP endpoint /mcp declares no tool`
- `MCP endpoint /mcp: tool 'Get Cart' is not a tool name ([a-z0-9_-]+, at most 128 characters)`
- `MCP endpoint /mcp declares the tool 'get_cart' 2 times`
- `MCP endpoint /mcp: tool 'get_cart' has no description`
- `MCP endpoint /mcp: tool 'book_job' refers to the command 'vanish' of 'job', which has no such handler`
- `MCP endpoint /mcp: tool 'book_job' refers to the component 'job', which this service does not register`
- `MCP endpoint /mcp: tool 'todays_jobs' refers to the route 'GET /nowhere' of '/jobs', which has no such route`
- `MCP endpoint /mcp and the route 'POST /mcp' of '/' claim one path`
- `MCP endpoint /mcp declares routes; an MCP endpoint declares tools`
- `MCP endpoint /mcp states no acl` (the existing null-acl refusal, worded for the kind)
- `MCP endpoint /mcp admits client keys but the service has no McpRuntime; add McpRuntime() to the service`
- `MCP endpoint /mcp opts into brokered sign-in but admits no token; add Oidc.authenticate() to its acl`
- `MCP endpoint /mcp opts into brokered sign-in but the issuer 'customers' has no client id; set ANKKA_AUTH_CUSTOMERS_CLIENT_ID`
- `MCP endpoint /mcp path is reserved` (`/_ankka…`, `/.well-known…`)

## Client keys

```scala
// in ankka-http
trait ClientKeys:
  def issue(subject: String, expiry: Expiry, endpoint: Option[String] = None, scope: Set[String] = Set.empty,
            attributes: Map[String, String] = Map.empty, label: Option[String] = None): IssuedClientKey
  def list(): Vector[ClientKeySummary]
  def revoke(id: String): Unit                   // idempotent; unknown id is NotFound
enum Expiry:
  case After(duration: FiniteDuration)
  case At(instant: Instant)
  case Never
final case class IssuedClientKey(id: String, clientKey: String)   // the secret, shown once
final case class ClientKeySummary(id: String, subject: String, attributes: Map[String, String], label: Option[String],
                                  endpoint: String, scope: Set[String], issuedAt: Instant, expiresAt: Option[Instant],
                                  lastUsed: Option[Instant], revokedAt: Option[Instant])

// EndpointClients gains
val clientKeys: ClientKeys                       // refuses with "add McpRuntime()" when none is registered

// in ankka-mcp
object ClientKeys:
  def authenticate(): Acl                        // Acl.Authenticate(ClientKeyAuthenticator), bound at start
```

Refusals from `issue` are `Rejection(CommandError(BadRequest, …))` naming what is wrong: `the
subject is blank`, `the expiry is required: After, At or Never`, `the expiry 2026-10-18T… is in the
past`, `17 attributes; at most 16`, `the attribute 'x…' is 257 characters; at most 256`, `the tool
'empty_cart' is not a tool of /mcp`, `the service has MCP endpoints /mcp and /office; name one`.
`list` and `revoke` need the index caught up and answer `Unavailable` before that.

A handler reads a key's principal as any: `principal.subject`, `principal.claims.get("person")`,
`principal.claims("client_key")` for the id.

## `McpRuntime`

```scala
final class McpRuntime private (clock: Clock, lastUseInterval: FiniteDuration) extends RuntimeExtension with ClientKeysProvider:
  val name = "mcp"
object McpRuntime:
  def apply(): McpRuntime
  def descriptors: Seq[ComponentDescriptor]      // ankka-client-key, ankka-sign-in-record, ankka-sign-in-keys, ankka-sign-in-expiry
```

Starts the `ClientKeyIndex` (and the sign-in key cache fed by the same follower), the last-use
sweep and, when any mounted MCP endpoint is brokered, the key rotation. Its readiness is the index's.
A service that registers `McpRuntime` without `McpRuntime.descriptors` is warned at start, as
`AgentRuntime` warns.

## `http` additions (generic)

```scala
object Acl: def anyOf(first: Acl, rest: Acl*): Acl       // Acl.AnyOf(Vector)
trait TokenAuthenticator extends (RequestContext => AuthDecision): def authorizationServers: Vector[String]
trait NeedsService: def bind(service: AnkkaService): Either[String, Unit]   // an authenticator bound at start
private[ankka] trait HttpEndpoint: def rootRoutes: Vector[Route]; def problems(mounting: Mounting): Vector[String]
private[ankka] final case class Mounting(registry: ComponentRegistry, endpoints: Vector[HttpEndpoint], service: AnkkaService)
object OpaqueCredentials: final class OpaqueCredentials(prefix: String) { mint; parse; digest; matches; looksLikeOne }
```

`Oidc.authenticate(...)` in `auth-oidc` returns an `Acl.Authenticate` whose function is a
`TokenAuthenticator`; nothing else about it changes.

## Testing a Scala MCP endpoint

`AnkkaTestKit` is enough: start the service with the endpoint on `HttpServer.at("127.0.0.1", 0)`,
drive it with `McpClient("cart", McpTransport.Url(s"${kit.httpUrl}/mcp", …), headers, "test")` from
`ankka-agent`, or with the kit's `http` helper for the raw protocol. `kit.clientKeys` issues a key
from a test as a handler would. Documentation shows this under "Test it".
