# Data Model: Judgments

**Feature**: `018-judgments` | **Date**: 2026-09-30

Nothing here is a new component and nothing adds DDL. Two things are stored: a `Judgment`, wherever
a developer puts one, and a session's judgment tokens, in the existing session memory entity.

## 1. Question

A value, declared once, used to ask and to read. Sealed; three kinds.

| Field | Choice | Score | Yes/no | Rule |
|---|---|---|---|---|
| `id` | ✓ | ✓ | ✓ | wire name; non-empty; declared, never derived |
| `instructions` | ✓ | ✓ | ✓ | non-empty; the question as the model reads it |
| `options` | ✓ | | | 2–255; each `(value: T, key, description)`; keys unique and non-empty; values unique |
| `levels` | | ✓ | | 2–10 descriptions, in order, first is level 0 |
| `yes`, `no` | | | optional | descriptions of the two answers |

A violation throws `IllegalArgumentException` naming the question's id and the rule, from the call
that builds the question.

Answer types, as read from a judgment:

| Question | Answer | Fields |
|---|---|---|
| `ChoiceQuestion[T]` | `ChoiceAnswer[T]` | `choice: T`, `probabilities: Map[T, Double]` (one per option), `confidence: Double` |
| `ScoreQuestion` | `ScoreAnswer` | `score: Double` in `[0, levels − 1]`, `probabilities: Vector[Double]` (one per level), `confidence: Double`; `level: Int` is `score` rounded |
| `YesNoQuestion` | `YesNoAnswer` | `probability: Double` of yes |

Every probability and confidence is in `[0, 1]`. A yes/no has no confidence.

## 2. Judgment request

| Field | Type | Rule |
|---|---|---|
| `state` | `JudgmentState`: `Text(String)` or `Structured(Json)` | a structured state reaches the provider as structured data |
| `questions` | `Vector[Question[?]]` | at least one; ids unique |
| `timeout` | `FiniteDuration` | positive; the whole budget for the judgment, retries included |

A violation throws `IllegalArgumentException` from construction, before any provider is called.

## 3. Judgment

| Field | Type | Notes |
|---|---|---|
| `model` | `String` | the version the provider reports answered |
| `answers` | `Map[String, Judgment.Stored]` | keyed by question id; exactly the ids asked |
| `usage` | `TokenUsage` | `inputTokens` and `outputTokens` as the provider reported; cache fields zero |

`Judgment.Stored`:

| Case | Fields |
|---|---|
| `Choice` | `key: String`, `probabilities: Map[String, Double]` by option key, `confidence: Double` |
| `Score` | `score: Double`, `probabilities: Vector[Double]` by level, `confidence: Double` |
| `YesNo` | `probability: Double` |

Encoded form (manifest `judgment`, the shared codec):

```json
{
  "model": "jev-1.13.0",
  "answers": {
    "route": {"type": "Choice", "key": "billing",
              "probabilities": {"billing": 0.88, "technical": 0.08, "sales": 0.04}, "confidence": 0.82},
    "frustration": {"type": "Score", "score": 1.43, "probabilities": [0.0, 0.57, 0.43], "confidence": 0.35},
    "refund": {"type": "YesNo", "probability": 0.95}
  },
  "usage": {"inputTokens": 296, "outputTokens": 20}
}
```

**Verification** (by `Judgments.ask`, for every provider): the answers' ids equal the request's;
each answer's case matches its question's kind; a choice's `key` and every probability key are
options the question offers, and every option has a probability; a score's probabilities number
its levels and its score lies on the scale; every number is in range. A failure is a
`JudgmentFailed` naming the question.

**Reading** `judgment(question)`: the stored answer under the question's id, converted by the
question. It throws, naming the question, when there is no such id, when the stored case is
another kind, or when a stored key is not one of the question's options. A question that has
*gained* an option since the judgment was stored still reads it; the new option's probability is
reported as 0.

## 4. Judged guardrail

