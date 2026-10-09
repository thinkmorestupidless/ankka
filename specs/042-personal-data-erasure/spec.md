# Feature Specification: Personal Data Erasure — Crypto-Shredding Per Data Subject

**Feature Branch**: `042-personal-data-erasure`

**Created**: 2026-10-08

**Status**: Draft

**Input**: User description: "Erasing personal data. A real-money operator must be able to erase a
player's personal data on request (GDPR Article 17) while keeping the ledger the regulator needs. ankka's
journal is append-only: deleting an event sourced entity appends a marker and every event stays, topics
never remove a message, an agent's cleared session keeps its transcript, and every backup keeps all of
it. Erase by crypto-shredding: personal fields are encrypted under a key per data subject, and erasure
destroys the key. Services mark fields, not entities, so amounts, ids and timestamps stay readable and a
ledger survives an erasure. An erasure is a platform request per subject in a project, applied in every
service of the project, which may carry a legal hold. Erasure must reach the journal, snapshots, key
value state, views, topics (consumers in other projects and outside machines included), agent
transcripts and object storage, and a restored backup must not bring an erased subject back."

## Clarifications

### Session 2026-10-08

- Q: How is personal data erased from the append-only journal, snapshots, topics and backups? → A:
  Crypto-shredding. Personal fields are encrypted under a key per data subject; erasure destroys the
  key. History is never rewritten.
- Q: How does a machine outside the installation, granted a topic under 040, read personal fields? → A:
  Redacted by default: they arrive as `Erased`. A grant may also allow decryption, and then the machine
  asks a keyring route to decrypt field by field under its token. Keys never leave the installation, so
  erasure reaches the partner's later reads, not copies it already stored.
- Q: What does a service mark as personal? → A: Fields, each belonging to a named data subject. Event,
  state, row and message schemas mark which fields are personal and whose they are. Everything else —
  amounts, ids, timestamps, statuses — stays readable, so ledgers, aggregates and views survive an
  erasure.
- Q: How is an erasure requested and carried across services and projects? → A: A platform erasure
  request per subject id in a project, filed by a member or by a service granted the right (spec 040
  for a service in another project). The platform applies it in every service of the project and
  records completion per service.
- Q: Regulators require some player data to be kept for years after closure. How does erasure meet a
  legal hold? → A: An erasure request may carry a not-before date and a hold reason. The platform
  shreds when the hold lapses and records that it did. The domain decides the date; the platform keeps
  it.
- Q: Where are the subject keys kept, so that restoring a backup cannot bring an erased subject back?
  → A: In a key store outside every project's database and backups, with its own backup policy, and an
  erasure log that is replayed after any restore — of the key store or of a project (spec 041) —
  before anything decrypts.
- Q: Which stores must erasure reach in this feature? → A: Journal, snapshots, key value state and
  views; topics, so consumers in other services and projects read redacted values; agent transcripts;
  and object storage. (How objects are reached was changed in review: see below.)
- Q: What does a handler see when it reads a field whose subject has been erased? → A: A typed
  redacted value. A personal field decodes as an explicit `Erased` in the SDK's type (`Personal[A]` is
  `Present(a)` or `Erased`), so replay and views keep working and code must handle the erased case.

### Session 2026-10-08 (review)

- Q: Where does encryption happen — in the runtime at the storage boundary, or in the SDK's codec? → A:
  In the codec. The runtime and the sidecar only ever hold bytes a serializer made, and the sidecar's
  contract is that it never inspects a payload; encrypting "at the boundary" would mean parsing and
  rewriting every payload on both sides. `Personal[A]`'s own codec encrypts on encode and decrypts, or
  yields `Erased`, on decode, reaching the keyring through a handle the runtime installs. Because
  every store passes through `Serializer`, one codec covers the journal, snapshots, key value state,
  view rows and topic messages alike. The earlier "no SDK sees cryptography" and the scan of declared
  serializers are withdrawn.
- Q: How do Python, TypeScript and Rust get it? → A: Each SDK has its own `Personal` type and codec
  and a process-local key cache, and one new sidecar rpc fetches keys and carries destroyed notices. A
  WebAssembly module gets a `keyring` host import beside 030's `request` and `clock`.
- Q: What is the trust boundary? → A: The subject key enters the service's process, exactly as the
  service's own secrets already do (023). A service that can read its journal can read its subjects'
  plaintext; that was always so. What the feature guarantees is that nothing *outside* a running,
  admitted service can.
- Q: A player known to payments and to a brand is two subjects. Who fans an erasure out? → A: The
  domain. An erasure request names one subject in one project; ankka does not know what a player is.
  The brand's service files into the other project under 040's `erasure` right, and each request
  carries an optional correlation id so the two audits can be joined.
- Q: How are a subject's objects erased, given that any S3 client writes to a bucket directly and
  Garage has no versioning? → A: By a call the SDK gains, `erase(subject)`, which deletes every object
  under the subject's prefix — every version on GCS, the one version on Garage — using the service's own
  credential; the service's erasure handler calls it. "Subject-tagged objects" and "a write for an
  erased subject is refused" are withdrawn: the platform cannot see a direct S3 write. The erasure
  handler runs again on every later application, so a late write is removed on the next pass.
- Q: Is the keyring ankka's, and how available must it be? → A: It is a platform component of ankka
  core — its admission is 040's, its restore is 041's, its wrapping key is 044's — not a separate
  product. It runs with two instances by default in Kubernetes; a service's cached keys keep serving
  reads during an outage for a bounded time, and a replay that needs an uncached key fails that
  entity's recovery and retries, never the service.
- Q: Where does the keyring's root key come from? → A: From 038's secret store on a Postgres
  installation (the keyring is a service with a secret store of its own) and from 044's `wrapping-key`
  request on Google Cloud. Never from a hand-made Kubernetes Secret.
- Q: What is the mechanism behind the 60-second bound? → A: A notice stream. Every service holds open
  the channel it fetches keys over, and the keyring pushes destroyed notices down it; a service that
  cannot confirm receipt within the bound drops its whole cache.
- Q: Is the subject id itself personal data? → A: Yes, and it is in the clear everywhere: it is the
  pseudonymous key the ledger must keep. That is the accepted residual of an erasure, and the
  documentation says so.
