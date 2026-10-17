# Implementation Plan: Model Providers — OpenAI, Compatible Endpoints and Named Configurations

**Branch**: `052-model-providers` | **Date**: 2026-10-17 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/052-model-providers/spec.md`

## Summary

ankka ships one model adapter, `AnthropicProvider`, with a constant name. This feature makes a
model provider something a service configures by name from a **protocol** (Anthropic's Messages or
OpenAI's), an **address**, a **model** and an optional **key**, or from a **preset** naming a
tested endpoint. The agent module gains `OpenAiProvider` (two shapes, Responses for OpenAI's own
API and Chat Completions for every compatible endpoint, over OpenAI's official Java SDK as
transport only), `AnthropicProvider` gains a name and an address, and one factory
(`ModelProviders`) builds either from a `ProviderConfig`. The sidecar's `Models.fromEnv` parses the
variable family `ANKKA_MODEL_<NAME>_*`, the two vendor shorthands and `ANKKA_MODEL_DEFAULT` into
configs and refuses what is incomplete, inconsistent, reserved or malformed at start, naming the
variable. Two things change above the seam, both additive: an opaque **provider state** a response
may attach to its assistant message, handed back on the next call of the same turn and never
recorded; and the **provider name and model name** recorded with every AI message, with the
session's usage summed per provider. Every shape is proven offline against a loopback stand-in;
live suites run only with a key or a local server's address.

## Technical Context

**Language/Version**: Scala 3.9.0 on JDK 21 (the agent module, the sidecar, the test kit); Python,
TypeScript and Rust SDKs touched in documentation and conformance reference services only.

**Primary Dependencies**: `com.anthropic:anthropic-java` 2.61.0 (present); **new**
`com.openai:openai-java` 4.83.0, Apache-2.0, Kotlin-based, pulls Jackson 2.18.x, OkHttp 4.12,
kotlin-stdlib and kotlin-reflect (see research R1). Pekko streams for `stream`. No new dependency
anywhere else: the sidecar and the test kit see providers through `ankka-agent`.

**Storage**: the session memory journal (Postgres, through the event-sourced `SessionMemoryEntity`).
One additive change: `SessionMessage.AiMessage` gains optional `provider` and `model`;
`SessionHistory` gains `usageByProvider`. Old journals read unchanged (`SessionMemoryCompatibilitySuite`).

**Testing**: munit; `AnkkaTestKit` for service-level scenarios; a new loopback stand-in in the
agent module's main scope (`ModelStandIn`, as `TestMcpServer` is) speaking Messages, Responses and
Chat Completions with scripted replies and recorded requests; `ConformanceSuite` for the SDKs; live
suites gated by `OPENAI_API_KEY`, `ANTHROPIC_API_KEY` and `ANKKA_LOCAL_MODEL_URL`.

**Target Platform**: the JVM runtime and the sidecar image (Linux containers, amd64 and arm64); a
developer's machine for local model servers.

**Project Type**: platform library and sidecar within the existing sbt multi-module build.

**Performance Goals**: none beyond today's: a model call is bounded by the runtime's model timeout;
translation adds no measurable latency; id rewriting is per request, linear in the message count.

**Constraints**: no BSL on the path (openai-java and its transitive set are Apache-2.0, R1); the
sidecar protocol does not change (a model is named by a string already); `modules/core` keeps no
Pekko dependency (`PlatformVariables` gains one string); `sbt test` passes with no key and no
network; wire names and journal manifests unchanged; every refusal names the variable and value.

**Scale/Scope**: 16 presets; two protocols, three shapes; ~10 docs pages touched and two added; 9
new suites in `agent` (two of them key- or address-gated), 2 in `testkit`, changes to 5 in
`sidecar`, 1 conformance case across 4 reference services.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

`.specify/memory/constitution.md` is the unfilled template, so the gates are the repository's own
principles (`CLAUDE.md`, `ankka-brain/principles.md`):

| Principle | Gate | Status |
|---|---|---|
| No BSL on the path | openai-java and every transitive artifact Apache/MIT/BSD | Pass (R1); re-checked by `sbt agent/dependencyLicenseInfo`-style review in tasks |
| Adopt, then diverge with reasons | the vendor SDK is transport only; the divergences page gains the OpenAI model-settings row | Pass by design |
| Effects are inert data | nothing new runs at build time; a provider is a value; provider state is a value on a message | Pass |
| Explicit registration | a provider exists because it was configured or constructed; nothing is detected | Pass |
| Honest not-implemented list | the limitations page gains the compatible-endpoint section (FR-020) | Pass in design, checked by `ModelProvidersDocumentationSuite` |
| Only a tag publishes | no release changes; the sidecar image carries the new `Models` | Pass |
| Could this check pass while the thing is false? | every scenario maps to a test that fails without the feature (Project Structure, tests) | Pass in design |
| Same conventions | Justfile, spec-kit, living features, docs tool, skills | Pass |

No violations; Complexity Tracking stays empty. Re-evaluated after Phase 1: unchanged.

## Project Structure

### Documentation (this feature)

```text
specs/052-model-providers/
├── plan.md              # This file
├── research.md          # Phase 0: decisions R1–R14
├── data-model.md        # Phase 1: entities, fields, validation, journal change
├── quickstart.md        # Phase 1: how to prove it works
├── contracts/
│   ├── variables.md     # the sidecar's variable family, shorthands, refusals
│   ├── scala-api.md     # the Scala constructors and the factory
│   ├── presets.md       # the preset table and the quirks each carries
│   └── stand-in.md      # what the loopback stand-in speaks and records
└── tasks.md             # Phase 2 (/speckit-tasks)
```

### Source Code (repository root)

```text
modules/core/src/main/scala/com/thinkmorestupidless/ankka/core/
└── PlatformVariables.scala            # "OPENAI_" joins RuntimeOnlyPrefixes

