# Research: Personal Data Erasure — Crypto-Shredding Per Data Subject

Decisions taken against `main` at `00cdbfbd` (specs 038–044 merged, none built; `038-…-impl` and
`039-…-impl` worktrees in progress beside this one). Path abbreviations: `C` =
`modules/core/src/main/scala/com/thinkmorestupidless/ankka/core`, `S` = `modules/sdk/…/sdk`, `R` =
`modules/runtime/…/runtime`, `H` = `modules/http/…/http`, `A` = `modules/agent/…/agent`, `TK` =
`modules/testkit/…/testkit`, `CP` = `controlplane/src/main/scala/…/controlplane`, `API` =
`controlplane-api/src/main/scala/…/controlplane/api`, `OP` = `operator/src/main/scala/…/operator`,
`SC` = `sidecar/src/main/scala/…/sidecar`, `K` = `kustomization`.

## What is not there yet, and what this feature does about it

Six specs this one names are unbuilt. The plan builds every seam 042 owns and leaves each
dependency a named hole, so that 042 merges alone and each later feature fills its hole without
touching 042's design:

| Needs | From | Hole 042 leaves |
|---|---|---|
| `decrypt` on a topic grant, the `erasure` right, grants rendered to the keyring, machine tokens | 040 | `GrantReader` (R12): the keyring and the control plane admit by a grants document; the test kit's keyring takes grants directly; the file reader is 040's. US5's cross-project and machine scenarios run offline; their k3s proof and US8 wait for 040. |
| restores and switches, the restore's status | 041 | The service replays from the keyring on every start (R16), which is what a switch needs; the test kit restores a database by template copy (R20); the k3s restore proof waits for 041. |
| GCS versions and the soft-delete window | 039 | `ObjectErasure` (R15) lists every version where the store has them and reports finality from the bucket's status; on Garage the window is zero. |
| the `wrapping-key` request | 044 | The keyring's root key comes from its own secret store (R9); a `RootKeySource` seam takes 044's key when the installation names one. |
| the Postgres secret backend | 038 | 023's `DatabaseSecretStore`, unchanged; nothing to wait for. |
| topic retention | 043 | Nothing. |

## The type and its codec

### R1. `Personal[A]` lives in `core`, beside `Serializer`, with its codec in its companion

**Decision**: `C/personal/Personal.scala`: `enum Personal[+A]` with `Present(subject: String, value: A,
origin: Option[String] = None, lookup: Boolean = false)` and `Erased(subject: String, origin:
Option[String] = None)`; `Personal.present(subject, value)`, `Personal.lookup(subject, value)`,
`toOption`, `map`, and `override def toString = s"Personal($subject)"` (FR-029). `DataSubject.problems`
holds FR-002's rule (1–253 characters of `[A-Za-z0-9._\-/:]`) and the constructors refuse an invalid
or empty subject. The companion holds `inline given codec[A]: JsonValueCodec[Personal[A]] =
PersonalCodec(JsonCodecMaker.make[A])` so `Codecs.make[PlayerRegistered]` picks it up with no
declaration (FR-001): jsoniter resolves a field's codec from the field type's implicit scope, and an
`inline given` is expanded at the derivation site where `A` is concrete. If the inline given does not
resolve for a nested generic, the fallback is `given codec[A](using JsonValueCodec[A])` plus explicit
givens for the primitives in `Personal.codecs`; the first task of US1 settles which.
**Rationale**: every store passes through `Serializer` (spec, Context), `core` is the module every
other module and every derivation site sees, and `core` has no Pekko dependency so the type stays
inert. **Alternatives**: a `Personal` in `sdk` (the control plane and the agent module derive codecs
too, and `core`'s `Codecs` could not see it); a marker annotation on an ordinary field (jsoniter's
`CodecMakerConfig` has no per-field hook; a macro over field annotations was declined for the same
reason the wire-name macro was).

### R2. The envelope is one JSON object with a fixed key order, and the erased form is its prefix

