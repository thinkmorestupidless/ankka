# Research: Compaction of a session started with a data subject

Every decision the plan rests on, with what was read to make it.

## R1. Where the compactor opens a sealed event

**Decision**: in `SessionCompactor.onMessage`, through one function shared with the session memory
entity: `SessionMemoryEvent.opened(event): Option[SessionMemoryEvent]`, which gives a plain event
as it is, a `Sealed(Personal.Present(_, json))` as the event its JSON holds, and
`Sealed(Personal.Erased(_))` as `None`. The compactor considers a session when the opened event is
an `AiMessageAdded` and ignores everything else, `HistoryCompacted` included.
`SessionMemoryEntity.applyEvent` reads its `Sealed` case through the same function.

**Rationale**: the platform has already decrypted the body by the time the handler runs
(`ConsumerEventHandler.processBound` decodes with the entity's serializer under
`ServiceKeyring.binding`), so the compactor holds a `Personal.Present` with the JSON in it and
needs no key of its own. One function for both readers follows the rule the runtime and the testkit
keep for effects (`materialise`): two readers of one shape cannot disagree.

**Alternatives considered**: considering a session on every `Sealed` event without opening it
(offered and declined in clarification: it loosens FR-001 and FR-005, and a compaction would then
trigger a second look at its own write); a decoder of the compactor's own that leaves the body
unopened (declined with R4).

## R2. The summary is sealed with no change

**Decision**: nothing changes in how the summary is written. `SessionMemoryEntity.compact` already
calls `record`, which maps every event through `sealedOr`; a session with a data subject writes
`Sealed(HistoryCompacted(...))`. FR-003 is proved by a test, not by new code.

**Rationale**: read in `SessionMemoryEntity.scala` (`compact` → `record` → `sealedOr`). The state's
snapshot is sealed whole by `Sealing.stateSerializer`, and `SessionHistory.observed` already
replaces a `SummaryMessage`'s text with `Personal(<subject>)`.

## R3. Erasure races

**Decision**: no new guard. Each point at which an erasure can land already ends in nothing
written and no failure:

| The erasure lands | What happens |
|---|---|
| before the consumer decodes the event | it decodes as `Sealed(Erased)`; `opened` gives `None`; ignored |
| after the decode, before `history` is read | the history reads erased: no messages, size 0; under the limit; ignored |
| after the summariser was called, before `compact` | `record` replies `Done` and persists nothing (`currentState.erased`); or, for a session still in memory, the serializer writes the event erased (`Sealing.eventSerializer`) |

In the third row the summariser's model has been sent the conversation. That is what FR-008
documents as beyond an erasure. `SessionCompactor.compacted` gains the session id in that row
though nothing was written; it is a diagnostic queue and stays as it is.

## R4. A keyring outage

**Decision** (asked, 2026-11-10): the compactor waits as every consumer does. A sealed event that
cannot be decoded because the keyring is unreachable fails in the projection, before the handler,
with `KeyringUnavailable`; the projection restarts with backoff and retries that event until the
keyring answers. No failure is recorded, no compaction is lost, and FR-006 is rewritten to say so.

**Rationale**: the decode is the platform's and precedes the handler, so the compactor's own
`catch` never sees it. Within `ankka.keyring.cache.outage-bound` (60 seconds) a key the instance
holds keeps serving and the compactor is unaffected. Past it, turns on a sealed session are refused
anyway. The price is that compaction of sessions with no data subject waits behind a sealed event
for the length of the outage; compaction is maintenance and tolerates that.

**What still reaches the catch**: the `history` read and the `compact` call, which can be refused
`Unavailable` when the entity must recover during an outage the decode slipped through. Those are
recorded and logged as any other compaction failure (R6) and the consumer moves on.

**Alternatives considered**: skip and record, as the spec first said (needs a second decoder of the
session event shape, kept in step with the entity's by hand); never opening the event (R1).

**Proof**: the scenario "compaction of a session started with a data subject waits while the
keyring cannot be reached and is made once it can" (`features/agents/compaction.feature`, added by
analyze, asked). `CompactionFeatures` binds it: the compactor is held in a first session's
summariser on a latch, a sealed session's turns are written behind it, the outage starts with
`ankka.keyring.cache.outage-bound` set short for the kit, the latch is released, and after the
outage ends the sealed session is compacted with nothing in `SessionCompactor.failures` for it. A
step that passes by timing is not a proof; a build that cannot make it deterministic stops.

## R5. Which languages this reaches

**Decision**: Scala only, and the plan says so.

**Rationale**: compaction is turned on by `AgentRuntime.withCompaction`, called only by the Scala
runtime and the Java and Kotlin agent modules; the sidecar never calls it (`limitations.md`: a
Python, TypeScript, Rust or Go agent's compaction "is not yet configurable"). A session is started
with a data subject from Scala, Python, TypeScript, Rust and Go; "Java and Kotlin services start no
session or autonomous agent's instance with a data subject" (the agents guide's own languages
comment). The two sets meet in Scala alone. No sample in another language can show the feature, so
none is added, and the guide's sentence sits outside the tabs.

## R6. The warning

