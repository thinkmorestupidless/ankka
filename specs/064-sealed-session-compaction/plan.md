# Implementation Plan: Compaction of a session started with a data subject

**Branch**: `064-sealed-session-compaction` | **Date**: 2026-11-10 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/064-sealed-session-compaction/spec.md`

## Summary

The session compactor matches only the plain `AiMessageAdded`, and a session started with a data
subject persists every event as `Sealed`, so such a session is never compacted. The fix opens a
sealed event in the compactor through one function shared with the session memory entity
(`SessionMemoryEvent.opened`), and considers the session when what it holds is an AI message. The
summary is already written sealed by the entity. Every compaction failure now logs one warning
naming the session and the kind of failure, never any text. The guide says compaction applies to a
session started with a data subject and that the summariser's model is sent its conversation.

## Technical Context

**Language/Version**: Scala 3.9.0 on JDK 21

**Primary Dependencies**: Apache Pekko 1.7.0 (projections carry the consumer); jsoniter-scala; slf4j

**Storage**: Postgres, unchanged: no DDL, no new event, no new column

**Testing**: munit; `AnkkaTestKit` with `InMemoryKeyring` and `TestModelProvider`; `GherkinSuite`
for the two feature files; `LogCapturing` for the warning

**Target Platform**: a service's own JVM (the compactor is a platform consumer in `ankka-agent`)

**Project Type**: library (the `agent` module), with tests in `testkit` and `controlplane`

**Languages reached**: Scala only. Compaction is turned on from Scala, Java and Kotlin; a session is
started with a data subject from Scala, Python, TypeScript, Rust and Go; the sidecar never turns
compaction on (`limitations.md`: "compaction of its sessions is not yet configurable"), and Java and
Kotlin services start no session with a data subject (the agents guide). The two sets meet in Scala
alone, so no other language gets a sample or a tab (research R5).

**Performance Goals**: none new. The compactor does one JSON read more per sealed event.

**Constraints**: no conversation text in any log line; no change to the bytes a session with no
data subject writes; the consumer's offset is not reset; warning-free compile (`-Werror`)

**Scale/Scope**: about 20 lines in `agent` main; one new Gherkin suite, steps added to another,
cases added to two existing unit suites; three docs pages, one skill line, one rule-file trap

## Constitution Check

`.specify/memory/constitution.md` is the unfilled template, so the gates are the repository's own
rules (`CLAUDE.md`, `.claude/rules/`):

| Rule | How this plan holds it |
|---|---|
| Effects are inert data; shared reductions are one function | `SessionMemoryEvent.opened` is the one reading of a sealed event, used by the entity and the compactor |
| Could this check pass while the thing it checks is false? | the sealed scenarios are red on `main`; "no summary is asked" waits on a later compaction before reading the count (R7); the plain scenarios are broken once to see them fail |
| One `TestModelProvider` cannot serve both an agent and the compactor (`agents.md`) | every suite gives the summariser a provider of its own |
| An `eventually` waits for the thing it asserts | each wait is on the history's shape or on `SessionCompactor.compacted`, never a sleep |
| A fixed port, a plain `ActorSystem` | none added; the suites are `AnkkaTestKit`'s |
| Every suite that can see `testkit` mixes in `LogCapturing` | `CompactionFeatures` does, and reads `capturedLog` for the warning |
| Wire names and stored shapes are versioning boundaries | no event case, command or manifest is added or renamed (R9) |
| Docs stand alone and tell no history | the pages say what compaction does, not that it was broken (R8) |
| Traps are written down the day they cost time | `.claude/rules/agents.md` gains the `Sealed` trap, dated |
| CI maps every tracked file to a job | new files fall under `features/**`, `modules/**`, `controlplane/**`, `docs/**`, already claimed |

Re-checked after design: no violation.

## Project Structure

### Documentation (this feature)

```text
specs/064-sealed-session-compaction/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   └── compactor.md
└── tasks.md             # /speckit-tasks
```

### Source Code (repository root)

```text
modules/agent/src/main/scala/com/thinkmorestupidless/ankka/agent/
├── SessionMemoryEntity.scala      # SessionMemoryEvent.opened; applyEvent's Sealed case reads through it
└── compaction.scala               # onMessage opens the event; the catch logs the warning

modules/agent/src/test/scala/com/thinkmorestupidless/ankka/agent/
├── SealingSuite.scala             # opened: plain, sealed, erased
└── CompactionSuite.scala          # what the compactor acts on, by event; the warning's text

modules/testkit/src/test/scala/com/thinkmorestupidless/ankka/testkit/
├── agents/CompactionFeatures.scala        # GherkinSuite over features/agents/compaction.feature
├── agents/CompactionSteps.scala
└── CompactionIntegrationSuite.scala       # not edited: its seven cases are SC-004's proof

controlplane/src/test/scala/com/thinkmorestupidless/ankka/controlplane/
└── ErasureAgentsFeatures.scala    # compaction on, a summariser's provider, steps for two scenarios

features/agents/compaction.feature         # written in the design
features/erasure/agents.feature            # two scenarios written in the design
GLOSSARY.md                                # summary, compaction size

docs/build/agents.md                       # Compaction
docs/build/personal-data.md                # Agents
docs/concepts/agents.md                    # Compaction
tools/docs/skill/ankka-agents/SKILL.md     # one line; rendered by `just docs-sync`
.claude/rules/agents.md                    # the trap
```

**Structure Decision**: the change is in the `agent` module, where the compactor and the session
memory entity live. Its proofs sit where the fixtures already are: `testkit` for the kit suites
(`agent` cannot see `testkit`), `controlplane` for `features/erasure/agents.feature`, which
`ErasureAgentsFeatures` already runs whole.

## Complexity Tracking

No violation to justify.