**Decision**: present: `{"subject":"<s>","project":"<p>","data":"<base64>"}`, with `,"lookup":"<hex>"`
before the closing brace when the field is marked for lookup and the scope allows a token (R6);
erased: `{"subject":"<s>","project":"<p>"}`. `data` is `0x01 ‖ nonce(12) ‖ ciphertext ‖ tag(16)`
(the secret store's framing, `R/SecretCipher.scala:58`). Decoding: no `data` → `Erased`; `data` and a
destroyed key → `Erased`; `data` and a key → the value, decoded with the inner codec from the
plaintext bytes; `data` and a bad tag → `JsonReaderException("personal envelope corrupt")`, never
`Erased` (FR-004); unknown keys refused. `protocol/fixtures/personal/` holds envelopes under a fixed
test key, written by `PersonalFixturesSuite` in `core` as `EncodingFixturesSuite` writes its own, and
every SDK reads them (SC-008). **Rationale**: a fixed grammar is what lets a view row be redacted by
one SQL statement (R14) and lets four SDKs prove they wrote the same bytes. **Alternatives**: the
ciphertext inline as the field's value with a sidecar-known prefix (nothing outside the codec may
know the envelope, FR-006); a binary framing (the journal is JSON text, the row table is TEXT).

### R3. Associated data is subject and project; the plaintext is the value's JSON

**Decision** (revised in implementation): AAD = `subject ‖ 0x00 ‖ project` (FR-004). The plaintext is
the inner value's JSON in every SDK, a string included (`"ada@example.com"` with its quotes), so a
fixture row has one plaintext in every language. **Rationale**: the manifest was the first design;
it fails the commonest cross-service read — a consumer in another service or in Python decodes a
topic message under its own type and manifest, and every such envelope would read as corrupt. An
envelope moved between subjects or projects still fails, which is the splice that matters.
**Alternatives**: the manifest as well (above); the enclosing field name (the codec cannot see it);
a primitive's plaintext as raw text (one more rule each SDK could get wrong, for no gain).

### R4. The keyring handle is a scope the runtime sets around every serialization, not a JVM global

