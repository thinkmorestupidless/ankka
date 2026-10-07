# Contract: the control plane

Two routes are added to `ServiceEndpoint` and one reply changes shape by gaining fields. Both new
routes are behind the endpoint's existing ACL and are authorized by project, as every service
route is: a person who is not a member of the project's organization is answered `404`, that there
is no such project.

## `POST /services/{projectId}/{name}/rollback`

Roll a service back: apply the descriptor of an earlier generation as a new generation.

**Body**: `RollbackRequest`

```json
{ "generation": 1 }
```

`generation` is optional. `{}` asks for the default target: the most recent generation whose
descriptor differs from the one the service has.

**Reply**: `200`, `RolledBack`

```json
{
  "rolledBackTo": 1,
  "status": { "name": "cart", "projectId": "shop", "generation": 3, "image": "cart:1", "lifecycle": "UpdateInProgress" }
}
```

`status` is a `ServiceStatus`, with its hostname when the service is exposed, as an apply's reply
has. A paused service's status says `Paused` and its instances stay at zero.

**Refusals**, in the order they are checked:

| Status | When | Message |
|---|---|---|
| `404` | the caller is not a member of the project's organization | `no such project 'shop'` |
| `409` | the organization is disabled | `organization 'acme' is disabled` |
| `404` | the service does not exist, or is deleted | `no such service 'cart' in project 'shop'` |
| `404` | the generation is below 1 or above the current one | `service 'cart' has no generation 9` |
| `409` | the generation's descriptor is no longer kept | `the descriptor of generation 3 is no longer kept; the oldest kept is generation 11` |
| `409` | the generation recorded no descriptor | `generation 3 was a restart and ran the descriptor of generation 2` |
| `409` | the generation's descriptor is the one the service has | `service 'cart' already has the descriptor of generation 2` |
| `409` | no generation named, and no kept descriptor differs | `service 'search' has no earlier generation with a different descriptor` |
| `400` | the descriptor no longer passes the platform's rules | `invalid descriptor at generation 1: <every problem>` |
| as an apply | the organization's quota does not allow the descriptor's instances | the organization's own refusal |

Nothing is written when a rollback is refused, and an instance reservation taken for it is put
back.

**What it does not change**: whether the service is paused, whether it is exposed, and its
restart count.

**Order of work in the handler**: authorize for write; ask the entity for the target
(`rollback-target`); check the descriptor's problems; reserve the target's instances with the
organization; send `rollback` with the resolved generation; on any failure after the reservation,
restore what the service counted before. The entity checks the target again, since it is the
single writer.

## `GET /services/{projectId}/{name}/descriptor?generation=N`

The descriptor recorded at generation N.

**Reply**: `200`, the `ServiceDescriptor`, exactly as `PUT /services/{projectId}/{name}` accepts
one.

```json
{ "name": "cart", "service": { "image": "cart:1" } }
```

**Refusals**:

| Status | When | Message |
|---|---|---|
| `404` | the caller is not a member | `no such project 'shop'` |
| `400` | `generation` is absent or not a whole number | `query parameter 'generation' is required` |
| `404` | the service has never been applied | `no such service 'cart' in project 'shop'` |
| `404` | the generation is below 1 or above the current one | `service 'cart' has no generation 9` |
| `409` | the descriptor is no longer kept | `the descriptor of generation 3 is no longer kept; the oldest kept is generation 11` |
| `409` | the generation recorded no descriptor | `generation 2 was a restart and ran the descriptor of generation 1` |

A deleted service answers, as its history does. A variable's literal value is in the reply as it
was applied; a value taken from a project secret is the reference, never the value.

## `GET /services/{projectId}/{name}/history` (changed)

Each entry may now carry three more fields. Nothing is removed and no existing field changes.

```json
[
  { "kind": "rolled-back", "generation": 3, "at": "2026-10-04T10:12:03.114Z",
    "actor": { "subject": "…", "display": "alice@example.com", "administrative": false },
    "image": "cart:1", "digest": "3f9a1c0be2d4…", "rolledBackTo": 1 },
  { "kind": "applied", "generation": 2, "image": "cart:2", "digest": "a41d77c09e15…" },
  { "kind": "paused", "generation": 1 },
  { "kind": "applied", "generation": 1, "image": "cart:1", "digest": "3f9a1c0be2d4…" }
]
```

| Field | On | Meaning |
|---|---|---|
| `image` | `applied`, `rolled-back` | the image of the descriptor that entry recorded |
| `digest` | `applied`, `rolled-back` | 64 lowercase hex characters; two entries share one exactly when their descriptors state the same things |
| `rolledBackTo` | `rolled-back` | the generation whose descriptor was applied again |

An entry of any other kind has none of the three. An `applied` entry may lack `image` and `digest`
when the control plane recorded it before it kept them.

## The entity

`ServiceEntity`, component id `service`. Wire names are declared and are a versioning boundary.

| Handler | Wire name | Kind | Input | Reply |
|---|---|---|---|---|
| `rollback` | `rollback` | command | `RollbackService(generation)` | `ServiceStatus` |
| `rollbackTarget` | `rollback-target` | query | `RollbackRequest` | `KeptDescriptor` |
| `descriptorAt` | `descriptor-at` | query | generation, a `Long` | `ServiceDescriptor` |
| `desiredState` | `desired` | query | none | `Option[Service]`, **now with `kept` emptied** |

`rollback` persists `ServiceApplied(projectId, target.descriptor, generation + 1, actor, at,
rolledBackTo = Some(n))`. It refuses, persisting nothing, for a service that does not exist, for
any `RollbackRefusal` of `rollbackTarget(Some(n))`, and for a descriptor with problems.

## Rolling update

A control plane node on the previous version, during the update:

- decodes a new `ServiceApplied` as an apply (the unknown field is skipped), so its listing row
  and its projection are right;
- has no `rollback` handler, so a rollback routed to an entity on that node is answered at once,
  `404` with `no handler 'rollback' on component 'service'`, and nothing is written; the member
  tries again once the update is done. The entity host answers an undeclared handler itself,
  without waiting for a timeout (`EventSourcedEntityHost`, held by `CallCountsSuite`).
