# Implementation Plan: Judgments — Fast, Typed Decisions from a System One Model

**Branch**: `018-judgments` | **Date**: 2026-09-30 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/018-judgments/spec.md`

## Summary

A second thing a service can ask a model for. A **judgment** is the answer to a set of typed
questions about a state — a choice, a score, a yes/no — each with the probabilities behind it,
from a System One model that writes no text. It reaches a Scala service in three places: an effect
an agent handler returns, a guardrail that asks instead of matching, and a scripted provider that
tests both offline. The first real provider is TypeSafe AI's Jev.

Technically: everything lives in `modules/agent`, in a new package `agent.judgment` (R1). A
`JudgmentProvider` is one method beside `ModelProvider`, and the platform verifies every answer
against the request whoever the provider is (R2). Questions are values validated where they are
declared, with wire ids and option keys of their own (R3); a judgment stores answers by those
names and types them at the read (R4). The effect is an ordinary `AgentEffect` carrying a plan, so
registration, the client and the sidecar are untouched (R5). A judged guardrail is a `Guardrail`
the two loops run through one shared function, which is where it gets the default provider,
reports its tokens, and fails closed with an error a caller can tell from a refusal (R6, R7).
Judgment tokens are a second figure on the session's record, written with the turn when there is
one and best-effort when there is not (R8). The adapter is the JDK's HTTP client and the module's
JSON tree on a virtual thread, with no new library (R9), built from the provider's documentation
and confirmed by a live suite the first time a key is present (R10).

## Technical Context

**Language/Version**: Scala 3 on JDK 21 (`agent`, `testkit` tests); JavaScript for one addition to
the local console's page (`cli/src/main/resources/console/app.js`)

**Primary Dependencies**: none added. `java.net.http.HttpClient` (JDK) for the adapter; jsoniter
(`Codecs.make`) and the module's own `Json` tree; `AnkkaExecutors.virtual` from `runtime`. `agent`'s
`libraryDependencies` stay `anthropicJava, pekkoHttp, pekkoStreamTyped`.

**Storage**: Postgres via the existing journal — one new event and one new state field on the
existing `ankka-session-memory` entity. **No DDL change**, no new component, no new entity type.

**Testing**: munit. Offline suites in `agent` (questions, codec, scripted provider, the shared
guardrail check, the adapter against the JDK's `HttpServer` on a loopback ephemeral port);
whole-service suites in `testkit` on `AnkkaTestKit` with `TestModelProvider` and the new
`TestJudgmentProvider`; a compatibility suite pinning session memory's stored form, written before
it changes; a live suite that `assume`s `TYPESAFE_API_KEY`. No k3s suite.

**Target Platform**: wherever a Scala ankka service runs. Nothing depends on the cluster overlay.

**Project Type**: platform library (`ankka-agent`), its tests in `ankka-testkit`, docs, one console asset

**Performance Goals**: one provider request per judgment and per text a guardrail checks,
whatever the number of questions (SC-002); the platform adds to the provider's own latency one
sharded call (the agent handler, as for any handler) and one local entity write for usage; the
offline suite for the feature under a minute (SC-005)

**Constraints**: `ankka-runtime`, `sdk`, `core`, `http` and the sidecar unchanged; effects stay
inert; wire names declared separately from Scala names; no secret in any journal, log or error;
session memory's stored form stays readable both ways for sessions without judgments;
`Test / parallelExecution := false` stays; `-Wunused` clean; no suite binds a fixed port

**Scale/Scope**: 7 new Scala files and 6 changed in `agent`'s main sources; 7 new suites in
`agent` and 4 in `testkit` plus one fixture agent; one suite changed by a constructor argument
and one by an added case;
1 new docs page and 12 changed; 1 skill; 1 console script

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

`.specify/memory/constitution.md` is the unfilled template, so the gate is the principles
`CLAUDE.md` states as the codebase's constitution:

| Principle | Status | How the design honours it |
|---|---|---|
| Effects are inert data; the runtime interprets | pass | `effects.judgment…thenReply()` builds a plan and asks nothing; `AgentLoop` interprets it. A refusal rule is a question and a predicate — data and a pure function. |
| Where two interpreters reduce the same thing, they share one function | pass | `Guardrails.check` replaces the guardrail iteration duplicated in `AgentLoop` and `IterationLoop`; `Judgments.ask` is the one place a provider is called and its answer verified (R2, R6) |
| Module dependency direction | pass | new code in `agent` only; `runtime` does not learn the word "judgment" — the console endpoint passes the session's reply through as bytes (R8) |
| No classpath scanning; explicit registration | pass | a judgment handler is registered with `command(…)` like any handler; no new descriptor kind |
| Wire names are a versioning boundary | pass | question ids and option keys are declared strings, never derived from Scala identifiers; a stored judgment holds only those (R3, R4) |
| `RuntimeExtension` seam | pass | the default provider is configured on `AgentRuntime`, which already is the extension |
| `ModuleCommand` — hosts handle unexpected commands, reply to `InvokeStream` | pass | no host gains a command; a streaming handler cannot return a judgment, by type |
| Never touch `ActorContext` from a `Future` callback | pass | judgments run on the loops' virtual threads and report through the paths those loops already use |
| Virtual threads make blocking free | pass | the adapter's retry loop and the guardrail's provider call are straight-line blocking code on `AnkkaExecutors.virtual` |
| One scripted model per consumer; scripts fail loudly | pass | the judgment script is a separate seam from the model script (FR-045 by construction); an unanswered question throws `JudgmentScriptFailed` naming it, and that type never takes the retry path (R11) |
| No secret value in a journal, a log or an error | pass | the key lives in one private field and one header; messages are built from status and body only; a suite asserts its absence (contract `provider-wire.md`) |
| A refusal is not a failure | pass | refused is `Forbidden`; could-not-check is `Unavailable`/`Timeout` — distinct codes on every path (R7) |
| Anything a consumer must see is a direct dependency of the published module | pass | nothing is added to any POM |
| A test never binds a fixed port | pass | the adapter suite's stand-in is loopback, port 0 |
| An `eventually` waits for the thing it asserts | pass | usage is written synchronously before the reply, so the suites read it without polling |
| Docs: pages stand alone, samples from tested code, new pages in nav and a skill, reference facts described | pass | R14 |
| Testkit-first: unit level with no runtime, whole service against Postgres | pass | R13 |

**Violations to justify**: none against these principles. Additions that widen the platform's
surface are under *Complexity Tracking*.

## Project Structure

### Documentation (this feature)

```text
specs/018-judgments/
├── plan.md              # this file
├── research.md          # R1–R14: decisions with file-level evidence; what the provider's docs do and do not establish
├── data-model.md        # question, request, judgment and its encoded form, judged guardrail, session memory's change, errors
├── quickstart.md        # the validation runs: pin → offline → adapter → whole service → live → docs
├── contracts/
│   ├── scala-api.md         # declaration, reading, the effect, the seam, runtime config, the guardrail, testing, internals
│   └── provider-wire.md     # what JevProvider sends, accepts, retries and never reveals
└── tasks.md             # /speckit-tasks output (not created here)
```

### Source Code (repository root)

```text
modules/agent/src/main/scala/…/agent/
├── judgment/                           # NEW package
│   ├── Question.scala                  # Question, Choice/Score/YesNoQuestion, ChoiceOption, the three typed answers; validation (data-model §1)
│   ├── Judgment.scala                  # Judgment, Judgment.Stored, codec + Serializer("judgment"), JudgmentState, JudgmentRequest, verification
│   ├── JudgmentProvider.scala          # the seam, JudgmentFailed, Judgments (private[ankka]: default + timeout, ask)
│   ├── JevProvider.scala               # the adapter (contracts/provider-wire.md)
│   ├── JudgedGuardrail.scala           # JudgedGuardrail, Refusal, Refuse
│   └── TestJudgmentProvider.scala      # the script, Answers, ScriptedAnswer, JudgmentScriptFailed
├── Guardrails.scala                    # NEW: the shared check — Direction, Spent, Refused, GuardrailCheckFailed (R6)
├── AgentEffect.scala                   # AgentEffect carries an optional plan; AgentEffects.judgment → JudgmentBuilder; Guardrail.judged
├── AgentLoop.scala                     # runs a plan; guardrails through Guardrails.check; fault/refusal mapping; judgment usage on every exit (R5, R7, R8)
├── AgentRuntime.scala                  # withJudgments; passes Judgments to both hosts; warns for a judged definition with no provider
├── SessionMemoryEntity.scala           # SessionHistory.judgmentUsage, JudgmentUsageAdded, Append.judgmentUsage (data-model §5)
└── autonomous/
    ├── IterationLoop.scala             # inputRejection and complete through Guardrails.check; usage to the task's session; JudgmentScriptFailed ends the task
    └── AutonomousAgentHost.scala       # a start check that could not be made is a counted, paused failure; no-provider task failure (R7)

