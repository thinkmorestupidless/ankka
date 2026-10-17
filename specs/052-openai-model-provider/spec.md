# Feature Specification: OpenAI as a Second Model Provider

**Feature Branch**: `052-openai-model-provider`

**Created**: 2026-10-17

**Status**: Draft

**Input**: User description: "i want to add OpenAI as a model provider alongside anthropic. A user
should be able to create agents with either model provider in the same service/system"

## Context

Everything an agent does sits above `ModelProvider` (`modules/agent/.../ModelProvider.scala`): a name,
a model name, `complete` and `stream`, over ankka's own `ModelRequest`, `ModelResponse` and
`ModelChunk`. The loop, tool dispatch, session memory, guardrails, approvals, compaction and token
accounting never see a vendor's API. `AnthropicProvider` is the one real adapter, using Anthropic's
Java SDK as transport only; `TestModelProvider` is the script. A Scala service hands the runtime a
default (`AgentRuntime.withDefaultModel(model)`) and any handler may name another with
`effects.model(provider)`; an autonomous agent's definition carries its own; a blueprint's worker
names one by a key registered with `BlueprintRegistry.models(...)`, where `default` is the runtime's.

A process-hosted service never calls a model: the sidecar holds the key and runs the loop. Its
models are configured by environment (`sidecar/.../Models.scala`): `ANTHROPIC_API_KEY` enables the
model named `anthropic`, `ANKKA_MODEL_NAME` says which Claude model, `ANKKA_MODEL_SCRIPT` enables
`scripted`, and a real model wins over a script as the default. A handler names one with
`with_model("anthropic")` in Python, `withModel` in TypeScript, `.model(...)` in Rust; an
autonomous agent's class names one in `model`. A plan naming a model the sidecar has not configured
is refused naming it; a sidecar with neither key nor script refuses agent calls naming both
variables. `PlatformVariables.RuntimeOnlyPrefixes` routes every `ANTHROPIC_` and `ANKKA_MODEL_`
variable to the platform's container alone, and a module's `config` import answers them absent, so
no developer's program ever holds the key.

What ankka's request carries is vendor-neutral by design: `ModelSettings` holds a model name,
temperature, max tokens, `effort` and `thinking`; the Anthropic adapter ignores temperature and sends
adaptive thinking and effort, because current Claude models reject sampling parameters
(`docs/reference/akka-divergences.md`, Model settings follow current models). `TokenUsage` counts
input, output, cache reads and cache writes. `StopReason` has `Refusal` as a successful response, so
a declined request is never mistaken for an answer.

So the seam exists and the documentation promises it ("Any other model is one class implementing
`ModelProvider`"), but ankka ships one vendor, and a developer who wants another writes and maintains
the adapter, tests it against the live API alone, and in Python, TypeScript or Rust cannot have one
at all: the sidecar's `Models` knows `anthropic` and `scripted` and nothing else. "Either provider in
the same service" is the gap: one agent on Claude and another on an OpenAI model, in one service, in
every language, with one session they can both continue, and one accounting of what each spent.

This feature makes these decisions.

- **OpenAI is a second shipped provider, the equal of Anthropic's.** `OpenAIProvider` in the agent
  module, using OpenAI's official Java SDK as transport only, as `AnthropicProvider` does, with
  `fromEnv()`, `withApiKey(key)` and a constructor taking a configured client. It reads
  `OPENAI_API_KEY`; the SDK's own `OPENAI_BASE_URL`, `OPENAI_ORG_ID` and `OPENAI_PROJECT_ID` are
  honoured, so a proxy or a compatible endpoint needs no code. Streaming is real, not the fallback.
- **The same request, honestly translated.** Tools, tool results, context, system message and the
  conversation cross as they do for Claude. `effort` maps onto OpenAI's reasoning effort as far as
  the model offers it; `thinking` off is the lowest effort the model offers; `temperature` is sent
  only to a model that accepts it. The documentation lists, per provider, what each setting does and
  what is ignored, as it does for Claude today. A refusal, a length stop and a tool stop each become
  the `StopReason` the loop already handles; usage is recorded in ankka's units, cached prompt tokens
  as cache reads.
- **A process-hosted service names providers, as it already does.** `OPENAI_API_KEY` on the sidecar
  enables the model named `openai`; `ANKKA_MODEL_OPENAI_NAME` names which OpenAI model, as
  `ANKKA_MODEL_NAME` names the Claude one and keeps doing so. When more than one real provider is
  configured, `ANKKA_MODEL_DEFAULT` says which is the default; without it, a call that names no model
  is refused naming the variable, and a call that names one proceeds. One real provider alone stays
  the default, so no existing descriptor changes meaning.
- **One session, any provider.** The session's messages are ankka's, so a session begun by an agent
  on one provider is continued by an agent on the other, tool calls and results included, and the
  session's usage is the sum across both.
- **The key is the platform's.** `OPENAI_` joins `ANTHROPIC_` among the prefixes routed to the
  platform's container only and withheld from a module's `config`, in the same lists, so the proof
  that no process sees a model key covers both vendors.
- **Tested without a key, and with one on purpose.** The adapter's translation, streaming, tool
  calls, refusals, usage and failures are proven against a stand-in speaking OpenAI's API on
  loopback, so an ordinary `sbt test` stays offline; `OpenAIProviderSuite` runs against the live API
  only when `OPENAI_API_KEY` is set, as the Anthropic one does.

What this feature is not: a judgment provider (Jev stays the only one), a change to the agent loop,
session memory or compaction, support for OpenAI's hosted tools (web search, file search, code
interpreter), Azure OpenAI's authentication, a per-interaction model name chosen by a process (a
process names a configured provider, as today), or a change to `ankka init`'s template, which keeps
Anthropic as its example and the documentation says how to swap.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Two agents, two providers, one Scala service (Priority: P1)