**Decision**: `C/personal/PersonalScope.scala`: a thread-local holding `(keyring: KeyringHandle,
project: String, manifest: String, lookupAllowed: Boolean)`; `PersonalScope.within(handle,
project)(body)` is set by the runtime at every place it calls a domain serializer: the event and
snapshot adapters (`R/EventSourcedEntityHost.scala:288,321`), the key value adapter
(`R/KeyValueEntityHost.scala:226`), the workflow adapter, `ProjectionRuntime`'s handlers and row
writes, `TopicHandlers`, `ProjectionSupport.applyConsumer`, `ViewQueries` and `KeyedViewHandlers`,
`ShardingTransport` (commands and replies), and `HttpServer` (request and response bodies). Outside
a scope every `Personal` encode or decode of a `Present` throws `CommandError(Unavailable)` naming
the keyring, which is FR-003's "before either" and `personal-fields.feature`'s "where no service
runs"; encoding an `Erased` needs no scope. `KeyringHandle` (core) is `key(project, subject, create:
Boolean): KeyResult` (`Available(bytes)`, `Destroyed`, `Refused`) and `lookupKey(project)`;
`KeyringHandle.unavailable` throws as `SecretStore.unavailable` does (`S/SecretStore.scala:43`).
`AnkkaService.keyring` is the service's handle as `secrets` is its store (`R/Ankka.scala:322,455`).
**Rationale**: the spec names `Keyring.current`, a global; a test JVM runs several kits — two
services of one project in US2's test and two projects in US5's — and a global cannot say which
service is encoding, while a thread-local set where the runtime already holds the service can. It
fails closed: a serialization site the runtime forgot to scope refuses the first personal field that
reaches it, in the first test that writes one. **Alternatives**: a global handle (wrong in every
multi-kit suite and silently so); wrapping every descriptor's serializers at registration (the
descriptors are immutable values in `sdk` handed over by the developer; the hosts are the runtime's).

### R5. The per-instance key cache is one class with the three bounds, and replay fetches once

**Decision**: `R/erasure/KeyCache.scala`: bounded (`ankka.erasure.cache.keys`, default 10,000, LRU),
each entry expiring 5 minutes after fetch while the keyring answers, kept through an outage for
`ankka.erasure.cache.outage-bound` (default 15 minutes) and then dropped, dropped at once on a
destroyed notice, and emptied when the channel is closed by the keyring (R8). A miss is one fetch
over the channel; a miss during an entity's recovery with no answer fails that recovery with
`Unavailable`, which Pekko's recovery failure turns into the actor stopping and sharding restarting
it on the next message (no backoff exists today; `R/EventSourcedEntityHost.scala` has no
`onRecoveryFailure`): the task adds `withRecoveryFailureBackoff` only if the k3s outage case shows a
hot loop. SC-007's 1.3× holds because one subject's 1,000 events are one fetch and 999 hits, measured
by `PersonalReplayBenchmark` in `testkit`'s tests against the in-memory keyring. **Rationale**: FR-020
and FR-023 name the numbers; a cache per instance is the only place they can be enforced.
**Alternatives**: a cache in the keyring handle per entity (a view's rows share subjects across
entities); no cache (every event a round trip).

### R6. A lookup token is a keyed hash written only into rows, and a declared query matches it

**Decision**: `LookupTokens.token(lookupKey, value)` = HMAC-SHA-256 over the field's plaintext
bytes, hex; the codec writes `"lookup"` only when the value is `lookup = true` *and* the scope says
`lookupAllowed`, which only `ProjectionRuntime`'s row writes set — so a journal never carries one.
A declared query matches `payload::jsonb->'email'->>'lookup' = :email`, and the caller computes the
parameter with `clients.lookupToken(value)` (`EndpointClients`, workflow, consumer and agent
contexts; the sidecar's `Client.LookupToken` rpc for a process; the `lookup_token` import for a
module). The project's lookup key is one per project, made by the keyring on first request, fetched
over the channel and cached like a subject key, never derivable from a dump (FR-012). `QueryCheck`
refuses a declared query that compares a personal field's `data` or the field itself. The
documentation says what a token leaks. **Rationale**: ciphertext cannot be matched; a keyed hash
matches equality and nothing else, and keeping it out of the journal keeps the journal's leak at
zero. **Alternatives**: deterministic encryption (leaks equality in every store, not only rows);
an index table beside the view (a second writer to keep in step on every row).

## The keyring and the channel

### R7. The keyring is an ankka application in `keyring/`, deployed as the control plane is

**Decision**: a new sbt project `keyring/` (image `ankka-keyring`, `publish / skip`), an ankka
application like `controlplane/`: `ProjectKeyEntity` (key value, id `<project>`: the project's
key-encryption key and lookup key, both wrapped by the root key), `SubjectKeyEntity` (key value, id
`<project>/<subject>`: the subject key wrapped by the project's KEK, or a tombstone with the erasure
id), `ErasureEntity` (event sourced, id `<erasureId>`: destroyed-at, the channels notified, each
channel's ack and each service's completion), an `ErasureRows` view, `KeyringEndpoint` (R10), the
`channel` socket route (R8), and `KeyringReplay` (R17). `K/components/keyring/`: a Deployment of two,
a CNPG `Cluster` of its own with the DDL ConfigMap and the `99-grants.sql` fragment exactly as
`components/postgres` does for the control plane, certificates `ankka://platform/keyring` from both
authorities, a network policy admitting every ankka-managed pod and the control plane to its port,
and an `HTTPRoute` at `keyring.<base>` for the machine route only. Its secret key is a Secret the
component carries like the control plane's. **Rationale**: the spec makes it a platform component of
ankka core; an ankka application gets sharding, a journal, mutual TLS, `Caller` from the certificate,
socket routes and the test kit for free, and the control plane is the precedent for every file.
**Alternatives**: a plain Pekko or JDK program (every one of those would be written again); part of
the control plane (the control plane's identity must not be able to read a key, FR-018).

### R8. One WebSocket per service instance carries fetches, notices, applies and completions

**Decision**: the service opens `wss://<keyring>/channel` once, as its own certificate (locally
plain `ws://` and `Caller.Local`), through the JDK's `java.net.http.WebSocket` client over
`RotatingTls`'s context (no dependency; `runtime` has no pekko-http client). JSON messages:
service → keyring `hello{project, service, instance, reads, appliedUpTo}`, `fetch{project, subject, create}`,
`lookupKey{}`, `ack{erasureId}`, `completed{erasureId, duties, handler}`; keyring → service
`key{subject, key, expiresAt}`, `destroyed{subject, erasureId}`, `refused{subject, reason}`,
`apply{erasureId, subject, reapply}`, `log{entries}` (the project's erasure log after `appliedUpTo`,
answered to `hello`), `close{reason}`. The keyring fans a destroyed notice out to every instance's
channels through a Pekko distributed pub-sub `Topic` per project — a channel is subscribed to its own
project's and to every project its `hello` named in `reads` and a `decrypt` grant admitted, so a
consumer in another project hears the destroy too — waits 60 seconds for each
channel's `ack` and closes a channel that has not answered (`close{"unacknowledged"}`), which the
client reads as "drop the whole cache" (FR-022). A sidecar holds the channel on the process's
behalf and mirrors it over `Client.SubjectKeys` (R11). **Rationale**: FR-022 asks for the channel
keys are fetched over to carry notices; the http module's socket routes are the one long-lived
duplex thing a service already serves, and a WebSocket is the one the JDK can open with no new
library. **Alternatives**: SSE for notices and POST for fetches (two channels, two reconnects, and
"could not confirm receipt" has no meaning on a stream nobody acks); a gRPC stream (the keyring
would need `grpc`, and the service a grpc-java client beside the JDK's).

### R9. Keys are wrapped in two layers with the secret store's cipher, and the root key is a secret of the keyring's own

**Decision**: `SecretCipher` (`R/SecretCipher.scala`) made `private[ankka]` and given a byte-array
form: the root key wraps each project's KEK and lookup key, the KEK wraps each subject key, with the
owning id as AAD. The root key is the secret `root-key` in the keyring's own secret store
(`DatabaseSecretStore` under the keyring's `ANKKA_SECRET_KEY`), generated on first start;
`RootKeySource` is the seam that takes 044's `wrapping-key` on Google Cloud, and until 044 the
Postgres source is the only one. **Rationale**: FR-017 names the layers and the source; the secret
store is 023's and 038 keeps it; a dump of the keyring's database then holds only wrapped keys.
**Alternatives**: one layer (an erasure of a project would need every subject key rewrapped);
the root key in a Kubernetes Secret (FR-017 forbids it).

### R10. The keyring's HTTP routes admit by certificate and by grants, and refuse the control plane a key

**Decision**: `KeyringEndpoint`: `POST /projects/{p}/erasures` (apply: write tombstone, destroy, fan
out, record), `POST /projects/{p}/erasures/{id}/reapply`, `GET /projects/{p}/erasures/{id}` (acks and
completions), `GET /status` (ready, which log copies it replayed, which was behind, whether it runs
with one copy), `POST /decrypt` (the machine route, R13), and the `channel` socket. Admission
(`Admission.scala`): a channel's `hello` for project `p` is admitted when the caller's certificate
names `ankka://p/<service>`, or when a grant admits the caller to `p` with `decrypt` (R12); a `fetch`
for a subject of `p` by a caller of another project is answered `refused` and recorded; the control
plane's identity (`ankka://platform/controlplane`) is admitted to the erasure routes and refused
every key (FR-018). Locally every caller is `Caller.Local` and the project is `local`. **Rationale**:
FR-018 in the words of `Caller.fromCertificate` (`H/Caller.scala:34`). **Alternatives**: a token per
service (the certificate already is one).

### R11. Protocol 1.15 adds three client rpcs, one process service and three module imports

**Decision**: `client.proto`: `SubjectKeys(stream KeyChannelIn) returns (stream KeyChannelOut)` — in:
`Fetch{subject, project, create}`, `Ack{erasure_id}`, `Completed{erasure_id, handler_outcome}`; out:
`Key{subject, project, key, expires_millis}`, `Destroyed{subject, project, erasure_id}`,
`Refused{…}`, `Apply{erasure_id, subject, reapply}`, `Closed{reason}`; `LookupToken(value) →
token`; `EraseObjects(subject) → {count, final_at_millis}`. `erasure.proto`: a process service
`Erasure { Handle(ErasureHandleRequest{subject, erasure_id, metadata}) → ErasureHandleReply{outcome} }`,
declared by `Spec.erasure_handler = true` in discovery. `wasm.proto`/`WASM-ABI.md`: imports
`subject_key`, `lookup_token`, `erase_objects` (each in its own `extern` block and entry function,
`.claude/rules/wasm.md`), export `ankka1_erase`. Every version site in `protocol/README.md`,
`API/Compatibility.scala:70`, `R/remote/Conversation.scala:177`, the three SDKs and their pinning
tests moves to 1.15. The sidecar's `GrpcConversation` keeps one `KeyringClient` (R8) and mirrors it
to the process: a process `Fetch` is a cache hit or one channel fetch, a `Destroyed` is forwarded,
the process's `Ack` completes the sidecar's. **Rationale**: a behaviour an older runtime must refuse
is a new call (`.claude/rules/sidecar.md`), and an SDK on an older sidecar reports `UNIMPLEMENTED`
as too old. **Alternatives**: the process holding the channel itself (the sidecar's certificate is
the identity, and the credential must not reach the process).

### R12. Grants reach the keyring through one reader, and until 040 only the test kit can fill it

**Decision**: `GrantReader` in `runtime` (`grants(principal): Set[Grant]`, `Grant(project, target,
attributes)`), read by the keyring's `Admission` and by the control plane's erasure authorization.
Two implementations now: `GrantReader.none` and the test kit's `GrantReader.of(...)`; 040 adds the
file reader over the rendered grants volume (its FR-010, FR-032). The shapes 040 must render for
042 are written in `contracts/grants.md` as a requirement on 040. **Rationale**: FR-018 and FR-031
admit by 040's grants; building the reader's file form here would decide 040's rendering for it.
**Alternatives**: an `erasureRights` list on the `Project` entity as a stopgap (a second grant
system 040 would have to migrate away from).

### R13. An outside machine decrypts field by field through one route, under a token the control plane issues

**Decision**: `POST /decrypt {envelope}` → `{value}` on the keyring, behind `Acl.Authenticate` from
`auth-oidc` over the issuer named by `ANKKA_AUTH_` on the keyring (the control plane's machine-token
issuer, 040 FR-014), admitted when a grant with `decrypt` names the machine (R12), refused once the
grant is revoked or the subject erased, every decryption recorded as an `ErasureEntity`-independent
`DecryptionRecorded` event on the `SubjectKeyEntity`. Tests mint tokens with `TestIssuer`. The
machine reads a topic through 040's broker access and never sees a key. **Rationale**: FR-031
verbatim; `auth-oidc` is the one verifier. **Alternatives**: issuing the machine a short-lived key
(FR-031 forbids a key leaving).

## The service's duties

### R14. A view row is redacted by one SQL statement over the envelope's fixed grammar

**Decision**: `ViewRedaction.redact(view, project, subject)`: `UPDATE <table> SET payload =
regexp_replace(payload, $pattern, $replacement, 'g'), updated_at = now() WHERE payload LIKE
'%"subject":"<s>"%'`, the pattern being the present envelope of R2 for that subject and project,
the replacement its erased form — under the view's exclusive advisory lock
(`ViewVersions.lockFragment`), one view at a time, every view of the service, run by whichever
instance holds the `apply` (every instance may; the statement is idempotent). Postgres's `regexp`
sees the subject as a literal because FR-002's alphabet has no regex metacharacter but `.`, which is
escaped. The same statement removes the lookup token. **Rationale**: a row table is `payload TEXT`
with no subject column (`R/ViewStore.scala:32`), the envelope grammar is fixed (R2), and a scan per
view per erasure is the cost of a rare, background job; nothing decodes a row to redact it, so a
view whose row type the service has since changed is redacted all the same. **Alternatives**: decode
and re-encode each row in Scala (needs a JSON tree library `core` lacks, and the row's current
codec); leave the ciphertext (the lookup token must go, and a redacted row needs no keyring to read
as erased).

### R15. Objects are erased by a minimal S3 client in `runtime`, and the bucket's variables reach the platform container

**Decision**: `R/erasure/ObjectErasure.scala`: SigV4 signing, `ListObjectVersions` (falling back to
`ListObjectsV2` when the store has no versioning), `DeleteObjects` in batches of 1,000, over
`java.net.http`, with `x-amz-checksum` off (Garage, `.claude/rules/kubernetes.md`); `erase(subject)`
deletes every version under `subjects/<subject>/` and returns `ErasedObjects(count, finalAt)`,
`finalAt` now on Garage and now plus the bucket's soft-delete window on GCS (read from the status
039 reports; until 039, Garage only). `PlatformVariables.ObjectStoragePrefix` moves from "the
program only" to shared, so the operator renders `ANKKA_S3_*` on the platform container too when the
service has a bucket (the sidecar and the module host run `erase` for a process and a module); the
docs' object storage page and `docs/reference/limitations.md` say so. **Rationale**: FR-026 wants one
call that is right on both backends and reaches a process through the sidecar; the AWS SDK is on
the test classpath only and is a heavy dependency for one call. **Alternatives**: each SDK's own S3
client (a module has none; four implementations of versions); the AWS SDK in `runtime` (published
to every service).

### R16. The service applies erasures from the keyring on every start, and is not ready until it has

**Decision**: `R/erasure/ErasureRuntime.scala`, a `RuntimeExtension` named `erasure`, registered by
`ServiceBuilder` whenever `ANKKA_KEYRING_URL` is set or the test kit installs a keyring: it opens
the channel (R8) at start, personal fields or none, and the operator renders the variable only when
it holds the keyring's address itself, an operator setting the keyring component patches on as the
`garage` and `broker` components patch theirs (`contracts/installation.md`); it opens the channel with `appliedUpTo` read from `ankka_erasures_applied(erasure_id, subject,
applied_at, handler_outcome)` (DDL `50-erasure-postgres.sql`, named in the seven lists), applies
every `log` entry and every `apply` message — drop the key, redact every view (R14), remove lookup
tokens, mark and stop the subject's sessions and instances (R19), run the erasure handler
(R18) — records the row, answers `completed`, and reports `readiness = true` only once the `hello`'s
log is applied. A restore rewinds the table, so a switched service reapplies before it is ready,
which is 041's FR-012 with no code of 041's; the extension's `appliedUpTo` and the time it finished
ride on the observe document for 041's status to read. A service with no keyring configured has no
extension and refuses every personal field (R4). **Rationale**: only the service knows its tables;
readiness is the one gate a rollout respects. **Alternatives**: the control plane calling a route on
the service (there is no control plane → service path but observe); a sidecar-side replay (the
sidecar never reads a row).

### R17. The keyring replays the union of both log copies before it answers, and locally runs with one

**Decision**: `KeyringReplay` (a `RuntimeExtension` of the keyring's, `readiness` false until done):
reads the control plane's copy at `GET /erasures/log?after=<n>` (`ANKKA_ERASURE_LOG_URL`, the
keyring's own identity admitted by `Acl.AllowCallers`) and the bucket's copy (`ANKKA_S3_*` of the
platform bucket, read-only key), unions by erasure id, ensures a tombstone and a destroyed key for
every entry, reports `behind: controlplane | bucket | neither` and the counts on `GET /status`, and
only then opens the channel route. With neither URL nor bucket (compose, a test) it replays its own
journal only and `GET /status` says `copies: 1`. A log write that fails leaves the control plane's
request `Failed` and destroys nothing (`restores.feature`, last scenario). **Rationale**: FR-021 and
the clarify answer on the second copy. **Alternatives**: the keyring writing the log itself (it must
not hold the only copy of its own undo).

### R18. The erasure handler is a service-level callback with a context of its own

**Decision**: `S/Erasure.scala`: `type ErasureHandler = ErasureContext => ErasureOutcome`;
`ErasureContext { subject, erasureId, objects: ObjectErasure (erase()), services, secrets }`;
`Ankka.service.withErasureHandler(handler)`. A process declares `erasure_handler` and answers
`Erasure.Handle`; a module exports `ankka1_erase`. The outcome carries what was erased and the
objects' finality; a handler that throws is a failed application, retried on the next sweep. A
service with no bucket that calls `erase` is answered `Refused("no bucket")` and its completion says
so (`objects.feature`). **Rationale**: FR-010, FR-026. **Alternatives**: a consumer of an erasure
topic (an erasure must be applied, not delivered).

### R19. A session, an instance or a task is tagged by its first call's metadata, and a platform view finds them

**Decision**: `AgentCalls.withSubject(subject)` / `TaskBuilder.withSubject(subject)` put
`ankka.subject` in the call's metadata; `SessionMemoryEntity` records `SubjectAssigned(subject)` on
first write and from then on persists `PersonalMessageAdded(Personal[StoredMessage])` (a new event
case beside the old ones, so pre-feature journals replay); `TaskEntity` records
`CreatedPersonal(Personal[TaskText])`; `InstanceEntity` carries the subject of the task it works. A
platform view `ankka-subject-index` (in `AgentRuntime.descriptors`) maps subject → session ids and
instance ids. On apply, `ErasureRuntime` asks the index and sends each session `markErased` and each
instance `terminate`; `SessionHistory` reads an `Erased` message as none and sets `erased = true`,
`PromptReplay` then starts with no messages and a system note that the earlier conversation was
erased (FR-025). `SessionCompactor` sees `Erased` summaries as nothing to summarise. **Rationale**:
there is no start-session call (`A/AgentRuntime.scala:1180`), metadata is the one per-call extra,
and a new event case is the one change that keeps old journals readable. **Alternatives**: a subject
on every message (the first message is the session's).

### R20. The test kit's keyring, erasure, outage and restore are four methods on `AnkkaTestKit`

**Decision**: `TK/InMemoryKeyring` (one per JVM, shared by every kit, projects kept apart, grants
settable); `AnkkaTestKit.start(..., keyring = InMemoryKeyring.shared, project = "local")`;
`kit.erase(subject)` (applies through the same `ErasureRuntime` path, synchronously);
`kit.keyringOutage { … }` (the handle answers nothing for the block); `kit.assertNoPersonalValue(values*)`
(every table of the kit's database, every column cast to text, plus base64 and hex forms, as
`secret.stored-encrypted` scans); `kit.snapshotDatabase(): Snapshot` and `kit.restoreDatabase(s)`
(stop, `CREATE DATABASE … TEMPLATE …` through `SharedPostgres`, start) — which is what US3's offline
cases need and what 041's real restore later replaces in the k3s suite. `EventSourcedTestKit` and
`KeyValueEntityTestKit` set a `PersonalScope` over the in-memory keyring so a unit test sees plain
values. **Rationale**: FR-030, and SC-002 without 041. **Alternatives**: a keyring container
(testcontainers is for things the JVM cannot host).

## The control plane

### R21. An erasure request is an entity, a view and a sweeper, and the audit is its journal

**Decision**: `CP/application/ErasureEntity` (event sourced, id `<project>/<erasureId>`): events
`Asked`, `Held`, `Withdrawn`, `Replaced`, `Overridden`, `LogWritten`, `KeyDestroyed`,
`ServiceCompleted`, `Applied`, `Final`, `ReapplyRequested`, `Failed`, each with `actor`/`at`;
`ErasureRows` (listing by subject, state, correlation id); `ErasureEndpoint` under
`/projects/{id}/erasures` (R22); `ErasureSweeper`, a cluster singleton like `ProjectionSweeper`
(`CP/deploy/ServiceProjector.scala:414`) at `sweep-interval`: applies held requests whose date has
passed (write the log, both copies, R23; then `POST` the keyring, R10), polls the keyring for
in-flight ones and records completions, and re-applies every applied one every
`erasure.reapply-interval` (15 minutes) until `finalAt` plus `erasure.reapply-grace` (30 days), when
the request becomes `Settled` and reapplies only on a member's `reapply` (FR-015); a handler run is
recorded on its first run, a failure and a changed outcome, never a repeat, as `Service.onObserved`
refuses an identical observation (FR-016); a service with no running instance at the destroy is
complete by absence and applies from the log at its next start. A refused ask by a service (FR-008) is
`ErasureRefused` on the `Project` entity, read through a new `Project.history` (as `Service.history`,
`CP/domain/model.scala:461`) and `GET /projects/{id}/history`. **Rationale**: the journal is the
audit (`CP/application/OrganizationEntity.scala:14`), and the sweeper shape exists. **Alternatives**:
a `TimerRuntime` in the control plane (it registers none; a singleton with a timer is the pattern).

### R22. The routes and the CLI mirror the secrets group, and the verb is `request`

**Decision**: `POST /projects/{id}/erasures` (`request`), `GET …/erasures?subject=&state=&correlation=`,
`GET …/erasures/{e}`, `DELETE …/erasures/{e}` (withdraw), `POST …/erasures/{e}/override`,
`GET …/erasures/{e}/certificate`, `GET /erasures/log?after=` (the keyring's); `ankka projects
erasures request <subject> [--not-before <date>] [--reason <text>] [--correlation <id>]
[--keyring <url>]`, `list [--subject] [--state] [--correlation]`, `get`, `withdraw`, `override
--reason`, `certificate`; the wire types and `problems` in `controlplane-api` (`API/descriptors.scala`),
`CliReferenceSuite` and `ControlPlaneRoutesReferenceSuite` regenerate the reference pages, and
`mcp/AnkkaTools` gains the mirrors. `--keyring` sends the request to a keyring directly, for a local
run with no control plane (FR-019). **Rationale**: FR-007, the clarify answer on the verb, and the
secrets group as the template (`cli/…/Main.scala:516`). **Alternatives**: none worth the table.

### R23. The erasure log's control-plane copy is an entity; the bucket copy is one object per erasure

**Decision**: `ErasureLogEntity` (event sourced, one instance, `Appended(entry)` with a sequence
number) is copy one; copy two is `erasure-log/<project>/<erasureId>.json` in the platform bucket
`ankka-platform-erasure-log`, written with R15's client under a credential the operator mints at
start (`Action.EnsurePlatformBucket`, `EnsureStorageCredential` for the control plane's namespace,
create-only, as `<service>-storage` is) and the control plane reads from its environment; an
installation with no object store has no second copy and `GET /status` of the keyring says so.
The sweeper writes copy one, then copy two, and only then asks the keyring; a failure at either
leaves the request `Failed` with the reason and nothing destroyed. At start the control plane lists
the bucket and appends what its entity lacks, logging how many it gained (041's FR-031, vacuous
until a restore). **Rationale**: the clarify answer; "write the cluster first" has the same shape.
**Alternatives**: a table outside the journal (a second persistence path in an entity-only
application).

### R24. Local: the keyring is two compose services, and the samples and templates run it

**Decision**: `docker-compose.yml` gains `keyring-db` (a second `postgres:17-alpine`, internal only)
and `keyring` (`ankka-keyring:latest`, port 9020, `ANKKA_CLUSTER_MODE=local`, its secret key from
`ANKKA_KEYRING_SECRET_KEY`); `sbt shoppingCart/run` reads `ANKKA_KEYRING_URL=http://localhost:9020`
(a platform variable, `RuntimeReadNames`, which the operator renders as
`https://ankka-keyring.ankka-keyring.svc:9020` on the platform container and a descriptor may not
set); the templates' compose files (`cli/src/main/templates/common-service/docker-compose.yml`,
`ankka.g8/src/main/g8/docker-compose.yml`) gain the same two services at the released image, and the
template suites assert the service starts with them. The image ships beside the others from the
`images` job. **Rationale**: the clarify answer. **Alternatives**: the keyring sharing compose's
Postgres (the keyring's database must not be a project's, and a second database in one container
needs an init script every template would carry).

### R25. Telemetry and logs: the text form is the guard, and the suites scan for leaks

**Decision**: `Personal.toString` is `Personal(<subject>)` in every SDK; nothing in the recorder,
the observe documents or the OTLP attributes carries a payload today (`RecordedSpanData.scala:71`),
so FR-029's attribute clause is pinned rather than implemented: `PersonalLeakSuite` in `testkit`
runs the feature's suites' plaintext values against the captured log (`LogCapturing`), the recorder
and the local console's `/sessions` document, and fails on any hit (SC-009). The local console's
session document shows an erased session as erased. **Rationale**: the cheapest proof is the scan.
**Alternatives**: a filter over span attributes (nothing to filter).

### R26. What is deferred, by name

US5's k3s proof, the `decrypt` and `erasure` grants, machine tokens and US8 wait for 040; the k3s
restore cases and the restore's status wait for 041; finality on GCS waits for 039; the wrapping
key waits for 044. Each is a task in `tasks.md` marked `[blocked: 0xx]` with the file it lands in,
so `/speckit-converge` finds it when the dependency lands.
