# Feature Specification: Model Providers — OpenAI, Compatible Endpoints and Named Configurations

**Feature Branch**: `052-openai-model-provider`

**Created**: 2026-10-17 (widened the same day from "OpenAI as a second model provider")

**Status**: Draft

**Input**: User description: "i want to add OpenAI as a model provider alongside anthropic. A user
should be able to create agents with either model provider in the same service/system." Widened
after research into the provider landscape: "create a list of possible providers we can support
along with a 'local' provider mechanism e.g. ollama", and the decision to make this one feature
rather than one per vendor.

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
is refused naming it. `PlatformVariables.RuntimeOnlyPrefixes` routes every `ANTHROPIC_` and
`ANKKA_MODEL_` variable to the platform's container alone, and a module's `config` import answers
them absent, so no developer's program ever holds the key.

What ankka's request carries is vendor-neutral by design: `ModelSettings` holds a model name,
temperature, max tokens, `effort` and `thinking`; the Anthropic adapter ignores temperature and sends
adaptive thinking and effort. `TokenUsage` counts input, output, cache reads and cache writes.
`StopReason` has `Refusal` as a successful response. ankka never sends a tool choice: the model
decides whether to call a tool, always.

**What the landscape looks like, as of October 2026.** The research behind this feature found that
the provider market has collapsed onto two wire protocols. OpenAI's Chat Completions shape is served
natively by Mistral, Groq, DeepSeek, Cohere, Together, Fireworks, Cerebras, the two routers
(OpenRouter and Hugging Face's Inference Providers), Amazon Bedrock's `openai/v1` endpoint, Google's
Gemini compatibility endpoint, Azure's unified `/openai/v1` path, and by every local server worth
naming: Ollama, llama.cpp's server, LM Studio, Docker Model Runner and vLLM. Anthropic's Messages
shape is served, beside it, by Ollama, llama.cpp, LM Studio, Docker Model Runner and DeepSeek, and
by Claude on Bedrock, Vertex and Foundry through backends the Anthropic SDK already ships. None of
Mistral, Groq, DeepSeek, xAI or Cohere ships an official Java SDK; an adapter per vendor would be the
generic one with a URL baked in. OpenAI itself has moved its primary API to Responses: GPT-6 Astra,
GPT-6.1 Sol and the codex models call tools only through it, while GPT-5.x still calls tools with
reasoning on Chat Completions (corrected by research R2; first written as "from GPT-5.4"). Responses
returns reasoning as separate items which, when the caller keeps its own state, must be replayed
between a tool call and its result within a turn. xAI recommends Responses and calls Chat Completions
deprecated. Ollama, LM Studio, llama.cpp, vLLM, Groq, OpenRouter and Hugging Face serve Responses too,
non-stateful; Gemini's compatibility endpoint does not.

So the seam exists and the documentation promises it ("Any other model is one class implementing
`ModelProvider`"), but ankka ships one vendor, a provider's name is a constant rather than something
the service chose, a developer who wants another vendor writes and maintains the adapter, and in
Python, TypeScript or Rust cannot have one at all. A developer with Ollama on their laptop cannot
point an ankka agent at it. The gap is not one vendor. It is that the platform cannot be told, by
name, "a model speaking this protocol, at this address, called this, with this key or none".

This feature makes these decisions.

- **Two protocols, not a vendor each.** ankka speaks Anthropic's Messages protocol and OpenAI's, and
  every provider it offers is one of the two pointed somewhere. The OpenAI protocol has two shapes:
  **Responses**, used for OpenAI's own API, where reasoning and tools together require it; and
  **Chat Completions**, used for every compatible endpoint, where it is what is universally served.
  OpenAI's official Java SDK is the transport for both, as Anthropic's is for Messages: ankka owns
  the loop, the SDK owns the wire. A vendor gets an adapter of its own only when the compatible
  protocol loses a capability ankka's seam exposes; none of the vendors named above qualifies today,
  and Gemini's native API, which does, is a later feature.
- **Every model provider has a name the service chose.** In Scala the name is given when the
  provider is built; on a sidecar it is the name in the variable that configures it. The name is
  what a handler, a worker or a definition names, what logs and failures say, and what the session's
  usage is recorded under, beside the name of the model that answered, since a provider's model may
  change between deploys while its name stays. `anthropic` and `openai` are the conventional names of the two vendors'
  own APIs, nothing more; a service may call its Ollama `local` and its Groq `fast`.
- **A model provider is configured by protocol, address, model and key.** On a sidecar, a family of
  variables under the prefix the platform already routes, `ANKKA_MODEL_<NAME>_*`: the protocol
  (`anthropic` or `openai`), the address, the model, and the key, which a local server does not
  need. `ANTHROPIC_API_KEY` and `OPENAI_API_KEY` remain shorthands that configure `anthropic` and
  `openai` at the vendors' own addresses: each is exactly the vendor's preset plus a key, so a
  family variable given beside it for the same name overrides what the preset set, as beside any
  preset. `ANKKA_MODEL_NAME` keeps naming the Claude model and is the same setting as
  `ANKKA_MODEL_ANTHROPIC_MODEL`; `ANKKA_MODEL_SCRIPT` keeps configuring `scripted`. In Scala, the
  same four things to a constructor. **Presets** name the endpoints ankka has tested, so `ANKKA_MODEL_FAST_PRESET=groq`
  and a key is a whole configuration: a preset carries the address, the protocol, the shape, a
  default model and the endpoint's known quirks, and the documentation lists them.
- **The default is named when there is more than one.** When exactly one real provider is configured
  on a sidecar it is the default, as today. With more than one, `ANKKA_MODEL_DEFAULT` names it;
  without it, a plan naming no model is refused naming the variable and the providers. A Scala
  service's default is the one it hands the runtime, as today.
- **The same request, honestly translated, and the differences written down.** Tools, tool results,
  context, system message and the conversation cross every protocol as they cross to Claude today.
  `effort` maps onto the protocol's reasoning effort as far as the endpoint offers it; `thinking` off
  is the lowest effort offered, or nothing sent; `temperature` is sent on the OpenAI protocol and
  withheld on Anthropic's, as today. A refusal, a length stop and a tool stop each become the
  `StopReason` the loop already handles. Usage is recorded in ankka's units: cached prompt tokens as
  cache reads, an endpoint that reports no usage as zero. A setting an endpoint rejects is withheld,
  never a failed call. The documentation says, per protocol and per preset, what each setting does
  and what is ignored.
- **One session, any provider.** The session's messages are ankka's, so a session begun on one
  provider is continued by an agent on another, tool calls and results included, and the session's
  usage is the sum. Because at least one vendor constrains the shape of a tool call's id, a provider
  may rewrite ids on the way out, consistently within one request, so a call and its result stay
  paired; the session keeps the ids the model gave.
- **A provider's reasoning state is carried within a turn, never between turns.** The Responses
  shape returns reasoning items that must accompany a tool call back to the model with its result.
  The loop keeps, with an assistant message, an opaque value of the provider's that it hands back on
  the next call of the same turn and never persists, as it keeps nothing of Claude's thinking today.
  Nothing is ever stored on the vendor's side: a session is ankka's.
- **The key is the platform's.** `OPENAI_` joins `ANTHROPIC_` among the prefixes routed to the
  platform's container only and withheld from a module's `config`; `ANKKA_MODEL_` already is, so a
  key given as `ANKKA_MODEL_<NAME>_KEY` is routed with no new rule. Presets do not introduce vendor
  key variables of their own: a preset's key is `ANKKA_MODEL_<NAME>_KEY`, so the routing list does
  not grow with the preset list.
- **"Local" is a named provider pointed at a server the developer runs.** On a laptop, Ollama, LM
  Studio or Docker Model Runner at their default addresses, with no key, under whatever name the
  developer likes. Nothing is detected: a provider exists because it was configured, as a component
  is registered. A model server the installation itself runs, which a deployed service reaches
  inside the cluster, is the next feature (053) and builds on the preset mechanism here.
- **Tested without a key, with one on purpose, and against a real local server when there is one.**
  Translation, streaming, tool calls, refusals, usage, id rewriting and failures are proven against
  a stand-in on loopback speaking each shape (Messages, Responses, Chat Completions), so an ordinary
  `sbt test` stays offline. A live suite per vendor runs only when its key is set. A suite against a
  real local server runs only when its address is given, and asserts the plumbing, not the model's
  wit.

What this feature is not: a judgment provider (Jev stays the only one), a change to the agent loop's
behaviour beyond carrying a provider's opaque state within a turn, a native Gemini adapter, support
for any vendor's hosted tools (web search, file search, code interpreter), Azure's or Bedrock's
own authentication beyond what the SDKs do when pointed at them, embeddings, a per-interaction model
name chosen by a process (a process names a configured provider, as today), or a change to `ankka
init`'s template beyond a commented example of a second provider.

## Clarifications

### Session 2026-10-17

- Q: The glossary proposes six terms for this feature (model provider, model protocol, preset, provider state, effort, default model) and stretches two existing words: *unavailable* (defined for token verification only) for a model provider that cannot be reached, and *key* (a message's key) in its everyday sense for an API key. How should these be settled? → A: Accept the six terms as defined; widen *unavailable* to also cover a model provider that cannot be reached; *key* stays an everyday word.
- Q: What characters may a provider's name have, given that it becomes a segment of `ANKKA_MODEL_<NAME>_*` and a dash has no clean variable form? → A: Lowercase letters and digits only, starting with a letter; the variable segment is the name upper-cased (`fast` → `ANKKA_MODEL_FAST_URL`). No dashes.
- Q: When a descriptor gives a shorthand (`OPENAI_API_KEY`, `ANTHROPIC_API_KEY` with `ANKKA_MODEL_NAME`) and family variables for the same name (`ANKKA_MODEL_OPENAI_URL`, `ANKKA_MODEL_ANTHROPIC_MODEL`), what happens? → A: The shorthand is sugar for the vendor's preset plus a key; family variables for that name override what the preset set. `ANKKA_MODEL_NAME` stays the Claude-model shorthand, equal to `ANKKA_MODEL_ANTHROPIC_MODEL`; both given and different is refused at start.
- Q: A provider's model can change between deploys while its name stays; what should a turn's recorded usage carry? → A: The provider's name and the model's name (e.g. `fast`, `llama-3.3-70b`), both recorded with each turn's usage.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Two vendors, two agents, one Scala service (Priority: P1)

A developer building a support service wants the triage agent on an OpenAI model and the drafting
agent on Claude, in the same service. They construct both providers from the environment, hand one to
the runtime as the default, and name the other on the one handler that wants it. Both agents answer;
each call is made by the provider the handler chose; the service starts with either key missing and
refuses only the work that needs the missing one.

**Why this priority**: This is the feature as first asked for: either vendor, chosen per agent, in
one service, with nothing above the provider changing.

**Independent Test**: In the Scala test kit, register two agents, one on the runtime's default
scripted provider and one naming a second scripted provider; call both and assert each script was
consumed by its own agent. Against the loopback stand-in speaking Responses, run a handler through
the OpenAI provider end to end: a plain answer, a tool call and its result, a refusal.

**Acceptance Scenarios**:

- added `features/agents/model-providers.feature`: a Scala service runs one agent on OpenAI and another on Anthropic
- added `features/agents/model-providers.feature`: a handler naming a model provider is answered by that model provider and no other
- added `features/agents/model-providers.feature`: a service with the key of one model provider alone refuses only the agent that names the other
- added `features/agents/model-providers.feature`: an OpenAI model's refusal is the caller's refusal, not an answer

---

### User Story 2 - A process-hosted service names its model providers (Priority: P1)

A Python, TypeScript or Rust developer sets `OPENAI_API_KEY` beside `ANTHROPIC_API_KEY` in the
descriptor, names `openai` as the default, and writes `with_model("anthropic")` on the one agent that
should stay on Claude. The sidecar makes each call with the provider the plan names; the process
never holds either key; an autonomous agent's class names `openai` the same way.

**Why this priority**: The polyglot SDKs are where a second provider is impossible today: a process
cannot bring its own adapter, so the sidecar must offer it or Python, TypeScript and Rust do not
have it.

**Independent Test**: The sidecar suite starts a sidecar with both shorthands, one naming the
loopback stand-in and one a scripted model, with a default named; a plan naming `openai` is answered
by the stand-in, one naming `anthropic` by the script, one naming nothing by the default. The
conformance suite gains a case per SDK for a handler that names a second provider.

**Acceptance Scenarios**:

- added `features/agents/model-providers.feature`: an agent naming the model provider openai is answered by it in every language
- added `features/agents/model-providers.feature`: a service written in another language with two model providers and no default model refuses only an agent that names none
- added `features/agents/model-providers.feature`: a service written in another language with one model provider has it as its default model
- added `features/agents/model-providers.feature`: an autonomous agent of a service written in another language names the model provider openai
- changed `features/secrets/platform-settings.feature`: a platform setting a descriptor gives is kept from the process

---

### User Story 3 - Any compatible endpoint, by name, including one on the developer's laptop (Priority: P1)

A developer running Ollama configures a provider named `local`: the OpenAI protocol, the address
`http://localhost:11434/v1`, the model `qwen3:8b`, no key. Their agent answers from it, with tool
calls, in Scala through a constructor and in Python through four variables on the sidecar. The same
developer, in production, configures `fast` as the `groq` preset with a key and a model, and the
agent moves with one name changed. Logs, failures and the session's usage say `local` and `fast`,
never a vendor's name the developer did not choose.

**Why this priority**: This is what turns two vendors into the whole market and a laptop. Every
endpoint the research found is reachable this way, and the local story is the one developers ask
for first.

**Independent Test**: The sidecar suite configures a named provider by the four variables against
the loopback stand-in speaking Chat Completions, with no key, and asserts a plan naming it is
answered, a tool call round-trips, usage is recorded under the name, and a failure names it. In the
Scala test kit, a provider built from the four values has the name it was given. When a local
server's address is set in the environment, a suite runs one agent turn and one tool call through
it, asserting a reply arrived, the tool was run, and usage was recorded; it is skipped, not
failed, without the address.

**Acceptance Scenarios**:

- added `features/agents/compatible-endpoints.feature`: a model provider is configured by name from a model protocol, an address, a model and no key
- added `features/agents/compatible-endpoints.feature`: a model provider at an address of the developer's own answers an agent with tool calls
- added `features/agents/compatible-endpoints.feature`: the model provider's name is the service's, in logs, failures and model usage
- added `features/agents/compatible-endpoints.feature`: a Scala service builds a model provider from the same four values
- added `features/agents/compatible-endpoints.feature`: a model provider configured with an address and no model protocol is refused at the service's start
- added `features/agents/compatible-endpoints.feature`: a model provider configured with a reserved name is refused at the service's start
- added `features/agents/compatible-endpoints.feature`: a model provider whose settings do not agree is refused at the service's start
- added `features/agents/compatible-endpoints.feature`: a variable of the model provider family that is not a setting is refused at the service's start
- added `features/agents/compatible-endpoints.feature`: a setting a model provider's address does not accept is withheld, never a failed call
- added `features/agents/compatible-endpoints.feature`: a model provider whose address reports no model usage is recorded as none

---

### User Story 4 - One session continues across providers (Priority: P2)

A conversation begins with an agent on Claude, which calls a tool and records its result in the
session. A second agent, on an OpenAI model, is asked the next question in the same session. It is
handed the whole conversation, the tool call and result included, and answers from it; the session's
usage after both turns is the sum of what each provider reported, in the same units. A third agent,
on a provider whose endpoint constrains the shape of tool call ids, is handed the same session and
answers too.

**Why this priority**: "In the same system" means a session is the platform's, not a vendor's; if
one provider's turns were unreadable by another, two providers would be two services in one binary.

**Independent Test**: In the test kit, with two scripted providers, run a turn with a tool call on
the first and a turn on the second in one session; assert the second provider's request carried the
first's messages, tool call and result, and that the session's recorded usage is the sum. Against
the loopback stand-in, replay a session holding Anthropic-shaped tool call ids and assert the request
received pairs every call with its result, and that with a preset that constrains ids the ids
received are of the constrained shape while the session's are unchanged.

**Acceptance Scenarios**:

- added `features/agents/model-providers.feature`: an agent on one model provider continues a session another model provider began
- added `features/agents/model-providers.feature`: a session's model usage is both model providers' added together
- added `features/agents/model-providers.feature`: a tool call one model provider made is sent to the other with its result
- added `features/agents/compatible-endpoints.feature`: a tool call's id is rewritten for a model provider that constrains it and the session keeps the model's

---

### User Story 5 - Blueprints and autonomous agents choose a provider by name (Priority: P2)

A Scala blueprint registers `openai` beside the runtime's default and gives its research worker
`.model("openai")` while its writing worker keeps `default`; a run makes each worker's calls on its
own provider. An autonomous agent's definition names a provider directly and iterates on it.

**Why this priority**: Blueprints and autonomous agents already name models; they must name any
provider with nothing new to learn, or the feature stops at request agents.

**Independent Test**: With two scripted providers registered under two names, run a two-worker
blueprint and assert each script was consumed by its worker; define an autonomous agent on the second
provider and assert its iterations' recorded model calls went to it.

**Acceptance Scenarios**:

- added `features/agents/model-providers.feature`: a blueprint runs one worker on each model provider
- added `features/agents/model-providers.feature`: an autonomous agent's definition names the model provider openai

---

### User Story 6 - Reasoning and tools together on OpenAI's own models (Priority: P2)

A developer's agent on a current OpenAI model has tools and leaves `thinking` on. The model reasons,
calls a tool, is told the result together with what it was reasoning, and finishes the turn. The
next turn starts clean; nothing of the model's reasoning is in the session.

**Why this priority**: Without it the OpenAI provider either turns reasoning off whenever tools are
present or fails on the models OpenAI sells today. It is the one place this feature touches what the
loop carries.

**Independent Test**: Against the loopback stand-in speaking Responses, which refuses a tool result
that does not bring back the reasoning item it issued, run a turn with a tool call and assert the
turn completes; then assert the session's recorded messages hold no reasoning state and a second
turn's first request carries none.

**Acceptance Scenarios**:

- added `features/agents/model-providers.feature`: a tool call made while the model reasons is sent back with the provider state the model asked to keep
- added `features/agents/model-providers.feature`: provider state is never recorded in the session

---

### User Story 7 - Presets for the endpoints ankka has tested (Priority: P3)

A developer who wants Mistral sets `ANKKA_MODEL_EU_PRESET=mistral`, `ANKKA_MODEL_EU_KEY` from a
secret, and nothing else; the preset supplies the address, the protocol, a default model and the
knowledge that this endpoint constrains tool call ids. The documentation lists every preset, what it
sets, and which of ankka's settings the endpoint honours. A preset that needs a key refuses to start
without one, naming the variable.

**Why this priority**: Presets are convenience and a tested list; a developer can always give the
four values by hand. They matter because "works" in the documentation must mean "tested".

**Independent Test**: For every preset, the sidecar suite configures it against the loopback stand-in
at the preset's address overridden to loopback and asserts the request carries the preset's
protocol, shape and default model, and that the id rule is applied where the preset says so. Each
preset's live suite runs against the real endpoint only when its key is set.

**Acceptance Scenarios**:

- added `features/agents/compatible-endpoints.feature`: a preset configures a model provider from a name and a key
- added `features/agents/compatible-endpoints.feature`: a preset that needs a key is refused at the service's start without one
- added `features/agents/compatible-endpoints.feature`: a value given beside a preset overrides what the preset set
- added `features/agents/compatible-endpoints.feature`: a preset without a key is a model provider at an address that needs none
- added `features/agents/compatible-endpoints.feature`: every preset the platform names is configured from its name
- added `features/agents/compatible-endpoints.feature`: a value of the model provider family beside a vendor's key overrides what the key set
- added `features/agents/compatible-endpoints.feature`: the model of the model provider anthropic given twice with different values is refused at the service's start

---

### User Story 8 - What each provider does with a setting is written down (Priority: P3)

A developer reading the agents guide finds every way to be given a model: the two shorthands, the
variable family, the presets and the Scala constructors; which is the default when there is more
than one; a table of what each setting (`effort`, `thinking`, `temperature`, `maxTokens`) does on
each protocol and each preset and what is ignored; and a local development page that gets Ollama,
LM Studio or Docker Model Runner answering an agent in a few lines. The limitations page says what a
compatible endpoint does not give. The configuration and descriptor references list the variable
family and the routing of `OPENAI_`.

**Why this priority**: Endpoints differ in what they accept, and a setting silently ignored is the
kind of thing the documentation exists to say first.

**Independent Test**: `just docs` passes with every new variable mentioned in prose beside its
generated table; the divergences page's model-settings section names both protocols.

**Acceptance Scenarios**:

- added `features/documentation/model-providers.feature`: the documentation lists every way a service is given a model provider and what each setting does on each
- added `features/documentation/model-providers.feature`: the documentation lists every preset and what it sets
- added `features/documentation/model-providers.feature`: the documentation gets a model on the developer's own machine answering an agent
- added `features/documentation/model-providers.feature`: the documentation lists every variable of the model provider family and says each is a platform setting
- added `features/documentation/model-providers.feature`: the documentation's limitations say what a model provider at an address of the developer's own does not give

---

### Edge Cases

- **Both real keys, no default named.** A call naming no model is refused with a message naming
  `ANKKA_MODEL_DEFAULT` and the configured providers; a call naming one proceeds; the service starts
  and its other components are unaffected.
- **A key that is set but empty** is unset, as `ANTHROPIC_API_KEY` is today. A provider configured
  by the variable family with no key and no preset is a keyless endpoint, allowed.
- **A script beside real providers.** The real providers win for the default as today; the script is
  still nameable as `scripted`.
- **A provider name that is reserved or malformed.** `scripted`, `default`, `name` and `script` are
  reserved; a name must be lowercase letters and digits, starting with a letter, so that its variable
  segment is the name upper-cased and nothing is mapped (`local-fast` and `my_model` are refused).
  Either is refused at the sidecar's start, naming the variable. A Scala service that builds a
  provider under a reserved name through the platform's factory is refused at construction, and
  one handed to the runtime as its default is refused when the runtime starts; a provider chosen
  in a handler is checked no earlier than the call that names it.
- **A variable under the family that is not a setting.** `ANKKA_MODEL_FAST_URLS`, or any
  `ANKKA_MODEL_*` name that is neither a shorthand nor `<NAME>_{PROTOCOL,URL,MODEL,KEY,SHAPE,PRESET}`,
  is refused at the sidecar's start naming it: a misspelt setting must not become a provider
  silently refused for the wrong reason.
- **A Scala provider built from the environment with its key unset** exists under its name and
  fails every call `Unavailable`, naming the variable; the service starts and refuses only the
  work that names it. Before this feature the Anthropic provider threw at construction.
- **A protocol value that is neither `anthropic` nor `openai`,** an address that is not a URL, a
  preset that does not exist, or a `ANKKA_MODEL_DEFAULT` naming a provider that is not configured:
  each is refused at the sidecar's start, naming the variable and the value.
- **An address with no protocol and no preset** is refused at start: the platform cannot guess what
  a server speaks. A preset with no address is complete; an address beside a preset overrides it.
- **A shorthand and the family for one name.** `OPENAI_API_KEY` beside `ANKKA_MODEL_OPENAI_URL` is
  the `openai` preset with its key and an overridden address: one provider, not two and not a
  conflict. `ANKKA_MODEL_NAME` and `ANKKA_MODEL_ANTHROPIC_MODEL` are one setting; given twice with
  different values, the service is refused at start naming both.
- **A model name the endpoint does not know.** The first call fails as the endpoint answers,
  surfaced as the provider's failure (`Unavailable` to the caller, a failed iteration to an
  autonomous agent), never at service start, as for Claude. A provider configured by the variable
  family with no model and no preset is refused at start: an arbitrary server has no default.
- **A rate limit or a server error** is the provider's failure, retried by nothing in ankka, as for
  Claude; the model timeout bounds the wait as it does today.
- **A refusal.** OpenAI's refusal content is `StopReason.Refusal` with its text as the reason. A
  compatible endpoint never produces one; a local model that declines in prose has answered, and a
  guardrail is the place to catch it.
- **A length stop** is `StopReason.MaxTokens` on every protocol.
- **Tool arguments arrive as a JSON string** on the OpenAI protocol. Arguments that do not parse are
  the tool error the model is told, as today; a streamed tool call whose arguments arrive in
  fragments is complete in the terminal chunk and nowhere earlier. A local model that emits a tool
  call as text, because its template is not tool-aware, has answered with text; the documentation
  says which models to choose.
- **A setting the endpoint refuses.** `effort` above what a model offers is the highest it offers;
  `temperature` is withheld on the Anthropic protocol; `thinking` off is the lowest reasoning effort
  offered, or nothing on an endpoint with none. On a bare compatible endpoint only what Chat
  Completions defines is sent, and `reasoning_effort` only when `effort` was set. None of these fails
  the call.
- **Usage.** Cached prompt tokens are cache reads and zero writes; an endpoint that reports no usage
  is recorded as zero, and the local console shows the same four columns it shows today. Each
  turn's usage is recorded with the provider's name and the model's name, so a session whose
  provider changed model between deploys still says which model answered each turn. A streamed
  response asks the endpoint to report usage in the stream where the protocol allows asking.
- **Replaying another provider's history.** Tool call ids recorded by one model are sent to another
  as recorded unless the receiving preset constrains ids, in which case they are rewritten
  consistently within the request; a history with a call and no result cannot exist, since the loop
  records every result.
- **A turn interrupted between a tool call and its result on the Responses shape** (a restart, an
  approval awaited) resumes without the reasoning state, since it was never persisted; the provider
  sends the call and result without it, which the protocol accepts at some cost to the answer. The
  documentation says so.
- **A sidecar from before this feature** answers a plan naming a new provider as it answers any
  unknown model name today, naming the model; no protocol change is needed, since a model is named
  by a string already.
- **Compaction** summarises with the runtime's default provider, whichever it is.
- **The key in a module.** A WebAssembly module's `config` answers `OPENAI_API_KEY` and every
  `ANKKA_MODEL_` variable absent.
- **An OpenAI endpoint that is not api.openai.com but is told to speak Responses** (a preset says so,
  or the developer sets the shape): the provider speaks Responses non-statefully, never asking the
  endpoint to store anything.

## Requirements *(mandatory)*

### Functional Requirements

**The protocols**

- **FR-001**: The agent module MUST ship a model provider speaking OpenAI's protocol, constructed from
  the environment (`OPENAI_API_KEY`), from a key, or from a configured client, with a default model
  name and overridable defaults, as the Anthropic provider is. It MUST use OpenAI's official Java SDK
  as transport only; nothing above `ModelProvider` changes beyond FR-013.
- **FR-002**: The OpenAI protocol MUST have two shapes, Responses and Chat Completions. OpenAI's own
  API MUST be spoken in Responses, never asking the endpoint to store state; a compatible endpoint
  MUST be spoken in Chat Completions unless its preset says otherwise.
- **FR-003**: Both the Anthropic and the OpenAI provider MUST be constructible, in Scala, from a
  name, a protocol shape, an address, a model and an optional key, and MUST report the name they
  were given as their `name`.
- **FR-004**: Every provider MUST translate every part of a `ModelRequest`: the system message, the
  conversation (user, assistant and tool-result messages, context included), the tools with their
  JSON Schema, and the settings; and every part of a response: the text, the tool calls with ids and
  parsed arguments, the stop reason (end of turn, tool use, length, refusal with its reason, other)
  and the usage in ankka's units, cached prompt tokens as cache reads, absent usage as zero.
- **FR-005**: Every provider MUST stream a response as it is generated: text deltas as they arrive,
  and a terminal `Completed` chunk carrying the same tool calls, stop reason and usage a completion
  would.
- **FR-006**: Every provider MUST honour `effort`, `thinking`, `maxTokens` and `temperature` as the
  endpoint accepts them, withholding what it rejects rather than failing the call, and the mapping
  MUST be documented per protocol and per preset. ankka MUST never send a tool choice.
- **FR-007**: A transport or API failure MUST surface as the provider's failure (`ModelCallFailed`,
  naming the provider's name), so the loop's existing handling applies unchanged.

**Naming and configuring providers**

- **FR-008**: On a sidecar, a model provider MUST be configurable by name through variables
  `ANKKA_MODEL_<NAME>_PROTOCOL`, `_URL`, `_MODEL`, `_KEY`, `_SHAPE` and `_PRESET`, exact names the
  plan settles under the `ANKKA_MODEL_` prefix. `ANTHROPIC_API_KEY`, `OPENAI_API_KEY`,
  `ANKKA_MODEL_NAME` and `ANKKA_MODEL_SCRIPT` MUST keep their meaning: shorthands for `anthropic`,
  `openai`, the Claude model and `scripted`. A vendor key shorthand MUST be equivalent to that
  vendor's preset plus `ANKKA_MODEL_<NAME>_KEY`, so a family variable given beside it for the same
  name overrides what the preset set; `ANKKA_MODEL_NAME` MUST be the same setting as
  `ANKKA_MODEL_ANTHROPIC_MODEL`, and the two given with different values MUST be refused at the
  sidecar's start, naming both.
- **FR-009**: A name MUST be lowercase letters and digits, starting with a letter, so that its
  segment of `ANKKA_MODEL_<NAME>_*` is the name upper-cased with no mapping, and MUST NOT be
  reserved (`scripted`, `default`, `name`, `script`); a configuration that is incomplete (no protocol and no
  preset, no model and no preset), inconsistent (an unknown protocol, shape or preset, a non-URL
  address) or names a key variable that is required and unset MUST be refused at the sidecar's
  start, naming the variable and the value; so MUST an `ANKKA_MODEL_` variable that is neither a
  shorthand nor a setting of the family. A configuration with no key and no preset MUST be
  accepted as a keyless endpoint.
- **FR-010**: When exactly one real provider is configured on a sidecar it MUST be the default. When
  more than one is, `ANKKA_MODEL_DEFAULT` MUST name the default; when it is unset, a plan naming no
  model MUST be refused naming the variable and the configured providers, and a plan naming a
  configured provider MUST proceed. A value naming a provider that is not configured MUST be refused
  at the sidecar's start.
- **FR-011**: A Scala service MUST be able to hand the runtime any provider as its default and name
  any on a handler, an autonomous agent definition or a blueprint worker, with no API beyond what
  names a provider today. A plan, class or worker in Python, TypeScript or Rust MUST be able to name
  any configured provider where it names `anthropic` today, with no SDK change beyond documentation.
- **FR-012**: Presets MUST exist for, at least: `openai`, `anthropic`, `ollama`, `lmstudio`,
  `llamacpp`, `docker-model-runner`, `vllm`, `groq`, `mistral`, `deepseek`, `xai`, `gemini`,
  `openrouter`, `huggingface`, `bedrock` and `azure`. Each MUST set the protocol, shape, address
  where one is fixed, whether a key is required, a default model where the vendor has an obvious
  one, and the endpoint's quirks (at least: whether tool call ids are constrained, and which
  `reasoning_effort` values are accepted where the vendor documents them; a preset that lists
  none sends `effort` only when it was set, as given). A value given beside a preset MUST
  override it.

**Sessions and state**

- **FR-013**: A provider MUST be able to attach an opaque value to an assistant message within a
  turn, which the loop hands back on the next call of that turn and never records in the session,
  so the Responses shape can return reasoning items with a tool result.
- **FR-014**: A session begun on one provider MUST be continuable by an agent on any other: the
  second provider's request MUST carry the recorded messages, tool calls and results, and the
  session's usage MUST be the sum, kept per provider's name, and every recorded AI message MUST
  name the provider and the model that answered it.
- **FR-015**: A provider whose preset constrains tool call ids MUST rewrite ids consistently within a
  request so each call stays paired with its result, and MUST leave the session's recorded ids
  unchanged.

**The key**

- **FR-016**: Every variable beginning `OPENAI_` MUST be routed to the platform's container only and
  withheld from a module's `config`, through the same lists that route `ANTHROPIC_` and
  `ANKKA_MODEL_`; the descriptor reference's routing table and the deploy guide MUST list it. No
  preset MUST introduce a key variable outside `ANKKA_MODEL_<NAME>_KEY`.

**Testing**

- **FR-017**: Every protocol shape's translation, streaming, tool calls, refusals, stop reasons,
  usage, id rewriting and failures MUST be proven offline against a stand-in on loopback speaking
  that shape, so `sbt test` needs no key. The stand-in speaking Responses MUST refuse a tool result
  that does not return the reasoning item it issued.
- **FR-018**: A live suite per vendor MUST run against the real API only when that vendor's key is
  set, and be skipped, not failed, without it. A suite against a real local model server MUST run
  only when its address is given, assert that a turn and a tool call round-trip and usage is
  recorded, and be skipped without it.
- **FR-019**: The sidecar suite MUST prove the sidecar's choice of provider for plans naming each
  configured name and nothing, with and without a default, and every refusal of FR-009 and FR-010
  by its message; the conformance suite MUST hold a case per SDK naming a second provider.

**Documentation**

- **FR-020**: The agents guide MUST present every way a service is given a provider, the default
  rule, the per-protocol and per-preset settings table, and the preset list. A local development
  page MUST get a local model server answering an agent. The configuration reference, the descriptor
  reference, the deploy and run-locally guides, the polyglot concepts page and the divergences page
  MUST name the variable family and routing where they name Anthropic's today. The limitations page
  MUST say what a compatible endpoint does not give: no refusal reason, no cache accounting where the
  endpoint reports none, tool quality that is the model's and its template's, reasoning state not
  carried across an interrupted turn.

### Key Entities

- **Model provider**: a model ankka can talk to, under a name the service chose, behind one seam: a
  name, a model name, completion and streaming. Built from a protocol, an address, a model and an
  optional key, or from a preset; `anthropic` and `openai` are the vendors' own, `scripted` the
  test's.
- **Model protocol**: one of the two wire shapes ankka speaks, Anthropic's Messages or OpenAI's; the
  OpenAI protocol in one of two shapes, Responses or Chat Completions.
- **Preset**: a tested endpoint's configuration under a name: protocol, shape, address, whether a
  key is required, a default model, and its quirks.
- **Configured model**: on a sidecar, a provider enabled by its variables under a name a plan can
  give; on a Scala service, a provider value handed to the runtime or a handler.
- **Default model**: the provider a plan or handler that names none is answered by.
- **Provider state**: an opaque value a provider attaches to an assistant message within a turn,
  handed back on the turn's next call and never recorded.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A Scala service runs one agent on OpenAI and another on Claude by constructing two
  providers and naming one on one handler, with no other change, and both answer in one test kit run.
- **SC-002**: A Python, TypeScript or Rust service moves an agent to any configured provider by
  adding variables to its descriptor and one name to the handler's effect, with no change to the SDK
  or the sidecar image beyond this release.
- **SC-003**: A developer with Ollama running points an agent at it with four variables, or one
  preset and a model, and gets an answer with a tool call, in every language; the same configuration
  with a different preset and a key reaches a hosted vendor.
- **SC-004**: A session continued across two providers holds every turn and the sum of both usages,
  each turn's under its provider's name and its model's name, and the local console shows it in the same columns as a single-provider session.
- **SC-005**: `sbt test` with no key and no local server address passes every suite, each live suite
  skipped and reported as skipped; with a vendor's key set its live suite passes against the real
  API; with a local server's address set that suite passes against it.
- **SC-006**: No developer's process or module can read `OPENAI_API_KEY` or any `ANKKA_MODEL_`
  variable: the platform-settings feature and the module's config suite prove it beside
  `ANTHROPIC_API_KEY`.
- **SC-007**: Every preset in FR-012 is exercised against the stand-in in the offline suite, and the
  documentation's preset table is generated from, or checked against, the same list.
- **SC-008**: Every suite that passed before this feature passes after it; no existing descriptor,
  handler or session changes behaviour.

## Assumptions

- OpenAI's official Java SDK (`com.openai:openai-java`, 4.83.0 at writing) is Apache 2.0 licensed,
  speaks both Responses and Chat Completions, honours `OPENAI_BASE_URL`, and streams with
  accumulators for both shapes; the plan confirms the licence and the version before adding it. The
  Anthropic SDK honours `ANTHROPIC_BASE_URL` and takes a base URL on its builder, which is how the
  Anthropic protocol reaches a compatible endpoint.
- The default OpenAI model name is the current general-purpose OpenAI model at implementation,
  named in the documentation as `claude-opus-5` is for Anthropic; the plan chooses it. Presets'
  default models are chosen the same way and listed.
- The variable names in FR-008 are proposals; they sit under the `ANKKA_MODEL_` prefix the platform
  already routes, so no routing rule changes whatever the plan settles on.
- OpenAI's Responses shape accepts replayed tool call ids of another vendor's shape when each call is
  followed by its result; Mistral constrains ids to nine alphanumeric characters; the plan verifies
  both and records any other constrained endpoint on its preset.
- No endpoint checks a replayed id against server-side state, so rewriting ids per request is sound.
- Anthropic's thinking blocks are not held in session memory today and this feature keeps it so;
  provider state of FR-013 is the one addition, held within a turn only.
- A model is named by a string across the sidecar protocol already, so no protocol version changes
  and no gate is needed from either end.
- A local model server's tool calling depends on the model and its chat template; the suite against
  one asserts plumbing and the documentation names models known to work.
- `with_context` text crosses every protocol as it crosses to Claude: as part of the user's message,
  sent and not remembered.

## Dependencies

- 009-polyglot-runtimes: the sidecar's configured models and the plan that names one.
- 015-autonomous-agents and 036-blueprints: the definitions and registries that name a model, which
  gain nothing but more names.
- 029-agent-approvals-and-mcp: a suspended turn resumes through the same provider seam; the resume
  is proven on the new providers, and the edge case of resuming without provider state is documented.
- 023 and 038 (secrets): the descriptor's `secretKeyRef` is how a key arrives; unchanged.
- 053-installation-models builds on this feature's presets: a model server the installation runs is
  one more preset, whose address the platform supplies.

## Open Questions

- Whether a process should be able to name a model within a provider per interaction
  (`with_model("openai", model="…")`), rather than one configured model per provider; today it
  cannot for Anthropic either.
- Whether prompt caching should be steered on either vendor (automatic on OpenAI; Anthropic's cache
  controls are not exposed by ankka today).
- Whether a provider's state (FR-013) should survive a suspended turn awaiting approval, which would
  mean recording an opaque vendor value in the session. This feature says no and documents the cost.
- Whether `ankka init` should offer a provider choice beyond a commented example.