A developer building a support service wants the triage agent on an OpenAI model and the drafting
agent on Claude, in the same service. They construct both providers from the environment, hand one to
the runtime as the default, and name the other on the one handler that wants it. Both agents answer,
each call made by the provider the handler chose, and a refusal from either model is the caller's
refusal as today.

**Why this priority**: This is the feature as asked for: either provider, chosen per agent, in one
service, with nothing above the provider changing.

**Independent Test**: In the Scala test kit, register two agents, one on the runtime's default
scripted provider and one naming a second scripted provider standing in for OpenAI; call both and
assert each script was consumed by its own agent. Against the loopback stand-in, run a handler through
`OpenAIProvider` end to end: a plain answer, a tool call and its result, a refusal, and assert the
reply, the tool run and the outcome.

**Acceptance Scenarios**:

- added `features/agents/model-providers.feature`: a Scala service runs one agent on OpenAI and another on Anthropic
- added `features/agents/model-providers.feature`: a handler naming a model provider is answered by that model provider and no other
- added `features/agents/model-providers.feature`: an OpenAI model's refusal is the caller's refusal, not an answer

---

### User Story 2 - A process-hosted service names either provider (Priority: P1)

A Python, TypeScript or Rust developer sets `OPENAI_API_KEY` beside `ANTHROPIC_API_KEY` in the
descriptor, names `openai` as the default, and writes `with_model("anthropic")` on the one agent that
should stay on Claude. The sidecar makes each call with the provider the plan names; the process
never holds either key; an autonomous agent's class names `openai` the same way.

**Why this priority**: The polyglot SDKs are where "either provider" is impossible today: a process
cannot bring its own adapter, so the sidecar must offer OpenAI or Python, TypeScript and Rust do not
have it.

**Independent Test**: The sidecar suite starts a sidecar with both keys naming the loopback stand-in
for OpenAI and a scripted model standing in for Anthropic, with a default named; a plan naming
`openai` is answered by the stand-in, one naming `anthropic` by the script, one naming nothing by
the default. The conformance suite gains a case per SDK for a handler that names `openai`.

**Acceptance Scenarios**:

- added `features/agents/model-providers.feature`: an agent naming the model provider openai is answered by it in every language
- added `features/agents/model-providers.feature`: a service written in another language with two model providers and no default model refuses only an agent that names none
- added `features/agents/model-providers.feature`: a service written in another language with one model provider has it as its default model
- added `features/agents/model-providers.feature`: an autonomous agent of a service written in another language names the model provider openai
- changed `features/secrets/platform-settings.feature`: a platform setting a descriptor gives is kept from the process
- changed `features/secrets/platform-settings.feature`: a module that asks for a platform setting is told that it is not set

---

### User Story 3 - One session continues across providers (Priority: P2)

A conversation begins with an agent on Claude, which calls a tool and records its result in the
session. A second agent, on an OpenAI model, is asked the next question in the same session. It is
handed the whole conversation, the tool call and result included, and answers from it; the session's
usage after both turns is the sum of what each provider reported, in the same units.