**Decision**: one `log.warn` in the compactor's `catch`, from a logger of `SessionCompactor`'s own:
`compaction of session '<id>' failed: <kind>`. The kind is the failure's class name, and for a
`CommandError` its `ErrorCode` beside it. Never `getMessage`, never the exception itself as the
log call's last argument (a stack trace prints the message).

**Rationale**: a `ModelCallFailed` message holds a summary of the provider's response body, and a
`CommandError`'s may quote what it refused; either can hold conversation text. The class and the
code are enough to tell a refusal from a timeout from an unavailable keyring.
`SessionCompactor.failures` keeps `"<session>: <message>"` as it is: it is in memory, read by
tests, and several existing cases match on it.

**Alternatives considered**: logging the message for a session with no data subject and only the
kind for one with (two shapes of one line, and a plain session can hold a person's words too).

## R7. Where each scenario's test lives

| Scenario | Proved by |
|---|---|
| `features/agents/compaction.feature`, all six | a new `CompactionFeatures` (`GherkinSuite` over the one file) with `CompactionSteps`, in `modules/testkit/src/test/.../testkit/agents/`, on `AnkkaTestKit` with two `TestModelProvider`s |
| `features/erasure/agents.feature`: the summary is kept encrypted | new steps in `ErasureAgentsFeatures` (`controlplane` tests), whose service gains compaction with a summariser of its own |
| `features/erasure/agents.feature`: an erased data subject's session is not compacted | the same suite; the summariser's provider is scripted with nothing, so a call fails loudly |
| the documentation steps | the existing pattern in `ErasureAgentsFeatures`: the page's text is read and searched |

`CompactionFeatures` reads the feature's own numbers (100 000 characters, 10 kept, no fewer than 4
replaced, 14 messages of 100 103 characters), which are SC-003's. "No summary is asked of the
model" is the summariser's provider's call count, zero, read after the compactor's offset has
passed the turn (the suite waits on a later, plain session's compaction: an `eventually` must wait
for something that happens).

`ErasureAgentsFeatures` is not a k3s suite; it runs in `build (rest)`.

**Red first**: on `main` the sealed session's compaction scenario fails (no summary ever appears)
and the scenario with no data subject passes; it is there because no feature described compaction
at all. The two not-compacted scenarios use a session started with a data subject, so on `main`
they pass for the wrong reason. Each is proved able to fail after the fix, by breaking the rule it
names once.

## R8. Documentation

**Decision**: three pages and one rule file.

- `docs/build/agents.md`, *Compaction*: a session started with a data subject is compacted like any
  other; its summary is kept sealed under the subject's key; the summariser's model is sent the
  conversation, which is beyond an erasure. And that a failed compaction logs a warning naming the
  session.
- `docs/build/personal-data.md`, *Agents*: one sentence that a summary is among what is kept sealed,
  linking to *Compaction*.
- `docs/concepts/agents.md`, *Compaction*: one sentence, the same statement.
- `.claude/rules/agents.md`, Traps: a consumer of session memory's events sees `Sealed` for every
  event of a session with a data subject; match on `SessionMemoryEvent.opened`, never on the plain
  case alone. Dated.

`tools/docs/skill/ankka-agents/SKILL.md` gains the same one line where it tells the reader to start
a session about a person with a data subject; `just docs-sync` renders it. Docs pages tell no
history: none says this was once broken.

## R9. Release and compatibility

**Decision**: no wire, journal, schema or protocol change. No event case is added, so
`SessionMemoryCompatibilitySuite`'s pinned forms are untouched. The consumer's offset is not reset:
a sealed session already past the size is compacted after its next turn. The fix ships in the next
patch release by tag, which is not this feature's work.

## Decisions made by the person in this design

- 2026-11-10: "summary" and "compaction size" stand as glossary terms, with no refused synonyms.
- 2026-11-10: every compaction failure logs one warning, session and kind, no text (FR-009).
- 2026-11-10: the summariser's model being sent the conversation is documented, not made opt-in.
- 2026-11-10: in a keyring outage the compactor waits as every consumer does (R4; FR-006 rewritten).
- 2026-11-10: FR-006 gets a living scenario of its own, which the build must prove (analyze, C1).

## Design finished

2026-11-10: analyze reported nothing above LOW.

## Found during implementation

- **The compactor reads behind the turns** (2026-11-10, T011). It is handed a session's events in
  a burst, after several turns have been written, and each AI message reads the session as it is
  now. So the first AI message of the burst already finds the session past the size, and when
  that summary fails, the next AI message of the same burst asks again and usually succeeds. This
  is how compaction behaves for any session, not something sealing brought, and nothing here
  changes it. The failure scenario's model therefore fails every time it is asked about that
  session (`CompactionSteps`), as its words say; a model that failed once would be retried.
  Not a decision: no question was needed.
- **The keyring staging is deterministic** (T013). With both keyring bounds at two seconds the kit
  drops its held key once the outage passes the notice bound (logged by `KeyringClient`); the
  compactor's next sealed event then fails in the projection with `KeyringUnavailable` and Pekko
  restarts the stream with backoff (`RestartWithBackoffSource`), as R4 said. Both are waited for
  by their log lines, never by time.