| Field | Type | Rule |
|---|---|---|
| `name` | `String` | as for any guardrail; appears in a refusal |
| `input` | `Vector[Refusal[?]]` | rules for the text going in; may be empty |
| `output` | `Vector[Refusal[?]]` | rules for the text coming out; may be empty |
| `provider` | `Option[JudgmentProvider]` | else the service's default |

`Refusal[A]`: a `Question[A]` and a predicate over `A`. Within one direction the questions' ids
are unique (they form one request); a violation throws when the rules are added. A judged
guardrail with no rule in either direction is a mistake that would allow everything in silence,
so it is refused where it is used: on an autonomous agent's definition when the agent is
registered, and on a request agent's interaction with an `Internal` error naming the guardrail.

Outcomes of checking one text in one direction:

| Outcome | When | Consequence |
|---|---|---|
| allowed, nothing asked | the direction has no rules, or the text is empty | no provider call, no tokens |
| allowed | no rule's predicate is met | tokens recorded |
| refused | a rule is met; the first in declaration order is named | `guardrail '<name>': question '<id>'`; tokens recorded |
| could not be checked | no provider, the provider failed, or it timed out | `GuardrailCheckFailed`; tokens recorded if any were spent |

## 5. Session memory (changed)

`SessionHistory`:

| Field | Type | Change |
|---|---|---|
| `messages` | `Vector[SessionMessage]` | — |
| `usage` | `TokenUsage` | — (the text model's tokens) |
| `sizeInBytes` | `Int` | — |
| `judgmentUsage` | `TokenUsage` | **new**, default zero, absent from the encoded form when zero |

`SessionMemoryEvent`: **new** case `JudgmentUsageAdded(usage: TokenUsage)`. Fold:
`judgmentUsage += usage`; messages, `usage` and `sizeInBytes` unchanged. `Cleared` resets it with
everything else, as it resets `usage`.

`SessionMemoryEntity.Append`: **new** field `judgmentUsage: TokenUsage = TokenUsage.zero`. The
`append` command persists the message events as today and, when the field is non-zero, one
`JudgmentUsageAdded` after them; with no messages and a non-zero field it persists that event
alone; with neither it replies without persisting, as today.

Compatibility rules: a state or an `Append` encoded before this feature decodes with
`judgmentUsage` zero; a session in which no judgment was made encodes byte-for-byte as before;
every existing event's encoded form is unchanged.

Not changed: `TaskRecord.usage` and `InstanceRecord.usage` count the text model's tokens only. An
autonomous agent's judgment tokens are on the task's session, `task:<task id>`.

## 6. Runtime configuration

| Setting | Where | Default |
|---|---|---|
| default judgment provider | `AgentRuntime.withJudgments(provider, timeout)` | none |
| judgment timeout | the same call | 5 seconds |
| Jev model | `JevProvider.fromEnv(model = …)` / `withApiKey` | `jev-1.13.0` |
| Jev base address | `TYPESAFE_BASE_URL`, or the `baseUrl` argument | `https://api.typesafe.ai` |
| Jev key | `TYPESAFE_API_KEY`, or the `apiKey` argument | none; its absence fails construction |

## 7. Errors a caller sees

| Situation | Code | Message |
|---|---|---|
| judgment effect, no provider anywhere | `Internal` | names the agent and both ways to configure one |
| judgment effect, provider failed | `Unavailable` | `<provider>: <message>` |
| judgment effect, timed out | `Timeout` | `<provider> did not answer within <timeout>` |
| judgment effect, reply function threw | `Internal` | the exception's message |
| guardrail refused | `Forbidden` | `guardrail '<name>': question '<id>'` |
| guardrail could not be checked | `Unavailable` or `Timeout` | `guardrail '<name>' could not be checked: <provider>: <message>` |
| judged guardrail, no provider anywhere | `Internal` | names the guardrail and what to configure |

The key, the state and the text checked never appear in any of them.