- Q: May a hold be overridden? → A: Only by an owner, with a recorded reason — a regulator's demand to
  destroy, or a data subject access request the law puts above the hold.
- Q: What does the data subject get back? → A: An erasure certificate: the request, the subject, each
  service's completion time and the time the erasure became final, fetchable by a member.
- Q: What about an erasure for a subject the keyring has never seen? → A: The keyring records a
  tombstone, so a later first write for that subject is refused rather than minting a key.
- Q: The two restore cases were told as one. → A: Split. A project restored while the keyring was not:
  the key is already gone; services only redact rows and drop lookup tokens. The keyring restored: the
  replay destroys the key again, then services redact. Services do the replay (only a service knows
  its view schema), and a service switched under 041 is not ready until it has.
- Q: Subject-key rotation that is not an erasure? → A: Out of scope.

### Session 2026-10-08 (clarify)

- Q: The keyring cannot know which subjects a topic carried. What does one `decrypt` grant on a
  topic admit its holder to? → A: Every subject key of the producing project. The holder only ever
  holds ciphertext it was granted anyway; the documentation says a project granting `decrypt` on one
  topic should assume it on every grant to that grantee.
- Q: Once a subject is erased, which writes of its personal fields does the codec refuse? → A: Only
  `Present`. Encoding `Erased` always succeeds, for any subject, and writes the subject-only envelope,
  so an entity, snapshot, state or row that already holds an erased subject keeps persisting.
- Q: What is the erasure log's second copy where the object store keeps one version (Garage), or where
  there is no object store? → A: One object per applied erasure, named by the erasure's id and never
  overwritten, so append-only needs no versioning; versioning is used where the store has it. An
  installation with no object store runs with the control plane's copy alone, and the keyring's
  status says so.
- Q: A local service has no project and no control plane. Where are its subject keys, and how is a
  subject erased there? → A: A keyring process in `docker compose`, beside Postgres and Keycloak,
  which a local service reaches by `ANKKA_KEYRING_URL` and the CLI asks for erasures from. The
  templates and the samples run it.
- Q: The glossary's 21 *Proposed* terms under *Erasure*, and `file`, which it refuses while the spec
  says "file" and "filer"? → A: All settled as written; `shredding` and `crypto-shredding` stay
  refused. The spec says "ask for" and "who asked for it", and the CLI command is
  `ankka projects erasures request`.

### Session 2026-10-09 (analysis)

- Q: How long does the platform keep re-running an applied erasure's handlers, and is every run
  recorded? → A: Until finality plus the installation's reapplication grace (default 30 days), after
  which the erasure is settled; a run is recorded on its first run, on a failure and when its outcome
  changes, never when it repeats.
- Q: What is the completion of a service with no running instance when the key is destroyed? → A:
  Complete by absence; it applies the erasure from the log on its next start before it reports ready.

## Context

A service's payloads are bytes its own serializer makes. `Serializer[A]` in `core` turns a value into
bytes under a manifest, and almost every serializer in practice is `Codecs.serializer`, jsoniter JSON
with a `type` discriminator. The runtime never looks inside: an event becomes a `JournalRecord(kind = 0,
manifest, payload)`, a snapshot a `StateRecord`, a key value state a durable state row, a view row the
`payload` text of `row_key | payload | updated_at`, a topic message the bytes the publisher handed over.
A Python, TypeScript or Rust service encodes in its own SDK (`json_codec`, `jsonCodec`, the crate's
codec) and the sidecar journals the bytes as they arrive, under a contract that it never inspects them.
So the one place a personal field exists as a typed value, on its way to every store, is inside the
codec that serializes it.

Nothing removes a personal value once written. `deleteEntity()` on an event sourced entity appends a
deletion marker (`kind = 1`) and every earlier event stays in `event_journal`; `expireAfter` appends an
expiry marker and an expired entity is noticed only lazily. A workflow's `delete()` appends `kind = 6`.
Snapshots are pruned (`snapshotEvery(n, 2)`), events never. A key value entity's deletion does remove
its value, as `Stored(empty, deleted = true)` at the next revision — but the old value stays in every
backup. A view drops a row when its source is deleted; a topic never drops a message, and a graph
consumer's tombstone leaves the record it tombstones in place. `SessionMemoryEntity.clear` persists
`Cleared` and keeps the transcript before it. Object storage (034) never deletes a bucket; 039 keeps
every version on GCS behind a soft-delete window and Garage keeps one version. There is no per-entity
key anywhere; the only encryption is the secret store's (023), AES-256-GCM under one key per service.

For eitheror this is the difference between a live casino and a regulatory finding. A player who closes
their account and asks to be forgotten must be forgotten — their name, email, address, date of birth,
documents and chat — while the operator keeps, for five years or more, that a player with this id
deposited these amounts and wagered these stakes. Rewriting an event journal to remove the first while
keeping the second breaks the one property event sourcing is for, and still leaves every backup and
every topic copy holding it.

Five decisions shape this feature.

- **Crypto-shredding, per data subject, per project.** Every personal field is stored encrypted under
  the **subject key** of the data subject it belongs to. A subject is an opaque id the domain chooses
  (`player/8c1f…`), scoped to one project: every service of a project that names the same subject uses
  the same key, and erasure destroys it once. Every copy of the ciphertext — journal, snapshot, view,
  topic, backup, a consumer's own store in another project — becomes unreadable at the same moment,
  without any of them being rewritten.
- **The codec encrypts; the runtime moves bytes.** `Personal[A]` is a type in every SDK — present with
  a subject and a value, or erased — and its codec is where the cryptography lives. On encode it takes
  the subject's key from a process-local cache (a miss is one fetch from the keyring; a first write for
  a new subject creates the key) and writes the **personal envelope**, the subject in the clear beside
  the ciphertext; on decode it takes the key and restores the value, or yields `Erased` when the
  keyring reports the key destroyed. The codec reaches the keyring through a scope the runtime sets
  around every serialization it performs, carrying the service's keyring handle and project; outside
  any scope the codec refuses as `Unavailable`, exactly as `SecretStore.unavailable` stands where no
  service runs, and the test kit sets a scope over an in-memory keyring.
  Every store a value reaches passes through `Serializer`, so the one codec covers the journal,
  snapshots, durable state, view rows and topic messages, and nothing outside it knows encryption is
  happening. The runtime, the sidecar and the journal see ordinary JSON. The costs are named: encryption
  sits on the persist path (a cached key costs microseconds beside the journal write already there; a
  miss costs one round trip), and the runtime-set scope is the one piece of ambient state the SDK
  gains.
