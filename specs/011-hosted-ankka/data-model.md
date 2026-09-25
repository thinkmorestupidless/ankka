# Data Model: A Platform a Hosted Product Can Provision

## OrganizationPolicy (configuration, `controlplane`)

| Field | Type | Source | Default |
|---|---|---|---|
| `creation` | `OrganizationCreation` = `Open` \| `PlatformAdmin` | `ankka.controlplane.organizations.creation` / `ANKKA_ORGANIZATION_CREATION` (`open` \| `platform-admin`) | `Open` |
| `signupUrl` | `Option[String]` | `ankka.controlplane.organizations.signup-url` / `ANKKA_SIGNUP_URL`, empty means none | `None` |

- `OrganizationPolicy.default` is `Open` with no URL: byte-for-byte today's behaviour.
- `OrganizationPolicy.from(config)` throws `IllegalArgumentException` for any other `creation`
  value; the message names the key, the variable and both accepted values.
- `refusal: String` — `"organizations in this installation are created by the platform
  administrator"`, with `"; sign up at <url>"` appended when `signupUrl` is set.

Not state: never persisted, never in an event.

## Owner (wire, `controlplane-api`)

| Field | Type | Notes |
|---|---|---|
| `subject` | `String` | the identity provider's stable subject id; the only key |
| `email` | `Option[String]` | display and invitation-collision check, stored via `Organization.key` |
| `display` | `Option[String]` | shown in the members listing |

## CreateOrganization (wire, `controlplane-api`) — extended

| Field | Type | Default | Notes |
|---|---|---|---|
| `name` | `String` | — | unchanged |
| `owner` | `Option[Owner]` | `None` | new; only a platform administrator may set it |

A body without `owner` is today's body. The codec is `Codecs.make`, unchanged.

## CreateForOwner (domain command, `controlplane`)

`CreateForOwner(name: String, owner: Owner)` — the payload of the new entity command
`create-for-owner`. Serialized with `Codecs.serializer[CreateForOwner]("create-for-owner")` in
`OrganizationEntity`'s companion, beside `Invite` and `AddMember`.

## OrganizationCreated (event) — extended

| Field | Type | Default | Meaning |
|---|---|---|---|
| `name` | `String` | — | unchanged |
| `actor` | `Option[Actor]` | `None` | who issued the command (the administrator, on the owner path) |
| `at` | `Option[Instant]` | `None` | when |
| `owner` | `Option[Owner]` | `None` | new: the first owner when it is not the actor |

Fold (`Organization.onCreated`):

- `owner = Some(o)` → members = `{ o.subject → Member(Role.Owner, o.email.map(key), o.display,
  at, addedBy = actor.map(_.subject)) }`.
- `owner = None` → exactly today's derivation from `actor` (creator is first owner; no actor, no
  owner).

The listing row (`OrganizationRows.onCreated`) mirrors the same rule for its `members` and
`owners` vectors.

Replay: a journal written before this feature decodes with `owner = None` and folds as before
(`EventCompatibilitySuite`).

## State transitions

Unchanged. Creation is still the transition from "unknown" to "exists"; the only difference is
which subject the first membership names and who the actor is.
