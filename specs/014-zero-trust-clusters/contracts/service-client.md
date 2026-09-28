# Contract: `ServiceClient` (Scala SDK, story 3)

## `modules/sdk`

```scala
trait ServiceClient:
  /** Blocking; parks the virtual thread like ComponentClient.invoke. */
  def get[R](path: String, headers: Seq[(String, String)] = Nil)(using JsonValueCodec[R]): R
  def post[B, R](path: String, body: B, headers: Seq[(String, String)] = Nil)(using JsonValueCodec[B], JsonValueCodec[R]): R
  def put[B, R](...): R
  def delete[R](path: String, headers: Seq[(String, String)] = Nil)(using JsonValueCodec[R]): R
  def request(method: String, path: String, body: Option[Array[Byte]], contentType: Option[String], headers: Seq[(String, String)]): ServiceResponse

final case class ServiceResponse(status: Int, contentType: String, body: Array[Byte], headers: Vector[(String, String)])

/** Thrown for a non-2xx answer; carries the callee's status and body verbatim. */
final case class ServiceCallFailed(service: String, status: Int, body: String) extends RuntimeException

/** Thrown before any request is sent. */
final case class ServiceUnresolvable(service: String, reason: String) extends RuntimeException
final case class ServiceIdentityMismatch(service: String, presented: String) extends RuntimeException

trait ServiceClients:
  def apply(name: String): ServiceClient                       // this project
  def apply(project: String, name: String): ServiceClient      // another project
```

## Where it is reachable

- `EndpointClients.services: ServiceClients` (beside `componentClient`, `viewClient`).
- `AnkkaService.services: ServiceClients` for components constructed by their companions.
- Primitives cross as `text/plain`, records as JSON — the encoding rules the sidecar protocol
  already states apply to bodies here too.

## Resolution

| Mode | Address | Port | Transport |
|---|---|---|---|
| Kubernetes | `<name>.<ANKKA_NAMESPACE_PREFIX>-<project>.svc.cluster.local` | SRV `_http._tcp.<that name>` via pekko-discovery DNS, cached for the record's TTL | mutual TLS: presents `/var/run/secrets/ankka/service`, trusts its `ca.crt`, requires the callee's DNS SAN to match the address **and** its URI SAN to be `ankka://<project>/<name>` (else `ServiceIdentityMismatch`) |
| local | the observability address of the entry named `<name>` in `ServiceRegistration`'s directory, asked for its HTTP bound address | from that answer | plain HTTP; `project` is ignored and logged once at DEBUG |

`ServiceUnresolvable` names what was tried (the SRV name, or the registry directory and the entry
looked for).

## Guarantees

- `ANKKA_NAMESPACE_PREFIX` is set by the operator on every workload and refused in a descriptor.
- No `Location` following, no retries: a caller decides both.
- Request headers pass through unchanged; `Host` and TLS-related headers cannot be overridden.
- Idle connections are pooled per callee; a rotated client certificate is used for new
  connections within one minute and open connections are left alone.
