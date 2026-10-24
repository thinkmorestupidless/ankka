# Run a model on your own machine

> Point an ankka agent at a model server on your own machine — Ollama, LM Studio, llama.cpp, Docker Model Runner — with no key, from a Scala service or a sidecar, and which models call tools.

Source: https://docs.ankka.cloud/deploy/local-models/
A model server on your machine is a model provider like any other: a name you choose, a protocol, an
address and a model, and no key. Every server below speaks OpenAI's protocol at a fixed address, and ankka
has a preset for each, so naming the preset and the model is the whole configuration.

| Server | Preset | Address |
|---|---|---|
| Ollama | `ollama` | `http://localhost:11434/v1` |
| LM Studio | `lmstudio` | `http://localhost:1234/v1` |
| llama.cpp's `llama-server` | `llamacpp` | `http://127.0.0.1:9931/v1`; older builds listen on `8080` |
| Docker Model Runner | `docker-model-runner` | `http://localhost:12434/engines/v1`, with TCP access to the host turned on |
| vLLM | `vllm` | `http://localhost:8000/v1` |

Start the server and pull a model that calls tools. With Ollama:

```bash
ollama pull qwen3:8b
ollama serve          # if it is not already running
```

## In a Scala service

Build the provider from the preset and the model, and hand it to the runtime or name it on a handler:

```scala
val laptop = ModelProviders.named("local", preset = Some("ollama"), model = Some("qwen3:8b"))

val onTheLaptop = AgentRuntime.withDefaultModel(laptop)
```

`ModelProviders` is in `com.thinkmorestupidless.ankka.agent.providers`. The name is yours: logs, failures and the session's model usage say `local`. Nothing is detected: the
provider exists because it was built, and a server that is not running fails the first call
`Unavailable`, naming `local`.

## In a Python, TypeScript, Rust or Go service

The agent loop runs in the sidecar, so the provider is configured on the sidecar's container, by two
variables:

```bash
ANKKA_MODEL_LOCAL_PRESET=ollama
ANKKA_MODEL_LOCAL_MODEL=qwen3:8b
```

The sidecar runs in a container, where `localhost` is the container itself. Give it the host's address
with `ANKKA_MODEL_LOCAL_URL=http://host.docker.internal:11434/v1`, which Docker Desktop provides and
the compose files map on Linux with `host-gateway`. Docker Model Runner is reached from a container at
`http://model-runner.docker.internal/engines/v1`.

With one provider it is the default, and every agent is answered by it. Beside a vendor's key, name it on
the agents that should use it with `with_model("local")` in Python, `withModel("local")` in TypeScript,
`.model("local")` in Rust or `WithModel("local")` in Go, and set `ANKKA_MODEL_DEFAULT` to say which answers the rest.

## Anthropic's protocol instead

Ollama, LM Studio, llama.cpp and Docker Model Runner also serve Anthropic's protocol. To speak it, give
the protocol and the server's root address:

```bash
ANKKA_MODEL_LOCAL_PROTOCOL=anthropic
ANKKA_MODEL_LOCAL_URL=http://host.docker.internal:11434
ANKKA_MODEL_LOCAL_MODEL=qwen3:8b
```

In Scala, `AnthropicProvider.at("local", "http://localhost:11434", "qwen3:8b")` does the same.

## Which models call tools

Whether a model calls a tool, rather than describing a call in its text, depends on the model and on its
chat template. Models known to call tools through Ollama's OpenAI endpoint include `qwen3:8b` and
`llama3.1:8b`. A model that writes a tool call as text has answered with text: the agent receives the
text, and no tool runs. Choose a model whose card lists tool use.

A local server reports usage in tokens, and reports no cached tokens; a session's usage counts what it
reports. Nothing else about the agent changes: memory, tools, guardrails, approvals and streaming behave as
they do with any provider. See [Model providers](../reference/model-providers.md) for every setting, and
[Limitations](../reference/limitations.md) for what a server of your own does not give.
