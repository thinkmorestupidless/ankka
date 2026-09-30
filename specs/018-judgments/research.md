# Research: Judgments — Fast, Typed Decisions from a System One Model

**Feature**: `018-judgments` | **Date**: 2026-09-30

Every decision below was checked against the code in this repository (paths given) and, for the
provider, against TypeSafe's published documentation as fetched on 2026-09-30. Nothing here was
run against the live provider: no key was available while planning. R10 lists exactly what that
leaves unverified and which suite pins it.

## R1. Placement: a package in `modules/agent`, configured on `AgentRuntime`

**Decision**: Questions, the judgment, the provider seam, the Jev adapter, the scripted provider
and the judged guardrail live in a new package `com.thinkmorestupidless.ankka.agent.judgment`. The
effect builder is reached from the existing `AgentEffects` (`AgentEffect.scala:113`), the guardrail
from the existing `Guardrail` companion (`AgentEffect.scala:178`), and the service's default
provider is set on `AgentRuntime` beside the default model (`AgentRuntime.scala:229`).

**Rationale**: Everything a judgment touches is already in this module — the effect algebra, both
loops, guardrails, session memory, the scripted model. `ankka-runtime` must not see any of it
(CLAUDE.md, the `RuntimeExtension` seam), and nothing in `runtime`, `sdk`, `http` or `core`
changes. No new published module: a service that depends on `ankka-agent` gets judgments.

**Alternatives considered**: a module `ankka-judgment` that `agent` depends on — a seventh
service library for one package, whose only consumer in this feature is `agent`. It becomes worth
revisiting when a judgment client for workflow steps and endpoints is built, because that client
must be reachable without the agent runtime; the package boundary drawn here is what would move.

## R2. The provider seam is one method, and the request carries its deadline

**Decision**:

```scala
trait JudgmentProvider:
  def name: String
  def modelName: String
  def judge(request: JudgmentRequest): Future[Judgment]
```

`JudgmentRequest(state, questions, timeout)` is validated on construction (at least one question,
ids unique). A failure is a failed `Future` carrying `JudgmentFailed(provider, message, cause,
timedOut)`, the counterpart of `ModelCallFailed` (`ModelProvider.scala:43`). The platform never
calls `judge` directly: both loops and the guardrail go through one internal function,
`Judgments.ask`, which resolves the provider, awaits with the timeout, and **verifies the answer
against the request** — one answer per question, of the question's kind, naming only offered
options, probabilities in range.

**Rationale**: `ModelProvider` has two methods and ankka's own request and response types, so that
adding a provider is one adapter (`ModelProvider.scala:8–14`); the same argument gives this shape.
Verification sits in the platform rather than the adapter (FR-024) because a developer's own
provider deserves the same protection from a malformed answer as Jev's does, and because
`Judgment.apply` would otherwise be the first place a bad answer is noticed — in the developer's
handler, far from its cause. The timeout travels in the request because FR-023 requires the
adapter to stop retrying before it: the model seam leaves the timeout to the loop's `Await`
(`AgentLoop.scala:264`), which is right for a call that is never retried and wrong for one that is.

**Alternatives considered**: implementing `ModelProvider` — `complete` takes messages and tools
and returns text and tool calls (`messages.scala:89–135`); a System One model has neither, so the
agent loop would receive an empty turn. A synchronous `judge` — every caller is on a virtual
thread, so it would work, but a `Future` keeps the seam the same shape as its neighbour and lets a
provider be non-blocking if it wants.

## R3. Questions are values, validated where they are declared

**Decision**: Three final classes under a sealed `Question[A]`, where `A` is the type of the
answer read back: `ChoiceQuestion[T] extends Question[ChoiceAnswer[T]]`, `ScoreQuestion extends
Question[ScoreAnswer]`, `YesNoQuestion extends Question[YesNoAnswer]`. Each is built by one call
that validates and throws `IllegalArgumentException` naming the question (contract: `scala-api.md`
§1). A choice over a developer's type lists its options as `value -> (key, description)`; the
key is declared, never derived from the Scala case name. A choice over plain keys is
`ChoiceQuestion[String]`.

