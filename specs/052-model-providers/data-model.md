# Data Model: Model Providers

Entities as the code will hold them. Nothing here is a database table; the one persisted change
is to the session memory journal, at the end.

## ModelProtocol and Shape

```
enum ModelProtocol: Anthropic | OpenAi
enum Shape: Responses | ChatCompletions          // only meaningful for OpenAi
```

Wire words (variables, presets): `anthropic`, `openai`; `responses`, `chat-completions`. Anything
else is an inconsistent configuration (FR-009).

## Quirks

What an endpoint is known to accept or need; carried by a preset, overridable by nothing (a
developer who needs other quirks gives the four values by hand and gets the bare endpoint's).

| Field | Type | Default for a bare endpoint | Meaning |
|---|---|---|---|
| `reasoningEfforts` | `Option[Vector[String]]` | `None` | the `reasoning_effort` values accepted, lowest first; `None`: send only when `effort` was set, as given |
| `acceptsTemperature` | `Boolean` | `true` | whether `temperature` is sent |
| `streamUsage` | `Boolean` | `true` | whether `stream_options.include_usage` is asked for (Chat Completions) |
| `toolCallIds` | `Option[IdRule]` | `None` | an id shape the endpoint constrains tool call ids to |
| `sendsThinking` | `Boolean` | `false` for a hand-given Anthropic address, `true` for the `anthropic` preset | whether adaptive thinking and `output_config.effort` are sent on the Anthropic protocol |

`IdRule`: `Alphanumeric(length: Int)`.

## Preset

```
Preset(
  name: String,                  // the wire name, e.g. "groq"
  protocol: ModelProtocol,
  shape: Shape,                  // ChatCompletions for Anthropic (ignored)
  address: Option[String],       // None: the address is required (bedrock, azure)
  keyRequired: Boolean,
  defaultModel: Option[String],  // None: the model is required (every local preset)
  quirks: Quirks,
  asOf: String                   // the date the default model and address were read
)
```

`Presets.all: Map[String, Preset]` holds the sixteen of FR-012. The generated documentation table
is rendered from it.

## ProviderConfig (as given) and ResolvedConfig

```
ProviderConfig(
  name: String,
  protocol: Option[ModelProtocol],
  shape: Option[Shape],
  url: Option[String],
  model: Option[String],
  key: Option[String],           // Some("") is treated as None (an empty key is unset)
  preset: Option[String]
)
```

`ProviderConfig.resolve: Either[Problem, ResolvedConfig]` applies the preset underneath the given
values and checks:

| Check | Problem names |
|---|---|
| name matches `^[a-z][a-z0-9]*$` | the name and the rule |
| name not in `{scripted, default, name, script}` | the name, "reserved" |
| preset, when given, exists | the preset value |
| protocol value is `anthropic` or `openai` | the value |
| shape value is `responses` or `chat-completions` | the value |
| shape given on the Anthropic protocol | refused as inconsistent |
| url, when given, parses as an absolute `http` or `https` URL | the value |
| protocol present after the preset | "a model protocol is missing" |
| url present after the preset | "an address is missing" |
| model present after the preset | "a model is missing" |
| key present when `keyRequired` and no key was given | "the variable that gives its key" (the sidecar fills the variable's name) |

```
ResolvedConfig(name, protocol, shape, url, model, key: Option[String], quirks)
```

A `None` key becomes the placeholder bearer token at construction (research R3).

## ModelProvider (unchanged trait) and the two adapters

| Adapter | Constructed from | `name` |
|---|---|---|
| `AnthropicProvider` | `fromEnv(model, defaults)`, `withApiKey(key, model, defaults)`, `at(name, address, model, key, defaults, quirks)`, `apply(client, model, defaults, name)` | the name given; `anthropic` by default |
| `OpenAiProvider` | `fromEnv(model, defaults)`, `withApiKey(key, model, defaults)`, `at(name, address, model, key, shape, defaults, quirks)`, `apply(client, model, shape, defaults, name)` | the name given; `openai` by default |
| `TestModelProvider` | `()` | `test` (the sidecar names it `scripted`) |

`OpenAiProvider.Defaults(maxTokens: Long = 16000, thinking: Boolean = true, effort: Option[Effort] = None)`
mirrors Anthropic's.

`ModelProviders.configure(resolved: ResolvedConfig): ModelProvider` picks the adapter by protocol.

## ProviderState

```
ProviderState(provider: String, value: String)
```

Opaque. `ModelResponse.providerState: Option[ProviderState]` is what a provider attaches;
`ChatMessage.Assistant.providerState: Option[ProviderState]` is where the loop carries it. Never
serialized: `ChatMessage` is not a journal type, and `SessionMessage.AiMessage` has no such field.
A provider ignores a state whose `provider` is not its own name.

Lifecycle within a request agent's turn:

```
model answers with state S ──► Assistant(text, calls, Some(S)) appended to progress.messages
        │                              │
        │                      tools run; ToolResults appended
        │                              │
        └──────────────────────► next request of the same turn carries Assistant(..., Some(S))
                                       │
                         turn ends, suspends or fails ──► recorded(...) drops S
```

An autonomous agent's `IterationLoop` keeps the last response's state in memory for the next
iteration of the same worker run and attaches it to the replayed assistant message whose tool call
ids match; after a restart there is none.

## Session memory (persisted; additive)

`SessionMessage.AiMessage` gains two optional fields:

| Field | Type | Default | Set by |
|---|---|---|---|
| `provider` | `Option[String]` | `None` | the loop and the iteration loop, from `provider.name` |
| `model` | `Option[String]` | `None` | from `provider.modelName` |

`SessionHistory` gains:

| Field | Type | Default | Folded from |
|---|---|---|---|
| `usageByProvider` | `Map[String, TokenUsage]` | `Map.empty` | `AiMessageAdded(message, usage)` when `message.provider` is `Some(p)`: `usageByProvider(p) += usage` |

Manifests (`session-messages`, `session-history`, `session-memory-event`) are unchanged. A journal
from before this feature reads with both fields absent, which jsoniter applies the defaults to; the
golden lines in `SessionMemoryCompatibilitySuite` gain the two fields so the diff is reviewed.

## The sidecar's Models

```
Models(
  providers: Map[String, ModelProvider],
  default: Option[ModelProvider],
  noDefault: Option[String]        // when default is None and providers.size > 1: the message
)                                  // naming ANKKA_MODEL_DEFAULT and the configured names
```

Default rule, in order: exactly one real provider → it; more than one → the one
`ANKKA_MODEL_DEFAULT` names (refused at start when it names none of them); else `None` with
`noDefault`. `scripted` counts as real only when it is the only provider.

## Tool call id rewriting

`ToolCallIds.rewrite(messages: Vector[ChatMessage], rule: IdRule): Vector[ChatMessage]` returns
the same messages with every `ToolCall.id` and `ToolResult.callId` replaced by
`ToolCallIds.under(rule)(id)`: for `Alphanumeric(n)`, the first `n` base-36 characters of
SHA-256(id), lower-cased. An id already of the rule's shape maps to itself. Deterministic, so the
same original maps the same in every request.
