# Tasks: Compaction of a session started with a data subject

**Input**: Design documents from `specs/064-sealed-session-compaction/`

**Prerequisites**: plan.md, spec.md, research.md (R1 to R9), data-model.md, contracts/compactor.md,
quickstart.md

**Tests**: every acceptance scenario is a living feature (`features/agents/compaction.feature`,
`features/erasure/agents.feature`) and ends as a test named after it, written before the code it
checks. Ask of each: could it pass while the thing is false? The sealed scenarios must be seen red
on the unfixed compactor; the plain ones are broken once to see them fail.

**Organization**: one user story (US1). Its tests come first and are red; the fix is two small
edits; the warning, the outage case and the pages follow.

**Languages**: Scala only (plan.md "Languages reached", research R5). Compaction is turned on from
Scala, Java and Kotlin; a session is started with a data subject from Scala, Python, TypeScript,
Rust and Go; they meet in Scala. The pages gain prose outside the language tabs and no sample, so
no per-language sample task exists and none can: there is nothing a Java, Kotlin, Python,
TypeScript, Rust or Go service can write to see this feature.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no dependency on an unfinished task)
- **[Story]**: US1 from spec.md
- Paths are from the repository root; `AG` is `modules/agent/src`, `TK` is
  `modules/testkit/src/test/scala/com/thinkmorestupidless/ankka/testkit`, `CP` is
  `controlplane/src/test/scala/com/thinkmorestupidless/ankka/controlplane`

## Phase 1: Setup

- [ ] T001 Confirm the starting point: `just features` reports 0 findings, and `sbt 'testkit/testOnly *CompactionIntegrationSuite'` is green with its seven cases, on JDK 21 with Docker running. Record the seven case names in the commit message; SC-004 holds them unchanged to the end.

## Phase 2: Foundational

- [ ] T002 In `AG/main/scala/com/thinkmorestupidless/ankka/agent/SessionMemoryEntity.scala` add `private[agent] def opened(event: SessionMemoryEvent): Option[SessionMemoryEvent]` to the `SessionMemoryEvent` companion (create the companion if the enum has none), exactly as data-model.md "`SessionMemoryEvent.opened`": `Sealed(Personal.Present(_, json))` → `Some(readFromStringReentrant(json)(using SessionMemoryCodecs.event))`, `Sealed(Personal.Erased(_))` → `None`, any other event → `Some(event)`. Change `applyEvent`'s `Sealed` case to read through it (`None` → `SessionHistory.erasedFrom(currentState)`). No behaviour of the entity changes.
- [ ] T003 [P] In `AG/test/scala/com/thinkmorestupidless/ankka/agent/SealingSuite.scala` add three cases for `opened`: a plain event is given as it is; a sealed `AiMessageAdded` and a sealed `HistoryCompacted` are given as the events they hold; a sealed event read after its key is destroyed is `None`. Use the suite's existing keyring fixture. Then `sbt 'agent/testOnly *SealingSuite'` green, the existing eight cases included.

## Phase 3: User Story 1 - A long conversation about a person is compacted (P1)

**Goal**: a session started with a data subject is compacted after the turn that takes it past the
compaction size, as one started with none; its summary is kept sealed; an erased one is left alone.

**Independent test**: `sbt 'testkit/testOnly *CompactionFeatures'` and
`sbt 'controlPlane/testOnly *ErasureAgentsFeatures'`.

### Tests first (red)

