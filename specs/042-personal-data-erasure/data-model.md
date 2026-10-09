# Data model: Personal Data Erasure

The shapes this feature adds or changes, as Scala where the code is Scala, with each SDK's
equivalent named. Validation rules follow each shape. `// new` marks a field or case added to an
existing type.

## `Personal[A]` (core)

```scala
enum Personal[+A]:
  case Present(subject: String, value: A, origin: Option[String] = None, lookup: Boolean = false)
  case Erased(subject: String, origin: Option[String] = None)

object Personal:
  def present[A](subject: String, value: A): Personal[A]      // refuses an invalid subject
  def lookup[A](subject: String, value: A): Personal[A]       // Present with lookup = true
  inline given codec[A]: JsonValueCodec[Personal[A]]          // the envelope, R1–R3
  object DataSubject:
    val Pattern = "[A-Za-z0-9._\\-/:]{1,253}".r
    def problems(subject: String): Vector[String]
```

- `subject`: 1–253 characters of `A–Z a–z 0–9 . _ - / :` (FR-002); refused at construction and by
  every SDK's constructor; never empty.
- `origin`: the producing project when the value was decoded from an envelope of another project
  (a consumer under a `decrypt` grant); `None` means the encoding service's own project. Re-encoding
  a value keeps its origin, so another project's store holds the field under the producing
  project's key (FR-027).
- `lookup`: written into a row's envelope as a token only when the scope allows (R6); never into a
  journal.
- `toString` is `Personal(<subject>)` in every SDK (FR-029). `toOption`, `map`, `getOrElse` exist;
  there is no `get`.
- Python: `Personal[T]` with `Present(subject, value)` / `Erased(subject)` dataclasses and
  `personal(subject, value)`; TypeScript: `Personal<T>` as a tagged union with `present()`,
  `erased()` and the schema kind `s.personal(inner)`; Rust: `Personal<T>` enum with serde impls.

## The personal envelope (every store, every SDK)

```json
{"subject":"player/8c1f","project":"brand","data":"AQ…"}
{"subject":"player/8c1f","project":"brand","data":"AQ…","lookup":"9f3a…"}
{"subject":"player/8c1f","project":"brand"}
```

- Key order is fixed and every SDK writes it; no other key is accepted.
- `data`: base64 of `0x01 ‖ nonce(12) ‖ AES-256-GCM(plaintext, tag 16)`; plaintext is the inner
  codec's bytes; AAD is `subject ‖ 0x00 ‖ project ‖ 0x00 ‖ manifest`.
- `lookup`: hex of `HMAC-SHA-256(lookupKey(project), plaintext)`.
- No `data`: erased at write time (the subject-only form). Decode table in R2.

## `PersonalScope` and `KeyringHandle` (core)

```scala
final case class PersonalScope(keyring: KeyringHandle, project: String, manifest: String, lookupAllowed: Boolean)
object PersonalScope:
  def within[T](keyring: KeyringHandle, project: String, lookupAllowed: Boolean = false)(body: => T): T
  private[ankka] def withManifest[T](manifest: String)(body: => T): T   // set by Serializer.json
  def current: Option[PersonalScope]

trait KeyringHandle:
  def key(project: String, subject: String, create: Boolean): KeyResult
  def lookupKey(project: String): Array[Byte]
enum KeyResult:
  case Available(key: Array[Byte])
  case Destroyed(erasureId: String)
  case Refused(reason: String)
object KeyringHandle:
  val unavailable: KeyringHandle   // throws CommandError(Unavailable, "no keyring is available here")
```

- Encoding `Present` or decoding an envelope with `data` outside a scope throws `Unavailable` naming
  the keyring; encoding `Erased` needs no scope; decoding the subject-only form needs no scope.
- `create = true` only on encode; a decode never mints a key.

## Service side (runtime)

```scala
// ankka_erasures_applied — DDL 50-erasure-postgres.sql
// erasure_id TEXT PRIMARY KEY, project TEXT, subject TEXT, applied_at TIMESTAMPTZ,
// handler_outcome TEXT NULL, objects_erased INT NULL, objects_final_at TIMESTAMPTZ NULL

final case class KeyCacheSettings(maxKeys: Int = 10000, expiry: FiniteDuration = 5.minutes,
                                  outageBound: FiniteDuration = 15.minutes)   // ankka.erasure.cache.*
final case class ErasureOrder(erasureId: String, subject: String, reapply: Boolean)
final case class Completion(erasureId: String, duties: Duties, handler: Option[HandlerOutcome])
final case class Duties(keyDropped: Boolean, viewsRedacted: Vector[String], tokensRemoved: Long,
                        sessionsMarked: Int, instancesStopped: Int)
final case class HandlerOutcome(ok: Boolean, detail: String, objects: Option[ErasedObjects])
final case class ErasedObjects(count: Long, finalAt: Instant)
```

