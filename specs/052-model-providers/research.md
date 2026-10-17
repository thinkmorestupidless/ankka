# Research: Model Providers

*2026-10-17.* Every unknown the plan's Technical Context named, resolved. Sources are the SDK's
POMs and source on GitHub, OpenAI's developer docs, each vendor's docs, and this repository.

## R1. The OpenAI Java SDK as transport

- **Decision**: add `com.openai:openai-java` 4.83.0 (released 2026-10-16) to `modules/agent`,
  pinned in `project/Dependencies.scala` beside `anthropic`.
- **Rationale**: Apache-2.0 (POM `<licenses>` of `openai-java-core` and `openai-java-client-okhttp`);
  speaks both Responses (`client.responses().create` / `createStreaming`, `ResponseAccumulator`) and
  Chat Completions (`client.chat().completions().create` / `createStreaming`,
  `ChatCompletionAccumulator`); `OpenAIOkHttpClient.builder().baseUrl(...)`; `fromEnv()` reads
  `OPENAI_API_KEY` and `OPENAI_BASE_URL`. Minimum JDK 8. Transitive set: Jackson 2.18.9 (the repo
  already resolves Jackson higher, 2.21.x, which the SDK accepts: it requires ≥ 2.13.4), OkHttp 4.12,
  kotlin-stdlib 1.8 and kotlin-reflect 1.8.20, error-prone annotations, swagger-annotations, victools
  jsonschema-generator: all Apache-2.0. The Anthropic SDK already brings the same Stainless-generated
  shape (Kotlin, OkHttp, Jackson), so no new family of dependency enters the image.
- **Alternatives considered**: hand-rolled HTTP (owning SSE framing and two wire formats for no
  gain; the Anthropic provider made the same call); an adapter per vendor (every vendor named in
  the spec is one of two wire protocols; none ships a Java SDK worth adopting).
- **Verified 2026-10-17** (`sbt agent/evicted`, `agent/compile`, the exported classpath): the SDK
  adds `openai-java{,-core,-client-okhttp}` 4.83.0, `jsonschema-generator`, `-module-jackson` and
  `-module-swagger-2` 4.38.0, `swagger-annotations` 2.2.50 and `jackson-module-kotlin` 2.19.4; it
  shares with `anthropic-java` Kotlin 1.9.25, OkHttp 4.12.0, Okio 3.6.0 and the build's Jackson
  2.21.6. Every one is Apache-2.0. The only eviction warning is slf4j-api, which predates this
  feature. `jackson-module-kotlin` stays a minor behind databind; the offline suites build real
  clients, so a refusal by the SDK's Jackson check would show there.

## R2. Responses for OpenAI's own API; Chat Completions for everything else

- **Decision**: FR-002 stands. The `openai` preset is shape Responses; every other preset and any
  hand-given address is Chat Completions unless its preset says Responses.
- **Rationale**: the function-calling guide says GPT-6 Astra and GPT-6.1 Sol require Responses for
  tool calling and codex models are Responses-only; GPT-5.x still does tools with reasoning on Chat
  Completions, so the spec's Context overstated the cut-off ("from GPT-5.4") but the conclusion
  holds: the vendor's own API is spoken in Responses or the newest models cannot call tools. Ollama,
  LM Studio, llama.cpp, vLLM, Groq, OpenRouter and Hugging Face serve `/v1/responses` too, all
  non-stateful; Gemini's compatibility layer does not; xAI calls Chat Completions deprecated and
  recommends Responses. So the `xai` preset is shape Responses; the rest stay Chat Completions, the
  universally served shape, and a developer may set `_SHAPE=responses` where a server offers it.
