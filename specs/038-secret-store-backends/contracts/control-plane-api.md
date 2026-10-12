# Contract: control plane routes and CLI for the read record

Both routes are new. `ControlPlaneRoutesReferenceSuite` will demand a generated row and a
hand-written section in `docs/reference/control-plane-api.md`; `CliReferenceSuite` the same for
`docs/reference/cli.md`.

## `POST /secret-reads` — a service writes one record

**Caller**: a service, by its client certificate (`Caller.Service(project, service)`); admitted by
`Acl.allowCallers(Callers.anyService)`. `Caller.Local` is admitted (a developer's machine, the
suites). The gateway (`Caller.Gateway`) and a bearer token are refused: this route is not behind
the token ACL and the route-level ACL admits no `Internet`.

**Request body** (`SecretReadRecord`):

```json
{
  "at": "2026-10-08T12:00:00Z",
  "project": "spinvibe", "service": "payments", "hosting": "embedded",
  "name": "psp/acme/api-key", "operation": "get", "outcome": "read",
  "backend": "secret-manager",
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736", "spanId": "00f067aa0ba902b7",
  "component": "charge", "componentKind": "workflow",
  "latestSkipped": false
}
```

**Responses**: `204` stored. `403` when `project`/`service` differ from the caller's certificate.
`400` on a malformed record (a value-shaped field is not accepted: there is none). `503` when the
record database is unreachable — the runtime treats anything but `2xx` within its timeout as
"not acknowledged" and refuses the operation.

**Guarantee**: the control plane answers only after the row is committed.

## `GET /projects/{projectId}/secret-reads` — an owner lists records

**Caller**: a member of the project's organization with the `owner` role, or a platform
administrator. A deploy token is a `member` and is refused `403`. A non-member gets the project's
`404`.

**Query**: `service` (exact), `name` (exact), `from` and `to` (RFC 3339 instants, inclusive /
exclusive), `limit` (default 200, max 1000). Newest first.

**Response** (`SecretReadsPage`): `{"records": [SecretReadRecord, …]}`.

## CLI

```
ankka projects secret-reads list -p <project> [--service <name>] [--name <secret>]
                                 [--from <instant>] [--to <instant>] [--limit <n>] [-o json|table]
```

Table columns: `AT`, `SERVICE`, `COMPONENT`, `NAME`, `OPERATION`, `OUTCOME`, `TRACE`. Never a value
column, because the record has none.

## `GET /installation` — where the installation keeps its secrets (044's route)

**Caller**: any authenticated principal (a member, a deploy token, a platform administrator).

**Response** (`Installation`; `cloud` is 044's, `secrets` this feature's):

```json
{
  "platformVersion": "0.12.0",
  "cloud": { "provider": "gcp", "account": "spinvibe-prod", "location": "europe-west2" },
  "secrets": { "backend": "secret-manager", "recordRetention": "365d", "auditLog": "unknown" }
}
```

`auditLog` is `on`, `off` or `unknown` (no provider has reported it). `secrets` is absent from a
control plane older than this feature. CLI: `ankka installation [-o json|table]`. First built as a
route of its own, `GET /platform`; folded into 044's route when 044 was merged in.

## Existing routes whose behaviour changes on the Secret Manager backend

- `PUT /projects/{id}/secrets/{name}`: the writer adds versions in Secret Manager; a refusal from
  Google is `503` naming the grant, and nothing is recorded (write the cloud first, then the
  journal, as today).
- `DELETE /projects/{id}/secrets/{name}?entry=`: disables the entry's versions.
- `GET /projects/{id}/secrets`: unchanged (names and entries from the entity).
- `GET /services/{p}/{n}`: `secretStore` appears in the service's status (`phase`, `detail`,
  `backend`, `move`), folded by `StatusIngest` from the resource and the observe document.
