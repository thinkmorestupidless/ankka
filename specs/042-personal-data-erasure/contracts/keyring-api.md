# Contract: the keyring's routes

Held by `features/erasure/erasing.feature`, `restores.feature`, `other-projects.feature` and the
keyring's suites. The keyring listens on 9020; in Kubernetes every route is mutual TLS and the
caller is its certificate; locally the caller is `Local`.

```text
POST /projects/{project}/erasures              control plane      apply: {erasureId, subject, logSequence}
POST /projects/{project}/erasures/{id}/reapply control plane      push apply again to every channel
GET  /projects/{project}/erasures/{id}         control plane      {state, keyDestroyedAt, channels: [{service, instance, acked, closed}], completions: [...]}
GET  /status                                   anyone admitted    {ready, copies, replayed, behind, lastReplayAt, channels}
POST /decrypt                                  a machine's token  {envelope} → {value} | 403
WS   /channel                                  a service          contracts/keyring-channel.md
```

- `POST …/erasures` is idempotent on `erasureId`; a second call answers the existing state. It
  refuses (409) an erasure whose `logSequence` the keyring has not been told was written — the
  control plane writes both copies first and sends the sequence as proof.
- For a subject with no key, the keyring records a tombstone and answers `applied` with
  `keyDestroyedAt` set and `everExisted: false`.
- Completion per service: every channel present at the destroy acked or was closed, and one
  `completed` per service arrived. A service with no channel at the destroy (none running) has no
  completion until its first `hello` applies the log.
- The control plane's identity (`ankka://platform/controlplane`) is admitted to the three erasure
  routes and `/status`, and to nothing that answers a key; a `fetch` from it is refused and recorded.
- `/decrypt` takes a bearer token the installation's machine issuer signed (040); the subject's
  project is read from the envelope; the grant must name the machine and carry `decrypt` on a topic
  of that project; a revoked grant or an erased subject is 403 on the next call; every decryption
  and every refusal is recorded on the subject's key entity with the machine and the grant.
- `GET /status` before replay finishes answers `ready: false` with 503, as does every other route.
  `copies` is 1 or 2; `behind` is `controlplane`, `bucket` or null.
