# Contract: the runtime's own wire and settings

What changes inside one installation's runtime: journal and snapshot records, sharded messages,
reserved methods, settings and refusals. Types are in `data-model.md`; this says what each party
may rely on.

## Journal records

- A record's `installation` is set by the host that persisted a handler's events or a platform
  record, from `ankka.replication.installation`; it is absent when the setting is unset. A
  `Mirrored` record is persisted with the `installation` it arrived with, except an unstamped one,
  which the follower stamps with the installation it pulled from (research R5); the kind, manifest,
  payload and expiry are never touched.
- Kinds 3 to 6 are mirrored; kind 7 never is. `ChangeReader.journal` reads all five as `Skip`.
- An older runtime that reads a kind it does not know fails recovery with "written by a newer
  runtime", as it does today; a service that has used replication is not downgraded.

## Snapshots

`StateRecord` and `RemoteStateRecord` carry the mode fields, absent when unset. A snapshot from
before this feature reads as `home = None`, which is `Unknown` mode; the next command at the
installation holding the journal makes it the home (research R2).

## Sharded messages

`Replicate`, `Wake`, `Reported`, `Forwarded` and `Locate` are `EntityProtocol.ModuleCommand`s,
serialised under the `AnkkaSerializable` binding, sent only within one cluster (`Locate` and
`Forwarded` only from the actor to itself through `pipeToSelf`). The workflow engine, the key
value host and the remote hosts answer them `unhandled` as they answer any module command they do
not know (`.claude/rules/runtime.md`, "every host must handle unexpected commands explicitly").

## Reserved methods

`ankka:replication` (read-only) and `ankka:handover-status` (read-only, `NotYet` loop) join
`ankka:lifecycle` and `ankka:await-end` under the `ankka:` prefix `Companion.descriptor` refuses to
user handlers. An instance from before this feature answers either "no handler", and the client
reports it as the instance being from before replication, as `ShardingTransport.fromBeforeAwaiting`
does for a wait.

## Forwarding

At a copy that is not the home, a non-read-only `Invoke` becomes `transport.forward(home, …,
ForwardedCall(method, payload, metadata))`, and the reply is piped back to `invoke.replyTo` as
`Succeeded` or `Rejected`. The forwarding host records one `Client` span under the caller's trace
through `Observability.calling`, counted `handled` once by the home's host and `unanswered` once by
the forwarding host if the transport fails; the forwarding host never counts the call `handled`.
The receiving installation handles a forwarded call as an `Invoke` from a local caller whose
metadata is the forwarded metadata.

Every reply a replicated entity's host gives carries the metadata entry `ankka.handled-at` naming
the installation whose handler ran, so a caller at a follower can tell a forwarded reply from a local
one; it travels with the reply's other metadata (`ankka-manifest`, `content-type`) wherever that
goes, including to a process and an HTTP caller's reply headers, and is never a secret.

## Settings and refusals at start

| Condition | Result |
|---|---|
| `ankka.replication.installations` empty | nothing checked, nothing started |
| `installation` names `|` or `/` or exceeds 63 characters | `StartRefusal` naming the setting |
| `installations` named, no provider, a sibling without `db.*` | `StartRefusal` naming the transport |
| a descriptor sets `ANKKA_INSTALLATION` or `ANKKA_INSTALLATION_*` | refused by `ServiceSpec.problems`, as every platform-only variable is |
| a non-entity component declared replicated | cannot be expressed in Scala; refused at discovery for a process or module |

## The local observability endpoint

The runtime's own transport adds `POST /replication/forward` and `POST /replication/report` to
`ObservabilityEndpoint` (loopback, ephemeral port, local mode only). Bodies are the CBOR of
`ForwardedCall` and of `(componentId, entityId, position)`. They exist for two services on one
machine and are not served under Kubernetes.
