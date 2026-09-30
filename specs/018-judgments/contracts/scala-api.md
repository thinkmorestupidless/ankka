# Contract: the Scala API (`modules/agent`, package `…ankka.agent.judgment`)

Everything below is public API of `ankka-agent` unless marked internal. Names are final; bodies are
not shown. `Guardrail`, `AgentEffects` and `AgentRuntime` are in the existing `…ankka.agent`
package.

## 1. Declaring questions

```scala
enum Team:
  case Billing, Technical, Sales

object TriageAgent extends Agent.Companion[TriageAgent](ComponentId("triage")):

  val route: ChoiceQuestion[Team] =
    Question.choice[Team]("route", "Which team should handle this ticket?")(
      Team.Billing   -> ("billing", "Payments, invoicing, refunds"),
      Team.Technical -> ("technical", "Bugs, outages, integrations"),
      Team.Sales     -> ("sales", "Pricing, upgrades, new accounts")
    )

  val frustration: ScoreQuestion =
    Question.score("frustration", "How frustrated is the customer?")(
      "Calm, stating facts",
      "Frustrated but civil",
      "Angry, strong language",
      "Abusive"
    )

  val refund: YesNoQuestion =
    Question.yesNo("refund", "The customer asks for a refund")

  val urgent: YesNoQuestion =
    Question.yesNo("urgent", "The ticket needs a reply today")
      .describing(yes = "A deadline, an outage or money at risk", no = "It can wait")
```

```scala
sealed trait Question[A]:
  def id: String                 // the wire name
  def instructions: String

object Question:
  def choice[T](id: String, instructions: String)(options: (T, (String, String))*): ChoiceQuestion[T]
  def choiceByKey(id: String, instructions: String)(options: (String, String)*): ChoiceQuestion[String]
  def score(id: String, instructions: String)(levels: String*): ScoreQuestion
  def yesNo(id: String, instructions: String): YesNoQuestion

final class ChoiceQuestion[T] extends Question[ChoiceAnswer[T]]:
  def options: Vector[ChoiceOption[T]]          // ChoiceOption(value, key, description)
final class ScoreQuestion extends Question[ScoreAnswer]:
  def levels: Vector[String]
final class YesNoQuestion extends Question[YesNoAnswer]:
  def describing(yes: String, no: String): YesNoQuestion
```

Each constructor throws `IllegalArgumentException("question '<id>': <rule>")` on a violation of
`data-model.md` §1. In `choiceByKey` the pair is `key -> description` and the value read back is
the key.

## 2. Reading a judgment

```scala
final case class Judgment(model: String, answers: Map[String, Judgment.Stored], usage: TokenUsage):
  def apply[A](question: Question[A]): A        // throws IllegalArgumentException naming the question
  def contains(question: Question[?]): Boolean

object Judgment:
  enum Stored: …                                 // data-model.md §3
  given JsonValueCodec[Judgment]
  given Serializer[Judgment]                     // manifest "judgment"

final case class ChoiceAnswer[T](choice: T, probabilities: Map[T, Double], confidence: Double)
final case class ScoreAnswer(score: Double, probabilities: Vector[Double], confidence: Double):
  def level: Int                                 // the nearest level, counted from 0
final case class YesNoAnswer(probability: Double)
```

```scala
val judgment = client.forAgent(SessionId(ticket.id)).call(TriageAgent.triage).invoke(ticket)
val team     = judgment(TriageAgent.route)       // ChoiceAnswer[Team]
if team.confidence < 0.5 then toAPerson(ticket)
else queue(team.choice, refundAsked = judgment(TriageAgent.refund).probability >= 0.7)
```

## 3. The judgment effect

```scala
final class TriageAgent(context: AgentContext) extends Agent:

  def triage(ticket: Ticket): Effect[Judgment] =
    effects.judgment
      .state(ticket.text)
      .question(TriageAgent.route, TriageAgent.frustration, TriageAgent.refund)
      .thenReply()

  def routing(ticket: Ticket): Effect[Routing] =
    effects.judgment
      .state(ticket)                             // a structured state: needs a JsonValueCodec[Ticket]
      .question(TriageAgent.route, TriageAgent.urgent)
      .thenReply { judgment =>
        val team = judgment(TriageAgent.route)
        Routing(
          team = Option.when(team.confidence >= 0.5)(team.choice),
          urgent = judgment(TriageAgent.urgent).probability >= 0.7
        )
      }
```

```scala
// on AgentEffects, i.e. `effects` inside an agent
def judgment: JudgmentBuilder

final class JudgmentBuilder:
  def state(text: String): JudgmentBuilder
  def state[S](value: S)(using JsonValueCodec[S]): JudgmentBuilder
  def question(first: Question[?], more: Question[?]*): JudgmentBuilder   // may be called repeatedly
  def provider(provider: JudgmentProvider): JudgmentBuilder
  def thenReply(): AgentEffect[Judgment]
  def thenReply[T](reply: Judgment => T): AgentEffect[T]
```

