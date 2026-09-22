# Contract: the additive change to `modules/http`

`ankka-http` is published. This is the whole of what changes in it, and each item is additive.

## `Principal`

```scala
final case class Principal(
    subject: String,
    name: Option[String],
    email: Option[String],
    emailVerified: Boolean,
    roles: Set[String],
    claims: Map[String, String]
)
```

Plain data. `http` never constructs one; an `Acl.Authenticate` does.

## `AuthDecision` and `Acl.Authenticate`

```scala
enum AuthDecision:
  case Allow(principal: Principal)
  case Unauthenticated(challenge: String)   // rendered into WWW-Authenticate
  case Forbidden(reason: String)
  case Unavailable(reason: String)          // the verifier cannot decide right now

enum Acl:
  case DenyAll
  case AllowAll
  case AllowIf(predicate: RequestContext => Boolean)
  case Authenticate(decide: RequestContext => AuthDecision)   // NEW
```

The three existing cases keep their exact behaviour: `AllowIf` still answers 403 on false.

## `RequestContext.principal`

```scala
trait RequestContext:
  ...
  def principal: Option[Principal]   // NEW; None for every ACL other than Authenticate
```

`HttpEndpoint` gains `protected def principal: Principal`, which throws `IllegalStateException`
if absent — an endpoint whose ACL is not `Authenticate` has no business calling it, and the
mistake should fail on the first request in a test, not return `None` into a permission check.

## Server behaviour

| decision | status | headers | body |
|---|---|---|---|
| `Allow(p)` | dispatch; `request.principal == Some(p)` on the handler's thread | | |
| `Unauthenticated(c)` | 401 | `WWW-Authenticate: Bearer <c>` | `{"error":"authentication required"}` in the existing problem shape |
| `Forbidden(r)` | 403 | | problem with `r` |
| `Unavailable(r)` | 503 | `Retry-After: 5` | problem with `r` |

`/_ankka/health` stays exempt, as today. The principal is put on the same `RequestContext` the
handler sees, so the `ThreadLocal` rule in `CLAUDE.md` holds unchanged: work handed to another
thread cannot see it, and a handler reads it before fanning out.

## What does not change

No dependency. No change to `EndpointClients`, `HttpServer.of`/`.at`, route declaration, SSE,
`HttpProblem`, or the health route. `Acl.AllowIf` is not deprecated: it is right for an endpoint
that decides on something other than identity.
