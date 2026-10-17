# Quickstart: proving model providers work

How to see the feature working, from the cheapest check to a real vendor. Contracts and the data
model are in [contracts/](contracts/) and [data-model.md](data-model.md); this page only says what
to run and what to expect.

## Prerequisites

- Docker (the integration suites share a Postgres per test JVM).
- No key, for everything in "Offline".
- Optional: `OPENAI_API_KEY`, `ANTHROPIC_API_KEY`, or `ANKKA_MODEL_<PRESET>_KEY` for a live suite;
  Ollama (or LM Studio, Docker Model Runner) running for the local suite.

## Offline (what `sbt test` runs)

```bash
sbt 'agent/testOnly *OpenAiProviderSuite'          # both shapes against ModelStandIn: translation,
                                                   # streaming, tool calls, refusal, length, usage,
                                                   # settings withheld, failures, provider state
sbt 'agent/testOnly *AnthropicCompatibleSuite'     # Messages at a loopback address, no key
sbt 'agent/testOnly *PresetsSuite'                 # every preset configured against the stand-in
sbt 'agent/testOnly *ProviderConfigSuite *ToolCallIdsSuite'
sbt 'testkit/testOnly *ModelProvidersSuite'        # two scripted providers in one service: US1, US4, US5, US6
sbt 'testkit/testOnly *ModelProvidersDocumentationSuite *SessionMemoryCompatibilitySuite'
sbt 'sidecar/testOnly *ModelsSuite *RemoteAgentSuite'   # fromEnv refusals by message; plans naming models
sbt 'sidecar/testOnly *ConformanceSuite -- *agent.names-a-model'
sbt 'core/testOnly *PlatformVariablesSuite' 'controlPlane/testOnly *PlatformDeclarationSuite'
sbt 'sidecar/testOnly *WasmHostSuite'              # OPENAI_API_KEY withheld from a module
```

Expected: every suite green with no network; the live suites report as skipped, not passed.

## Live (only with a key)

```bash
OPENAI_API_KEY=sk-... sbt 'agent/testOnly *OpenAiProviderLiveSuite'
ANKKA_MODEL_GROQ_KEY=gsk_... sbt 'agent/testOnly *GroqLiveSuite'      # one per vendor preset
```

Expected: a plain answer with `EndTurn` and non-zero usage; a declared tool called with well-formed
arguments; a tool result round-tripping into an answer; for `openai`, a turn with a tool call while
reasoning completes (the state was replayed).

## A local model server

```bash
ollama pull qwen3:8b
ANKKA_LOCAL_MODEL_URL=http://localhost:11434/v1 ANKKA_LOCAL_MODEL_NAME=qwen3:8b \
  sbt 'agent/testOnly *LocalModelServerSuite'
```

Expected: one agent turn answered, one tool run, usage recorded (zero is accepted when the server
reports none). Skipped without the address.

## A Python service with two providers, by hand

In a process-hosted service's descriptor:

```json
"env": [
  {"name": "ANTHROPIC_API_KEY", "valueFrom": {"secretKeyRef": {"name": "models", "key": "anthropic"}}},
  {"name": "OPENAI_API_KEY",    "valueFrom": {"secretKeyRef": {"name": "models", "key": "openai"}}},
  {"name": "ANKKA_MODEL_DEFAULT", "value": "openai"},
  {"name": "ANKKA_MODEL_LOCAL_PRESET", "value": "ollama"},
  {"name": "ANKKA_MODEL_LOCAL_MODEL",  "value": "qwen3:8b"}
]
```

In the agent: `effects.model("anthropic")` on the one that stays on Claude; `effects.model("local")`
on the one for the laptop; nothing on the rest. Expected: each answered by the named provider; the
process never sees a key (`ankka services logs` shows the sidecar naming `openai`, `anthropic`,
`local`, never a vendor the developer did not name); `ANKKA_MODEL_DEFAULT` unset with both keys
makes an unnamed call `Unavailable` with a message naming the variable and the providers, and the
service stays `Ready`.

