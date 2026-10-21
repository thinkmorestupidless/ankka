# Model providers

> Every way a service is given a model provider — a vendor's API, any server speaking either protocol, a preset — the sidecar's variables, the default, each setting per protocol, and every preset.

Source: https://docs.ankka.cloud/reference/model-providers/
A model provider is a model an ankka service talks to, under a name the service chose. ankka speaks two
wire protocols, and every provider is one of them pointed at an address:

- **Anthropic's Messages protocol**, spoken to Anthropic's own API and to any server that serves it.
- **OpenAI's protocol**, in one of two shapes. **Responses** is how OpenAI's own API is spoken, since its
  current models call tools while they reason only there. **Chat Completions** is how every other server
  is spoken, since every one of them serves it.

The name is what an agent's handler, an autonomous agent's definition and a blueprint's worker name, what
logs and failures say, and what a session's model usage is kept under. `anthropic` and `openai` are the
conventional names of the two vendors' own APIs, nothing more: a service may call its Ollama `local` and
its Groq `fast`.

A name is lowercase letters and digits, starting with a letter, so that the name upper-cased is its part
of a variable's name with nothing changed. `scripted`, `default`, `name` and `script` are reserved.

## In Scala

A Scala service builds each provider it uses and hands one to the runtime as the default.
`AnthropicProvider` is in `com.thinkmorestupidless.ankka.agent`, `OpenAiProvider` in
`com.thinkmorestupidless.ankka.agent.openai`, and `ModelProviders` in
`com.thinkmorestupidless.ankka.agent.providers`:

| Built with | What it is |
|---|---|
| `AnthropicProvider.fromEnv()` | Anthropic's API, with `ANTHROPIC_API_KEY`; named `anthropic` |
| `OpenAiProvider.fromEnv()` | OpenAI's API in the Responses shape, with `OPENAI_API_KEY`; named `openai` |
| `AnthropicProvider.withApiKey(key)`, `OpenAiProvider.withApiKey(key)` | the same, with a key given |
| `AnthropicProvider.at(name, address, model, key)` | Anthropic's protocol at any address |
| `OpenAiProvider.at(name, address, model, key, shape)` | OpenAI's protocol at any address, Chat Completions unless a shape is given |
| `ModelProviders.named(name, protocol, url, model, key, preset)` | either protocol, from the four values or a preset, with the same rules the sidecar applies |

A provider built from the environment whose key is not set still exists, under its name, and every call
to it fails `Unavailable`, naming the variable. A service with one vendor's key and not the other's
starts, and refuses only the work that names the missing one.

## On the sidecar

A service written in Python, TypeScript or Rust never holds a key: the sidecar runs the agent loop and
talks to the model. Its providers are configured by variables in the service descriptor:

| Variable | What it configures |
|---|---|
| `ANTHROPIC_API_KEY` | `anthropic`: the `anthropic` preset with this key |
| `ANKKA_MODEL_NAME` | the model of `anthropic`; `claude-opus-5` when unset |
| `OPENAI_API_KEY` | `openai`: the `openai` preset with this key |
| `ANKKA_MODEL_<NAME>_PROTOCOL` | the protocol of the provider `<name>`: `anthropic` or `openai` |
| `ANKKA_MODEL_<NAME>_URL` | its address |
| `ANKKA_MODEL_<NAME>_MODEL` | its model |
| `ANKKA_MODEL_<NAME>_KEY` | its key; an address that needs none is sent the placeholder `none` |
| `ANKKA_MODEL_<NAME>_SHAPE` | the shape on OpenAI's protocol: `responses` or `chat-completions` |
| `ANKKA_MODEL_<NAME>_PRESET` | a preset, below; anything given beside it overrides what it sets |
| `ANKKA_MODEL_DEFAULT` | which provider a plan that names none is answered by |
| `ANKKA_MODEL_SCRIPT` | `scripted`, a model answering from a script, for a test |