**Rationale**: A question declared as a `val` on a companion is constructed when the companion is
initialised, which for a registered agent is at startup — so FR-007's "refused where it is
declared" is a property of validating in the constructor, with no registration hook. A
single-call constructor (rather than a `.option(…).option(…)` builder, which is the shape Akka's
announcement shows) is what makes that possible: every intermediate value of a builder is a
question with too few options. The key is declared separately for the reason every wire name is
(CLAUDE.md, *Registration and handler identity*): a judgment is a reply and can be stored (R4),
so renaming `Team.Billing` must not change what a stored judgment means. The key is also read by
the model as part of the question, which is a second reason it should be a chosen word.

Exhaustiveness over an enumeration is **not** required: a question may offer a subset, and a case
added later is simply never chosen until it is offered.

**Alternatives considered**: deriving keys from enum case names with a `Mirror` — declined for the
same reason a macro deriving handler wire names was. A type class `Choices[T]` supplying keys and
descriptions for a type — one declaration per type rather than per question, but two questions
over one type legitimately describe its cases differently.

## R4. A judgment stores answers by wire name; typing happens at the read

**Decision**: `Judgment(model, answers: Map[String, Judgment.Stored], usage: TokenUsage)`, where
`Stored` is an enum of `Choice(key, probabilities: Map[String, Double], confidence)`,
`Score(score, probabilities: Vector[Double], confidence)` and `YesNo(probability)`.
`judgment(question)` finds the stored answer by the question's id and converts it with the
question: a choice maps the key back to the declared value. A missing id, a stored answer of
another kind, or a key the question does not declare throws naming the question (FR-010).
The codec is `Codecs.make` under manifest `judgment`, with a `Serializer[Judgment]` in the
companion so `command("triage")(_.triage)` finds it. `JudgmentCodecSuite` pins the JSON with a
fixture in `modules/agent/src/test/resources`.

**Rationale**: The stored form holds only declared names and numbers, which is FR-011 and what
lets a judgment sit in a workflow's state across a deploy that renames Scala identifiers. Under
the shared codec a sealed type encodes with `"type"` (`Serializer.scala:59–67`), so a stored
answer reads `{"type":"Choice","key":"billing",…}` — the discriminator is right here, since this
is a stored sum type and not a status word a CLI prints. Usage reuses `TokenUsage`
(`messages.scala:108`): the provider reports input and output tokens and the cache fields stay
zero. The fixture is not in `protocol/fixtures/`, which belongs to `EncodingFixturesSuite` and
describes the sidecar protocol; judgments do not cross it in this feature.

**Alternatives considered**: a `Judgment` parameterised by its questions so that reading a
question that was not asked fails to compile — it needs a type-level list of questions threaded
through the effect, the reply serializer and the client, for an error that the first test of the
handler finds anyway.

## R5. The effect is an `AgentEffect` with a judgment plan

**Decision**: `effects.judgment` returns a `JudgmentBuilder` with `state(text)`, `state(value)`
(anything with a `JsonValueCodec`, sent as structured data), `question(q, more*)`,
`provider(p)`, and two terminators: `thenReply(): AgentEffect[Judgment]` and
`thenReply[T](f: Judgment => T): AgentEffect[T]`. `AgentEffect` gains one private field holding
the plan; `AgentLoop.run` (`AgentLoop.scala:32`) checks it after `failure` and before `execute`.
The provider is resolved when the loop runs the plan — the effect's own, else the runtime's
default — not when the effect is built.