- **Alternatives considered**: Chat Completions everywhere (fails the newest OpenAI models with
  tools); Responses everywhere (Gemini's layer does not serve it; a bare endpoint cannot be assumed to).

## R3. A keyless endpoint

- **Decision**: a provider with no key sends the placeholder bearer token `none`
  (`ModelProviders.NoKey`) on both protocols.
- **Rationale**: `OpenAIOkHttpClient.builder().build()` throws "At least one credential source must
  be specified" with no credential, and `.apiKey("")` builds but throws at request time. Ollama, LM
  Studio, Docker Model Runner and llama.cpp ignore the header; vLLM accepts any token unless
  `--api-key` is set. The Anthropic SDK's builder requires a key or an auth token likewise, and
  Ollama's `/v1/messages` ignores it. The documentation says the placeholder is sent.
- **Alternatives considered**: a custom OkHttp interceptor stripping the header (more code, and the
  servers do not care); refusing keyless configurations (defeats the local story).

## R4. Reasoning items and provider state

- **Decision**: in stateless Responses mode the provider keeps the response's whole `output` array
  (JSON) as the turn's provider state and replays it verbatim before the `function_call_output`
  items on the next call of the turn; it still sends `include: ["reasoning.encrypted_content"]`.
- **Rationale**: the reasoning guide: with `store: false` "preserve every output item … and replay
  the complete history"; the function-calling guide: "any reasoning items returned in model
  responses with tool calls must also be passed back with tool call outputs". Reasoning items now
  carry `encrypted_content` by default in stateless mode; the `include` value is accepted as legacy,
  so sending it costs nothing and helps a compatible server that still needs it. Replaying the
  array as returned keeps the reasoning item before its function_call item without ankka knowing
  the ordering rule, which the docs imply rather than state.
- **Alternatives considered**: `previous_response_id` (server-side state; the spec forbids it);
  persisting the state in the session (open question the spec answered no); keeping only reasoning
  items (then ankka must reconstruct the function_call items with their `id`s, which the API also
  wants matched).
- **Autonomous agents**: `IterationLoop` rebuilds each request from the session record, so the
  state is carried in memory between iterations of one worker run only: the loop keeps the last
  response's state and attaches it to the replayed assistant message whose tool call ids match.
  After a restart the next iteration runs without it, which the protocol accepts at some cost to
  the answer; the limitations page says so.

## R5. The default OpenAI model name

- **Decision**: `OpenAiProvider.DefaultModel = "gpt-6.1-sol"`, named in the documentation as
  `claude-opus-5` is for Anthropic; verified live by `OpenAiProviderLiveSuite` when a key is set.
- **Rationale**: the models page (October 2026) lists the GPT-6 family (`gpt-6-astra`,
  `gpt-6.1-sol`, `gpt-6-luna`) as the current flagship line; `gpt-6.1-sol` is the newest
  general-purpose model there and requires Responses for tools, which is the shape the preset
  speaks. A cheaper model is whoever runs the service's choice, as the Anthropic default's comment
  already says.
- **Alternatives considered**: `gpt-6-luna` (adds effort `none`, otherwise the same family);
  a GPT-5.x model (works on both shapes but is not the current line).

## R6. Settings on the OpenAI protocol

- **Decision**: effort maps `Low→low, Medium→medium, High→high, XHigh→xhigh, Max→max`, clamped to
  the highest the preset's quirks list; `thinking = false` sends the lowest the quirks list (`none`
  where offered, else `minimal`, else `low`), and nothing on an endpoint whose quirks list none;
  `temperature` is withheld on OpenAI's own reasoning models (the `openai` preset's quirk) and sent
  elsewhere; `maxTokens` is `max_output_tokens` (Responses) or `max_completion_tokens` (Chat
  Completions); a bare endpoint with no preset sends `reasoning_effort` only when `effort` was set.
- **Rationale**: the Chat Completions reference lists `reasoning_effort` values
  `none, minimal, low, medium, high, xhigh, max` and says not every model supports every value;
  `none` returns 400 on models without it. Reasoning models reject sampling parameters, so a
  temperature on the `openai` preset would fail every call that set one, as it did for Claude.
- **Alternatives considered**: sending everything and mapping 400s to withheld retries (two calls,
  and a failure the loop would see).

## R7. Usage and streaming