modules/agent/src/main/scala/com/thinkmorestupidless/ankka/agent/
├── ModelProvider.scala                # unchanged trait; + ProviderState
├── messages.scala                     # + ChatMessage.Assistant.providerState, ModelResponse.providerState
├── AnthropicProvider.scala            # name parameter, `at(...)` builder, quirks for a compatible address
├── openai/
│   ├── OpenAiProvider.scala           # one class, two shapes; client, name, model, defaults, quirks
│   ├── ResponsesShape.scala           # ModelRequest ⇄ Responses API, reasoning items as provider state
│   └── ChatCompletionsShape.scala     # ModelRequest ⇄ Chat Completions, stream_options usage
├── providers/
│   ├── ModelProtocol.scala            # Anthropic | OpenAi; Shape: Responses | ChatCompletions
│   ├── ProviderConfig.scala           # name, protocol, shape, url, model, key, quirks; validation
│   ├── Presets.scala                  # the table (contracts/presets.md), one value per preset
│   ├── ModelProviders.scala           # configure(ProviderConfig): ModelProvider; name rules
│   └── ToolCallIds.scala              # rewriting under an id rule, consistent within a request
├── ModelStandIn.scala                 # main scope: loopback server speaking the three shapes
├── AgentLoop.scala                    # carries providerState within a turn; records provider + model
├── AgentRuntime.scala                 # refuses a reserved/malformed default name at start
├── SessionMemoryEntity.scala          # usageByProvider folded from AiMessageAdded
├── memory.scala                       # AiMessage.provider, AiMessage.model (optional)
├── PromptReplay.scala                 # unchanged shape; state never comes from history
└── autonomous/IterationLoop.scala     # records provider + model; carries state in memory between iterations

modules/agent/src/test/scala/com/thinkmorestupidless/ankka/agent/
├── openai/OpenAiProviderSuite.scala         # offline, against ModelStandIn: both shapes
├── openai/OpenAiProviderLiveSuite.scala     # OPENAI_API_KEY-gated
├── AnthropicCompatibleSuite.scala           # Messages at a loopback address, keyless
├── providers/ProviderConfigSuite.scala      # validation messages, name rules
├── providers/PresetsSuite.scala             # every preset against the stand-in (SC-007)
├── providers/ToolCallIdsSuite.scala
├── LocalModelServerSuite.scala              # ANKKA_LOCAL_MODEL_URL-gated plumbing check
└── ModelPresetsReferenceSuite.scala         # writes the generated preset table (docs)

modules/testkit/src/test/scala/com/thinkmorestupidless/ankka/testkit/
├── ModelProvidersSuite.scala                # US1, US4, US5, US6 scenarios with scripted providers
├── ModelProvidersDocumentationSuite.scala   # features/documentation/model-providers.feature
└── SessionMemoryCompatibilitySuite.scala    # golden JSON gains provider/model fields

sidecar/src/main/scala/com/thinkmorestupidless/ankka/sidecar/
├── Models.scala                       # fromEnv: shorthands, family, presets, default rule, refusals
├── RemoteAgent.scala                  # "no default" refusal names ANKKA_MODEL_DEFAULT and providers
└── RemoteAutonomousAgent.scala        # same for a definition naming none