Running the plan: resolve the provider (none → `Internal`, saying what to configure, as "has no
model" does at `AgentLoop.scala:40–46`); `Judgments.ask`; record usage (R8); apply `f`. It reads
no history and appends no message. `JudgmentFailed` becomes `Unavailable`, or `Timeout` when it
timed out — the codes a failed model call gets (`AgentLoop.scala:266–281`), both `retryable`
(`CommandError.scala:33`).

**Rationale**: Handlers are registered as `A => I => AgentEffect[O]` (`Agent.scala:93`) and the
host casts what comes back to `AgentEffect[Any]` (`AgentRuntime.scala:382`), so a judgment that
*is* an `AgentEffect` needs no new registration method, no new handle type and no change to
`AgentCalls` — FR-031 for free. The sidecar builds its effects through the public builders
(`RemoteAgent.scala:62–88`) and is untouched by a private field. `thenStream` exists only on a
model interaction, so a streaming handler cannot return a judgment, by type. Resolving the
provider in the loop avoids adding a member to the public `AgentContext` trait (`Agent.scala:45`).

"Recorded as a fault, not a refusal" (FR-032, FR-038) is realised by the error code. Agent hosts
record no spans of their own (no `SpanOutcome` in `modules/agent`); the endpoint's span and the
HTTP status follow the code, and `Forbidden` and `Unavailable` are what tell a caller a refusal
from a check that could not be made.

**Alternatives considered**: a sealed `AgentEffect` hierarchy — every existing builder method
would move to one case and `RemoteAgent` would have to follow. A continuation
(`thenDecide(judgment => AgentEffect[R])`) — out of scope by the spec's second assumption.

## R6. A judged guardrail is a `Guardrail` the platform knows how to run

**Decision**: `Guardrail.judged(name)` returns a `JudgedGuardrail` — a final class extending
`Guardrail` — with `onInput(rules*)`, `onOutput(rules*)` and `provider(p)`. A rule is a
`Refusal[A]`: a `Question[A]` and a predicate over its typed answer, built by `Refuse.ifYes`,
`Refuse.ifChosen`, `Refuse.ifScore` or `Refuse.when`. Both loops stop iterating guardrails
themselves and call one new internal function:

```scala
private[ankka] object Guardrails:
  def check(guardrails, text, direction, judgments: Judgments, spent: Spent): Option[Refused]
```

For an ordinary guardrail it calls `checkInput`/`checkOutput` as today. For a `JudgedGuardrail`
it skips an empty text (FR-039) and a direction with no rules, makes one `Judgments.ask` carrying
that direction's questions, adds the tokens to `spent`, and returns the first rule met in
declaration order. A check that cannot be made throws `GuardrailCheckFailed(guardrail, cause)`.

**Rationale**: `Guardrail` is a synchronous `Either[String, Unit]` handed only the text
(`AgentEffect.scala:166–176`), and three things a judged guardrail needs do not fit through it:
the service's default provider (FR-040 — an autonomous agent's definition is built on a companion,
which cannot see a provider constructed in `Main`), somewhere to report tokens (FR-048), and a
third outcome besides allow and refuse (FR-038). The two loops already duplicate the guardrail
iteration (`AgentLoop.scala:325–336`, `IterationLoop.scala:255–257` and `337–340`); one shared
function is the shape CLAUDE.md asks for where two interpreters must not disagree, and it is the
place the three needs are met without changing the public trait. A blocking provider call is
legal there: guardrails run on the loop's virtual thread.

The refusal's reason is `question '<id>'` and nothing else (FR-035). The probability is
deliberately not in the error a caller sees: a judged guardrail reads text its author may be
tuning against it, and returning the score is an oracle for that tuning.

`JudgedGuardrail.checkInput` and `checkOutput`, called directly by code that is not the
platform's, work when the guardrail was given a provider of its own and otherwise throw, saying
it is run by the agent runtime.

**Alternatives considered**: adding `check(text, direction, context): Verdict` to the trait with
a default that delegates — a wider public surface, and every developer-written guardrail would be
offered a context it has no use for. A thread-local scope set by the loops — the codebase uses
them where work must see its request (`RequestContext`, `AutonomousAgent.CurrentTask`), but here
both callers are the platform's own and an argument is plainer. Returning `Left` for a fault — a
caller could not tell it from a refusal, which is FR-038's whole point.

## R7. What a refusal and a fault do on each path