- **Decision**: Chat Completions streams with `stream_options.include_usage = true` where the
  quirks allow (the default for every Chat Completions preset); an endpoint that reports no usage
  is recorded as zero. Cached prompt tokens (`prompt_tokens_details.cached_tokens` /
  `input_tokens_details.cached_tokens`) are cache reads; cache writes are zero unless
  `cache_write_tokens` is reported.
- **Rationale**: both fields confirmed in the API references and the SDK models.
- **Alternatives considered**: counting tokens locally (a tokenizer per vendor; wrong by design).

## R8. The Anthropic protocol at another address

- **Decision**: `AnthropicProvider.at(name, address, model, key)` uses `baseUrl`; adaptive thinking
  and `output_config.effort` are sent at Anthropic's own address (preset `anthropic`) and withheld
  at any other unless the preset says otherwise; `temperature` stays withheld on the protocol.
- **Rationale**: Ollama, LM Studio, llama.cpp, Docker Model Runner and DeepSeek serve
  `/v1/messages` with tools and streaming, but `thinking: {type: adaptive}` and `output_config` are
  Anthropic's newest fields and a compatible server that does not know them may reject the call.
- **Alternatives considered**: sending and retrying (see R6).

## R9. Mistral's tool call ids

- **Decision**: the `mistral` preset's quirk `toolCallIds = Alphanumeric(9)`; `ToolCallIds` maps
  each id to nine base-36 characters of a SHA-256 digest of the original, so a call and its result
  agree within and across requests of a session, and the session keeps the model's ids.
- **Rationale**: `mistral-common`'s validator enforces `^[a-zA-Z0-9]{9}$` up to tokenizer v13,
  which relaxes it; nine alphanumerics is the safe common denominator (vLLM's Mistral parser does
  the same). A digest is deterministic, so replaying a session gives the same rewritten id each
  time without state.
- **Alternatives considered**: sequential ids per request (not stable across requests, which
  matters when a replayed call and a later result land in different requests).

## R10. Preset addresses

| Preset | Address | Notes |
|---|---|---|
| openai | `https://api.openai.com/v1` | Responses |
| anthropic | `https://api.anthropic.com` | Messages |
| ollama | `http://localhost:11434/v1` | also serves Messages and Responses |
| lmstudio | `http://localhost:1234/v1` | |
| llamacpp | `http://127.0.0.1:9931/v1` | the current README's default port (older builds: 8080); the docs say so |
| docker-model-runner | `http://localhost:12434/engines/v1` | needs TCP host access on; from a container `http://model-runner.docker.internal/engines/v1` |
| vllm | `http://localhost:8000/v1` | |
| groq | `https://api.groq.com/openai/v1` | |
| mistral | `https://api.mistral.ai/v1` | ids constrained |
| deepseek | `https://api.deepseek.com` | |
| xai | `https://api.x.ai/v1` | Responses (Chat Completions deprecated there) |
| gemini | `https://generativelanguage.googleapis.com/v1beta/openai/` | Chat Completions only; `reasoning_effort` cannot combine with Gemini's own thinking fields |
| openrouter | `https://openrouter.ai/api/v1` | |
| huggingface | `https://router.huggingface.co/v1` | |
| bedrock | none fixed: `https://bedrock-runtime.<region>.amazonaws.com/openai/v1`, the address is required | bearer is a Bedrock API key; SigV4 is out of scope |
| azure | none fixed: `https://<resource>.openai.azure.com/openai/v1`, the address is required | Responses; API-key bearer; Entra is out of scope |

Default models: none for the local presets (an arbitrary server has no default; the model is
required); vendor presets carry the vendor's current general-purpose model as read from its models
page at implementation and dated in `Presets.scala`, listed in the generated table. The live suite
per vendor is the check that a default still exists.

## R11. Where the variable family is parsed and validated

- **Decision**: `ProviderConfig` and its validation live in `modules/agent`; the sidecar's
  `Models.fromEnv` only turns variables into configs. Unknown `ANKKA_MODEL_*` variables are
  refused at start, naming them.
