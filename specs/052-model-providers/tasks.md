# Tasks: Model Providers — OpenAI, Compatible Endpoints and Named Configurations

**Input**: Design documents from `specs/052-model-providers/`

**Prerequisites**: plan.md, spec.md, research.md (R1–R15), data-model.md, contracts/ (variables,
scala-api, presets, stand-in), quickstart.md

**Tests**: included. The spec requires them (FR-017, FR-018, FR-019, SC-005, SC-007): every shape
proven offline against a stand-in, live suites gated by a key, the sidecar's choice of provider and
every refusal proven by message. Write each test first and watch it fail; a scenario-named test
that passes before its feature exists is checking nothing.

**Organization**: by user story, in the spec's priority order. Paths are repository-relative.
Scala packages are under `com/thinkmorestupidless/ankka/`, abbreviated `…/ankka/` below. Message
wording comes from `contracts/variables.md`; signatures from `contracts/scala-api.md`.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no dependency on an unfinished task)
- **[Story]**: US1–US8 from spec.md

---

## Phase 1: Setup

**Purpose**: the dependency and the routing rule every story needs.

- [ ] T001 Add `openaiJava = "com.openai" % "openai-java" % V.openai` with `V.openai = "4.83.0"` in `project/Dependencies.scala` beside `anthropicJava`, and add it to the `agent` module's `libraryDependencies` in `build.sbt` (the line that lists `anthropicJava`)
- [ ] T002 Run `sbt agent/evicted` and `sbt agent/compile`; record in `specs/052-model-providers/research.md` (R1, "verified") the resolved Jackson, OkHttp and Kotlin versions and that no artifact outside Apache/MIT/BSD entered the classpath (read the POMs of anything new in `~/.cache/coursier` or `sbt agent/dependencyTree`); fix any eviction warning that `-Werror` turns into a failure
- [ ] T003 [P] Add `"OPENAI_"` to `RuntimeOnlyPrefixes` in `modules/core/src/main/scala/…/ankka/core/PlatformVariables.scala` with a comment naming this feature, and extend the prefix test in `modules/core/src/test/scala/…/ankka/core/PlatformVariablesSuite.scala` (lines ~93–94 and ~166) to assert `runtimeOnly("OPENAI_API_KEY")` and `runtimeOnly("ANKKA_MODEL_EU_KEY")`
- [ ] T004 [P] Extend the literal scan in `controlplane/src/test/scala/…/ankka/controlplane/PlatformDeclarationSuite.scala` (line ~66 and the fixture at ~102–103) so a literal starting `OPENAI_` outside `PlatformVariables.scala` fails the suite, as `ANTHROPIC_` does
- [ ] T005 [P] Add `EnvEntry("OPENAI_API_KEY", …)` and `EnvEntry("ANKKA_MODEL_EU_KEY", …)` to the descriptor fixture in `operator/src/test/scala/…/ankka/operator/ProcessHostingRenderingSuite.scala` and assert both land on the sidecar container and never on the app container, in the test named `a platform setting a descriptor gives is kept from the process`
- [ ] T006 [P] Add `OPENAI_API_KEY` and `ANKKA_MODEL_EU_KEY` to the reserved-name list in `sidecar/src/test/scala/…/ankka/sidecar/WasmHostSuite.scala` (lines ~335–355) so the module's `config` import is proven to answer them absent

---

## Phase 2: Foundational

**Purpose**: the values that cross the seam, the factory, the stand-in and the OpenAI adapter.
Nothing in a story phase compiles without these.

**⚠️ CRITICAL**: complete before any user story.

### Values above the seam