| Path | Refusal | Check could not be made |
|---|---|---|
| Request agent, input (`AgentLoop.scala:48`) | `Forbidden`, `guardrail '<name>': question '<id>'`; no model call, no memory | `Unavailable` or `Timeout`, `guardrail '<name>' could not be checked: <provider>: <message>`; no model call, no memory |
| Request agent, output (`AgentLoop.scala:55`) | `Forbidden`; reply not written | `Unavailable`/`Timeout`; reply not written |
| Streaming handler, output (`AgentLoop.scala:110`) | as today: text already sent, memory not written | `StreamFailed` with `Unavailable`/`Timeout`; memory not written |
| Autonomous, task start (`AutonomousAgentHost.scala:572`) | task failed with the reason, as today | recorded as `IterationFailed(0, …)`, paused and retried; the task fails after `maxConsecutiveFailures` |
| Autonomous, completed result (`IterationLoop.scala:254`) | `ResultRejected`, reason returned to the model, as today | `Faulted`: the same recorded result is checked again after the pause, the model is not re-asked |

**Decision for the two autonomous rows**: the result row needs no new code path — `complete` is
already wrapped so that "a check that throws has decided nothing" (`IterationLoop.scala:218–228`),
and `GuardrailCheckFailed` is such a throw. The start row does: today a throw from
`inputRejection` escapes to `runLoop`'s catch-all, which warns and retries every second with no
bound (`AutonomousAgentHost.scala:461–464`). The host's `start` catches `GuardrailCheckFailed`,
records `IterationFailed(0, error)` — which the instance entity accepts for a selected task that
has not started (`InstanceEntity.scala:220–222`, `needsTask(None)`) and counts in
`consecutiveFailures` (`:161`) — and then applies the pause and the failure bound an iteration's
fault gets (`AutonomousAgentHost.scala:602–618`), lifted into one helper.

**A scripted provider that has run out fails the task at once.** The loop already does this for
the scripted model (`IterationLoop.scala:152–155`: a test that is no longer testing what it says
should fail now, not retry into a stall). For judgments the scripted provider says so with a type
of its own, `JudgmentScriptFailed`, rather than by its name (R11), and both autonomous rows fail
the task on it; without that an exhausted script costs the full backoff — about fifteen seconds —
before the task fails. A request agent reports it as `Internal`.

**No provider at all** (FR-040): a request agent's interaction fails `Internal` when made. For an
autonomous agent, `AgentRuntime.startAutonomous` warns beside the existing no-model warning
(`AgentRuntime.scala:167–172`) and the host fails each task in `work`, beside the existing
no-model branch (`AutonomousAgentHost.scala:557–562`). The spec said "refused at registration, as
a definition with no model is"; a definition with no model is *not* refused at registration, so
the spec's edge case and FR-040 were corrected to the behaviour the platform has.

## R8. Judgment tokens: a field on the session, an event, and one more field on `Append`

**Decision**:

- `SessionHistory` gains `judgmentUsage: TokenUsage = TokenUsage.zero` (`SessionMemoryEntity.scala:8`).
- `SessionMemoryEvent` gains `JudgmentUsageAdded(usage: TokenUsage)`; the fold adds it to
  `judgmentUsage` and touches nothing else.
- `SessionMemoryEntity.Append` gains `judgmentUsage: TokenUsage = TokenUsage.zero`; `append`
  emits the new event when it is non-zero, in the same `persistAll` as the messages, and with no
  messages persists it alone. No new command.
- Each loop accumulates what its guardrails and its judgment effect spent and passes it to the
  write it already makes on success (`AgentLoop.scala:391–394`). On a path that writes no message
  — a refusal, a fault after a judgment was made, the judgment effect itself, either autonomous
  check — it makes a usage-only `Append`, **best effort**: a failure is logged at warning and
  never changes the outcome. A refused request stays `Forbidden` even if its accounting could not
  be written.
- The local console's session view shows judgment tokens as their own figure when present
  (`cli/src/main/resources/console/app.js:563–568`). The endpoint needs no change: it passes the
  `history` reply through as bytes (`ObservabilityEndpoint.scala:267–300`).

