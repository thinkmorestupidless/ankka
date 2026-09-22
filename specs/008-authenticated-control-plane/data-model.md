# Data Model: An Authenticated, Multi-User Control Plane

**Feature**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md) | **Research**: [research.md](./research.md)

Everything below is either plain data in `http`, a wire type in `controlplane-api`, or state and
events in `controlplane`. Nothing here touches `crd` or the operator.

## Principal (`modules/http`)

What an ACL establishes and a handler reads from `request.principal`. Plain data; `http` does not
know where it came from.

| field | type | source in this feature | notes |
|---|---|---|---|
| `subject` | `String` | token `sub` | the only field ever used as a key |
| `name` | `Option[String]` | `name` or `preferred_username` | display only |
| `email` | `Option[String]` | `email` | display, and invitation matching **only when verified** |
| `emailVerified` | `Boolean` | `email_verified`, default false | gates invitation claims |
| `roles` | `Set[String]` | `realm_access.roles` | `platform-admin` is the one the control plane reads |
| `claims` | `Map[String, String]` | anything else, stringified | for `whoami`; never for decisions |

`AuthDecision` (also `http`): `Allow(principal)` | `Unauthenticated(challenge: String)` |
`Forbidden(reason: String)` | `Unavailable(reason: String)`.

## Actor (`controlplane/domain`)

Attached to every command-produced event and every history entry.

| field | type | notes |
|---|---|---|
| `subject` | `String` | the principal's subject |
| `display` | `Option[String]` | email, else name, at the time — a label, may go stale |
| `administrative` | `Boolean` | true only when `platform-admin` was *needed* for the action (FR-012) |

## Organization (entity state)

```
Organization(
  id, name, deleted,                       -- unchanged
  members:     Map[Subject, Member],       -- NEW
  invitations: Map[Email, Invitation],     -- NEW, keyed by lower-cased email
  disabled:    Boolean = false             -- NEW
)
Member(role: Role, email: Option[String], display: Option[String], since: Instant, addedBy: Option[Actor])
Invitation(role: Role, invitedAt: Instant, invitedBy: Actor)
Role = Owner | Member
```

Derived: `exists`, `known` unchanged. `roleOf(subject): Option[Role]`. `ownerCount`.
`isLastOwner(subject)`.

**Invariants, enforced in command handlers** (the entity sees its own state only):

- creating records the creator as `Owner` (FR-013);
- `invite(email)` refused if the email is a member or already pending (FR-016);
- `removeMember` / `changeRole` refused if it would leave zero owners (FR-018);
- `claimInvitation(subject, email)` refused if no invitation for that email is pending; on
  success removes the invitation and adds a `Member` with its role;
- `disable` refused if already disabled or not existing; `enable` likewise;
- every mutating command on a disabled organization *other than* `enable`, `delete` and
  administrative membership repair is refused with `Conflict` naming the organization as disabled
  (FR-034) — the endpoint also checks, but the entity is the last line;
- `delete` unchanged in rule (endpoint checks projects) and additionally clears members and
  invitations in the fold (FR-019).

**Who may issue which command** is the endpoint's decision (contracts/http-api.md), not the
entity's: the entity does not know the caller's role — it is told the actor and records it.

### Organization events (`organization-event`)

All existing cases gain `actor: Option[Actor] = None, at: Option[Instant] = None`.

| event | fields | fold |
|---|---|---|
| `OrganizationCreated` | `name`, + `owner: Option[Actor]` | sets name; if `owner` present adds it as `Owner` (pre-feature events have none) |
| `OrganizationRenamed` | `name` | unchanged |
| `OrganizationDeleted` | | + clears `members`, `invitations` |
| `MemberInvited` | `email`, `role` | adds `Invitation` |
| `InvitationRevoked` | `email` | removes it |
| `InvitationClaimed` | `email`, `subject`, `display` | removes invitation, adds `Member` with its role |
| `MemberAdded` | `subject`, `role`, `email`, `display` | adds `Member` (administrative repair path) |
| `MemberRemoved` | `subject` | removes |
| `MemberRoleChanged` | `subject`, `role` | updates |
| `OrganizationDisabled` | | `disabled = true` |
| `OrganizationEnabled` | | `disabled = false` |

The last-owner rule is checked in the handler *and* the fold is written so that replaying a
journal that somehow violates it does not throw — the fold applies what happened; the handler
prevents it from happening again.

### Organization queries

| query | reply |
|---|---|
| `get` | `OrganizationDetail` (+ `disabled`) — refuses `NotFound` if not existing |
| `exists` | unchanged |
| `roleOf(subject)` | `MembershipAnswer(role: Option[Role], disabled: Boolean, exists: Boolean)` — one call answers the whole authorization question |
| `pendingFor(email)` | `Option[Role]` — the claim-on-refusal check (R9) |
| `members` | `Vector[MemberSummary]` + `Vector[InvitationSummary]` |

## Project (entity state and events)

State unchanged. Events gain `actor`/`at`. `ProjectCreated` additionally records nothing new —
the organization is already on it. All writes are refused by the endpoint while the organization
is disabled; the entity does not know.

