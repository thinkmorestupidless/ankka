# Data Model: Personal Data Erasure

The values this feature adds, where each lives, and how each changes. Research decisions are cited
as R*n* (`research.md`).

## SDK values (every language)

| Value | Scala (`core`) | Java / Kotlin | Python | TypeScript | Go | Rust |
|---|---|---|---|---|---|---|
| Data subject | `opaque type DataSubject = String` | `DataSubject` record | `DataSubject` (str newtype) | `DataSubject` (branded string) | `ankka.DataSubject` | `DataSubject` newtype |
| Personal field | `enum Personal[+A] { Present(subject, value); Erased(subject) }` | `sealed interface Personal<A>` with `Present`, `Erased` | `Personal[T]` with `Present`, `Erased` | `Personal<T>` union with `kind: "present" \| "erased"` | `Personal[T]` struct with `Erased() bool` | `enum Personal<T> { Present{…}, Erased{…} }` |
| Textual form | `Personal(<subject>)` in every language (FR-029, R24) |||||

**DataSubject** (FR-002): 1 to 253 characters from `[A-Za-z0-9._/:-]`. Construction of a value that
breaks the rule fails in every SDK with the same message; the rule is held to
`protocol/fixtures/personal/subjects.json`, which every SDK's tests read.

**Personal**: constructed only with a subject (no subject is a compile error in Scala, Java, Kotlin,
TypeScript and Rust, and a construction error in Python and Go). `Erased` is produced only by decode.
Encoding `Erased` writes the subject-only envelope (a row being re-written after a redaction).

## The personal envelope (every store)

```json
{"$personal": 1, "project": "brand", "subject": "player/8c1f", "c": "<base64(nonce ‖ ciphertext ‖ tag)>"}
{"$personal": 1, "project": "brand", "subject": "player/8c1f"}
```

- `$personal`: the envelope's version; a reader refuses a version it does not know.
- `project`: the project whose subject key sealed the field, in the clear, so a consumer in another
  project and a follower installation know whose key to ask for and what to bind as associated data.
- `c`: AES-256-GCM of the field's own JSON encoding under the subject key, 96-bit random nonce,
  associated data `len‖subject ‖ len‖project ‖ len‖manifest` (R2, R11). Absent once redacted.
- Held to `protocol/fixtures/personal/envelopes.json`: a fixed key, nonce and plaintext per case,
  which every SDK must produce byte for byte and open (SC-008).

## The edge form (R26, FR-034)

In an endpoint's reply or a caller's body, where no `Serializer` is encoding:

```json
{"subject": "player/8c1f", "value": "ada@example.com"}
{"subject": "player/8c1f"}
```

Held to `protocol/fixtures/personal/edge.json`.

## Keyring store (the keyring's own database, R10)

| Table | Columns | Notes |
|---|---|---|
| `kek` | `project TEXT PK, wrapped BYTEA, root_ref TEXT, created_at` | the project's key-encryption key, wrapped by the root key |
| `lookup_key` | `project TEXT PK, wrapped BYTEA, created_at` | wrapped by the project's KEK |
| `subject_key` | `project, subject, wrapped BYTEA, created_at, PK(project, subject)` | made on first write, `INSERT … ON CONFLICT DO NOTHING` then read back, so concurrent first writes agree (FR-005) |
| `tombstone` | `project, subject, erasure_seq BIGINT, destroyed_at, PK(project, subject)` | written in the same transaction as the key's `DELETE`; a fetch for a tombstoned subject answers `Destroyed`; a create is refused |
| `notice_ack` | `erasure_seq, project, service, instance, acked_at, PK(erasure_seq, instance)` | completions (R15) |
| `applied_log` | `erasure_seq BIGINT PK, project, subject, applied_at` | what this database has applied; the replay after a restore reads the gap |
| `fetch_refusal` | `at, caller, project, subject, reason` | FR-018's record |
| `decryption` | `at, machine, grant, project, subject, field_count` | FR-031's record; no value |

After a `DELETE` from `subject_key`, the keyring runs `VACUUM subject_key` (R10).

## Erasure request (control plane, R21)

`ErasureRequestEntity`, id `<project>/<rid>`; `rid` a ULID.

| Field | Type | Rules |
|---|---|---|
| `project` | project id | the request's project; never carried beyond it (FR-008) |
| `subject` | `DataSubject` | FR-002 |
| `askedBy` | `Actor` (member or service `ankka://p/s`) | a service needs the `erasure` grant of `project` |
| `notBefore` | `Option[Instant]` | absent: applied at once |
| `reason` | `Option[String]` | ≤ 500 chars, nothing personal by documentation |
| `correlation` | `Option[String]` | ≤ 128 chars, same character rule as a subject |
| `state` | `Held \| Applying \| Applied \| Withdrawn` | below |
| `override` | `Option[(owner, reason, at)]` | owners only |
| `erasureSeq` | `Option[Long]` | the log's sequence once written |
| `destroyedAt` | `Option[Instant]` | from the keyring |
| `completions` | `Map[service, Completion]` | `Completion(at, rowsRedacted, tokensDropped, sessionsEnded, handler: Option[HandlerReport])` |
| `objectsFinalAt` | `Option[Instant]` | the latest finality any handler reported |
| `finalAt` | `Option[Instant]` | when every service has completed and objects are final |
| `lastRunAt` | `Option[Instant]` | the last re-run of the handlers; a quiet run sets only this (a state-only update, not an audited event) |