- [ ] T004 [US1] Create `TK/agents/CompactionSteps.scala` and `TK/agents/CompactionFeatures.scala`: a `GherkinSuite` with `LogCapturing` over the one file `features/agents/compaction.feature`, on `AnkkaTestKit` with a request agent `helper` (model: one `TestModelProvider`), `AgentRuntime.withDefaultModel(model).withCompaction(settings, Some(ModelSummariser(summaryModel, 30.seconds)))` where `summaryModel` is a second `TestModelProvider` (`.claude/rules/agents.md`: one provider cannot serve both), `agents.descriptors` and `ProjectionRuntime()`. Bind every step of the five scenarios. The Background's numbers become the `CompactionSettings` (`maxHistoryBytes = 100000`, `keepRecentMessages = 10`, `minMessagesToCompact = 4`). "A session … with N messages of M characters together" runs N/2 turns whose user and AI texts sum to M characters, the last turn being the one "a turn in the session ends" names; started "with the data subject" uses `componentClient.forAgent(SessionId(…), subject = Some(DataSubject(…)))`. "The session holds a summary and then its 10 most recent messages verbatim" waits (an `eventually` on the history's shape) for one `SummaryMessage` first and compares the ten that follow with the texts sent. "Holds its N messages verbatim and no summary" must not pass by reading too early: first run a second, plain session past the size and wait for it in `SessionCompactor.compacted`, so the consumer's offset is known to be past the session under test, then read. Give each scenario session ids of its own and clear `SessionCompactor.compacted` and `.failures` before each.
- [ ] T005 [US1] In `CompactionSteps.scala` bind the failure scenario: "the model that writes the summary fails" is `summaryModel.expectRefusal(…)` with a refusal text that is itself checked for in the log; "the log of … holds a warning that names the session and the failure" reads `capturedLog` for one line at `WARN` holding the session id and `ModelCallFailed` (contracts/compactor.md "A failed compaction"); "nothing in the log … reads as a message of the session" searches `capturedLog` for every user and AI text the scenario sent and for the refusal text, and finds none.
- [ ] T006 [US1] Run `sbt 'testkit/testOnly *CompactionFeatures'` and record what it reports: five scenarios found; the two with no data subject and no failure green; "a session started with a data subject past the compaction size is compacted as one started with none" red on the wait for a summary; the failure scenario red on the missing warning. Then break `considerCompacting`'s size comparison once (`<=` to `>=`), see the two plain not-compacted scenarios go red, and restore it (CLAUDE.md: a check proved able to fail). A scenario green before the fix that this task does not name is a defect in its steps: fix the steps.
- [ ] T007 [P] [US1] In `CP/ErasureAgentsFeatures.scala` turn compaction on for the feature's service: a `TestModelProvider` of the summariser's own, `withCompaction` with a small `CompactionSettings`, `agents.descriptors`, and a `ProjectionRuntime` if the suite's kit has none. Bind the steps of the two new scenarios of `features/erasure/agents.feature`: "a session of {string} started with the data subject {string}, past the compaction size of {string}" runs turns until the history is over the size; "compaction replaces the oldest messages of the session with the summary {string}" scripts the summariser with that text, ends a turn and waits for the `SummaryMessage`; "the database of {string} holds the summary encrypted under the subject key of {string}" reads the session's journal rows after the `Started` one with the suite's existing envelope pattern and decrypts the last with the subject key to find a `HistoryCompacted` holding the text; "nothing in the database … reads as …" is the existing step (`assertNoPersonalValue`); "compaction runs on the session" for the erased session ends nothing new (an erased session refuses a turn): it waits for the consumer to pass the session's last event by compacting a second, plain session; "no summary is asked of the model" is the summariser provider's call count unchanged since the erasure; "the conversation of the session is erased" reuses the existing erased-history assertion. The four existing scenarios must stay green with compaction on: give their sessions sizes under the limit or script nothing for the summariser so an unexpected call fails loudly.
- [ ] T008 [US1] In `CP/ErasureAgentsFeatures.scala` bind "the documentation says that what compaction sent to the model that writes the summary is beyond an erasure" in the suite's existing documentation-step pattern, reading `docs/build/agents.md`. Run `sbt 'controlPlane/testOnly *ErasureAgentsFeatures'`: six scenarios found; "the summary of a session started with a data subject is kept encrypted under the subject key" red (no summary appears, and the page lacks the sentence); "a session of an erased data subject is not compacted" may be green before the fix, because nothing compacts a sealed session yet: T011 proves it can fail.

### The fix

- [ ] T009 [US1] In `AG/main/scala/com/thinkmorestupidless/ankka/agent/compaction.scala` change `SessionCompactor.onMessage` to data-model.md "The compactor's rule": `SessionMemoryEvent.opened(event)` is `Some(_: SessionMemoryEvent.AiMessageAdded)` → `considerCompacting()`, anything else → `effects.ignore()`. Keep the comment that `HistoryCompacted` deliberately does not trigger, and say it holds for a sealed one. Update the class comment and `SessionMemoryEvent.Sealed`'s to say a consumer reads a sealed event through `opened`.
- [ ] T010 [US1] In `compaction.scala` add the warning (FR-009, research R6, contracts/compactor.md): a `private val log = LoggerFactory.getLogger(classOf[SessionCompactor])` in the companion, and in the `catch` of `considerCompacting`, beside the existing `failures.add`, `log.warn("compaction of session '{}' failed: {}", sessionId, kind(failure))` where `private[agent] def kind(failure: Throwable): String` is the class's simple name, with `(<ErrorCode>)` after it for a `CommandError`. Never `getMessage`, and never the throwable as the last argument. `failures` keeps its present text.
- [ ] T011 [US1] Run `sbt 'testkit/testOnly *CompactionFeatures'` (five green) and `sbt 'controlPlane/testOnly *ErasureAgentsFeatures'` (six green once T015 has written the page; until then only the documentation step of the summary scenario is red). Prove "a session of an erased data subject is not compacted" can fail: in a scratch edit make `opened` give `Some(AiMessageAdded(…))` for an erased body or skip the erased check, see the scenario's "no summary is asked" or the summariser's empty script fail it, and restore. If it cannot be made to fail, its steps are wrong: fix them.
- [ ] T012 [P] [US1] In `AG/test/scala/com/thinkmorestupidless/ankka/agent/CompactionSuite.scala` add unit cases with no runtime: `SessionCompactor.kind` gives `ModelCallFailed` for a model failure and `CommandError(Unavailable)` for a refused call, and neither holds the failure's message; and, if the compactor can be built against a stub `ConsumerContext` as the suite's descriptor case does, that a sealed `HistoryCompacted`, a sealed `UserMessageAdded`, a `Started` and an erased `Sealed` are each ignored without a call to the client. Where the stub is not practical, leave the event cases to T003 and the feature suites and say so in the commit.

