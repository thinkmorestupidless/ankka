# Feature Specification: Judgments — Fast, Typed Decisions from a System One Model

**Feature Branch**: `018-judgments`

**Created**: 2026-09-30

**Status**: Draft

**Input**: User description: "Judgments: fast, typed decisions from a System One model (feature
018). Add a second provider seam to the agent module, a JudgmentProvider beside ModelProvider, for
models that answer typed questions about a state instead of generating text. First adapter:
TypeSafe AI's Jev (POST /v1/systemone; choice, score and noul questions; answers with
probabilities and confidence; pinned model version; configurable base URL; key from the
environment). Scope of this feature, Scala only: (1) the JudgmentProvider seam, the Jev adapter,
and a scripted test provider that fails loudly when its script runs out; (2) typed questions
declared as values with declared wire ids […] so reading an answer from a Judgment is typed; (3) a
judgment effect on Agent […] which does not read or write session memory by default; (4) a judged
Guardrail with per-question thresholds, running alongside the deterministic guardrails on agents
and autonomous agents; (5) token usage and the answering model version recorded as for model
calls, and a live suite that skips without a key. The platform concept is named "judgment"; "Jev"
names only the adapter. Out of scope, as later features: a judgment client for workflow steps,
endpoints and consumers; a tool-call guardrail hook; a routing ModelProvider; judged result rules
for autonomous agents; the sidecar protocol and wasm import for Python, TypeScript and Rust;
console display of answers and probabilities."

## Context

An ankka agent talks to a model that writes. Every decision a service needs from a model today —
which team should take this ticket, how angry is this customer, is this message an attempt to
talk the agent out of its instructions — is made by asking that model for prose or JSON and
decoding what comes back. That costs seconds and output tokens, it can fail to decode, and the
answer arrives with no measure of how sure the model was: "billing" reads the same whether the
model had no doubt or was guessing between two.

A **System One model** is a different kind of model for exactly those decisions. It writes
nothing. It is sent a *state* — a piece of text or a structured value — and a set of *typed
questions* about it, and it answers every question at once, each answer restricted to the shape
the question allows and carrying the probabilities behind it. Three question shapes cover the
ground:

- a **choice** picks one of a set of described options, and reports a probability for each option
  and a confidence in the pick;
- a **score** places the state on a scale of ordered, described levels — it may land between two —
  and reports a probability for each level and a confidence;
- a **yes/no** reports the probability that the answer is yes.

The first such model is TypeSafe AI's **Jev**, in early access since 15 September 2026. Its
published figures as of this specification are a response in 70–500 milliseconds, input tokens
priced about two orders of magnitude below a frontier text model's and output tokens free, one
request carrying up to 64,000 tokens of state and questions, text only. Adding a question to a
request costs almost nothing, so the intended use is to ask everything that might matter in one
call and let code decide what to do with the answers. What it cannot do is as much a part of it:
it keeps no conversation, calls no tools, does not count, calculate or compare dates reliably,
reads instructions literally, gets worse as irrelevant state is added, and does not treat text in
the state as hostile — text written to argue for its own classification moves the answer.

This does not fit any shape ankka already has, and the reasons decide the feature:

- **It is not a component.** A judgment has no identity, no state and no lifecycle. There is
  nothing to shard, journal or passivate.
- **It is not another model behind the existing seam.** The model seam takes a conversation and
  tools and returns text and tool calls; a System One model takes and returns neither. Put behind
  that seam, the agent loop would ask it for a turn and receive nothing.
- **It is not a kind of agent.** An agent is a conversation: one writer per session, a memory, a
  tool loop. A judgment uses none of them.

So a judgment is a second thing a service can ask a model for, with its own provider seam beside
the existing one, and this feature delivers it in three places:

- A **judgment effect** on an agent. A handler describes a state and the questions to ask of it,
  and the reply is the judgment — or a value the handler computes from it. Like every ankka
  effect it is inert: building it asks nothing. Akka has announced the same effect for its own
  SDK, not yet released; ankka's follows its shape where that shape is published.
