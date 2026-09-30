# Quickstart: validating judgments

Each scenario names the suite that automates it and, where one exists, the by-hand check. Docker is
required from §3 on. No k3s suite is touched by this feature; pass `-Dankka.cluster.tests=off` to
keep the rest of the tree fast. No key is needed for anything but §6.

## 0. First: pin what session memory stores today

```bash
sbt 'testkit/testOnly *SessionMemoryCompatibilitySuite'
```

Written and green **before** `SessionMemoryEntity` is changed. Expected: today's encoded state and
every existing event, captured as fixtures, decode and re-encode unchanged. After the change
(`data-model.md` §5) the same fixtures still pass, and new cases show a session with judgment
tokens carries `judgmentUsage` while one without is byte-for-byte what it was.

## 1. Offline: questions, the judgment, the script

```bash
sbt 'agent/testOnly *QuestionSuite *JudgmentCodecSuite *JudgmentsSuite *TestJudgmentProviderSuite'
```

Expected: every rule of `data-model.md` §1 refuses at construction, naming the question; a
judgment round-trips to the JSON in `data-model.md` §3 and each answer reads through its question
with its type; reading a question that was not asked, an answer of another kind, or a removed
option throws naming the question; the script's matching is `research.md` R11 — standing answers
do not consume the queue, a question with no answer and an answer with no question each throw
`JudgmentScriptFailed` naming it, and an answer scripted by value alone carries probabilities and
a confidence consistent with it; and the platform's own `ask` refuses a request with no questions
or a repeated id before any provider is called, times out as a fault, and fails an answer that
does not fit its question, naming the question.

## 2. Offline: the shared guardrail check

```bash
sbt 'agent/testOnly *GuardrailsSuite'
```

Expected, with the scripted provider and no runtime: a deterministic guardrail behaves as before;
a judged one makes one request per text with all of that direction's questions, names the first
rule met, asks nothing for an empty text or a direction without rules, stops at an earlier
guardrail's refusal without asking, adds what it spent to `Spent`, throws
`GuardrailCheckFailed` — not a refusal — when the provider fails, times out or is absent, and
refuses to run at all with no rule in either direction.

## 3. Offline: the adapter against a stand-in

```bash
sbt 'agent/testOnly *JevProviderSuite'
```

Expected, against the JDK's HTTP server on a loopback ephemeral port: the request for each
question kind and each state kind is `contracts/provider-wire.md`; the documented response
converts to the judgment in `data-model.md` §3; 401 and 422 fail at once with the body in the
message; 429 and 529 are retried, honouring `retry-after-ms` and `Retry-After`, and stop at the
deadline reporting the last failure; silence fails as a timeout; `fromEnv` with no key throws
naming `TYPESAFE_API_KEY`; and the key is in no message, no cause and no `toString` from any case.

## 4. Whole service: the effect, the guardrail, the tokens

```bash
sbt 'testkit/testOnly *JudgmentAgentSuite *JudgmentUnconfiguredSuite'
```

Expected, with `TestJudgmentProvider` and `TestModelProvider` on one `AnkkaTestKit`:

- **US1** — `triage` makes one provider request carrying the ticket and three questions and the
  caller reads a `Team`, a score and a probability; a session that held a conversation holds the
  same one afterwards and none of it was in the request; `routing` replies with the service's own
  type; no provider anywhere is `Internal` naming what to configure (`JudgmentUnconfiguredSuite`, a service started without one); a named provider is the one
  asked; `failNext` reaches the caller as `Unavailable`.
- **US3** — a judged input guardrail above its threshold is `Forbidden` naming the guardrail and
  the question, with `model.callCount == 0` and an unchanged conversation; below it the
  interaction proceeds; an output rule keeps the reply out of memory; a deterministic guardrail
  declared first refuses without a provider call; `failNext` is `Unavailable` with "could not be
  checked", and `failNext(…, timedOut = true)` is `Timeout`; on the streaming handler the refusal
  arrives after the text and the reply is not remembered.
- **US5** — with `reporting(TokenUsage(inputTokens = 100))`, the session's `judgmentUsage` is 100
  per judgment made — by the effect, by an allowing guardrail and by a refusing one — and `usage`
  is what the scripted model reported and nothing more.
- **US4 scenario 5** — neither script consumed the other's.

```bash
sbt 'testkit/testOnly *autonomous.JudgedGuardrailSuite'
```

Expected: a task whose instructions the guardrail refuses fails with the refusal and no model
call; a completed result it refuses goes back to the model as `result rejected — guardrail …` and
the task completes on the next accepted result; `failNext` at the start is recorded as a failed
iteration, the task starts on the retry, and `maxConsecutiveFailures` of them fail the task; an
exhausted script fails the task at once, in well under the backoff; a definition with a judged
guardrail and no provider anywhere starts with a warning and fails its task saying what to
configure; the task's session, `task:<id>`, carries the judgment tokens.

## 5. Nothing else moved

```bash
sbt -Dankka.cluster.tests=off agent/test testkit/test sidecar/test
sbt shoppingCart/test
sbt compile        # warning-free; -Wunused is on
sbt scalafmtCheckAll
```

Expected: every suite that passed before passes unchanged (SC-008) — `AgentSuite`,
`AgentStreamSuite`, `CompactionIntegrationSuite`, the `autonomous` suites, the sidecar's
conformance reference. `ResumePointSuite` changes by one constructor argument.

## 6. Live, with a key

```bash
TYPESAFE_API_KEY=… sbt 'agent/testOnly *JevProviderLiveSuite'
```

Expected: one request with a choice, a score and a yes/no about a fixed ticket returns a verified
judgment — a chosen option among those offered, a score on the scale, probabilities in range, a
versioned `model`, non-zero input tokens. A structured state is accepted. A deliberately invalid
key is a 401 that the adapter does not retry. Without the variable the suite reports itself
skipped and the build's result is unchanged.

This is the first run against the real endpoint. If a documented shape turns out wrong, the fix
is in `JevProvider`'s conversion and `contracts/provider-wire.md` together, and the offline
suite's canned response is replaced by what was actually returned.

## 7. By hand

```bash
docker compose up -d
ANTHROPIC_API_KEY=… sbt multiAgentPlanner/run     # unchanged: a service without judgments is unaffected
ankka local console                                                  # a session's view shows tokens as before
```

For judgments themselves the by-hand check is the fixture service the suite starts; there is no
sample change in this feature. In the local console, a session in which judgments were made shows
"judgment tokens" beside "tokens in" and "tokens out"; one in which none were made looks as it
did.

## 8. Documentation

```bash
just docs-sync && just docs
```

Expected: `docs/build/judgments.md` is in the navigation and the `ankka-agents` skill; its samples
are the regions of `TriageAgent.scala` and `JudgmentAgentSuite.scala`; `docs check` passes with no
positional reference and no feature number on any changed page; the rendered skill under
`marketplace/` and `ankka.g8/` is refreshed and committed; `TemplateSuite`'s escaping check is
satisfied by the sync.