- Building asks nothing. `thenReply` throws `IllegalArgumentException` when no state was given,
  no question was given, or two questions share an id — the handler's failure, `Internal`.
- The handler is registered with `command("triage")(_.triage)` like any other; its reply needs a
  `Serializer`, which `Judgment` has.
- Running it reads no session history and writes no message; it adds the judgment's tokens to the
  session's `judgmentUsage`.
- `reply` runs on the loop's virtual thread after the judgment is verified; it should be a pure
  function of the judgment.

## 4. The provider seam

```scala
trait JudgmentProvider:
  def name: String
  def modelName: String
  def judge(request: JudgmentRequest): Future[Judgment]

final case class JudgmentRequest(state: JudgmentState, questions: Vector[Question[?]], timeout: FiniteDuration)
enum JudgmentState:
  case Text(text: String)
  case Structured(value: Json)

final case class JudgmentFailed(
    provider: String, message: String, cause: Option[Throwable] = None, timedOut: Boolean = false
) extends RuntimeException
```

A provider answers every question in `request.questions`, keyed by id, or fails the `Future` with
`JudgmentFailed`. It should give up by `request.timeout`; the platform stops waiting then whether
it has or not. The platform verifies every answer (`data-model.md` §3), so a provider need not.

```scala
object JevProvider:
  val DefaultModel: String   = "jev-1.13.0"
  val DefaultBaseUrl: String = "https://api.typesafe.ai"
  def fromEnv(model: String = DefaultModel, env: Map[String, String] = sys.env): JevProvider
  def withApiKey(apiKey: String, model: String = DefaultModel, baseUrl: String = DefaultBaseUrl): JevProvider
```

`fromEnv` reads `TYPESAFE_API_KEY` (required; its absence throws
`IllegalArgumentException("TYPESAFE_API_KEY is not set")`) and `TYPESAFE_BASE_URL` (optional).
`name` is `"jev"`. Its behaviour on the wire is `provider-wire.md`.

## 5. Configuring the runtime

```scala
// on AgentRuntime (instance method; returns a new runtime, as withCompaction does)
def withJudgments(provider: JudgmentProvider, timeout: FiniteDuration = 5.seconds): AgentRuntime
```

```scala
Ankka.service
  .register(TriageAgent.descriptor)
  .registerAll(AgentRuntime.descriptors)
  .withExtension(AgentRuntime.withDefaultModel(AnthropicProvider.fromEnv()).withJudgments(JevProvider.fromEnv()))
```

A service that only judges: `AgentRuntime().withJudgments(provider)`.

## 6. The judged guardrail

```scala
val safety: Guardrail =
  Guardrail
    .judged("safety")
    .onInput(Refuse.ifYes(Safety.overridesInstructions, atLeast = 0.7))
    .onOutput(
      Refuse.ifYes(Safety.givesMedicalAdvice, atLeast = 0.7),
      Refuse.ifScore(Safety.hostility, atLeast = 2),
      Refuse.ifChosen(Safety.topic, minConfidence = 0.6)(Topic.Legal, Topic.Medical)
    )

effects
  .systemMessage(…)
  .userMessage(question)
  .guardrails(Guardrail.maxInputLength(4000), safety)   // the free check first
  .thenReply()
```

```scala
// on the Guardrail companion
def judged(name: String): JudgedGuardrail

final class JudgedGuardrail extends Guardrail:
  def onInput(rules: Refusal[?]*): JudgedGuardrail
  def onOutput(rules: Refusal[?]*): JudgedGuardrail
  def provider(provider: JudgmentProvider): JudgedGuardrail

object Refuse:
  def ifYes(question: YesNoQuestion, atLeast: Double): Refusal[YesNoAnswer]
  def ifChosen[T](question: ChoiceQuestion[T], minConfidence: Double = 0.0)(options: T*): Refusal[ChoiceAnswer[T]]
  def ifScore(question: ScoreQuestion, atLeast: Double): Refusal[ScoreAnswer]
  def when[A](question: Question[A])(refuse: A => Boolean): Refusal[A]
```

- `Refuse.*` validates its arguments against the question — a threshold outside 0 to 1, an option
  the question does not offer, a level off the scale, no options — and throws naming the question.
- `onInput`/`onOutput` throw when two rules in one direction share a question id.
- One request per text checked, carrying that direction's questions; the first rule met, in
  declaration order, is the one named. An empty text is not checked.
