# Contract: control plane HTTP API changes

No route is added or removed. One route's body and refusals change.

## `POST /organizations/{organizationId}`

Body:

```json
{ "name": "Acme Corp" }
```

or, from a platform administrator only:

```json
{
  "name": "Acme Corp",
  "owner": { "subject": "3f2a…", "email": "alice@example.com", "display": "Alice Example" }
}
```

`owner.email` and `owner.display` are optional.

Answers:

| Status | When |
|---|---|
| `204` | created; without `owner` the caller is the first owner, with it the named subject is |
| `403` | `owner` given by a caller without the `platform-admin` role — `platform administrator role required to name an owner` |
| `403` | creation policy is `platform-admin` and the caller lacks the role — `organizations in this installation are created by the platform administrator[; sign up at <url>]` |
| `409` | the id was ever used (unchanged) |
| `400` | empty name (unchanged) |

Order of checks: owner-without-role first, then the policy, then the entity. Neither refusal
creates anything or touches the named subject.

An administrator naming no owner becomes the first owner (unchanged behaviour).

## `GET /organizations/{organizationId}/members`

Unchanged shape. For an organization created with a named owner, the one member's `addedBy` is
the administrator's subject and `since` the creation time.

## `GET /auth` (spoke)

Unchanged shape; `issuer` is the configured issuer, which for a spoke is the hub realm's, not one
derived from the spoke's own base domain. Already the behaviour; now asserted end to end.

## Wire library

`com.thinkmorestupidless:ankka-controlplane-api_3` is published at the platform's version. It
holds every request and response type on this page, `Role`, `ServiceLifecycle`, the descriptor
types and `ServiceSpec.problems`. It depends on `ankka-core` only.
