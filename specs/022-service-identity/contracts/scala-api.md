# Contract: the Scala API

Package `com.thinkmorestupidless.ankka.auth.oidc`, artifact `com.thinkmorestupidless:ankka-auth-oidc_3`.

## Declaring an authenticated endpoint

```scala
import com.thinkmorestupidless.ankka.auth.oidc.Oidc
import com.thinkmorestupidless.ankka.http.*

final class AccountEndpoint extends HttpEndpoint("/account"):
  val acl: Acl = Oidc.authenticate()          // OidcConfig.fromEnv(sys.env), or throws naming the variable

  get("/me")(() => s"${principal.subject} from ${principal.issuer.getOrElse("?")}")
```

`Oidc.authenticate()` with no argument reads the environment once and fails at construction,
with every problem, when the set is malformed or empty: a service does not start with an
authenticated route it cannot serve. `Oidc.authenticate(config: OidcConfig)` takes a configuration
built any other way. Both return `Acl.Authenticate(decide)`, so `withAcl` and route ACLs work as
for any ACL.

## The surface

```scala
object Oidc:
  def authenticate(): Acl
  def authenticate(config: OidcConfig): Acl
  def verifier(config: OidcConfig): OidcVerifier
  def principal(claims: JWTClaimsSet, issuer: Issuer): Principal

final case class Issuer(name: String, issuer: String, jwksUrl: String, audience: String,
                        ca: Option[java.nio.file.Path] = None, typ: Option[String] = None,
                        clockSkew: FiniteDuration = 60.seconds)

final case class OidcConfig(issuers: Vector[Issuer], realm: String = "ankka")
object OidcConfig:
  val empty: OidcConfig
  def fromEnv(env: Map[String, String] = sys.env): Either[Vector[String], OidcConfig]
  def problems(config: OidcConfig): Vector[String]     // duplicate names, empty strings

enum Verification:
  case Verified(claims: JWTClaimsSet, issuer: Issuer)
  case Rejected(reason: String)
  case Unavailable(reason: String)

final class OidcVerifier(config: OidcConfig, keys: Issuer => JWKSource[SecurityContext]):
  def verify(token: String): Verification
object OidcVerifier:
  val Asymmetric: Set[JWSAlgorithm]
  def remote(config: OidcConfig): OidcVerifier           // production key sources
  def keySource(issuer: Issuer, minTimeBetweenFetches: FiniteDuration): JWKSource[SecurityContext]
```

## Decisions and their responses

Unchanged from `ankka-http`: `Allow` proceeds with the principal; `Unauthenticated(challenge)` is
401 with `WWW-Authenticate: Bearer <challenge>`; `Unavailable(reason)` is 503 with `Retry-After: 5`.
The challenge is `realm="<realm>"` for a missing token and
`realm="<realm>", error="invalid_token", error_description="<reason>"` for a rejected one, the
reason quoted and truncated as the control plane does.

## `ankka-http`'s change

`Principal` gains `issuer: Option[String] = None`. Nothing else in `ankka-http` changes.

## Test support (`ankka-auth-oidc`'s test sources, shared with the control plane's tests)

```scala
final class TestIssuer(val issuer: String, val name: String = "test"):
  def config(audience: String, typ: Option[String] = None, ca: Option[Path] = None): Issuer
  def token(subject: String, audience: String, roles: Set[String] = Set.empty,
            claims: Map[String, Any] = Map.empty, expiresIn: FiniteDuration = 5.minutes,
            notBefore: Option[Instant] = None, typ: Option[String] = Some("Bearer"),
            kid: Option[String] = None): String
  def tokenSignedWithSecret(...): String      // HMAC, to be refused
  def rotate(): Unit; def goOffline(): Unit; def comeBack(): Unit; def delayResponses(by: FiniteDuration): Unit
  val fetches: AtomicInteger
  def close(): Unit
```

## What the control plane keeps

`AuthConfig` and every `ANKKA_AUTH_ISSUER`, `ANKKA_AUTH_JWKS_URL`, `ANKKA_AUTH_JWKS_CA` reading;
`AuthConfig.toOidc` is new. `ControlPlaneAcl.oidc(config: AuthConfig): Acl` and `composite`
keep their signatures. `Principals.PlatformAdmin`, `isPlatformAdmin`, `display` stay.