- A refusal is `Forbidden`, `guardrail 'safety': question 'overrides-instructions'`.
- A check that could not be made is `Unavailable` or `Timeout`,
  `guardrail 'safety' could not be checked: jev: …`. The interaction does not proceed.
- On an autonomous agent's definition (`define.guardrails(safety)`) the input rules see a task's
  instructions and the output rules see the completed result as JSON; outcomes are
  `research.md` R7.
- `checkInput` and `checkOutput`, called directly, run the check when the guardrail has a provider
  of its own and throw `IllegalStateException` otherwise.
- A judged guardrail with no rule in either direction is refused where it is used: an autonomous
  agent's `descriptor` lists it among the definition's problems, and a request agent's interaction
  fails `Internal`, naming the guardrail.

## 7. Testing

```scala
final class TestJudgmentProvider(val modelName: String = "test-judge") extends JudgmentProvider:
  def expect(answers: ScriptedAnswer*): TestJudgmentProvider    // queues one judgment's answers
  def always(answers: ScriptedAnswer*): TestJudgmentProvider    // standing answers, by question
  def failNext(message: String, timedOut: Boolean = false): TestJudgmentProvider   // queues one failure: a later judgment fails as a provider fails
  def reporting(usage: TokenUsage): TestJudgmentProvider        // the tokens every judgment reports; zero by default
  def requests: Vector[JudgmentRequest]
  def lastRequest: JudgmentRequest
  def callCount: Int
  def reset(): Unit                                             // queue, standing answers, queued failures, recorded requests; reported usage back to zero

object Answers:
  def choice[T](question: ChoiceQuestion[T], choice: T, confidence: Double = 1.0): ScriptedAnswer
  def choiceWith[T](question: ChoiceQuestion[T], probabilities: Map[T, Double]): ScriptedAnswer
  def score(question: ScoreQuestion, score: Double): ScriptedAnswer
  def scoreWith(question: ScoreQuestion, probabilities: Vector[Double]): ScriptedAnswer
  def yesNo(question: YesNoQuestion, probability: Double): ScriptedAnswer

final class JudgmentScriptFailed(message: String) extends RuntimeException(message)
```

```scala
val judge = TestJudgmentProvider()
  .always(Answers.yesNo(Safety.overridesInstructions, 0.02))
  .expect(
    Answers.choice(TriageAgent.route, Team.Billing, confidence = 0.9),
    Answers.score(TriageAgent.frustration, 2),
    Answers.yesNo(TriageAgent.refund, 0.97)
  )

val testKit = AnkkaTestKit.start(
  Seq(TriageAgent.descriptor) ++ AgentRuntime.descriptors,
  Seq(AgentRuntime.withDefaultModel(model).withJudgments(judge))
)
```

- `name` is `"test"`. Matching is `research.md` R11: standing answers first; if any question
  remains, the head of the queue must answer exactly those.
- A question with no answer, a queued answer for a question not asked, and an empty queue when
  one is needed each throw `JudgmentScriptFailed` naming the questions. It is not a
  `JudgmentFailed`: a request agent reports it as `Internal`, and an autonomous agent fails the
  task with it at once rather than retrying.
- `Answers.*` validate against the question as they are called. Each `failNext` queues one failure, taken in order before any answer is looked up; it produces a genuine
  `JudgmentFailed("test", …)`, which takes the fault path.
- `AnkkaTestKit` is unchanged: a judgment provider arrives through the `AgentRuntime` extension.

## 8. Internal (`private[ankka]`), named here because two loops share them

```scala
final case class Judgments(default: Option[JudgmentProvider], timeout: FiniteDuration):
  def ask(provider: Option[JudgmentProvider], state: JudgmentState, questions: Vector[Question[?]]): Judgment

object Guardrails:
  enum Direction { case Input, Output }
  final class Spent                                   // judgment tokens spent by checks; one thread
  final case class Refused(guardrail: String, reason: String)
  final class GuardrailCheckFailed(val guardrail: String, val cause: Throwable) extends RuntimeException
  def check(guardrails: Vector[Guardrail], text: String, direction: Direction,
            judgments: Judgments, spent: Spent): Option[Refused]
```

`Judgments.ask` resolves the provider, builds the request with the runtime's timeout, awaits,
verifies, and throws `JudgmentFailed` (or lets `JudgmentScriptFailed` through).
`AgentLoop`, `IterationLoop` and `AutonomousAgentHost` take a `Judgments`; `ResumePointSuite`
constructs an `IterationLoop` and gains the argument.

## Reserved names

Serializer manifest `judgment`; provider names `jev` and `test`; environment variables
`TYPESAFE_API_KEY` and `TYPESAFE_BASE_URL` (the provider's own, read only by `JevProvider.fromEnv`).
