# Contract: project secrets — routes, wire types and commands

What a member, a deploy token and the CLI see. Decisions are in [research.md](../research.md)
(R13–R16).

## Routes

Every route is behind the control plane's authentication. Membership is the project's: a caller
with no role in the project's organization is answered `404 no such project '<id>'`, whatever the
route; a deploy token is a member.

### `PUT /projects/{projectId}/secrets/{name}`

Sets entries of a project secret. Entries named are added or replaced; every other entry is kept.

```json
{ "entries": { "STRIPE_KEY": "sk_live_1", "WEBHOOK_KEY": "whsec_1" } }
```

| Answer | When |
|---|---|
| `204` | the cluster holds the entries and the control plane has recorded their names |
| `400` | the name, an entry's name or a value breaks its rule, or there are no entries; every problem is named. Nothing is written |
| `404` | the caller is not a member, or the project does not exist |
| `503` | the cluster refused or could not be reached. Nothing is recorded |

### `DELETE /projects/{projectId}/secrets/{name}?entry={key}`

Removes one entry, named by the query parameter (the endpoint DSL takes at most two path parameters
on `DELETE`).

| Answer | When |
|---|---|
| `204` | the entry is removed from the cluster and from the record |
| `404` | not a member; no such project; or the record has no such entry. Nothing is written |
| `503` | the cluster refused or could not be reached. Nothing is recorded |

### `GET /projects/{projectId}/secrets`

Lists the project's secrets from the control plane's own record, sorted by name. A secret with no
entry is not listed. No value is ever in the answer; the control plane holds none.

```json
[ { "name": "checkout", "entries": ["STRIPE_KEY", "WEBHOOK_KEY"],
    "setAt": "2026-10-03T10:15:00Z", "setBy": "Ada" } ]
```

## Rules (`controlplane-api`, `ProjectSecrets.problems`)

- **Secret name**: lowercase letters, digits, `-` and `.`; begins and ends with a letter or
  digit; at most 253 characters; does not begin `ankka-`; does not end `-db`, `-cluster-tls`,
  `-service-tls`, `-database-tls` or `-secret-key`. Those forms are the platform's own.
- **Entry name**: letters, digits, `.`, `_` and `-`; 1 to 253 characters.
- **Value**: not empty; at most 65,536 bytes as UTF-8.

The CLI applies the same function before it sends, so both ends refuse the same things in the same
words.

## Wire types (`controlplane-api`, in `Wire`)

```scala
final case class SetProjectSecret(entries: Map[String, String])
final case class ProjectSecretSummary(
    name: String,
    entries: Vector[String],
    setAt: Option[Instant] = None,
    setBy: Option[String] = None)
```

Each has a fixture, and a zod mirror in `console/package/src/client/schemas.ts`. The console gains
no page.

## What is recorded

Project events, each with `actor` and `at` defaulting to `None`:

```scala
case ProjectSecretEntriesSet(name: String, entries: Vector[String], actor, at)
case ProjectSecretEntryRemoved(name: String, entry: String, actor, at)
```

`entries` and `entry` are names of entries. Neither event has a field that could hold a value, and
neither names the project: an event is the project entity's own. `EventCompatibilitySuite` asserts
the encoded event's field names are exactly these, that no value given in the request occurs in
it, and that a `Project` snapshot written before this feature decodes with no secrets.

## The cluster

- The Secret is in the project's namespace, type `Opaque`, labelled
  `app.kubernetes.io/managed-by: ankka` and `ankka.thinkmorestupidless.com/project-secret: "true"`.
- Written by a merge patch, then by a create when the patch answers `404`. Never read. Never
  deleted: a secret with no entry stays, empty.
- A Secret left with no entry takes a new one by the same merge patch, and is then listed again.
- The control plane's grant on Secrets stays `create, patch`.

## Commands

```text
ankka projects secrets set <name> <key>=<value>... [-p <project>]
ankka projects secrets unset <name> <key>          [-p <project>]
ankka projects secrets list                        [-p <project>] [-o table|json]
```

- A pair written `<key>=-` takes its value from standard input, read to the end with one trailing
  newline removed. At most one pair may. Input is read through `Console.in`.
- `set` prints `project secret '<name>' in '<project>' has <entries>` naming the entries it set, and
  never a value. `unset` prints which entry it removed. `list` prints `NAME`, `ENTRIES`, `SET`,
  `BY`.
- Exit codes are the CLI's: `0`, `1` for a refusal with the control plane's words, `2` for a
  command that does not parse.

## Referencing one from a descriptor

Unchanged, and it is what makes the secret useful:

```json
{ "name": "STRIPE_KEY", "secretKeyRef": { "name": "checkout", "key": "STRIPE_KEY" } }
```

A variable whose name is one the platform alone sets is refused whether it has a value or a
`secretKeyRef`.

## What a test must show

- **Fast HTTP suite** (`ControlPlaneHttpSuite`, the fake cluster): every row of the three answer
  tables; that a set merges in the fake as a merge patch would; that after a `503` the listing is
  unchanged; that a deploy token may set, unset and list; that each reserved form is refused and
  nothing reaches the fake.
- **The fake must not be kinder than the cluster.** It keeps entries per Secret and applies a set
  as a merge and a removal as a removal, so a test of "the other entries are kept" fails if the
  endpoint replaced them.
- **k3s, the shipped grant** (`ControlPlaneClusterSuite`): with the control plane's own token —
  patch of a missing Secret is `404`; create then patch merges; a `null` removes one entry; `get`,
  `list` and `delete` are `403`.
- **k3s, end to end** (`EndToEndClusterSuite`, through the real CLI): set, apply a descriptor that
  references it, read the variable inside the pod; set again, restart, read the new value.
- **The reserved forms are the operator's names**: a suite in `controlPlane`'s tests asks the
  operator's naming functions for a sample service's Secret names and asserts
  `ProjectSecrets.problems` refuses each.