- **A keyring in ankka, and an erasure log that survives restores.** Subject keys live in the
  installation's **keyring**, a platform component whose store is not a project database and is not in
  any project's backup. Each key is wrapped by its project's key-encryption key, which is wrapped by the
  installation's root key — from the keyring's own secret store (038) on a Postgres installation, from
  044's `wrapping-key` on Google Cloud. The keyring's own backups (041) would still hold a destroyed
  key, so every erasure is first written to the **erasure log**, kept in two places outside the
  keyring's database, and a restore of the keyring replays it before the keyring answers any read. The
  keyring admits callers by 040's identities and the grants 040 renders to it.
- **Erasure is a platform request with a hold, and the domain fans it out.** A member, or a service
  granted it, asks for an erasure request for one subject in one project, optionally with a not-before
  date, a reason and a correlation id. The platform holds it until the date, then destroys the key,
  records the erasure, and drives the work only a service can do — view rows to redact, lookup tokens
  to drop, agent sessions to forget, caches to drop — and runs each service's **erasure handler**, the
  one place a service does its own part, such as erasing the subject's objects. Completion is recorded
  per service. A held request can be withdrawn; an applied one cannot. A person known to two projects
  is two subjects; the domain asks twice and correlates.
- **Erased is a value, not an error.** `Personal[A]` decodes as `Present(a)` or `Erased` in every SDK.
  An entity whose player has been erased still replays, a balance still sums, a view still rebuilds; a
  handler that wants the email gets `Erased` and decides what that means. Nothing throws on replay.

