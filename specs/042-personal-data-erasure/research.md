# Research: Personal Data Erasure

Decisions, each with what it rests on. File references are to `main` at the plan's date (2026-11-04).
`CORE`, `SDK`, `RT`, `HTTP`, `AG`, `TK` are `modules/{core,sdk,runtime,http,agent,testkit}/src/main/scala/com/thinkmorestupidless/ankka/<module>/`;
`CP` is `controlplane/src/main/scala/com/thinkmorestupidless/ankka/controlplane/`; `OP` the operator's;
`SC` the sidecar's.

The survey found six things the spec did not know. Each is decided below and written back into the
spec as a planning amendment:

- a codec never sees the manifest the spec makes associated data (R2);
- the Python, TypeScript and Go codecs are synchronous, and a key fetch is not (R6);
- no rpc pushes anything to a process (R7);
- entity hosts have no supervision, so "recovery fails and is retried with backoff" has no
  mechanism yet (R4);
- a declared query knows nothing of a row's fields, so "a query on a personal field is refused at
  start" has nothing to check against (R12);
- the replication transport is fixed per provider and carries nothing but journals and forwarded
  commands (R17).

## R1. `Personal[A]` is a type in `core`, and its codec is a field codec in the companion

**Decision**: `CORE/Personal.scala` holds `enum Personal[+A] { case Present(subject: DataSubject, value: A); case Erased(subject: DataSubject) }`, the `DataSubject` opaque type with FR-002's rule, and a `given [A](using JsonValueCodec[A]): JsonValueCodec[Personal[A]]`. A derived codec of an enclosing type picks it up with no declaration, as `Phase`, `Struggle`, `TaskStatus` and `RunStatus` already are (`AG/autonomous/InstanceEntity.scala:12-35`, `ENCODING.md`). `toString` is `Personal(<subject>)` for both cases (FR-029). `core` gains no dependency: the cipher is `javax.crypto`.

**Evidence**: `Codecs.make` (`CORE/Codecs.scala:59-67`) derives with the shared config; companion-given field codecs are found at any depth (collections, `Option`, nested case classes), which is the spec's "nested" edge case.

**Alternatives**: an annotation on the field (`@personal email: String`) needs a macro and a second derivation path, and loses "code must handle the erased case" at compile time.

## R2. The manifest reaches the codec through a scoped encoding context

**Decision**: `Serializer.json` (`CORE/Serializer.scala:23-32`) binds a `PersonalScope(manifest)` in a `ThreadLocal` for exactly the duration of `writeToArray`/`readFromArray`, and the personal codec reads it for the associated data (subject, project, manifest; FR-004). The project comes from the keyring handle (R3). Outside a `Serializer` (a raw `writeToArray`) the scope is absent and the codec refuses to encode, naming `Serializer`.

**Evidence**: `JsonValueCodec` has no context parameter and `toBytes`/`fromBytes` pass only the value (`Serializer.scala:30-31`); the Java `Serializer` interface is the same. A thread-local is sound here because encode and decode are synchronous calls on one thread (the virtual-thread rule of `CLAUDE.md` concerns work handed to another thread, which never happens inside a codec).

**Alternatives**: dropping the manifest from the associated data weakens FR-004 (an envelope could move between payload types of one subject unnoticed); a `Serializer` per call site with the manifest captured in the codec means a codec per manifest, which derivation cannot give.

## R3. One process-level keyring handle, `Keyring.current`, installed by the runtime

**Decision**: `CORE/Keyring.scala` defines the handle trait (`seal(subject, plaintext, ad)`, `open(subject, ciphertext, ad)`, returning `Opened.Value | Opened.Erased`) and `Keyring.unavailable`, which refuses as `Unavailable` naming the keyring (FR-003). `Keyring.current` is a `@volatile` holder set by `Ankka` when the service starts and cleared when it stops, following `GrpcClients`' `setting`/`current` (`modules/grpc/.../GrpcClients.scala:65,99-102`). `AnkkaTestKit` installs an in-memory keyring before the service and swaps it on `restartService` (the runtime.md:282-285 trap: nothing may cache the dead system's handle; the codec reads `current` on every call).