sidecar/src/test/scala/com/thinkmorestupidless/ankka/sidecar/
├── ModelsSuite.scala                  # fromEnv: every FR-009/FR-010 refusal by message
├── RemoteAgentSuite.scala             # plans naming each configured name and nothing
├── ProcessDouble.scala                # plan(model = Some(...))
├── WasmHostSuite.scala                # reserved list gains OPENAI_API_KEY, ANKKA_MODEL_EU_KEY
└── conformance/{ConformanceSuite,ConformanceTarget,ConformanceReference}.scala  # agent.names-a-model

sdks/python/examples/shopping_cart/conformance.py      # route naming the second model
sdks/typescript/examples/shopping-cart/conformance.ts
sdks/rust/examples/shopping-cart/src/conformance.rs
specs/009-polyglot-runtimes/contracts/conformance.md   # the case documented

modules/core/src/test/.../PlatformVariablesSuite.scala
controlplane/src/test/.../PlatformDeclarationSuite.scala   # literal scan includes OPENAI_
operator/src/test/.../ProcessHostingRenderingSuite.scala   # OPENAI_API_KEY goes to the sidecar

project/Dependencies.scala, build.sbt                       # openai-java on `agent`
justfile                                                    # docs-reference runs ModelPresetsReferenceSuite
mkdocs.yml                                                  # nav + generated block `model-presets`