**Why this priority**: "In the same system" means a session is the platform's, not a vendor's; if
one provider's turns were unreadable by the other, two providers would be two services in one
binary.

**Independent Test**: In the test kit, with two scripted providers, run a turn with a tool call on
the first and a turn on the second in one session; assert the second provider's request carried the
first's messages, tool call and result, and that the session's recorded usage is the sum. Against the
loopback stand-in, replay a session holding Anthropic-shaped tool call ids and assert the request
OpenAI receives pairs every call with its result.

**Acceptance Scenarios**:

- added `features/agents/model-providers.feature`: an agent on one model provider continues a session another model provider began
- added `features/agents/model-providers.feature`: a session's model usage is both model providers' added together
- added `features/agents/model-providers.feature`: a tool call one model provider made is sent to the other with its result

---

### User Story 4 - Blueprints and autonomous agents choose a provider by name (Priority: P2)

A Scala blueprint registers `openai` beside the runtime's default and gives its research worker
`.model("openai")` while its writing worker keeps `default`; a run makes each worker's calls on its
own provider. An autonomous agent's definition names an OpenAI provider directly and iterates on it.

**Why this priority**: Blueprints and autonomous agents already name models; they must name this one
with nothing new to learn, or the feature stops at request agents.

**Independent Test**: With two scripted providers registered under two names, run a two-worker
blueprint and assert each script was consumed by its worker; define an autonomous agent on the second
provider and assert its iterations' recorded model calls went to it.

**Acceptance Scenarios**:

- added `features/agents/model-providers.feature`: a blueprint runs one worker on each model provider
- added `features/agents/model-providers.feature`: an autonomous agent's definition names the model provider openai

---

### User Story 5 - What each provider does with a setting is written down (Priority: P3)

A developer reading the agents guide finds OpenAI beside Anthropic: how each is constructed, which
variables configure each on a sidecar, which is the default when both are present, and a table of
what each setting (`effort`, `thinking`, `temperature`, `maxTokens`) does on each provider and what is
ignored. The limitations page says what the OpenAI provider does not do. The configuration and
descriptor references list the new variables and the routing of `OPENAI_`.

**Why this priority**: The providers differ in what they accept, and a setting silently ignored is
the kind of thing the documentation exists to say first.

**Independent Test**: `just docs` passes with every new variable mentioned in prose beside its
generated table; the divergences page's model-settings section names both providers.

**Acceptance Scenarios**:

- added `features/documentation/model-providers.feature`: the documentation lists both model providers and what each setting does on each
- added `features/documentation/model-providers.feature`: the documentation lists every variable of the model provider openai
- added `features/documentation/model-providers.feature`: the documentation's limitations say what the model provider openai does not do

---

### Edge Cases

- **Both real keys, no default named.** A call naming no model is refused with a message naming
  `ANKKA_MODEL_DEFAULT` and the two providers; a call naming one proceeds; the service starts and its
  other components are unaffected. A Scala service has no such case: its default is the one it hands
  the runtime.
- **A key that is set but empty** is unset, as `ANTHROPIC_API_KEY` is today.
- **A script beside two real keys.** The real providers win for the default as today; the script is
  still nameable as `scripted`.
- **An OpenAI model name that does not exist.** The first call fails as the API answers, surfaced
  as the provider's failure (`Unavailable` to the caller, a failed iteration to an autonomous agent),
  never at service start, as for Claude.
- **A rate limit or a server error** is the provider's failure, retried by nothing in ankka, as for
  Claude; the model timeout bounds the wait as it does today.
- **A refusal.** OpenAI's refusal content on a message is `StopReason.Refusal` with its text as the
  reason, so the loop answers the caller as it does a Claude refusal and never records it as an
  answer.
- **A length stop** is `StopReason.MaxTokens`.
- **Tool arguments arrive as a JSON string.** Arguments that do not parse are the tool error the
  model is told, as today; a streamed tool call whose arguments arrive in fragments is complete in
  the terminal chunk and nowhere earlier.
- **A setting the model refuses.** `effort` above what a model offers is the highest it offers;
  `temperature` is withheld from a model that rejects it; `thinking` off is the lowest reasoning
  effort offered, or nothing on a model with none. None of these fails the call; the documentation
  says which applies.
- **Cache accounting.** OpenAI reports cached prompt tokens and no cache writes; the usage carries
  them as cache reads and zero writes, and the local console shows the same four columns it shows
  today.
