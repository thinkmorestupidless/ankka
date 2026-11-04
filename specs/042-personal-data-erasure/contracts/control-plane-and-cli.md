# Contract: the control plane, the CLI, the console and the MCP server

## Routes (`CP/api/ErasureEndpoint.scala`)

| Method and path | Who | Body / answer |
|---|---|---|
| `POST /projects/{id}/erasures` | member; service with the `erasure` grant of `{id}` | `{subject, notBefore?, reason?, correlation?}` → 201 the request; 200 an applied one already for the subject; 400 a subject outside FR-002 |
| `GET /projects/{id}/erasures?subject=&state=&correlation=` | member | list, newest first, paged |
| `GET /projects/{id}/erasures/{rid}` | member | the request with completions, and `lastFailure: {reason, at}` while an attempt to apply it has failed |
| `PUT /projects/{id}/erasures/{rid}` | filer or member | replace a held request's `notBefore`/`reason`; 409 if not held |
| `POST /projects/{id}/erasures/{rid}/withdraw` | filer or member | 409 if applied |
| `POST /projects/{id}/erasures/{rid}/override` | owner | `{reason}` → applied at once; 403 for a member |
| `GET /projects/{id}/erasures/{rid}/certificate` | member | the certificate (`data-model.md`); 409 before applied |
| `GET /erasures?correlation=` | member of each project listed | requests of every project the caller is a member of with that id |
| `GET /internal/erasure-log?after=` | the keyring's identity only | log entries |

Every write is refused with 409 and "erasure is off on this installation" when `ANKKA_ERASURE` is off.
A refused service ask is recorded (`asking.feature`) as a `Refused` entry in the project's history.

## Installation facts

`GET /installation` → `erasure: {enabled: bool, keyringInstances: int}` (now stated), from the
setting. 057's `InstallationErasure` shape unchanged.

## Descriptor

`PUT /services/{p}/{n}` with `"personal": true` on an installation with erasure off → 400
"the service declares personal fields and this installation's erasure is off (ANKKA_ERASURE)".

## CLI

```text
ankka projects erasures file <subject> [-p <project>] [--not-before <date>] [--reason <text>] [--correlation <id>]
ankka projects erasures list [-p <project>] [--subject <s>] [--state held|applying|applied|withdrawn] [--correlation <id>]
ankka projects erasures get <rid> [-p <project>]
ankka projects erasures replace <rid> [--not-before <date>] [--reason <text>]
ankka projects erasures withdraw <rid>
ankka projects erasures override <rid> --reason <text>
ankka projects erasures certificate <rid> [--json]
ankka local erasures file <subject>          # against a service run locally (R20)
```

`ankka projects grants make … erasure` and `--decrypt` exist already (040).

## Console

Project page, *Erasures* section (`console/package/src/routes/project.tsx`): the list with state,
subject, not-before date, completions; a request's page; *Ask for an erasure*; *Withdraw*;
*Override hold* (shown to owners); *Certificate* download. Schemas in `client/schemas.ts`; the fake
control plane and `ControlPlaneFixturesSuite` extended. No new control plane route for the console.

## MCP tools (`cli/mcp/AnkkaTools.scala`)

`erasures_list`, `erasure_get`, `erasure_file`, `erasure_replace`, `erasure_withdraw`,
`erasure_override`, `erasure_certificate`, each calling the route above as the logged-in member.
