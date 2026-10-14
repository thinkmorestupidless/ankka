# Data Model: Replicated Entities

Nothing here is a new table. The model is what the journal gains, what the stored state and the
snapshot carry, the internal commands a copy exchanges with its puller and its siblings, the
settings, and the errors a caller reads. Research numbers refer to `research.md`.

## JournalRecord (journalled; R3)

`modules/runtime/.../wire.scala`. One new field, four new kinds, one local-only kind.

| Field | Type | Meaning |
|---|---|---|
| `kind` | `Int` | as today, plus 3 to 7 below |
| `manifest` | `String` | as today; platform kinds use `ankka.home`, `ankka.replication-set`, `ankka.handover`, `ankka.handover-abandoned`, `ankka.conflict` |
| `payload` | `Array[Byte]` | as today; platform kinds carry UTF-8 text or a JSON array |
| `expiryMillis` | `Long` | as today |
| `installation` | `Option[String]` | **new**, `NON_ABSENT`: the installation whose home wrote this record; absent on every record from before this feature and on a service with no installations named |

| Kind | Constant | Payload | Mirrored | Meaning |
|---|---|---|---|---|
| 3 | `KindHome` | home name | yes | the home is this installation from this record on |
| 4 | `KindReplicationSet` | JSON array of names | yes | the replication set after the change |
| 5 | `KindHandover` | target name | yes | a handover to the target is pending |
| 6 | `KindHandoverAbandoned` | target name | yes | the pending handover did not happen |
| 7 | `KindConflict` | other home's name | **no** | this copy found another history for the id |

Rules:

- A record written at a copy with no installations named carries no `installation` and is byte for
  byte what the release before wrote (pinned by `journal/journal-record.txt`).
- An unstamped record in a replicated journal was written at the installation holding that journal,
  before this feature. A follower that mirrors one persists it with `installation = Some(source)`,
  the installation it pulled from, and nothing else changed (R5); so only the home's own journal
  holds unstamped records, and every reader takes an unstamped record as written here.
- The first record of a replicated entity created under this feature is kind 3, then kind 4 with
  `[self]`, then the handler's events.
- Kind 3 is written only by the current home (the first writer, or the home ending a kind 5).
- A kind 7 is written only locally and is never handed to a puller; a copy in conflict stays in
  conflict until an operator resolves it outside this feature.

## Journaled (in memory; R3, R5)

`EventSourcedEntityHost.Journaled[+E]` gains:

| Case | Carries | From record kind |
|---|---|---|
| `Home(installation)` | the new home | 3 |
| `ReplicationSet(installations)` | the whole set | 4 |
| `Handover(target)` | the pending target | 5 |
| `HandoverAbandoned(target)` | the target | 6 |
| `Conflict(other)` | the other home | 7 |
| `Mirrored(record)` | a record pulled from the home, persisted unchanged | any, at a follower |

`toJournal(Mirrored(r)) = r`. `fromJournal` never yields `Mirrored`.

## Stored (the behaviour's state; R2)

`EventSourcedEntityHost.Stored[S]` gains:

| Field | Type | Folded from | Meaning |
|---|---|---|---|
| `home` | `Option[String]` | kind 3 | the current home; `None` before any replication record |
| `replicationSet` | `Set[String]` | kind 4 | the installations holding the entity; empty before any record |
| `pendingHandover` | `Option[String]` | kind 5 set, kind 3 or 6 clear | the target a handover is waiting on |
| `conflict` | `Option[String]` | kind 7 | the other home, when in conflict |

Derived, never stored:

| Mode | Condition |
|---|---|
| `Unreplicated` | `installations.isEmpty` in the settings: every check below is skipped |
| `Unknown` | `home.isEmpty` |
| `Home` | `home.contains(self)` |
| `Follower` | `replicationSet.contains(self)` and not home |
| `Outside` | otherwise |

## StateRecord and RemoteStateRecord (snapshot; R4)

Both gain `home: Option[String]`, `replicationSet: Option[Vector[String]]`,
`pendingHandover: Option[String]`, `conflict: Option[String]`, all `NON_ABSENT`; read back as
`None`, empty, `None`, `None`. Pinned by `journal/state-record.txt` before the fields exist.

## Internal commands (`EntityProtocol.ModuleCommand`; R5, R7, R10)

Sent only inside one installation, never on the wire between installations.