- **Replaying another provider's history.** Tool call ids recorded by Claude are sent to OpenAI as
  recorded, each assistant call paired with the tool result the session holds; a history with a call
  and no result cannot exist, since the loop records every result.
- **A sidecar from before this feature** answers a plan naming `openai` as it answers any unknown
  model name today, naming the model; no protocol change is needed, since a model is named by a
  string already.
- **Compaction** summarises with the runtime's default provider, whichever vendor it is.
- **The key in a module.** A WebAssembly module's `config` answers `OPENAI_API_KEY` absent, as it
  answers `ANTHROPIC_API_KEY`.

## Requirements *(mandatory)*

### Functional Requirements

**The provider**

- **FR-001**: The agent module MUST ship an OpenAI `ModelProvider` constructed from the environment
  (`OPENAI_API_KEY`), from a key, or from a configured client, with a default model name and
  overridable defaults, as the Anthropic provider is. It MUST use OpenAI's official Java SDK as
  transport only; nothing above `ModelProvider` changes.
- **FR-002**: The provider MUST translate every part of a `ModelRequest`: the system message, the
  conversation (user, assistant and tool-result messages, context included), the tools with their
  JSON Schema, and the settings; and every part of a response: the text, the tool calls with ids and
  parsed arguments, the stop reason (end of turn, tool use, length, refusal with its reason, other)
  and the usage in ankka's units, cached prompt tokens as cache reads.
- **FR-003**: The provider MUST stream a response as it is generated: text deltas as they arrive, and
  a terminal `Completed` chunk carrying the same tool calls, stop reason and usage a completion
  would, so a streaming handler gives up nothing.
- **FR-004**: The provider MUST honour `effort`, `thinking`, `maxTokens` and `temperature` as the
  model accepts them, withholding what the model rejects rather than failing the call, and the
  mapping MUST be documented per provider.
- **FR-005**: A transport or API failure MUST surface as the provider's failure (`ModelCallFailed`,
  naming `openai`), so the loop's existing handling applies unchanged: `Unavailable` or `Timeout` to
  a caller, a failed iteration to an autonomous agent.

**Choosing a provider**

- **FR-006**: A Scala service MUST be able to hand the runtime either provider as its default and
  name either on any handler, autonomous agent definition or blueprint worker, with no API beyond
  what names a provider today.
- **FR-007**: A sidecar MUST configure the model named `openai` when `OPENAI_API_KEY` is set, with
  `ANKKA_MODEL_OPENAI_NAME` naming the OpenAI model and a documented default when unset;
  `ANTHROPIC_API_KEY`, `ANKKA_MODEL_NAME` and `ANKKA_MODEL_SCRIPT` MUST keep their meaning.
- **FR-008**: When exactly one real provider is configured on a sidecar it MUST be the default, as
  today. When more than one is, `ANKKA_MODEL_DEFAULT` MUST name the default; when it is unset, a plan
  naming no model MUST be refused naming the variable and the configured providers, and a plan naming
  a configured provider MUST proceed. A value naming a provider that is not configured MUST be
  refused at the sidecar's start, naming it.
- **FR-009**: A plan, an autonomous agent's class or a blueprint worker in Python, TypeScript or Rust
  MUST be able to name `openai` where it names `anthropic` today, with no SDK change beyond
  documentation.

**The key**

- **FR-010**: Every variable beginning `OPENAI_` MUST be routed to the platform's container only and
  withheld from a module's `config`, through the same lists that route `ANTHROPIC_`, so the existing
  proofs cover it; the descriptor reference's routing table and the deploy guide MUST list it.

**Sessions and accounting**

- **FR-011**: A session begun by an agent on one provider MUST be continuable by an agent on the
  other: the second provider's request MUST carry the recorded messages, tool calls and results, and
  the session's usage MUST be the sum of both providers' reported usage.

**Testing**

- **FR-012**: The provider's translation, streaming, tool calls, refusals, stop reasons, usage and
  failures MUST be proven offline against a stand-in speaking OpenAI's API on loopback, so `sbt test`
  needs no key; a live suite MUST run against the real API only when `OPENAI_API_KEY` is set and be
  skipped, not failed, without it.
- **FR-013**: The sidecar suite MUST prove the sidecar's choice of provider for a plan naming
  `openai`, `anthropic`, `scripted` and nothing, with and without a default named; the conformance
  suite MUST hold a case per SDK naming `openai`.