**Rationale**: A session's tokens are the `usage` on its record and nothing else
(`SessionMemoryEntity.scala:51–53`); FR-048 and FR-049 therefore mean a second figure on the same
record. Reusing `Append` keeps the turn and its guardrails' tokens in one atomic write on the
success path. The autonomous agent's session for a task is `task:<id>`
(`IterationLoop.scala:347`), so the same write covers US5 scenario 4 with no change to the task or
instance records — which means `TaskRecord.usage` and `InstanceRecord.usage` do **not** include
judgment tokens, and the data model says so.

Compatibility: the shared codec omits a field at its default (jsoniter's `transientDefault`, not
overridden in `Serializer.scala:59–67`), so a session with no judgments serialises exactly as
today and a stored state or `Append` without the field decodes. Session memory has no
compatibility fixture today; `SessionMemoryCompatibilitySuite` adds one, **captured before the
change**, pinning today's state and every existing event. `SessionCompactor` ignores events it
does not name (`compaction.scala:110–111`).

One forward-compatibility consequence, stated rather than solved: during a rolling update, a
session that a new pod wrote `JudgmentUsageAdded` into cannot be replayed by an old pod. Only
sessions that used a judgment are affected, only a service's first deploy with judgments can
produce it, and the old pod has no judgment handler to call in that session anyway; it clears
when the roll completes. It is the same exposure any new handler has in a roll.

**Alternatives considered**: a field on `TokenUsage` — it would flow into task and instance
records too, but it would still need a new event to be recorded without a message, and it would
put a judgment figure on every model response. A separate platform entity for judgment usage — a
second record to read for one number.

**Found in passing, not fixed here**: `append` attaches the batch's `usage` to *every*
`AiMessageAdded` it emits (`SessionMemoryEntity.scala:86–87`), and the fold adds each
(`:51–53`). A request-agent turn that used tools writes two AI messages in one batch
(`AgentLoop.scala:185`, `:296–303`), so its tokens are counted twice. No suite asserts a session's
total. It is outside this feature — fixing it changes reported totals for existing sessions — and
is reported to the user with the plan.

## R9. The Jev adapter: the JDK's HTTP client, the module's JSON tree, a virtual thread

**Decision**: `JevProvider` sends `POST <base>/v1/systemone` with `Authorization: Bearer <key>`
using `java.net.http.HttpClient`. The body is built with the module's `Json` tree
(`Json.scala:13`) and the response read with `Json.parse`. `judge` runs as straight-line blocking
code in a `Future` on `AnkkaExecutors.virtual`: send, and on a transient failure sleep and send
again, until the request's deadline. Wire contract: `contracts/provider-wire.md`.

