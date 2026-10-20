# Contract: the preset `installation`

How a service names an installation model, on a sidecar and in Scala, and what it is told.
Decisions are in [research.md](../research.md) (R5). Builds on feature 052's named providers and
presets; the variable names below are 052's family.

## On a sidecar (Python, TypeScript, Rust)

```json
"env": [
  { "name": "ANKKA_MODEL_LOCAL_PRESET", "value": "installation" },
  { "name": "ANKKA_MODEL_LOCAL_MODEL",  "value": "qwen3:0.6b" }
]
```

and `with_model("local")` / `withModel("local")` / `.model("local")` on the agent, or
`ANKKA_MODEL_DEFAULT=local` when more than one provider is configured.

What the preset sets:

| | |
|---|---|
| protocol, shape | `openai`, Chat Completions |
| address | `${ANKKA_MODELS_ADDRESS}/v1`, from the platform's variable |
| key | none; a `Authorization` header is never sent |
| client | the sidecar's `RotatingTls` at the service mount: `sslSocketFactory` and `trustManager` from `contextRequiring("ankka://platform/models")`, so the call presents the service's certificate and accepts only the model server's |
| settings | `temperature` sent; `effort` sent as `reasoning_effort` when set; `maxTokens` sent; `thinking` off sends nothing |
| usage | as the server reports it; no cache columns |

## Refusals at the sidecar's start

| When | Text |
|---|---|
| `ANKKA_MODEL_LOCAL_URL` set beside the preset | `ANKKA_MODEL_LOCAL_URL: an installation model takes no address` |
| `ANKKA_MODEL_LOCAL_KEY` set beside the preset | `ANKKA_MODEL_LOCAL_KEY: an installation model takes no key` |
| no `ANKKA_MODEL_LOCAL_MODEL` | `ANKKA_MODEL_LOCAL_MODEL: an installation model must be named` |
| no `ANKKA_MODELS_ADDRESS` in the environment | `model provider 'local': there is no installation model server here: this platform runs none, or the service runs outside an installation. Point a model provider at a server of your own instead; see docs.ankka.cloud/platform/installation-models` |

Each is a `StartRefusal`, so the pod's termination message reaches `services get`. A model that is
not served is not refused here: the operator holds the service back until it is, and a call to
a model removed since is the provider's failure at that call.

## At the first call

`InstallationModels.Capabilities` (agent, so a Scala service and the sidecar share it): `POST
/api/show` through the door, once per model per process, cached on success. Then, before any call
to the model:

| Request | Model lacks | `ModelCallFailed(name, …)` |
|---|---|---|
| tools declared | `tools` | `installation model "x" lacks tool calling` |
| `effort` set (thinking is a model's default, so asking for none needs nothing; FR-012) | `thinking` | `installation model "x" lacks thinking` |

The loop answers the caller `Unavailable` and an autonomous agent a failed iteration, as for any
provider's failure. A model with both capabilities is called with no further check. A `/api/show`
that fails is the provider's failure for that call and is not cached.

## Failures a caller meets

| Cause | What the loop answers |
|---|---|
| the door refuses the handshake (certificate missing or wrong) | `Unavailable`, `local: the model server refused the connection` |
| Ollama's queue is full | `Unavailable`, `local: server busy, please try again` |
| the call outlives the model timeout | `Timeout`, as for any provider |
| Ollama answers 400 for a request shape | `Unavailable` with Ollama's message |

## In Scala

```scala
val local = ModelProviders.installation("qwen3:0.6b")            // name "installation" by default
val named = ModelProviders.installation("qwen3:0.6b", name = "local")
AgentRuntime.withDefaultModel(local)
```

Reads `ANKKA_MODELS_ADDRESS` and the runtime's `RotatingTls`; refuses with the same texts when the
variable is absent, when the runtime starts. Outside an installation (`ANKKA_CLUSTER_MODE` unset)
the refusal is the FR-010 message above.

## What the process never sees

`ANKKA_MODELS_ADDRESS` and every `ANKKA_MODEL_*` variable are the platform's: a process is not
given them and a module's `config` answers them absent (`features/secrets/platform-settings.feature`
gains `ANKKA_MODELS_ADDRESS` as an example). The process holds no certificate, so even the address
would open nothing.
