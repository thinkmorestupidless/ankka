# Contract: erasure requests on the control plane

Held by `features/erasure/erasing.feature`, `holds.feature`, `asking.feature` (blocked on 040) and
`restores.feature` (the log route). Every route is behind the control plane's ACL; a member of the
project may read and request, an owner may override, whoever asked or a member may withdraw.

```text
POST   /projects/{id}/erasures                         RequestErasure → ErasureRequest (201; 200 with the applied one for an erased subject)
GET    /projects/{id}/erasures?subject=&state=&correlation=   [ErasureRequest]
GET    /projects/{id}/erasures/{e}                     ErasureRequest
DELETE /projects/{id}/erasures/{e}                     withdraw (204; 409 unless Held)
POST   /projects/{id}/erasures/{e}/override            {reason} → ErasureRequest (owner; 403 for a member; 409 unless Held)
GET    /projects/{id}/erasures/{e}/certificate         ErasureCertificate (404 unless Applied, Final or Settled)
POST   /projects/{id}/erasures/{e}/reapply              run every service's handler again (member; any applied state)
GET    /projects/{id}/history                          [ProjectHistoryEntry]   (ErasureRefused among them)
GET    /erasures/log?after=<sequence>                  [ErasureLogEntry]        (the keyring's identity only)
```

- A request for a subject that already has a `Held` request from the same asker replaces it
  (`Replaced`, both kept in the history); from a different asker it is 409.
- A service asks through its service client with its certificate as identity; admitted when a
  grant gives it the `erasure` right in that project (040; until then every service is refused),
  and a refusal is recorded on the project's history with the service and the subject.
- `state` filter values: `held`, `withdrawn`, `replaced`, `applying`, `applied`, `final`, `settled`, `failed`.
- The sweeper runs every `sweep-interval` (30 s): applies `Held` requests whose date has passed,
  drives `Applying` ones (writes the log to both copies, posts the keyring, polls completions,
  records `Applied` when every service of the project completed and `Final` when the latest
  object finality has passed, `Settled` when finality plus `erasure.reapply-grace`, 30 days, has
  passed), retries `Failed` ones, and reapplies every `Applied` and `Final` one every
  `erasure.reapply-interval` (15 min); a `Settled` one is reapplied only by
  `POST /projects/{id}/erasures/{e}/reapply` (a member). A handler run is recorded on its first run,
  a failure and a changed outcome only. A service with no running instance at the destroy is
  recorded complete by absence.

## The CLI

```text
ankka projects erasures request <subject> [--not-before YYYY-MM-DD] [--reason TEXT] [--correlation ID] [--keyring URL]
ankka projects erasures list [--subject S] [--state STATE] [--correlation ID]
ankka projects erasures get <id>
ankka projects erasures withdraw <id>
ankka projects erasures override <id> --reason TEXT
ankka projects erasures certificate <id> [-o json]
ankka projects history
```

```text
$ ankka projects erasures list -p brand
ID        SUBJECT        STATE    NOT BEFORE   ASKED BY           CORRELATION
e-4f2a…   player/8c1f    applied  -            member alice       closure-4411
```

`--keyring URL` sends `request` to a keyring directly for a local run (project `local`, applied at
once, no hold, no certificate); the CLI says so.

## The certificate

JSON: the request as listed, the data subject, who asked, each service's completion time and
handler outcome, the time the key was destroyed, the time the erasure became final, `issuedAt`.
It holds no personal field and no value; `EventCompatibilitySuite`'s sibling for erasures asserts
the wire form has no field that could carry one.

## Reference pages

`CliReferenceSuite` and `ControlPlaneRoutesReferenceSuite` fail until `just docs-reference` has
regenerated `docs/reference/cli.md` and `docs/reference/control-plane-api.md`, and each new route
has a hand-written section; `mcp/AnkkaTools` mirrors `erasures list|get|request|certificate`.