- `ErasureRuntime.readiness` is false until the `hello`'s `log` is applied; the observe document
  carries `erasures: {appliedUpTo, finishedAt}`.
- Redaction runs under each view's exclusive advisory lock; the statement is R14's.

## SDK (sdk)

```scala
type ErasureHandler = ErasureContext => ErasureOutcome
trait ErasureContext:
  def subject: String; def erasureId: String; def reapply: Boolean
  def objects: ObjectErasure            // erase(): ErasedObjects; Refused("no bucket") without one
  def services: HttpServiceClients; def secrets: SecretStore
enum ErasureOutcome:
  case Done(detail: String = "", objects: Option[ErasedObjects] = None)
  case Failed(reason: String)           // retried on the next sweep
// Ankka.service.withErasureHandler(handler)
// EndpointClients / WorkflowContext / ConsumerContext / AgentContext: def lookupToken(value: String): String
// AgentCalls.withSubject(subject); TaskBuilder.withSubject(subject)
```

## Agents (agent)

```scala
enum SessionMemoryEvent:
  // existing cases unchanged
  case SubjectAssigned(subject: String)                       // new
  case PersonalMessageAdded(message: Personal[StoredMessage]) // new; StoredMessage = the message union
  case Erased(at: Long)                                       // new: marked by the runtime on apply
final case class SessionHistory(…, subject: Option[String] = None, erased: Boolean = false)  // new fields
enum TaskEvent:  case CreatedPersonal(id, typeName, text: Personal[TaskText], …)               // new
final case class InstanceRecord(…, subject: Option[String] = None)                             // new
// platform view ankka-subject-index: row key = subject; row = {sessions: Set[String], instances: Set[String]}
```

- A session with no subject is unchanged by any erasure; a session with one refuses a message
  without the subject's metadata only when the message is personal (nothing else changes).
- `PromptReplay` on `erased = true`: no earlier messages and the system note
  "The earlier conversation in this session was erased at the data subject's request."

## The keyring (keyring/)

```scala
// ProjectKeyEntity — key value, id <project>
final case class ProjectKeys(kek: Wrapped, lookupKey: Wrapped, createdAt: Instant)
// SubjectKeyEntity — key value, id <project>/<subject>
enum SubjectKeyState:
  case Live(key: Wrapped, createdAt: Instant)
  case Tombstone(erasureId: String, destroyedAt: Instant, everExisted: Boolean)
final case class Wrapped(version: Byte, nonce: Array[Byte], ciphertext: Array[Byte])  // SecretCipher framing
// ErasureEntity — event sourced, id <erasureId>
enum KeyringErasureEvent:
  case Started(project, subject, at, channels: Vector[ChannelId])
  case KeyDestroyed(at)
  case Acknowledged(channel: ChannelId, at)
  case ChannelClosedUnacknowledged(channel, at)
  case Completed(service: String, instance: String, completion: Completion, at)
  case Reapplied(at, channels)
  case DecryptionRefused(…)  // the machine route, on SubjectKeyEntity as DecryptionRecorded / Refused
final case class ChannelId(project: String, service: String, instance: String)
final case class KeyringStatus(ready: Boolean, copies: Int, replayed: Int, behind: Option[String], lastReplayAt: Option[Instant])
```

- A `fetch` with `create = true` for a `Tombstone` answers `refused("erased")` (FR-005, FR-013).
- An erasure for a subject with no key records `Tombstone(everExisted = false)` (edge case).
- Service completion = every channel in `Started.channels` (plus any `Reapplied` set) has
  `Acknowledged` or `ChannelClosedUnacknowledged` *and* one `Completed` per service; the control
  plane reads it as per-service completion.

## The channel (service ↔ keyring, JSON over one WebSocket)

See [contracts/keyring-channel.md](contracts/keyring-channel.md) for every message. Shapes:

```scala
enum ToKeyring:  case Hello(project, service, instance, reads: Set[String], appliedUpTo: Option[String]); case Fetch(project, subject, create: Boolean)
                 case LookupKey; case Ack(erasureId); case Completed(erasureId, completion: Completion)
enum FromKeyring: case Key(subject, key: Base64, expiresAt: Instant); case LookupKeyIs(key: Base64, expiresAt)
                 case Destroyed(project, subject, erasureId); case Refused(project, subject, reason)
                 case Apply(erasureId, subject, reapply: Boolean); case Log(entries: Vector[LogEntry])
                 case Close(reason: String)
final case class LogEntry(erasureId: String, project: String, subject: String, destroyedAt: Instant, sequence: Long)
```

## The control plane (controlplane, controlplane-api)

```scala
// wire (controlplane-api)
final case class RequestErasure(subject: String, notBefore: Option[LocalDate] = None, reason: Option[String] = None,
                                correlationId: Option[String] = None)
enum ErasureState: Held, Withdrawn, Replaced, Applying, Applied, Final, Settled, Failed   // string codec in the companion
final case class ErasureRequest(id: String, projectId: String, subject: String, state: ErasureState,
  askedBy: Who, askedAt: Instant, notBefore: Option[LocalDate], reason: Option[String], correlationId: Option[String],
  override: Option[Override], keyDestroyedAt: Option[Instant], completions: Vector[ServiceCompletion],
  finalAt: Option[Instant], failure: Option[String], replacedBy: Option[String])
final case class Who(kind: "member" | "service", subject: String, display: String, project: Option[String])
final case class Override(by: Who, reason: String, at: Instant)
final case class ServiceCompletion(service: String, completedAt: Instant, handler: Option[String],
                                   objectsErased: Option[Long], objectsFinalAt: Option[Instant])
final case class ErasureCertificate(request: ErasureRequest, issuedAt: Instant)   // holds nothing personal
final case class ErasureLogEntry(sequence: Long, erasureId: String, projectId: String, subject: String, destroyedAt: Instant)
final case class ProjectHistoryEntry(kind: String, actor: HistoryActor, at: Instant, detail: String)   // ErasureRefused among them

// entity (controlplane): ErasureEntity, id <projectId>/<erasureId>; events carry actor/at
enum ErasureEvent: Asked, Held, Withdrawn, Replaced(by), Overridden(reason), LogWritten(sequence),
                   KeyDestroyed(at), ServiceCompleted(completion), Applied(at), Final(at), ReapplyRequested(at), Failed(reason)
```

- `RequestErasure.problems`: the subject rule; `notBefore` not in the past; `reason` required with
  `notBefore`; `correlationId` 1–128 characters.
- State machine: `Held → Withdrawn | Replaced | Applying`; `Applying → Applied | Failed`;
  `Applied → Final → Settled`; nothing leaves `Withdrawn`, `Replaced`, `Settled`; `Failed → Applying`
  on the next sweep. A request with no `notBefore` starts `Applying`. `Final → Settled` when
  `finalAt + erasure.reapply-grace` (30 days) has passed; a settled request's handlers run again only
  through `POST …/{e}/reapply` by a member. A `HandlerRun` is recorded on the first run, a failure and
  a changed outcome only; a service with no running instance at the destroy is recorded
  `completedByAbsence`.
- Withdraw: by whoever asked or a member, only while `Held`. Override: an owner, only while
  `Held`, recorded with the reason. A second request for an applied subject is accepted and answered
  with the applied request (edge case).
- `ErasureRows` is keyed by `<projectId>/<erasureId>` with `subject`, `state`, `correlationId`
  as declared-query columns.

## Protocol 1.15 (protocol)

See [contracts/protocol-1.15.md](contracts/protocol-1.15.md). Version sites: `protocol/README.md`,
`Compatibility.scala`, `Conversation.scala`, the three SDKs, five pinning tests.

## Platform variables (core `PlatformVariables`)

| Variable | Class | Who sets it |
|---|---|---|
| `ANKKA_KEYRING_URL` | runtime-read, operator-rendered, descriptor-refused; an operator setting the keyring component patches on | the operator, when it has the setting (`https://ankka-keyring.ankka-keyring.svc:9020`), compose (`http://keyring:9020` / `http://localhost:9020`) |
| `ANKKA_S3_*` | shared (was: the program only) | the operator, on both containers when the service has a bucket |
| `ANKKA_ERASURE_LOG_URL` | the keyring's own | the keyring component (`https://ankka-controlplane.ankka-controlplane.svc:9000`) |
| `ANKKA_KEYRING_SECRET_KEY` | compose only | the developer's shell, for the local keyring's secret store |