- Constructors: `fromEnv(model, env)` reads `TYPESAFE_API_KEY` and, if set, `TYPESAFE_BASE_URL` —
  the names the provider's own clients read — and throws naming the variable when the key is
  absent; `withApiKey(key, model, baseUrl)`. `env` defaults to `sys.env` and is a parameter so a
  test can pass a map (the sidecar's `Models` does the same).
- `DefaultModel = "jev-1.13.0"`: a version, not `jev-latest` (FR-021). The answering version is
  the response's `model` field (FR-026).
- Retries: 408, 429, every 5xx (529 among them) and connection failures; never 401 or 422.
  The wait is `retry-after-ms`, else `Retry-After`, else 250 ms doubling; a retry whose wait would
  pass the deadline is not made and the last failure is reported.
- The key is held in a private field, excluded from `toString`, sent only as the header, and no
  error message is built from request headers. Error text is the status and the response body.

**Rationale**: The provider publishes clients for Python and JavaScript only, and its API is one
request and one response. The JDK client is what `ObservabilityEndpoint` chose its server for —
no dependency — and `agent`'s dependencies stay `anthropicJava, pekkoHttp, pekkoStreamTyped`
(`build.sbt:261`). The `Json` tree fits because a question's `criteria` is an object, an array or
absent depending on its kind, and a state is a string or an arbitrary value: a derived codec
would need a hand-written sum for each. Sequential code with `Thread.sleep` on a virtual thread
is the idiom both loops are written in.

**Alternatives considered**: pekko-http's client — already a dependency, but it needs an
`ActorSystem` at construction, and a provider is constructed in `Main` before the service exists
(`AnthropicProvider.fromEnv()` sets the precedent of needing nothing).

## R10. What the provider's documentation establishes, and what it does not

Established from `docs.typesafe.ai` (API reference, primitives, models, confidence, SDK retries
and constants pages):

| Fact | Value |
|---|---|
| Endpoint, auth | `POST https://api.typesafe.ai/v1/systemone`, `Authorization: Bearer` |
| Request | `{model, state, questions}`; `state` a string, object or array |
| Choice | `{type:"choice", instructions, criteria:{key: description}}`, up to 255 options → `{type, choice, probabilities:{key: p}, confidence}` |
| Score | `{type:"score", instructions, criteria:[level…]}`, 2–10 levels → `{type, score, probabilities:{"0": p, …}, confidence, legend}`; `score` is the probability-weighted mean of the level numbers, counted from 0 |
| Yes/no | `{type:"noul", instructions, criteria?:{true, false}}` → `{type, noul}`; no confidence |
| Response | `{model, answers, usage:{input_tokens, output_tokens}}`; `model` is the resolved version |
| Errors | 401 key, 422 invalid, 429 rate limit, 529 overloaded |
| Retry hints | `Retry-After`, `retry-after-ms`; the provider's clients retry 408, 429 and 5xx |
| Limits | 64k tokens per request; 32k for state plus the longest question; text only |
| Confidence | from the spread of the probabilities; for three options `(3·max − 1) / 2` |
| Variables | `TYPESAFE_API_KEY`, `TYPESAFE_BASE_URL` |

**Not established**, and pinned by `JevProviderLiveSuite` the first time it runs with a key: the
body of an error response (the adapter reports it verbatim, so its shape does not matter to
correctness); whether `Retry-After` is always present on 429; and that the documented shapes are
what the live endpoint returns. Published rate limits disagree between the provider's models page
(100k tokens and 40 requests a second) and a third-party guide (250k tokens a second, 1,200
requests a minute); the adapter relies on neither — it retries on what it is told. The offline
`JevProviderSuite` uses the documented examples as its canned responses, and the live suite's
first job is to confirm them.

The provider is in early access and direct access is by waiting list. The adapter speaks one wire
format at a configurable address, so a gateway or a compatible service that preserves that format
is a change of base address; one that reshapes it (a different path, a different envelope) is a
different adapter and out of scope.

## R11. The scripted provider: a queue, standing answers, and a strict match

**Decision**: `TestJudgmentProvider` (in `agent`'s main sources, beside `TestModelProvider`,
`name = "test"`) holds a queue of scripted judgments and a map of standing answers by question id.
For each request:

1. Questions with a standing answer take it.
2. If any question remains, the head of the queue is taken and must answer exactly the remaining
   questions: a missing one fails naming it (FR-042), and an answer for a question the request did
   not ask fails naming that (FR-043).
3. If none remains, the queue is not touched (a standing answer is given "without consuming the
   queue").

A script that cannot answer throws `JudgmentScriptFailed`, which is deliberately not a
`JudgmentFailed`: a test's mistake must not take the path a provider's outage takes, where it
would be retried. `failNext(message, timedOut)` is how a test produces a real `JudgmentFailed`,
to exercise that path on purpose.

Answers are scripted with `Answers.choice(q, value)`, `Answers.score(q, score)`,
`Answers.yesNo(q, probability)`, or with full probabilities, and validated against the question
as they are scripted. From a value alone: a choice puts its probability on the chosen option —
all of it, or, given a `confidence`, the share that the provider's documented formula,
generalised to `(n·max − 1)/(n − 1)`, maps to that confidence, with the rest spread evenly; a
score splits between the two levels either side so that the weighted mean is the score. From
full probabilities the choice is the largest and the score the weighted mean. Every request is
recorded.

**Rationale**: `TestModelProvider` answers from a queue and from rules and fails when both are
empty (`TestModelProvider.scala:25–30`); the same two mechanisms are needed here for the same two
reasons — an effect's judgment is scripted per call, a guardrail's is asked on every request.
The strict match is what makes "I added a question and forgot the test" a failure that names the
question. Being a different seam from the scripted model gives FR-045 by construction, which is
the trap CLAUDE.md records for one scripted model serving two consumers.

## R12. Configuration is code, as the default model is

**Decision**: `AgentRuntime` gains `withJudgments(provider, timeout = 5.seconds)`, an instance
method returning a new runtime as `withCompaction` does (`AgentRuntime.scala:47`). No key in
`reference.conf`, no new environment variable of ankka's own.

**Rationale**: The default model is set in code (`AgentRuntime.withDefaultModel`), and a provider
is an object a developer constructs. A service with judgments and no text model is
`AgentRuntime().withJudgments(p)`. The whole-service test kit takes extensions
(`AnkkaTestKit.scala:167`), so FR-046 needs no test kit change: a suite passes
`AgentRuntime.withDefaultModel(model).withJudgments(script)`.

## R13. Where the tests live

**Decision**: Suites that need only `agent` go in `modules/agent/src/test` — `QuestionSuite`,
`JudgmentCodecSuite`, `TestJudgmentProviderSuite`, `GuardrailsSuite` (the shared check against the
scripted provider, no runtime), `JevProviderSuite` (against the JDK's `HttpServer` on a loopback
ephemeral port) and `JevProviderLiveSuite` (`assume`s the key, as `AnthropicProviderSuite.scala:23`
does). Suites that need a running service or an entity test kit go in `modules/testkit/src/test`
— `JudgmentAgentSuite` with its fixture `TriageAgent`, `autonomous/JudgedGuardrailSuite`, and
`SessionMemoryCompatibilitySuite` — because `testkit` depends on `agent` and not the reverse.

**Rationale**: CLAUDE.md's trap list: `testkit` depends on `agent`, so anything needing
`EventSourcedTestKit` or `AnkkaTestKit` for an agent-module type lives in `testkit`. No suite binds
a fixed port. No k3s suite is needed: nothing here depends on where the process runs.

## R14. Documentation

**Decision**: One new page, `docs/build/judgments.md` (kind `guide`, language `scala`), in the
navigation after *Agents* and in the `ankka-agents` skill's `pages:` list. Its samples are regions
of `TriageAgent.scala` and `JudgmentAgentSuite.scala` in `testkit`'s tests — the pattern
`docs/build/agents.md` uses with `WeatherAgent.scala`. Changed pages: `concepts/agents.md` (what a
judgment is, beside *Model providers*), `concepts/designing-agents.md` (when to judge rather than
ask a text model; what a judged guardrail can be talked out of), `build/agents.md` (judged
guardrails, `withJudgments`), `build/autonomous-agents.md` (guardrails on a definition),
`build/testing.md` (the scripted provider), `reference/scala-sdk.md`,
`reference/configuration.md` (the two `TYPESAFE_` variables, in the hand-written *Agents and
models* prose — the generated block is `reference.conf`'s and does not change),
`reference/limitations.md`, `reference/akka-divergences.md`, `reference/glossary.md`,
`reference/error-codes.md` (what a guardrail answers when it refuses and when its check could not
be made), `operate/local-console.md` (the session view's second figure). `just docs-sync` re-renders the
skill into `marketplace/` and `ankka.g8/`.

**Rationale**: `docs check` refuses a page that is not in the navigation and a skill, a sample
that drifted from its region, and a page that refers to a feature number or to "above"
(CLAUDE.md, *Documentation*). The samples in `samples/` are untouched (SC-008).

The differences from Akka's announced API the divergences page records: questions are declared
values built in one call, not inline `Question.choice(…).option(…)` builders; a judgment is read
through the question, typed; the effect can reply with a value computed from the judgment; Akka
has not published how a `Judgment` is read or tested, so those are ankka's own.
