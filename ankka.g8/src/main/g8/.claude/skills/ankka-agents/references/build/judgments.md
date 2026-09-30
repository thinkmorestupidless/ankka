# Judgments

> Ask a System One model typed questions about a state — a choice, a score, a yes or no — read typed answers with their probabilities, guard agents with them, and test them offline.

Source: https://docs.ankka.cloud/build/judgments/
A judgment is the answer to a set of typed questions about a state, from a model that writes no text.
You send a state — a piece of text, or a structured value — and questions of three kinds: a **choice**
among described options, a **score** on a scale of described levels, and a **yes or no**. Every question
is answered at once, each answer restricted to the shape its question allows and carrying the
probabilities behind it. A model of this kind is called a System One model; ankka's first provider is
TypeSafe AI's Jev.

A judgment is the right tool for a decision with a bounded set of answers that code then acts on: which
team takes a ticket, how angry a customer is, whether a message tries to talk an agent out of its
instructions. It answers in a fraction of a second, costs a small fraction of a text model call, and
says how sure it is. It is the wrong tool for anything that needs prose, a tool call, arithmetic, or
facts that are not in the state.

## Declaring questions

A question is a value, declared once on the agent's companion and used both to ask and to read the
answer.

```scala
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
  Question
    .yesNo("urgent", "The ticket needs a reply today")
    .describing(yes = "A deadline, an outage or money at risk", no = "It can wait")
```

- `Question.choice[T](id, instructions)(value -> (key, description), …)` offers two to 255 options that
  are values of your own type; `Question.choiceByKey` offers plain keys. A question need not offer every
  value of its type.
- `Question.score(id, instructions)(levels*)` places the state on two to ten ordered levels, counted from
  0. The answer may lie between two levels: it is the probability-weighted mean of the level numbers.
- `Question.yesNo(id, instructions)` answers with the probability that the answer is yes;
  `.describing(yes, no)` says what each means.

The id and each option's key are **wire names**. They are what the provider is sent and what a stored
judgment is keyed by, so they are declared rather than derived from a Scala name: renaming a `val` or an
enum case must not change what a stored judgment means. An option's key is also part of what the model
reads, so make it a meaningful word. Every question is checked where it is built — an empty id, a choice
with one option or two options sharing a key, a score with eleven levels — so a mistake fails the
service at startup, not at its first request.

## Asking from a handler

An agent's handler describes a judgment as an effect and returns it, as it would an interaction with a
text model:

```scala
def triage(ticket: Ticket): Effect[Judgment] =
  effects.judgment
    .state(ticket.text)
    .question(TriageAgent.route, TriageAgent.frustration, TriageAgent.refund)
    .thenReply()
```

The handler is registered like any other, `val triage = command("triage")(_.triage)`, and called through
the component client. The caller reads each answer through the question that asked it, and the answer is
typed:

```scala
val judgment = client.forAgent(SessionId(ticket.id)).call(TriageAgent.triage).invoke(ticket)
val team     = judgment(TriageAgent.route)          // ChoiceAnswer[Team]
if team.confidence < 0.5 then sendToAPerson(ticket)
else queue(team.choice, refundAsked = judgment(TriageAgent.refund).probability >= 0.7)
```

Reading a question that was not asked throws, naming it; a judgment never answers with a default.

A handler can reply with a value of the service's own instead, computed from the judgment, so its caller
never sees the judgment at all. The state can be a structured value, which the provider receives as
structured data:

```scala
/**
 * Judges the whole ticket, and replies with the service's own decision rather than the judgment.
 */
def routing(ticket: Ticket): Effect[Routing] =
  effects.judgment
    .state(ticket)
    .question(TriageAgent.route, TriageAgent.urgent)
    .thenReply { judgment =>
      val team = judgment(TriageAgent.route)
      Routing(
        team = Option.when(team.confidence >= 0.5)(team.choice),
        urgent = judgment(TriageAgent.urgent).probability >= 0.7
      )
    }
```

A `Judgment` has a serializer, so it can be a reply, cross between services, and be kept in an entity's
or a workflow's state. Its stored form holds only the declared ids and keys and the numbers.

### What a judgment effect does not do

- **It reads no session history and writes no message.** A judgment is asked of the state the handler
  gives it and nothing else. To judge the recent conversation, put it in the state.
- **It is one effect.** A handler returns a judgment or an interaction with a text model, not both. "Judge,
  then call the text model only if needed" is two calls made by an endpoint or a workflow step — or, when
  the question is only whether to refuse, a judged guardrail.