## Service (entity state)

```
Service(
  ... unchanged ...,
  suspended: Boolean = false,                 -- NEW: desired, owned by the organization
  history:   Vector[HistoryEntry] = Vector()  -- NEW: last 50 command-produced changes
)
HistoryEntry(kind: String, generation: Long, actor: Option[Actor], at: Option[Instant])
```

- `targetInstances` = 0 if `paused || suspended`.
- `onSuspended`: `suspended = true`, `lifecycle = Suspended`, `desiredInstances = 0`.
- `onReinstated`: `suspended = false`, `lifecycle = if paused then Paused else UpdateInProgress`.
- `onApplied`, `onRestarted`, `onPaused`, `onResumed`, `onExposed`, `onUnexposed`, `onDeleted`
  each append a `HistoryEntry` and truncate to 50. `onObserved` does not.
- `onDeleted` also clears `suspended` (a re-applied name in an enabled organization starts
  running; in a disabled one the endpoint refuses the apply).
- `toStatus` adds `suspended: Boolean`; lifecycle `Suspended` is reported while suspended
  regardless of what the operator last observed, exactly as `Paused` is today.

### Service events (`service-event`)

Existing command-produced cases gain `actor`/`at`. New:

| event | fold |
|---|---|
| `ServiceSuspended` | `onSuspended` |
| `ServiceReinstated` | `onReinstated` |

`ServiceObserved` unchanged, no actor.

### Service commands and queries

New commands `suspend`, `reinstate` (idempotent: already in the target state → reply current
status, persist nothing). New query `history: Vector[HistoryEntry]`.

## `ServiceLifecycle` (wire, `controlplane-api`)

Gains `Suspended`. The companion's string codec covers it automatically; `byName` too. The CLI's
table prints it as a word like the others.

## View rows

### `organization-rows` (extended)

```
OrganizationRow(id, name, disabled: Boolean, members: Vector[Subject], invitations: Vector[Email])
```

Filled from the events above. `GET /organizations` filters by containment on `members`;
invitation claim-on-listing filters on `invitations` (R8, R9). The wire type
`OrganizationSummary` gains `disabled` and, for the caller, `role: Option[Role]`, and never
exposes the subject list.

### `project-rows`, `service-rows`

Unchanged. `service-rows`' `ServiceStatus` payload gains `suspended` and the new lifecycle word
through the existing serializer.

## Wire types (`controlplane-api`), new

| type | fields |
|---|---|
| `AuthDiscovery` | `issuer`, `clientId`, `audience` |
| `Whoami` | `subject`, `name?`, `email?`, `emailVerified`, `platformAdmin: Boolean`, `organizations: Vector[OrganizationMembership(id, name, role)]` |
| `Invite` | `email`, `role` |
| `RoleChange` | `role` |
| `MemberSummary` | `subject`, `role`, `email?`, `display?`, `since` |
| `InvitationSummary` | `email`, `role`, `invitedAt`, `invitedBy` |
| `MembersResponse` | `members`, `invitations` |
| `HistoryEntry` | `kind`, `generation`, `actor?` (`subject`, `display?`, `administrative`), `at?` |
| `Role` | enum `owner`/`member`, string codec in its companion (the `ServiceLifecycle` rule) |

`OrganizationSummary` gains `disabled: Boolean = false` and `role: Option[Role] = None`.
`ServiceStatus` gains `suspended: Boolean = false`. Defaults keep an older CLI reading a newer
control plane's JSON.

## CLI credentials file

`<config dir>/credentials.json`, mode `0600`, created with `Files.createFile` and
`PosixFilePermissions` where the filesystem supports it.

```
{ "<control plane url>": { "issuer", "clientId", "refreshToken", "accessToken", "expiresAt" } }
```

Never printed. `config get` does not show it. `logout` removes the entry for the current URL;
`logout --all` empties the file.

## Configuration (`controlplane/reference.conf`)

```
ankka.controlplane.auth {
  issuer    = ""   issuer    = ${?ANKKA_AUTH_ISSUER}     # required; startup fails if empty
  jwks-url  = ""   jwks-url  = ${?ANKKA_AUTH_JWKS_URL}   # defaults to <issuer>/protocol/openid-connect/certs
  audience  = "ankka-controlplane"
  client-id = "ankka-cli"                                 # what GET /auth advertises
  realm-hint = "ankka"                                    # the WWW-Authenticate realm
  clock-skew = 60s
}
```

`auth.token` and `ANKKA_CONTROLPLANE_TOKEN` are removed.

## State transitions worth drawing

```
Organization.disabled:   false --disable (admin)--> true --enable (admin)--> false
Service (desired):       running --suspend--> suspended --reinstate--> (paused ? paused : running)
                         paused  --suspend--> suspended+paused --reinstate--> paused
Invitation:              pending --claim (verified email matches)--> Member(role)
                         pending --revoke (owner)--> gone
Member:                  member <--role change (owner, not last owner)--> owner
                         any    --remove (owner, not last owner)--> gone
```
