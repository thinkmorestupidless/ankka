# Installation models

> The model server an installation can run for its services — what it is and is not, declaring the models it serves, how a service names one, who may call it, what a member is told when a model is not served, and its status.

Source: https://docs.ankka.cloud/platform/installation-models/
An installation can run a model server of its own: [Ollama](https://ollama.com/) and a keeper of
ankka's beside it, in the namespace `models-system`. The installation's administrator declares which
open-weight models it serves. The platform fetches each one once, keeps it loaded, and admits calls
to it only from the services of the installation. A call to an installation model never leaves the
installation: no prompt, reply or tool argument is sent to anyone outside it.

The model server is a component an administrator installs. An installation without it is unchanged:
nothing is rendered for it, and no service is given its address.

## What it is, and what it is not

The model server serves the models its declaration lists, by Ollama's tag, and nothing else.

- **It serves only declared models.** No service can fetch, remove or make a model: the door admits
  five paths (`/v1/chat/completions`, `/v1/models`, `/api/tags`, `/api/ps` and `/api/show`) and answers
  every other path that it is not served.
- **It is one server per cluster.** It runs one replica, and what it runs at once is a setting of the
  installation.
- **It serves chat completions.** It does not serve embeddings, and an agent's judgments are not made
  on it.
- **It serves no one outside the installation.** No route through the gateway reaches it.

## Declaring the models

The declaration is the ConfigMap `models-declaration` in `models-system`, whose `models.json` lists
tags:

```json title="models.json"
{"models": ["qwen3:0.6b", "llama3.2:1b"]}
```

A tag with no version is Ollama's `:latest`. The keeper reads the file within seconds of a change:

- **A tag added** is fetched, one model at a time in the order listed, then loaded and kept loaded.
  Its digest is recorded once it is served.
- **A tag removed** is removed from the model server and from its status. To fetch a model again,
  remove its tag, wait for it to leave the status, and add it back.
- **A tag whose fetch fails** is reported failed, with the model server's own words, and tried again
  after a minute, then two, doubling to an hour. A change to the declaration tries every failed fetch
  again at once.
- **A declaration that cannot be read** keeps the last one that could, and the status says why.

A model the server has fetched is kept on the server's volume, so a restarted model server serves
it again without fetching it.

## Naming a model from a service

A service names an installation model through a model provider whose preset is `installation`,
giving the model's tag:

```json title="service.json"
{
  "name": "assistant",
  "service": {
    "image": "registry.example.com/acme/assistant:1.0.0",
    "env": [
      { "name": "ANKKA_MODEL_LOCAL_PRESET", "value": "installation" },
      { "name": "ANKKA_MODEL_LOCAL_MODEL", "value": "qwen3:0.6b" }
    ]
  }
}
```

The platform supplies the address and admits the service by the certificate it already holds. A
descriptor gives neither: an address or a key beside the preset is refused, and a descriptor that
sets the address variable itself is refused when it is applied.

A Scala service builds the same provider in code, naming the model:

```scala
val local = ModelProviders.installation("qwen3:0.6b")
```

Outside an installation there is no model server, and both forms are refused when the service
starts: `there is no installation model server here: this platform runs none, or the service runs
outside an installation`. A provider of the developer's own takes its place there.

The model server says what each model can do, and an agent's first call that needs more is refused
before the model is asked, naming the model:

| The agent | The refusal |
|---|---|
| declares tools, and the model cannot call tools | `installation model "x" lacks tool calling` |
| asks for an effort, and the model cannot think | `installation model "x" lacks thinking` |

A service that names an installation model starts only once every model it names is served. Until
then, `ankka services get` shows why, in these words:

| The service is | When | The detail |
|---|---|---|
| failed | the model is not declared | `installation model "mistral:7b" is not served; served: qwen3:0.6b` |
| waiting | the model is still being fetched | `installation model "llama3.2:1b" is still being fetched` |
| failed | the model's fetch failed | `installation model "no-such-model:1b" failed: pull model manifest: file does not exist` |
| failed | the cluster has no model server | `this cluster has no model server` |
| failed | the descriptor gives an address or a key | `an installation model takes no address (ANKKA_MODEL_LOCAL_URL)` |
| waiting | the model server does not answer | `the model server is not answering` |

Instances already running are left alone while a service waits: the platform holds a new rollout
back, and never stops instances to do it. Once the model is served, the service starts, and its
status names each model it uses and the digest served.

## Who may call it

The model server's door is mutual TLS from the installation's service authority. A call is answered
when the certificate it presents names a service of the installation:

- **A service's platform program** holds the service's certificate and is admitted.
- **A developer's own program** beside the platform's, in a process-hosted or web-hosted service,
  holds no certificate, and its connection fails at the handshake.
- **The gateway, a mount, and a workload with no service certificate** are refused.
- **A certificate of another installation** fails at the handshake.

The caller's own credential is not passed on: the model server is told the calling service by its
certificate, and a call is counted against it.

## Its status

`ankka installation` lists the model server and every declared model:

```text
model server (home): https://models.models-system.svc:8443, ready
  qwen3:0.6b       served    7d4f3a1c
  llama3.2:1b      fetching
  no-such-model:1b failed    pull model manifest: file does not exist
```

An installation without the model server shows `model server: none`. The model server is read when
the status is asked for, through its door, so the list is what it serves at that moment.

The model server sends its metrics and a span for each call to the installation's telemetry store:
calls answered, failed and in flight, calls waiting for the server, the models declared, served and
loaded, failed fetches, and how long each pass took. A call that carries a trace context is a span
in that trace.

## Choosing models

A model that calls tools reliably is what an agent needs. `qwen3:0.6b` calls a declared tool and runs on
a processor, which makes it the model a local platform declares: it proves that a service reaches the
model server and is answered. On a processor a turn can take minutes, more so for a model that thinks
before it answers, so an agent people wait on needs an accelerator and a larger model. Every model
the declaration lists is fetched from Ollama's registry the first time, so the model server needs to
reach it then. An installation that cannot reach it supplies its own models and does not use this
server.

## Installing it

On the local platform the model server is opt-in, since its first start fetches a model of about
520 MB:

```bash
just deploy-models
```

On a cluster, the overlay lists the component and places and sizes it for the node it runs on, an
accelerator's for anything beyond the smallest models. The [cloud](install-cloud.md) and
[GKE](install-gke.md) pages show the overlay's part.
