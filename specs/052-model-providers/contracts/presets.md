# Contract: the presets

One row per preset in `Presets.all`; the documentation's table is generated from the same values.
"Efforts" are the `reasoning_effort` values the quirks list, lowest first; "—" means none known,
so `effort` is sent only when set, as given. "Temp." is whether `temperature` is sent. "Ids" is a
tool call id rule. A vendor preset carries a default model only where the vendor keeps a stable name
for one (an alias it maintains, or a model every source lists as production, as of 2026-10-17); the
rest, like the local presets, require a model, so no stale guess ships as a default.

| Preset | Protocol | Shape | Address | Key | Default model | Efforts | Temp. | Ids | Notes |
|---|---|---|---|---|---|---|---|---|---|
| `openai` | openai | responses | `https://api.openai.com/v1` | required | `gpt-6.1-sol` | none, minimal, low, medium, high, xhigh, max (clamped per model by the API; `none` withheld unless listed for the model) | no | — | the vendor's own; `OPENAI_API_KEY` is its shorthand |
| `anthropic` | anthropic | — | `https://api.anthropic.com` | required | `claude-opus-5` | ankka's effort → `output_config.effort`; adaptive thinking sent | no | — | `ANTHROPIC_API_KEY` is its shorthand |
| `ollama` | openai | chat-completions | `http://localhost:11434/v1` | none | — (required) | — | yes | — | also serves `/v1/messages` and `/v1/responses`; `_PROTOCOL=anthropic` or `_SHAPE=responses` work |
| `lmstudio` | openai | chat-completions | `http://localhost:1234/v1` | none | — (required) | — | yes | — | key needed only when the server requires authentication |
| `llamacpp` | openai | chat-completions | `http://127.0.0.1:9931/v1` | none | — (required) | — | yes | — | older builds default to port 8080; override `_URL` |
| `docker-model-runner` | openai | chat-completions | `http://localhost:12434/engines/v1` | none | — (required) | — | yes | — | from a container: `http://model-runner.docker.internal/engines/v1` |
| `vllm` | openai | chat-completions | `http://localhost:8000/v1` | none | — (required) | — | yes | — | a key only when the server was started with one |
| `groq` | openai | chat-completions | `https://api.groq.com/openai/v1` | required | `openai/gpt-oss-120b` | — | yes | — | `stream_options.include_usage` honoured |
| `mistral` | openai | chat-completions | `https://api.mistral.ai/v1` | required | `mistral-large-latest` | — | yes | alphanumeric, 9 | ids rewritten (research R9) |
| `deepseek` | openai | chat-completions | `https://api.deepseek.com` | required | — (required) | — | yes | — | also serves Messages at `/anthropic` |
| `xai` | openai | responses | `https://api.x.ai/v1` | required | — (required) | low, high (as the vendor lists) | yes | — | Chat Completions is deprecated there |
| `gemini` | openai | chat-completions | `https://generativelanguage.googleapis.com/v1beta/openai/` | required | `gemini-flash-latest` | low, medium, high | yes | — | no Responses; a native Gemini adapter is a later feature |
| `openrouter` | openai | chat-completions | `https://openrouter.ai/api/v1` | required | — (required) | — | yes | — | the model names the vendor (`vendor/model`) |
| `huggingface` | openai | chat-completions | `https://router.huggingface.co/v1` | required | — (required) | — | yes | — | |
| `bedrock` | openai | chat-completions | — (required: `https://bedrock-runtime.<region>.amazonaws.com/openai/v1`) | required | — (required) | — | yes | — | the key is a Bedrock API key sent as a bearer; SigV4 out of scope |
| `azure` | openai | responses | — (required: `https://<resource>.openai.azure.com/openai/v1`) | required | — (required) | as `openai` | no | — | API-key bearer; Entra out of scope |

## Invariants the tests hold

- Every preset name is in the living feature's outline (`every preset the platform names is
  configured from its name`) and in the generated table.
- A preset with `address = None` or `defaultModel = None` makes the corresponding value required,
  with the message in contracts/variables.md.
- `PresetsSuite` configures each preset with its address overridden to the stand-in and asserts
  the request's shape, protocol, model and id rule.
- A preset's live suite (`<Preset>LiveSuite`, under `OpenAiProviderLiveSuite`'s pattern) runs only
  when `ANKKA_MODEL_<PRESET>_KEY` is set in the test's environment, and asserts a plain answer, a
  tool call round-trip and non-zero usage where the vendor reports it.
