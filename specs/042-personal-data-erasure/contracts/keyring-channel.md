# Contract: the channel between a service instance and the keyring

Held by `features/erasure/erasing.feature` (the 60-second scenario), `personal-fields.feature`
(one key, whichever instance), `restores.feature` (not ready until applied) and the keyring's
`ChannelSuite`. One WebSocket per service instance at `<ANKKA_KEYRING_URL>/channel`, opened by
`ErasureRuntime` when the service starts and re-opened on every close with backoff (1 s doubling to
30 s). Text frames, one JSON object each, `{"type": …}` first.

## Service → keyring

| type | fields | when |
|---|---|---|
| `hello` | `project`, `service`, `instance`, `reads` (the other projects whose topics this service consumes under a `decrypt` grant), `appliedUpTo` (erasure id or null) | first frame |
| `fetch` | `project`, `subject`, `create` | a cache miss; `create` only from an encode, and only for the service's own project |
| `lookupKey` | — | first lookup token |
| `ack` | `erasureId` | the key is dropped from this instance's cache |
| `completed` | `erasureId`, `completion` (duties, handler outcome, objects) | every duty done |

## Keyring → service

| type | fields | when |
|---|---|---|
| `log` | `entries[]` (erasureId, subject, destroyedAt, sequence) | answers `hello`: every applied erasure of the project after `appliedUpTo`; empty when none |
| `key` | `subject`, `key` (base64), `expiresAt` | answers `fetch` |
| `lookupKeyIs` | `key`, `expiresAt` | answers `lookupKey` |
| `refused` | `subject`, `reason` (`erased` \| `not-admitted`) | answers `fetch` |
| `destroyed` | `project`, `subject`, `erasureId` | pushed to every channel subscribed to the project on a destroy: its own services' and every channel whose `reads` named it and was admitted |
| `apply` | `erasureId`, `subject`, `reapply` | pushed after `destroyed`; again on a reapply |
| `close` | `reason` (`unacknowledged` \| `not-admitted` \| `shutdown`) | before the keyring closes the socket |

## Rules

- Admission is decided at `hello`: the caller's certificate must name `ankka://<project>/<service>`
  for `project`, and each entry of `reads` is admitted only when a grant gives the caller `decrypt` on
  a topic of that project (an entry not admitted is answered in `log` as `refused` and the channel
  stays open). A `fetch` names its project: the service's own, or an admitted entry of `reads`;
  `create`, `lookupKey`, `apply` and `completed` are for the service's own project only. The channel
  is subscribed to notices of its own project and of every admitted `reads` entry, so a consumer in
  another project reads a destroyed subject as erased within the same 60 seconds (FR-022, SC-004).
  Locally every caller is admitted to project `local`.
- The service is not ready until it has applied every entry of `log`; it records each in
  `ankka_erasures_applied` and answers `completed` for each.
- On `destroyed` the service drops the key at once and answers `ack`. The keyring waits 60 seconds
  for every channel's `ack`; a channel that has not answered is closed with `unacknowledged`, and a
  service whose channel was closed with that reason empties its whole cache before reconnecting.
- A channel the service cannot open is an outage: cached keys serve reads for the outage bound and
  are then dropped; no `destroyed` can arrive, and no erasure is applied, while the keyring is down.
- `apply` is idempotent: a service applies the same erasure id any number of times and answers
  `completed` each time; only the handler's outcome may differ.
- A sidecar holds the channel for a process and mirrors `fetch`/`key`/`refused`/`destroyed`/`apply`/
  `ack`/`completed` over `Client.SubjectKeys`; a module's host holds it and answers the imports.
- Keys cross the channel only inside the installation's mutual TLS; locally over loopback.
