# Feature Specification: Compaction of a session started with a data subject

**Feature Branch**: `064-sealed-session-compaction`

**Created**: 2026-11-10

**Status**: Draft

**Input**: User description: "The session compactor never compacts a session started with a data
subject. Once a session's first call names a data subject (feature 042), every session memory event
after `Started` is persisted as `SessionMemoryEvent.Sealed` (`SessionMemoryEntity.scala`: \"Every
event after it is `Sealed`\"). `SessionCompactor.onMessage` acts only on `AiMessageAdded`
(`compaction.scala`), so it ignores every event of such a session, and the session grows without
bound. Found by eitheror's support agent (ankka-modules spec 017, FR-005), which starts every
session with the player as its data subject and needs compaction on. In its integration kit on
0.16.0, a session of 14 messages and 100 103 characters, over a 100 000 limit, drew no summariser
call and recorded no failure."

## Context

Compaction (the agents guide, *Compaction*) replaces a long session's oldest messages with a summary
once its text passes `maxHistoryBytes`, keeping `keepRecentMessages` verbatim. It runs as a consumer
over session memory's events, and considers a session each time a turn ends with an AI message.

Feature 042 seals a session about a person: from the session's first call that names a data
subject, every event is written as the event's own JSON sealed under the subject's key, so an
erasure makes the conversation unreadable. The compactor was written before 042 and still looks for
the plain event. It never sees one in a sealed session, so it never compacts one.

The two features are documented to work together: "Start a session that is about a person with that
person's data subject", and "With compaction on, the oldest messages are replaced by a summary". A
service that does both, which is what the guide recommends for any conversation with a person, gets
no compaction, no error, no log line and no entry in `SessionCompactor.failures`. Its sessions grow
past the model's context window, and every turn costs more than the last.

This feature makes the compactor treat a sealed AI message as the AI message it holds. Erasure is
unchanged: a sealed event whose subject was erased has nothing to compact.

## Clarifications

### Session 2026-11-10

- Q: Do the two glossary terms the scenarios needed, "summary" and "compaction size", stand? → A: Both are accepted as defined, with no refused synonyms.
- Q: Should a compaction failure be logged, beside `SessionCompactor.failures`? → A: Yes. Every compaction failure, of a session with a data subject or without, logs one warning naming the session and the kind of failure, never the conversation or a provider's message body.
- Q: How far does the feature go on the summariser's model being sent a data subject's conversation? → A: It is documented. The guide says the summariser's model is sent the conversation, and that this is beyond an erasure. Behaviour does not change and nothing is opt-in.
- Read from the code, not asked: the sidecar never turns compaction on (`withCompaction` is called only by the Scala, Java and Kotlin agent modules), so a Python, TypeScript, Go or Rust service's sessions are outside this feature.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A long conversation about a person is compacted (Priority: P1)

A service runs an agent with compaction on and starts each session with the person it is about as
its data subject. Once a session's text passes the size limit, after the turn that crossed it, its
oldest messages are replaced by one summary and the most recent ones stay verbatim, exactly as for a
session with no data subject. The summary, like every other message of the session, is kept sealed
under the subject's key.

**Why this priority**: without it, a service must choose between forgetting a person on request and
bounding what each turn sends the model. Both are documented as defaults.

**Independent Test**: in the integration kit, run a session started with a data subject past
`maxHistoryBytes` with more than `keepRecentMessages + minMessagesToCompact` messages, and read its
history: a summary first, then the kept messages. Then check the database holds the summary only
encrypted.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- added `features/agents/compaction.feature`: a session past the compaction size has its oldest messages replaced by a summary and its most recent kept verbatim
- added `features/agents/compaction.feature`: a session started with a data subject past the compaction size is compacted as one started with none
- added `features/agents/compaction.feature`: a session under the compaction size is not compacted
- added `features/agents/compaction.feature`: a session with too few messages beyond those kept is not compacted
- added `features/erasure/agents.feature`: the summary of a session started with a data subject is kept encrypted under the subject key
- added `features/erasure/agents.feature`: a session of an erased data subject is not compacted
- added `features/agents/compaction.feature`: a compaction that fails is reported in the log of the service by its session and never by its conversation

---

### Edge Cases

- **Erased before the compactor reads it.** An erasure lands between the turn and the compactor's
  read. The sealed event reads as erased and the history reads as erased, so nothing is summarised
  and nothing is written. This is not a failure.
- **A sealed compaction.** The compactor's own `HistoryCompacted` is sealed too. Unsealed, it is
  still not acted on, so compaction cannot trigger itself (the guard compaction already has for the
  plain event).
- **A turn that waits for approval.** As today, compaction never touches a turn that is waiting.
  Sealing changes nothing here.
- **Sealed events already in the journal.** A deployment that upgrades has sealed sessions that are
  already past the limit. They are compacted after their next turn, not by replaying the journal.
  The consumer's offset is not reset.
- **The subject key cannot be read** (keyring unreachable). The event cannot be opened. This is a
  failure of that one session's compaction: it is recorded in `SessionCompactor.failures` and the
  consumer moves on, as any other compaction failure does. It never stalls the consumer.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The session compactor MUST consider a session after a turn that ends with an AI
  message, whether that message was persisted plain or sealed under a data subject's key.
- **FR-002**: For a session started with a data subject, compaction MUST follow the same rule as for
  one started with none:
  - it compacts only when the session is over `maxHistoryBytes`;
  - it drops only when at least `minMessagesToCompact` messages lie beyond `keepRecentMessages`;
  - it writes nothing when the summary is no shorter than what it replaces.
- **FR-003**: The summary written for a session started with a data subject MUST be persisted
  sealed under that subject's key, like every other event of the session (feature 042). No part of
  it may be readable in the database or the broker.
- **FR-004**: A sealed event whose data subject has been erased MUST NOT lead to a summarisation or
  a write, and MUST NOT be recorded as a failure.
- **FR-005**: A sealed event that holds the compactor's own `HistoryCompacted` MUST NOT lead to
  another compaction.
- **FR-006**: A sealed event that cannot be opened for any reason other than erasure MUST be
  recorded in `SessionCompactor.failures`, naming the session, and MUST NOT stop the consumer.
- **FR-007**: The text sent to the summariser for a session started with a data subject MUST be
  sent the way an agent's turns are: to the model provider only. It MUST NOT be logged.
- **FR-008**: The agents guide's *Compaction* section MUST say that compaction applies to a session
  started with a data subject, that its summary is kept sealed, and that the summariser's model is
  sent the conversation, which is beyond an erasure as what a session sent to its own model is.
- **FR-009**: Every compaction failure, of a session started with a data subject or with none, MUST
  log one warning that names the session and the kind of failure. The warning MUST NOT carry any
  text of the conversation, the summary, or a model provider's message body.

### Key Entities

- **Sealed session event**: a session memory event of a session started with a data subject. It
  holds the plain event's JSON under the subject's key, or reads as erased once the key is
  destroyed.
- **Compaction**: as the glossary defines it.
- **Summary**, **compaction size**: the two terms this feature adds to the glossary, for what
  compaction writes and for the size past which it runs.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A session started with a data subject is compacted after the first turn that ends past
  the size limit with enough messages to drop. Its history then holds the same number of messages
  as a session with no data subject run through the same turns.
- **SC-002**: The database and the broker of a service whose sealed session was compacted contain
  none of the summary's text, by the kit's `assertNoPersonalValue`.
- **SC-003**: The kit test that reproduces the bug passes: 14 messages, over 100 000 characters,
  `keepRecentMessages = 10`. On 0.16.0 it drew no summariser call; after the fix, the history reads
  as one summary and 10 kept messages.
- **SC-004**: No session with no data subject compacts differently than before. The existing
  compaction tests pass unchanged. The one addition such a session sees is the warning a failed
  compaction now logs (FR-009).
- **SC-005**: A compaction made to fail in the kit leaves one warning in the service's log that
  names the session, and no line of the log holds any text of the session.

## Assumptions

- The fix is in the compactor, not in how sessions are sealed: sealing stays per event, as feature
  042 settled.
- The consumer can open a sealed event the way the session memory entity does when it replays one,
  since both run in the service that holds the subject key.
- This is a fix to 0.16.0's behaviour, released in the next patch release. Services that need it
  (eitheror's support agent, ankka-modules spec 017) move to that release; nothing changes in their
  code.
- Scala, Java and Kotlin agents use this compactor in the service's own JVM. The sidecar never
  turns compaction on, so its agents (Python, TypeScript, Go, Rust) are untouched. Making compaction
  configurable where an SDK has no setting for it yet ("compaction is not yet configurable" for a
  Python or TypeScript service, per the agents guide) is out of scope.