modules/agent/src/test/scala/…/agent/judgment/
├── QuestionSuite.scala
├── JudgmentCodecSuite.scala            # + src/test/resources/judgment/judgment.json
├── JudgmentsSuite.scala                # the platform's ask: resolution, timeout, verification
├── TestJudgmentProviderSuite.scala
├── GuardrailsSuite.scala
├── JevProviderSuite.scala              # stand-in on the JDK's HttpServer; canned bodies in src/test/resources/judgment/jev-*.json
└── JevProviderLiveSuite.scala          # assume(TYPESAFE_API_KEY)

modules/testkit/src/test/scala/…/testkit/
├── SessionMemoryCompatibilitySuite.scala    # FIRST: fixtures of today's stored form in src/test/resources/journal/session-memory-*.json
├── TriageAgent.scala                        # fixture; the guide's docs:start regions
├── JudgmentAgentSuite.scala                 # US1, US3, US5, US4.5
├── JudgmentUnconfiguredSuite.scala          # a service with no judgment provider: the effect and the guardrail both say what to configure
└── autonomous/
    ├── JudgedGuardrailSuite.scala           # US3.8–9, R7's autonomous rows
    └── ResumePointSuite.scala               # one constructor argument

cli/src/main/resources/console/app.js   # a session's judgment tokens as their own figure, when present

