<picture>
  <source media="(prefers-color-scheme: dark)" srcset="assets/ankka-lockup-dark.svg">
  <img src="assets/ankka-lockup.svg" alt="ankka" width="400">
</picture>

[![maven central](https://img.shields.io/maven-central/v/com.thinkmorestupidless/ankka-core_3?label=maven%20central)](https://central.sonatype.com/artifact/com.thinkmorestupidless/ankka-core_3)
[![ci](https://github.com/thinkmorestupidless/ankka/actions/workflows/ci.yml/badge.svg)](https://github.com/thinkmorestupidless/ankka/actions/workflows/ci.yml)
[![license](https://img.shields.io/badge/license-Apache--2.0-blue)](https://www.apache.org/licenses/LICENSE-2.0)

A serverless application platform for agentic AI, built on the actor model — a
reimplementation of [Akka's](https://doc.akka.io/) component model in Scala 3 on
[Apache Pekko](https://pekko.apache.org/), with services in Scala or Python.

You write components; ankka supplies the runtime. Sharding, persistence, replay,
projections, durable orchestration, timers, HTTP and the agent loop are the platform's
problem, not yours. A control plane, an operator and a CLI deploy, expose, scale and observe
the result on Kubernetes.

```scala
final class ShoppingCartEntity(context: EventSourcedEntityContext)
    extends EventSourcedEntity[ShoppingCart, ShoppingCartEvent]:

  def emptyState: ShoppingCart = ShoppingCart.empty(context.entityId)

  def applyEvent(event: ShoppingCartEvent): ShoppingCart = event match
    case ItemAdded(item)        => currentState.addItem(item)
    case ItemRemoved(productId) => currentState.removeItem(productId)
    case CheckedOut             => currentState.onCheckedOut

  def addItem(item: LineItem): Effect[Done] =
    if currentState.checkedOut then effects.error("cart is already checked out", ErrorCode.Conflict)
    else effects.persist(ItemAdded(item)).thenReply(_ => Done)
```

## Documentation

**[docs.ankka.cloud](https://docs.ankka.cloud/)**, and the same
pages as Markdown in [`docs/`](docs/index.md):

- **[Get started](docs/get-started/install.md)** — install the tools
  (`brew install thinkmorestupidless/tap/ankka` for the CLI), write a first service in
  [Scala](docs/get-started/first-service-scala.md) or [Python](docs/get-started/first-service-python.md),
  and [deploy it to a local platform](docs/get-started/deploy-locally.md).
- **[Concepts](docs/concepts/architecture.md)** — how ankka works, the component model, and
  [designing a service](docs/concepts/designing-services.md).
- **[Build](docs/build/event-sourced-entities.md)**, **[Run and deploy](docs/deploy/run-locally.md)**,
  **[Observe and operate](docs/operate/local-console.md)**, **[Run the platform](docs/platform/install-local.md)**.
- **[Reference](docs/reference/cli.md)** — the CLI, the service descriptor, the control plane API,
  configuration, the SDKs, the sidecar protocol, and an honest list of [limitations](docs/reference/limitations.md).

For coding agents: the documentation ships as Agent Skills in every project made from the template and
in the Claude Code plugin published to [`ankka-marketplace`](https://github.com/thinkmorestupidless/ankka-marketplace), `ankka mcp` serves the platform's tools
over MCP, and the site publishes `llms.txt` and `llms-full.txt`. See
[Work with a coding agent](docs/get-started/coding-agents.md).

## Try it

```bash
docker compose up -d                 # Postgres: journal, views, timers, offsets
sbt shoppingCart/run                 # HTTP on :9000

curl -X POST localhost:9000/carts/c1/items \
  -H 'Content-Type: application/json' \
  -d '{"productId":"p1","name":"Widget","quantity":2}'
curl localhost:9000/carts/c1
```

A service of your own starts from the template — `ankka init orders` or
`sbt new thinkmorestupidless/ankka.g8 --name=orders` — as
[Your first service in Scala](docs/get-started/first-service-scala.md) describes.

## Why Pekko

Akka moved to the Business Source Licence. Pekko is the Apache 2.0 fork of Akka 2.6 with the same
APIs — typed actors, cluster sharding, persistence, projections, streams, HTTP — so ankka can offer
Akka's programming model with no licence constraint on who runs it.

## This repository

```
modules/core      effects, ids, codecs, component descriptors — no Pekko, no I/O
modules/sdk       the component API: entities, workflows, views, consumers, timers, client
modules/runtime   interprets effects: sharding, persistence, projections, timers
modules/http      endpoint DSL and server
modules/agent     model providers, session memory, function tools, the agent loop
modules/testkit   unit and integration test support
controlplane-api  descriptors, statuses and validation shared by the server and the CLI
controlplane      the control plane, built as an ankka application
crd               the AnkkaService custom resource — the contract, no ankka dependencies
operator          the Kubernetes operator: watches resources, owns the workloads
cli               the `ankka` command, over HTTP; `ankka mcp` for agents
protocol          the sidecar protocol: .proto files, ENCODING.md, the encoding fixtures
sidecar           the runtime booted from a discovery handshake, for a service in another language
sdks/python       the Python SDK, its testkits, and the sample cart ported to it
samples/          the shopping cart and the multi-agent planner
ankka.g8          the service template
docs              the documentation; tools/docs builds it
marketplace       the Claude Code plugin: the documentation as skills, and `ankka mcp`; pushed to ankka-marketplace on release
kustomization     the platform's manifests and the local deploy script
```

Contributing starts at [`CLAUDE.md`](CLAUDE.md) — the architecture, the build, and the traps that have
already cost debugging time — and, for the documentation,
[Writing documentation](docs/contributing/documentation.md).

```bash
sbt -Dankka.cluster.tests=off test   # everything but the Kubernetes suites; Docker required
just docs                            # check and build the documentation
```

## Licence

Apache 2.0, matching Pekko.