- **Rationale**: a Scala service, the sidecar and the test kit share one set of rules and one
  message per problem; a typo in a setting name (`_URLS`) would otherwise be a provider silently
  built without an address and refused for the wrong reason.
- **Alternatives considered**: parsing in the sidecar alone (the Scala side would then have its own
  rules, and FR-003's constructor would validate differently).

## R12. Recording the provider and model with a turn

- **Decision**: `SessionMessage.AiMessage` gains `provider: Option[String] = None` and
  `model: Option[String] = None`; `SessionHistory` gains
  `usageByProvider: Map[String, TokenUsage] = Map.empty`, folded from `AiMessageAdded` when the
  message names a provider. Both are additive with defaults, so a journal written before this
  feature reads unchanged; `SessionMemoryCompatibilitySuite`'s golden lines gain the fields.
- **Rationale**: the clarification session chose provider name and model name; the event already
  carries the turn's usage with its last AI message, so keying the sum by the message's provider
  needs no new event. `TokenUsage` stays vendor-neutral.
- **Alternatives considered**: a new event `ModelUsageAdded(provider, model, usage)` (a second
  record of the same tokens); per-message usage on `AiMessage` (the loop records one usage per
  batch, not per message).

## R13. The loopback stand-in

- **Decision**: `ModelStandIn` in the agent module's main scope: a JDK `HttpServer` on
  `127.0.0.1:0` serving `/v1/messages`, `/v1/responses` and `/v1/chat/completions`, scripted from
  ankka's own `ModelResponse` values (so a test says "answer with a tool call" once and the
  stand-in renders it in whichever shape is asked), recording every request as parsed JSON,
  streaming as SSE when `stream: true`, and configurable to refuse a Responses tool result that
  does not bring back the reasoning item it issued (FR-017), to report no usage, to reject a
  setting with a 400, and to hang.
- **Rationale**: `JevProviderSuite`'s inline server is the pattern, but three suites in three
  modules need this one, and `agent` tests cannot see `testkit`'s `ScriptedService`; `TestMcpServer`
  set the precedent for a scripted server in main scope.
- **Alternatives considered**: WireMock or MockWebServer (a new dependency for what forty lines of
  the JDK's server do); recording real responses as fixtures (brittle, and they would not stream).

## R14. The documentation's preset table

- **Decision**: a generated block `model-presets` (kind `external`) on the new
  `docs/reference/model-providers.md`, written by `ModelPresetsReferenceSuite` under
  `-Dankka.docs.update=true` from `Presets.all`, run by `just docs-reference`; the coverage check
  then requires prose naming every preset.
- **Rationale**: SC-007 asks that the table be generated from, or checked against, the same list;
  the repository already does this for the CLI and the control plane's routes.
- **Alternatives considered**: a hand-written table checked by a test (two copies to keep in step).

## R15. A Scala provider built from the environment without its key

- **Decision**: `OpenAiProvider.fromEnv()` and `AnthropicProvider.fromEnv()` with the key unset or
  empty return a provider that exists under its name and fails every call with
  `ModelCallFailed(name, "<VARIABLE> is not set")`, logging one warning when built. The service
  starts; the agent that names it is answered `Unavailable`; the rest of the service is unaffected.
- **Rationale**: User Story 1 says both providers are constructed from the environment and "the
  service starts with either key missing and refuses only the work that needs the missing one";
  the runtime already applies that rule to an autonomous agent with no model ("a missing model
  refuses the work that needs one, not the service"), and the sidecar has always started without a
  key. `AnthropicProvider.fromEnv` throwing today is why the shopping cart sample reads the variable
  itself before choosing a model.
- **Alternatives considered**: keep throwing and document the workaround (the story's scenario
  cannot then be written as it stands; every sample keeps its guard); return `Option` (a signature
  change for every caller).
- **Consequence for SC-008**: a Scala service that crashed at start without a key now starts and
  fails its agent calls; no passing suite changes outcome, and the samples lose a guard.