- [ ] T007 Add `final case class ProviderState(provider: String, value: String)` to `modules/agent/src/main/scala/…/ankka/agent/ModelProvider.scala`; add `providerState: Option[ProviderState] = None` to `ModelResponse` and to `ChatMessage.Assistant` in `modules/agent/src/main/scala/…/ankka/agent/messages.scala` (no serializer: neither type is a journal type)
- [ ] T008 Add `provider: Option[String] = None` and `model: Option[String] = None` to `SessionMessage.AiMessage` in `modules/agent/src/main/scala/…/ankka/agent/memory.scala`; add `usageByProvider: Map[String, TokenUsage] = Map.empty` to `SessionHistory` in `modules/agent/src/main/scala/…/ankka/agent/SessionMemoryEntity.scala` and fold `AiMessageAdded(message, usage)` into it when `message.provider` is defined (both the event handler and the snapshot state); `TokenUsage` and `SessionHistory` serializers unchanged
- [ ] T009 Update the golden journal in `modules/testkit/src/test/resources/journal/session-memory.json` and the fixtures in `modules/testkit/src/test/scala/…/ankka/testkit/SessionMemoryCompatibilitySuite.scala`: one `AiMessage` with `provider = Some("openai"), model = Some("gpt-6.1-sol")`, one without; a `SessionHistory` with `usageByProvider`; and a test that a line written before this feature (no `provider`, `model`, `usageByProvider` keys) still reads, with the defaults
- [ ] T010 Set `provider = Some(provider.name)` and `model = Some(provider.modelName)` on every `SessionMessage.AiMessage` the request loop records in `modules/agent/src/main/scala/…/ankka/agent/AgentLoop.scala` (`recorded(...)`, which needs the provider passed in from `writeHistory`'s callers) and the iteration loop records in `modules/agent/src/main/scala/…/ankka/agent/autonomous/IterationLoop.scala` (`callModel`); in the same file replace the `model.name == "test"` check at line ~177 with a structural one (`model.isInstanceOf[TestModelProvider]`), and give `TestModelProvider` in `modules/agent/src/main/scala/…/ankka/agent/TestModelProvider.scala` a `name: String = "test"` constructor parameter so a story's scripted provider can be called `openai` without losing the fail-fast path (analysis I1)

### The providers package

- [ ] T011 [P] Create `modules/agent/src/main/scala/…/ankka/agent/providers/ModelProtocol.scala`: `enum ModelProtocol { Anthropic, OpenAi }` with `wireName` (`anthropic`, `openai`) and `fromWire`; `enum Shape` lives on `OpenAiProvider` (T020) and gets `wireName` (`responses`, `chat-completions`) and `fromWire`
- [ ] T012 [P] Create `modules/agent/src/main/scala/…/ankka/agent/providers/Quirks.scala`: `Quirks(reasoningEfforts: Option[Vector[String]], acceptsTemperature: Boolean, streamUsage: Boolean, toolCallIds: Option[IdRule], sendsThinking: Boolean)` with `Quirks.compatibleOpenAi`, `Quirks.compatibleAnthropic`, `Quirks.openAi` (no temperature; efforts none…max), `Quirks.anthropic` (sendsThinking); `enum IdRule { Alphanumeric(length: Int) }` per data-model.md
- [ ] T013 [P] Create `modules/agent/src/main/scala/…/ankka/agent/providers/Preset.scala` and `Presets.scala`: `Preset(name, protocol, shape, address: Option[String], keyRequired, defaultModel: Option[String], quirks, asOf)` and `Presets.all` holding the sixteen rows of `contracts/presets.md` with the local presets' `defaultModel = None`, `bedrock`/`azure` `address = None`, and vendor default models as named there (`⁺` rows: read the vendor's models page, record the model and the date in `asOf`; likewise read each vendor's documented `reasoning_effort` values into `quirks.reasoningEfforts`, leaving `None` where the vendor documents none, analysis U3); `Presets.named(name)`
- [ ] T014 [P] Create `modules/agent/src/main/scala/…/ankka/agent/providers/ToolCallIds.scala`: `under(rule)(id)` (SHA-256 of the id, base-36, cut to the rule's length, lower-cased; an id already of the shape maps to itself) and `rewrite(messages, rule)` replacing `ToolCall.id` and `ToolResult.callId` consistently
- [ ] T015 Create `modules/agent/src/main/scala/…/ankka/agent/providers/ProviderConfig.scala`: `ProviderConfig(name, protocol, shape, url, model, key, preset)` with `resolve: Either[String, ResolvedConfig]` implementing every check and message of `contracts/variables.md` (reserved and malformed names, unknown protocol/shape/preset, shape on the Anthropic protocol, non-URL address, missing protocol/address/model after the preset, key required; an empty key is unset); `ResolvedConfig(name, protocol, shape, url, model, key: Option[String], quirks)` (depends on T011–T013)
- [ ] T016 Create `modules/agent/src/main/scala/…/ankka/agent/providers/ModelProviders.scala`: `NoKey = "none"`, `Reserved`, `validName`, `configure(resolved): ModelProvider` choosing `AnthropicProvider.at` or `OpenAiProvider.at` by protocol, `named(name, protocol, url, model, key, preset, shape)` (resolve, then configure, throwing `IllegalArgumentException` with the problem), and `unavailable(name, modelName, reason): ModelProvider` whose `complete` and `stream` fail with `ModelCallFailed(name, reason)` (R15) (depends on T015, T019, T020)
- [ ] T017 [P] Write `modules/agent/src/test/scala/…/ankka/agent/providers/ProviderConfigSuite.scala`: one test per row of the refusal table in `contracts/variables.md` that `resolve` can produce, asserting the exact message; a preset's values applied underneath given values; `Some("")` key treated as none; `validName` accepts `local`, `fast`, `claude2`, refuses `Fast`, `my_model`, `local-fast`, `2fast`, and every reserved name
- [ ] T018 [P] Write `modules/agent/src/test/scala/…/ankka/agent/providers/ToolCallIdsSuite.scala`: `toolu_01ABC` maps to nine alphanumerics, the same every time; a call and its result in different messages get the same id; a nine-alphanumeric id maps to itself; messages are otherwise unchanged

### The adapters

- [ ] T019 Change `modules/agent/src/main/scala/…/ankka/agent/AnthropicProvider.scala`: a `name` constructor parameter (default `"anthropic"`) returned by `def name`; `apply(client, modelName, defaults, name)`; `at(name, address, modelName, key, defaults, quirks)` over `AnthropicOkHttpClient.builder().baseUrl(address).apiKey(key.getOrElse(ModelProviders.NoKey))`; adaptive thinking and `output_config.effort` sent only when `quirks.sendsThinking`; `fromEnv(modelName, defaults)` and a new overload `fromEnv(env: Map[String, String], modelName, defaults)` return `ModelProviders.unavailable("anthropic", modelName, "ANTHROPIC_API_KEY is not set")` with a warning log when the variable is unset or empty (R15, analysis A1); `withApiKey` unchanged. T016, T019 and T020 reference each other and land in one compile (analysis I5)
- [ ] T020 Create `modules/agent/src/main/scala/…/ankka/agent/openai/OpenAiProvider.scala`: `final class OpenAiProvider private (client: OpenAIClient, val name: String, val modelName: String, shape: Shape, defaults: Defaults, quirks: Quirks)`; `enum Shape { Responses, ChatCompletions }`; `DefaultModel = "gpt-6.1-sol"`; `Defaults` as Anthropic's; `complete` and `stream` delegating to the shape (T021, T022) on a virtual-thread executor and the blocking-io dispatcher exactly as `AnthropicProvider` does; `fromEnv` and `fromEnv(env: Map[String, String], …)` (unavailable without `OPENAI_API_KEY`, R15), `withApiKey`, `apply(client, modelName, shape, defaults, name)`, `at(name, address, modelName, key, shape, defaults, quirks)` over `OpenAIOkHttpClient.builder().baseUrl(address).apiKey(key.getOrElse(ModelProviders.NoKey))`; applies `ToolCallIds.rewrite` when `quirks.toolCallIds` is set; the settings mapping of research R6 shared by both shapes in a private `Settings` helper; every `ModelCallFailed` is built from the provider's `name`, the HTTP status and a short body summary, never from the SDK exception's class name or message, so a failure of `local` never says "openai" (analysis I2)
- [ ] T021 Create `modules/agent/src/main/scala/…/ankka/agent/openai/ResponsesShape.scala`: `ModelRequest → ResponseCreateParams` (`store(false)`, `include(REASONING_ENCRYPTED_CONTENT)`, `instructions` from the system message, `input` items: user messages, assistant messages as a `message` item plus `function_call` items, or the provider state's output array replayed verbatim when the `Assistant` carries one whose `provider == name`, then `function_call_output` per tool result by `call_id`; tools as function tools with the JSON Schema; `max_output_tokens`; `reasoning.effort`); `Response → ModelResponse` (output text, `function_call` items with parsed arguments, `refusal` part → `Refusal` with its text, `incomplete_details.reason == max_output_tokens` → `MaxTokens`, usage with `input_tokens_details.cached_tokens` as cache reads, absent usage as zero, `providerState = Some(ProviderState(name, outputArrayJson))`); streaming through `ResponseAccumulator` emitting `TextDelta` on `response.output_text.delta`, `ToolCallStarted` on `response.output_item.added` of a function call, `Completed` with the accumulated response
- [ ] T022 Create `modules/agent/src/main/scala/…/ankka/agent/openai/ChatCompletionsShape.scala`: `ModelRequest → ChatCompletionCreateParams` (`system`, `user`, `assistant` with `tool_calls` whose `function.arguments` is the rendered JSON string, one `tool` message per result with `tool_call_id`; function tools; `max_completion_tokens`; `reasoning_effort` per R6; `temperature` when `quirks.acceptsTemperature`; `stream_options.include_usage` when `quirks.streamUsage`); `ChatCompletion → ModelResponse` (`message.content`, `tool_calls` with arguments parsed from the string, `message.refusal` → `Refusal`, `finish_reason` `length` → `MaxTokens`, `tool_calls` → `ToolUse`, usage with `prompt_tokens_details.cached_tokens`, absent usage as zero); streaming through `ChatCompletionAccumulator`, tool calls complete only in the terminal `Completed` chunk
- [ ] T023 Create `modules/agent/src/main/scala/…/ankka/agent/ModelStandIn.scala` (main scope, beside `TestMcpServer`) implementing `contracts/stand-in.md`: a JDK `HttpServer` on `127.0.0.1:0`, the three paths, scripted `ModelResponse`s rendered per shape, SSE when `stream: true` (including the Chat Completions final usage chunk when asked), `reportNoUsage`, `rejectSetting`, `failNext`, `hang`/`release`, `requireReasoningReplay` (Responses: issue a `reasoning` item with an `rs_` id and `encrypted_content` before each `function_call`; a later request whose `function_call_output` is not preceded by that reasoning item is answered 400), `requests: Vector[Received]` with the body as `Json`, 418 when nothing is scripted
- [ ] T024 Write `modules/agent/src/test/scala/…/ankka/agent/openai/OpenAiProviderSuite.scala` against `ModelStandIn`, for each shape (a test per line, named so the shape is in the name): a plain answer with `EndTurn` and usage in ankka's units (cached tokens as cache reads); a tool call with parsed arguments and `ToolUse`; a tool result round-trip (the request's `tool`/`function_call_output` pairs the id); a refusal → `Refusal` with its text; a length stop → `MaxTokens`; absent usage → zero; streaming: text deltas arrive before `Completed` and `Completed` carries the tool calls and usage a completion would; `effort` mapping and clamping per R6; `thinking = false` → lowest effort or nothing; `temperature` withheld under `Quirks.openAi`, sent under `Quirks.compatibleOpenAi`; a 429 and a 500 → `ModelCallFailed` naming the provider's name and containing neither "openai" nor the SDK's class names when the provider is named `local`; a 400 for an unknown model → `ModelCallFailed` (never a start-time failure); a streamed tool call whose arguments do not parse → the tool call with empty arguments, left to the loop's tool-error path; no request carries `tool_choice`; the `Authorization` header carries the key, or `none` when keyless; `name` is the name given; the request names the configured model (analysis C1, C2, I2)
- [ ] T025 [P] Write `modules/agent/src/test/scala/…/ankka/agent/AnthropicCompatibleSuite.scala`: `AnthropicProvider.at("local", standIn.address, "qwen3:8b", None)` answers, round-trips a tool call, reports usage, sends `Bearer none`, and sends neither `thinking` nor `output_config` under `Quirks.compatibleAnthropic` but both under `Quirks.anthropic`
- [ ] T026 [P] Write `modules/agent/src/test/scala/…/ankka/agent/openai/OpenAiProviderLiveSuite.scala` gated by `OPENAI_API_KEY` (`assume`, as `AnthropicProviderSuite`): a plain answer with non-zero usage; a declared tool called with well-formed arguments; a tool result round-tripping into an answer; a turn with a tool call while `thinking` is on completing (the reasoning item replayed) against `OpenAiProvider.DefaultModel`; a replayed history holding an Anthropic-shaped tool call id (`toolu_01ABC…`) and its result accepted by the Responses shape (analysis U4: if the API refuses it, give the `openai` preset an id rule in T013 and say so in the spec's Assumptions)

**Checkpoint**: `sbt agent/test` green with no key; both adapters build from a name, an address, a model and an optional key.

---

## Phase 3: User Story 1 — Two vendors, two agents, one Scala service (Priority: P1) 🎯 MVP

**Goal**: a Scala service constructs both providers from the environment, hands one to the runtime as the default and names the other on one handler; each call is made by the chosen provider; the service starts with either key missing and refuses only the work that needs it.

**Independent Test**: `sbt 'testkit/testOnly *ModelProvidersSuite'` with two scripted providers, plus `OpenAiProviderSuite`'s refusal case.

- [ ] T027 [US1] In `modules/agent/src/main/scala/…/ankka/agent/AgentRuntime.scala` `start`, refuse (throw `IllegalArgumentException` naming the provider and the rule) a default model whose `name` fails `ModelProviders.validName`, and log the default's `name` and `modelName` as judgments are logged today
- [ ] T028 [US1] Write `modules/testkit/src/test/scala/…/ankka/testkit/ModelProvidersSuite.scala` with two `TestModelProvider`s named `openai` and `anthropic` through the `name` parameter T010 adds (no wrapper, analysis A2), the runtime default `anthropic`, agents `triage` (names `openai`) and `drafting` (names none), and tests named: `a Scala service runs one agent on OpenAI and another on Anthropic` (each script consumed by its own agent); `a handler naming a model provider is answered by that model provider and no other` (the other's `callCount` is 0); `an OpenAI model's refusal is the caller's refusal, not an answer` (scripted `Refusal` → `Forbidden` with the reason; the session holds no AI message for the turn)
- [ ] T029 [US1] Add to `ModelProvidersSuite`: `a service with the key of one model provider alone refuses only the agent that names the other`: `OpenAiProvider.fromEnv(Map.empty)` (the `env` overload of T020), the runtime starts, `drafting` on the scripted default answers, `triage` is `Unavailable` with a message naming `OPENAI_API_KEY`, and `AgentRuntime.start` logged the warning; and `a model provider configured with a reserved name is refused at the service's start` for Scala: `AgentRuntime.withDefaultModel(TestModelProvider(name = "default"))` fails `start` naming the name
- [ ] T030 [P] [US1] Simplify `samples/shopping-cart/src/main/scala/Main.scala` (lines ~42–156): drop the key guard around `AnthropicProvider.fromEnv()` now that it builds without a key, keep `CART_CALLING_AGENT` and `RelayModel` as they are, and update the comment; confirm `sbt shoppingCart/test` passes
- [ ] T031 [P] [US1] Add a compiled, included sample for the guide: a `// docs:start two-providers` region in `modules/testkit/src/test/scala/…/ankka/testkit/ModelProvidersSuite.scala` (or a new `ModelProviderSamples.scala` beside `blueprint/GuideSamples.scala`) building `OpenAiProvider.fromEnv()`, `AnthropicProvider.fromEnv()`, `ModelProviders.named("local", preset = Some("ollama"), model = Some("qwen3:8b"))`, handing one to `AgentRuntime.withDefaultModel` and naming another with `effects.model(...)`

**Checkpoint**: User Story 1 proven by `ModelProvidersSuite` and `OpenAiProviderSuite`; a Scala service can run two vendors.

---

## Phase 4: User Story 2 — A process-hosted service names its model providers (Priority: P1)

**Goal**: `OPENAI_API_KEY` beside `ANTHROPIC_API_KEY` on the sidecar, a default named, a handler naming either, an autonomous definition naming `openai`; the process never holds a key.

**Independent Test**: `sbt 'sidecar/testOnly *ModelsSuite *RemoteAgentSuite *RemoteAutonomousAgentSuite'` and `sbt 'sidecar/testOnly *ConformanceSuite -- *agent.names-a-model'`.

- [ ] T032 [US2] Rewrite `Models.fromEnv` in `sidecar/src/main/scala/…/ankka/sidecar/Models.scala` to build `ProviderConfig`s: shorthands (`ANTHROPIC_API_KEY` → preset `anthropic` with `ANKKA_MODEL_NAME` as model; `OPENAI_API_KEY` → preset `openai`), `ANKKA_MODEL_SCRIPT` → `scripted` unchanged, resolve each through `ProviderConfig.resolve` and `ModelProviders.configure`, throwing `IllegalArgumentException` with the variable prefixed to the problem per `contracts/variables.md`; add `noDefault: Option[String]` to `Models`; the default rule of data-model.md (one real provider; `ANKKA_MODEL_DEFAULT`, refused at start when it names an unconfigured provider; else `noDefault` with the message naming the variable and the configured names); keep `Models.only` and `Models.none`
- [ ] T033 [US2] In `sidecar/src/main/scala/…/ankka/sidecar/RemoteAgent.scala` (lines ~64–78) and `sidecar/src/main/scala/…/ankka/sidecar/RemoteAutonomousAgent.scala` (line ~170), answer a plan or definition naming no model with `models.noDefault`'s message when set, and replace the "set ANTHROPIC_API_KEY" message with the call-time wording of `contracts/variables.md`; the unknown-name message stays
- [ ] T034 [US2] Extend `sidecar/src/test/scala/…/ankka/sidecar/ModelsSuite.scala` with `fromEnv` tests over an env map: both shorthands give `anthropic` and `openai` with `noDefault` set and naming both; `ANKKA_MODEL_DEFAULT=openai` picks it; one key alone is the default (one test per vendor, as the feature's outline); `ANKKA_MODEL_DEFAULT=fast` with no `fast` is refused naming the variable and the value; `ANKKA_MODEL_NAME` sets `anthropic`'s model; a script beside a key is nameable as `scripted` and not the default
- [ ] T035 [P] [US2] Add `model: Option[String]` to `ProcessDouble.plan(...)` in `sidecar/src/test/scala/…/ankka/sidecar/ProcessDouble.scala` (lines ~310–327) so a double's plan can name a model; default `None`
- [ ] T036 [US2] Add to `sidecar/src/test/scala/…/ankka/sidecar/RemoteAgentSuite.scala` tests with `Models(Map("anthropic" -> scriptA, "openai" -> scriptB), default = Some(scriptA))`: `an agent naming the model provider openai is answered by it` (scriptB consumed, scriptA's `callCount` 0); with `default = None, noDefault = Some(msg)`: `a service written in another language with two model providers and no default model refuses only an agent that names none` (the unnamed plan is `Unavailable` with the message naming `ANKKA_MODEL_DEFAULT`, `anthropic` and `openai`; the named plan answers); with one provider: it is the default
- [ ] T037 [US2] Add to `sidecar/src/test/scala/…/ankka/sidecar/RemoteAutonomousAgentSuite.scala`: `an autonomous agent of a service written in another language names the model provider openai` (a definition with `model = Some("openai")` iterates on scriptB only)
- [ ] T038 [US2] Add `agent.names-a-model` to `sidecar/src/test/scala/…/ankka/sidecar/conformance/ConformanceSuite.scala`: `POST /conformance/ask-second/{id}` is answered by a second scripted model named `second`, the first model's `callCount` unchanged; make `ConformanceTarget` (`sidecar/src/test/scala/…/ankka/sidecar/conformance/ConformanceTarget.scala`, the `Models.only` sites at ~522, ~530, ~711, ~719) pass `Models(Map("scripted" -> model, "second" -> second), Some(model))`; expose `second` on the suite as `model` is (reset in `beforeEach`)
- [ ] T039 [P] [US2] Add the `ask-second` route and an assistant naming `second` to `sidecar/src/test/scala/…/ankka/sidecar/conformance/ConformanceReference.scala` (beside `Assistant` at ~739)
- [ ] T040 [P] [US2] Add the same route and `with_model("second")` to `sdks/python/examples/shopping_cart/conformance.py` (the ask routes at ~754–766, `ConformanceAssistant` at ~386)
- [ ] T041 [P] [US2] Add the same route and `.model("second")` to `sdks/typescript/examples/shopping-cart/conformance.ts` (`ConformanceAssistant` at ~612)
- [ ] T042 [P] [US2] Add the same route and `.model("second")` to `sdks/rust/examples/shopping-cart/src/conformance.rs` (`ConformanceAssistant::ask` at ~1011)
- [ ] T043 [P] [US2] Document `agent.names-a-model` in `specs/009-polyglot-runtimes/contracts/conformance.md` beside the other `agent.*` cases
- [ ] T044 [US2] Run the conformance suite for every target: `sbt 'sidecar/testOnly *ConformanceSuite -- *agent.*'` in process, `cd sdks/python && uv run conformance`, `cd sdks/typescript && npm run conformance`, `cd sdks/rust && ./conformance.sh`; fix what fails

**Checkpoint**: a Python, TypeScript or Rust service names `openai` or `anthropic`, the sidecar picks correctly, every refusal is by message.

---

## Phase 5: User Story 3 — Any compatible endpoint, by name, including one on the laptop (Priority: P1)

**Goal**: the variable family configures a provider under a name the developer chose, with no key; the same in Scala by constructor; logs, failures and usage say that name; the refusals at start name the variable.

**Independent Test**: `sbt 'sidecar/testOnly *ModelsSuite *RemoteAgentSuite'`, `sbt 'agent/testOnly *AnthropicCompatibleSuite *OpenAiProviderSuite'`, and `ANKKA_LOCAL_MODEL_URL=… sbt 'agent/testOnly *LocalModelServerSuite'`.

- [ ] T045 [US3] Extend `Models.fromEnv` in `sidecar/src/main/scala/…/ankka/sidecar/Models.scala`: parse every `ANKKA_MODEL_<NAME>_<SETTING>` with `SETTING ∈ {PROTOCOL, URL, MODEL, KEY, SHAPE, PRESET}` into the config of `<name>` (lower-cased), merged over a shorthand of the same name; `ANKKA_MODEL_NAME` and `ANKKA_MODEL_ANTHROPIC_MODEL` are one setting, refused when both given and different, naming both; any other `ANKKA_MODEL_*` variable (not `NAME`, `SCRIPT`, `DEFAULT`) is refused naming it; the variable's full name is prefixed to every problem `resolve` reports. Build the shorthand and setting names from the `ANKKA_MODEL_` prefix plus suffixes, never as a literal collection of full names, or `PlatformDeclarationSuite`'s literal scan fails the build (analysis I8)
- [ ] T046 [US3] Add to `sidecar/src/test/scala/…/ankka/sidecar/ModelsSuite.scala`, one test per example row of the three refusal outlines in `features/agents/compatible-endpoints.feature` and the override scenarios, asserting the exact message: missing protocol / model / address; the six reserved or malformed names; `grpc` protocol, `not a url`, `no-such-preset`, unknown default; `ANKKA_MODEL_OPENAI_URL` beside `OPENAI_API_KEY` yields one `openai` provider at that address; `ANKKA_MODEL_NAME` and `ANKKA_MODEL_ANTHROPIC_MODEL` differing refused; `a variable of the model provider family that is not a setting is refused at the service's start` (`ANKKA_MODEL_FAST_URLS`, the message naming it); four variables with no key give a keyless provider named `local` in protocol `openai` at the address for the model
- [ ] T047 [US3] Add to `sidecar/src/test/scala/…/ankka/sidecar/RemoteAgentSuite.scala`, against `ModelStandIn` configured through `Models.fromEnv` with `ANKKA_MODEL_LOCAL_{PROTOCOL,URL,MODEL}`: `a model provider is configured by name from a model protocol, an address, a model and no key` (the stand-in's request names the model, path `/v1/chat/completions`, `Bearer none`); `the model provider's name is the service's, in logs, failures and model usage` (stand-in stopped: the caller is `Unavailable` naming `local`, the captured log names `local` and never `groq`/`openai`; stand-in answering: the session's `usageByProvider` holds `local`); `a model provider whose address reports no model usage is recorded as none` (`reportNoUsage`, usage zero under `local`); `a setting a model provider's address does not accept is withheld, never a failed call` (the three outline rows: effort above the quirks' highest is clamped, `thinking` off on a bare endpoint sends no effort, a temperature is sent)
- [ ] T048 [P] [US3] Add `a Scala service builds a model provider from the same four values` to `modules/testkit/src/test/scala/…/ankka/testkit/ModelProvidersSuite.scala`: `ModelProviders.named("local", protocol = Some(OpenAi), url = Some(standIn.address), model = Some("qwen3:8b"))` answers an agent and reports `name == "local"`
- [ ] T049 [P] [US3] Write `modules/agent/src/test/scala/…/ankka/agent/LocalModelServerSuite.scala` gated by `ANKKA_LOCAL_MODEL_URL` (and `ANKKA_LOCAL_MODEL_NAME`, default `qwen3:8b`) with `assume`: one turn answered, one declared tool run with parseable arguments and its result round-tripped, usage recorded (zero accepted); the Anthropic protocol likewise when `ANKKA_LOCAL_MODEL_PROTOCOL=anthropic`; asserts plumbing, never the answer's content
- [ ] T050 [US3] Add the local-suite gate variables and the new suites to the `Commands` block of `CLAUDE.md` (`OpenAiProviderSuite`, `OpenAiProviderLiveSuite` skipping without `OPENAI_API_KEY`, `LocalModelServerSuite` skipping without `ANKKA_LOCAL_MODEL_URL`, the `<Preset>LiveSuite`s), and ensure `Test / javaOptions` or the environment forwards nothing new (these read `System.getenv`, not `-D`)

**Checkpoint**: Ollama on a laptop answers an agent in every language through four variables or one constructor; every bad configuration is refused by name.

---

## Phase 6: User Story 4 — One session continues across providers (Priority: P2)

**Goal**: a session begun on one provider is continued by another with its tool calls and results; usage is summed per provider; a constrained endpoint gets rewritten ids while the session keeps the model's.

**Independent Test**: `sbt 'testkit/testOnly *ModelProvidersSuite'` session cases and `sbt 'agent/testOnly *OpenAiProviderSuite'` id cases.

- [ ] T051 [US4] Add to `modules/testkit/src/test/scala/…/ankka/testkit/ModelProvidersSuite.scala`: `an agent on one model provider continues a session another model provider began` (turn on `anthropic` with a tool call and result; the `openai` script's `lastRequest.messages` holds the user message, the assistant's tool call and the `ToolResults`); `a tool call one model provider made is sent to the other with its result` (the call's arguments as given, followed by its result under the same id); `a session's model usage is both model providers' added together` (scripted usages `TokenUsage(10,5)` and `TokenUsage(7,3)`: `history.usage` is the sum, `history.usageByProvider` holds each under its name, and each recorded `AiMessage` carries `provider` and `model`)
- [ ] T052 [US4] Add to `modules/agent/src/test/scala/…/ankka/agent/openai/OpenAiProviderSuite.scala`: `a tool call's id is rewritten for a model provider that constrains it and the session keeps the model's`: a request replaying `toolu_01ABC` through `OpenAiProvider.at(..., quirks = Presets.named("mistral").get.quirks)` reaches the stand-in with a nine-alphanumeric id on both the `tool_calls` entry and the `tool` message, and the `ModelRequest` handed in is unchanged; and that ids the stand-in returns are kept as given
- [ ] T053 [US4] In `modules/agent/src/main/scala/…/ankka/agent/AgentLoop.scala`, confirm `PromptReplay` (unchanged) hands another provider's recorded ids through untouched and that `ChatMessage.Assistant` built from history carries `providerState = None`; add a comment at `recorded(...)` saying provider state is dropped there on purpose

**Checkpoint**: two providers share one session; usage is per provider; Mistral-shaped ids never reach the journal.

---

## Phase 7: User Story 5 — Blueprints and autonomous agents choose a provider by name (Priority: P2)

**Goal**: a blueprint registers `openai` beside the runtime's default and its workers choose; an autonomous definition names a provider directly.

**Independent Test**: `sbt 'testkit/testOnly *ModelProvidersSuite'` blueprint and autonomous cases.

- [ ] T054 [US5] Add to `modules/testkit/src/test/scala/…/ankka/testkit/ModelProvidersSuite.scala` (the scripted providers named `openai` and `anthropic` keep the fail-fast path through T010's structural check): `a blueprint runs one worker on each model provider` (a two-worker blueprint, `research.model("openai")` registered through `BlueprintRegistry.models("openai" -> scriptB)`, `write` on `default`; after `awaitRun` each script's `requests` are its worker's only); `an autonomous agent's definition names the model provider openai` (`define(...).model(scriptB)`; every iteration's recorded `AiMessage.provider` is `openai` and scriptA's `callCount` is 0)
- [ ] T055 [US5] Verify `BlueprintRegistry.models` in `modules/agent/src/main/scala/…/ankka/agent/blueprint/BlueprintRegistry.scala` accepts any `ModelProvider` name but `default` (no change expected) and that `RunWorker`'s usage accounting (`modules/agent/src/main/scala/…/ankka/agent/blueprint/RunWorker.scala` ~224) still reads `history.usage`; add nothing unless a test fails

**Checkpoint**: nothing new to learn for blueprints and definitions; both proven.

---

## Phase 8: User Story 6 — Reasoning and tools together on OpenAI's own models (Priority: P2)

**Goal**: a tool call made while the model reasons is sent back with the provider state the model asked to keep; the state is never recorded; the next turn starts clean.

**Independent Test**: `sbt 'agent/testOnly *OpenAiProviderSuite -- *reasons*'` with `requireReasoningReplay()` and the two `ModelProvidersSuite` cases.

- [ ] T056 [US6] In `modules/agent/src/main/scala/…/ankka/agent/AgentLoop.scala` `step`, build the assistant message as `ChatMessage.Assistant(response.text, response.toolCalls, response.providerState)` on both the tool-round and the waiting branches; the `Answer` branch's `Assistant` carries none (the turn ends); `recorded(...)` ignores it (T053's comment); `resume` builds from recorded messages and therefore carries none
- [ ] T057 [US6] In `modules/agent/src/main/scala/…/ankka/agent/autonomous/IterationLoop.scala`, keep the last response's `providerState` in a worker-local `var` and, when building the next iteration's request, attach it to the replayed last `ChatMessage.Assistant` whose tool call ids equal the response's; clear it on any other path (a restart has none by construction)
- [ ] T058 [US6] Add to `modules/agent/src/test/scala/…/ankka/agent/openai/OpenAiProviderSuite.scala` with `standIn.requireReasoningReplay()`: `a tool call made while the model reasons is sent back with the provider state the model asked to keep`: a Responses turn with a tool call returns a `providerState`; a second request carrying that state on its `Assistant` is accepted (the stand-in sees the `reasoning` item before the `function_call` and the `function_call_output`); the same second request without the state is the 400 the stand-in promises, surfaced as `ModelCallFailed`; a state whose `provider` is another name is ignored
- [ ] T059 [US6] Add to `modules/testkit/src/test/scala/…/ankka/testkit/ModelProvidersSuite.scala` using `OpenAiProvider.at(..., shape = Responses)` against `ModelStandIn` with `requireReasoningReplay()`: the whole turn (tool call, result, answer) completes through the loop; `provider state is never recorded in the session` (the history's `AiMessage`s and the journal line hold no state field; the first request of the next turn carries no `reasoning` item in `input`)
- [ ] T060 [US6] Add to `ModelProvidersSuite` the interrupted-turn edge case: a tool requiring approval suspends the turn; after the decision the resumed request carries no provider state and the stand-in (without `requireReasoningReplay`) answers; document the cost in the limitations task (T088)

**Checkpoint**: OpenAI's own models reason with tools through ankka's stateless loop.

---

## Phase 9: User Story 7 — Presets for the endpoints ankka has tested (Priority: P3)

**Goal**: `ANKKA_MODEL_EU_PRESET=mistral` and a key is a whole configuration; every preset is configured from its name; the table in the documentation is generated from the same list.

**Independent Test**: `sbt 'agent/testOnly *PresetsSuite'`, `sbt 'sidecar/testOnly *ModelsSuite -- *preset*'`, `just docs-reference`.

- [ ] T061 [US7] Write `modules/agent/src/test/scala/…/ankka/agent/providers/PresetsSuite.scala`: `every preset the platform names is configured from its name` as one test per preset (the sixteen of FR-012 and of the feature's outline, asserted equal to `Presets.all.keySet`): `ProviderConfig(name = "p", preset = Some(p), url = Some(standIn.address), model = …, key = …)` resolves and the provider's request reaches the stand-in on the preset's shape's path, in the preset's protocol, for the preset's default model (or the given one); the `mistral` row applies the id rule; `openai` and `azure` withhold temperature; `bedrock` and `azure` without an address are refused "an address is missing"
- [ ] T062 [US7] Add to `sidecar/src/test/scala/…/ankka/sidecar/ModelsSuite.scala`: `a preset configures a model provider from a name and a key` (`ANKKA_MODEL_EU_PRESET=mistral`, `_KEY`: protocol openai, Mistral's address, its default model); `a preset that needs a key is refused at the service's start without one` (message names `ANKKA_MODEL_EU_KEY`); `a value given beside a preset overrides what the preset set` (`_MODEL=mistral-small-latest`); `a preset without a key is a model provider at an address that needs none` (`ollama` with `_MODEL`, `Bearer none`)
- [ ] T063 [P] [US7] Write one `<Preset>LiveSuite` per vendor preset that needs a key (`groq`, `mistral`, `deepseek`, `xai`, `gemini`, `openrouter`, `huggingface`) as a single parametrised file `modules/agent/src/test/scala/…/ankka/agent/providers/PresetLiveSuites.scala` (one class per preset, each `assume`-gated by `ANKKA_MODEL_<PRESET>_KEY`): a plain answer, a tool call round-trip, non-zero usage where the vendor reports it; `bedrock` and `azure` also need `ANKKA_MODEL_<PRESET>_URL`
- [ ] T064 [US7] Write `modules/agent/src/test/scala/…/ankka/agent/ModelPresetsReferenceSuite.scala` after `CliReferenceSuite`'s pattern: under `-Dankka.docs.update=true` it writes the generated block `model-presets` into `docs/reference/model-providers.md` from `Presets.all` (name, protocol, shape, address or "required", key, default model, efforts, temperature, id rule, notes); otherwise it asserts the block matches
- [ ] T065 [US7] Register the block in `mkdocs.yml` under `extra.docs.generated` as `model-presets`, kind `external`, on `reference/model-providers.md`, and add the suite to the `docs-reference` recipe in `justfile` (line ~133)

**Checkpoint**: sixteen presets, each proven offline, each vendor's proven live when its key is set, the table generated.

---

## Phase 10: User Story 8 — What each provider does with a setting is written down (Priority: P3)

**Goal**: the documentation presents every way to be given a provider, the default rule, the settings table per protocol and preset, the preset list, a local-model page, the limitations, and the routing of `OPENAI_`.

**Independent Test**: `just docs` and `sbt 'testkit/testOnly *ModelProvidersDocumentationSuite'`.

- [ ] T066 [P] [US8] Write `modules/testkit/src/test/scala/…/ankka/testkit/ModelProvidersDocumentationSuite.scala` after `BlueprintsDocumentationSuite`'s pattern, one test per scenario of `features/documentation/model-providers.feature`, reading the pages named below and asserting the strings each scenario promises (every preset name; every family variable; "platform setting"; the four settings per protocol; the limitation sentences)
- [ ] T067 [US8] Rewrite `## Registering agents and choosing a model` in `docs/build/agents.md` (lines ~525–575 and the process section ~718–727): every way a service is given a provider (the two shorthands, `fromEnv`/`withApiKey`/`at`, `ModelProviders.named`, the variable family, presets), the default rule with and without `ANKKA_MODEL_DEFAULT`, the keyless placeholder, a settings table (`effort`, `thinking`, `temperature`, `maxTokens` × Anthropic, OpenAI Responses, OpenAI Chat Completions) with a pointer to the per-preset table, the `fromEnv`-without-key behaviour, and the included `two-providers` sample (T031); update the Python/TypeScript effect table row at ~663
- [ ] T068 [P] [US8] Create `docs/reference/model-providers.md`: the variable family and shorthands (from `contracts/variables.md`), the default rule, the refusals at start and at call time, the per-protocol settings table, the generated `model-presets` block with prose naming every preset, the id-rewriting rule, provider state within a turn; add it to `mkdocs.yml` `nav` under Reference
- [ ] T069 [P] [US8] Create `docs/deploy/local-models.md`: Ollama, LM Studio and Docker Model Runner answering an agent in a few lines, in Scala (`ModelProviders.named`) and in Python/TypeScript/Rust (four variables or `_PRESET` plus `_MODEL` on a local `ankka run`), models known to call tools (`qwen3:8b` and one more verified at implementation), the `/v1/messages` alternative, llama.cpp's port note; add to `mkdocs.yml` `nav` beside `deploy/run-locally.md`
- [ ] T070 [P] [US8] Add to `docs/reference/limitations.md` under `## Components` (agents, ~228–383) the compatible-endpoint section: no refusal reason, no cache accounting where the endpoint reports none, tool quality that is the model's and its template's, reasoning state not carried across an interrupted turn or an autonomous agent's restart, no vendor hosted tools, no Gemini native API, no SigV4/Entra, no per-interaction model name
- [ ] T071 [P] [US8] Update `docs/reference/akka-divergences.md`: the summary row (~26) and `## Model settings follow current models` (~141–144) to say what `effort`, `thinking` and `temperature` do on both protocols
- [ ] T072 [P] [US8] Update `docs/reference/configuration.md` `### Agents and models` (~346–360): `OPENAI_API_KEY`, the family, `ANKKA_MODEL_DEFAULT`, the shorthand equivalences, the routing sentence gaining `OPENAI_`; link to `reference/model-providers.md`
- [ ] T073 [P] [US8] Update `docs/reference/service-descriptor.md`: the example at ~37 (optionally a second key), the routing table at ~341–346 gaining `OPENAI_` → sidecar, and the wasm section at ~348–353
- [ ] T074 [P] [US8] Update `docs/deploy/deploy-a-service.md` (~120, ~130, ~187, ~207), `docs/deploy/run-locally.md` (`## Give agents a model key` ~168–179), `docs/concepts/polyglot.md` (~90–92), `docs/reference/wasm-abi.md` (~258–261): `OPENAI_` beside `ANTHROPIC_`, `ANKKA_MODEL_DEFAULT`, and a sentence pointing to `deploy/local-models.md`
- [ ] T075 [P] [US8] Update `docs/concepts/agents.md` `## Model providers` (~104–110) and `docs/concepts/designing-agents.md` `## Choosing a model` (~149–156): two protocols, named providers, presets, the local story; "effort is the lever" on both vendors
- [ ] T076 [P] [US8] Update the SDK references: `docs/reference/scala-sdk.md` (Models row ~224: `OpenAiProvider`, `AnthropicProvider.at`, `ModelProviders.named`), `docs/reference/python-sdk.md` (~204, ~227), `docs/reference/typescript-sdk.md` (~243, ~265), `docs/reference/rust-sdk.md` (~244): `with_model`/`withModel`/`.model` name any configured provider
- [ ] T077 [P] [US8] Update `docs/build/testing.md` (~890–927): scripting the sidecar's model beside real providers, `ModelStandIn` for a Scala test of a provider at an address, the live and local suites' gates
- [ ] T078 [P] [US8] Update the skills: `tools/docs/skill/ankka-agents/SKILL.md` (~64–69, ~103–117), `tools/docs/skill/ankka-python/SKILL.md` (~60–73), `tools/docs/skill/ankka-typescript/SKILL.md` (~71, ~85), `tools/docs/skill/ankka-rust/SKILL.md`, `tools/docs/skill/ankka-deploy/SKILL.md` (~96); add `reference/model-providers.md` and `deploy/local-models.md` to the `pages:` lists of `ankka-agents` and `ankka-deploy`
- [ ] T079 [P] [US8] Change the text "Anthropic, or yours" in `docs/assets/diagrams/components.svg` (~53) to "Anthropic, OpenAI, or any compatible endpoint"
- [ ] T080 [US8] Run `just docs-sync` (included samples, skills rendered into `marketplace/plugins/ankka/skills/` and `ankka.g8/src/main/g8/.claude/skills/`), `just docs-reference`, then `just docs`; fix every coverage error until the build is clean

**Checkpoint**: the documentation says, per protocol and per preset, what each setting does; `ModelProvidersDocumentationSuite` green.

---

## Phase 11: Polish & Cross-Cutting

- [ ] T081 [P] Amend the landscape paragraph in `specs/052-model-providers/spec.md` Context (the "from GPT-5.4" sentence) to what research R2 found: GPT-5.x still calls tools with reasoning on Chat Completions; GPT-6 Astra, GPT-6.1 Sol and the codex models require Responses; and note llama.cpp's port in the Assumptions
- [ ] T082 [P] Add traps learned to `.claude/rules/agents.md` under a new `## Model providers` heading: the keyless placeholder token, `fromEnv` without a key being unavailable rather than a throw, provider state dropped at `recorded(...)` on purpose, the stand-in answering 418 when unscripted, `ANKKA_MODEL_NAME` and `ANKKA_MODEL_ANTHROPIC_MODEL` being one setting, and anything that cost time during implementation
- [ ] T083 [P] Check `.github/ci-coverage.py` claims every new file (new Scala under `modules/agent`, `sidecar`, `modules/testkit`; the two new docs pages; the skill copies) and that the `build` matrix's `rest` runner picks up the new agent suites without listing; run `python3 -I .github/ci-coverage.py` if it is runnable locally
- [ ] T084 [P] Confirm the `ankka init` templates need nothing: `ankka.g8/src/main/g8/src/main/scala/$package$/Main.scala` has no agent, and `cli/src/main/templates/*/service.json` carries no model; if a commented second-provider example fits the g8 `Main.scala` without adding an agent, add it; otherwise note in `specs/052-model-providers/plan.md` that the template is unchanged
- [ ] T085 Run `sbt scalafmtAll scalafmtSbt` and `sbt compile` (warning-free under `-Werror`), then `sbt -Dankka.cluster.tests=off test` under `caffeinate -i`; every suite that passed before passes after (SC-008), the live and local suites report skipped
- [ ] T086 Run `just features` (the living features, the glossary and the specs still agree: the spec references every scenario; the checker exits 0)
- [ ] T087 Walk `specs/052-model-providers/quickstart.md` end to end: the offline block, one live suite if a key is at hand, the local suite against Ollama if it is running, the Python two-provider descriptor against a local sidecar; record what was run and what was skipped in the pull request description
- [ ] T088 Review the limitations text (T070) against what T060 and T057 actually do, and make the documentation's "at some cost to the answer" sentence match the stand-in-proven behaviour

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: T001 first (nothing compiles against the SDK before it); T003–T006 in parallel with T002.
- **Foundational (Phase 2)**: T007–T010 first (the values); T011–T014 in parallel; T015 after T011–T013; T019 and T020 after T012 and T014; T016 after T015, T019, T020; T021–T022 with T020; T023 any time after T007; T024–T026 after T020–T023. Blocks every story.
- **US1 (Phase 3)**: after Phase 2. T030 and T031 parallel with T028–T029.
- **US2 (Phase 4)**: after Phase 2; T032 before T033–T034 and T036–T038; T035 and T039–T043 parallel; T044 last.
- **US3 (Phase 5)**: after US2's T032 (it extends `fromEnv`); T046–T049 after T045; T050 any time.
- **US4 (Phase 6)**: after Phase 2 and US1's suite file (T028). T052 needs T020's id rewriting.
- **US5 (Phase 7)**: after T028.
- **US6 (Phase 8)**: after Phase 2; T056–T057 before T058–T060.
- **US7 (Phase 9)**: after Phase 2 and US3's T045 (the sidecar's preset parsing); T064–T065 before Phase 10's T080.
- **US8 (Phase 10)**: after every code story it describes; T066 first (it should fail until the pages exist); T080 last.
- **Polish (Phase 11)**: after everything.

### User Story Dependencies

- US1, US2, US6 depend only on Phase 2.
- US3 extends US2's `Models.fromEnv` (T032).
- US4, US5 extend US1's suite file and need nothing else.
- US7 needs US3's parsing for the sidecar half; its agent half (T061, T063, T064) needs Phase 2 only.
- US8 documents all of them.

### Parallel Opportunities

- Phase 1: T003, T004, T005, T006 together.
- Phase 2: T011, T012, T013, T014 together; then T017, T018, T025, T026 together once the adapters exist.
- US2: T035, T039, T040, T041, T042, T043 together after T038's case is named.
- US8: T068–T079 together; T066 before them.
- Across stories after Phase 2: US1, US2 and US6 can proceed on separate files at once; US4 and US5 only add tests to `ModelProvidersSuite`, so sequence them with US1.

---

## Parallel Example: Phase 2

```bash
# The providers package, four files at once:
Task: "Create ModelProtocol.scala"            (T011)
Task: "Create Quirks.scala"                   (T012)
Task: "Create Preset.scala and Presets.scala" (T013)
Task: "Create ToolCallIds.scala"              (T014)

# Then, once T020–T023 compile, the suites at once:
Task: "ProviderConfigSuite"       (T017)
Task: "ToolCallIdsSuite"          (T018)
Task: "AnthropicCompatibleSuite"  (T025)
Task: "OpenAiProviderLiveSuite"   (T026)
```

## Parallel Example: User Story 2

```bash
Task: "ProcessDouble.plan(model)"                      (T035)
Task: "ConformanceReference ask-second route"          (T039)
Task: "Python conformance route"                       (T040)
Task: "TypeScript conformance route"                   (T041)
Task: "Rust conformance route"                         (T042)
Task: "conformance.md"                                 (T043)
```

---

## Implementation Strategy

### MVP First (User Story 1)

1. Phase 1 and Phase 2: the SDK, the routing rule, the values, the factory, the stand-in, both adapters, the offline suites.
2. Phase 3: a Scala service on two vendors, proven with scripted providers and the stand-in.
3. Stop and validate: `sbt agent/test`, `sbt 'testkit/testOnly *ModelProvidersSuite'`, and `OPENAI_API_KEY=… sbt 'agent/testOnly *OpenAiProviderLiveSuite'` once.

### Incremental Delivery

1. US2 makes the feature real for Python, TypeScript and Rust (the sidecar's table and the conformance case).
2. US3 turns two vendors into the whole market and a laptop (the variable family, the refusals, the local suite).
3. US4, US5, US6 close the session, blueprint and reasoning stories on the same suites.
4. US7 fills and proves the preset table and generates its documentation.
5. US8 writes it all down; `ModelProvidersDocumentationSuite` says when it is done.
6. Polish: traps, CI coverage, the full run, the quickstart walk.

### Notes

- Every test named after a living scenario must fail before its feature is built; check it once.
- Message wording is the contract (`contracts/variables.md`); change the contract before the code when a message must change.
- `TestModelProvider.name` is `test`; wrap it to give a story's provider a vendor's name rather than changing it, so every existing suite keeps its wording.
- The k3s suites do not change in this feature.
