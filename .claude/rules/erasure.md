---
paths:
  - "modules/core/src/main/scala/**/personal/**"
  - "modules/runtime/src/main/scala/**/erasure/**"
  - "keyring/**"
  - "controlplane/src/main/scala/**/ErasureEndpoint.scala"
  - "controlplane/src/main/scala/**/deploy/Erasure*"
  - "controlplane/src/main/scala/**/application/Erasure*"
  - "kustomization/components/keyring/**"
  - "modules/testkit/src/main/scala/**/InMemoryKeyring.scala"
  - "protocol/fixtures/personal/**"
  - "modules/sdk/src/main/scala/**/Erasure.scala"
  - "modules/agent/src/main/scala/**/SubjectIndexEntity.scala"
  - "kustomization/components/postgres/ddl/50-erasure-postgres.sql"
---

# Personal data erasure: the personal type, the keyring and erasure requests

Feature 042. A field typed `Personal[A]` (`core/personal`) is stored only as a personal envelope —
`{"subject","project","data"[,"lookup"]}`, AES-256-GCM over the value's JSON with the subject and project
as associated data — because its codec, found by any derived codec through an `inline given` in the
companion, writes nothing else. Erasure destroys the subject's key in the keyring (`keyring/`, an ankka
application of its own, deployed beside the control plane), so every copy becomes unreadable without
being rewritten. `docs/platform/erasure.md` is the contract with a service; `specs/042-*/contracts/` the
contracts between the parts.

## The scope a codec reads its keys from

`PersonalScope` is a thread-local (keyring, project, lookupAllowed) with a JVM default: every running
service registers its keyring and project, and when all registrations share one project that is the
default. On the platform a JVM runs one service, so the default is always right; in a test JVM with two
projects there is no default and a site that set no scope **fails closed** (`Unavailable`). So each
actor system carries its own, `ServiceScope(system)`, set in `host()` and applied where the runtime
serializes: the entity and workflow adapters (`ScopedAdapters`) and command handlers, every projection and
topic handler and its executor (`ServiceScope.executionContext`, so a Future callback keeps it), every
row decode on the database driver's thread (`Database`), the view client and the HTTP handlers'
executor. Every row write also allows lookup (`PersonalScope.allowingLookup`, the only place a token is
written). Destroyed subjects are kept per keyring handle (`KeyringHandle.isDestroyed`, a service's
`KeyCache`), never per JVM.

## A process or a module gets its keys from the sidecar (protocol 1.15)

A codec is synchronous in every SDK, so a key it lacks is one blocking call: `FetchSubjectKey` (Python a
synchronous gRPC stub, TypeScript a worker thread under `Atomics.wait`, a module the `subject_key`
import). `SubjectKeyEvents` streams every subject the sidecar's cache is told is erased, and **replays
every one already known when a process subscribes**, so a notice sent while the process was between
reconnects is not lost. A module hears nothing, so a stored Rust value asks the runtime whether its
subject is erased each time it is read. The sidecar never opens an envelope; it fetches, caches and
forwards. An erasure reaches a process only through `Erasure.Handle`, after the platform's duties.

## Grants admit everything that crosses a project, and an installation has none yet