What this feature is not: physical deletion of journal events or topic records (the ciphertext stays,
unreadable, until the topic's retention (043) or a backup's age removes it); a classifier that finds
personal data a service did not mark (marking is the service's responsibility, and the docs say so);
erasure across installations; re-encryption of events written before the feature (see Assumptions);
rotation of a subject key that is not an erasure; and the domain's retention rules, which decide when
a hold lapses.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A service marks personal fields and they are stored only encrypted (Priority: P1)

A developer's players service has an event `PlayerRegistered(playerId, email, name, dateOfBirth,
currency, registeredAt)`. They mark `email`, `name` and `dateOfBirth` as personal to the subject
`player/<playerId>` by giving them the type `Personal[String]` / `Personal[LocalDate]` and naming the
subject when constructing them. Their event's codec is derived as before and picks up `Personal`'s.
They dump the service's database: the journal row, the snapshot and the view row hold `currency`,
`registeredAt` and the id readable, and the three personal fields only as ciphertext beside their
subject. The entity, the view and a consumer all see the plain values.

**Why this priority**: Without encryption at write time there is nothing to shred. Every other story
depends on it, and every service eitheror ports must be written this way from its first event.

**Independent Test**: Through `AnkkaTestKit` with its in-memory keyring: register a player, dump every
table of the service's database, assert no personal value appears in any row in any encoding and every
non-personal field does; read the entity, the view row and a consumer's received event and assert the
plain values.

**Acceptance Scenarios**:

- added `features/erasure/personal-fields.feature`: the journal holds a personal field encrypted beside its data subject, and every other field as written
- added `features/erasure/personal-fields.feature`: a snapshot holds a personal field encrypted
- added `features/erasure/personal-fields.feature`: a key value entity's state holds a personal field encrypted
- added `features/erasure/personal-fields.feature`: a view's row holds a personal field encrypted and is read as the value
- added `features/erasure/personal-fields.feature`: a message on a topic holds a personal field encrypted and a consumer is handed the value
- added `features/erasure/languages.feature`: an SDK writes the same personal envelope and reads it back into its own personal type
- added `features/erasure/personal-fields.feature`: a data subject's subject key is made on its first write, once, whichever instance writes first
- added `features/erasure/personal-fields.feature`: a personal field cannot be written where no service runs

---

### User Story 2 - An erasure makes a subject's personal fields unreadable everywhere (Priority: P1)

A player asks to be forgotten. Their account is closed and no hold applies. A member asks for an erasure
for `player/8c1f…` in the brand project. Within a minute every service of the project reads that
player's email, name and date of birth as `Erased`: the players entity on replay, the backoffice view,
the wallet's journal (which recorded the name on a payout), the engagement consumer. The wallet's
balance, every amount and every id are unchanged. The erasure request shows applied, with each service's
completion, and the member fetches the certificate for the player's reply.

**Why this priority**: This is the obligation. Story 1 is how it is possible; this is the act.

**Independent Test**: Through `AnkkaTestKit` with two services sharing one in-memory keyring as one
project: write personal and non-personal fields for one subject and another, ask for an erasure for the
first, restart both services, replay every entity, read every view row and consumer delivery, and
assert the first subject's personal fields are `Erased` and everything else is as written, the second
subject included.

**Acceptance Scenarios**:

- added `features/erasure/erasing.feature`: an erasure request with no not-before date destroys the subject key and every service reads the data subject as erased
- added `features/erasure/erasing.feature`: an entity of an erased data subject is recovered with its personal fields erased
- added `features/erasure/erasing.feature`: a view holding rows of an erased data subject is rebuilt with its personal fields erased
- added `features/erasure/erasing.feature`: a new personal field cannot be written for an erased data subject
- added `features/erasure/erasing.feature`: a member reads an applied erasure request with each service's completion
- added `features/erasure/erasing.feature`: no table of any service of the project can give back a personal field of an erased data subject
- added `features/erasure/erasing.feature`: a member fetches the erasure certificate of an applied erasure request
- added `features/erasure/erasing.feature`: a service's erasure handler runs on every application of an erasure

---

### User Story 3 - A restored backup cannot bring an erased subject back (Priority: P1)

A month after an erasure, two different things go wrong on two different days. First the brand
project's database is restored to a point before the erasure (spec 041) while the keyring is untouched:
the key is already gone, so the restored journal is unreadable where it matters, and the only work is
the rows the restore brought back — services redact them before they report ready. Later the keyring's
own database is restored to a point before the erasure: the key is back in the restored store, so the
keyring replays the erasure log and destroys it again before it answers anyone, and then services redact
as before.

**Why this priority**: A backup that resurrects personal data makes every erasure provisional, and a
regulator's question about erasure is always "and the backups?".

**Independent Test**: Run in the k3s suite, as two cases. Case A: erase a subject; restore the project
database to a point before the erasure; switch a service; assert it does not answer until its replay
finished, and that afterwards the subject reads `Erased` in the journal, snapshots, durable state and
every view row and no lookup token remains. Case B: restore the keyring's database to a point before the
erasure; assert the keyring answers no key request until the log is replayed, and holds no key for the
subject afterwards.

**Acceptance Scenarios**:

- added `features/erasure/restores.feature`: a service restored to before an erasure is not ready until it has applied the erasure log to its own tables
- added `features/erasure/restores.feature`: an entity recovered from a restored database reads an erased data subject as erased, because the keyring was not restored
- added `features/erasure/restores.feature`: the keyring restored to before an erasure applies the erasure log before it answers any request
- added `features/erasure/restores.feature`: the keyring applies the union of both copies of the erasure log and reports the copy that was behind
- added `features/erasure/restores.feature`: an erasure whose erasure log write failed destroys no subject key

---

### User Story 4 - An erasure waits for a legal hold (Priority: P1)

A player closes their account. The operator's anti-money-laundering obligation is to keep identity data
for five years after the last transaction. The players service asks for an erasure for the player with a
not-before date five years out and the reason `aml-retention`. Nothing changes for five years; then the
key is destroyed without anyone acting. Two years in, a regulator orders the data destroyed: an owner
overrides the hold with the order's reference, and it is applied that day.

**Why this priority**: For a regulated operator nearly every erasure is held. A feature that erases only
on the day it is asked is unusable.

**Independent Test**: With a test clock: ask for an erasure with a not-before date; assert reads stay
`Present`; advance past the date; assert the subject is erased and the request records the hold reason
and the time it lapsed. Separately: ask for a held erasure, override it as an owner with a reason, assert
it applies at once and the override is recorded; attempt the same as a member and assert refusal.

**Acceptance Scenarios**:

- added `features/erasure/holds.feature`: an erasure request with a not-before date is held, and nothing is destroyed before the date
- added `features/erasure/holds.feature`: a held erasure request is applied when its not-before date passes, without anyone acting
- added `features/erasure/holds.feature`: a held erasure request is withdrawn before its not-before date
- added `features/erasure/holds.feature`: a held erasure request is replaced by a later one from whoever asked for it
- added `features/erasure/holds.feature`: an owner overrides a hold with a reason and the erasure request is applied at once
- added `features/erasure/holds.feature`: a member who is not an owner cannot override a hold
- added `features/erasure/holds.feature`: an applied erasure request cannot be withdrawn

---

### User Story 5 - Another project's consumer, and an outside machine, read an erased subject as erased (Priority: P2)

The shared payments project consumes the brand project's `players` topic under a grant carrying
`decrypt` (spec 040) to match a deposit's card holder name to the player's. An affiliate platform
outside the installation reads the brand's attribution topic under a machine grant. After the player is
erased, payments' consumer and its own view read the name as `Erased`, though payments never received an
erasure request and its own database still holds the ciphertext it stored.

**Why this priority**: eitheror's casino runs brands and payments as separate projects. Without this,
every cross-project copy is a separate erasure obligation nobody can discharge.

**Independent Test**: Two projects in one test installation: grant project B a consume with `decrypt`
on project A's topic; publish a personal field for a subject; B's consumer stores it in a view; erase
the subject in A; assert B's view and a replay of B's consumer read `Erased` without any request asked for
in B. Revoke the grant and assert B's next key request is refused.

**Acceptance Scenarios**:

- added `features/erasure/other-projects.feature`: a consumer in another project with a grant that allows decryption reads the value
- added `features/erasure/other-projects.feature`: a consumer in another project with a grant that does not allow decryption reads the field as erased
- added `features/erasure/other-projects.feature`: an erasure in the producing project reaches what another project kept of the data subject
- added `features/erasure/other-projects.feature`: a revoked grant ends another project's reads of the producing project's data subjects
- added `features/erasure/other-projects.feature`: a machine outside the installation with a grant that does not allow decryption reads every personal field as erased
- added `features/erasure/other-projects.feature`: a machine outside the installation with a grant that allows decryption asks the keyring to decrypt each field
- added `features/erasure/other-projects.feature`: the keyring refuses to decrypt for a machine outside the installation once its grant is revoked or the data subject is erased

---

### User Story 6 - An agent's conversation about a subject is forgotten (Priority: P2)

The player-support agent holds sessions with a player. Its transcript holds the player's own words,
which no field marking can reach: free text has no fields. The developer tags each session with the
subject when it starts. After an erasure the session's history reads as erased, an autonomous agent's
task for the player likewise, and the agent's next turn in such a session starts with no memory of it.

**Why this priority**: Chat transcripts are among the most personal data a casino holds, and agents are
an ankka component the casino plan relies on.

**Independent Test**: Start an agent session tagged with a subject, exchange messages, erase the
subject, restart, and assert the session's history decodes as erased and a new turn sees no prior
messages; a session not tagged is untouched.

**Acceptance Scenarios**:

- added `features/erasure/agents.feature`: a session started with a data subject keeps its conversation encrypted under the subject key
- added `features/erasure/agents.feature`: a session of an erased data subject reads as erased and a new turn begins with nothing
- added `features/erasure/agents.feature`: an agent instance or a task tagged with an erased data subject reads as erased and the agent instance is stopped
- added `features/erasure/agents.feature`: a session started with no data subject is unchanged by any erasure

---

### User Story 7 - A subject's objects are erased (Priority: P2)

The KYC service stores a player's passport scan in its bucket under the key
`subjects/player/8c1f…/passport.jpg`, the prefix convention the documentation gives. Its erasure
handler calls `erase(subject)`. On GCS every version of every object under the prefix is deleted and
the erasure of objects becomes final when the soft-delete window (039) has passed; on Garage the one
version is gone at once. The request shows the time of finality. A week later a stale job writes one
more scan under the prefix; the next application of the erasure runs the handler again and it is
removed.

**Why this priority**: Identity documents are the most sensitive data a casino holds, but a bucket is
written by any S3 client the service chooses (034), so the platform cannot stand between the service and
its objects; it can only give the service one call that does the right thing on every backend.

**Independent Test**: Against Garage in the test installation: store two objects under a subject's
prefix and one outside it; erase the subject; assert the two are gone and the third remains, and the
service's completion records the count. In the GCS suite (039), the same with versions: assert none
listed and the finality time reported as the soft-delete window.

**Acceptance Scenarios**:

- added `features/erasure/objects.feature`: every object under a data subject's subject prefix is erased, and the rest are kept
- added `features/erasure/objects.feature`: the erasure request says when the erasure of objects becomes final on an object store with a soft-delete window
- added `features/erasure/objects.feature`: an object written under the subject prefix after an erasure is removed on the next application
- added `features/erasure/objects.feature`: a service with no bucket cannot erase objects

---

### User Story 8 - A granted service asks for an erasure, and the domain fans it out (Priority: P3)

The players service decides when a player's hold ends and asks for the erasure itself, with the hold,
when the account closes. The same player is a subject in the shared payments project under a different
id the players service keeps a mapping for; it asks for a second erasure there, under a grant from payments
(040), with the same correlation id on both, so an auditor reading either project finds the other.

**Why this priority**: Members asking by hand works for the first erasures. A domain that owns its
retention rules needs to ask for them as code, and ankka does not know what a player is.

**Independent Test**: Grant a service the right to ask for erasures in its own project and in another;
ask from each with one correlation id; assert both are applied, the requests name the service that asked,
and a listing by correlation id returns both; assert a service without the grant is refused.

**Acceptance Scenarios**:

- added `features/erasure/asking.feature`: a service asks for an erasure request in its own project and is named as who asked for it
- added `features/erasure/asking.feature`: a service granted the right by another project asks for an erasure request there
- added `features/erasure/asking.feature`: a service without the right is refused and the refusal is recorded
- added `features/erasure/asking.feature`: a member lists the erasure requests of two projects by their correlation id in either project

---

### Edge Cases

- **A personal field with no subject.** An SDK refuses to construct a `Personal` without one; the
  codec never sees it.
- **One event, two subjects.** A transfer names two players. Each field carries its own subject; erasing
  one leaves the other's fields readable.
- **A subject whose key the keyring cannot reach.** A write needing an uncached key fails as
  `Unavailable` and nothing is persisted. A read during replay with no cached key and no answer from the
  keyring fails the entity's recovery, which the runtime retries with backoff; it never decodes as
  `Erased`, which is reserved for a key the keyring reports destroyed, and it never fails the service. A
  cached key keeps serving reads through an outage for the bound of FR-020; no erasure can be applied
  while the keyring is down, so a stale cache cannot leak an erased subject.
- **A personal field nested in a collection, an `Option` or another case class.** It is a field codec;
  it runs wherever the derived codec reaches it, at any depth.
- **A serializer that is not the SDK's codec.** `Serializer.bytes` and a hand-written serializer carry
  nothing the platform knows to be personal; the documentation says a personal field exists only
  through `Personal` and its codec.
- **A view's declared query filters on a personal field.** It cannot match ciphertext. A field marked
  for lookup carries a lookup token (FR-012); a query on a personal field not so marked is refused when
  the view starts.
- **A graph consumer or a graph sink receives a personal field.** It receives the decrypted value, and
  whatever it writes outside ankka is outside this feature; the documentation says so.
- **Telemetry and logs.** A personal value never appears in a span attribute, a log line, an error
  message or the local console's recorder (FR-029).
- **An erasure asked for twice.** The second for an applied subject is accepted and answered as already
  applied, recording nothing new but running every service's handler once more.
- **An erasure request for a subject never seen.** It is applied: the keyring records a tombstone for the
  subject, and a later first write for it is refused rather than minting a key. This is how a subject is
  erased before its data arrives (a migration).
- **A service added to the project after an erasure.** It reads the subject as erased from its first
  start; it has no completion to record until its handler has run once.
- **A rolling deploy during an erasure.** Old and new instances both drop the key from their caches;
  completion is recorded per service when every running instance has, within FR-022's bound.
- **A service with no running instance when the key is destroyed** (paused, or desired at zero). It has
  no cache to drop and no channel to answer; its completion is recorded as complete by absence, and it
  applies the erasure from the log on its next start before it reports ready.
- **A topic's retention (043) drops the encrypted records later.** Nothing to do; the ciphertext was
  already unreadable.
- **The keyring is down when an erasure is asked for.** The request is recorded and stays pending; it is
  applied when the keyring answers.
- **An envelope copied from one subject's record into another's.** It does not decrypt: the ciphertext
  is bound to its subject, project and manifest (FR-004), so a spliced envelope fails authentication
  and the codec reports it as corrupt, not as `Erased`.

## Requirements *(mandatory)*

### Functional Requirements

**Marking and encryption**

- **FR-001**: Every SDK MUST offer a personal type — `Personal[A]` in Scala, and the equivalent in
  Python, TypeScript and the Rust crate — whose value is either present with a subject or erased, and
  whose codec writes the **personal envelope** on encode: a JSON object carrying the subject and its
  project in the clear and the field's own encoding as ciphertext under the subject's key; and on decode restores the
  value, or yields the erased value when the keyring reports the key destroyed. A derived codec for a
  type with `Personal` fields MUST pick the personal codec up with no further declaration.
- **FR-002**: A subject id MUST be a string of 1 to 253 characters from letters, digits, `.`, `_`, `-`,
  `/` and `:`; it is opaque to the platform and scoped to one project.
- **FR-003**: The codec MUST reach the keyring through a scope the runtime sets around every
  serialization it performs, carrying the service's keyring handle and project, and the test kit sets
  for a test; outside any scope the codec MUST refuse to encode a present value or decrypt an envelope
  as `Unavailable`, naming the keyring, as `SecretStore.unavailable` does. For a process-hosted service the
  handle is the SDK's own, speaking to the sidecar over one new rpc that fetches a subject's key and
  carries destroyed notices; for a module it is a `keyring` host import beside 030's `request` and
  `clock`.
- **FR-004**: The ciphertext MUST be authenticated encryption under a key used for no other subject,
  with the subject, the project and the serializer's manifest as associated data, so an envelope moved
  between subjects or payload types fails to decrypt and is reported as corrupt, never as erased.
- **FR-005**: A subject's key MUST be created on its first write, once, whichever instance or service
  writes first, unless the keyring holds a tombstone for the subject, in which case the write MUST be
  refused naming the subject as erased.
- **FR-006**: The runtime, the sidecar and every store MUST handle a payload with personal envelopes as
  they handle any other bytes; nothing that carries a payload MUST parse, rewrite or depend on the
  envelope. The one exception is the platform's own redaction of a view row on erasure, which rewrites
  the envelope to its erased form by the grammar the fixtures pin and reads no value.

**Erasure**

- **FR-007**: A member MUST be able to ask for an erasure request for one subject in one project, through
  the control plane's API and the CLI (`ankka projects erasures request <subject> [--not-before <date>]
  [--reason <text>] [--correlation <id>]`), and to list and read requests by subject, by state and by
  correlation id.
- **FR-008**: A service MUST be able to ask for an erasure request through its service client in its own
  project when the project grants it the erasure right, and in another project when that project
  grants it (spec 040); a request names who asked for it. The platform MUST NOT fan a request out beyond its
  project; a subject known to two projects is two requests, which the domain asks for and MAY correlate.
- **FR-009**: Applying an erasure MUST, in this order: write the erasure to the erasure log (both
  copies, FR-017); destroy the subject's key in the keyring and record a tombstone so a later write is
  refused; push the destroyed notice to every service (FR-022); and record each service's completion
  when it has dropped the key from every instance's cache, redacted its view rows and removed its
  lookup tokens (FR-012), stopped and erased its agent sessions and instances for the subject
  (FR-025), and run its erasure handler to the end (FR-010).
- **FR-010**: Every SDK MUST let a service register one **erasure handler**, called with the subject on
  every application of an erasure in its project, run again on each later application, and expected to
  be idempotent; the platform's own duties (caches, rows, tokens, sessions) MUST NOT depend on it. For
  a process-hosted service the handler is a callback the sidecar protocol gains (a protocol version
  bump); a service without one has nothing of its own to do.
- **FR-011**: Decoding a personal envelope whose subject is erased MUST yield the erased value in every
  SDK, and MUST NOT fail replay, a rebuild, a query or a delivery.
- **FR-012**: A view row MUST hold personal fields encrypted, as every store does. A field marked for
  lookup MUST also carry a **lookup token** — a keyed hash under the project's lookup key, which the
  keyring holds and which is not a subject key and not derivable from any dump — that a declared query
  can match by equality; on erasure the service MUST remove every lookup token of the subject from its
  view rows. The documentation MUST state that a lookup token leaks equality and that anyone holding
  both the lookup key and the table can test guesses against it.
- **FR-013**: A write of a `Present` personal field for an erased subject MUST be refused by the
  codec with an error naming the subject as erased, and nothing MUST be persisted. Encoding `Erased`
  MUST always succeed, for any subject, and MUST write the subject-only envelope, so an entity,
  snapshot, state or row that already holds an erased subject keeps persisting.
- **FR-014**: An erasure request MAY carry a not-before date and a reason; it MUST then be held and
  applied by the platform when the date passes. A held request MUST be withdrawable and replaceable by
  whoever asked for it or a member; an applied one MUST NOT be. An owner MUST be able to override a hold with a
  recorded reason, applying the erasure at once; a member MUST NOT.
- **FR-015**: The platform MUST check held requests at least every 15 minutes, and MUST re-run an
  applied erasure's service handlers at least every 15 minutes until the erasure's finality plus the
  installation's reapplication grace (default 30 days, a platform setting); after that the erasure is
  **settled** and its handlers run again only on a member's request.
- **FR-016**: Every request, hold, replacement, withdrawal, override, application and per-service
  completion MUST be recorded in the control plane's audit with who did it and when; a handler run MUST
  be recorded on its first run, on any failure and whenever its outcome differs from the last recorded
  one, and MUST NOT be recorded when it repeats the last outcome, so a settled erasure's journal is
  bounded. Every record MUST be
  readable by members of the project, and an applied request MUST yield an **erasure certificate** — the
  request, the subject, who asked for it, each service's completion time and the time of finality, holding
  nothing personal — that a member can fetch.

**Keys, the keyring and restores**

- **FR-017**: The installation MUST run one **keyring**, a platform component of ankka, whose store is
  not a project database and is excluded from every project's backup and backed up in its own right
  (041). Subject keys MUST be wrapped by a per-project key-encryption key, itself wrapped by the
  installation's root key: on a Postgres installation a key the keyring keeps in its own secret store
  (038); on Google Cloud the key of 044's `wrapping-key` request for the keyring's identity. The erasure
  log MUST be kept in two places outside the keyring's database: the control plane's database and a
  platform bucket in the installation's object store, where each applied erasure is one object named
  by the erasure's id and never overwritten (versioned where the store keeps versions, 039; one
  version on Garage). An installation with no object store MUST run with the control plane's copy
  alone, and the keyring's status MUST say so.
- **FR-018**: The keyring MUST admit a request for a subject key only from a service of the subject's
  project, or from a principal holding a grant (spec 040) that carries `decrypt` on a topic of that
  project, identified as spec 040 identifies callers and read from the grants 040 renders to the
  keyring. A `decrypt` grant on any one topic admits its holder to every subject key of the project:
  the keyring does not know which subjects a topic carried, and the documentation MUST say that a
  project granting `decrypt` on one topic should assume it on every grant to that grantee. A refused
  fetch MUST be recorded. The control plane's identity MUST NOT be able to read a key.
- **FR-019**: The keyring MUST run with the number of instances the installation sets (default 2 in
  Kubernetes, 1 locally), and a request MUST be answered by any instance. Locally the keyring is a
  container in the bundled `docker compose`, beside Postgres and Keycloak; a local service reaches it
  by `ANKKA_KEYRING_URL`, a platform variable, and the CLI asks for erasures from it with no control
  plane. A local service that sets no `ANKKA_KEYRING_URL` refuses every personal field as
  `Unavailable`, naming the keyring (FR-003). The templates (`ankka init`) and the samples MUST run
  it.
- **FR-020**: A service instance MAY cache subject keys. The cache MUST be bounded (default 10,000 keys,
  a platform setting), MUST expire a key at most 5 minutes after it was fetched while the keyring
  answers, MUST keep serving reads from cached keys while the keyring does not answer for at most the
  outage bound (default 15 minutes, a platform setting) and then drop them, and MUST drop a key at once
  on a destroyed notice.
- **FR-021**: After a restore of the keyring's database, the keyring MUST replay the union of both
  erasure log copies before answering any request, and MUST report any copy that was behind. After a
  restore of a project's database (spec 041), each service switched to it MUST apply the erasure log's
  entries for its project to its own tables — lookup tokens removed, view rows redacted, agent sessions
  marked — before it reports ready; the restore's status MUST say when this finished. The keyring not
  having been restored changes nothing in the second case: the key is already gone.
- **FR-022**: Every service MUST hold open to the keyring the channel it fetches keys over, and the
  keyring MUST push each destroyed notice down every such channel. From the moment an erasure's key is
  destroyed, no instance of any service of the project, and no consumer in another project, MUST decrypt
  a personal field of the subject more than 60 seconds later; an instance that cannot confirm it
  received the notice within that time MUST drop its whole cache.
- **FR-023**: Replay MUST fetch each subject's key at most once per entity recovery, and a recovery of
  an entity of 1,000 events with personal fields of one subject MUST take no more than 1.3 times as long
  as the same recovery without personal fields, measured locally against a keyring on the same
  machine.

**Agents, objects, topics and telemetry**

- **FR-024**: An agent session and an autonomous agent instance MUST be able to be started with a
  subject, carried in the session's or instance's state; the agent module MUST then wrap every message,
  tool call, tool result, summary and task input or result it persists in the personal envelope under
  that subject, through the same codec a service uses.
- **FR-025**: On erasure an agent session's history MUST read as erased and a new turn MUST start with
  an empty history and tell the model that the earlier conversation was erased; an autonomous agent
  instance tagged with the subject MUST be stopped and its state
  read as erased. The documentation MUST say that what a session sent to its model provider is beyond
  the installation's edge and this feature.
- **FR-026**: Every SDK MUST offer one object storage call, `erase(subject)`, that deletes every object
  under the subject's prefix in the service's bucket — every version on GCS, the one version on Garage
  — using the service's own credential (034), and reports the count; a process-hosted service reaches
  it through the sidecar. The documentation MUST give the prefix convention (`subjects/<subject>/`) and
  say that an object stored outside it is not erased by this call. The request MUST show the time the
  erasure of objects becomes final: at once on Garage, after the soft-delete window on GCS (039).
- **FR-027**: A message published to a topic MUST carry its personal fields encrypted exactly as a
  journal does, because the same codec wrote it; a consumer in another project MUST decrypt with the
  producing project's keys under FR-018, and its own stores MUST keep the fields encrypted under the
  producing project's keys.
- **FR-028**: Subject-key rotation that is not an erasure is out of scope; a subject has one key for its
  life, destroyed once.
- **FR-029**: No personal value MUST appear in a log line, a span attribute, an error message, the
  recorder, or the local console. `Personal`'s textual form in every SDK MUST be `Personal(<subject>)`
  and nothing else, so an interpolated event prints no value. The feature's suites MUST scan the
  captured log, the recorder and the local console's documents for every plaintext they wrote and fail
  on a hit.
- **FR-030**: The test kit MUST install an in-memory keyring, offer a way to erase a subject and to
  simulate a keyring outage in a test, and offer an assertion that a database dump holds no personal
  value.

**Outside machines**

- **FR-031**: A machine outside the installation granted a topic (spec 040) MUST receive every personal
  field as `Erased` unless its grant also carries `decrypt`. When it does, the machine MUST ask a
  keyring route to decrypt field by field under its own token; no subject key leaves the installation.
  Every decryption MUST be recorded against the machine and the grant, a revoked grant or an erased
  subject MUST be refused on the next request, and the documentation MUST say plainly that erasure
  reaches the partner's later reads, not copies it has already stored.

### Key Entities

- **Data subject**: the person a personal field is about, named by an opaque id the domain chooses,
  scoped to one project. The id itself is in the clear everywhere and survives an erasure.
- **Personal field**: a field of a payload typed `Personal`; stored only encrypted.
- **Personal envelope**: the JSON shape a personal field takes in every store — the subject and its
  project in the clear beside ciphertext, or the subject and project alone once erased.
- **Subject key**: the key one subject's personal fields are encrypted under in one project; created on
  first write, destroyed by an erasure, never rotated.
- **Keyring**: the platform component that keeps subject keys and the lookup keys, wrapped, outside
  every project's database; admits callers by 040's identities and grants.
- **Keyring handle**: what a codec calls; installed by the runtime or the test kit, `unavailable`
  before.
- **Tombstone**: the keyring's record of an erased subject, kept so no later write mints a key.
- **Erasure request**: a request to erase one subject in one project — asked for, held, withdrawn,
  overridden or applied — with who asked for it, its hold, correlation id and per-service completion.
- **Erasure handler**: the one callback a service registers to do its own part of an erasure, run on
  every application.
- **Erasure log**: the append-only record of every applied erasure, kept in two places, replayed after
  any restore.
- **Erasure certificate**: what a member fetches for an applied request: the request, the subject, who
  asked for it, completions and finality; nothing personal.
- **Lookup token**: a keyed hash of a personal field's value that lets a declared query match it by
  equality, removed on erasure.
- **Erased**: the value a personal field decodes to once its subject's key is destroyed.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: After a subject is erased, a dump of every database of every service in the project, and
  of every topic it published to, holds no value from which a personal field of the subject can be
  recovered — checked by the test kit's assertion and by a search for every plain value written.
- **SC-002**: A restore of the project's database to a point before an erasure, with the keyring
  untouched, leaves the subject erased: no switched service answers before its replay finishes, and
  afterwards every personal field of the subject reads `Erased` and no lookup token for it remains.
- **SC-003**: A restore of the keyring's database to a point before an erasure leaves the subject
  erased: the keyring answers no request before the log is replayed, and holds no key for the subject
  afterwards.
- **SC-004**: 60 seconds after an erasure's key is destroyed, no instance of any service in the project
  or in a project consuming its topics decrypts a personal field of the subject (FR-022).
- **SC-005**: Every non-personal field of an erased subject's events, state, rows and messages reads
  exactly as before the erasure, and every entity and view holding them replays and rebuilds.
- **SC-006**: A held erasure is applied within 15 minutes of its date with no one acting (FR-015), and
  every service reads the subject as erased within 60 seconds more (FR-022).
- **SC-007**: Recovering an entity of 1,000 events with personal fields takes at most 1.3 times as long
  as without them (FR-023).
- **SC-008**: The same event with personal fields, persisted by a Scala, a Python, a TypeScript and a
  Rust module service, is stored in the same envelope and decodes in each; the sidecar's conformance
  suite proves it read none of them.
- **SC-009**: No log line, span or recorder entry from a full run of the feature's suites contains a
  personal value written by them, including runs that interpolate whole events into log lines.
- **SC-010**: With the keyring stopped, a service keeps answering reads of cached subjects for the
  outage bound, applies no erasure, and resumes within one fetch of the keyring's return; no entity
  recovery that failed during the outage is lost.
- **SC-011**: After an erasure, every object under the subject's prefix in the service's bucket is gone
  on Garage at once and unlisted on GCS with finality at the soft-delete window; an object written under
  the prefix afterwards is gone within 15 minutes.

## Assumptions

- **Events written before this feature are not re-encrypted.** A service gains personal fields from the
  version that declares them; earlier events stay as written. eitheror's migration from the twin
  substrate imports one opening event per entity (the casino plan's Phase 4), so its journals start
  encrypted — and the old journal kept read-only for regulatory retention still holds plaintext, which
  the migration plan, not this feature, must account for. A service with an existing journal that
  needs its history covered rebuilds the entity under a new id from a migration, a path the
  documentation describes and this feature does not automate.
- **The subject id is the accepted residual.** It is personal data under GDPR when linkable, and it
  stays in the clear in every envelope, journal row, view key and topic record, because the ledger the
  regulator needs is keyed by it. The documentation says so, and says to choose an id that is not
  itself a name, an email or a document number.
- **Subjects are per project.** A person who is a subject in two projects is two subjects; the domain
  asks for an erasure in each (a granted service can, FR-008) and correlates them.
- **The key enters the service.** A codec that decrypts holds the key in the service's process, as the
  service's secrets already are; the trust boundary is the running, admitted service, not the codec.
- **The domain decides holds.** The platform does not know AML or gambling-licence retention periods;
  it keeps the date it is given, and an owner can override it.
- **Physical removal follows retention.** Ciphertext stays in journals, topics and backups until topic
  retention (043) or backup retention (041) removes it; crypto-shredding is what makes it unreadable
  before then, which is the standard regulators accept for encrypted backups.
- **Marking is the service's responsibility.** The platform encrypts what is typed `Personal` and
  cannot know what is not. The documentation gives a checklist for a regulated service (names, contact
  details, dates of birth, addresses, document numbers, IP addresses, free text a person wrote).
- **One subject key per subject per project**, not per field or per service: one erasure, one key.
- **The persist path pays for encryption.** A cached key costs microseconds beside the journal write
  that is already there; a miss costs one round trip to the keyring; FR-023 bounds the total.
- **`erase(subject)` is the first storage call the SDK carries.** 034 chose no SDK storage client so
  any S3 client would do; this one call is the exception, because the two backends differ in exactly
  the way an erasure cares about (versions) and the service should not have to know.
- **The keyring's root key on a Postgres installation** is only as safe as 038's store; a real-money
  installation on Google Cloud uses 044's wrapping key.

## Dependencies

- **038 (secret store backends)**: the keyring's root key on a Postgres installation is kept in the
  keyring's own secret store.
- **039 (object storage on GCS)**: versioning and the soft-delete window decide when the erasure of
  objects is final; Garage has one version; the platform bucket for the erasure log's second copy.
- **040 (cross-project access)**: the `decrypt` attribute on a topic grant, the `erasure` right, and
  grants rendered to the keyring; machine identities for FR-031.
- **041 (Postgres backup and recovery)**: the keyring's database backed up in its own bucket; a
  switched service replays the erasure log before it is ready; the control plane reconciles its copy of
  the log from the bucket after its own restore.
- **043 (topic retention)**: physical removal of encrypted topic records.
- **044 (cloud provider)**: the `wrapping-key` request for the keyring's identity on Google Cloud.
- **030 (wasm request and clock)**: the host import module the `keyring` import joins.

## Glossary terms

Settled in `GLOSSARY.md` under *Erasure* (clarify session of 2026-10-08): data subject, personal field,
personal envelope, subject key, keyring, lookup token, lookup key, erasure, erased, erasure request,
not-before date, withdrawn, completion, erasure handler, erasure log, erasure certificate, correlation
id, decryption, subject prefix, soft-delete window, switched; `shredding` and `crypto-shredding` are
refused. The features and this spec say "asks for" an erasure request, not "files" one (`file` is a
refused synonym of `object`), and the CLI verb is `request`; "hold" stays an everyday word with
`not-before date` as the term; the keyring handle and the tombstone are not named in any scenario; and
`encrypted` was widened to cover a personal field.

## Open Questions

None. The review session settled the encryption placement, fan-out, objects, keyring availability, the
notice mechanism, holds, certificates and the restore cases.