docs/
├── build/agents.md                    # "Choosing a model" rewritten: every way, default rule, settings table
├── reference/model-providers.md       # NEW: variable family, presets (generated), per-protocol settings
├── deploy/local-models.md             # NEW: Ollama, LM Studio, Docker Model Runner answering an agent
├── reference/limitations.md           # what a compatible endpoint does not give
├── reference/akka-divergences.md      # model settings on both protocols
├── reference/configuration.md         # Agents and models section
├── reference/service-descriptor.md    # routing table gains OPENAI_
├── deploy/deploy-a-service.md, deploy/run-locally.md, concepts/polyglot.md, concepts/agents.md,
│   concepts/designing-agents.md, reference/{scala,python,typescript,rust}-sdk.md, reference/wasm-abi.md
└── (skills) tools/docs/skill/{ankka-agents,ankka-python,ankka-typescript,ankka-rust,ankka-deploy}/SKILL.md
```

**Structure Decision**: everything provider-shaped lives in `modules/agent` under two new packages
(`openai`, `providers`) so the Scala SDK, the sidecar and the test kit share one factory and one
preset table; the sidecar keeps only environment parsing. The stand-in sits in the agent module's
main scope, as `TestMcpServer` does, because `sidecar` and `testkit` tests need it and `agent`
tests cannot see `testkit`.

## Design

### The seam stays; two values cross it

`ModelProvider` keeps its two methods. `ModelResponse` gains `providerState: Option[ProviderState]`
and `ChatMessage.Assistant` gains `providerState: Option[ProviderState] = None`, where
`ProviderState(provider: String, value: String)` is opaque to everything but the provider that made
it. `AgentLoop.step` builds the assistant message from the response as today and copies the state
onto it; the next request of the same turn carries it in `messages`; `recorded(...)` builds
`SessionMessage.AiMessage` without it, so it is never persisted. A provider ignores a state whose
`provider` is not its own name. `PromptReplay` cannot produce one, so a resumed or continued turn
starts clean, as the spec says.

### OpenAiProvider

One class, a `Shape`:

- **Responses** (`store = false`, `include = ["reasoning.encrypted_content"]`, no
  `previous_response_id`): an assistant message with provider state is replayed as the response's
  own output items verbatim (the state's value is the JSON of the `output` array: reasoning items,
  the message item, the function_call items), followed by `function_call_output` items for the tool
  results. Without state it is translated: a `message` item with `output_text` and one
  `function_call` per tool call. Tool results map to `function_call_output` by `call_id`. Refusal is
  the `refusal` content part → `StopReason.Refusal` with its text. `incomplete_details.reason ==
  "max_output_tokens"` → `MaxTokens`. Usage from `input_tokens`, `input_tokens_details.cached_tokens`,
  `output_tokens`. Streaming through `ResponseAccumulator`; text deltas from
  `response.output_text.delta`; tool calls complete in the terminal chunk.
- **Chat Completions**: `messages` with `system`, `user`, `assistant` (`tool_calls` with
  `function.arguments` as a JSON string), `tool` (one per result, `tool_call_id`). Refusal is
  `message.refusal`; `finish_reason` `length` → `MaxTokens`, `tool_calls` → `ToolUse`. Streaming
  with `stream_options.include_usage` when the quirks allow; `ChatCompletionAccumulator` builds the
  terminal response; usage from `prompt_tokens_details.cached_tokens`.

Settings: `effort` → `reasoning.effort` (Responses) or `reasoning_effort` (Chat Completions) mapped
`Low→low, Medium→medium, High→high, XHigh→xhigh, Max→max`, clamped to the highest the quirks list;
`thinking = false` → the lowest the quirks list (`none`, else `minimal`, else `low`), or nothing on a
bare endpoint; `temperature` sent only where the quirks accept it (not on OpenAI's own reasoning
models); `maxTokens` → `max_output_tokens` / `max_completion_tokens`. ankka never sets `tool_choice`.

A keyless address gets the placeholder bearer token `ModelProviders.NoKey` (`"none"`), because the
SDK refuses to build a client with no credential and local servers ignore the header (R3). A
provider built `fromEnv` with its key unset exists and fails every call naming the variable, so a
Scala service starts with either key missing and refuses only the work that needs it (R15).

### AnthropicProvider at an address

Gains `name` (default `anthropic`) and `AnthropicProvider.at(name, address, modelName, key)`
over `AnthropicOkHttpClient.builder().baseUrl(...)`. At a vendor's own address nothing changes. At
another address the quirks decide whether adaptive thinking and `output_config.effort` are sent;
the `anthropic` preset says yes, a hand-given address says no (R8).

### The factory and the presets

`ProviderConfig(name, protocol: Option, shape: Option, url: Option, model: Option, key: Option,
preset: Option)` is what the sidecar parses and what a Scala service may build;
`ProviderConfig.resolve` applies the preset underneath the given values and yields either a problem
naming the field or a `ResolvedConfig`; `ModelProviders.configure(resolved)` returns the provider.
`Presets.all: Map[String, Preset]` is the one table (contracts/presets.md); the documentation's
preset table is generated from it by `ModelPresetsReferenceSuite`, as the CLI reference is.

### Names

`ModelProviders.validName`: `^[a-z][a-z0-9]*$`, not in `Reserved = Set("scripted", "default",
"name", "script")`. The sidecar refuses at start; the Scala builders throw `IllegalArgumentException`
at construction, and `AgentRuntime.start` refuses a default whose name fails the rule, so a Scala
service that builds one by hand is refused when the runtime starts.

### The sidecar's table

`Models.fromEnv` now: (1) shorthands → `anthropic` (preset `anthropic`, key `ANTHROPIC_API_KEY`,
model `ANKKA_MODEL_NAME`) and `openai` (preset `openai`, key `OPENAI_API_KEY`); (2) every
`ANKKA_MODEL_<NAME>_<SETTING>` with a known setting merged over the shorthand of the same name;
`ANKKA_MODEL_NAME` and `ANKKA_MODEL_ANTHROPIC_MODEL` are one setting and differing values are
refused; (3) an `ANKKA_MODEL_` variable that is neither a shorthand nor a known setting is refused,
naming it; (4) each config resolved and built, or refused with the variable and value; (5) the
default: the only real provider, else `ANKKA_MODEL_DEFAULT` (must name a configured provider), else
none with a reason. `Models` gains `noDefault: Option[String]`, the message `RemoteAgent` and
`RemoteAutonomousAgent` answer a plan or definition naming no model. A script beside real providers
is still `scripted`, never the default.

### Usage per provider

`SessionMessage.AiMessage` gains `provider: Option[String]` and `model: Option[String]`;
`SessionMemoryEvent.AiMessageAdded` is unchanged in shape; the entity folds `usage` into
`SessionHistory.usageByProvider(provider)` when the message names one. The loop and the iteration
loop set both from the provider that answered. The local console keeps its columns and reads the
total as before.

### Tool call ids

`ToolCallIds.rewrite(messages, rule)` maps each id in the request to one of the rule's shape,
deterministically from the original (a base-36 digest cut to the rule's length), the same for a
call and its result; applied inside the provider on the way out only. Ids the model returns are
kept as given.

### Tests, by scenario

Agent features are tested by munit cases named after their scenarios (the repository's practice
for agent features); the documentation feature by a `*DocumentationSuite`. Descriptor-level
scenarios ("a member applies the descriptor … lifecycle Failed … detail names …") are proven at the
level that produces the detail: `Models.fromEnv` throwing the message the detail carries, as
`ANKKA_MODEL_SCRIPT` refusals are today. The k3s suites do not grow. The mapping is in
quickstart.md.

## Complexity Tracking

No constitution violations to justify.
