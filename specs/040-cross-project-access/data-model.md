# Data Model: Cross-Project Access

## Grantee and target (`controlplane-api`, shared by the CLI and the control plane)

```scala
enum Grantee:
  case Service(project: String, name: String)        // "service:<project>/<name>"
  case Machine(organization: String, name: String)   // "machine:<organization>/<name>"

enum GrantTarget:
  case Route(service: String, method: String, path: String)   // method upper-case; path a template
  case Method(service: String, method: String)                // "<Service>/<Method>", gRPC full-name form
  case Topic(name: String, right: TopicRight, decrypt: Boolean = false)   // decrypt only with Consume
  case Erasure                                                 // the project's erasure right (feature 042)

enum TopicRight { case Consume, Produce }
```

- Text forms: a grantee is parsed from `service:p/n` or `machine:o/n`; a target from `route <SERVICE>
  <METHOD> <PATH>`, `method <SERVICE> <Svc/Method>`, `topic <NAME> consume|produce [decrypt]`,
  `erasure` (the CLI's words; the wire carries the structured form).
- Rules (`GrantRules.problems`): a project id under `ProjectId.problems`; a service or machine name
  under the service name rule; an organization id under the organization id rule; a method in the
  HTTP method set; a path template that is `/`-rooted; a topic name under the topic name rule;
  `decrypt` only with `Consume`. Both ends apply them.

## Grant (control plane, `Project.grants`)

```scala
enum GrantState { case Pending, Accepted, Declined, Withdrawn, Revoked, Relinquished, Lapsed }

final case class Grant(
    id: String,                       // 16 hex characters, minted by the endpoint
    grantee: Grantee,
    target: GrantTarget,
    state: GrantState,
    granted: Attribution,             // who made it, when
    answered: Option[Attribution] = None,   // accept or decline
    ended: Option[Attribution] = None)      // withdraw, revoke, relinquish, lapse
```

- Live: `Pending` or `Accepted`. One live grant per `(grantee, target)`; `make-grant` for a live pair
  replies the existing grant and persists nothing.
- Transitions: `Pending → Accepted | Declined | Withdrawn | Lapsed`; `Accepted → Revoked |
  Relinquished | Lapsed`. Any other is `Conflict`. A grant within the granting project's own
  organization starts `Accepted`.
- Refusals at `make-grant`: a topic the project has not declared (`NotFound`, naming the topic);
  a grantee service of the project itself (`InvalidArgument`); `decrypt` on `Produce`.
- Events (`ProjectEvent`): `GrantMade(id, grantee, target, pending: Boolean, actor, at)`,
  `GrantAccepted(id, actor, at)`, `GrantDeclined`, `GrantWithdrawn`, `GrantRevoked`,
  `GrantRelinquished`, `GrantLapsed`. No event holds a credential.
- Queries: `grants` → `Vector[Grant]`; `grant(id)`.

## Received grant (control plane, `Project.received` and `Organization.received`)

```scala
enum GrantChange { case Made, Offered, Accepted, Declined, Withdrawn, Revoked, Relinquished, Lapsed }

final case class ReceivedGrant(
    id: String, grantingProject: String, grantingOrganization: String,
    grantee: Grantee, target: GrantTarget, state: GrantState,
    changes: Vector[(GrantChange, Attribution)])
```

- Written only by `record-grant-change(id, grantingProject, grantingOrganization, grantee, target,
  change, actor, at)` from the `GrantMirror` consumer; a `(id, change)` already recorded is
  `Conflict`, which the consumer ignores. Event `GrantRecorded(…)` on each entity.
- Query `received-grants` → `Vector[ReceivedGrant]`; the entity's `history` shows each record.

## Machine (control plane, `MachineEntity`, id `<organization>/<name>`)

```scala
final case class ByteRates(produceBytesPerSecond: Long, consumeBytesPerSecond: Long, requestPercentage: Int)

final case class Machine(
    organizationId: String, name: String,
    digest: String,                   // SHA-256 hex of the client secret
    registeredBy: Attribution,
    byteRates: Option[ByteRates] = None,   // None: the installation's defaults
    deleted: Boolean = false)
```

- Client id `machine:<organization>/<name>`; the secret is 64 hex characters from 256 random bits,
  shown once in `MachineRegistered`'s reply and never kept.
- Events (`MachineEvent`): `MachineRegistered(organizationId, name, digest, actor, at)`,
  `MachineByteRatesSet(produce, consume, requestPercentage, actor, at)`, `MachineDeleted(actor, at)`.
  `register` after a deletion is accepted: a new digest, a new `registeredBy`, `deleted = false`.
- Rules: a name under the service name rule; byte rates `> 0` and at most the installation's
  ceiling (`ANKKA_MACHINE_BYTE_RATE_CEILING`); `requestPercentage` 1–100.
- Queries: `get`; `check-secret(digest)` → `Some(machine)` only when the digest matches
  (`MessageDigest.isEqual`) and the machine is not deleted.
- View `MachineRows` (`machine-rows`): `MachineSummary(organizationId, name, registeredBy,
  registeredAt, byteRates)`; the row is deleted on `MachineDeleted`.

## Machine token

| Claim | Value |
|---|---|
| `iss` | the public control plane address (`https://api.<base>[:port]`; locally the configured issuer) |
| `sub` | `machine:<organization>/<name>` |
| `aud` | `["ankka"]` |
| `exp`, `iat`, `nbf` | `iat + 900`, now, now |
| `jti` | 16 random bytes, hex |
| `typ` | `Bearer` |
| `organization`, `machine` | the two parts of `sub` |
| `broker_user` | `machine.<organization>.<name>` |

Header: `alg: RS256`, `kid`, `typ: JWT`. The JWKS lists every key the control plane holds, `use:
sig`, `kid`, `n`, `e`.

## Signing keys (control plane, a Secret it creates)

`ankka-controlplane-machine-keys` in the control plane's namespace: one entry `<kid>.pem` (PKCS#8)
per key; `kid` is the key's creation time in `yyyyMMddHHmmss` plus 4 random hex characters, so the
newest sorts last. Mounted `optional: true` at `/var/run/ankka/machine-keys`; `MachineKeys` re-reads
the directory by mtime, signs with the newest, publishes all. Rotation: `patch` adds a key; the
sweep an hour later removes every key but the newest two. No key is ever in the journal.

## `AnkkaProject` (CRD, `spec.grants`)

```scala
final case class ProjectGrantEntry(
    id: String = "", grantee: String = "",              // "service:p/n" | "machine:o/n"
    kind: String = "",                                   // route | method | topic | erasure
    service: Option[String] = None, httpMethod: Option[String] = None, path: Option[String] = None,
    method: Option[String] = None,
    topic: Option[String] = None, right: Option[String] = None, decrypt: Boolean = false,
    grantedAt: String = "")
```

Only `Accepted` grants are projected. `ankkaproject.yaml` declares every field; `CrdSchemaSuite`
compares both ways.

## `AnkkaMachine` (CRD, cluster-scoped, name `<organization>.<name>`)

```scala
final case class AnkkaMachineSpec(organizationId: String = "", name: String = "",
    produceBytesPerSecond: Option[Long] = None, consumeBytesPerSecond: Option[Long] = None,
    requestPercentage: Option[Int] = None)
final case class AnkkaMachineStatus(user: Option[String] = None, phase: String = "", detail: Option[String] = None)
```

Written by the control plane on register and byte-rate changes, deleted on delete. The operator's
`MachineReconciler` renders the `KafkaUser` and writes `status.user` and `phase` (Waiting |
Provisioned | Failed, the broker phases).

## `AnkkaService.status.grants`

`grants: Option[String]` — `"mounted"` once the operator has applied a Deployment carrying the
`ankka-project` mount. Absent on an operator that predates the feature. The control plane reads it
into `ServiceStatus.grants`.

## `grants.json` (the file a service reads)

```json
{
  "project": "spinvibe",
  "grants": [
    {"id": "…", "grantee": "service:payments/merchant", "kind": "route", "service": "wallet",
     "httpMethod": "POST", "path": "/v1/wallets/{player}/{currency}/deposits"},
    {"id": "…", "grantee": "machine:eitheror/affiliate-network", "kind": "method", "service": "wallet",
     "method": "WalletService/Deposit"},
    {"id": "…", "grantee": "service:payments/merchant", "kind": "topic", "topic": "casino.players",
     "right": "consume", "decrypt": true},
    {"id": "…", "grantee": "service:payments/merchant", "kind": "erasure"}
  ]
}
```

The runtime keeps the entries whose `service` is its own (`ServiceIdentity`), indexed by
`(grantee, target)`; topic and erasure entries are kept for the keyring and read by nothing else.

## Runtime values (`modules/http`)

```scala
enum Caller { case Gateway; case Service(project, name); case Machine(organization, name); case Local }
enum CallerMatcher { case Internet; case NamedService(project: Option[String], name: String); case AnyInProject; case Self; case Granted }
enum GrantTarget { case Route(method: String, template: String); case Method(fullName: String) }
trait Grants { def admits(caller: Caller, target: GrantTarget): Boolean; def entries: Vector[GrantEntry] }
```

`CallerMatcher.admits(caller, self, target: Option[GrantTarget])`; `Granted` with no target (an
endpoint-level ACL on a non-route dispatch) admits only `Local`.

## Service status additions (`controlplane-api`)

```scala
final case class CrossProjectTopic(project: String, topic: String, right: String, state: String)   // state: granted | no grant | pending | ended
// ServiceStatus gains: grants: Option[String], crossProjectTopics: Option[Vector[CrossProjectTopic]]
// TopologyHandler gains: grantable: Option[Boolean]
```

## Wire types (`controlplane-api`, each with a `Wire` codec and a console fixture)

`GrantRequest(grantee, target)`, `GrantDetail(id, grantee, target, state, effect, granted,
answered, ended)`, `ReceivedGrantDetail(id, grantingProject, grantingOrganization, grantee,
target, state, changes, topic: Option[TopicSettings])`, `MachineRegistration(name)`,
`MachineRegistered(clientId, clientSecret, tokenUrl, brokerBootstrap: Option[String])`,
`MachineSummary(…)`, `ByteRatesRequest(produceBytesPerSecond, consumeBytesPerSecond,
requestPercentage)`, `TokenResponse(access_token, token_type, expires_in)`, `Jwks(keys)`.

## Protocol 1.15 (`protocol/`)

- `discovery.proto`: `CallerMatcher.kind += Empty granted = 5`; `Source.Topic += optional string
  project`; `Publication += optional string project`.
- `endpoint.proto`: `Caller.kind += MachineCaller machine = 4` with `organization`, `name`.
