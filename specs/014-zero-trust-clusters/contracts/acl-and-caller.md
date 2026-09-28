# Contract: caller identity and caller-naming ACLs (all three SDKs)

## Scala (`modules/http`)

```scala
enum Caller:
  case Gateway
  case Service(project: String, name: String)
  case Local

enum CallerMatcher:
  case Internet
  case NamedService(project: Option[String], name: String)
  case AnyInProject
  case Self

object Callers:
  val internet: CallerMatcher = CallerMatcher.Internet
  def service(name: String): CallerMatcher
  def service(project: String, name: String): CallerMatcher
  val anyInProject: CallerMatcher = CallerMatcher.AnyInProject
  val self: CallerMatcher = CallerMatcher.Self

enum Acl:
  case DenyAll; case AllowAll
  case AllowIf(predicate: RequestContext => Boolean)
  case Authenticate(decide: RequestContext => AuthDecision)
  case AllowCallers(matchers: Vector[CallerMatcher])      // NEW
object Acl:
  def allowCallers(first: CallerMatcher, rest: CallerMatcher*): Acl    // non-empty by construction

trait RequestContext:
  def caller: Caller                                          // NEW, total
  def principal: Option[Principal]                            // unchanged

abstract class HttpEndpoint:
  protected def caller: Caller = request.caller               // NEW convenience
```

Rules:

1. `caller` is set before the ACL runs and is visible to `AllowIf` predicates, `Authenticate`
   deciders and handlers, on the handler's thread.
2. `AllowCallers` admits `Local` always; `Gateway` iff `Internet` is listed; `Service(p, n)` iff
   `NamedService(Some(p), n)`, or `NamedService(None, n)` with `p == own project`, or
   `AnyInProject` with `p == own project`, or `Self` with `(p, n) == own identity`.
3. Refusal is `HttpProblem.forbidden("not permitted by this endpoint's acl")` — the same text as
   today's refusals, disclosing nothing.
4. Under TLS, a peer certificate without an `ankka://` URI SAN is refused 403 before routing with
   `unrecognised caller certificate`; the recorder marks it a refusal.
5. A route may state `AllowCallers` through `withAcl` exactly as any other ACL.
6. Startup, local mode, when any endpoint or route uses `AllowCallers`: one INFO line
   `caller identity is not enforced outside a cluster: every request is Caller.Local`.

## Testkit (`modules/testkit`)

```scala
final class AnkkaTestKit:
  def httpAs(caller: Caller): HttpClientLike   // requests carry X-Ankka-Local-Caller: <token> <caller>
```

Encoding of `<caller>` in the header: `gateway` | `service:<project>/<name>` | `local`. The token is
the server's per-process random value; a request with a wrong token is `Local`, not an error.

## Python (`ankka.endpoint`)

```python
class Acl:
    ALLOW_ALL: Acl; DENY_ALL: Acl; AUTHENTICATED: Acl          # existing names keep working
    @staticmethod
    def allow_callers(*matchers: CallerMatcher) -> Acl: ...     # at least one

class Callers:
    internet: CallerMatcher
    @staticmethod
    def service(name: str, *, project: str | None = None) -> CallerMatcher: ...
    any_in_project: CallerMatcher
    self_: CallerMatcher

@dataclass(frozen=True) class Gateway: ...
@dataclass(frozen=True) class ServiceCaller: project: str; name: str
@dataclass(frozen=True) class LocalCaller: ...
Caller = Gateway | ServiceCaller | LocalCaller

# in a handler
request.caller  # -> Caller
```

`Acl` is no longer an `Enum`; `Acl.ALLOW_ALL` etc. remain as class attributes and compare by
identity, so `acl = Acl.ALLOW_ALL` and `@get("/x", acl=Acl.DENY_ALL)` are unchanged.

## TypeScript (`endpoint.ts`, `routes.ts`)

```ts
export type Caller =
  | { kind: "gateway" }
  | { kind: "service"; project: string; name: string }
  | { kind: "local" }

export const Callers = {
  internet: CallerMatcher,
  service(name: string, opts?: { project?: string }): CallerMatcher,
  anyInProject: CallerMatcher,
  self: CallerMatcher,
}
export const Acl = { allowAll, denyAll, authenticated, allowCallers(...matchers: CallerMatcher[]): Acl }

// in a handler
ctx.request.caller  // Caller
```

## Conformance cases (`ConformanceSuite`, all three targets)

- `ep.caller-local`: with no token, `request.caller` is local and an `allow_callers` route serves.
- `ep.caller-gateway`: with the token and `gateway`, an `allow_callers(internet)` route serves and
  an `allow_callers(service("x"))` route answers 403.
- `ep.caller-service`: with `service:p/x`, `service("x", project="p")` serves; `service("y")` 403.
- `ep.caller-and-principal`: an `AUTHENTICATED` endpoint with an `allow_callers` route: the handler
  sees both.
- `ep.caller-in-stream`: an SSE route under `allow_callers` refuses before the stream opens.