## A Scala service with two providers

```scala
val triage   = OpenAiProvider.fromEnv()                      // OPENAI_API_KEY
val drafting = AnthropicProvider.fromEnv()                   // ANTHROPIC_API_KEY
val local    = ModelProviders.named("local", preset = Some("ollama"), model = Some("qwen3:8b"))

AgentRuntime.withDefaultModel(drafting)
// in a handler: effects.model(triage) ...
```

Expected in the test kit (`ModelProvidersSuite`): with two `TestModelProvider`s in the places above,
each agent consumes its own script; a session begun on one is continued by the other with the tool
call and result in the second's request; `history.usageByProvider` holds both names.

## Documentation

```bash
just docs-reference        # regenerates the preset table from Presets.all
just docs                  # every page builds; prose names every preset and variable
```

Expected: `docs/reference/model-providers.md` lists sixteen presets, the variable family and the
per-protocol settings table; `docs/deploy/local-models.md` gets Ollama answering in a few lines;
the limitations page says what a compatible endpoint does not give.

## Scenario to test map

| Living scenario (file: name) | Proved by |
|---|---|
| model-providers: a Scala service runs one agent on OpenAI and another on Anthropic; a handler naming a model provider is answered by that one and no other; a blueprint runs one worker on each; an autonomous agent's definition names openai | `testkit/ModelProvidersSuite`, two scripted providers under those names |
| model-providers: a service with the key of one model provider alone refuses only the agent that names the other | `testkit/ModelProvidersSuite` with `OpenAiProvider.fromEnv()` built from an environment map without the key (the unavailable provider, research R15) |
| model-providers: an OpenAI model's refusal is the caller's refusal | `agent/OpenAiProviderSuite` (refusal part → `Refusal`) and `testkit/ModelProvidersSuite` (caller `Forbidden`, no answer recorded) |
| model-providers: the four "written in another language" scenarios | `sidecar/RemoteAgentSuite` and `RemoteAutonomousAgentSuite` with `Models` holding two providers, plans naming each and none, with and without a default; `ConformanceSuite` `agent.names-a-model` per SDK; `WasmHostSuite`/`ProcessHostingRenderingSuite` for the keys never reaching the process |
| model-providers: the three session scenarios (continues, tool call sent to the other, usage added) | `testkit/ModelProvidersSuite` |
| model-providers: the two provider-state scenarios | `agent/OpenAiProviderSuite` with `requireReasoningReplay()`, and `testkit/ModelProvidersSuite` asserting the recorded messages carry none |
| compatible-endpoints: configured by name from four values; a Scala service builds from the same four; the name is the service's in logs, failures and usage; usage recorded as none; a setting withheld | `sidecar/ModelsSuite` + `RemoteAgentSuite` against the stand-in; `agent/OpenAiProviderSuite`; `testkit/ModelProvidersSuite` |
| compatible-endpoints: at an address of the developer's own answers with tool calls | `agent/LocalModelServerSuite` (address-gated) and offline against the stand-in |
| compatible-endpoints: the refusals at the service's start (three outlines) and the preset refusals | `sidecar/ModelsSuite`, one test per example row, asserting the message in contracts/variables.md |
| compatible-endpoints: the five preset scenarios; a value beside a vendor's key overrides; the double model refused | `agent/PresetsSuite`, `sidecar/ModelsSuite` |
| compatible-endpoints: a tool call's id is rewritten for a provider that constrains it | `agent/ToolCallIdsSuite` and `OpenAiProviderSuite` with the `mistral` preset against the stand-in |
| documentation: the five scenarios | `testkit/ModelProvidersDocumentationSuite` reading the published pages |
| platform-settings: a platform setting a descriptor gives is kept from the process | `operator/ProcessHostingRenderingSuite`, `sidecar/WasmHostSuite` with `OPENAI_API_KEY` and `ANKKA_MODEL_EU_KEY` added |