- **It waits its turn.** A judgment handler is a handler of its agent, so it takes its place in its
  session's order like any other: one asked in a session that is in the middle of a long conversation turn
  waits for that turn. Ask it in a session of its own when it must not queue.

## Configuring the provider

The service's judgment provider is configured on the agent runtime, beside its text model:

```scala
Ankka.service
  .register(TriageAgent.descriptor)
  .registerAll(AgentRuntime.descriptors)
  .withExtension(
    AgentRuntime
      .withDefaultModel(AnthropicProvider.fromEnv())
      .withJudgments(JevProvider.fromEnv())
  )
  .start()
```

`withJudgments(provider, timeout)` bounds every judgment by the timeout, five seconds unless given
another; the provider's retries happen inside it. A service that only judges needs no text model:
`AgentRuntime().withJudgments(provider)`. A handler can name a provider of its own with
`effects.judgment.provider(p)`. A judgment asked with no provider anywhere fails with `Internal`, saying
what to configure.

`JevProvider.fromEnv()` reads the key from `TYPESAFE_API_KEY`, and fails at startup when it is not set.
`TYPESAFE_BASE_URL` sends requests somewhere other than TypeSafe's API — a gateway or a proxy that
passes the provider's requests through unchanged. `JevProvider.withApiKey(key, model, baseUrl)` takes
all three directly.

Its default model is a version, `jev-1.13.0`, not the `jev-latest` alias. Answers can change between
versions, and thresholds are tuned against one, so upgrading is a deliberate change to the model named.
Every judgment reports the version that answered, in `judgment.model`.

The adapter retries what the provider's own clients treat as transient — rate limiting, overload, a
server error, a request timeout, a connection that could not be made — waiting as long as the provider
asks, and never past the judgment's timeout. A refused key and an invalid request fail at once.

### What a judgment costs

Every judgment reports the tokens its provider counted, in `judgment.usage`, and the platform records them
against the session it was asked in — a judgment effect's and every judged guardrail's, whether the
interaction was allowed or refused. A session's `history` reports them as `judgmentUsage`, a figure of its
own beside the text model's `usage`: the two are priced far apart, and one figure summing both would mean
nothing. An autonomous agent's judgments are recorded on the task's session. Recording tokens is best
effort where no message is written with them: a failure to record is logged and never changes what the
caller is told.

## Judged guardrails

A judged guardrail asks questions about the text going into a model, or coming out of it, and refuses
the interaction when an answer meets a rule:

```scala
val safety: Guardrail =
  Guardrail
    .judged("safety")
    .onInput(Refuse.ifYes(Safety.overridesInstructions, atLeast = 0.7))
    .onOutput(
      Refuse.ifYes(Safety.givesMedicalAdvice, atLeast = 0.7),
      Refuse.ifScore(Safety.hostility, atLeast = 2),
      Refuse.ifChosen(Safety.topic, minConfidence = 0.6)("legal", "medical")
    )
```

`Refuse.ifYes(question, atLeast)` refuses at or above a probability; `Refuse.ifChosen(question,
minConfidence)(options*)` when one of the named options is chosen with at least that confidence;
`Refuse.ifScore(question, atLeast)` at or above a point on the scale; `Refuse.when(question)(f)` when your
own function of the typed answer says so. Each rule is checked against its question where it is
declared.

It goes in the same list as the deterministic guardrails:

```scala
/** A support conversation, checked going in and coming out. */
def guarded(message: String): Effect[String] =
  effects
    .systemMessage("You answer support questions.")
    .userMessage(message)
    .guardrails(Guardrail.maxInputLength(200), TriageAgent.safety)
    .thenReply()
```

- **One judgment per text checked**, carrying all of that direction's questions. The first rule met, in
  declaration order, is the one a refusal names.
- **Guardrails run in declaration order and stop at the first refusal**, so put the free checks first: a
  message the length check refuses costs no judgment.
- **A refusal has the consequences any guardrail's refusal has.** On the way in, no model is called and
  nothing is written to memory; on the way out, the reply is not remembered. The caller gets `Forbidden`,
  `guardrail 'safety': question 'overrides-instructions'`. The message names the question and not the
  score, and never repeats the text.
- **An empty text is not checked**: a handler with no message, or a reply with no text.
- **A check that could not be made is not a refusal.** When the provider fails or times out, the
  interaction does not proceed, and the caller gets `Unavailable` (or `Timeout`),
  `guardrail 'safety' could not be checked: …`. A caller can therefore retry a check that did not happen
  and not one that refused.