docs/build/judgments.md                                   # NEW
docs/concepts/{agents,designing-agents}.md
docs/build/{agents,autonomous-agents,testing}.md
docs/reference/{scala-sdk,configuration,limitations,akka-divergences,glossary,error-codes}.md
docs/operate/local-console.md
mkdocs.yml                                                # nav
tools/docs/skill/ankka-agents/SKILL.md                    # pages: and a rule
marketplace/plugins/ankka/skills/… and ankka.g8/src/main/g8/.claude/skills/…   # rendered by `just docs-sync`, committed
```

**Structure Decision**: no new module and no new component. A package in `ankka-agent`, because
everything a judgment touches — the effect algebra, both loops, guardrails, session memory, the
scripted model — is there, and `AgentRuntime` is already where a service says which models it
uses (R1).

## Order of work

The dependencies between the pieces, for `/speckit-tasks`:

1. **Pin session memory** (`SessionMemoryCompatibilitySuite`, green against today's code) — before
   anything changes the entity.
2. **Questions, judgment, seam, scripted provider** — no dependency on the loops; the offline
   suites of quickstart §1.
3. **The effect** (US1) — `AgentEffect`, `AgentLoop`, `AgentRuntime.withJudgments`;
   `JudgmentAgentSuite`'s US1 cases. Tokens are not yet recorded.
4. **The adapter** (US2) — independent of 3; needs only 2. Offline suite, then the live suite.
5. **The shared guardrail check and the judged guardrail** (US3) — `Guardrails.check` replacing
   both loops' iteration with behaviour unchanged for existing guardrails (every existing agent
   and autonomous suite green) *before* `JudgedGuardrail` is added to it.
6. **The autonomous paths** — the start-check fault, the script-exhaustion guard, the no-provider
   task failure.
7. **Judgment tokens** (US5) — the session memory change, recording on every path of both loops,
   the console figure. After 3 and 5, because it records on the paths they create.
8. **Documentation and skill sync.**

## Complexity Tracking

| Addition | Why Needed | Simpler Alternative Rejected Because |
|---|---|---|
| A second provider seam (`JudgmentProvider`) beside `ModelProvider` | a System One model takes no conversation and returns no text | behind `ModelProvider` the agent loop would ask it for a turn and get nothing (R2) |
| `Guardrails.check`, and both loops routed through it | a judged guardrail needs the default provider, somewhere to report tokens, and a third outcome — none of which fit through `Either[String, Unit]` | widening the public `Guardrail` trait offers every hand-written guardrail a context it has no use for; returning `Left` for a fault makes it a refusal (R6) |
| A new event and state field on session memory, and a field on `Append` | tokens spent on a judgment must be recorded where no message is — a refusal, the effect itself | summing into `usage` mixes two prices into one figure; a separate entity is a second record for one number (R8) |
| `IterationFailed(0, …)` for a start check that could not be made | today a throwing input guardrail retries every second forever, which a provider outage would turn into an unbounded loop | leaving it: a task that never starts and never fails, logging once a second (R7) |
| `JudgmentScriptFailed`, distinct from `JudgmentFailed` | a test's unanswered question must fail the test now, not be retried as an outage | keying on the provider's name, as the model path does, leaves no way to script a genuine provider failure (R11) |
| One `thenReply(f)` beyond Akka's announced effect | lets a handler reply with the service's own type, so a caller need not see or store the platform's | without it every judgment handler's reply type is `Judgment`, and its stored form becomes every caller's concern (R5) |

## Constitution Check (post-design)

Re-evaluated after Phase 1: unchanged, all pass. The design adds no dependency, no DDL, no
published module, no descriptor field, no configuration key and no protocol change; it touches
`agent`, tests in `testkit`, one console script and the docs.

The spec was corrected in two places during planning. FR-040 and its edge case said a definition
with a judged guardrail and no provider is "refused at registration, as a definition with no
model is"; a definition with no model is not refused at registration — the service warns and the
task fails — so both now state that behaviour (R7). FR-023 named only rate limiting and overload
as retried; it now includes the other failures the provider's own clients treat as transient (R9).

It was corrected in four more after the cross-artifact analysis. FR-024 now places verification
of an answer on the platform, where R2 put it. FR-048 now says usage written without a message is
best effort (R8). SC-005 now bounds the suites that need no database and the whole-service suites
separately. The edge case about a binary state now says one cannot be offered, rather than that
it is refused.

## Reported with this plan, not part of it

`SessionMemoryEntity.append` attaches a batch's token usage to every AI message in the batch, and
the fold adds each, so a request-agent turn that used tools has its tokens counted twice (R8,
*Found in passing*). No suite asserts a session's total. This feature adds a field beside the
faulty line and leaves the line alone: fixing it changes reported totals for existing sessions
and is a decision of its own.

## Not in this feature (from the spec's Out of Scope, restated for the tasks)

No effect that judges and then continues into a text model interaction; no judgment client for
workflow steps, endpoints, consumers or tools; no guardrail on tool calls; no model routing by
judgment; no judged task-result rules; no Python, TypeScript or Rust surface, no protocol or ABI
change; no display of answers or probabilities anywhere; no provider other than Jev and the
script; no sample change. Judgment tokens are not added to a task's or an instance's own usage.