Another project's fetch of a key (the keyring's `Grants`, asked **at each fetch**, never captured at
`hello`), a service asking for an erasure (`GrantReader.allows(…, "erasure")` in the control plane's ask
route, which admits a `Caller.Service` by an `Acl.Authenticate` of its own and records a refusal as a
`Refused` request the project's history shows), and a machine outside decrypting (`POST /decrypt` on the
keyring, a token's `machine` claim, `GrantReader` with `decrypt`) are each admitted by a grant.
`GrantReader.fromFile` answers `none` with a `TODO(040)` until spec 040 renders grants, so every one of
them is refused on an installation and admitted only by a test's own reader. The other blocked seams:
backups and restores of the keyring and services on k3s wait on 041; the k3s suites' machine token on 040.

## The keyring's channel

One WebSocket per service instance (`/channel`, admitted by the certificate's project). It sends the
project's erasure log on `hello`, every destroyed notice and apply order as they happen, and closes with
`Close("not-admitted")` on a `hello` for another project and `Close("unacknowledged")` to a channel that
has not acknowledged a destroyed notice within `ackWithin` (60s): the instance reconnects and replays
the log from its `appliedUpTo`, so a closed channel is a retried one, never a lost erasure.

## Traps

- **A running entity holds plaintext.** It decoded or built its state before the erasure. `Personal`'s
  accessors (`toOption`, `getOrElse`, `fold`, `map`, `isErased`) consult `PersonalScope.isDestroyed`, which
  every service's key cache fills on a notice, so a held value reads as erased at once; a pattern match on
  `Present` does not, and the docs say to read through the accessors.
- **A stored value is redacted, a fresh one refused.** `Present.stored` is set when a value is written or
  read; re-encoding a stored value of an erased subject (the next snapshot, a key value state written for
  another field) writes the erased form, where a fresh one is refused. The refusal is made in
  `Personal.present`, inside the handler, because a refusal thrown during a persist crashes the entity and
  the caller times out. Both entity hosts now answer a thrown `CommandError` as the refusal it is.
- **`Personal[X]` inside `X` expands forever.** The inline given derives the inner codec where it is
  used, so an enum with a `Personal[ThatEnum]` case never finishes compiling. Wrap a separate type
  (`SessionContent` beside `SessionMemoryEvent`).
- **`writeToArray` inside a codec corrupts the outer write.** jsoniter reuses one writer per thread; a field
  codec encodes its inner value with `writeToArrayReentrant` and decodes with `readFromArrayReentrant`.
  Anything a codec calls is inside that read too: the keyring channel's own messages (`ChannelWire`) are
  written and read reentrantly, or a cold key fetch during a view's rebuild handed the projection the
  channel's message as its event. Only the real `KeyringClient` serializes; the in-memory keyring hid it.
- **The journal carries no lookup token, so a value read from an event is not marked for lookup.** A view
  marks its row's field again where it writes it (`forLookup` in every language).
- **Garage accepts `If-None-Match: *` and overwrites anyway.** The erasure log's bucket copy is
  append-only because `ErasureLogBucket` reads the key before it writes (one writer, the sweeper
  singleton), not because the store refuses; `ObjectStoreClientSuite` pins the store's behaviour so the
  day it changes is visible.
- **A test keyring restarted on port 0 comes back elsewhere.** Restoring its database restarts it, and
  every channel and the sweeper still name the old port; a suite that restarts the keyring binds it to a
  port chosen once (`RestoresFeatures.keyringPort`), as a deployment finds it at its Service's address.
- **A first application can arrive twice** — the control plane asks until it hears, a reconnecting channel
  replays the log — and each copy ran the handler. `ErasureRuntime` answers a repeat with the completion it
  sent; only a reapplication runs again, and a failed handler is not remembered as complete.
- **The manifest is not in the associated data.** A consumer in another service or language decodes a
  message under its own type and manifest; binding the manifest made every such read corrupt.
- **Redaction is one statement over the envelope's fixed grammar** (`ViewRedaction`): the key order every
  SDK writes is load-bearing. A subject's alphabet has one regular-expression character, `.`, escaped.
- **The keyring applies its own schema** (`Schema`, from the runtime jar's DDL): kustomize's load
  restrictor stops its component reading `postgres/ddl`, and a copy would be a second schema.
- **`protocol/fixtures/personal/` is not compared byte for byte** — the nonce is random — so
  `PersonalFixturesSuite` pins the rows and checks every stored envelope decodes as its row says.
- **A new DDL file is named in seven lists** (`kubernetes.md`, Schema). `50-erasure-postgres.sql`
  (`ankka_erasures_applied`) is; the keyring's own database applies the same files itself (`Schema`).
- **`ankka.erasure.handler-timeout` is read in two places**: the sidecar for a process's handler and
  `Ankka.host` for a JVM service's. The JVM one was missed once and ran on the class default.
- **The SDK test kits start a keyring too.** Python's, TypeScript's and Rust's integration kits run
  `ankka-keyring` and its own Postgres beside the sidecar (image like the sidecar's: a released SDK's
  version, `ankka-keyring:latest` unreleased, `ANKKA_KEYRING_IMAGE` over both), so a generated project's
  personal field is written in its own tests. Build `keyring/Docker/publishLocal` before them.
- **Every test kit runs the JVM's `InMemoryKeyring` by default.** A suite that runs the keyring, or a
  service with its own channel, passes `keyring = None` to every other kit, or the JVM default scope sees
  two projects and fails closed.