**Evidence**: there is no `current` for stores today (`SecretStore` is passed explicitly, `RT/Ankka.scala:339-378`), but a codec is reached from derivation, never from a host, so it cannot be passed one. The spec names this "the one piece of global state the SDK gains". Two services in one JVM (a test kit with two kits) need two handles: `current` is therefore a `@volatile` default plus a thread-local override that each host binds around its serializer calls, and the override wins. `MultiProjectKeyringSuite` proves the two-kit case.

## R4. Entity hosts gain one supervision: restart with backoff on `KeyringUnavailable`

**Decision**: `EventSourcedEntityHost`, `KeyValueEntityHost`, `WorkflowHost` and the agent hosts wrap their behaviour in `Behaviors.supervise(...).onFailure[KeyringUnavailable](SupervisorStrategy.restartWithBackoff(1.s, 30.s, 0.2))`. Any other failure keeps today's behaviour (stop; sharding restarts on the next message). A caller waiting during the backoff gets the existing ask timeout. A read decoded during an outage never becomes `Erased` (edge case, FR-020).

**Evidence**: no `Behaviors.supervise`, `SupervisorStrategy` or `RecoveryFailed` exists in any main source; recovery failure stops the actor (`RT/EventSourcedEntityHost.scala:113-144`, `746-747`). Backoff exists only in Kafka, replication (`HostReplication.scala:189`) and timers.

## R5. A key miss blocks the entity's thread, bounded

**Decision**: the handle's `open`/`seal` on a cache miss fetches synchronously with a deadline (`ankka.keyring.fetch-timeout`, default 2s), then throws `KeyringUnavailable`. Replay fetches each subject's key at most once per recovery because the cache holds it (FR-023). The cost is measured by `PersonalRecoveryBenchmark` (SC-007) against a keyring on the same machine.

**Evidence**: event and snapshot adapters decode on the entity's actor (`EventSourcedEntityHost.scala:695, 746, 774`); `SecretStore.scala:20-22` keeps blocking reads off that path, which is why a read of a *secret* is refused to an entity. A key fetch is different in kind: it happens once per subject per cache lifetime, not per command. The default dispatcher is not virtual; the deadline bounds the stall.

**Alternatives**: decrypt lazily on access (`Personal.value` decrypting on demand) moves the stall into handlers and makes `Erased` a thing that can happen in the middle of a command; prefetching subjects before recovery needs the journal read twice.

## R6. Async SDKs fetch keys before the synchronous codec runs

**Decision**: in Python, TypeScript and Go, encode and decode stay synchronous. Before a decode, the SDK scans the payload's JSON for personal envelopes (the subject is in the clear) and fetches every uncached subject's key; before an encode, it walks the value for `Personal` instances. The codec then finds every key in the process-local cache. A subject the keyring reports destroyed is cached as destroyed. Java and Kotlin go through the JVM handle (R3); Rust modules through the host import (R9).

**Evidence**: `Codec` is synchronous in Python (`sdks/python/src/ankka/codec.py:35`), TypeScript (`sdks/typescript/src/codec.ts:17`) and Go (`sdks/go/codec.go:23`); Node cannot block on a gRPC call. Every payload passes through the SDK's dispatch before its codec, so there is one place per SDK for the prefetch.

## R6a. Python takes `cryptography` for AES-GCM

**Decision**: the Python SDK adds `cryptography` (≥ 44, < 47) and seals and opens in process, as
every other SDK does.

**Evidence**: the standard library has `hashlib` and `hmac` (enough for lookup tokens) but no AES.
The SDK already depends on native wheels (`grpcio`, `protobuf`, `rfc8785`; `sdks/python/pyproject.toml`).

**Alternatives**: sealing and opening through the sidecar over two more rpcs puts every plaintext
value in the sidecar's memory, which the sidecar's contract says it never inspects, and costs a round
trip per payload with personal fields.

## R7. One new rpc pair for processes: `GetSubjectKey` and a process-started notice stream

