# Data Model: Deploy from GitHub Actions

**Feature**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md) | **Research**: [research.md](./research.md)

Four places hold data in this feature: the control plane's journal (the token entity and one new
project event), each control-plane node's memory (the token index), the cluster (the pull Secret and one
field on the resource), and the action's inputs. Nothing in the descriptor changes. Field-level detail
of what crosses the wire is in [contracts/](./contracts/).

## The deploy token (control plane, event sourced)

**Entity** `deploy-token`, entity id = the token's id (16 lowercase hex characters).

| field | type | notes |
|---|---|---|
| `id` | `String` | the entity id; also the subject's suffix (`token:<id>`) |
| `organizationId` | `String` | the organization the token was created in; never changes |
| `label` | `String` | for people; not unique; becomes `Member.display` and the actor's display |
| `digest` | `String` | SHA-256 of the secret part, lowercase hex; the only form the secret is ever stored in |
| `createdBy` | `Actor` | the owner who created it |
| `createdAt` | `Instant` | |
| `expiresAt` | `Option[Instant]` | `None` only when the creator asked for a token that never expires; otherwise `createdAt + lifetime`, 90 days by default |
| `lastUsed` | `Option[LocalDate]` | the date, never the instant (clarification Q3); `None` until first use |
| `revoked` | `Boolean` | a tombstone; the id is not reused |

Derived: `exists = !revoked && organizationId.nonEmpty`; `expired(now) = expiresAt.exists(_.isBefore(now))`;
`subject = s"token:$id"`.

**Events** (`DeployTokenEvent`), every command-produced one carrying `actor` and `at`:

| event | fields | fold |
|---|---|---|
| `DeployTokenCreated` | `organizationId, label, digest, expiresAt, actor, at` | sets every field above; `createdBy = actor` |
| `DeployTokenUsed` | `date: LocalDate` | `lastUsed = Some(date)`; carries no actor — recorded by the platform's own task |
| `DeployTokenRevoked` | `actor, at` | `revoked = true`; the digest is kept for the audit trail, and is useless without the secret |

**Commands**:

| command | refuses when | reply |
|---|---|---|
| `create(CreateToken(organizationId, label, digest, expiresAt))` | already exists, or was revoked (the id is not reused) | `Done` |
| `recordUse(date)` | not exists; or `date` not after `lastUsed` (so two nodes on one day produce one event) | `Done`, or a no-op reply when the date is not later |
| `revoke` | not exists (a second revoke is a `404`, like removing a member twice) | `Done` |
| `get` | not exists | `DeployTokenDetail` — everything but the digest |

`LocalDate` crosses the journal as an ISO string with an explicit codec in the event's companion, for
the same reason `ServiceLifecycle` has one: a fieldless shape under the shared codec config is a wrapper
object, and a date in a listing wants to be `2026-09-25`.

**View** `deploy-token-rows`, one row per **live** token:

| field | notes |
|---|---|
| `id`, `organizationId`, `label`, `createdBy` (display), `createdAt`, `expiresAt`, `lastUsed` | from the events; `lastUsed` updated by `DeployTokenUsed` |

`DeployTokenRevoked` deletes the row. Queried by `organizationId` for `GET /organizations/{id}/tokens`.
The digest is never in a row.

**Relationship to the organization**: the token's `subject` is a key in `Organization.members` with
`Member(role = Role.Member, display = Some(label), since = createdAt, addedBy = creator's display)`,
written by the existing `MemberAdded` event. The organization does not know it is a token beyond the
subject's `token:` prefix, and does not need to. Removing it is the existing `MemberRemoved`.

## The token index (each control-plane node, in memory)

A `ConcurrentHashMap[String, Live]` keyed by token id, held by the `DeployTokenIndex` extension:

| field of `Live` | type | source |
|---|---|---|
| `digest` | `String` | `DeployTokenCreated` |
| `organizationId` | `String` | `DeployTokenCreated` |
| `label` | `String` | `DeployTokenCreated` |
| `expiresAt` | `Option[Instant]` | `DeployTokenCreated` |
| `persistedLastUsed` | `Option[LocalDate]` | `DeployTokenUsed`, as the stream delivers it |
| `touched` | `AtomicReference[Option[LocalDate]]` | `index.touch(id)` on every successful verification; local to this node |

