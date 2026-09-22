# Contract: the control plane's HTTP API

Base: unchanged (`https://api.<base>[:port]`). Every route below except `GET /auth` and
`/_ankka/health` requires `Authorization: Bearer <access token>` issued by the installation's
Keycloak for audience `ankka-controlplane`.

## Authentication semantics

| condition | status | body / headers |
|---|---|---|
| no `Authorization` header, or not `Bearer` | 401 | `WWW-Authenticate: Bearer realm="ankka"` |
| token expired, bad signature, wrong `iss`, `aud` missing `ankka-controlplane`, wrong `typ`, unknown `kid` after one refresh, `alg` not asymmetric | 401 | `WWW-Authenticate: Bearer realm="ankka", error="invalid_token", error_description="<reason>"` |
| keys cannot be fetched and none are cached | 503 | `Retry-After: 5`; message names the issuer |
| valid token, action not permitted | 403 | message says what role the action needs |
| valid token, organization/project/service the caller cannot see | 404 | the same message as for an id that was never used |
| valid token, write to a disabled organization | 409 | `organization '<id>' is disabled` |

`error_description` never echoes the token.

## Roles required, per route

Legend: **A** any authenticated user · **M** member or owner of the organization that owns the
target · **O** owner · **P** platform admin only · *(P bypasses M and O everywhere, recorded as
administrative)*. "disabled → 409" marks writes refused while the organization is disabled.

### `/auth` (new)

| route | who | reply |
|---|---|---|
| `GET /auth` | none | `AuthDiscovery{issuer, clientId, audience}` — what `ankka login` needs; reveals nothing else |
| `GET /auth/whoami` | A | `Whoami` — subject, display claims, `platformAdmin`, organizations with roles; **claims pending invitations for the caller's verified email first** (research R9) |

### `/organizations`

| route | who | change |
|---|---|---|
| `GET /organizations` | A | only the caller's organizations, each with `role`; every organization for P, with `role` absent where P is not a member; claims pending invitations first |
| `GET /organizations/{id}` | M | 404 for non-members |
| `POST /organizations/{id}` | A | creator becomes `owner`; body unchanged |
| `PUT /organizations/{id}/name` | O | disabled → 409 |
| `DELETE /organizations/{id}` | O | unchanged rule (no projects); allowed while disabled |
| `GET /organizations/{id}/members` | M | `MembersResponse{members, invitations}` |
| `POST /organizations/{id}/members` | O | body `Invite{email, role}`; 409 if member or pending; disabled → 409 |
| `DELETE /organizations/{id}/members/{subject}` | O | 409 if last owner; disabled → 409 |
| `PUT /organizations/{id}/members/{subject}/role` | O | body `RoleChange{role}`; 409 if it would demote the last owner; disabled → 409 |
| `DELETE /organizations/{id}/invitations/{email}` | O | revoke; disabled → 409 |
| `POST /organizations/{id}/members/{subject}/repair` | P | administrative: adds `{subject, role}` directly — the path for an organization whose owners have all left; **not** refused while disabled |
| `POST /organizations/{id}/disable` | P | 409 if already disabled; fans out suspension (research R10) |
| `POST /organizations/{id}/enable` | P | 409 if not disabled; fans out reinstatement |

An owner removing *themselves* is a `DELETE` on their own subject and obeys the last-owner rule.

### `/projects`

| route | who | change |
|---|---|---|
| `GET /projects[?organization=]` | A | only projects in the caller's organizations; the filter, if given, must be one of them or the result is empty (never 403) |
| `GET /projects/{id}` | M | 404 for non-members |
| `POST /projects/{id}` | M of `organizationId` | 404 if the organization is not visible to the caller (was: 404 if missing — same answer); disabled → 409 |
| `PUT /projects/{id}/name` | M | disabled → 409 |
| `DELETE /projects/{id}` | M | unchanged rule (no services); disabled → 409 |

### `/services`

| route | who | change |
|---|---|---|
| `GET /services/{project}` | M | 404 for non-members |
| `GET /services/{project}/{name}` | M | `ServiceStatus` gains `suspended`; lifecycle may be `Suspended` |
| `PUT /services/{project}/{name}` | M | disabled → 409 |
| `POST …/pause`, `…/resume`, `…/restart`, `…/expose`, `…/unexpose` | M | disabled → 409 |
| `GET …/logs` | M | allowed while disabled (there is nothing running, and the CLI already says so) |
| `DELETE /services/{project}/{name}` | M | disabled → 409 |
| `GET /services/{project}/{name}/history` | M | **new**: `Vector[HistoryEntry]`, newest first, at most 50 |

## Actor on commands

Every write carries the caller as `Actor{subject, display, administrative}` into the entity
command; `administrative` is true only when the caller's role would not have sufficed without
`platform-admin`. Entities record it on the event; `history` and the members listing show it.

## Removed

`ControlPlaneAcl.bearer`, `ankka.controlplane.auth.token`, `ANKKA_CONTROLPLANE_TOKEN`. There is no
route, header or configuration that accepts the old shared token.

## Wire compatibility

Every new field on an existing type has a default, so a CLI one minor version behind reads the new
JSON. A CLI sending the old token gets a 401 whose `error_description` says
`shared tokens are no longer accepted; run 'ankka login'`.