- **On a streaming handler an output guardrail runs after the text was delivered**, as every output
  guardrail on a stream does: a refusal keeps the reply out of memory and cannot recall it.

On an autonomous agent's definition, a judged guardrail's input rules see a task's instructions before any
model call and its output rules see the completed result, as JSON. A check that cannot be made there is a
failed iteration: it is tried again after a pause, and the task fails only after the definition's
`maxConsecutiveFailures`.

A judged guardrail reads text its author may have written to defeat it. A System One model does not treat
the state as hostile, and a message argued for its own classification moves the answer. Use it beside
deterministic guardrails, not instead of them, and keep what must never happen out of the model's reach
altogether.

## Choosing thresholds

A choice's `confidence` and a score's are measures of how concentrated the probabilities are — all of it
on one answer is 1 — not probabilities themselves. A yes/no has no confidence: its probability is the
model's belief. The probability of "is urgent" and of "is not urgent" are two answers that need not sum
to one.

Scale a threshold to the cost of acting on a wrong answer: a low bar for showing a balance, a high one for
approving a transfer, and a middle band that goes to a person — which is application code over the
judgment, since a guardrail can only allow or refuse. Tune thresholds against real examples on the pinned
version, and read `judgment.model` when a threshold that worked stops working.

## What a System One model does badly

- **Counting and arithmetic.** Count and calculate in code, and ask the model about the result.
- **Dates.** It reads dates as text. Compare them in code; ask it to pick a date's parts from a choice.
- **Negation and indirection.** It reads instructions literally. Ask "the message is urgent", not "the
  message is not unurgent", and split a question about a property of a property into two.
- **Irrelevant state.** Accuracy falls as unrelated content is added. Send the fields the questions are
  about.
- **Contradictory instructions.** A yes/no whose "yes" describes "no" answers worse. Keep instructions and
  descriptions in agreement.
- **Languages other than English**, which the provider supports less well; test on your own content.

## Testing

`TestJudgmentProvider` answers from a script, offline and with no key, as `TestModelProvider` does for
text. Give it to the test service through the agent runtime:

```scala
val model = TestModelProvider()
val judge = TestJudgmentProvider()

val testKit = AnkkaTestKit.start(
  Seq(TriageAgent.descriptor) ++ AgentRuntime.descriptors,
  Seq(AgentRuntime.withDefaultModel(model).withJudgments(judge))
)
```

`expect(answers*)` queues one judgment's answers, taken in order. `always(answers*)` gives standing
answers by question, used every time the question is asked without consuming the queue — which is what a
guardrail asked on every request needs:

```scala
// Standing answers for the guardrail, asked on every request.
judge.always(
  Answers.yesNo(TriageAgent.Safety.overridesInstructions, 0.02),
  Answers.yesNo(TriageAgent.Safety.givesMedicalAdvice, 0.01),
  Answers.score(TriageAgent.Safety.hostility, 0),
  Answers.choice(TriageAgent.Safety.topic, "general")
)
// One scripted reply per turn, for the text model.
model.expectText("Your order ships tomorrow.").expectText("It left the warehouse today.")

val first  = agent("s-both").call(TriageAgent.guarded).invoke("Where is my order?")
val second = agent("s-both").call(TriageAgent.guarded).invoke("And now?")

assertEquals((first, second), ("Your order ships tomorrow.", "It left the warehouse today."))
assertEquals(judge.callCount, 4) // an input and an output check per call
assertEquals(model.callCount, 2)
```

`Answers.choice`, `Answers.score` and `Answers.yesNo` script an answer by its value and give it
probabilities and a confidence consistent with it; `Answers.choiceWith` and `Answers.scoreWith` take the
full probabilities. Each is checked against its question as it is written. `failNext(message, timedOut)`
makes the next judgment fail as a provider's outage would, to test that path on purpose. `requests`,
`lastRequest` and `callCount` show what was asked.

The script fails loudly: a question it has no answer for, or an answer for a question that was not asked,
fails the call naming the question. A test that adds a question and forgets its answer says which
question. The judgment script and the text model's script are separate, so neither consumes the other's.

## What to read next

- [Agents](agents.md) for handlers, guardrails and the text model.
- [Agents and sessions](../concepts/agents.md) for what a judgment is beside a model interaction.
- [Designing with agents](../concepts/designing-agents.md) for when a decision is a judgment.
- [Testing](testing.md) for the scripted providers and the whole-service test kit.