- A **judged guardrail**. Guardrails today are deterministic — a length, a pattern. A judged
  guardrail asks questions about the text going into or coming out of a model and refuses the
  interaction when an answer crosses a threshold the developer set. It sits in the same list as
  the deterministic ones, on request agents and autonomous agents alike.
- A **scripted judgment provider**, so everything above is tested offline, with no key and no
  network, by the same rule the scripted model follows: a script that runs out is a failed test,
  never a default answer.

Questions are declared as values with wire ids of their own, as handlers are declared on
companions. That is what makes reading an answer typed — the question a developer asked is the
key they read the answer with — and it keeps a renamed Scala identifier from changing what is
sent to the provider or what a stored judgment means.

What this feature is *not*: a way to ask for a judgment from a workflow step, an endpoint or a
consumer; a check on the tool calls a model asks for; a model router; or anything for Python,
TypeScript or Rust services. Each is a feature of its own that the seam here is built to carry.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A handler asks typed questions and reads typed answers (Priority: P1)

A developer building a support service declares three questions beside a triage agent: which team
should take a ticket (a choice over the service's own `Team` enumeration), how frustrated the
customer is (a score over four described levels), and whether the customer asks for a refund (a
yes/no). The agent's `triage` handler names the ticket as the state and the three questions, and
replies. An endpoint calls it and reads each answer through the question that asked it: the team
as a `Team`, with how confident the model was; the frustration as a number on the scale; the
refund as a probability. The endpoint routes a confident answer to a queue and an unsure one to a
person.

**Why this priority**: This is the capability. Every other story is a way of configuring, guarding
or testing it.

**Independent Test**: With the scripted judgment provider: script one answer per question, call
the handler through a running test service, and assert each answer read through its question has
the scripted value and type, that the provider saw exactly one request carrying the state and the
three questions, and that the session's conversation is unchanged.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- added `features/judgments/asking.feature`: a judgment asks every question of the judged content in one request
- added `features/judgments/asking.feature`: a choice is read as an option of the type it was declared over
- added `features/judgments/asking.feature`: a score is read as a place among its levels
- added `features/judgments/asking.feature`: a yes or no question is read as the probability of yes
- added `features/judgments/asking.feature`: a judgment neither reads nor changes the session's conversation
- added `features/judgments/asking.feature`: a handler that replies with a value computed from its judgment gives only that value
- added `features/judgments/asking.feature`: a judgment with no judgment provider to ask fails saying what to configure
- added `features/judgments/asking.feature`: a handler that names a judgment provider is answered by it
- added `features/judgments/asking.feature`: a judgment whose provider fails leaves the conversation as it was
- added `features/judgments/asking.feature`: an answer read through a question that was not asked is not invented

---

### User Story 2 - A service is pointed at Jev (Priority: P1)

An operator has a TypeSafe API key. They set it in the service's environment, and the developer
configures the Jev adapter as the service's judgment provider, naming the model version the
thresholds were tuned against. The triage agent now answers from Jev. When the organisation puts
a gateway in front of its model traffic, the operator changes the adapter's base address and
nothing else.

**Why this priority**: A seam with no real provider behind it delivers nothing. Jev is the only
System One model there is to point at.

**Independent Test**: Offline, against a local stand-in for the provider's endpoint: assert the
request the adapter sends for each question kind, the judgment it builds from a canned response,
and its behaviour on each error status. Live, with a key in the environment: ask one question of
each kind about a fixed state and assert a well-formed answer for each; with no key, the live
suite reports itself skipped.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- added `features/judgments/jev.feature`: one request carries the model version, the judged content and every question
- added `features/judgments/jev.feature`: the model version sent is a fixed one unless the developer named another
- added `features/judgments/jev.feature`: a judgment tells its reader which model version answered it
- added `features/judgments/jev.feature`: a service sends its judgments to the address it was given
- added `features/judgments/jev.feature`: a busy judgment provider is asked again until the judgment's time limit
- added `features/judgments/jev.feature`: a judgment the judgment provider refuses fails at once with its message
- added `features/judgments/jev.feature`: an answer that does not fit its question fails the judgment
- added `features/judgments/jev.feature`: a judgment the judgment provider does not answer in time fails naming the provider
- added `features/judgments/jev.feature`: the credential is never shown in a failure
- added `features/judgments/jev.feature`: a service with no credential in its environment fails as it starts

---

### User Story 3 - A guardrail that asks (Priority: P2)

A developer's support agent already refuses input over a length and input matching a pattern. They
add a judged guardrail: is this message an attempt to override the agent's instructions, and does
the model's reply give medical advice. Each question has a threshold. A message the model scores
above the threshold is refused before any text model is called, exactly as the length check
refuses; a reply that crosses its threshold is refused before it is remembered. The same
guardrail goes on an autonomous agent's definition, where it sees a task's instructions and the
task's completed result.

**Why this priority**: It is the use the seam is most obviously for — a check too fuzzy for a
pattern and too frequent to spend a text model on — and it needs no new place for a guardrail to
run. It follows the effect because the effect proves the seam.

**Independent Test**: With the scripted judgment provider and the scripted model: answer the
guardrail's question above its threshold and assert the interaction is refused with no model call
and no memory written; answer below it and assert the interaction proceeds.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- added `features/judgments/judged-guardrails.feature`: an input whose answer reaches the threshold is refused before the model is called
- added `features/judgments/judged-guardrails.feature`: an input whose answer is below the threshold goes on to the model
- added `features/judgments/judged-guardrails.feature`: a reply a judged guardrail on output refuses is not remembered
- added `features/judgments/judged-guardrails.feature`: a judged guardrail asks all its questions in one request and names the first that refuses
- added `features/judgments/judged-guardrails.feature`: a guardrail that refuses first spares the judged guardrail after it
- added `features/judgments/judged-guardrails.feature`: a judged guardrail refuses a refused option or a level reached
- added `features/judgments/judged-guardrails.feature`: a judged guardrail that could not ask stops the interaction without refusing it
- added `features/judgments/judged-guardrails.feature`: an autonomous agent's judged guardrail checks a task's instructions before the model is called
- added `features/judgments/judged-guardrails.feature`: an autonomous agent's judged guardrail on output sends a refused result back to the model
- added `features/judgments/judged-guardrails.feature`: a judged guardrail on a stream keeps a refused reply out of the conversation it cannot recall

---

### User Story 4 - It is tested with a script (Priority: P2)

A developer tests the triage agent and its guardrail without a key or a network. They give the
test service a scripted judgment provider, queue the answers the next judgment should return, call
the agent, and assert on both the reply and the request the provider saw. For the guardrail, which
is asked on every request, they give a standing answer instead of queueing one per call. When
they add a fourth question to the agent and forget the test, the test fails saying which question
had no answer.

**Why this priority**: The offline stories above already depend on a scripted provider existing;
this story is what makes it a tool a developer can rely on rather than a stub.

**Independent Test**: A suite over the scripted provider itself: each acceptance scenario below,
with no service running where none is needed.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- added `features/judgments/testing.feature`: queued answers are given in order and every request is kept for the test
- added `features/judgments/testing.feature`: a script with no answer for a question fails naming the question
- added `features/judgments/testing.feature`: an answer that does not fit its question fails the test naming what does not fit
- added `features/judgments/testing.feature`: a standing answer is given to every judgment that asks its question
- added `features/judgments/testing.feature`: an agent's judgments and its model calls draw on their own scripts
- added `features/judgments/testing.feature`: an answer scripted in part reads as a whole answer that agrees with it

---

### User Story 5 - What a judgment cost, and who answered (Priority: P3)

An operator reading a session's token usage sees what the session spent on judgments beside what
it spent on the text model — separately, because the two are priced two orders of magnitude apart
and a sum of them would mean nothing. A developer debugging a threshold that stopped working
reads, on the judgment itself, which model version answered.

**Why this priority**: Nothing else in the feature depends on it, but a platform that records
every model call's tokens and silently omits these would be reporting a total that is wrong.

**Independent Test**: Run judgments in a session through the effect and through a guardrail,
including one the guardrail refuses, and read the session's usage: judgment tokens equal the sum
the scripted provider reported, and the text model's tokens are unchanged by them.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- added `features/judgments/usage.feature`: an answered judgment says which model version answered it and what it cost
- added `features/judgments/usage.feature`: a session reports its judgments' usage apart from its model's
- added `features/judgments/usage.feature`: the usage of a judged guardrail that refused is still counted
- added `features/judgments/usage.feature`: the usage of an autonomous agent's judged guardrail is counted on its task's session
- added `features/judgments/usage.feature`: a session recorded before judgments reads as it did

---

### User Story 6 - Judgments are documented as a property of the platform (Priority: P3)

A developer — or a coding agent that retrieved one page alone — learns from the documentation
what a judgment is, when to use one instead of a text model, how to declare questions, ask them
from an agent, guard with them and test them, and what a System One model gets wrong. The
limitations page says where judgments are not yet available.

**Why this priority**: The feature is usable without it by someone reading the source, and by
nobody else.

**Independent Test**: The documentation build passes; the guide's samples are regions of tested
code; a reader following only the guide produces the triage agent of User Story 1 with a passing
test.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- added `features/judgments/documentation.feature`: the documentation guides building with judgments from tested code
- added `features/judgments/documentation.feature`: the documentation says what a judged guardrail can and cannot be trusted with
- added `features/judgments/documentation.feature`: the documentation says why the model version is fixed
- added `features/judgments/documentation.feature`: the documentation lists every setting of the judgment provider
- added `features/judgments/documentation.feature`: the documentation says where judgments are not available and how they differ from Akka's
- added `features/judgments/documentation.feature`: every page about judgments can be found

---

### Edge Cases

- **A judgment handler and a conversation handler on one session.** A judgment handler is a
  handler of its agent like any other, so it takes its turn in its session's order; a session in
  the middle of a long tool loop makes a judgment in that session wait. A caller that wants a
  judgment unqueued behind a conversation asks it in a session of its own.
- **A handler cannot judge and then talk to the text model in one reply.** A handler returns one
  effect. "Judge, then decide whether to call the text model" is two calls made by the caller —
  an endpoint or a workflow step — or a judged input guardrail when the decision is only whether
  to refuse.
- **State larger than the provider accepts.** The provider refuses it; the judgment fails with
  the provider's message. Under a judged guardrail that is a check that could not be made, so the
  interaction does not proceed — a deterministic length guardrail declared first is how a
  developer turns that into an ordinary refusal that costs nothing.
- **A state that is not text.** A structured state is sent as structured data. Binary content —
  an image a tool returned — cannot be part of a state: a state is text or a value the platform
  can encode, and there is no way to offer anything else.
- **An empty text under a guardrail.** A handler with no user message, or a model reply with no
  text, is not sent to the provider; there is nothing to judge and the guardrail allows it.
- **Two questions with one id in a request, or a request with no questions.** Refused before any
  provider call, naming the id.
- **A choice declared with fewer than two options or more than the provider-independent maximum
  of 255; a score declared with fewer than two or more than ten levels; two options with one
  key.** Refused where the question is declared, so a service with such a question fails at
  startup.
- **An enumeration that gains a case after a judgment was stored.** A stored judgment still reads:
  its chosen option is one the question still declares. One whose chosen option has been
  *removed* from the question fails to read, naming the option, rather than reading as another.
- **A yes/no asked both ways.** The probability of "is urgent" and of "is not urgent" are two
  independent answers and need not sum to one; the platform does not reconcile them.
- **A confidence is not a probability.** A choice's confidence is derived from how concentrated
  its probabilities are and is reported as the provider reports it; the platform neither
  recomputes it nor invents one for a yes/no, which has none.
- **A guardrail refusal on a streamed reply.** The text has been delivered; the refusal keeps it
  out of memory and cannot recall it — the rule every output guardrail on a stream already has.
- **The provider is slow but answering.** Retries on rate limiting and overload happen within one
  judgment's timeout, never beyond it; a guardrail therefore delays an interaction by at most
  that timeout before failing it.
- **A service with no judgment provider and no judgments.** Nothing changes: it starts, runs and
  tests exactly as before this feature.
- **An autonomous agent whose definition has a judged guardrail but whose service has no
  judgment provider.** Treated as a definition with no model is: the service starts, with a
  warning naming the agent and what to configure, and every task given to that agent fails at its
  start saying the same. A missing provider refuses the work that needs it, not the service.
- **A Python, TypeScript or Rust service.** It cannot declare a judgment or a judged guardrail;
  nothing in its SDK names one.

## Requirements *(mandatory)*

### Functional Requirements

**Questions**

- **FR-001**: A developer MUST be able to declare a question as a value, of one of three kinds: a
  choice among described options, a score over ordered described levels, or a yes/no.
- **FR-002**: Every question MUST have an id declared by the developer, which is its wire name: it
  is what is sent to the provider and what a judgment's answer is keyed by, and it MUST NOT be
  derived from any Scala identifier.
- **FR-003**: Every question MUST carry instructions — the question itself, as the model reads it.
- **FR-004**: A choice MUST be declarable over an enumerated type of the developer's, with one
  option per case; each option has a declared key, which is its wire name, and a description the
  model reads. A choice MUST also be declarable over plain keys where no such type exists.
- **FR-005**: A score MUST be declared with two to ten levels, in order, each a description the
  model reads.
- **FR-006**: A yes/no MAY carry a description of what yes means and what no means.
- **FR-007**: A question that is malformed — a choice with fewer than two options, more than 255,
  or two options with one key; a score with fewer than two or more than ten levels; an empty id
  or empty instructions — MUST be refused where it is declared, with an error naming the question
  and the problem.

**The judgment**

- **FR-008**: A judgment MUST hold exactly one answer for each question that was asked, the model
  version that answered, and the tokens the provider reported.
- **FR-009**: An answer MUST be read from a judgment through the question that asked it, and the
  value read MUST have that question's type: for a choice, the chosen option as the declared
  type, a probability for every option and a confidence; for a score, a number on the scale whose
  whole values are the levels counted from the first, a probability for every level and a
  confidence; for a yes/no, the probability of yes.
- **FR-010**: Reading a judgment with a question it holds no answer for, or whose stored answer is
  of another kind or names an option the question does not declare, MUST fail with an error
  naming the question; it MUST NOT return a default.
- **FR-011**: A judgment MUST be a value the platform can encode and decode, so that it can be an
  agent handler's reply, cross between services, and be kept in a workflow's or an entity's
  state; its encoded form MUST identify questions and options by their declared wire names only.
- **FR-012**: A developer MUST be able to read every answer's full probabilities, so that a
  measure of certainty other than the reported confidence can be computed in code.

**The provider seam**

- **FR-013**: The platform MUST define a judgment provider as a seam separate from the text model
  seam: given a state and one or more questions, it answers with a judgment or fails. It
  identifies itself by a name and the model it is configured to ask.
- **FR-014**: A state MUST be either text or a structured value the platform can encode; a
  provider MUST receive a structured state as structured data, not as text that happens to
  contain it.
- **FR-015**: A request with no questions, or with two questions sharing an id, MUST be refused
  before any provider is called.
- **FR-016**: A provider's failure MUST be reported as a failure naming the provider and the
  cause, distinct from every answer a judgment can hold.
- **FR-017**: A service MUST be able to configure one judgment provider as its default, in the
  same place its default text model is configured; configuring none MUST leave every existing
  behaviour unchanged.
- **FR-018**: Every judgment MUST have a timeout, with a default, configurable for the service.

**The Jev adapter**

- **FR-019**: The platform MUST ship a judgment provider for TypeSafe AI's Jev that sends one
  request per judgment to the provider's evaluation endpoint, carrying the model, the state and
  every question in the provider's form, and builds the judgment from the response.
- **FR-020**: The adapter MUST be constructible from the environment, reading the key from the
  variable the provider's own tools read, and from an explicit key; constructing it from an
  environment with no key MUST fail naming the variable.
- **FR-021**: The adapter's default model MUST be a specific version, never a moving alias; a
  developer MUST be able to name another version or an alias explicitly.
- **FR-022**: The adapter's base address MUST be configurable, so that requests can be sent
  through a gateway, a proxy or a compatible service.
- **FR-023**: On a rate-limit or overload answer, and on any other failure the provider's own
  clients treat as transient — a request timeout, a server error, a connection that could not be
  made — the adapter MUST retry with increasing waits, honouring the provider's stated wait when
  one is given, and MUST stop when the judgment's timeout would be exceeded. It MUST NOT retry a
  refused key or an invalid request.
- **FR-024**: The platform MUST verify every provider's answer against the request — an answer
  for every question asked, of the question's kind, naming only options the question offered,
  with probabilities between 0 and 1 — and fail the judgment, naming the question, when it does
  not hold. The adapter MUST fail the judgment when the response cannot be read at all.
- **FR-025**: The key MUST NOT appear in any log line, error message, judgment or recorded usage.
- **FR-026**: The adapter MUST report, on the judgment, the model version the provider says
  answered.

**The judgment effect**

- **FR-027**: An agent handler MUST be able to return an effect that describes a judgment: a
  state, one or more questions, and optionally the provider to ask. Building the effect MUST ask
  nothing.
- **FR-028**: The effect MUST reply either with the judgment or with a value computed from the
  judgment by a function the handler supplies; a failure in that function is the handler's
  failure.
- **FR-029**: Running a judgment effect MUST NOT read the session's conversation, send any of it
  to the provider, or write any message to it.
- **FR-030**: A judgment effect with no provider named, in a service with no default, MUST fail
  with an error saying what to configure, before any provider is called.
- **FR-031**: A judgment handler MUST be registered, addressed, called and refused like any other
  handler of its agent — by its wire name, in its session's order, through the same client.
- **FR-032**: A failed judgment MUST reach the handler's caller as an error and MUST be recorded
  as a fault, not a refusal.

**The judged guardrail**

- **FR-033**: A developer MUST be able to declare a guardrail that, for input, for output or for
  both, asks one or more questions about the text being checked and applies to each answer a rule
  that decides whether to refuse.
- **FR-034**: The platform MUST provide the common rules: for a yes/no, refuse at or above a
  probability; for a choice, refuse when the chosen option is among those named, optionally only
  at or above a confidence; for a score, refuse at or above a level. A developer MUST also be
  able to supply a rule of their own over the typed answer.
- **FR-035**: A judged guardrail MUST make one provider request per text it checks, carrying all
  of that direction's questions, and MUST refuse with the first rule met in declaration order;
  the refusal MUST name the guardrail and the question and MUST NOT include the text checked.
- **FR-036**: A judged guardrail MUST be usable wherever a guardrail is accepted today — on a
  request agent's interaction and on an autonomous agent's definition — in one list with
  deterministic guardrails, run in declaration order, stopping at the first refusal.
- **FR-037**: A judged guardrail's refusal MUST have exactly the consequences a deterministic
  guardrail's has in the same place: before a text model call and with no memory written on
  input; before memory is written on output; for an autonomous agent, as that agent treats a
  refused instruction and a refused result.
- **FR-038**: When the provider fails or times out, a judged guardrail MUST fail closed: the
  interaction does not proceed, and the error MUST be distinguishable from a refusal — it says
  the check could not be made and is recorded as a fault. On an autonomous agent it MUST be
  treated as a failed iteration is, not as a refused instruction or a rejected result.
- **FR-039**: A judged guardrail MUST NOT ask the provider about an empty text.
- **FR-040**: A judged guardrail MUST use the service's default judgment provider unless it is
  given one. Where neither exists, the work that needs it MUST be refused with an error saying
  what to configure — a request agent's interaction when it is made, an autonomous agent's task
  when it starts — and the service MUST warn at startup for an autonomous agent's definition in
  that state, as it does for one with no model.

**Testing**

- **FR-041**: The platform MUST ship a scripted judgment provider that answers from queued answers
  in order and from standing answers per question, and records every request it receives.
- **FR-042**: The scripted provider MUST fail loudly — failing the test, naming the questions —
  when asked a question it has neither a queued nor a standing answer for.
- **FR-043**: The scripted provider MUST refuse an answer that does not fit its question: an
  unknown question, an option not offered, a score off the scale, a probability outside 0 to 1.
- **FR-044**: A test MUST be able to script an answer by its value alone — the chosen option, the
  score, the yes probability — and receive probabilities and a confidence consistent with it, or
  script the full probabilities.
- **FR-045**: The scripted judgment provider and the scripted text model MUST be independent:
  neither consumes the other's script.
- **FR-046**: The whole-service test kit MUST accept a judgment provider for the service under
  test as it accepts a text model.
- **FR-047**: A suite that exercises the real provider MUST run only when a key is present in the
  environment and MUST report itself skipped otherwise; every other test of this feature MUST run
  offline with no key.

**Usage**

- **FR-048**: The tokens a judgment spent MUST be recorded against the session it was made in,
  whether it was made by a handler's effect or by a judged guardrail, and whether or not the
  interaction it belonged to was refused. Where no message is written with them, a failure to
  record them MUST be logged and MUST NOT change the outcome the caller sees.
- **FR-049**: Judgment tokens MUST be reported as a figure of their own wherever a session's
  tokens are reported, and MUST NOT be added to the text model's.
- **FR-050**: Session records written before this feature MUST read unchanged, with no judgment
  tokens.

**Documentation**

- **FR-051**: The documentation MUST gain a guide to building with judgments, and the agent
  concept pages MUST say what a judgment is, when to prefer one to a text model, and what a
  System One model does badly; every code sample MUST be included from tested code.
- **FR-052**: The configuration reference MUST describe every variable and setting this feature
  reads; the limitations page MUST state that judgments are available to Scala services' agents
  only and name what is not yet available; the page of differences from Akka MUST state where
  ankka's judgment API differs from Akka's announced one.

**Compatibility**

- **FR-053**: Existing agents, autonomous agents, guardrails, their tests and the samples MUST
  behave exactly as before; a service that configures no judgment provider and declares no
  judgment MUST be unaffected.

### Key Entities

- **Question**: What is asked of a state. It has a wire id, instructions and a kind. A choice
  adds options, each with a wire key and a description, optionally tied to the cases of an
  enumerated type; a score adds two to ten ordered level descriptions; a yes/no may describe its
  two answers. Declared once, as a value, and used both to ask and to read.
- **State**: What the questions are about: text, or a structured value. Never binary content.
- **Judgment**: The answers to one request — one per question, keyed by question id — with the
  model version that answered and the tokens spent. A value that can be a reply and can be
  stored.
- **Answer**: One question's result. A choice answer holds the chosen option's key, a probability
  per option and a confidence; a score answer holds a number on the scale, a probability per
  level and a confidence; a yes/no answer holds the probability of yes.
- **Judgment provider**: Something that turns a state and questions into a judgment: the Jev
  adapter, the scripted provider, or a developer's own. A service has at most one default.
- **Judged guardrail**: A guardrail made of questions and, for each, a rule over its answer that
  decides refusal; for input, output or both.
- **Judgment usage**: The tokens a session has spent on judgments, kept beside and apart from the
  tokens it has spent on the text model.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A developer can write the triage agent of User Story 1, a judged guardrail and a
  passing offline test for both from the documentation alone in under thirty minutes.
- **SC-002**: A judgment asked through a running service makes exactly one provider request
  however many questions it carries, and a judged guardrail makes exactly one per text it checks.
- **SC-003**: After any number of judgment handler calls in a session, the session's stored
  conversation is identical to what it was before them.
- **SC-004**: A request refused by a judged input guardrail results in zero text model calls and
  zero messages recorded, and its caller can tell it from a request whose check could not be
  made.
- **SC-005**: Every acceptance scenario in this specification has an automated test that runs
  offline with no key. The suites that need no database complete in under a minute together, and
  the whole-service suites in under three.
- **SC-006**: With a key present, the live suite gets a well-formed answer of each of the three
  kinds from the real provider; with none, it is reported as skipped, and the build's result is
  the same either way.
- **SC-007**: The key appears in no log output, error or recorded value produced by any test in
  the feature, including the tests of failure.
- **SC-008**: Every agent, autonomous agent and guardrail test that passed before this feature
  passes unchanged, and the samples run unchanged.
- **SC-009**: A session's reported usage shows judgment tokens equal to the sum the provider
  reported for every judgment made in it — including those of refused requests — and a text
  model figure unaffected by them.
- **SC-010**: The documentation build passes with every new page in the navigation and a skill,
  every included sample from tested code, and the configuration reference's prose covering every
  new variable.

## Assumptions

- **A guardrail fails closed, with no option to fail open.** A guardrail is a safety control; one
  that lets everything through while its provider is down is not one. A developer who wants the
  other behaviour writes a rule of their own around a judgment. This is the decision most worth
  revisiting if a judged guardrail is put in front of traffic that must not stop.
- **The effect replies; it does not continue.** A handler returns a judgment or a value computed
  from it. An effect that judges and then chooses a text model interaction in one handler would
  put the headline pattern — judge first, call the text model only when needed — inside an
  agent, but it is a new shape of effect with a streaming variant of its own, and Akka's
  announced effect does not have it. Callers sequence the two calls. It is listed under Out of
  Scope as the first candidate to follow.
- **A guardrail's outcome is binary.** The provider's own guidance describes three bands — act,
  review, pass. A guardrail can only allow or refuse; a middle band that routes to a person is
  application logic built on the judgment effect.
- **Judgment tokens are kept apart from text model tokens.** The platform reports tokens, not
  money, and a sum over two models priced a hundred times apart would make the one figure it does
  report meaningless.
- **Usage is recorded against the session; spans are unchanged.** Time spent waiting on a
  judgment provider appears on a trace as time waiting on a text model does. Showing a
  judgment's answers and probabilities anywhere is a later feature.
- **The key variable is the provider's own**, `TYPESAFE_API_KEY`, as the Anthropic adapter reads
  Anthropic's own. How a deployed service is given it is how it is given any secret variable
  today.
- **Defaults.** A judgment times out after five seconds. The adapter's default model is the
  version current when this feature is built, `jev-1.13.0` as of this specification. Retries
  start at a quarter of a second and double.
- **The adapter adds no library.** The provider publishes clients for Python and JavaScript only;
  the adapter is one request and one response over the HTTP client and JSON codec the platform
  already has.
- **The provider's limits are the provider's to enforce.** The platform checks the shape of a
  question where it is declared; it does not count tokens, and a request too large is refused by
  the provider and reported as its failure.
- **A choice's option keys are read by the model.** They are wire names and they are also part of
  what the model sees, so they should be meaningful words; the guide says so.
- **A judgment is asked of the state the handler gives it and nothing else.** No conversation, no
  context the platform adds. A developer who wants the recent conversation judged puts it in the
  state.
- **English first.** The provider states that English is its best-supported language; the
  documentation repeats that and the platform does nothing about it.
- **Provider facts are as published on 2026-09-30.** The model is in early access, its limits are
  stated to be subject to change, and direct access is by waiting list. The live suite and the
  adapter's default version are the two places that would notice a change.

## Out of Scope

- **An effect that judges and then continues into a text model interaction** in one handler.
- **A judgment client for workflow steps, endpoints, consumers and tools.** They cannot ask for a
  judgment directly; they call an agent's judgment handler.
- **A guardrail on tool calls** — checking what a model asks a tool to do before it runs. Guardrails
  see input and output text only.
- **A text model chosen by judgment** — routing a request to a cheaper or a stronger model.
- **Judged rules on an autonomous agent's task results**, beyond the guardrails a definition
  already takes.
- **Python, TypeScript and Rust.** The sidecar protocol, the WebAssembly interface and the three
  SDKs are unchanged.
- **Showing answers, probabilities or thresholds** in either console, and recording them anywhere
  the developer did not put them.
- **Other providers, self-hosted models and any model gateway.** The seam admits them; none ships.
- **Streaming, batching and calibration tooling.** The provider offers none, and the platform adds
  none.