**Documentation**

- **FR-014**: The agents guide MUST present both providers: construction, sidecar variables, the
  default rule, and a per-provider table of what each setting does. The configuration reference, the
  descriptor reference, the deploy and run-locally guides, the polyglot concepts page and the
  divergences page MUST name the OpenAI variables and routing where they name Anthropic's today. The
  limitations page MUST say what the OpenAI provider does not do.

### Key Entities

- **Model provider**: a vendor's model ankka can talk to, behind one seam: a name, a model name,
  completion and streaming. Two ship: `anthropic` and `openai`; `scripted` is the test's.
- **Configured model**: on a sidecar, a provider enabled by its key and named by a fixed string a
  plan can give; on a Scala service, a provider value handed to the runtime or a handler.
- **Default model**: the provider a plan or handler that names none is answered by: the one handed
  to the runtime in Scala; on a sidecar, the only real provider, or the one `ANKKA_MODEL_DEFAULT`
  names.
- **Model settings**: what a request asks of a model (`effort`, `thinking`, `maxTokens`,
  `temperature`), honoured as each provider's models accept them.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A Scala service runs one agent on OpenAI and another on Claude by constructing two
  providers and naming one on one handler, with no other change, and both answer in one test kit run.
- **SC-002**: A Python, TypeScript or Rust service moves an agent to OpenAI by adding one variable to
  its descriptor and one name to the handler's effect, with no change to the SDK or the sidecar image
  beyond this release.
- **SC-003**: A session continued across both providers holds every turn and the sum of both usages,
  and the local console shows it in the same columns as a single-provider session.
- **SC-004**: `sbt test` with neither key set passes every suite, the two live suites skipped and
  reported as skipped; with `OPENAI_API_KEY` set, the live suite passes against the real API.
- **SC-005**: No developer's process or module can read `OPENAI_API_KEY`: the platform-settings
  feature and the module's config suite prove it beside `ANTHROPIC_API_KEY`.
- **SC-006**: Every suite that passed before this feature passes after it; no existing descriptor,
  handler or session changes behaviour.

## Assumptions

- OpenAI's official Java SDK (`com.openai:openai-java`) is Apache 2.0 licensed, so it adds no BSL
  constraint to the path; the plan confirms the licence and the version before adding it.
- The default OpenAI model name is the current general-purpose OpenAI model at implementation, named
  in the documentation as `claude-opus-5` is for Anthropic; the plan chooses it.
- The variable names `ANKKA_MODEL_OPENAI_NAME` and `ANKKA_MODEL_DEFAULT` are proposals; they sit
  under the `ANKKA_MODEL_` prefix the platform already routes, so no routing rule changes whatever
  the plan settles on.
- `OPENAI_BASE_URL`, `OPENAI_ORG_ID` and `OPENAI_PROJECT_ID` are read by the SDK's own environment
  support when the provider is built from the environment; ankka adds no handling of its own, and
  Azure OpenAI's authentication is out of scope.
- Session messages carry tool call ids opaquely, and OpenAI accepts a replayed id of another vendor's
  shape as long as each assistant tool call is followed by its result; the loop records every result,
  so a session never holds an unpaired call.
- Anthropic's thinking blocks are not held in session memory today, so nothing of Claude's reasoning
  has to be translated or dropped for OpenAI.
- A model is named by a string across the sidecar protocol already, so no protocol version changes
  and no gate is needed from either end.
- `with_context` text crosses to OpenAI as it crosses to Claude: as part of the user's message, sent
  and not remembered.

## Dependencies

- 009-polyglot-runtimes: the sidecar's configured models and the plan that names one.
- 015-autonomous-agents and 036-blueprints: the definitions and registries that name a model, which
  gain a provider and nothing else.
- 029-agent-approvals-and-mcp: a suspended turn resumes through the same provider seam; nothing
  changes, and the resume is proven on the new provider.
- 023 and 038 (secrets): the descriptor's `secretKeyRef` is how the key arrives; unchanged.

## Open Questions

- Whether a process should be able to name a model within a provider per interaction
  (`with_model("openai", model="…")`), rather than one configured model per provider; today it cannot
  for Anthropic either.
- Whether OpenAI's prompt caching should be steered (it is automatic for OpenAI; Anthropic's cache
  controls are not exposed by ankka today either).
- Whether the `ankka init` template should offer a provider choice.