Fold: `Created` puts; `Used` updates `persistedLastUsed`; `Revoked` removes. `caughtUp: Boolean` flips
once the startup replay reaches the journal's head (research V1) and is what `readiness` reports. The
last-use task, once a minute: for every entry where `touched > persistedLastUsed`, send `recordUse`;
the entity's own guard makes a lost race harmless.

**State transitions of a token as the ACL sees it**: absent → `Unauthenticated`; present and
`expiresAt < now` → `Unauthenticated` (expired); present → `Allow`, then `touch`. There is no
"revoked" state in the map, because a revoked token is removed.

## The project's registry (control plane, event sourced)

`Project` gains one field:

| field | type | notes |
|---|---|---|
| `registry` | `Option[RegistryRef]` | `RegistryRef(server, username, secretName, setBy: Actor, setAt: Instant)` — **no password** |

**Events** (`ProjectEvent`): `RegistryConfigured(server, username, actor, at)` sets it with
`secretName = "ankka-registry"`; `RegistryCleared(actor, at)` sets `None`. Both default `actor` and
`at` to `None` so earlier journals decode, as every event here does.

**Commands**: `configureRegistry(server, username)` and `clearRegistry`, both refusing when the project
does not exist. The endpoint writes the Kubernetes Secret *before* issuing `configureRegistry`, so the
journal never claims a credential that was not written (research R7).

`ProjectDetail` (wire) gains `registry: Option[RegistrySummary(server, username, setAt, setBy)]`; the
view row carries the same, so `projects list` and `projects get` both show it.

## The cluster

**The Secret**: `ankka-registry` in the project's namespace, type `kubernetes.io/dockerconfigjson`,
data `.dockerconfigjson` = the standard `{"auths": {"<server>": {"username", "password", "auth"}}}`
document, base64 by Kubernetes. Labelled `app.kubernetes.io/managed-by: ankka`. Written by the control
plane with server-side apply as field manager `ankka-controlplane`; never read by it.

**The resource**: `AnkkaServiceSpec` gains

| field | type | default | rendered as |
|---|---|---|---|
| `imagePullSecret` | `Option[String]` | `None` | `pod.spec.imagePullSecrets: [{name}]` on both pod shapes when present; nothing when absent |

`None` on a resource written before this feature, so it renders exactly as it did (FR-027). The
control plane sets it from the project's `registry` at projection time; the operator does not know what
a project is and does not need to.

## The action's inputs (GitHub)

| input | required | default | becomes |
|---|---|---|---|
| `version` | no | `0.0.0` in the tree, the release version once published | which `ankka-cli-<version>.zip` is fetched |
| `url` | yes | | `ANKKA_URL` for the rest of the job |
| `token` | yes | | `ANKKA_TOKEN`, masked, for the rest of the job |
| `project` | no | | `ANKKA_PROJECT` when given |
| `ca` | no | | `$RUNNER_TEMP/ankka-ca.crt` and `ANKKA_CA` when given |

Nothing is written into the checkout; everything lives in `RUNNER_TEMP`, `GITHUB_PATH` and
`GITHUB_ENV`, which end with the job.

## Validation rules, collected

| what | rule | where |
|---|---|---|
| token label | non-empty, at most 100 characters, one line | endpoint and CLI |
| token lifetime | `--expires-in <duration>` a positive duration up to 365 days, or `--never-expires`; absent means 90 days | CLI parses, endpoint applies from its clock |
| presented credential | `ankka_` + 16 hex + `_` + 64 hex, else it is not a deploy token and goes to OIDC | `ControlPlaneAcl.composite` |
| registry server | non-empty, no scheme (`ghcr.io`, not `https://ghcr.io`) | endpoint and CLI |
| registry username / password | non-empty | endpoint and CLI |
| `services deploy` | `descriptor.name == <service>`; the image non-empty; then `ServiceDescriptor.problems` | CLI, before the request |
| `imagePullSecret` | when present, a DNS label | operator, refusing the resource as it refuses a bad service name |