### The keyring outage (FR-006, research R4)

- [ ] T013 [US1] In `TK/CompactionIntegrationSuite.scala` add one case, "in a keyring outage a sealed session's compaction waits, and is made when the keyring answers", without touching the seven existing cases: a custom `Summariser` holds the compactor on a latch inside a first, plain session's compaction; a sealed session's turns are run past the size behind it; `kit.keyring.outage(…)` starts with `ankka.keyring.cache.outage-bound` set short for this kit (read `KeyringOutageSuite` and `docs/build/personal-data.md` for the setting and how a kit takes configuration); the latch is released; the case asserts no entry of `SessionCompactor.failures` starts with the sealed session's id while the outage lasts, ends the outage, and waits for the sealed session in `SessionCompactor.compacted` and for its `SummaryMessage`. This needs a kit of its own (its own settings and summariser), started and stopped inside the case or in a small sibling suite `TK/CompactionOutageSuite.scala` with `LogCapturing`. If the kit cannot shorten the bound or the case cannot be made deterministic, do not write a case that passes by timing: write down in this file, under this task, what was tried and why it cannot be proved in the kit, and leave FR-006's waiting to `KeyringOutageSuite`'s existing proof of the platform's behaviour.
- [ ] T014 [US1] Run `sbt 'testkit/testOnly *CompactionIntegrationSuite'`: the seven cases of T001 green with no edit to any of them (SC-004); "a failing summariser loses that compaction rather than stalling the rest" now also leaves the warning, which the suite does not assert.

### The pages (FR-008)

- [ ] T015 [US1] In `docs/build/agents.md`, *Compaction*, after the paragraph that ends "rather than stalling compaction for every session", add prose outside the language tabs (research R8, contracts/compactor.md): a session started with a data subject is compacted as any other; its summary is kept sealed under the data subject's key and is erased with it; the summariser's model is sent the conversation, which is beyond an erasure as what a session sent to its own model is (link to the data-subject section of the same page); a failed compaction logs a warning that names the session and never its text; while the keyring cannot be reached compaction waits. Present tense, no history, no mention that this was ever otherwise; read `ankka-brain/voice.md` first. The sentence T008's documentation step searches for must be on the page in the words the step uses.
- [ ] T016 [P] [US1] In `docs/build/personal-data.md`, *Agents*, add one sentence: with compaction on, the summary that replaces a session's oldest messages is among what is kept sealed, linking to `agents.md#compaction`. In `docs/concepts/agents.md`, *Compaction*, add the same statement in one sentence.
- [ ] T017 [P] [US1] In `tools/docs/skill/ankka-agents/SKILL.md`, at the rule "Start every session, autonomous instance or task about a person with that person's data subject", add that such a session is compacted like any other and its summary kept sealed. Run `just docs-sync`, then `just docs` (every page checks, the site builds).

## Phase 4: Polish

- [ ] T018 [P] In `.claude/rules/agents.md`, Traps, add, dated 2026-11-10, feature 064: a consumer of session memory's events sees `Sealed` for every event of a session started with a data subject after `Started`; match on `SessionMemoryEvent.opened(event)`, never on a plain case alone. The compactor matched `AiMessageAdded` and never compacted a sealed session, silently, from 042 until 064.
- [ ] T019 Run quickstart.md end to end: `sbt scalafmtCheckAll compile` (warning-free), `just features` (0 findings), `just docs`, the four suite commands of its section 2 with their scenario counts read from the output (five and six, never zero), then `sbt -Dankka.cluster.tests=off test` under `caffeinate -i`. Check SC-001 to SC-005 one by one against what ran and say in the commit which test shows each.
- [ ] T020 In `specs/064-sealed-session-compaction/spec.md` set **Status** to `Implemented`, and confirm `docs/reference/limitations.md` needs no change: its line that a Python, TypeScript, Rust or Go agent's compaction is not yet configurable still holds and is what excludes those languages here.

## Dependencies

- T002 before T003, T009. T004 before T005, T006. T006 before T009 (red is seen first). T007 before
  T008. T009 and T010 before T011, T013, T014. T015 before T011's last green and before T017's
  `just docs`. T019 after everything but T020.
- [P] together: T003 with T004; T007 with T004 to T006; T012 with T013; T016, T017 (its edit) and
  T018 with each other.

## Requirement to task

| Requirement | Tasks |
|---|---|
| FR-001 | T002, T004, T009 |
| FR-002 | T004, T006, T011 |
| FR-003, SC-002 | T007, T011 |
| FR-004 | T003, T007, T011 |
| FR-005 | T003, T009, T012 |
| FR-006 | T013 |
| FR-007, FR-009, SC-005 | T005, T010, T012 |
| FR-008 | T008, T015, T016, T017 |
| SC-001, SC-003 | T004, T011 |
| SC-004 | T001, T014 |

## Implementation strategy

The MVP is T002, T004, T006, T009 and T011: the sealed session compacts and a feature suite says
so. Everything after it (the warning, the erasure proofs, the outage case, the pages) is owed before
the pull request but each lands as its own commit, `feat(064): …`, `test(064): …`, `docs(064): …`.