**Decision**: `client.proto` gains `rpc GetSubjectKey(SubjectKeyRequest) returns (SubjectKeyReply)` and `rpc SubjectNotices(SubjectNoticesRequest) returns (stream SubjectNotice)`. The process opens the stream at start and holds it, which is the spec's "channel it fetches keys over". The sidecar relays each destroyed notice from the runtime's keyring client. Through `ClientService.drain`, as `Watch` does, so a notice is sent only as the process reads. The process acks each notice with `AckSubjectNotice` once its cache is dropped and its handler has run (FR-009's completion). Protocol 1.23; `ErasureSince = 23`; each SDK refuses personal fields under an older runtime, naming 1.23.

**Evidence**: every streaming rpc on `Client` is started by the process (`client.proto:12,45-46,57`); there is no push (sidecar.md: "a behaviour an older runtime must refuse is a new call, not a new field"). The version is 1.22 in eight places (`Conversation.scala:218`, `Discovery.scala:73`, `Compatibility.scala:86`, the four SDKs, the Go example's `service.json`).

## R8. The erasure handler is a component the process serves, like MCP

**Decision**: a JVM service registers one handler with `ServiceBuilder.withErasureHandler(ErasureHandler(ctx => subject => ...))`, its context carrying the component client, secrets and `storage` (R16). A process registers it in discovery (`Spec.erasure_handler = true`) and serves a new `Erasure` service with one `Invoke(ErasureCall) returns (ErasureReply)`, wired as `Mcp` is: `Conversation.invokeErasure` defaulting to a throw, `GrpcConversation`, `WasmConversation`, `Discovery`'s gate. A module exports `erase`, added to `ModuleLoader.Exports` and classified in `WasmImportsSuite`.

**Evidence**: MCP (1.20) is the nearest precedent for a callback that is not a component handler (`discovery.proto:20-24`, `Conversation.scala:413`, `GrpcConversation.scala:95, 522`, `WasmConversation.scala:407`, `Discovery.scala:179`).

## R9. A module's `keyring` import seals and opens in the host; the key never enters the module

**Decision**: `HostImports` gains `keyring_seal` and `keyring_open` (bytes in, bytes out, through `bytes(name)(fn)`), answered by the sidecar's JVM handle. They are permitted in every call site, entities' `handle` and `fold` included: unlike `request`, they are deterministic given the key and cause no effect. The crate's `Personal<T>` serde implementation calls them through dedicated `call_*` functions (wasm.md: a shared `match` is imported by every module). `WASM-ABI.md`, `ModuleLoader.Imports` and the native stub follow.

**Evidence**: `HostImports.scala:74-96, 254, 283`; `CallSite.mayRequest` refuses `handle` and `fold` (`CallSite.scala:34,65-76`), so the keyring imports get their own permission; the crate's newtype precedent is `Bytes` and `Instant` (`sdks/rust/ankka/src/codec/mod.rs:122-135`, `codec/time.rs:129`).

## R10. The keyring is a standalone platform component with its own database, not an ankka application

**Decision**: a new sbt project `keyring` (image `ankka-keyring`), depending on `core`, `grpc`'s server support and `auth-oidc`, never on `runtime`. Keys live in a plain Postgres table on its own CNPG cluster in `ankka-keyring`, made by code as `secret-reads` does (`components/secret-reads`, `ReadRecordStore.scala:135`). Destroying a key is a `DELETE` of its row and a tombstone row in one transaction, followed by `VACUUM` of the table so no dead tuple holds it. It speaks gRPC over mutual TLS as `ankka://platform/keyring`, issued by the `ankka-service` ClusterIssuer and checked at start as the model keeper checks its own (`model-keeper` `Main.Identity`, `components/models/certificate.yaml:13-32`). It is a kustomize component, `components/keyring`, which an installation adds to turn erasure on (R19).

**Evidence**: an ankka application keeps its state in an append-only journal, where a destroyed key would stay forever: the one store the keyring must not be. The control plane is an ankka application by principle 6, but "the operator deliberately is not"; the keyring is the second such exception, and for a reason the journal itself gives.

**Alternatives**: keys in the control plane's database puts every key in the control plane's backups and its identity next to them (FR-018 forbids the control plane reading a key); Google Cloud KMS per subject costs one KMS key per player and is Google-only.

## R11. Keys are AES-256-GCM, wrapped twice

**Decision**: a subject key is 32 random bytes; a field is sealed with AES-256-GCM, a random 96-bit nonce, and the associated data `subject ‖ project ‖ manifest` (length-prefixed). The envelope is `{"$personal":1,"subject":"…","c":"<base64 nonce‖ciphertext‖tag>"}`, and after an erasure the same envelope still decodes (to `Erased`) because the subject is read before the key. Each project's key-encryption key wraps its subject keys and its lookup key, and the root key wraps each project's: on a Postgres installation it is a 32-byte key in the keyring's own secret store under 038's `postgres` backend, on Google Cloud the KMS key of a `wrapping-key` request with the keyring as requester (R18). `SecretCipher` is the precedent for the cipher and the associated data (`RT/SecretCipher.scala:44-87`).

## R12. A view's personal fields are known to the view, from the row type

**Decision**: a view's descriptor carries the row's personal field paths and which are marked for lookup. They are derived where the SDK can see the type: Scala by a `Mirror`-based `PersonalFields.of[Row]` given at registration, Java and Kotlin from record components, TypeScript from the shape, Go by reflection, Python from type hints. A Rust module's view declares them by hand. `QueryCheck` (`RT/QueryCheck.scala:91-123`) then refuses at start any predicate on a personal path not marked for lookup, and rewrites an equality on a lookup path to the token column. The row writer refuses a row holding an envelope at a path the view did not declare (a hand-written codec), failing the projection with the path named.

**Evidence**: `DeclaredQuery` is only `view, name, statement, watchable` (`SDK/DeclaredQuery.scala:31-36`); `QueryCheck` parses with JSqlParser and checks names, tables and functions but no fields.

## R13. Lookup tokens are a second column, not inside the payload

**Decision**: a view whose row has lookup fields gets a `lookup jsonb` column (`{"email":"<token>"}`), added by the view's own DDL at start like `row_key`/`payload`; a token is HMAC-SHA-256 of the field's canonical encoding under the project's lookup key, fetched once per process from the keyring. An equality on a lookup path compares `lookup->>'email'` with the token of the query's parameter, computed in the query's process. Erasure removes the subject's tokens with one `UPDATE … SET lookup = NULL` over the rows that hold the subject (R14).

**Evidence**: view tables are `row_key TEXT PRIMARY KEY, payload TEXT, updated_at TIMESTAMPTZ` (`RT/ViewStore.scala:34-37`), made per view at start; a token inside the payload would be under the payload's codec and could not be removed without re-encoding the row.

## R14. A service finds a subject's rows by the subject in the clear

**Decision**: redaction runs `UPDATE <view> SET payload = <envelopes reduced to the subject>, lookup = NULL WHERE payload::jsonb @? '$.** ? (@."$personal" == 1 && @.subject == $s)'`, view by view, in the service that owns the view, on a destroyed notice and on the restore replay (FR-021). Rows are rewritten only in what they hold of the erased subject; ciphertext of any other subject in the same row stays. The redaction is the projection's own write path, so the view's version is not changed.

**Evidence**: Postgres jsonpath (`@?`) needs no index for a correctness pass and matches nested envelopes at any depth; the projection already writes rows (`ProjectionSupport.scala:148`).

## R15. A service's completion goes to the keyring, and the control plane reads it

**Decision**: each instance acks a destroyed notice on its open channel when its cache is dropped, its rows redacted, its tokens dropped, its sessions marked and (for the instance that won the subject's singleton lease, R16) its handler finished. The keyring records acks per instance and reports a service complete when every instance connected at the notice has acked, or has disconnected and its successor has acked. The control plane polls `ErasureStatus(request)` from the keyring, a call that returns no key, and records completion in the request's entity. The keyring's 60-second bound (FR-022) is enforced on the instance: no ack within it and the instance drops its whole cache.

**Evidence**: services cannot call the control plane today; status reaches it through the operator (`StatusIngest`). The keyring is already the one party every instance is connected to.

## R16. The erasure handler runs once per application per service, on one instance

**Decision**: the runtime runs the handler through a cluster singleton per service (Pekko `ClusterSingleton`, as the timer sweeper is), so two instances do not both erase objects. Its context has `client`, `secrets` and `storage`. `context.storage.erase(subject)` lists `subjects/<subject>/` and deletes every object; on GCS it lists versions (`ListObjectVersions`, `versions` query) and deletes each by version id, which 055's client does not do today and this feature adds to `S3Bucket` and the four other stores. It returns the count and the store's finality (`immediate`, or `after(softDeleteDays)` from `ANKKA_OBJECT_STORE_SOFT_DELETE_DAYS`, `OP/ObjectStorage.scala:206-215`).

**Evidence**: 055's storage client is `RT/storage/` with `ObjectStorage.unavailable` in contexts (`SDK/contexts.scala:46`); a process speaks S3 itself, so Python, TypeScript and Go implement `erase` in their own clients, and a module's goes through the `storage` import.

## R17. Followers reach the home's keyring through a relay SPI the replication layer implements

**Decision**: `RT/replication/KeyringRelay.scala` is a new ServiceLoader SPI beside `ReplicationTransportProvider`: `fetch(home, project, subject): Future[SubjectKeyReply]` and `notices(home): Source[SubjectNotice]`. The runtime's keyring client, for a subject key of a replicated component, asks the relay with the entity's home; the default relay (no provider) answers `Unavailable` naming the relay, so a follower without one fails recovery and retries (R4), never decodes `Erased`. The home's keyring admits a relayed fetch only for an installation in its admitted list (`HostReplication.admission`, `:412-419`), carried as the relay's peer identity. The in-JVM test relay goes through `LocalInstallations`. ankka-replication implements the relay over its peer link on its own release.

**Evidence**: `ReplicationTransport` carries only `records`, `forward`, `report`, `locate`, `advanced`, `reachable` (`ReplicationTransport.scala:48-98`), and 057 R5 changed the provider signature with no default because ankka-replication pins releases. A separate SPI leaves that signature alone. `ReplicationPeer` (`ReplicationPeer.scala:16`) is the precedent for a seam the transport fills.

## R18. The keyring is a platform requester of a `wrapping-key`

**Decision**: `CloudRequests` gains a platform requester (`Requester.Platform("keyring")`) and the operator renders `CloudRequests.wrappingKey` (`OP/CloudRequests.scala:309-321`) for it when erasure is on and the installation's cloud is Google; the keyring reads the output's key name and calls KMS `encrypt`/`decrypt` through the cloud identity the request makes. On a Postgres installation the operator does nothing: the keyring makes its root key in its own secret store at first start.

**Evidence**: a `Requester` is only a service or a project today (`CloudRequests.scala:113-147`); nothing in main calls `wrappingKey`; `CloudKinds.WrappingKey` exists (`crd/CloudResource.scala:98-115`).

## R19. Erasure on is a platform setting and a component

**Decision**: `ANKKA_ERASURE` (`on`/`off`, default `off`) and `ANKKA_KEYRING_INSTANCES` (default 2) are declared in `PlatformVariables` and set in `ankka-platform`, copied by replacements to the control plane and the operator. `components/keyring` adds the Deployment, its CNPG cluster, the certificate, the NetworkPolicy and the patch that sets `ANKKA_ERASURE=on`. `InstallationEndpoint` reports `InstallationErasure(enabled, keyringInstances)` from the setting (`CP/api/InstallationEndpoint.scala:71-96`, now `None`). `PUT /services/...` refuses a descriptor with `"personal": true` when erasure is off, beside `objectStorageProblems` (`ServiceEndpoint.scala:154-170`). The operator gives every service `ANKKA_KEYRING_ADDRESS` when on.

**Evidence**: kubernetes.md's rule that a platform setting comes from `ankka-platform` through replacements, and that `RemoteOverlaySuite` renders a copy with distinct values.

## R20. Locally the keyring is in-process, in the service's database

**Decision**: under `sbt run`, `ankka local` and the test kits, the runtime installs `LocalKeyring`, which keeps keys in an `ankka_keyring` table of the service's own database (made by code) and destroys them with `DELETE` and a tombstone. Erasures are filed with `ankka local erasures file <subject>` against the local console's control routes. The documentation says the local keyring is for development, because its keys share the database's backups. `AnkkaTestKit` uses an in-memory one by default and `LocalKeyring` on request.

## R21. Erasure requests are their own entity in the control plane, not a field of the project

**Decision**: `ErasureRequestEntity` (event sourced, id `<project>/<request-id>`), events `Asked`, `Replaced`, `Withdrawn`, `HoldOverridden`, `LogWritten`, `KeyDestroyed`, `ServiceCompleted`, `HandlerRan`, `ObjectsFinal`, `Finalised` (as `data-model.md` lists them), each with `actor`/`at` defaulted per control-plane.md. A view `erasure_requests` lists by project, subject, state and correlation id. `ErasureSweep`, a cluster-singleton sweep every 5 minutes like `RetentionSweeper` (`SecretRecords.scala:107-119`), applies lapsed holds and re-runs handlers every 15 minutes for the re-run window after application (`ANKKA_ERASURE_RERUN_WINDOW`, default 30 days; FR-015) by asking the keyring to re-send the notice. A quiet run (nothing erased, no failure) sets `lastRunAt` instead of an event, so the journal grows only by what happened. The routes are `/projects/{id}/erasures[/{rid}[/withdraw|/override|/certificate]]`, members by `Authorization.project`, overrides by `projectOwner`, and a service with the erasure grant by its certificate (`GrantsFile` reads the `erasure` entries the operator already renders, `ProjectConfig.scala:86`). The audit is the entity's events, readable through the project history.

**Evidence**: grants live on `ProjectEntity` (`ProjectEntity.scala:89-104`), but a casino may hold hundreds of thousands of erasure requests, which a project's state must not grow by.

## R22. The erasure log: a table and a bucket, applied before anything answers

**Decision**: the control plane writes each application to `erasure_log` (made by code in its database) and as one object `erasure-log/<seq>-<project>-<subject-hash>.json` in the platform bucket `platform.erasure-log` (`Buckets.scala:84-92` reserves the `platform.` prefix; GCS versioning with a retention lock, Garage append-only by key), both before it asks the keyring to destroy. A failure of either write leaves the request `Applying` and destroys nothing (`restores.feature`). After a restore of its database, the keyring reads the union of the two (it has read access to the bucket and to the control plane's `GET /internal/erasure-log`), reports the copy that was behind, and destroys again before serving. `RestoreHold`'s `reconcilers` hook (`RestoreHold.scala:22-27,69`) brings the control plane's copy up to date from the bucket after its own restore. A service switched to a restore (`ServiceEntity.switchDatabase`) holds `ExtensionsReadiness` (`Ankka.scala:387-390` precedent) until it has applied its project's entries (R14 over every entry).

## R23. Agents wrap what they persist when a session has a subject

**Decision**: `SessionMemoryEntity`'s messages, `SuspendedTurn.payload`, instance records and task events gain `Personal` wrapping of their text fields when the session or instance carries `subject: Option[DataSubject]` (new optional fields, `NON_ABSENT`, `EventCompatibilitySuite`-style fixtures for the old shapes). A session read with an erased subject has an empty history; an instance tagged with an erased subject is stopped by its host on the destroyed notice. `ObservabilityEndpoint` serves session memory with personal fields as `Personal(<subject>)`.

**Evidence**: `AG/SessionMemoryEntity.scala:70-95, 302-303`; `AG/memory.scala:17-51`; `AG/autonomous/TaskEntity.scala:88-105`; `Approvals.scala:13-21`.

## R24. Telemetry drops envelopes and prints subjects only

**Decision**: `Personal`'s `toString` and every SDK's textual form print `Personal(<subject>)`. The recorder (`RT/Recorder.scala:96,176-181`) and the OTLP export (`RecordedSpanData.scala:79-81`) drop an attribute whose value parses as a personal envelope. `PersonalLeakSuite` runs the feature's suites with an appender that fails on any plain value written by them (SC-009).

## R25. The console and the MCP server

**Decision**: the console gains an *Erasures* section of the project page (console.md: sections are routes, no new control plane route for the console), with filing, withdrawal, an owner's override and the certificate's download, its schemas and the fake control plane extended, and Playwright cases. `AnkkaTools.controlPlane` gains `erasures_list`, `erasure_get`, `erasure_file`, `erasure_withdraw`, `erasure_override`, `erasure_certificate`, acting as the logged-in member.

## Planning amendments written into the spec

- FR-004: the manifest reaches the codec through a scoped encoding context (R2).
- FR-003: async SDKs fetch keys before a synchronous codec (R6); the process's channel is `SubjectNotices`, started by the process (R7).
- Edge case on keyring outages: recovery is retried by a supervision added for it (R4).
- Edge case on declared queries: a view knows its personal fields from the row type, by hand in Rust (R12).
- FR-032: the follower's fetch goes through a relay SPI the replication layer implements (R17).
- FR-017: the keyring is a standalone component, not an ankka application, because a journal cannot forget (R10).

## Declined remediations

None yet.

## Design finished

2026-11-04: analyze reported nothing above LOW.