`ANTHROPIC_API_KEY` is `ANKKA_MODEL_ANTHROPIC_PRESET=anthropic` with `ANKKA_MODEL_ANTHROPIC_KEY`, and
`OPENAI_API_KEY` is `ANKKA_MODEL_OPENAI_PRESET=openai` with `ANKKA_MODEL_OPENAI_KEY`, so a variable of
the family for the same name overrides what the shorthand set: `ANKKA_MODEL_OPENAI_URL` beside
`OPENAI_API_KEY` sends OpenAI's protocol through a proxy. `ANKKA_MODEL_NAME` and
`ANKKA_MODEL_ANTHROPIC_MODEL` are one setting. An empty key is no key.

Every one of these variables is a platform setting: it reaches the sidecar's container only, a process
is never given it, and a module's `config` answers it absent. A handler names a provider with
`with_model("fast")` in Python, `withModel("fast")` in TypeScript and `.model("fast")` in Rust.

```json title="service.json"
{
  "name": "support",
  "service": {
    "image": "registry.example.com/acme/support:1.0.0",
    "hosting": "process",
    "protocol": "1.19",
    "env": [
      { "name": "ANTHROPIC_API_KEY", "secretKeyRef": { "name": "models", "key": "anthropic" } },
      { "name": "OPENAI_API_KEY", "secretKeyRef": { "name": "models", "key": "openai" } },
      { "name": "ANKKA_MODEL_DEFAULT", "value": "openai" },
      { "name": "ANKKA_MODEL_EU_PRESET", "value": "mistral" },
      { "name": "ANKKA_MODEL_EU_KEY", "secretKeyRef": { "name": "models", "key": "mistral" } }
    ]
  }
}
```

### Which is the default

- With one real provider configured, it is the default.
- With more than one, `ANKKA_MODEL_DEFAULT` names it. Without it, a plan that names no model is refused
  `Unavailable`, naming the variable and the providers, and every plan that names one is answered.
- `scripted` is the default only when it is the only provider; beside a real one it is still nameable.

A Scala service's default is the provider it hands `AgentRuntime.withDefaultModel`.

### What is refused when the sidecar starts

The sidecar refuses to start, naming the variable and the value, when a configuration cannot work: a
variable under `ANKKA_MODEL_` that is neither a shorthand nor a setting of the family (a misspelt
`ANKKA_MODEL_FAST_URLS`); a reserved or malformed name; a protocol other than `anthropic` or `openai`; a
shape other than `responses` or `chat-completions`, or a shape given for Anthropic's protocol; an
address that is not `http` or `https`; a preset that does not exist; a configuration with no protocol,
no address or no model once its preset is applied; a preset that needs a key given none;
`ANKKA_MODEL_NAME` and `ANKKA_MODEL_ANTHROPIC_MODEL` given different values; and an
`ANKKA_MODEL_DEFAULT` naming a provider that is not configured. A model name the endpoint does not know
is not refused at start: the first call fails as the endpoint answers.

## What each setting does

ankka's request carries the same settings to every provider: the most tokens, whether the model may
reason, how hard, and a sampling temperature. A setting an endpoint does not accept is withheld, never a
failed call. ankka never sends a tool choice: the model decides whether to call a tool.

| Setting | Anthropic's API | Anthropic's protocol elsewhere | OpenAI's Responses | OpenAI's Chat Completions |
|---|---|---|---|---|
| most tokens | `max_tokens` | `max_tokens` | `max_output_tokens` | `max_tokens` |
| thinking on | adaptive thinking | not sent | the effort asked for, or the model's own | the effort asked for, or nothing |
| thinking off | not sent | not sent | the lowest effort the endpoint offers | the lowest the preset knows, or nothing |
| effort | `output_config.effort` | not sent | the highest offered at or below what was asked | the same where the preset knows the values; as asked otherwise |
| temperature | withheld | withheld | withheld on OpenAI's own models, sent elsewhere | sent where the endpoint takes one |

Usage is kept in ankka's units on every protocol: cached prompt tokens are counted as cache reads beside
the prompt's other tokens, as Anthropic counts them, and an endpoint that reports no usage is recorded as
none.

## Presets

A preset names an endpoint ankka has tested: its protocol and shape, its address, whether it needs a key,
a default model where the vendor keeps a stable name for one, and what it is known to accept. Presets
are `openai`, `anthropic`, `ollama`, `lmstudio`, `llamacpp`, `docker-model-runner`, `vllm`, `groq`,
`mistral`, `deepseek`, `xai`, `gemini`, `openrouter`, `huggingface`, `bedrock` and `azure`. Where the
table says required, the value is given beside the preset.