```text
            ask (notBefore in future)          date passes / owner override
   ──────▶ Held ───────────────────────────────────────────────────────────▶ Applying
     │       │ withdraw (filer or member)              ▲                         │
     │       ▼                                         │ log written, key gone   │
     │    Withdrawn                                    │                         ▼
     └── ask (no notBefore) ───────────────────────────┘                      Applied
                                       replace (filer or member) : Held → Held (new notBefore/reason)
```

- An ask for a subject already `Applied` answers that request, records nothing new, and asks the
  keyring to re-send the notice (edge case: filed twice).
- `Applying` with a failed log write stays `Applying`, destroys nothing, and is retried by the sweep.
- `Applied` is final; withdraw, replace and override on it answer 409.

Events (each with `actor: Option[Actor]`, `at: Option[Instant]`): `Asked`, `Replaced`, `Withdrawn`,
`HoldOverridden`, `LogWritten(seq)`, `KeyDestroyed(at)`, `ServiceCompleted(service, completion)`,
`HandlerRan(service, report, at)`, `ObjectsFinal(at)`, `Finalised(at)`.

View `erasure_requests` (control plane): `project, rid, subject, state, correlation, not_before, asked_by, applied_at, final_at`.

## Erasure log (R22)

One entry per application: `seq BIGINT, project, subject, rid, applied_at`. Kept in the control
plane's `erasure_log` table and as one object `erasure-log/<seq>-<project>-<sha256(subject)>.json`
in `platform.erasure-log`. The subject is in the object's body, not its name.

## Erasure certificate

JSON (and a printable text form from the CLI and the console):

```json
{"request": "brand/01J…", "project": "brand", "subject": "player/8c1f",
 "askedBy": "ankka://brand/players", "askedAt": "…", "hold": {"notBefore": "…", "reason": "aml-retention", "lapsedAt": "…"},
 "override": null, "keyDestroyedAt": "…",
 "services": [{"service": "players", "completedAt": "…", "handler": {"objectsErased": 2, "finality": "immediate"}}],
 "finalAt": "…"}
```

Holds no personal field (FR-016).

## Descriptor and resource

- `service.json` / `ServiceSpec`: `"personal": true | false` (default false). Refused at apply when
  the installation's erasure is off (FR-033).
- `AnkkaService` spec: `personal: Boolean` (schema updated, `CrdSchemaSuite`); the operator renders
  `ANKKA_KEYRING_ADDRESS` for a personal service and the keyring's NetworkPolicy admits it.
- View descriptor: `personalFields: Vector[FieldPath]`, `lookupFields: Vector[FieldPath]` (R12).

## View tables

A view whose row type has lookup fields gets `lookup JSONB` (null when none); made by the view's
start DDL as `payload` is. Redaction and token removal are in R13, R14.

## Agents (R23)

- `SessionMemory` state and `SessionMemoryEvent.Started`: `subject: Option[DataSubject]` (NON_ABSENT).
- `UserMessage.text`, `AiMessage.text`, `ToolCall.arguments`, `ToolResultMessage.content`,
  `SummaryMessage.text`, `SuspendedTurn.payload`: written as a personal envelope when the session has
  a subject; a reader accepts both shapes.
- `InstanceRecord`, `TaskEvent.Created.instructions`, `Completed.result`, `ApprovalRequest.arguments`:
  likewise under the instance's subject.

## Settings

| Name | Where | Default |
|---|---|---|
| `ANKKA_ERASURE` | `ankka-platform`, `PlatformVariables` | `off` |
| `ANKKA_KEYRING_INSTANCES` | `ankka-platform` | `2` |
| `ANKKA_ERASURE_RERUN_WINDOW` | `ankka-platform`, control plane | `30d` |
| `ANKKA_KEYRING_ADDRESS` | rendered per personal service | the keyring's service |
| `ankka.keyring.cache.max-keys` | runtime `reference.conf` | 10000 |
| `ankka.keyring.cache.ttl` | runtime | 5 minutes |
| `ankka.keyring.cache.outage-bound` | runtime | 15 minutes |
| `ankka.keyring.notice-bound` | runtime | 60 seconds |
| `ankka.keyring.fetch-timeout` | runtime | 2 seconds |