| Command | Fields | Sent by | Handled by |
|---|---|---|---|
| `Replicate` | `sequenceNr: Long`, `record: JournalRecord`, `ack: ActorRef[ReplicateAck]` | the copy's puller | a follower: persists `Mirrored(record)` if `sequenceNr == last + 1` and the writer is in the set or is the pending home, stamping an unstamped record with the puller's source; else `ReplicateAck.Refused(reason)` and the puller restarts from the copy's position |
| `Wake` | none | the transport's `advanced` feed | any copy: starts, which starts the puller if a follower; a copy with no record asks `locate` and starts the puller if the answer's set holds this installation |
| `Locate` | `result: Try[Option[Located]]` | `pipeToSelf` after `transport.locate` | a copy with no record: `Some` makes it `Outside` (or a follower after `Wake`), `None` lets a persisting command create here |
| `Reported` | `installation: String`, `position: Long` | the transport, on `report` | the home with a pending handover: persists kind 3 when `position == last`, else waits |
| `Forwarded` | `replyTo`, `result: Try[ForwardedReply]` | `pipeToSelf` | a follower: answers the caller's `Invoke` |

## Reserved method (`Invoke`; R8, R11)

| Name | Read-only | Answers |
|---|---|---|
| `ankka:replication` | yes | `ReplicationStatus(home, replicationSet, pendingHandover, conflict, position)` as JSON; refused to user handlers by `Companion.descriptor` under the `ankka:` prefix |
| `ankka:handover-status` | yes | `NotYet` while a handover is pending, `Succeeded` when kind 3 was written, `Rejected(Unavailable)` when kind 6 was; the client loops as `awaitEnd` does, as `awaitHandover(timeout)`. The handover command itself is answered when kind 5 is persisted, in both modes |

## Transport values (R6; the contract is `contracts/transport-seam.md`)

| Type | Fields |
|---|---|
| `Replicated` | `sequenceNr: Long`, `record: JournalRecord` |
| `ForwardedCall` | `method: MethodName`, `payload: Array[Byte]`, `metadata: Metadata` |
| `ForwardedReply` | `Succeeded(payload, metadata)` or `Rejected(CommandError)` |
| `Located` | `home: String`, `replicationSet: Set[String]` |
| `Advanced` | `installation: String`, `componentId: ComponentId`, `entityId: EntityId`, `position: Long` |

## Settings (R8, R9)

| Key | Variable | Default | Meaning |
|---|---|---|---|
| `ankka.replication.installation` | `ANKKA_INSTALLATION` | unset | this installation's name; a DNS label of at most 63 characters |
| `ankka.replication.installations.<name>.db.{host,port,name,user,password}` | `ANKKA_INSTALLATION_<NAME>_DB_*` | none | a sibling's journal for the runtime's own transport |
| `ankka.replication.poll` | none | `1s` | the runtime's own transport's pull interval |
| `ankka.replication.handover.hold-writes` | none | `off` | hold persisting commands while a handover waits (clarification Q4) |
| `ankka.replication.handover.timeout` | none | `30s` | abandon a held handover after this |

`ANKKA_INSTALLATION` is platform-only in `PlatformVariables`: a descriptor that sets it is refused.
The `ANKKA_INSTALLATION_` prefix is runtime-only: a descriptor may give a sibling's database, and it
reaches the platform's program and never a process. A name read from the environment is lower-cased
with `_` read as `-`.

## Errors (R10, R11)

| Code | New | When |
|---|---|---|
| `ErrorCode.NotHome` | yes | a persisting command at a copy whose home cannot be reached, or a read at a copy outside the replication set; the message names the home. HTTP 421 (misdirected request), gRPC `OUT_OF_RANGE` (`FAILED_PRECONDITION` is `Conflict`'s, and the reverse map is one to one), protocol `NOT_HOME` |
| `ErrorCode.Conflict` | reuse if present, else new | a persisting command at a copy in conflict; the message names the other home |
| `ErrorCode.Unavailable` | no | a held handover abandoned at its timeout, answered to a caller of `ankka:handover-status`; the message names the target |
| `ErrorCode.BadRequest` | no | a handover or a replication-set change naming an installation the service does not name, or a handover to one outside the set |

## Consumer declaration (R12)

`Consumer.Companion.mirroredChanges: Boolean = false`, on `ConsumerDescriptor`, in `ConsumerDetail`
on the protocol, and in the Python, TypeScript and Rust consumer declarations.

## State transitions of one copy

```text
Unknown ──first persisting command here, nobody holds it──▶ Home (kind 3, kind 4 [self])
Unknown ──locate finds a home──▶ Outside (forward writes, refuse reads)
Outside ──kind 4 adds self (learned through advanced/locate, then pulled)──▶ Follower
Follower ──pulls kind 3 naming self──▶ Home
Home ──persists kind 3 naming another──▶ Follower (if in set) | Outside (if not)
Home ──persists kind 5──▶ Home, handover pending ──kind 3──▶ Follower | ──kind 6──▶ Home
Follower ──pulls kind 4 without self──▶ Outside (puller stopped, copy kept)
any ──kind 7──▶ in conflict (writes refused, reads served)
```