| Preset | Protocol | Shape | Address | Key | Default model | Reasoning efforts | Temperature | Tool call ids | Notes |
|---|---|---|---|---|---|---|---|---|---|
| `anthropic` | anthropic | — | `https://api.anthropic.com` | required | `claude-opus-5` | adaptive thinking and effort | withheld | as given | Anthropic's own API; `ANTHROPIC_API_KEY` is its shorthand |
| `azure` | openai | responses | required | required | required | none, minimal, low, medium, high, xhigh, max | withheld | as given | `https://<resource>.openai.azure.com/openai/v1`, with an API key |
| `bedrock` | openai | chat-completions | required | required | required | as asked | sent | as given | `https://bedrock-runtime.<region>.amazonaws.com/openai/v1`, with a Bedrock API key |
| `deepseek` | openai | chat-completions | `https://api.deepseek.com` | required | required | as asked | sent | as given |  |
| `docker-model-runner` | openai | chat-completions | `http://localhost:12434/engines/v1` | none | required | as asked | sent | as given | from a container: `http://model-runner.docker.internal/engines/v1` |
| `gemini` | openai | chat-completions | `https://generativelanguage.googleapis.com/v1beta/openai/` | required | `gemini-flash-latest` | low, medium, high | sent | as given |  |
| `groq` | openai | chat-completions | `https://api.groq.com/openai/v1` | required | `openai/gpt-oss-120b` | as asked | sent | as given |  |
| `huggingface` | openai | chat-completions | `https://router.huggingface.co/v1` | required | required | as asked | sent | as given |  |
| `installation` | openai | chat-completions | the platform's | none | required | as asked | sent | as given | the installation's model server; the platform gives the address and the certificate |
| `llamacpp` | openai | chat-completions | `http://127.0.0.1:9931/v1` | none | required | as asked | sent | as given | older builds listen on `8080` |
| `lmstudio` | openai | chat-completions | `http://localhost:1234/v1` | none | required | as asked | sent | as given |  |
| `mistral` | openai | chat-completions | `https://api.mistral.ai/v1` | required | `mistral-large-latest` | as asked | sent | rewritten to 9 letters and digits | tool call ids are rewritten to nine letters and digits |
| `ollama` | openai | chat-completions | `http://localhost:11434/v1` | none | required | as asked | sent | as given | also serves Anthropic's protocol at `http://localhost:11434` |
| `openai` | openai | responses | `https://api.openai.com/v1` | required | `gpt-6.1-sol` | none, minimal, low, medium, high, xhigh, max | withheld | as given | OpenAI's own API; `OPENAI_API_KEY` is its shorthand |
| `openrouter` | openai | chat-completions | `https://openrouter.ai/api/v1` | required | required | as asked | sent | as given | a model is named `vendor/model` |
| `vllm` | openai | chat-completions | `http://localhost:8000/v1` | none | required | as asked | sent | as given |  |
| `xai` | openai | responses | `https://api.x.ai/v1` | required | required | low, high | sent | as given | Chat Completions is deprecated there |
`bedrock` and `azure` have no address of their own, since it names an account's region or resource;
`bedrock` takes a Bedrock API key as its bearer, and neither signs requests any other way. The local
servers name no model, since a server holds whatever was pulled into it. `xai`, `deepseek`, `openrouter`
and `huggingface` name none either, since their model names change faster than a release.

## Tool call ids

A session is ankka's, so it holds the ids the model gave, and a session begun on one provider may carry
ids another refuses. A provider whose endpoint constrains the shape of an id rewrites each one on the
way out, from a digest of itself, so a call and its result keep the same id in every request; the
session keeps the model's. Mistral's endpoint takes nine letters and digits.

## Reasoning within a turn

OpenAI's reasoning models return what they reasoned beside a tool call, and must be handed it back with
the tool's result. ankka keeps nothing on the vendor's side, so it carries that reasoning itself for the
rest of the turn as provider state: an opaque value on the model's answer, handed back on the turn's next
call and never recorded in the session. The next turn starts without it. A turn that waits for a person
to approve a tool goes on without it, and so does an autonomous agent's task after its instance
restarts; the protocol accepts that, at some cost to the answer.
